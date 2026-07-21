package com.kit.wallet.data.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class SecureMessagingProjectionDeliveryState {
    INBOUND_RECEIVED,
    OUTBOUND_PENDING,
    OUTBOUND_SENT,
    OUTBOUND_DELIVERED,
    OUTBOUND_READ,
    /** Append-only codec value: the durable ciphertext cannot be retried against the current roster. */
    OUTBOUND_RETRY_REQUIRED,
    /** Append-only local state; it is never transmitted as a server-visible read receipt. */
    INBOUND_READ,
}

internal data class SecureMessagingProjectedMessage(
    val durableRecord: LibSignalCompanionRecord,
    val serverMessageId: String?,
    val sentAt: Instant,
    val deliveryState: SecureMessagingProjectionDeliveryState,
)

internal class SecureMessagingProjectionPage(
    messages: List<SecureMessagingProjectedMessage>,
    val nextAfterRecordKey: String?,
) {
    private val immutableMessages = messages.toList()

    fun messages(): List<SecureMessagingProjectedMessage> = immutableMessages.toList()
}

/**
 * Encrypted local projection over atomically committed libsignal companion records.
 *
 * Text and ciphertext remain in the crypto adapter's companion record. This store adds only
 * server-validated display/delivery metadata; absence after a crash falls back to the companion
 * commit timestamp and is repaired when the same sync event is replayed.
 */
