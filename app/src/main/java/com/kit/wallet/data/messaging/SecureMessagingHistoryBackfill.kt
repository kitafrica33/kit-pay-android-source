package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ENCRYPTED_ATTACHMENT_MESSAGE_KIND
import com.kit.wallet.data.remote.ENCRYPTED_EDIT_MESSAGE_KIND
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.ENCRYPTED_REACTION_MESSAGE_KIND
import com.kit.wallet.data.remote.SECURE_MESSAGE_KINDS
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import okio.Buffer

/** Authenticated original-message projection released only after wrapper + server metadata match. */
internal sealed interface SecureMessagingAuthenticatedHistory {
    val messageId: String
    val clientMessageId: String
    val conversationId: String
    val sender: SecureMessagingCryptoAddress
    val senderEnrollmentEpoch: Long
    val rosterRevision: String
    val replyToMessageId: String?
    val sentAt: Instant
    val text: String
}

internal data class AuthenticatedHistoryValue(
    override val messageId: String,
    override val clientMessageId: String,
    override val conversationId: String,
    override val sender: SecureMessagingCryptoAddress,
    override val senderEnrollmentEpoch: Long,
    override val rosterRevision: String,
    override val replyToMessageId: String?,
    override val sentAt: Instant,
    override val text: String,
) : SecureMessagingAuthenticatedHistory

