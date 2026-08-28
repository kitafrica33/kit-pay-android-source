package com.kit.wallet.data.backup

import com.kit.wallet.data.messaging.AccountArchivedMessage
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.requiresModernMediaSchemaFence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

/**
 * What actually goes inside a Kit Pay backup, once [KitBackupArchive] has taken care of hiding it.
 *
 * The payload is a manifest followed by a stream of framed records and a terminator that states how
 * many there were. Nothing about it is loaded whole: messages are written from a lazy sequence and
 * read back one at a time, because the phones this runs on cannot hold years of conversation and a
 * backup that only works for light users is not a backup.
 *
 * Two deliberate properties:
 *  - The manifest names the account the history belongs to, and is readable before any message is,
 *    so a restore can refuse somebody else's backup before it imports a word of it.
 *  - The terminator carries the record count, so a stream that ends early is caught by the payload
 *    as well as by the archive's final-chunk flag. Belt and braces, because a silently short
 *    restore looks exactly like a real one.
 *
 * Records are length-prefixed and tagged, so a build that meets a record kind it has never heard of
 * skips it and keeps the messages it does understand. [SCHEMA] therefore only moves when the
 * framing itself changes, not when a new kind of record is added.
 *
 * [SCHEMA_MEDIA_PROVENANCE] is that framing move: every message body gains a trailing marker that
 * says whether this device proved the message's strict-v2 envelope binding. It is a schema bump
 * rather than a new record kind precisely so an older build refuses the whole backup up front —
 * skipping the marker would strip proven provenance and restore reserved-format text as an
 * unproven text-kind claim. Every backup containing v2/future-family text uses this fence even
 * when its marker is false; v1-only and ordinary histories keep schema 1 for older builds.
 */
internal object KitBackupPayload {
    private val MAGIC = "kit.backup.payload".toByteArray(Charsets.US_ASCII)
    private const val SCHEMA = 1
    private const val SCHEMA_MEDIA_PROVENANCE = 2

    private const val RECORD_END = 0
    private const val RECORD_MESSAGE = 1

    private const val MAX_IDENTIFIER_BYTES = 640
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_RECORD_BYTES = 128 * 1024
    private const val MAX_VERSION_BYTES = 64

    /**
     * Writes [manifest] and every message [messages] yields, then the terminator.
     *
     * The sequence is consumed lazily and exactly once, so the caller can page straight out of the
     * message archive without materialising it. Returns the number of messages written.
     *
     * [sink] is left open: it belongs to the caller, and closing it is what finalises the
     * surrounding archive.
     */
    fun write(
        sink: OutputStream,
        manifest: KitBackupManifest,
        messages: Sequence<AccountArchivedMessage>,
        withMediaProvenance: Boolean = false,
    ): Int {
        val data = DataOutputStream(sink)
        data.write(MAGIC)
        // The schema is committed before the lazy sequence is consumed, so the caller declares up
        // front whether any message carries a validation verdict. Undeclared verdicts fail the
        // write below instead of being silently stripped.
        data.writeInt(if (withMediaProvenance) SCHEMA_MEDIA_PROVENANCE else SCHEMA)
        data.writeBounded(manifest.ownerAccountId, MAX_IDENTIFIER_BYTES)
        data.writeLong(manifest.createdAt.toEpochMilli())
        data.writeBounded(manifest.writerVersion, MAX_VERSION_BYTES)
        var written = 0
        messages.forEach { message ->
            require(
                withMediaProvenance ||
                    (!message.mediaValidated && !requiresModernMediaSchemaFence(message.text)),
            ) {
                "A modern media-family message cannot enter a provenance-free backup"
            }
            val body = encodeMessage(message, withMediaProvenance)
            try {
                data.writeByte(RECORD_MESSAGE)
                data.writeInt(body.size)
                data.write(body)
            } finally {
                body.fill(0)
            }
            written++
        }
        data.writeByte(RECORD_END)
        data.writeInt(4)
        data.writeInt(written)
        data.flush()
        return written
    }