@Singleton
internal class SecureMessagingProjectionStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
    private val companionReader: LibSignalCompanionStateReader,
) {
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    fun outboundIntent(clientMessageId: String): SecureMessagingCompanionStateIntent =
        SecureMessagingCompanionStateIntent.outbound(
            namespace = COMPANION_NAMESPACE,
            recordKey = outboundRecordKey(clientMessageId),
        )

    fun inboundIntent(messageId: String): SecureMessagingCompanionStateIntent =
        SecureMessagingCompanionStateIntent.inbound(
            namespace = COMPANION_NAMESPACE,
            recordKey = inboundRecordKey(messageId),
        )

    suspend fun readInbound(messageId: String): LibSignalCompanionRecord? =
        companionReader.read(COMPANION_NAMESPACE, inboundRecordKey(messageId))

    suspend fun readOutbound(clientMessageId: String): LibSignalCompanionRecord? =
        companionReader.read(COMPANION_NAMESPACE, outboundRecordKey(clientMessageId))

    /**
     * Returns whether the authenticated inbound projection has already been durably recorded.
     *
     * The event processor records this metadata only after its idempotent notification sink
     * returns successfully. A committed companion record without matching metadata is therefore
     * recoverable notification work after either process death or a failed publication attempt.
     */
    suspend fun isInboundPublicationRecorded(
        durableRecord: LibSignalCompanionRecord,
        sentAt: Instant,
    ): Boolean {
        val durable = requireInboundProjection(durableRecord)
        val expected = inboundMetadata(durable, sentAt)
        val existing = readMetadataRecord(durable.recordKey) ?: return false
        validateMetadataMatches(existing.metadata, durable)
        validateIdempotentMetadata(existing.metadata, expected)
        return true
    }

    suspend fun readPage(
        afterRecordKey: String? = null,
        limit: Int,
    ): SecureMessagingProjectionPage {
        val companionPage = companionReader.readPage(
            namespace = COMPANION_NAMESPACE,
            afterRecordKey = afterRecordKey,
            limit = limit,
        )
        val messages = companionPage.records().map { companion ->
            val durable = requireDurableLibSignalCompanionRecord(companion)
            validateCompanionAddress(durable)
            val metadata = readMetadata(durable.recordKey)
            if (metadata != null) validateMetadataMatches(metadata, durable)
            SecureMessagingProjectedMessage(
                durableRecord = durable,
                serverMessageId = metadata?.serverMessageId,
                sentAt = Instant.ofEpochMilli(
                    metadata?.sentAtEpochMillis ?: durable.updatedAtEpochMillis,
                ),
                deliveryState = metadata?.deliveryState ?: when (durable.direction) {
                    LibSignalCompanionDirection.INBOUND ->
                        SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
                    LibSignalCompanionDirection.OUTBOUND ->
                        SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING
                },
            )
        }
        return SecureMessagingProjectionPage(messages, companionPage.nextAfterRecordKey)
    }

    suspend fun recordInbound(
        durableRecord: LibSignalCompanionRecord,
        sentAt: Instant,
    ) {
        val durable = requireInboundProjection(durableRecord)
        if (createOrValidate(
            inboundMetadata(durable, sentAt),
            durable,
        )) signalChanged()
    }

    suspend fun recordOutboundPending(
        durableRecord: LibSignalCompanionRecord,
        createdAt: Instant,
    ) {
        val durable = requireDurableLibSignalCompanionRecord(durableRecord)
        check(durable.direction == LibSignalCompanionDirection.OUTBOUND) {
            "Only an outgoing companion record can receive outbox projection metadata"
        }
        validateCompanionAddress(durable)
        if (createOrValidate(
            ProjectionMetadata(
                direction = durable.direction,
                conversationId = durable.conversationId,
                clientMessageId = durable.clientMessageId,
                serverMessageId = null,
                sentAtEpochMillis = requireTimestamp(createdAt),
                deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
            ),
            durable,
        )) signalChanged()
    }

    /**
     * Persists local read state for authenticated inbound projections only. The v2 protocol does
     * not emit server-visible read receipts, so this changes neither ciphertext nor remote state.
     */
    suspend fun markConversationRead(conversationId: String) {
        require(isCanonicalUuid(conversationId)) { "Invalid read-marker conversation ID" }
        var afterRecordKey: String? = null
        var changed = false
        repeat(MAX_READ_MARKER_PAGES) {
            val page = companionReader.readPage(
                namespace = COMPANION_NAMESPACE,
                afterRecordKey = afterRecordKey,
                limit = READ_MARKER_PAGE_SIZE,
            )
            page.records().forEach { companion ->
                val durable = requireDurableLibSignalCompanionRecord(companion)
                validateCompanionAddress(durable)
                if (durable.direction != LibSignalCompanionDirection.INBOUND ||
                    durable.conversationId != conversationId
                ) return@forEach

                val existing = readMetadataRecord(durable.recordKey) ?: return@forEach
                validateMetadataMatches(existing.metadata, durable)
                if (existing.metadata.deliveryState == SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED) {
                    writeMetadata(
                        recordKey = durable.recordKey,
                        metadata = existing.metadata.copy(
                            deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_READ,
                        ),
                        expectedVersion = existing.version,
                    )
                    changed = true
                }
            }
            val next = page.nextAfterRecordKey
            if (next == null) {
                if (changed) signalChanged()
                return
            }
            check(afterRecordKey == null || next > afterRecordKey!!) {
                "Secure-message read-marker pagination did not advance"
            }
            afterRecordKey = next
        }
        if (changed) signalChanged()
        error("Secure-message history exceeds the supported read-marker bound")
    }

    suspend fun markOutboundSent(
        durableRecord: LibSignalCompanionRecord,
        receipt: RemoteSecureMessagingTransport.Session.OutboundReceipt,
    ) {
        val durable = requireDurableLibSignalCompanionRecord(durableRecord)
        requireOutboundProjection(durable)
        check(
            receipt.clientMessageId == durable.clientMessageId &&
                receipt.conversationId == durable.conversationId &&
                receipt.rosterRevision == durable.rosterRevision,
        ) { "Outbound receipt does not match its durable encrypted projection" }
        markOutboundSent(durable, receipt.messageId, receipt.sentAt)
    }

    /** Applies an authenticated sync echo after a send response was lost across process death. */
    suspend fun markOutboundSent(
        durableRecord: LibSignalCompanionRecord,
        event: RemoteSecureMessagingTransport.Session.OutboundEvent,
    ) {
        val durable = requireDurableLibSignalCompanionRecord(durableRecord)
        requireOutboundProjection(durable)
        check(
            event.clientMessageId == durable.clientMessageId &&
                event.conversationId == durable.conversationId &&
                event.rosterRevision == durable.rosterRevision,
        ) { "Outbound sync event does not match its durable encrypted projection" }
        markOutboundSent(durable, event.messageId, event.sentAt)
    }

    /**
     * Retires a pending fanout that no longer matches the authoritative roster. The durable
     * ciphertext remains visible for audit/retry UX but can never be selected as pending again.
     */
    suspend fun markOutboundRetryRequired(
        durableRecord: LibSignalCompanionRecord,
    ) {
        val durable = requireDurableLibSignalCompanionRecord(durableRecord)
        requireOutboundProjection(durable)
        val existing = readMetadataRecord(durable.recordKey)
        existing?.let { validateMetadataMatches(it.metadata, durable) }
        if (
            existing?.metadata?.deliveryState ==
            SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED
        ) return
        check(
            existing == null ||
                existing.metadata.deliveryState ==
                SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
        ) { "Only pending ciphertext can require a fresh encrypted retry" }
        val updated = ProjectionMetadata(
            direction = durable.direction,
            conversationId = durable.conversationId,
            clientMessageId = durable.clientMessageId,
            serverMessageId = null,
            sentAtEpochMillis = existing?.metadata?.sentAtEpochMillis
                ?: durable.updatedAtEpochMillis,
            deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
        )
        writeMetadata(durable.recordKey, updated, existing?.version)
        signalChanged()
    }

    private suspend fun markOutboundSent(
        durable: LibSignalCompanionRecord,
        serverMessageId: String,
        sentAt: Instant,
    ) {
        val existing = readMetadataRecord(durable.recordKey)
        val updated = ProjectionMetadata(
            direction = durable.direction,
            conversationId = durable.conversationId,
            clientMessageId = durable.clientMessageId,
            serverMessageId = serverMessageId,
            sentAtEpochMillis = requireTimestamp(sentAt),
            deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
        )
        if (existing != null) {
            validateMetadataMatches(existing.metadata, durable)
            if (existing.metadata == updated) return
            check(
                existing.metadata.deliveryState ==
                    SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING ||
                    existing.metadata.deliveryState ==
                    SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED ||
                    existing.metadata == updated,
            ) { "Outbound projection cannot regress or change its server receipt" }
        }
        writeMetadata(durable.recordKey, updated, existing?.version)
        signalChanged()
    }

    private fun requireOutboundProjection(durable: LibSignalCompanionRecord) {
        check(durable.direction == LibSignalCompanionDirection.OUTBOUND) {
            "Only an outgoing companion record can be marked sent"
        }
        validateCompanionAddress(durable)
    }

    private fun requireInboundProjection(
        durableRecord: LibSignalCompanionRecord,
    ): LibSignalCompanionRecord {
        val durable = requireDurableLibSignalCompanionRecord(durableRecord)
        check(durable.direction == LibSignalCompanionDirection.INBOUND) {
            "Only an incoming companion record can receive incoming projection metadata"
        }
        validateCompanionAddress(durable)
        return durable
    }

    private fun inboundMetadata(
        durable: LibSignalCompanionRecord,
        sentAt: Instant,
    ) = ProjectionMetadata(
        direction = durable.direction,
        conversationId = durable.conversationId,
        clientMessageId = durable.clientMessageId,
        serverMessageId = durable.messageId,
        sentAtEpochMillis = requireTimestamp(sentAt),
        deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
    )

    private suspend fun createOrValidate(
        metadata: ProjectionMetadata,
        durable: LibSignalCompanionRecord,
    ): Boolean {
        val existing = readMetadataRecord(durable.recordKey)
        if (existing != null) {
            validateMetadataMatches(existing.metadata, durable)
            validateIdempotentMetadata(existing.metadata, metadata)
            return false
        }
        writeMetadata(durable.recordKey, metadata, expectedVersion = null)
        return true
    }

    private fun validateIdempotentMetadata(
        existing: ProjectionMetadata,
        expected: ProjectionMetadata,
    ) {
        if (expected.deliveryState == SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED &&
            existing == expected.copy(
                deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_READ,
            )
        ) {
            return
        }
        check(existing == expected) {
            "Secure-message projection metadata changed for an existing record"
        }
    }

    private fun signalChanged() {
        mutableChanges.update { revision ->
            if (revision == Long.MAX_VALUE) 1L else revision + 1L
        }
    }

    private suspend fun readMetadata(recordKey: String): ProjectionMetadata? =
        readMetadataRecord(recordKey)?.metadata

    private suspend fun readMetadataRecord(recordKey: String): VersionedProjectionMetadata? {
        val record = try {
            stateStore.read(METADATA_NAMESPACE, recordKey)
        } catch (error: SecureMessagingStateUnavailableException) {
            throw SecureMessagingCryptographicFailureException(
                SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                "Secure-message projection metadata is unavailable",
                error,
            )
        } ?: return null
        return try {
            try {
                VersionedProjectionMetadata(
                    ProjectionMetadataCodec.decode(record.bytes),
                    record.version,
                )
            } catch (error: SecureMessagingCryptographicFailureException) {
                throw error
            } catch (error: Exception) {
                throw SecureMessagingCryptographicFailureException(
                    SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                    "Secure-message projection metadata is corrupt",
                    error,
                )
            }
        } finally {
            record.bytes.fill(0)
        }
    }

    private suspend fun writeMetadata(
        recordKey: String,
        metadata: ProjectionMetadata,
        expectedVersion: Long?,
    ) {
        val encoded = ProjectionMetadataCodec.encode(metadata)
        try {
            try {
                stateStore.write(METADATA_NAMESPACE, recordKey, expectedVersion, encoded)
            } catch (error: SecureMessagingStateUnavailableException) {
                throw SecureMessagingCryptographicFailureException(
                    SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                    "Secure-message projection metadata could not be committed",
                    error,
                )
            }
        } finally {
            encoded.fill(0)
        }
    }

    private fun validateMetadataMatches(
        metadata: ProjectionMetadata,
        durable: LibSignalCompanionRecord,
    ) {
        check(
            metadata.direction == durable.direction &&
                metadata.conversationId == durable.conversationId &&
                metadata.clientMessageId == durable.clientMessageId,
        ) { "Projection metadata does not match its durable secure message" }
        when (durable.direction) {
            LibSignalCompanionDirection.INBOUND -> check(
                metadata.serverMessageId == durable.messageId &&
                    metadata.deliveryState in INBOUND_DELIVERY_STATES,
            ) { "Incoming projection metadata is inconsistent" }
            LibSignalCompanionDirection.OUTBOUND -> check(
                metadata.serverMessageId == null || isCanonicalUuid(metadata.serverMessageId),
            ) { "Outgoing projection has an invalid server message ID" }
        }
    }

    private fun validateCompanionAddress(record: LibSignalCompanionRecord) {
        check(record.recordNamespace == COMPANION_NAMESPACE) {
            "Secure-message projection belongs to another namespace"
        }
        val expected = when (record.direction) {
            LibSignalCompanionDirection.INBOUND -> inboundRecordKey(record.messageId)
            LibSignalCompanionDirection.OUTBOUND -> outboundRecordKey(record.clientMessageId)
        }
        check(record.recordKey == expected) {
            "Secure-message projection key does not match its authenticated identifiers"
        }
    }

    private data class VersionedProjectionMetadata(
        val metadata: ProjectionMetadata,
        val version: Long,
    )

    companion object {
        const val COMPANION_NAMESPACE = "message-projection-v1"
        private const val METADATA_NAMESPACE = "message-metadata-v1"

        fun inboundRecordKey(messageId: String): String {
            require(isCanonicalUuid(messageId)) { "Invalid incoming message ID" }
            return "in:$messageId"
        }

        fun outboundRecordKey(clientMessageId: String): String {
            require(isCanonicalUuid(clientMessageId)) { "Invalid outgoing client message ID" }
            return "out:$clientMessageId"
        }
    }
}