internal object SecureMessagingHistoryBackfillCodec {
    fun deterministicTransferId(
        messageId: String,
        targetDeviceId: String,
        targetEnrollmentEpoch: Long,
        donorDeviceId: String,
        donorEnrollmentEpoch: Long,
        transferRosterRevision: String,
    ): String {
        requireUuid(messageId, "history message ID")
        requireUuid(targetDeviceId, "history target device ID")
        requireUuid(donorDeviceId, "history donor device ID")
        require(targetEnrollmentEpoch > 0 && donorEnrollmentEpoch > 0) {
            "History enrollment epochs must be positive"
        }
        requireRosterRevision(transferRosterRevision, "history transfer roster revision")
        val digest = MessageDigest.getInstance("SHA-256").digest(
            listOf(
                SCHEMA,
                messageId,
                targetDeviceId,
                targetEnrollmentEpoch.toString(),
                donorDeviceId,
                donorEnrollmentEpoch.toString(),
                transferRosterRevision,
            ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
        )
        return try {
            digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
            digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
            val bytes = ByteBuffer.wrap(digest)
            UUID(bytes.long, bytes.long).toString()
        } finally {
            digest.fill(0)
        }
    }

    fun encode(
        transferClientMessageId: String,
        targetDeviceId: String,
        targetEnrollmentEpoch: Long,
        transferRosterRevision: String,
        candidate: RemoteSecureMessagingTransport.Session.HistoryBackfillCandidate,
        projected: SecureMessagingProjectedMessage,
    ): String {
        requireUuid(transferClientMessageId, "history transfer ID")
        requireUuid(targetDeviceId, "history target device ID")
        require(targetEnrollmentEpoch > 0) { "Invalid history target enrollment epoch" }
        requireRosterRevision(transferRosterRevision, "history transfer roster revision")
        val durable = requireDurableLibSignalCompanionRecord(projected.durableRecord)
        check(projected.serverMessageId == candidate.messageId) {
            "Retained history projection has another server message ID"
        }
        check(
            durable.clientMessageId == candidate.clientMessageId &&
                durable.conversationId == candidate.conversationId &&
                durable.sender.userId == candidate.senderUserId &&
                durable.sender.serverDeviceId == candidate.senderDeviceId &&
                durable.sender.signalDeviceId == candidate.senderSignalDeviceId &&
                durable.rosterRevision == candidate.rosterRevision &&
                durable.replyToMessageId == candidate.replyToMessageId &&
                projected.sentAt.toEpochMilli() == candidate.sentAt.toEpochMilli(),
        ) { "Retained history projection does not match the server candidate" }
        val authenticatedKind = authenticatedMessageKind(durable.authenticatedText)
        check(authenticatedKind == candidate.kind) {
            "Retained history content does not match the server message kind"
        }
        KitReactionMessage.parse(durable.authenticatedText)?.let { reaction ->
            check(reaction.targetMessageId == durable.replyToMessageId) {
                "Retained reaction content does not match its reply target"
            }
        }

        val buffer = Buffer()
        JsonWriter.of(buffer).apply { serializeNulls = true }.use { writer ->
            writer.beginObject()
            writer.name("schema").value(SCHEMA)
            writer.name("type").value(TYPE)
            writer.name("transfer_client_message_id").value(transferClientMessageId)
            writer.name("target_device_id").value(targetDeviceId)
            writer.name("target_enrollment_epoch").value(targetEnrollmentEpoch)
            writer.name("transfer_roster_revision").value(transferRosterRevision)
            writer.name("message_id").value(candidate.messageId)
            writer.name("client_message_id").value(candidate.clientMessageId)
            writer.name("conversation_id").value(candidate.conversationId)
            writer.name("sender_user_id").value(candidate.senderUserId)
            writer.name("sender_device_id").value(candidate.senderDeviceId)
            writer.name("sender_enrollment_epoch").value(candidate.senderEnrollmentEpoch)
            writer.name("sender_signal_device_id").value(candidate.senderSignalDeviceId.toLong())
            writer.name("original_roster_revision").value(candidate.rosterRevision)
            writer.name("kind").value(candidate.kind)
            writer.name("reply_to_message_id")
            candidate.replyToMessageId?.let(writer::value) ?: writer.nullValue()
            writer.name("sent_at").value(candidate.sentAt.toString())
            writer.name("text").value(durable.authenticatedText)
            writer.endObject()
        }
        return buffer.readUtf8().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_DESCRIPTOR_BYTES) {
                "History descriptor is too large"
            }
        }
    }

    fun authenticate(
        descriptorText: String,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
        expectedTargetDeviceId: String,
        expectedTargetEnrollmentEpoch: Long,
    ): SecureMessagingAuthenticatedHistory {
        require(envelope.isHistoryBackfill) { "Incoming envelope is not a history transfer" }
        val decoded = decode(descriptorText)
        check(
            decoded.transferClientMessageId == envelope.transferClientMessageId &&
                decoded.targetDeviceId == expectedTargetDeviceId &&
                decoded.targetEnrollmentEpoch == expectedTargetEnrollmentEpoch &&
                envelope.recipientEnrollmentEpoch == expectedTargetEnrollmentEpoch &&
                decoded.transferRosterRevision == envelope.transferRosterRevision &&
                decoded.messageId == envelope.messageId &&
                decoded.clientMessageId == envelope.clientMessageId &&
                decoded.conversationId == envelope.conversationId &&
                decoded.senderUserId == envelope.senderUserId &&
                decoded.senderDeviceId == envelope.senderDeviceId &&
                decoded.senderEnrollmentEpoch == envelope.senderEnrollmentEpoch &&
                decoded.senderSignalDeviceId == envelope.senderSignalDeviceId &&
                decoded.originalRosterRevision == envelope.rosterRevision &&
                decoded.kind == envelope.kind &&
                decoded.replyToMessageId == envelope.replyToMessageId &&
                decoded.sentAt == envelope.sentAt,
        ) { "Authenticated history descriptor does not match original server metadata" }
        val authenticatedKind = authenticatedMessageKind(decoded.text)
        // Reserved-media history that can never be actionable is not discarded: the transcript
        // slot is authentic, so it recovers as the bare sanitized generation marker, which every
        // layer renders as the generic placeholder. This covers any family generation that fails
        // strict parsing outright, and a strict v2 descriptor whose outer kind or row set does
        // not bind. A strictly valid v1 descriptor keeps its historical strict rejection below,
        // and ordinary text keeps today's checks unchanged.
        val unsupportedMedia = KitMediaFamily.isFamilyText(decoded.text) &&
            KitMediaMessage.parse(decoded.text) == null &&
            (kitMediaAttachmentsFor(decoded.text).isEmpty() ||
                authenticatedKind != decoded.kind ||
                !kitMediaOuterAttachmentsMatch(decoded.text, envelope.attachments))
        if (!unsupportedMedia) {
            check(authenticatedKind == decoded.kind) {
                "Authenticated history content does not match its message kind"
            }
            check(kitMediaOuterAttachmentsMatch(decoded.text, envelope.attachments)) {
                "Authenticated history content does not match its server attachment metadata"
            }
        }
        KitReactionMessage.parse(decoded.text)?.let { reaction ->
            check(reaction.targetMessageId == decoded.replyToMessageId) {
                "Authenticated history reaction does not match its reply target"
            }
        }
        return AuthenticatedHistoryValue(
            messageId = decoded.messageId,
            clientMessageId = decoded.clientMessageId,
            conversationId = decoded.conversationId,
            sender = SecureMessagingCryptoAddress(
                userId = decoded.senderUserId,
                serverDeviceId = decoded.senderDeviceId,
                signalDeviceId = decoded.senderSignalDeviceId,
            ),
            senderEnrollmentEpoch = decoded.senderEnrollmentEpoch,
            rosterRevision = decoded.originalRosterRevision,
            replyToMessageId = decoded.replyToMessageId,
            sentAt = decoded.sentAt,
            // The recovered durable record itself carries only the marker — descriptor bytes
            // from unbindable reserved history never persist on the receiving device.
            text = if (unsupportedMedia) {
                KitMediaFamily.sanitizedFamilyMarker(decoded.text)
            } else {
                decoded.text
            },
        )
    }

    private fun decode(text: String): DecodedHistory {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_DESCRIPTOR_BYTES) { "Invalid history descriptor size" }
        val strictText = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            bytes.fill(0)
        }
        val reader = JsonReader.of(Buffer().writeUtf8(strictText)).apply { isLenient = false }
        val values = mutableMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(name in MEMBERS && !values.containsKey(name)) {
                "History descriptor contains an unknown or duplicate member"
            }
            values[name] = when (name) {
                "target_enrollment_epoch", "sender_enrollment_epoch",
                "sender_signal_device_id",
                -> reader.readStrictLong(name)
                "reply_to_message_id" -> if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<String>()
                } else {
                    reader.readStrictString(name)
                }
                else -> reader.readStrictString(name)
            }
        }
        reader.endObject()
        require(reader.peek() == JsonReader.Token.END_DOCUMENT && values.keys == MEMBERS) {
            "History descriptor is incomplete or contains trailing data"
        }
        reader.close()

        fun string(name: String) = values[name] as? String
            ?: throw IllegalArgumentException("History descriptor $name is missing")
        val decoded = DecodedHistory(
            transferClientMessageId = string("transfer_client_message_id"),
            targetDeviceId = string("target_device_id"),
            targetEnrollmentEpoch = values.requirePositiveLong("target_enrollment_epoch"),
            transferRosterRevision = string("transfer_roster_revision"),
            messageId = string("message_id"),
            clientMessageId = string("client_message_id"),
            conversationId = string("conversation_id"),
            senderUserId = string("sender_user_id"),
            senderDeviceId = string("sender_device_id"),
            senderEnrollmentEpoch = values.requirePositiveLong("sender_enrollment_epoch"),
            senderSignalDeviceId = values.requirePositiveLong("sender_signal_device_id").toInt(),
            originalRosterRevision = string("original_roster_revision"),
            kind = string("kind"),
            replyToMessageId = values["reply_to_message_id"] as? String,
            sentAt = Instant.parse(string("sent_at")),
            text = string("text"),
        )
        require(string("schema") == SCHEMA && string("type") == TYPE) {
            "History descriptor schema is unsupported"
        }
        listOf(
            decoded.transferClientMessageId,
            decoded.targetDeviceId,
            decoded.messageId,
            decoded.clientMessageId,
            decoded.conversationId,
            decoded.senderUserId,
            decoded.senderDeviceId,
        ).forEach { requireUuid(it, "history descriptor UUID") }
        decoded.replyToMessageId?.let { requireUuid(it, "history reply target") }
        requireRosterRevision(decoded.transferRosterRevision, "history transfer roster revision")
        requireRosterRevision(decoded.originalRosterRevision, "history original roster revision")
        require(decoded.senderSignalDeviceId in 1..127) { "Invalid history Signal device ID" }
        require(decoded.kind in SECURE_MESSAGE_KINDS) {
            "Invalid history message kind"
        }
        require('\u0000' !in decoded.text && decoded.text.isNotEmpty()) { "Invalid history text" }
        return decoded
    }

    private fun JsonReader.readStrictString(field: String): String {
        require(peek() == JsonReader.Token.STRING) { "History $field must be a string" }
        return nextString()
    }

    private fun JsonReader.readStrictLong(field: String): Long {
        require(peek() == JsonReader.Token.NUMBER) { "History $field must be an integer" }
        val encoded = nextString()
        require(POSITIVE_INTEGER.matches(encoded)) { "History $field must be a positive integer" }
        return requireNotNull(encoded.toLongOrNull()) { "History $field is too large" }
    }

    private fun Map<String, Any?>.requirePositiveLong(field: String): Long =
        (this[field] as? Long)?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("History $field must be positive")

    private fun requireUuid(value: String, field: String) {
        require(UUID_PATTERN.matches(value)) { "Invalid $field" }
    }

    private fun requireRosterRevision(value: String, field: String) {
        require(ROSTER_REVISION.matches(value)) { "Invalid $field" }
    }

    private data class DecodedHistory(
        val transferClientMessageId: String,
        val targetDeviceId: String,
        val targetEnrollmentEpoch: Long,
        val transferRosterRevision: String,
        val messageId: String,
        val clientMessageId: String,
        val conversationId: String,
        val senderUserId: String,
        val senderDeviceId: String,
        val senderEnrollmentEpoch: Long,
        val senderSignalDeviceId: Int,
        val originalRosterRevision: String,
        val kind: String,
        val replyToMessageId: String?,
        val sentAt: Instant,
        val text: String,
    )

    private const val SCHEMA = "kit.messaging.history.v1"
    private const val TYPE = "history_backfill"
    private const val MAX_DESCRIPTOR_BYTES = 48 * 1024
    private val UUID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val ROSTER_REVISION = Regex("^v1:sha256:[a-f0-9]{64}$")
    private val POSITIVE_INTEGER = Regex("^[1-9][0-9]*$")
    private val MEMBERS = setOf(
        "schema",
        "type",
        "transfer_client_message_id",
        "target_device_id",
        "target_enrollment_epoch",
        "transfer_roster_revision",
        "message_id",
        "client_message_id",
        "conversation_id",
        "sender_user_id",
        "sender_device_id",
        "sender_enrollment_epoch",
        "sender_signal_device_id",
        "original_roster_revision",
        "kind",
        "reply_to_message_id",
        "sent_at",
        "text",
    )
}