    /**
     * Reads the manifest, and only the manifest, leaving [source] positioned at the first record.
     *
     * Separate from [readMessages] so a restore can tell the user whose backup this is and when it
     * was made, and stop there if the answer is wrong.
     */
    fun open(source: InputStream): KitBackupManifest {
        val data = DataInputStream(source)
        val magic = ByteArray(MAGIC.size)
        data.readFullyOrFail(magic, "manifest")
        if (!MessageDigest.isEqual(magic, MAGIC)) {
            throw KitBackupFormatException("This backup does not contain Kit Pay message history")
        }
        val schema = data.readInt()
        if (schema != SCHEMA && schema != SCHEMA_MEDIA_PROVENANCE) {
            throw KitBackupFormatException("This backup was written by a newer version of Kit Pay")
        }
        return KitBackupManifest(
            ownerAccountId = data.readBounded(MAX_IDENTIFIER_BYTES),
            createdAt = Instant.ofEpochMilli(data.readLong()),
            writerVersion = data.readBounded(MAX_VERSION_BYTES),
            carriesMediaProvenance = schema == SCHEMA_MEDIA_PROVENANCE,
        )
    }

    /**
     * Hands every message in the payload to [onMessage], in the order it was written, and returns
     * how many there were.
     *
     * [source] must be positioned where [open] left it, and [manifest] must be what [open]
     * returned there: its schema fixes how each message body decodes. A record that does not
     * decode, or a count that disagrees with the terminator, fails the whole restore rather than
     * delivering part of a conversation.
     */
    fun readMessages(
        source: InputStream,
        manifest: KitBackupManifest,
        onMessage: (AccountArchivedMessage) -> Unit,
    ): Int {
        val data = DataInputStream(source)
        var read = 0
        while (true) {
            val kind = data.read()
            if (kind < 0) throw KitBackupIntegrityException("This backup ends before its history does")
            val length = data.readInt()
            if (length < 0 || length > MAX_RECORD_BYTES) {
                throw KitBackupFormatException("This backup declares an impossible record")
            }
            val body = ByteArray(length)
            data.readFullyOrFail(body, "record")
            try {
                when (kind) {
                    RECORD_MESSAGE -> {
                        onMessage(decodeMessage(body, manifest.carriesMediaProvenance))
                        read++
                    }
                    RECORD_END -> {
                        val declared = ByteBuffer.wrap(body).takeIf { length == 4 }?.int
                            ?: throw KitBackupFormatException("This backup ends malformed")
                        if (declared != read) {
                            throw KitBackupIntegrityException(
                                "This backup is missing part of its history",
                            )
                        }
                        return read
                    }
                    // A newer build's record kind. The framing is what matters, and it held.
                    else -> Unit
                }
            } finally {
                body.fill(0)
            }
        }
    }

