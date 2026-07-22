package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionRecord
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingTextContentBinding
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordPage
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.validateSecureMessagingNamespacePageRequest
import com.kit.wallet.data.messaging.encodeSecureMessagingTextContent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Test-only encrypted-state substitute shared by focused crash/restart contract tests. */
internal class TestSecureMessagingStateStore : SecureMessagingStateStore {
    private data class Stored(
        val version: Long,
        val bytes: ByteArray,
        val updatedAtEpochMillis: Long,
    )

    private val records = mutableMapOf<Pair<String, String>, Stored>()
    private var clock = 1_000L

    override suspend fun read(namespace: String, recordKey: String): SecureMessagingRecord? =
        records[namespace to recordKey]?.let { stored ->
            SecureMessagingRecord(
                namespace = namespace,
                recordKey = recordKey,
                version = stored.version,
                bytes = stored.bytes.copyOf(),
                updatedAtEpochMillis = stored.updatedAtEpochMillis,
            )
        }

    override suspend fun readNamespacePage(
        namespace: String,
        afterRecordKey: String?,
        limit: Int,
    ): SecureMessagingRecordPage {
        validateSecureMessagingNamespacePageRequest(namespace, afterRecordKey, limit)
        val candidates = records.entries.asSequence()
            .filter { it.key.first == namespace }
            .filter { afterRecordKey == null || it.key.second > afterRecordKey }
            .sortedBy { it.key.second }
            .take(limit + 1)
            .toList()
        val selected = candidates.take(limit).map { (address, stored) ->
            SecureMessagingRecord(
                namespace = address.first,
                recordKey = address.second,
                version = stored.version,
                bytes = stored.bytes.copyOf(),
                updatedAtEpochMillis = stored.updatedAtEpochMillis,
            )
        }
        return SecureMessagingRecordPage(
            records = selected,
            nextAfterRecordKey = if (candidates.size > limit) {
                selected.last().recordKey
            } else {
                null
            },
        )
    }

    override suspend fun write(
        namespace: String,
        recordKey: String,
        expectedVersion: Long?,
        bytes: ByteArray,
    ): SecureMessagingRecordVersion = writeBatch(
        listOf(SecureMessagingStateWrite(namespace, recordKey, expectedVersion, bytes)),
    ).single()

    override suspend fun writeBatch(
        writes: List<SecureMessagingStateWrite>,
    ): List<SecureMessagingRecordVersion> {
        require(writes.isNotEmpty())
        require(writes.map { it.namespace to it.recordKey }.distinct().size == writes.size)
        val versions = writes.map { write ->
            val current = records[write.namespace to write.recordKey]
            when {
                current == null && write.expectedVersion == null -> 1L
                current == null || current.version != write.expectedVersion ->
                    throw SecureMessagingStateConflictException("version mismatch")
                else -> current.version + 1L
            }
        }
        writes.zip(versions).forEach { (write, version) ->
            records.put(
                write.namespace to write.recordKey,
                Stored(version, write.copyBytes(), clock++),
            )?.bytes?.fill(0)
        }
        return writes.zip(versions).map { (write, version) ->
            SecureMessagingRecordVersion(write.namespace, write.recordKey, version)
        }
    }

    override suspend fun deleteNamespace(namespace: String) {
        val removed = records.filterKeys { it.first == namespace }
        records.keys.removeAll { it.first == namespace }
        removed.values.forEach { it.bytes.fill(0) }
    }

    override suspend fun eraseAll() {
        records.values.forEach { it.bytes.fill(0) }
        records.clear()
    }
}

internal data class PersistedCompanionEnvelopeFixture(
    val recipient: SecureMessagingCryptoAddress,
    val kind: SecureMessagingEnvelopeKind,
    val ciphertext: ByteArray,
)

/**
 * Writes a production-decodable companion record through the public durable-state boundary.
 * This mirrors the version-1 binary fixture so restart tests never instantiate the private
 * decoded-record implementation or bypass its authenticated-content checks.
 */
internal suspend fun TestSecureMessagingStateStore.persistCompanionRecordForTest(
    namespace: String,
    recordKey: String,
    direction: LibSignalCompanionDirection,
    messageId: String,
    clientMessageId: String,
    conversationId: String,
    rosterRevision: String,
    sender: SecureMessagingCryptoAddress,
    replyToMessageId: String? = null,
    text: String,
    envelopes: List<PersistedCompanionEnvelopeFixture> = emptyList(),
): LibSignalCompanionRecord {
    val plaintext = encodeSecureMessagingTextContent(
        SecureMessagingTextContentBinding(
            clientMessageId = clientMessageId,
            conversationId = conversationId,
            rosterRevision = rosterRevision,
            sender = sender,
            replyToMessageId = replyToMessageId,
        ),
        text,
    )
    val output = ByteArrayOutputStream()
    val encoded = try {
        DataOutputStream(output).use { data ->
            data.write(byteArrayOf(0x4b, 0x49, 0x54, 0x4d, 0x53, 0x47, 0x32))
            data.writeInt(1)
            data.writeByte(
                when (direction) {
                    LibSignalCompanionDirection.OUTBOUND -> 1
                    LibSignalCompanionDirection.INBOUND -> 2
                },
            )
            data.writeSized(messageId)
            data.writeSized(clientMessageId)
            data.writeSized(conversationId)
            data.writeSized(rosterRevision)
            data.writeAddress(sender)
            data.writeBoolean(replyToMessageId != null)
            replyToMessageId?.let(data::writeSized)
            data.writeSized(plaintext)
            data.writeInt(envelopes.size)
            envelopes.forEach { envelope ->
                data.writeAddress(envelope.recipient)
                data.writeByte(
                    when (envelope.kind) {
                        SecureMessagingEnvelopeKind.PREKEY -> 1
                        SecureMessagingEnvelopeKind.SESSION -> 2
                    },
                )
                data.writeSized(envelope.ciphertext)
            }
        }
        output.toByteArray()
    } finally {
        plaintext.fill(0)
    }
    try {
        write(namespace, recordKey, expectedVersion = null, bytes = encoded)
    } finally {
        encoded.fill(0)
    }
    return checkNotNull(LibSignalCompanionStateReader(this).read(namespace, recordKey))
}

private fun DataOutputStream.writeAddress(address: SecureMessagingCryptoAddress) {
    writeSized(address.userId)
    writeSized(address.serverDeviceId)
    writeInt(address.signalDeviceId)
}

private fun DataOutputStream.writeSized(value: String) = writeSized(value.toByteArray(Charsets.UTF_8))

private fun DataOutputStream.writeSized(value: ByteArray) {
    writeInt(value.size)
    write(value)
}