/**
 * The outer kind a piece of authenticated plaintext binds itself to.
 *
 * One function, used by every path that sends, receives, replays or donates a message, so the
 * envelope a device writes and the envelope it will accept back can never be derived by two
 * different rules.
 */
internal fun authenticatedMessageKind(text: String): String = when {
    // Either media generation: one strict v1 row or 2–8 strict v2 rows. Family text that fails
    // strict parsing yields no rows and deliberately reads as ordinary text here — binding then
    // fails on the row set, never by accepting an almost-descriptor.
    kitMediaAttachmentsFor(text).isNotEmpty() -> ENCRYPTED_ATTACHMENT_MESSAGE_KIND
    KitReactionMessage.parse(text) != null -> ENCRYPTED_REACTION_MESSAGE_KIND
    KitEditMessage.parse(text) != null -> ENCRYPTED_EDIT_MESSAGE_KIND
    else -> ENCRYPTED_MESSAGE_KIND
}

/**
 * The kind an outbound request carries *before* its attachment metadata is attached.
 *
 * The same rule as [authenticatedMessageKind] with one exception: a media descriptor still reads
 * as ordinary text here. The transport is the single place that pairs `encrypted_attachment` with
 * the attachment list it must travel with, because the wire model refuses either one without the
 * other — so announcing that kind any earlier would build a request that cannot be constructed.
 */