    private fun encodeMessage(
        message: AccountArchivedMessage,
        withMediaProvenance: Boolean,
    ): ByteArray {
        val output = WipingByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeBounded(message.serverMessageId, MAX_IDENTIFIER_BYTES)
            data.writeBounded(message.clientMessageId, MAX_IDENTIFIER_BYTES)
            data.writeBounded(message.conversationId, MAX_IDENTIFIER_BYTES)
            data.writeBounded(message.sender.userId, MAX_IDENTIFIER_BYTES)
            data.writeBounded(message.sender.serverDeviceId, MAX_IDENTIFIER_BYTES)
            data.writeInt(message.sender.signalDeviceId)
            data.writeBounded(message.rosterRevision, MAX_IDENTIFIER_BYTES)
            val reply = message.replyToMessageId
            data.writeBoolean(reply != null)
            if (reply != null) data.writeBounded(reply, MAX_IDENTIFIER_BYTES)
            data.writeLong(message.sentAt.toEpochMilli())
            data.writeByte(deliveryCode(message.deliveryState))
            data.writeBounded(message.text, MAX_TEXT_BYTES)
            // Every schema-2 body carries the marker so the framing stays strictly positional.
            if (withMediaProvenance) data.writeBoolean(message.mediaValidated)
        }
        return output.toByteArray().also { output.wipe() }
    }

    private fun decodeMessage(
        body: ByteArray,
        withMediaProvenance: Boolean,
    ): AccountArchivedMessage =
        DataInputStream(ByteArrayInputStream(body)).use { data ->
            // AccountArchivedMessage validates every field itself on construction, so a backup
            // cannot smuggle in a malformed identifier or a state that carries send authority.
            val message = AccountArchivedMessage(
                serverMessageId = data.readBounded(MAX_IDENTIFIER_BYTES),
                clientMessageId = data.readBounded(MAX_IDENTIFIER_BYTES),
                conversationId = data.readBounded(MAX_IDENTIFIER_BYTES),
                sender = SecureMessagingCryptoAddress(
                    userId = data.readBounded(MAX_IDENTIFIER_BYTES),
                    serverDeviceId = data.readBounded(MAX_IDENTIFIER_BYTES),
                    signalDeviceId = data.readInt(),
                ),
                rosterRevision = data.readBounded(MAX_IDENTIFIER_BYTES),
                replyToMessageId = if (data.readBoolean()) {
                    data.readBounded(MAX_IDENTIFIER_BYTES)
                } else {
                    null
                },
                sentAt = Instant.ofEpochMilli(data.readLong()),
                deliveryState = deliveryState(data.readUnsignedByte()),
                text = data.readBounded(MAX_TEXT_BYTES),
                mediaValidated = if (withMediaProvenance) {
                    when (data.read()) {
                        0 -> false
                        1 -> true
                        else -> throw KitBackupFormatException(
                            "A backed-up message has an invalid media-validation marker",
                        )
                    }
                } else {
                    false
                },
            )
            if (data.read() >= 0) {
                throw KitBackupFormatException("A backed-up message carries trailing data")
            }
            message
        }

    private fun deliveryCode(state: SecureMessagingProjectionDeliveryState): Int = when (state) {
        SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED -> 1
        SecureMessagingProjectionDeliveryState.INBOUND_READ -> 2
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED -> 3
        SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ -> 4
        else -> error("Unbackupable message delivery state")
    }

    private fun deliveryState(code: Int): SecureMessagingProjectionDeliveryState = when (code) {
        1 -> SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
        2 -> SecureMessagingProjectionDeliveryState.INBOUND_READ
        3 -> SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED
        4 -> SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ
        else -> throw KitBackupFormatException("A backed-up message has an unknown delivery state")
    }
}

/** Who a backup belongs to and when it was taken — readable before any of its history is. */
internal data class KitBackupManifest(
    val ownerAccountId: String,
    val createdAt: Instant,
    val writerVersion: String,
    /**
     * True when the payload schema carries a per-message media-validation marker. Set from the
     * schema [KitBackupPayload.open] read, never chosen by callers, and required by
     * [KitBackupPayload.readMessages] so bodies decode under the schema they were written with.
     */
    val carriesMediaProvenance: Boolean = false,
)

/** A plaintext message must not linger in a buffer the collector may hand to something else. */
private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}

private fun DataOutputStream.writeBounded(value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    try {
        require(bytes.size in 1..maximumBytes) { "Invalid backed-up string size" }
        writeInt(bytes.size)
        write(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun DataInputStream.readBounded(maximumBytes: Int): String {
    val size = readInt()
    if (size !in 1..maximumBytes) {
        throw KitBackupFormatException("This backup declares an impossible field")
    }
    val bytes = ByteArray(size)
    return try {
        readFullyOrFail(bytes, "field")
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } finally {
        bytes.fill(0)
    }
}

private fun InputStream.readFullyOrFail(destination: ByteArray, what: String) {
    var read = 0
    while (read < destination.size) {
        val count = read(destination, read, destination.size - read)
        if (count < 0) throw EOFException("This backup ends part-way through a $what")
        read += count
    }
}
