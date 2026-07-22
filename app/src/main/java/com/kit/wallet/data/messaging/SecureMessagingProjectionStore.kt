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
    /** Append-only states for a message authored on another device of the current account. */
    INBOUND_SELF_DELIVERED,
    INBOUND_SELF_READ,
    /** Append-only message-local rejection; never exposed through projection/UI queries. */
    INBOUND_SUPPRESSED,
    /** Append-only non-retryable failure for an attachment handle that can never be rebound. */
    OUTBOUND_PERMANENT_FAILURE,
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
        val messages = companionPage.records().mapNotNull { companion ->
            val durable = requireDurableLibSignalCompanionRecord(companion)
            validateCompanionAddress(durable)
            val metadata = readMetadata(durable.recordKey)
            if (metadata != null) validateMetadataMatches(metadata, durable)
            if (
                metadata?.deliveryState ==
                SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED
            ) {
                return@mapNotNull null
            }
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

    /**
     * Durably hides one message-local invalid inbound record while retaining its crypto commit.
     * The companion record is required for idempotent delivery acknowledgement after restart;
     * this append-only marker prevents that plaintext from ever entering a projection/UI page.
     */
    suspend fun recordInboundSuppressed(
        durableRecord: LibSignalCompanionRecord,
        sentAt: Instant,
    ) {
        val durable = requireInboundProjection(durableRecord)
        val suppressed = inboundMetadata(durable, sentAt).copy(
            deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED,
        )
        repeat(MAX_PROJECTION_WRITE_ATTEMPTS) { attempt ->
            val existing = readMetadataRecord(durable.recordKey)
            if (existing != null) {
                validateMetadataMatches(existing.metadata, durable)
                if (
                    existing.metadata.deliveryState ==
                    SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED
                ) {
                    return
                }
            }
            val updated = existing?.metadata?.copy(
                deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED,
            ) ?: suppressed
            try {
                writeMetadata(durable.recordKey, updated, existing?.version)
                signalChanged()
                return
            } catch (conflict: SecureMessagingStateConflictException) {
                if (attempt == MAX_PROJECTION_WRITE_ATTEMPTS - 1) throw conflict
            }
        }
        error("Inbound suppression write retry bound was exhausted")
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

    /** Selects only a durably authenticated, still-unread message from the direct peer. */
    suspend fun newestUnreadInboundMessageId(
        conversationId: String,
        peerUserId: String,
    ): String? {
        require(isCanonicalUuid(conversationId)) { "Invalid unread conversation ID" }
        require(isCanonicalUuid(peerUserId)) { "Invalid unread peer user ID" }
        return readInboundPeerProjections(conversationId, peerUserId)
            .asSequence()
            .filter {
                it.deliveryState == SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
            }
            .maxWithOrNull { left, right -> compareServerMessageOrder(left, right) }
            ?.serverMessageId
    }

    /**
     * Applies a successful server receipt only through a locally authenticated marker.
     *
     * The response can contain a newer canonical marker advanced by another device. It is used
     * only when that message is already an authenticated inbound projection from this peer and is
     * not behind the marker this device posted. A message arriving concurrently after the chosen
     * boundary therefore remains unread unless the canonical response specifically covers it.
     */
    suspend fun markInboundReadThrough(
        conversationId: String,
        peerUserId: String,
        requestedLastReadMessageId: String,
        canonicalLastReadMessageId: String,
        canonicalReadAt: Instant,
    ) {
        require(isCanonicalUuid(conversationId)) { "Invalid read-marker conversation ID" }
        require(isCanonicalUuid(peerUserId)) { "Invalid read-marker peer user ID" }
        require(isCanonicalUuid(requestedLastReadMessageId)) {
            "Invalid requested last-read message ID"
        }
        require(isCanonicalUuid(canonicalLastReadMessageId)) {
            "Invalid canonical last-read message ID"
        }
        val inbound = readInboundPeerProjections(conversationId, peerUserId)
        val requested = checkNotNull(
            inbound.singleOrNull { it.serverMessageId == requestedLastReadMessageId },
        ) { "The posted read marker has no authenticated inbound projection" }
        val canonical = inbound.singleOrNull {
            it.serverMessageId == canonicalLastReadMessageId
        } ?: return
        check(compareServerMessageOrder(canonical, requested) >= 0) {
            "The canonical read marker regressed behind the posted marker"
        }
        applyInboundReadThrough(inbound, canonical, canonicalReadAt)
    }

    /**
     * Applies this account's server-authenticated marker on another enrolled device.
     *
     * A newly enrolled device can receive a receipt event without owning the historical target
     * envelope. Such an unknown marker cannot be ordered against local plaintext projections and
     * is therefore ignored. When the target is present, only messages authenticated as coming from
     * the direct peer are advanced; self-authored cross-device copies remain sender-state bubbles.
     */
    suspend fun markInboundReadThroughCanonicalIfKnown(
        conversationId: String,
        peerUserId: String,
        canonicalLastReadMessageId: String,
        canonicalReadAt: Instant,
    ) {
        require(isCanonicalUuid(conversationId)) { "Invalid read-marker conversation ID" }
        require(isCanonicalUuid(peerUserId)) { "Invalid read-marker peer user ID" }
        require(isCanonicalUuid(canonicalLastReadMessageId)) {
            "Invalid canonical last-read message ID"
        }
        val inbound = readInboundPeerProjections(conversationId, peerUserId)
        val target = inbound.singleOrNull {
            it.serverMessageId == canonicalLastReadMessageId
        } ?: return
        applyInboundReadThrough(inbound, target, canonicalReadAt)
    }

    /** Applies a server-authenticated peer delivery event to any current-user-authored copy. */
    suspend fun markAuthoredDelivered(
        conversationId: String,
        messageId: String,
        currentUserId: String,
        deliveredAt: Instant,
    ) {
        require(isCanonicalUuid(conversationId)) { "Invalid delivery conversation ID" }
        require(isCanonicalUuid(messageId)) { "Invalid delivered message ID" }
        require(isCanonicalUuid(currentUserId)) { "Invalid delivery author user ID" }
        val target = findAuthoredProjection(conversationId, messageId, currentUserId) ?: return
        check(!deliveredAt.isBefore(target.sentAt)) {
            "The peer delivery time predates its target message"
        }
        if (advanceAuthored(target, currentUserId, authoredRead = false)) {
            signalChanged()
        }
    }

    /**
     * Applies the direct peer's monotonic last-read marker using the backend's sent-at then UUID
     * ordering, including messages that precede the target at an equal timestamp.
     */
    suspend fun markAuthoredReadThrough(
        conversationId: String,
        lastReadMessageId: String,
        currentUserId: String,
        readAt: Instant,
    ) {
        require(isCanonicalUuid(conversationId)) { "Invalid read-receipt conversation ID" }
        require(isCanonicalUuid(lastReadMessageId)) { "Invalid last-read message ID" }
        require(isCanonicalUuid(currentUserId)) { "Invalid read-receipt author user ID" }
        val authored = readAuthoredProjections(conversationId, currentUserId)
        val target = authored.singleOrNull { it.serverMessageId == lastReadMessageId } ?: return
        check(!readAt.isBefore(target.sentAt)) {
            "The peer read time predates its target message"
        }
        var changed = false
        try {
            authored.forEach { projected ->
                if (compareServerMessageOrder(projected, target) <= 0) {
                    changed = advanceAuthored(projected, currentUserId, authoredRead = true) || changed
                }
            }
        } finally {
            // A later write can fail or be cancelled after earlier records committed. Publish the
            // durable partial advance so the UI and the retry loop never wait for another event.
            if (changed) signalChanged()
        }
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
        repeat(MAX_PROJECTION_WRITE_ATTEMPTS) { attempt ->
            val existing = readMetadataRecord(durable.recordKey)
            existing?.let { validateMetadataMatches(it.metadata, durable) }
            when (existing?.metadata?.deliveryState) {
                SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED -> return
                SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE -> return
                SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
                SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED,
                SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
                -> return // A concurrent accepted send is stronger than stale-roster retirement.
                SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
                null,
                -> Unit
                else -> error("Only pending ciphertext can require a fresh encrypted retry")
            }
            val updated = ProjectionMetadata(
                direction = durable.direction,
                conversationId = durable.conversationId,
                clientMessageId = durable.clientMessageId,
                serverMessageId = null,
                sentAtEpochMillis = existing?.metadata?.sentAtEpochMillis
                    ?: durable.updatedAtEpochMillis,
                deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
            )
            try {
                writeMetadata(durable.recordKey, updated, existing?.version)
                signalChanged()
                return
            } catch (conflict: SecureMessagingStateConflictException) {
                if (attempt == MAX_PROJECTION_WRITE_ATTEMPTS - 1) throw conflict
            }
        }
        error("Outbound retirement write retry bound was exhausted")
    }

    /** Retires a media fanout whose server-side blob handle can never be claimed again. */
    suspend fun markOutboundPermanentFailure(
        durableRecord: LibSignalCompanionRecord,
    ) {
        val durable = requireDurableLibSignalCompanionRecord(durableRecord)
        requireOutboundProjection(durable)
        repeat(MAX_PROJECTION_WRITE_ATTEMPTS) { attempt ->
            val existing = readMetadataRecord(durable.recordKey)
            existing?.let { validateMetadataMatches(it.metadata, durable) }
            when (existing?.metadata?.deliveryState) {
                SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE -> return
                SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
                SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED,
                SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
                -> return // An authenticated accepted send is stronger than a late error.
                SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
                SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
                null,
                -> Unit
                else -> error("Only unresolved outbound ciphertext can fail permanently")
            }
            val updated = ProjectionMetadata(
                direction = durable.direction,
                conversationId = durable.conversationId,
                clientMessageId = durable.clientMessageId,
                serverMessageId = null,
                sentAtEpochMillis = existing?.metadata?.sentAtEpochMillis
                    ?: durable.updatedAtEpochMillis,
                deliveryState =
                SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
            )
            try {
                writeMetadata(durable.recordKey, updated, existing?.version)
                signalChanged()
                return
            } catch (conflict: SecureMessagingStateConflictException) {
                if (attempt == MAX_PROJECTION_WRITE_ATTEMPTS - 1) throw conflict
            }
        }
        error("Permanent outbound failure write retry bound was exhausted")
    }

    private suspend fun markOutboundSent(
        durable: LibSignalCompanionRecord,
        serverMessageId: String,
        sentAt: Instant,
    ) {
        val updated = ProjectionMetadata(
            direction = durable.direction,
            conversationId = durable.conversationId,
            clientMessageId = durable.clientMessageId,
            serverMessageId = serverMessageId,
            sentAtEpochMillis = requireTimestamp(sentAt),
            deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
        )
        repeat(MAX_PROJECTION_WRITE_ATTEMPTS) { attempt ->
            val existing = readMetadataRecord(durable.recordKey)
            if (existing != null) {
                validateMetadataMatches(existing.metadata, durable)
                when (existing.metadata.deliveryState) {
                    SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
                    SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED,
                    SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
                    -> {
                        check(
                            existing.metadata.copy(
                                deliveryState =
                                SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
                            ) == updated,
                        ) { "Outbound projection cannot change its immutable server receipt" }
                        // A send response or sync echo can race a delivery/read event, or replay
                        // after process death but before cursor commit. Never regress later state.
                        return
                    }
                    SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
                    SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
                    SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
                    -> Unit
                    else -> error("Outbound projection cannot use an inbound delivery state")
                }
            }
            try {
                writeMetadata(durable.recordKey, updated, existing?.version)
                signalChanged()
                return
            } catch (conflict: SecureMessagingStateConflictException) {
                if (attempt == MAX_PROJECTION_WRITE_ATTEMPTS - 1) throw conflict
                // Reload. An exact concurrent SENT/DELIVERED/READ commit is success; any changed
                // immutable receipt remains a hard failure on the next validation pass.
            }
        }
        error("Outbound projection write retry bound was exhausted")
    }

    private suspend fun readInboundPeerProjections(
        conversationId: String,
        peerUserId: String,
    ): List<SecureMessagingProjectedMessage> {
        val inbound = mutableListOf<SecureMessagingProjectedMessage>()
        var afterRecordKey: String? = null
        while (true) {
            val page = readPage(afterRecordKey, READ_MARKER_PAGE_SIZE)
            inbound += page.messages().filter { projected ->
                val durable = projected.durableRecord
                durable.direction == LibSignalCompanionDirection.INBOUND &&
                    durable.conversationId == conversationId &&
                    durable.sender.userId == peerUserId &&
                    projected.serverMessageId != null
            }
            val next = page.nextAfterRecordKey
            if (next == null) return inbound
            check(afterRecordKey == null || next > afterRecordKey!!) {
                "Secure-message inbound read-marker pagination did not advance"
            }
            afterRecordKey = next
        }
    }

    /** Matches the backend's monotonic message ordering: sent_at, then message UUID. */
    private fun compareServerMessageOrder(
        left: SecureMessagingProjectedMessage,
        right: SecureMessagingProjectedMessage,
    ): Int {
        val timeOrder = left.sentAt.compareTo(right.sentAt)
        if (timeOrder != 0) return timeOrder
        return checkNotNull(left.serverMessageId)
            .compareTo(checkNotNull(right.serverMessageId))
    }

    private suspend fun applyInboundReadThrough(
        inbound: List<SecureMessagingProjectedMessage>,
        target: SecureMessagingProjectedMessage,
        canonicalReadAt: Instant,
    ) {
        check(!canonicalReadAt.isBefore(target.sentAt)) {
            "The canonical read time predates its target message"
        }
        var changed = false
        try {
            inbound.forEach { projected ->
                if (compareServerMessageOrder(projected, target) <= 0) {
                    changed = advanceInboundRead(projected) || changed
                }
            }
        } finally {
            // Preserve observability when a multi-record marker is only partly applied before a
            // conflict, cancellation, or storage failure. Durable unread state drives the retry.
            if (changed) signalChanged()
        }
    }

    private suspend fun advanceInboundRead(
        projected: SecureMessagingProjectedMessage,
    ): Boolean {
        val durable = requireInboundProjection(projected.durableRecord)
        repeat(MAX_PROJECTION_WRITE_ATTEMPTS) { attempt ->
            val existing = checkNotNull(readMetadataRecord(durable.recordKey)) {
                "A server-addressable inbound projection omitted metadata"
            }
            validateMetadataMatches(existing.metadata, durable)
            check(existing.metadata.serverMessageId == projected.serverMessageId) {
                "Inbound read-marker target changed while it was being applied"
            }
            when (existing.metadata.deliveryState) {
                SecureMessagingProjectionDeliveryState.INBOUND_READ -> return false
                SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED -> Unit
                else -> error("A peer-authored inbound read marker targeted sender state")
            }
            try {
                writeMetadata(
                    recordKey = durable.recordKey,
                    metadata = existing.metadata.copy(
                        deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_READ,
                    ),
                    expectedVersion = existing.version,
                )
                return true
            } catch (conflict: SecureMessagingStateConflictException) {
                if (attempt == MAX_PROJECTION_WRITE_ATTEMPTS - 1) throw conflict
            }
        }
        error("Inbound read-marker write retry bound was exhausted")
    }

    private suspend fun findAuthoredProjection(
        conversationId: String,
        messageId: String,
        currentUserId: String,
    ): SecureMessagingProjectedMessage? = readAuthoredProjections(conversationId, currentUserId)
        .singleOrNull { it.serverMessageId == messageId }

    private suspend fun readAuthoredProjections(
        conversationId: String,
        currentUserId: String,
    ): List<SecureMessagingProjectedMessage> {
        val authored = mutableListOf<SecureMessagingProjectedMessage>()
        var afterRecordKey: String? = null
        while (true) {
            val page = readPage(afterRecordKey, READ_MARKER_PAGE_SIZE)
            authored += page.messages().filter { projected ->
                projected.durableRecord.conversationId == conversationId &&
                    projected.durableRecord.sender.userId == currentUserId &&
                    projected.serverMessageId != null
            }
            val next = page.nextAfterRecordKey
            if (next == null) return authored
            check(afterRecordKey == null || next > afterRecordKey!!) {
                "Secure-message authored-receipt pagination did not advance"
            }
            afterRecordKey = next
        }
    }

    private suspend fun advanceAuthored(
        projected: SecureMessagingProjectedMessage,
        currentUserId: String,
        authoredRead: Boolean,
    ): Boolean {
        check(projected.durableRecord.sender.userId == currentUserId) {
            "A peer receipt targeted a message authored by another user"
        }
        return when (projected.durableRecord.direction) {
            LibSignalCompanionDirection.OUTBOUND -> advanceOutbound(
                projected,
                if (authoredRead) {
                    SecureMessagingProjectionDeliveryState.OUTBOUND_READ
                } else {
                    SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED
                },
            )
            LibSignalCompanionDirection.INBOUND -> advanceSelfAuthoredInbound(
                projected,
                authoredRead,
            )
        }
    }

    private suspend fun advanceSelfAuthoredInbound(
        projected: SecureMessagingProjectedMessage,
        authoredRead: Boolean,
    ): Boolean {
        val durable = requireInboundProjection(projected.durableRecord)
        repeat(MAX_PROJECTION_WRITE_ATTEMPTS) { attempt ->
            val existing = checkNotNull(readMetadataRecord(durable.recordKey)) {
                "A server-addressable self-authored projection omitted metadata"
            }
            validateMetadataMatches(existing.metadata, durable)
            check(existing.metadata.serverMessageId == projected.serverMessageId) {
                "Self-authored receipt target changed while it was being applied"
            }
            val next = if (authoredRead) {
                when (existing.metadata.deliveryState) {
                    SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
                    SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
                    -> SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ
                    SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ -> return false
                    else -> error("A peer read targeted a non-authored inbound state")
                }
            } else {
                when (existing.metadata.deliveryState) {
                    SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED ->
                        SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED
                    SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
                    SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ,
                    -> return false
                    else -> error("A peer delivery targeted a non-authored inbound state")
                }
            }
            try {
                writeMetadata(
                    recordKey = durable.recordKey,
                    metadata = existing.metadata.copy(deliveryState = next),
                    expectedVersion = existing.version,
                )
                return true
            } catch (conflict: SecureMessagingStateConflictException) {
                if (attempt == MAX_PROJECTION_WRITE_ATTEMPTS - 1) throw conflict
            }
        }
        error("Self-authored receipt write retry bound was exhausted")
    }

    private suspend fun advanceOutbound(
        projected: SecureMessagingProjectedMessage,
        target: SecureMessagingProjectionDeliveryState,
    ): Boolean {
        require(
            target == SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED ||
                target == SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
        ) { "Invalid outbound receipt target" }
        val durable = projected.durableRecord
        requireOutboundProjection(durable)
        repeat(MAX_PROJECTION_WRITE_ATTEMPTS) { attempt ->
            val existing = checkNotNull(readMetadataRecord(durable.recordKey)) {
                "A server-addressable outbound projection omitted metadata"
            }
            validateMetadataMatches(existing.metadata, durable)
            check(existing.metadata.serverMessageId == projected.serverMessageId) {
                "Outbound receipt target changed while it was being applied"
            }
            val current = existing.metadata.deliveryState
            val next = when (target) {
                SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED -> when (current) {
                    SecureMessagingProjectionDeliveryState.OUTBOUND_SENT -> target
                    SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED,
                    SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
                    -> return false
                    else -> return false
                }
                SecureMessagingProjectionDeliveryState.OUTBOUND_READ -> when (current) {
                    SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
                    SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED,
                    -> target
                    SecureMessagingProjectionDeliveryState.OUTBOUND_READ -> return false
                    else -> return false
                }
                else -> error("Unreachable outbound receipt target")
            }
            try {
                writeMetadata(
                    recordKey = durable.recordKey,
                    metadata = existing.metadata.copy(deliveryState = next),
                    expectedVersion = existing.version,
                )
                return true
            } catch (conflict: SecureMessagingStateConflictException) {
                if (attempt == MAX_PROJECTION_WRITE_ATTEMPTS - 1) throw conflict
            }
        }
        error("Outbound receipt write retry bound was exhausted")
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
        if (
            expected.deliveryState == SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED &&
            existing.deliveryState in INBOUND_DELIVERY_STATES &&
            existing.copy(
                deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
            ) == expected
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
    SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
    SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ,
    SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED,
)

private const val READ_MARKER_PAGE_SIZE = 100
private const val MAX_PROJECTION_WRITE_ATTEMPTS = 3