internal fun authenticatedOutboundMessageKind(text: String): String =
    authenticatedMessageKind(text).takeUnless { it == ENCRYPTED_ATTACHMENT_MESSAGE_KIND }
        ?: ENCRYPTED_MESSAGE_KIND

/** Durable, non-secret work item that lets history transfer resume without holding sync back. */
internal data class SecureMessagingHistoryBackfillTask(
    val recordKey: String,
    val recordVersion: Long,
    val conversationId: String,
    val targetDeviceId: String,
    val targetEnrollmentEpoch: Long,
    val nextCursor: String?,
    val completed: Boolean,
)

internal object SecureMessagingHistoryBackfillTaskCodec {
    fun recordKey(
        conversationId: String,
        targetDeviceId: String,
        targetEnrollmentEpoch: Long,
    ): String {
        require(HISTORY_UUID.matches(conversationId)) { "Invalid history-task conversation ID" }
        require(HISTORY_UUID.matches(targetDeviceId)) { "Invalid history-task target device ID" }
        require(targetEnrollmentEpoch > 0) { "Invalid history-task target enrollment epoch" }
        return "task:$conversationId:$targetDeviceId:$targetEnrollmentEpoch"
    }

    fun encode(
        conversationId: String,
        targetDeviceId: String,
        targetEnrollmentEpoch: Long,
        nextCursor: String?,
        completed: Boolean,
    ): ByteArray {
        val expectedRecordKey = recordKey(
            conversationId,
            targetDeviceId,
            targetEnrollmentEpoch,
        )
        check(expectedRecordKey.isNotEmpty())
        nextCursor?.let(::requireHistoryCursor)
        val buffer = Buffer()
        JsonWriter.of(buffer).apply { serializeNulls = true }.use { writer ->
            writer.beginObject()
            writer.name("schema").value(TASK_SCHEMA)
            writer.name("conversation_id").value(conversationId)
            writer.name("target_device_id").value(targetDeviceId)
            writer.name("target_enrollment_epoch").value(targetEnrollmentEpoch)
            writer.name("next_cursor")
            nextCursor?.let(writer::value) ?: writer.nullValue()
            writer.name("completed").value(completed)
            writer.endObject()
        }
        return buffer.readByteArray().also {
            require(it.size in 1..MAX_TASK_BYTES) { "Invalid history-task size" }
        }
    }