private data class ProjectionMetadata(
    val direction: LibSignalCompanionDirection,
    val conversationId: String,
    val clientMessageId: String,
    val serverMessageId: String?,
    val sentAtEpochMillis: Long,
    val deliveryState: SecureMessagingProjectionDeliveryState,
) {
    init {
        require(isCanonicalUuid(conversationId)) { "Invalid projected conversation ID" }
        require(isCanonicalUuid(clientMessageId)) { "Invalid projected client message ID" }
        serverMessageId?.let {
            require(isCanonicalUuid(it)) { "Invalid projected server message ID" }
        }
        require(sentAtEpochMillis > 0) { "Invalid projected message timestamp" }
        require(
            (direction == LibSignalCompanionDirection.INBOUND) ==
                (deliveryState in INBOUND_DELIVERY_STATES),
        ) { "Projected message direction and delivery state disagree" }
    }
}

private object ProjectionMetadataCodec {
    fun encode(metadata: ProjectionMetadata): ByteArray {
        val output = WipingProjectionOutputStream(MAX_RECORD_BYTES)
        val data = DataOutputStream(output)
        try {
            data.write(MAGIC)
            data.writeInt(SCHEMA_VERSION)
            data.writeByte(metadata.direction.ordinal)
            data.writeString(metadata.conversationId)
            data.writeString(metadata.clientMessageId)
            data.writeBoolean(metadata.serverMessageId != null)
            metadata.serverMessageId?.let { data.writeString(it) }
            data.writeLong(metadata.sentAtEpochMillis)
            data.writeByte(metadata.deliveryState.ordinal)
            data.flush()
            return output.toOwnedByteArray()
        } finally {
            output.close()
        }
    }

