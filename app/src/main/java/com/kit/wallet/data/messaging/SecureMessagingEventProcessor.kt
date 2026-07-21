package com.kit.wallet.data.messaging

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serial, crash-safe consumer for the validated encrypted event stream.
 *
 * The transport owns all network validation and opaque event capabilities. This processor owns
 * only durable ordering: ratchet + companion state, projection metadata, delivery acknowledgement,
 * cursor persistence, and finally confirmation of the exact in-memory batch.
 */
@Singleton
internal class SecureMessagingEventProcessor @Inject constructor(
    private val cryptoEngine: SecureMessagingCryptoEngine,
    private val projections: SecureMessagingProjectionStore,
    private val cursors: SecureMessagingSyncCursorStore,
    private val notifications: SecureMessagingIncomingNotificationSink =
        NoOpSecureMessagingIncomingNotificationSink,
    private val currentActivationRevocation: SecureMessagingCurrentActivationRevocation =
        NoOpSecureMessagingCurrentActivationRevocation,
) {
    private class SessionState(
        val session: RemoteSecureMessagingTransport.Session,
        var checkpoint: RemoteSecureMessagingTransport.Session.SyncCheckpoint,
        var cursorRecordVersion: Long?,
    ) {
        var batch: RemoteSecureMessagingTransport.Session.SyncBatch? = null
        var events: List<RemoteSecureMessagingTransport.Session.SyncEvent> = emptyList()
        var eventIndex: Int = 0
        val deliveryTokens = mutableListOf<RemoteSecureMessagingTransport.Session.DeliveryToken>()
        var persistedBatchPosition: SecureMessagingSyncResumePosition? = null
        var pendingDecryption: PendingDecryption? = null
        var conversations: Map<String, RemoteSecureMessagingTransport.Session.DirectConversation>? =
            null
        val historicalPlans = mutableMapOf<HistoricalRosterKey, HistoricalRosterPlan>()

        fun beginBatch(value: RemoteSecureMessagingTransport.Session.SyncBatch) {
            check(batch == null && eventIndex == 0 && deliveryTokens.isEmpty()) {
                "Secure-messaging batch state was not fully finalized"
            }
            batch = value
            events = value.events()
        }

        fun finishBatch(next: RemoteSecureMessagingTransport.Session.SyncCheckpoint) {
            checkpoint = next
            batch = null
            events = emptyList()
            eventIndex = 0
            deliveryTokens.clear()
            persistedBatchPosition = null
            pendingDecryption = null
        }

        fun invalidateConversation(conversationId: String) {
            conversations = null
            historicalPlans.keys.removeAll { it.conversationId == conversationId }
        }
    }

    private data class HistoricalRosterKey(
        val conversationId: String,
        val rosterRevision: String,
    )

    private data class HistoricalRosterPlan(
        val conversation: RemoteSecureMessagingTransport.Session.DirectConversation,
        val roster: RemoteSecureMessagingTransport.Session.AuthoritativeRoster,
        val plan: SecureMessagingEncryptionPlan,
    )

    private data class PendingDecryption(
        val envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
        val request: SecureMessagingDecryptionRequest,
    )

    private val mutex = Mutex()
    private var currentState: SessionState? = null

    suspend fun synchronize(session: RemoteSecureMessagingTransport.Session) = mutex.withLock {
        try {
            val state = stateFor(session)
            while (true) {
                if (state.batch == null) {
                    state.beginBatch(session.sync(state.checkpoint, SYNC_PAGE_SIZE))
                }
                val batch = checkNotNull(state.batch)
                processEvents(state)
                acknowledgeIncoming(state)
                val position = persistBatchCursor(state, batch)
                val hasMore = batch.hasMore
                val next = session.confirmProcessed(batch, position)
                state.finishBatch(next)
                if (!hasMore) return@withLock
            }
        } catch (error: SecureMessagingCryptographicFailureException) {
            currentState = null
            runCatching { session.quarantine(error) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /**
     * Reconciles every durable pending fanout after sync has had the first chance to supply a
     * server echo. Re-sends reuse the original client message ID and exact ciphertext; a changed
     * roster retires the stale fanout instead of ever encrypting or delivering it to the wrong
     * device set.
     */
    suspend fun recoverPendingOutbox(
        session: RemoteSecureMessagingTransport.Session,
    ) = mutex.withLock {
        try {
            val pending = pendingOutboundRecords()
            if (pending.isEmpty()) return@withLock

            val conversations = session.directConversations().associateBy { it.conversationId }
            val plans = mutableMapOf<String, SecureMessagingEncryptionPlan>()
            pending.forEach { durable ->
                if (durable.sender.userId != session.binding.userId ||
                    durable.sender.serverDeviceId != session.binding.serverDeviceId
                ) {
                    throw SecureMessagingCryptographicFailureException(
                        SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                        "A pending outbox record belongs to another authenticated sender",
                    )
                }
                val conversation = conversations[durable.conversationId]
                if (conversation == null) {
                    projections.markOutboundRetryRequired(durable)
                    return@forEach
                }
                val plan = plans.getOrPut(durable.conversationId) {
                    val roster = session.roster(conversation)
                    session.encryptionPlan(conversation, roster)
                }
                val planSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
                val durableRecipients = durable.ciphertextFanout()
                    .map(LibSignalPersistedEnvelope::recipient)
                    .toSet()
                val stillAuthoritative =
                    durable.conversationId == planSnapshot.conversationId &&
                        durable.rosterRevision == planSnapshot.rosterRevision &&
                        durable.sender == planSnapshot.sender &&
                        durableRecipients == planSnapshot.recipients.addressSet()
                if (!stillAuthoritative) {
                    projections.markOutboundRetryRequired(durable)
                    return@forEach
                }

                val encrypted = try {
                    SecureMessagingCryptoWireMapper.retryEncryption(durable, plan)
                } catch (error: SecureMessagingCryptographicFailureException) {
                    throw error
                } catch (error: Exception) {
                    throw SecureMessagingCryptographicFailureException(
                        SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                        "A pending outbox record failed durable fanout validation",
                        error,
                    )
                }
                val receipt = session.send(conversation, encrypted)
                projections.markOutboundSent(durable, receipt)
            }
        } catch (error: SecureMessagingCryptographicFailureException) {
            runCatching { session.quarantine(error) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private suspend fun pendingOutboundRecords(): List<LibSignalCompanionRecord> {
        val pending = mutableListOf<LibSignalCompanionRecord>()
        var after: String? = null
        repeat(MAX_OUTBOX_PAGES) {
            val page = projections.readPage(afterRecordKey = after, limit = OUTBOX_PAGE_SIZE)
            page.messages().forEach { projected ->
                if (projected.deliveryState ==
                    SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING
                ) {
                    val durable = projected.durableRecord
                    if (durable.direction != LibSignalCompanionDirection.OUTBOUND) {
                        throw SecureMessagingCryptographicFailureException(
                            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                            "Pending projection metadata points at a non-outbound message",
                        )
                    }
                    pending += durable
                }
            }
            val next = page.nextAfterRecordKey ?: return pending
            check(after == null || next > after!!) {
                "Secure-message outbox pagination did not advance"
            }
            after = next
        }
        throw SecureMessagingCryptographicFailureException(
            SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
            "Secure-message outbox exceeds the bounded recovery scan",
        )
    }

    private suspend fun stateFor(
        session: RemoteSecureMessagingTransport.Session,
    ): SessionState {
        currentState?.takeIf { it.session === session }?.let { return it }
        val restored = cursors.load()
        return SessionState(
            session = session,
            checkpoint = session.initialSyncCheckpoint(restored?.position),
            cursorRecordVersion = restored?.recordVersion,
        ).also { currentState = it }
    }

    private suspend fun processEvents(state: SessionState) {
        while (state.eventIndex < state.events.size) {
            when (val event = state.events[state.eventIndex]) {
                is RemoteSecureMessagingTransport.Session.IncomingEnvelope ->
                    processIncoming(state, event)
                is RemoteSecureMessagingTransport.Session.OutboundEvent ->
                    processOutbound(event)
                is RemoteSecureMessagingTransport.Session.RosterRefreshEvent ->
                    processRosterRefresh(state, event)
                is RemoteSecureMessagingTransport.Session.MetadataEvent ->
                    state.invalidateConversation(event.conversationId)
            }
            state.eventIndex++
        }
    }

    private suspend fun processRosterRefresh(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.RosterRefreshEvent,
    ) {
        val binding = state.session.binding
        val targetsCurrentDevice =
            event.affectedUserId == binding.userId &&
                event.affectedDeviceId == binding.serverDeviceId
        val revokesCurrentActivation =
            (event.eventType == ALL_DEVICES_REVOKED_EVENT &&
                event.affectedUserId == binding.userId) ||
                (event.eventType == DEVICE_REVOKED_EVENT && targetsCurrentDevice)
        val changesCurrentIdentity =
            event.eventType == IDENTITY_CHANGED_EVENT && targetsCurrentDevice
        if (revokesCurrentActivation || changesCurrentIdentity) {
            val reason = if (revokesCurrentActivation) {
                SecureMessagingQuarantineReason.CURRENT_DEVICE_REVOKED
            } else {
                SecureMessagingQuarantineReason.IDENTITY_CHANGED
            }
            val failure = SecureMessagingCryptographicFailureException(
                quarantineReason = reason,
                message = if (revokesCurrentActivation) {
                    "The active secure-messaging device was revoked"
                } else {
                    "The active secure-messaging identity changed"
                },
            )
            // Invalidate READY synchronously before notification cancellation or disk erasure can
            // suspend. The outer failure path cannot revive this transport generation.
            state.session.quarantine(failure)
            currentState = null
            runCatching { notifications.cancelAll() }
            try {
                currentActivationRevocation.eraseSecureMessagingState()
            } catch (erasureFailure: Throwable) {
                failure.addSuppressed(erasureFailure)
            }
            throw failure
        }

        if (event.eventType in PEER_STATE_RETIREMENT_EVENTS) {
            state.session.retireRemoteDevices(
                engine = cryptoEngine,
                affectedUserId = event.affectedUserId,
                affectedServerDeviceId = event.affectedDeviceId,
            )
        }
        state.invalidateConversation(event.conversationId)
    }

    private suspend fun processIncoming(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
    ) {
        var durable = projections.readInbound(envelope.messageId)
        var decrypted: SecureMessagingCommittedResult.Decrypted? = null
        try {
            if (durable == null) {
                val pending = state.pendingDecryption
                val request = if (pending == null) {
                    val historical = historicalPlan(state, envelope)
                    state.session.decryptionRequest(
                        envelope,
                        historical.roster,
                        historical.plan,
                    ).also { issued ->
                        state.pendingDecryption = PendingDecryption(envelope, issued)
                    }
                } else {
                    check(pending.envelope === envelope) {
                        "A decryption request was retained for another sync event"
                    }
                    pending.request
                }
                decrypted = commitDecryption(state.session, request, envelope.messageId)
                state.pendingDecryption = null
                durable = checkNotNull(projections.readInbound(envelope.messageId)) {
                    "Committed incoming message omitted its durable projection"
                }
            }

            val persisted = checkNotNull(durable)
            if (!projections.isInboundPublicationRecorded(persisted, envelope.sentAt)) {
                // The companion record is already durable, but projection metadata is committed
                // only after publication. A failure therefore leaves retryable work, while the
                // stable message ID lets the sink replace an ambiguously published notification.
                try {
                    notifications.publish(
                        SecureMessagingIncomingNotification(
                            messageId = persisted.messageId,
                            conversationId = persisted.conversationId,
                            sessionEpoch = state.session.binding.sessionEpoch,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw SecureMessagingNotificationPublicationException(error)
                }
            }
            projections.recordInbound(persisted, envelope.sentAt)
            val token = if (decrypted != null) {
                state.session.deliveryToken(envelope, decrypted)
            } else {
                state.session.deliveryTokenFromDurableState(envelope, persisted)
            }
            state.deliveryTokens += token
        } finally {
            decrypted?.close()
        }
    }

    private suspend fun commitDecryption(
        session: RemoteSecureMessagingTransport.Session,
        request: SecureMessagingDecryptionRequest,
        messageId: String,
    ): SecureMessagingCommittedResult.Decrypted {
        val transaction = session.openCryptoTransaction(cryptoEngine)
        return try {
            transaction.stageDecryption(request, projections.inboundIntent(messageId))
            transaction.commit() as? SecureMessagingCommittedResult.Decrypted
                ?: error("A decryption transaction returned another operation result")
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { transaction.abort() }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private suspend fun processOutbound(
        event: RemoteSecureMessagingTransport.Session.OutboundEvent,
    ) {
        val durable = projections.readOutbound(event.clientMessageId) ?: return
        projections.markOutboundSent(durable, event)
    }

    private suspend fun historicalPlan(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
    ): HistoricalRosterPlan {
        val key = HistoricalRosterKey(envelope.conversationId, envelope.rosterRevision)
        state.historicalPlans[key]?.let { return it }
        val conversations = state.conversations ?: state.session.directConversations()
            .associateBy(RemoteSecureMessagingTransport.Session.DirectConversation::conversationId)
            .also { state.conversations = it }
        val conversation = checkNotNull(conversations[envelope.conversationId]) {
            "Incoming secure message belongs to an unavailable direct conversation"
        }
        val roster = state.session.historicalRoster(conversation, envelope.rosterRevision)
        return HistoricalRosterPlan(
            conversation = conversation,
            roster = roster,
            plan = state.session.decryptionPlan(conversation, roster),
        ).also { state.historicalPlans[key] = it }
    }

    private suspend fun acknowledgeIncoming(state: SessionState) {
        if (state.deliveryTokens.isEmpty()) return
        state.session.acknowledgeDelivery(state.deliveryTokens.toList())
        state.deliveryTokens.clear()
    }

    private suspend fun persistBatchCursor(
        state: SessionState,
        batch: RemoteSecureMessagingTransport.Session.SyncBatch,
    ): SecureMessagingSyncResumePosition {
        state.persistedBatchPosition?.let { return it }
        val position = state.session.resumePositionAfter(batch)
        val version = try {
            cursors.save(position, state.cursorRecordVersion)
        } catch (writeFailure: Throwable) {
            val loaded = try {
                cursors.load()
            } catch (loadFailure: Throwable) {
                writeFailure.addSuppressed(loadFailure)
                throw writeFailure
            }
            val expected = requireSecureMessagingSyncResumePosition(position)
            val actual = loaded?.let { requireSecureMessagingSyncResumePosition(it.position) }
            if (loaded == null || actual != expected) throw writeFailure
            loaded.recordVersion
        }
        state.cursorRecordVersion = version
        state.persistedBatchPosition = position
        return position
    }

    private companion object {
        const val SYNC_PAGE_SIZE = 50
        const val OUTBOX_PAGE_SIZE = 100
        const val MAX_OUTBOX_PAGES = 100
        const val DEVICE_REVOKED_EVENT = "device.revoked"
        const val ALL_DEVICES_REVOKED_EVENT = "devices.revoked"
        const val IDENTITY_CHANGED_EVENT = "identity.changed"
        val PEER_STATE_RETIREMENT_EVENTS = setOf(
            DEVICE_REVOKED_EVENT,
            ALL_DEVICES_REVOKED_EVENT,
            IDENTITY_CHANGED_EVENT,
        )
    }
}

/** Initial catch-up stage used before the coordinator publishes a READY session. */
@Singleton
class RealSecureMessagingInitialSyncActivation @Inject internal constructor(
    private val processor: SecureMessagingEventProcessor,
) : SecureMessagingInitialSyncActivation {
    override suspend fun synchronize(session: RemoteSecureMessagingTransport.Session) {
        processor.synchronize(session)
    }
}