    fun decode(
        recordKey: String,
        recordVersion: Long,
        bytes: ByteArray,
    ): SecureMessagingHistoryBackfillTask {
        require(recordVersion > 0) { "Invalid history-task record version" }
        require(bytes.size in 1..MAX_TASK_BYTES) { "Invalid history-task size" }
        val reader = JsonReader.of(Buffer().write(bytes)).apply { isLenient = false }
        val values = mutableMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(name in TASK_MEMBERS && !values.containsKey(name)) {
                "History task contains an unknown or duplicate member"
            }
            values[name] = when (name) {
                "target_enrollment_epoch" -> reader.readTaskLong(name)
                "completed" -> {
                    require(reader.peek() == JsonReader.Token.BOOLEAN) {
                        "History task completed must be boolean"
                    }
                    reader.nextBoolean()
                }
                "next_cursor" -> if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<String>()
                } else {
                    reader.readTaskString(name)
                }
                else -> reader.readTaskString(name)
            }
        }
        reader.endObject()
        require(reader.peek() == JsonReader.Token.END_DOCUMENT && values.keys == TASK_MEMBERS) {
            "History task is incomplete or contains trailing data"
        }
        reader.close()
        fun string(name: String) = values[name] as? String
            ?: throw IllegalArgumentException("History task $name is missing")
        require(string("schema") == TASK_SCHEMA) { "Unsupported history-task schema" }
        val conversationId = string("conversation_id")
        val targetDeviceId = string("target_device_id")
        val targetEnrollmentEpoch = values["target_enrollment_epoch"] as? Long
            ?: throw IllegalArgumentException("History task target epoch is missing")
        val nextCursor = values["next_cursor"] as? String
        nextCursor?.let(::requireHistoryCursor)
        require(
            recordKey == SecureMessagingHistoryBackfillTaskCodec.recordKey(
                conversationId,
                targetDeviceId,
                targetEnrollmentEpoch,
            ),
        ) { "History task address does not match its contents" }
        return SecureMessagingHistoryBackfillTask(
            recordKey = recordKey,
            recordVersion = recordVersion,
            conversationId = conversationId,
            targetDeviceId = targetDeviceId,
            targetEnrollmentEpoch = targetEnrollmentEpoch,
            nextCursor = nextCursor,
            completed = values["completed"] as? Boolean
                ?: throw IllegalArgumentException("History task completion state is missing"),
        )
    }

    private fun JsonReader.readTaskString(field: String): String {
        require(peek() == JsonReader.Token.STRING) { "History task $field must be a string" }
        return nextString()
    }

    private fun JsonReader.readTaskLong(field: String): Long {
        require(peek() == JsonReader.Token.NUMBER) { "History task $field must be an integer" }
        val encoded = nextString()
        require(TASK_POSITIVE_INTEGER.matches(encoded)) {
            "History task $field must be a positive integer"
        }
        return requireNotNull(encoded.toLongOrNull()) { "History task $field is too large" }
    }

    private fun requireHistoryCursor(cursor: String) {
        require(cursor.length <= MAX_CURSOR_LENGTH && HISTORY_CURSOR.matches(cursor)) {
            "Invalid history-task cursor"
        }
    }

    private const val TASK_SCHEMA = "kit.messaging.history-task.v1"
    private const val MAX_TASK_BYTES = 8 * 1024
    private const val MAX_CURSOR_LENGTH = 2_048
    private val HISTORY_UUID =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val HISTORY_CURSOR = Regex("^[A-Za-z0-9_.-]+$")
    private val TASK_POSITIVE_INTEGER = Regex("^[1-9][0-9]*$")
    private val TASK_MEMBERS = setOf(
        "schema",
        "conversation_id",
        "target_device_id",
        "target_enrollment_epoch",
        "next_cursor",
        "completed",
    )
}