    fun decode(bytes: ByteArray): ProjectionMetadata {
        require(bytes.size in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
            "Invalid secure-message projection metadata size"
        }
        val owned = bytes.copyOf()
        val input = ByteArrayInputStream(owned)
        try {
            return DataInputStream(input).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC)) {
                    "Invalid secure-message projection metadata header"
                }
                require(data.readInt() == SCHEMA_VERSION) {
                    "Unsupported secure-message projection metadata schema"
                }
                val direction = enumAt<LibSignalCompanionDirection>(data.readUnsignedByte())
                val conversationId = data.readString()
                val clientMessageId = data.readString()
                val serverMessageId = when (val present = data.readUnsignedByte()) {
                    0 -> null
                    1 -> data.readString()
                    else -> throw IllegalArgumentException(
                        "Invalid projected server-message marker: $present",
                    )
                }
                val sentAtEpochMillis = data.readLong()
                val state = enumAt<SecureMessagingProjectionDeliveryState>(
                    data.readUnsignedByte(),
                )
                require(input.available() == 0) {
                    "Secure-message projection metadata contains trailing bytes"
                }
                ProjectionMetadata(
                    direction,
                    conversationId,
                    clientMessageId,
                    serverMessageId,
                    sentAtEpochMillis,
                    state,
                )
            }
        } finally {
            owned.fill(0)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        try {
            require(encoded.size in 1..MAX_STRING_BYTES) { "Invalid projection string" }
            writeInt(encoded.size)
            write(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 1..MAX_STRING_BYTES) { "Invalid projection string size" }
        val encoded = ByteArray(size).also(::readFully)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
        } finally {
            encoded.fill(0)
        }
    }

    private inline fun <reified T : Enum<T>> enumAt(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal)
            ?: throw IllegalArgumentException("Invalid secure-message projection enum")

    private val MAGIC = byteArrayOf(0x4b, 0x49, 0x54, 0x50, 0x52, 0x4f, 0x4a)
    private const val SCHEMA_VERSION = 1
    private const val MAX_STRING_BYTES = 160
    private const val MIN_RECORD_BYTES = 7 + Int.SIZE_BYTES + 1 + 4 + 1 + 4 + 1 + 8 + 1
    private const val MAX_RECORD_BYTES = 1_024
}

private class WipingProjectionOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(count < maximumBytes) { "Secure-message projection metadata is too large" }
        super.write(value)
    }

    override fun write(value: ByteArray, offset: Int, length: Int) {
        require(length >= 0 && count.toLong() + length <= maximumBytes) {
            "Secure-message projection metadata is too large"
        }
        super.write(value, offset, length)
    }

    fun toOwnedByteArray(): ByteArray = buf.copyOf(count)

    override fun close() {
        buf.fill(0)
        count = 0
        super.close()
    }
}

private fun requireTimestamp(value: Instant): Long = value.toEpochMilli().also {
    require(it > 0) { "Invalid secure-message timestamp" }
}

private fun isCanonicalUuid(value: String): Boolean = CANONICAL_UUID.matches(value)

private val CANONICAL_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val INBOUND_DELIVERY_STATES = setOf(
    SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
    SecureMessagingProjectionDeliveryState.INBOUND_READ,
)

private const val READ_MARKER_PAGE_SIZE = 100
private const val MAX_READ_MARKER_PAGES = 100
