package com.kit.wallet.data.messaging

import androidx.annotation.VisibleForTesting
import com.kit.wallet.data.remote.ScheduleContract
import com.kit.wallet.data.remote.ScheduledPaymentStatus
import com.kit.wallet.data.repository.GroupPaymentRepository
import com.kit.wallet.data.repository.ServerScheduledPaymentRepository
import com.kit.wallet.data.repository.WalletRefreshTrigger
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.SecureMessagingWireValidationException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface SecureMessagingHistoryContinuationScheduler {
    fun schedule(delayMillis: Long)
}

internal object NoOpSecureMessagingHistoryContinuationScheduler :
    SecureMessagingHistoryContinuationScheduler {
    override fun schedule(delayMillis: Long) = Unit
}

/** A non-secret metadata hint contradicted a successful authoritative read and cannot recover. */
private class DeterministicSyncEventPoisonException(message: String) : Exception(message)

@VisibleForTesting
internal data class SecureMessagingHistoryDrainResult(
    val workUnits: Int,
    val madeProgress: Boolean,
    val hadFailure: Boolean,
    val pending: Boolean,
)

/** Round-robins page-sized history work and gives every non-failing task a chance in this run. */
@VisibleForTesting
internal suspend fun drainSecureMessagingHistoryWork(
    maxWorkUnits: Int,
    batchSize: Int,
    loadPending: suspend (limit: Int, excludedRecordKeys: Set<String>) ->
        List<SecureMessagingHistoryBackfillTask>,
    attempt: suspend (SecureMessagingHistoryBackfillTask) -> Boolean,
): SecureMessagingHistoryDrainResult {
    require(maxWorkUnits > 0)
    require(batchSize > 0)
    val failed = mutableSetOf<String>()
    val deferredUntilNextRound = mutableSetOf<String>()
    var workUnits = 0
    var madeProgress = false

    while (workUnits < maxWorkUnits) {
        val tasks = loadPending(
            minOf(batchSize, maxWorkUnits - workUnits),
            failed + deferredUntilNextRound,
        )
        if (tasks.isEmpty()) {
            if (deferredUntilNextRound.isEmpty() ||
                loadPending(1, failed).isEmpty()
            ) {
                break
            }
            deferredUntilNextRound.clear()
            continue
        }
        tasks.forEach { task ->
            if (workUnits == maxWorkUnits) return@forEach
            workUnits++
            if (attempt(task)) {
                madeProgress = true
                deferredUntilNextRound += task.recordKey
            } else {
                failed += task.recordKey
                deferredUntilNextRound -= task.recordKey
            }
        }
    }

    return SecureMessagingHistoryDrainResult(
        workUnits = workUnits,
        madeProgress = madeProgress,
        hadFailure = failed.isNotEmpty(),
        pending = loadPending(1, emptySet()).isNotEmpty(),
    )
}

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
    @Suppress("UNUSED_PARAMETER")
    currentActivationRevocation: SecureMessagingCurrentActivationRevocation =
        NoOpSecureMessagingCurrentActivationRevocation,
    private val historyContinuationScheduler: SecureMessagingHistoryContinuationScheduler =
        NoOpSecureMessagingHistoryContinuationScheduler,
    private val walletRefresh: WalletRefreshTrigger = NoOpWalletRefreshTrigger,
    private val systemEvents: ConversationSystemEventStore? = null,
    private val scheduledPayments: ServerScheduledPaymentRepository? = null,
    private val groupPayments: GroupPaymentRepository? = null,
) {
    private class SessionState(
        val session: RemoteSecureMessagingTransport.Session,
        var checkpoint: RemoteSecureMessagingTransport.Session.SyncCheckpoint,
        var cursorRecordVersion: Long?,
    ) {
        val activation: SecureMessagingActivationCapability = session.activationCapability()
        var batch: RemoteSecureMessagingTransport.Session.SyncBatch? = null
        var events: List<RemoteSecureMessagingTransport.Session.SyncEvent> = emptyList()
        var eventIndex: Int = 0
        val deliveryTokens = mutableListOf<RemoteSecureMessagingTransport.Session.DeliveryToken>()
        var persistedBatchPosition: SecureMessagingSyncResumePosition? = null
        var pendingDecryption: PendingDecryption? = null
        var conversations: Map<String, RemoteSecureMessagingTransport.Session.SecureConversation>? =
            null
        val historicalPlans = mutableMapOf<HistoricalRosterKey, HistoricalRosterPlan>()

        /**
         * Conversation IDs a fresh authoritative list still omitted, remembered for the current
         * batch so one departed conversation's event backlog costs a single refetch on the serial
         * sync path instead of one per event.
         */
        val absentConversations = mutableSetOf<String>()

        /**
         * Historical rosters the transport refused to certify, remembered for the current batch
         * so a backlog sealed under one dead revision costs a single refused fetch instead of one
         * per envelope.
         */
        val rejectedRosters = mutableSetOf<HistoricalRosterKey>()

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
            absentConversations.clear()
            rejectedRosters.clear()
        }

        fun invalidateConversation(conversationId: String) {
            conversations = null
            absentConversations.clear()
            historicalPlans.keys.removeAll { it.conversationId == conversationId }
            rejectedRosters.removeAll { it.conversationId == conversationId }
        }
    }

    private data class HistoricalRosterKey(
        val conversationId: String,
        val rosterRevision: String,
    )

    private data class HistoricalRosterPlan(
        val conversation: RemoteSecureMessagingTransport.Session.SecureConversation,
        val roster: RemoteSecureMessagingTransport.Session.AuthoritativeRoster,
        val plan: SecureMessagingEncryptionPlan,
    )

    private data class PendingDecryption(
        val envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
        val request: SecureMessagingDecryptionRequest,
    )

    private val mutex = Mutex()
    private var currentState: SessionState? = null
    private var historyPreparedActivation: SecureMessagingActivationCapability? = null
    private var historyReconciledActivation: SecureMessagingActivationCapability? = null

    private suspend fun <T> RemoteSecureMessagingTransport.Session.withProjectionLease(
        operation: suspend SecureMessagingProjectionStore.() -> T,
    ): T = projections.withActivationLease(activationCapability(), operation = operation)

    private suspend fun <T> SessionState.withProjectionLease(
        operation: suspend SecureMessagingProjectionStore.() -> T,
    ): T = projections.withActivationLease(activation, operation = operation)

    private suspend fun <T> RemoteSecureMessagingTransport.Session.withCursorLease(
        operation: suspend SecureMessagingSyncCursorStore.() -> T,
    ): T = cursors.withActivationLease(activationCapability(), operation)

    private suspend fun <T> SessionState.withCursorLease(
        operation: suspend SecureMessagingSyncCursorStore.() -> T,
    ): T = cursors.withActivationLease(activation, operation)

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
            val pending = pendingOutboundRecords(session)
            if (pending.isEmpty()) return@withLock

            val conversations = session.conversations().associateBy { it.conversationId }
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
                    session.withProjectionLease { markOutboundRetryRequired(durable) }
                    return@forEach
                }
                val reaction = KitReactionMessage.parse(durable.authenticatedText)
                val edit = KitEditMessage.parse(durable.authenticatedText)
                val plan = if (reaction != null || edit != null) {
                    try {
                        val roster = session.roster(conversation)
                        if (reaction != null) {
                            session.requireReactionCapability(conversation, roster)
                        } else {
                            session.requireMessageEditCapability(conversation, roster)
                        }
                        session.encryptionPlan(conversation, roster)
                    } catch (_: SecureMessagingConversationCapabilityUnavailableException) {
                        // Neither annotation can reach the roster, and neither has a standalone
                        // retry bubble. Retire only its ciphertext so it disappears locally and a
                        // stale device cannot starve later text/media or another conversation's
                        // recovery. The reaction can be added, or the correction written, again
                        // once every device supports it. The original wording still stands.
                        session.withProjectionLease { markOutboundPermanentFailure(durable) }
                        return@forEach
                    }
                } else {
                    plans.getOrPut(durable.conversationId) {
                        val roster = session.roster(conversation)
                        session.encryptionPlan(conversation, roster)
                    }
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
                    session.withProjectionLease { markOutboundRetryRequired(durable) }
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
                // Attachment metadata is server-visible but is authenticated inside the durable
                // Signal plaintext descriptor. Re-derive it on every retry just as the initial
                // send path does; retrying an attachment as ordinary encrypted text would create
                // a message that the recipient can decrypt but can never authorize/download.
                val attachments = kitMediaAttachmentsFor(durable.authenticatedText)
                if (attachments.isEmpty() &&
                    KitMediaFamily.isFamilyText(durable.authenticatedText)
                ) {
                    // Reserved text that no longer strictly parses has no rows to bind. Sending
                    // it as ordinary encrypted text would put an unauthenticated descriptor on
                    // the wire, so this fanout fails closed instead.
                    session.withProjectionLease { markOutboundPermanentFailure(durable) }
                    return@forEach
                }
                val receipt = try {
                    session.send(conversation, encrypted, attachments)
                } catch (error: KitWalletApiException) {
                    if (attachments.isNotEmpty()) {
                        if (error.isPermanentAttachmentBindingFailure()) {
                            // An unclaimed blob expires after 24 hours, and a handle claimed by
                            // another accepted message can never be reused. Retire only this
                            // durable media fanout so it cannot starve later outbox entries.
                            session.withProjectionLease {
                                markOutboundPermanentFailure(durable)
                            }
                            return@forEach
                        }
                        if (error.isAttachmentCompatibilityFailure()) {
                            // The blob can still be valid, but a code-12 roster device or a
                            // temporarily disabled content profile cannot accept it today. Stop
                            // automatic recovery from wedging later text; retain explicit retry
                            // for after every device/server profile supports attachments.
                            session.withProjectionLease { markOutboundRetryRequired(durable) }
                            return@forEach
                        }
                    }
                    throw error
                }
                session.withProjectionLease { markOutboundSent(durable, receipt) }
            }
        } catch (error: SecureMessagingCryptographicFailureException) {
            runCatching { session.quarantine(error) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /**
     * Restores retained sources first, reconciles every current same-account target, then advances
     * bounded round-robin history work after ordinary sync is safely committed. Failures remain
     * pending without starving later conversations or holding the live cursor/current outbox.
     */
    suspend fun recoverPendingHistory(
        session: RemoteSecureMessagingTransport.Session,
    ) = mutex.withLock {
        val conversations = try {
            session.conversations()
                .associateBy(RemoteSecureMessagingTransport.Session.SecureConversation::conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isRecoverableSecureMessagingStateLoss(error)) throw error
            return@withLock
        }

        if (!prepareHistorySources(session, conversations.keys)) return@withLock
        val rosters = reconcileHistoryTargets(session, conversations).toMutableMap()
        val result = drainSecureMessagingHistoryWork(
            maxWorkUnits = MAX_HISTORY_WORK_UNITS_PER_RUN,
            batchSize = MAX_HISTORY_TASKS_PER_BATCH,
            loadPending = { limit, excluded ->
                session.withProjectionLease {
                    pendingHistoryBackfills(limit, excluded)
                }
            },
            attempt = { task ->
                attemptHistoryTask(session, task, conversations, rosters)
            },
        )
        if (result.pending) {
            scheduleHistoryContinuation(
                if (result.madeProgress) 0L else HISTORY_FAILURE_RETRY_DELAY_MILLIS,
            )
        }
    }

    private suspend fun prepareHistorySources(
        session: RemoteSecureMessagingTransport.Session,
        allowedConversationIds: Set<String>,
    ): Boolean {
        val activation = session.activationCapability()
        if (historyPreparedActivation === activation) return true
        try {
            projections.restoreArchivedHistory(
                activation = activation,
                currentUserId = session.binding.userId,
                allowedConversationIds = allowedConversationIds,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isRetryableHistoryArchiveRestoreFailure(error)) {
                scheduleHistoryContinuation(HISTORY_FAILURE_RETRY_DELAY_MILLIS)
                return false
            }
            // A permanently missing/corrupt optional display archive has no recoverable source.
            // Continue with any live retained projections rather than blocking ordinary messaging.
        }
        historyPreparedActivation = activation
        return true
    }

    private suspend fun reconcileHistoryTargets(
        session: RemoteSecureMessagingTransport.Session,
        conversations: Map<String, RemoteSecureMessagingTransport.Session.SecureConversation>,
    ): Map<String, RemoteSecureMessagingTransport.Session.AuthoritativeRoster> {
        val activation = session.activationCapability()
        if (historyReconciledActivation === activation) return emptyMap()
        val rosters = mutableMapOf<String, RemoteSecureMessagingTransport.Session.AuthoritativeRoster>()
        conversations.values.forEach { conversation ->
            val roster = session.roster(conversation)
            rosters[conversation.conversationId] = roster
            session.historyBackfillTargets(conversation, roster).forEach { target ->
                session.withProjectionLease {
                    enqueueHistoryBackfill(
                        conversationId = conversation.conversationId,
                        targetDeviceId = target.deviceId,
                        targetEnrollmentEpoch = target.enrollmentEpoch,
                        // Code 20 could complete this before the account archive was restored.
                        reopenCompleted = true,
                    )
                }
            }
        }
        historyReconciledActivation = activation
        return rosters
    }

    private suspend fun attemptHistoryTask(
        session: RemoteSecureMessagingTransport.Session,
        task: SecureMessagingHistoryBackfillTask,
        conversations: Map<String, RemoteSecureMessagingTransport.Session.SecureConversation>,
        rosters: MutableMap<String, RemoteSecureMessagingTransport.Session.AuthoritativeRoster>,
    ): Boolean = try {
        backfillRetainedHistoryPage(session, task, conversations, rosters)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (changed: SecureMessagingAuthenticationEpochChangedException) {
        throw changed
    } catch (error: KitWalletApiException) {
        when {
            error.code in TERMINAL_HISTORY_TASK_CODES -> {
                updateHistoryTaskBestEffort(
                    session,
                    task,
                    nextCursor = null,
                    completed = true,
                )
                true
            }
            error.code == HISTORY_CURSOR_INVALID && task.nextCursor != null -> {
                updateHistoryTaskBestEffort(
                    session,
                    task,
                    nextCursor = null,
                    completed = false,
                )
                true
            }
            else -> false
        }
    } catch (error: Exception) {
        if (isRecoverableSecureMessagingStateLoss(error)) throw error
        false
    }

    private fun scheduleHistoryContinuation(delayMillis: Long) {
        runCatching { historyContinuationScheduler.schedule(delayMillis) }
    }

    private fun isRetryableHistoryArchiveRestoreFailure(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.toList()
        if (error.hasPermanentlyMissingAccountMessageArchiveKey()) return false
        return causes.any {
            it is AccountMessageArchiveKeyUnavailableException ||
                it is AccountMessageArchiveConflictException ||
                it is IOException
        }
    }

    private suspend fun updateHistoryTaskBestEffort(
        session: RemoteSecureMessagingTransport.Session,
        task: SecureMessagingHistoryBackfillTask,
        nextCursor: String?,
        completed: Boolean,
    ) {
        try {
            session.withProjectionLease {
                updateHistoryBackfill(task, nextCursor, completed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isRecoverableSecureMessagingStateLoss(error)) throw error
            // A later synchronization re-reads the still-durable task.
        }
    }

    private suspend fun pendingOutboundRecords(
        session: RemoteSecureMessagingTransport.Session,
    ): List<LibSignalCompanionRecord> {
        val pending = session.withProjectionLease {
            val collected = mutableListOf<SecureMessagingProjectedMessage>()
            var after: String? = null
            repeat(MAX_OUTBOX_PAGES) {
                val page = readPage(afterRecordKey = after, limit = OUTBOX_PAGE_SIZE)
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
                        collected += projected
                    }
                }
                val next = page.nextAfterRecordKey ?: return@withProjectionLease collected
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
        // Storage pages are keyed by random client UUID. Restore authored order from the immutable
        // companion commit time; the ID is only a deterministic tie-breaker for legacy same-ms rows.
        return pending.sortedWith(
            compareBy<SecureMessagingProjectedMessage>(
                { it.durableRecord.updatedAtEpochMillis },
                { it.durableRecord.clientMessageId },
            ),
        ).map(SecureMessagingProjectedMessage::durableRecord)
    }

    private fun KitWalletApiException.isPermanentAttachmentBindingFailure(): Boolean = when (code) {
        ATTACHMENT_REFERENCE_INVALID -> statusCode == 422
        ATTACHMENT_ALREADY_ATTACHED -> statusCode == 409
        in MEDIA_MESSAGE_V2_PERMANENT_BINDING_FAILURES -> statusCode == 422
        else -> false
    }

    private fun KitWalletApiException.isAttachmentCompatibilityFailure(): Boolean =
        statusCode == 409 && code in ATTACHMENT_COMPATIBILITY_FAILURES

    private suspend fun stateFor(
        session: RemoteSecureMessagingTransport.Session,
    ): SessionState {
        currentState?.takeIf { it.session === session }?.let { return it }
        val restored = session.withCursorLease { load() }
        return SessionState(
            session = session,
            checkpoint = session.initialSyncCheckpoint(restored?.position),
            cursorRecordVersion = restored?.recordVersion,
        ).also { currentState = it }
    }

    private suspend fun processEvents(state: SessionState) {
        while (state.eventIndex < state.events.size) {
            try {
                when (val event = state.events[state.eventIndex]) {
                    is RemoteSecureMessagingTransport.Session.UnsupportedEvent -> Unit
                    is RemoteSecureMessagingTransport.Session.IncomingEnvelope ->
                        processIncoming(state, event)
                    is RemoteSecureMessagingTransport.Session.OutboundEvent ->
                        processOutbound(state, event)
                    is RemoteSecureMessagingTransport.Session.DeliveryReceiptEvent ->
                        processDeliveryReceipt(state, event)
                    is RemoteSecureMessagingTransport.Session.ReadReceiptEvent ->
                        processReadReceipt(state, event)
                    is RemoteSecureMessagingTransport.Session.RosterRefreshEvent ->
                        processRosterRefresh(state, event)
                    is RemoteSecureMessagingTransport.Session.MetadataEvent ->
                        processMetadata(state, event)
                    is RemoteSecureMessagingTransport.Session.FinancialMetadataEvent ->
                        processFinancialMetadata(state, event)
                }
            } catch (_: DeterministicSyncEventPoisonException) {
                // A replay produces the same contradiction after the authoritative reads succeed.
                // Consume only this explicitly classified hint so later ciphertext can progress.
            }
            state.eventIndex++
        }
    }

    /**
     * Financial sync rows are authenticated wake hints, never money authority. Refreshing the
     * wallet and invalidating the conversation makes the UI exact-read the referenced resource;
     * malformed events have already been rejected by the transport validator.
     */
    private suspend fun processFinancialMetadata(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.FinancialMetadataEvent,
    ) {
        if (event.type in GROUP_PAYMENT_REQUEST_SYSTEM_EVENT_TYPES) {
            try {
                systemEvents?.record(
                    activation = state.activation,
                    conversationId = event.conversationId,
                    event = ConversationSystemEvent(
                        eventId = event.eventId,
                        type = event.type,
                        userId = event.requesterUserId,
                        role = null,
                        occurredAt = event.occurredAt,
                        paymentId = event.paymentId,
                        contributionId = event.contributionId,
                        contributorUserId = event.contributorUserId,
                        contributionAmountMinor = event.contributionAmountMinor,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort presentation record; wallet refresh remains the money authority.
            }
        } else if (event.type in SCHEDULED_PAYMENT_SYSTEM_EVENT_TYPES) {
            processScheduledPaymentMetadata(state, event)
        }
        walletRefresh.refreshNow()
        state.invalidateConversation(event.conversationId)
    }

    /** A terminal wake hint becomes visible only after every stated fact matches exact API state. */
    private suspend fun processScheduledPaymentMetadata(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.FinancialMetadataEvent,
    ) {
        val conversation = authoritativeConversations(state)[event.conversationId]
        val scheduleRepository = checkNotNull(scheduledPayments) {
            "Scheduled-payment authority is unavailable"
        }
        val directEvent = event.type.startsWith("scheduled_payment.")
        val directAction = if (directEvent) {
            checkNotNull(KitScheduledPaymentAction.fromEventType(event.type))
        } else null
        val exactDirect = if (directEvent) {
            val sender = checkNotNull(event.senderUserId)
            val recipient = checkNotNull(event.recipientUserId)
            val currentUserId = state.session.binding.userId.lowercase()
            val exact = try {
                scheduleRepository.direct(event.paymentId)
            } catch (error: KitWalletApiException) {
                // A full conversation read plus the resource's own 404 is authoritative proof that
                // a chat left/deleted before this queued event was consumed is no longer visible.
                // Transient and server failures must still pin the cursor for a later exact read.
                if (conversation == null && error.statusCode == 404 &&
                    error.code == "SCHEDULED_PAYMENT_NOT_FOUND"
                ) return
                throw error
            }
            val action = checkNotNull(directAction)
            val amountMinor = checkNotNull(event.amountMinor)
            val currency = checkNotNull(event.currency)
            val scale = checkNotNull(event.currencyScale)
            val scheduledFor = checkNotNull(event.scheduledFor)
            requireDeterministicMetadata(
                exact.conversationId == event.conversationId &&
                    exact.knownStatus?.wire == action.wire &&
                    ScheduleContract.minor(exact.amount, scale) == amountMinor &&
                    exact.currency.code == currency && exact.currency.scale.toIntOrNull() == scale &&
                    Instant.parse(exact.scheduledFor) == scheduledFor && exact.note == event.note &&
                    exact.walletTransactionId == event.walletTransactionId,
                "Scheduled-payment exact state did not match its event",
            )
            if (exact.sourceWalletId != null) {
                requireDeterministicMetadata(
                    currentUserId == sender,
                    "A creator's scheduled-payment event changed its sender",
                )
            } else {
                requireDeterministicMetadata(
                    action == KitScheduledPaymentAction.COMPLETED && currentUserId == recipient,
                    "A recipient's scheduled-payment event changed its recipient",
                )
            }
            when (action) {
                KitScheduledPaymentAction.COMPLETED -> requireDeterministicMetadata(
                    exact.failure == null && exact.completedAt?.let(Instant::parse) == event.completedAt &&
                        exact.cancelledAt == null,
                    "Scheduled-payment completion state did not match its event",
                )
                KitScheduledPaymentAction.FAILED -> {
                    val failure = requireDeterministicMetadata(
                        exact.failure,
                        "Scheduled-payment failure state was missing",
                    )
                    requireDeterministicMetadata(
                        failure.isStructurallyValid() && failure.code == event.failureCode &&
                            (event.failureMessage == null ||
                                failure.message == event.failureMessage) &&
                            exact.completedAt?.let(Instant::parse) == event.completedAt &&
                            exact.cancelledAt == null,
                        "Scheduled-payment failure state did not match its event",
                    )
                }
                KitScheduledPaymentAction.CANCELLED -> requireDeterministicMetadata(
                    exact.failure == null && exact.completedAt == null &&
                        exact.cancelledAt?.let(Instant::parse) == event.cancelledAt,
                    "Scheduled-payment cancellation state did not match its event",
                )
            }
            exact
        } else null
        val exactGroup = if (!directEvent) {
            val exact = try {
                scheduleRepository.group(event.paymentId)
            } catch (error: KitWalletApiException) {
                if (conversation == null && error.statusCode == 404 &&
                    error.code == "SCHEDULED_GROUP_PAYMENT_NOT_FOUND"
                ) return
                throw error
            }
            val action = event.type.substringAfterLast('.')
            requireDeterministicMetadata(
                exact.conversationId == event.conversationId && exact.knownStatus?.wire == action &&
                Instant.parse(exact.scheduledFor) == event.scheduledFor &&
                    exact.groupPaymentId == event.groupPaymentId,
                "Scheduled-group exact state did not match its event",
            )
            when (exact.knownStatus) {
                ScheduledPaymentStatus.COMPLETED -> requireDeterministicMetadata(
                    exact.failure == null && exact.completedAt?.let(Instant::parse) == event.completedAt &&
                        exact.cancelledAt == null,
                    "Scheduled-group completion state did not match its event",
                )
                ScheduledPaymentStatus.FAILED -> {
                    val failure = requireDeterministicMetadata(
                        exact.failure,
                        "Scheduled-group failure state was missing",
                    )
                    val legacyHint = event.failureCode == null && event.failureMessage == null
                    requireDeterministicMetadata(
                        failure.isStructurallyValid() &&
                            (legacyHint || failure.code == event.failureCode &&
                                failure.message == event.failureMessage) &&
                            exact.completedAt?.let(Instant::parse) == event.completedAt &&
                            exact.cancelledAt == null,
                        "Scheduled-group failure state did not match its event",
                    )
                }
                ScheduledPaymentStatus.CANCELLED -> requireDeterministicMetadata(
                    exact.failure == null && exact.completedAt == null &&
                        exact.cancelledAt?.let(Instant::parse) == event.cancelledAt,
                    "Scheduled-group cancellation state did not match its event",
                )
                else -> throw DeterministicSyncEventPoisonException(
                    "Scheduled-group exact state is not terminal",
                )
            }
            exact
        } else null

        // A full authoritative conversation list can legitimately omit a chat the user left or
        // deleted. Consume its already-validated terminal hint without recreating local history.
        val visibleConversation = conversation ?: return
        val projection = if (directEvent) {
            requireDeterministicMetadata(
                !visibleConversation.isGroup,
                "A direct schedule event belongs to a group",
            )
            val sender = checkNotNull(event.senderUserId)
            val recipient = checkNotNull(event.recipientUserId)
            val members = visibleConversation.members.map { it.userId.lowercase() }.toSet()
            requireDeterministicMetadata(
                members == setOf(sender, recipient),
                "A direct schedule event changed its participants",
            )
            val exact = checkNotNull(exactDirect)
            val action = checkNotNull(directAction)
            val amountMinor = checkNotNull(event.amountMinor)
            val currency = checkNotNull(event.currency)
            val scale = checkNotNull(event.currencyScale)
            val scheduledFor = checkNotNull(event.scheduledFor)
            val reason = when (action) {
                KitScheduledPaymentAction.COMPLETED -> null
                KitScheduledPaymentAction.FAILED -> checkNotNull(exact.failure).message
                    .trim().take(MAX_SCHEDULED_PAYMENT_REASON_LENGTH)
                KitScheduledPaymentAction.CANCELLED -> "The scheduled payment was cancelled."
            }
            val descriptor = requireDeterministicMetadata(
                KitScheduledPaymentMessage.create(
                    action, event.paymentId, amountMinor, currency, scale, scheduledFor,
                    event.walletTransactionId, event.note, reason,
                ),
                "A scheduled-payment projection is invalid",
            )
            ScheduleProjection(descriptor.encode(), sender, descriptor.deterministicMessageId())
        } else {
            requireDeterministicMetadata(
                visibleConversation.isGroup,
                "A scheduled group event belongs to a direct chat",
            )
            val exact = checkNotNull(exactGroup)
            if (exact.knownStatus == ScheduledPaymentStatus.COMPLETED) {
                val resultId = checkNotNull(event.groupPaymentId)
                val payment = checkNotNull(groupPayments) {
                    "Group-payment authority is unavailable"
                }.groupPayment(resultId)
                val memberIds = visibleConversation.members.map { it.userId.lowercase() }.toSet()
                val sender = checkNotNull(payment.senderUserId).lowercase()
                val scheduledRecipients = exact.recipients.map { it.userId.lowercase() }
                val paymentRecipients = payment.recipients.mapNotNull { it.userId?.lowercase() }
                requireDeterministicMetadata(
                    payment.id.lowercase() == resultId &&
                        payment.conversationId == event.conversationId &&
                        payment.splitMode == exact.splitMode && payment.audience == exact.audience &&
                        payment.currencyCode == exact.currency.code &&
                        payment.currencyScale == exact.currency.scale.toIntOrNull() &&
                        payment.recipientCount == exact.recipientCount && payment.note == exact.note &&
                        payment.pendingCount >= 0 && payment.acceptedCount >= 0 &&
                        payment.returnedCount >= 0 &&
                        payment.pendingCount + payment.acceptedCount + payment.returnedCount ==
                        payment.recipientCount && paymentRecipients.size == payment.recipientCount &&
                        paymentRecipients.toSet() == scheduledRecipients.toSet() &&
                        (exact.splitMode != "even" ||
                            ScheduleContract.minor(exact.totalAmount!!, payment.currencyScale) ==
                            payment.totalAmountMinor),
                    "Scheduled group result did not match its reviewed schedule",
                )
                // The exact schedule and payment can remain readable after one of their historical
                // participants leaves an otherwise-current group. Current membership cannot
                // authenticate a name or announcement for that departed participant, but retrying
                // the same settled facts can never change the roster. Consume the wake without a
                // local projection so one old payment cannot pin every conversation's sync cursor.
                if (sender !in memberIds || scheduledRecipients.any { it !in memberIds }) return
                val descriptor = requireDeterministicMetadata(
                    KitGroupPaymentMessage.announcing(payment, scheduledRecipients),
                    "Scheduled group result could not be projected",
                )
                requireDeterministicMetadata(
                    descriptor.matchesAuthoritativePayment(payment),
                    "Scheduled group projection did not match exact state",
                )
                ScheduleProjection(
                    descriptor.encode(), sender,
                    deterministicUuid("scheduled-group-payment|${exact.id}|$resultId"),
                )
            } else {
                val outcome = if (exact.knownStatus == ScheduledPaymentStatus.FAILED) {
                    KitScheduledGroupPaymentOutcomeAction.FAILED
                } else KitScheduledGroupPaymentOutcomeAction.CANCELLED
                val descriptor = requireDeterministicMetadata(
                    KitScheduledGroupPaymentOutcomeMessage.create(
                        outcome, exact.id, Instant.parse(exact.scheduledFor),
                    ),
                    "Scheduled group outcome could not be projected",
                )
                ScheduleProjection(
                    descriptor.encode(), state.session.binding.userId,
                    descriptor.deterministicMessageId(),
                )
            }
        }
        systemEvents?.record(
            activation = state.activation,
            conversationId = event.conversationId,
            event = ConversationSystemEvent(
                eventId = event.eventId,
                type = event.type,
                userId = projection.senderUserId,
                role = null,
                occurredAt = event.occurredAt,
                paymentId = event.paymentId,
                projectionText = projection.text,
            ),
        )
    }

    private data class ScheduleProjection(
        val text: String,
        val senderUserId: String,
        val messageId: String,
    )

    private fun requireDeterministicMetadata(condition: Boolean, message: String) {
        if (!condition) throw DeterministicSyncEventPoisonException(message)
    }

    private fun <T : Any> requireDeterministicMetadata(value: T?, message: String): T =
        value ?: throw DeterministicSyncEventPoisonException(message)

    /**
     * A metadata event carries no ciphertext, so the only thing it can change is what the next
     * refresh reads back — except for a membership change, which is also the only record that a
     * group's timeline will ever have of somebody joining or leaving.
     *
     * The system-message record is written *before* the conversation is invalidated so a refresh
     * racing this event cannot publish a roster the timeline has no line for. It is best-effort:
     * a failed write costs one timeline annotation and must never stall the sync cursor, because
     * a stalled cursor is undelivered messages.
     */
    private suspend fun processMetadata(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.MetadataEvent,
    ) {
        val subject = event.memberUserId
        if (subject != null && event.type in MEMBERSHIP_SYSTEM_EVENT_TYPES) {
            try {
                systemEvents?.record(
                    activation = state.activation,
                    conversationId = event.conversationId,
                    event = ConversationSystemEvent(
                        eventId = event.eventId,
                        type = event.type,
                        userId = subject,
                        role = event.memberRole,
                        occurredAt = event.occurredAt,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Deliberately swallowed; see the note above.
            }
        }
        state.invalidateConversation(event.conversationId)
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
            // The sync stream is append-only and a fresh login starts without a cursor. It can
            // therefore replay a revocation or identity event from an older enrollment epoch.
            // Trust neither the historical hint nor its timestamps: re-fetch the current device
            // enrollment and ignore the hint only when it still exactly matches the private
            // identity reconciled for this activation. A real current revocation/change remains
            // fail-closed.
            val pinnedTarget = state.session.reconciledKeyIdentityResetTarget()
            val revalidationPhase = state.session.beginReconciledKeyIdentityRevalidation()
            val revalidation = try {
                state.session.revalidateReconciledKeyIdentity()
            } catch (cancelled: CancellationException) {
                throw SecureMessagingRevalidationCancellationException(cancelled)
            } catch (error: Throwable) {
                // Only a successfully validated authoritative status may erase or reset state.
                // Malformed, authorization, server and transport failures all remain withdrawn
                // and retry this exact pinned activation without destructive recovery.
                throw SecureMessagingRevalidationRetryException(error)
            }
            if (revalidation == ReconciledIdentityStatus.CURRENT) {
                state.session.finishReconciledKeyIdentityRevalidation(revalidationPhase)
                state.invalidateConversation(event.conversationId)
                return
            }
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
            when (revalidation) {
                ReconciledIdentityStatus.UNENROLLED ->
                    quarantineForPinnedReset(state, failure, pinnedTarget)
                ReconciledIdentityStatus.MISMATCH ->
                    quarantineForPinnedReset(state, failure, pinnedTarget)
                ReconciledIdentityStatus.CURRENT -> error("Current identity returned early")
            }
        }

        if (event.eventType in PEER_STATE_RETIREMENT_EVENTS) {
            state.session.retireRemoteDevices(
                engine = cryptoEngine,
                affectedUserId = event.affectedUserId,
                affectedServerDeviceId = event.affectedDeviceId,
            )
        }
        state.invalidateConversation(event.conversationId)
        if (
            event.eventType == DEVICE_ENROLLED_EVENT &&
            event.affectedUserId == binding.userId &&
            event.affectedDeviceId != null &&
            event.affectedDeviceId != binding.serverDeviceId &&
            event.affectedEnrollmentEpoch != null
        ) {
            state.withProjectionLease {
                enqueueHistoryBackfill(
                    conversationId = event.conversationId,
                    targetDeviceId = event.affectedDeviceId,
                    targetEnrollmentEpoch = event.affectedEnrollmentEpoch,
                )
            }
        }
    }

    private suspend fun backfillRetainedHistoryPage(
        session: RemoteSecureMessagingTransport.Session,
        task: SecureMessagingHistoryBackfillTask,
        conversations: Map<String, RemoteSecureMessagingTransport.Session.SecureConversation>,
        rosters: MutableMap<String, RemoteSecureMessagingTransport.Session.AuthoritativeRoster>,
    ) {
        val conversation = conversations[task.conversationId]
        if (conversation == null) {
            session.withProjectionLease {
                updateHistoryBackfill(task, nextCursor = null, completed = true)
            }
            return
        }
        val roster = rosters[task.conversationId] ?: session.roster(conversation).also {
            rosters[task.conversationId] = it
        }
        val page = session.historyBackfillCandidates(
            conversation = conversation,
            roster = roster,
            targetDeviceId = task.targetDeviceId,
            targetEnrollmentEpoch = task.targetEnrollmentEpoch,
            after = task.nextCursor,
            limit = HISTORY_PAGE_SIZE,
        )
        val plan = session.historyBackfillEncryptionPlan(conversation, roster, page)
        page.candidates().forEach { candidate ->
            val projected = session.withProjectionLease {
                retainedHistorySource(
                    messageId = candidate.messageId,
                    clientMessageId = candidate.clientMessageId,
                )
            } ?: return@forEach
            // Locally rejected content is never donated: a suppressed row was refused outright,
            // and unsupported media exists only as a sanitized placeholder — its durable
            // descriptor bytes must not leave this device. The derived predicate also covers
            // inbound rows recorded before the disposition pin existed (reserved text with no
            // strict rows), while own-authored outbound media stays donatable: it was strictly
            // derived before commit and the recipient re-checks the binding on its own device.
            if (projected.unsupportedMedia ||
                projected.deliveryState ==
                SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED
            ) {
                return@forEach
            }
            val transferId = SecureMessagingHistoryBackfillCodec.deterministicTransferId(
                messageId = candidate.messageId,
                targetDeviceId = task.targetDeviceId,
                targetEnrollmentEpoch = task.targetEnrollmentEpoch,
                donorDeviceId = session.binding.serverDeviceId,
                donorEnrollmentEpoch = session
                    .reconciledKeyIdentityResetTarget()
                    .enrollmentEpoch,
                transferRosterRevision = page.rosterRevision,
            )
            val encrypted = session.withProjectionLease {
                readHistoryOutbound(transferId)
            }?.let { durable ->
                SecureMessagingCryptoWireMapper.retryEncryption(durable, plan)
            } ?: run {
                val descriptor = try {
                    SecureMessagingHistoryBackfillCodec.encode(
                        transferClientMessageId = transferId,
                        targetDeviceId = task.targetDeviceId,
                        targetEnrollmentEpoch = task.targetEnrollmentEpoch,
                        transferRosterRevision = page.rosterRevision,
                        candidate = candidate,
                        projected = projected,
                    )
                } catch (_: IllegalArgumentException) {
                    return@forEach
                } catch (_: IllegalStateException) {
                    return@forEach
                }
                commitHistoryEncryption(
                    conversation = conversation,
                    roster = roster,
                    session = session,
                    plan = plan,
                    transferClientMessageId = transferId,
                    descriptor = descriptor,
                )
            }
            session.storeHistoryEnvelope(
                conversation = conversation,
                roster = roster,
                page = page,
                candidate = candidate,
                encryptedSend = encrypted,
            )
        }
        if (!page.hasMore) {
            session.withProjectionLease {
                updateHistoryBackfill(task, nextCursor = null, completed = true)
            }
            return
        }
        val next = checkNotNull(page.nextAfter) {
            "History candidate page omitted its continuation"
        }
        check(task.nextCursor == null || next != task.nextCursor) {
            "History candidate pagination did not advance"
        }
        session.withProjectionLease {
            updateHistoryBackfill(task, nextCursor = next, completed = false)
        }
    }

    private suspend fun commitHistoryEncryption(
        session: RemoteSecureMessagingTransport.Session,
        conversation: RemoteSecureMessagingTransport.Session.SecureConversation,
        roster: RemoteSecureMessagingTransport.Session.AuthoritativeRoster,
        plan: SecureMessagingEncryptionPlan,
        transferClientMessageId: String,
        descriptor: String,
    ): SecureMessagingEncryptedSend {
        var transaction = session.openCryptoTransaction(cryptoEngine)
        var committed = false
        try {
            val missing = transaction.missingSessions(plan)
            if (!missing.isEmpty) {
                val establishment = session.consumeKeyBundles(
                    conversation = conversation,
                    roster = roster,
                    plan = plan,
                    deviceIds = missing.addresses().mapTo(mutableSetOf()) {
                        it.serverDeviceId
                    },
                )
                transaction.stageSessionEstablishment(establishment)
                check(transaction.commit() is SecureMessagingCommittedResult.SessionsEstablished) {
                    "History target session establishment returned another operation"
                }
                committed = true
                transaction = session.openCryptoTransaction(cryptoEngine)
                committed = false
                check(transaction.missingSessions(plan).isEmpty) {
                    "History target session remains unavailable after key establishment"
                }
            }
            val request = SecureMessagingEncryptionRequest.history(
                plan = plan,
                clientMessageId = transferClientMessageId,
                descriptor = descriptor,
            )
            try {
                transaction.stageEncryption(
                    request,
                    projections.historyOutboundIntent(transferClientMessageId),
                )
                val result = transaction.commit()
                check(result is SecureMessagingCommittedResult.Encrypted) {
                    "History encryption returned another operation"
                }
                committed = true
                return SecureMessagingCryptoWireMapper.encryption(result)
            } finally {
                request.close()
            }
        } finally {
            if (!committed) withContext(NonCancellable) { runCatching { transaction.abort() } }
        }
    }

    private suspend fun quarantineCurrentActivation(
        state: SessionState,
        failure: SecureMessagingCryptographicFailureException,
    ) {
        // Withdraw READY synchronously. A lifecycle hint never authorizes local erasure or a
        // reset of whatever enrollment may have replaced this activation on the server.
        try {
            state.session.quarantine(failure)
        } catch (fenceFailure: Throwable) {
            failure.addSuppressed(fenceFailure)
            throw failure
        }
        currentState = null
        withContext(NonCancellable) {
            runCatching { notifications.cancelAll() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        }
        val cancellation = failure.cause as? CancellationException
        if (cancellation != null) {
            throw SecureMessagingRevalidationCancellationException(cancellation)
        }
    }

    private suspend fun quarantineForPinnedReset(
        state: SessionState,
        failure: SecureMessagingCryptographicFailureException,
        pinnedTarget: com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget,
    ): Nothing {
        quarantineCurrentActivation(state, failure)
        throw SecureMessagingReauthenticationRequiredException(
            target = pinnedTarget,
            activationFence = state.session.activationFence(),
            message = "The reconciled messaging enrollment changed during synchronization",
            cause = failure,
        )
    }

    private suspend fun processIncoming(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
    ) {
        if (envelope.isHistoryBackfill) {
            processIncomingHistory(state, envelope)
            return
        }
        var durable = state.withProjectionLease { readInbound(envelope.messageId) }
        var decrypted: SecureMessagingCommittedResult.Decrypted? = null
        try {
            if (durable == null) {
                val pending = state.pendingDecryption
                val request = if (pending == null) {
                    // A null plan is the settled answer that no certified roster will ever exist
                    // for this envelope: the account left or deleted the chat before the queued
                    // event was consumed, or the stored roster for its revision failed
                    // verification. Consume the event without touching the ratchet or
                    // acknowledging delivery rather than wedging every other conversation's
                    // synchronization behind it forever.
                    val historical = historicalPlan(state, envelope) ?: return
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
                decrypted = commitDecryption(
                    session = state.session,
                    request = request,
                    intent = projections.inboundIntent(envelope.messageId),
                )
                state.pendingDecryption = null
                durable = checkNotNull(state.withProjectionLease {
                    readInbound(envelope.messageId)
                }) {
                    "Committed incoming message omitted its durable projection"
                }
            }

            val persisted = checkNotNull(durable)
            if (!hasAuthenticatedAttachmentBinding(envelope, persisted)) {
                if (isReservedUnsupportedMediaText(persisted.authenticatedText)) {
                    // Authenticated reserved-media content whose envelope binding failed: a
                    // malformed family descriptor, or a strict v2 descriptor carried under the
                    // wrong outer kind or with a mismatched row set. The transcript slot is
                    // real, so it stays visible — pinned as unsupported media, projected and
                    // notified only as the sanitized generation marker, never actionable, and
                    // its descriptor bytes never enter a page, shade, archive or donation.
                    publishInboundProjection(
                        state,
                        persisted,
                        envelope.sentAt,
                        disposition = SecureMessagingMediaDisposition.UNSUPPORTED,
                    )
                } else {
                    // A peer controls both the outer metadata and encrypted plaintext it sends.
                    // A mismatch is therefore message-local hostile input, not evidence that
                    // this account activation or ratchet is corrupt. Keep the durable crypto
                    // commit, suppress projection/opening, acknowledge the envelope and advance
                    // the cursor so the peer cannot remotely quarantine or permanently wedge
                    // this recipient. Commit the tombstone before acknowledgement/cursor
                    // advance. If this write is interrupted, replay sees the companion commit,
                    // revalidates the same binding, and retries suppression without re-running
                    // the ratchet.
                    state.withProjectionLease {
                        recordInboundSuppressed(persisted, envelope.sentAt)
                    }
                }
                val token = if (decrypted != null) {
                    state.session.deliveryToken(envelope, decrypted)
                } else {
                    state.session.deliveryTokenFromDurableState(envelope, persisted)
                }
                state.deliveryTokens += token
                return
            }
            // Binding held: the outer attachment rows were checked against the authenticated
            // descriptor on this device. Rows carrying strict media earn a durable VALIDATED
            // verdict so the provenance survives archive and restore; everything else stays NONE.
            publishInboundProjection(
                state,
                persisted,
                envelope.sentAt,
                disposition = if (KitMediaMessageV2.parse(persisted.authenticatedText) != null) {
                    SecureMessagingMediaDisposition.VALIDATED
                } else {
                    SecureMessagingMediaDisposition.NONE
                },
            )
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

    private suspend fun processIncomingHistory(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
    ) {
        val transferId = checkNotNull(envelope.transferClientMessageId) {
            "Validated history envelope omitted its transfer ID"
        }
        var wrapper = state.withProjectionLease { readHistoryInbound(transferId) }
        var decrypted: SecureMessagingCommittedResult.Decrypted? = null
        try {
            if (wrapper == null) {
                val pending = state.pendingDecryption
                val request = if (pending == null) {
                    // Same consume-without-recreating rule as processIncoming: a history transfer
                    // whose conversation is authoritatively gone, or whose stored roster failed
                    // verification, has no certified roster to decrypt against and must not stall
                    // the stream.
                    val historical = historicalPlan(state, envelope) ?: return
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
                decrypted = commitDecryption(
                    session = state.session,
                    request = request,
                    intent = projections.historyInboundIntent(transferId),
                )
                state.pendingDecryption = null
                wrapper = checkNotNull(state.withProjectionLease {
                    readHistoryInbound(transferId)
                }) {
                    "Committed history wrapper omitted its durable crypto state"
                }
            }

            val durableWrapper = checkNotNull(wrapper)
            val targetEpoch = checkNotNull(envelope.recipientEnrollmentEpoch) {
                "Validated history envelope omitted its target enrollment"
            }
            val authenticated = try {
                SecureMessagingHistoryBackfillCodec.authenticate(
                    descriptorText = durableWrapper.authenticatedText,
                    envelope = envelope,
                    expectedTargetDeviceId = state.session.binding.serverDeviceId,
                    expectedTargetEnrollmentEpoch = targetEpoch,
                )
            } catch (_: IllegalArgumentException) {
                state.deliveryTokens += historyDeliveryToken(
                    state,
                    envelope,
                    decrypted,
                    durableWrapper,
                )
                return
            } catch (_: IllegalStateException) {
                state.deliveryTokens += historyDeliveryToken(
                    state,
                    envelope,
                    decrypted,
                    durableWrapper,
                )
                return
            }

            // A history wrapper can make this installation a donor for another current device.
            // Use the projection lookup rather than only the inbound record: a message authored on
            // this installation can already be retained under its outbound client-message key.
            // Reuse that exact projection instead of materializing a duplicate inbound copy.
            val retainedSource = state.withProjectionLease {
                retainedHistorySource(
                    messageId = authenticated.messageId,
                    clientMessageId = authenticated.clientMessageId,
                )
            }
            val retainedOutbound = retainedSource?.durableRecord?.direction ==
                LibSignalCompanionDirection.OUTBOUND
            val recovered = if (retainedOutbound) {
                checkNotNull(retainedSource).durableRecord
            } else {
                state.withProjectionLease {
                    try {
                        recordRecoveredHistory(authenticated)
                    } catch (unavailable: SecureMessagingStateUnavailableException) {
                        throw unavailable
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: IllegalStateException) {
                        null
                    }
                }
            }
            if (recovered == null) {
                state.deliveryTokens += historyDeliveryToken(
                    state,
                    envelope,
                    decrypted,
                    durableWrapper,
                )
                return
            }
            if (!retainedOutbound) {
                if (retainedSource == null) {
                    // Invalidate before notification publication. If publication is interrupted
                    // after its durable projection commit, the retry still reconciles completed
                    // propagation tasks; a process restart also begins unreconciled.
                    historyReconciledActivation = null
                }
                // The recipient re-derives its own verdict: authenticate() re-checked the
                // envelope binding on this device, so strict media rows recovered here earn
                // VALIDATED; recovered modern/future-family text without a strict v2 binding
                // stays pinned UNSUPPORTED so it never turns actionable later. A valid legacy
                // KITMEDIA1 descriptor needs no provenance pin and retains its historical NONE.
                publishInboundProjection(
                    state,
                    recovered,
                    authenticated.sentAt,
                    disposition = when {
                        KitMediaMessageV2.parse(recovered.authenticatedText) != null ->
                            SecureMessagingMediaDisposition.VALIDATED
                        requiresModernMediaSchemaFence(recovered.authenticatedText) ->
                            SecureMessagingMediaDisposition.UNSUPPORTED
                        else -> SecureMessagingMediaDisposition.NONE
                    },
                )
                if (retainedSource == null) {
                    scheduleHistoryContinuation(0L)
                }
            }
            state.deliveryTokens += historyDeliveryToken(
                state,
                envelope,
                decrypted,
                durableWrapper,
            )
        } finally {
            decrypted?.close()
        }
    }

    private suspend fun publishInboundProjection(
        state: SessionState,
        durable: LibSignalCompanionRecord,
        sentAt: Instant,
        disposition: SecureMessagingMediaDisposition = SecureMessagingMediaDisposition.NONE,
    ) {
        val authoredOnThisAccount = durable.sender.userId == state.session.binding.userId
        // A pinned unsupported-media row must never hand its durable text to the shade: the
        // sanitized generation marker carries the placeholder meaning with zero descriptor bytes.
        val notificationText = if (disposition == SecureMessagingMediaDisposition.UNSUPPORTED) {
            KitMediaFamily.sanitizedFamilyMarker(durable.authenticatedText)
        } else {
            durable.authenticatedText
        }
        val notificationPending = state.withProjectionLease {
            // A reaction is an annotation on a bubble the user has already been told about, and
            // its descriptor is not readable copy. It commits to the projection like any other
            // message but never reaches the shade.
            val pending = !authoredOnThisAccount &&
                !KitReactionMessage.isReactionText(durable.authenticatedText) &&
                prepareInboundNotificationPublication(durable, sentAt)
            // Commit and signal the conversation projection before making a shade notification
            // externally visible. An interrupted reconnect can now duplicate only the stable-tag
            // notification, never leave the user with an alert for a locally invisible message.
            if (disposition == SecureMessagingMediaDisposition.UNSUPPORTED) {
                recordInboundUnsupportedMedia(durable, sentAt)
            } else {
                recordInbound(durable, sentAt, disposition)
            }
            pending
        }
        // A committed incoming payment event means balances just changed for this account —
        // money landed, a transfer is being held, or a held transfer went back. The backend
        // sends no wallet push, so this authenticated message is the receiver's earliest signal.
        if (
            !authoredOnThisAccount &&
            KitPaymentMessage.parse(durable.authenticatedText)?.action?.movesMoney == true
        ) {
            walletRefresh.refreshNow()
        }
        if (!notificationPending) return

        // Resolve the sender only after the message is locally visible. A temporary conversation
        // lookup failure can delay the alert, but can no longer produce an alert-only state.
        val notificationSender = authenticatedNotificationSender(state, durable)
        if (notificationSender == null) {
            // A fresh authoritative conversation list refused to name this sender: the chat is
            // gone from this account, or the sender sits outside its membership. The message is
            // already committed and locally visible; only the shade needed a name, and naming
            // stays fail-closed. Consume the pending marker so one silent alert can never stall
            // or replay against account-wide synchronization.
            state.withProjectionLease { completeInboundNotificationPublication(durable) }
            return
        }
        // Re-enter the exact activation lease for the external publication. Erasure drains this
        // phase before cancelAll, so an obsolete activation cannot notify after logout/replacement.
        state.withProjectionLease {
            if (!prepareInboundNotificationPublication(durable, sentAt)) return@withProjectionLease
            try {
                notifications.publish(
                    SecureMessagingIncomingNotification(
                        messageId = durable.messageId,
                        conversationId = durable.conversationId,
                        sessionEpoch = state.session.binding.sessionEpoch,
                        senderName = notificationSender.name,
                        groupTitle = notificationSender.groupTitle,
                        authenticatedText = notificationText,
                        sentAt = sentAt,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw SecureMessagingNotificationPublicationException(error)
            }
            completeInboundNotificationPublication(durable)
        }
    }

    /** Who the shade may name, resolved only from authenticated conversation membership. */
    private data class NotificationSender(val name: String?, val groupTitle: String?)

    /**
     * Resolves display identity only from the authenticated conversation membership, or null when
     * the authoritative list no longer contains the conversation or the sender.
     */
    private suspend fun authenticatedNotificationSender(
        state: SessionState,
        durable: LibSignalCompanionRecord,
    ): NotificationSender? {
        val conversation = authoritativeConversation(state, durable.conversationId) ?: return null
        // Membership, not peer equality: a group has many authentic senders, and the shade must
        // name the one who actually sent this. A sender outside the membership is still refused.
        val sender = conversation.members.firstOrNull {
            it.userId.equals(durable.sender.userId, ignoreCase = true)
        } ?: return null
        return NotificationSender(
            name = sender.name,
            groupTitle = conversation.title.takeIf { conversation.isGroup },
        )
    }

    private fun historyDeliveryToken(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
        decrypted: SecureMessagingCommittedResult.Decrypted?,
        durableWrapper: LibSignalCompanionRecord,
    ): RemoteSecureMessagingTransport.Session.DeliveryToken = if (decrypted != null) {
        state.session.deliveryToken(envelope, decrypted)
    } else {
        state.session.deliveryTokenFromDurableState(envelope, durableWrapper)
    }

    private fun hasAuthenticatedAttachmentBinding(
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
        durable: LibSignalCompanionRecord,
    ): Boolean {
        val reaction = KitReactionMessage.parse(durable.authenticatedText)
        val edit = KitEditMessage.parse(durable.authenticatedText)
        // Text that reaches for a reserved namespace but does not parse into it is refused rather
        // than shown: rendering it as ordinary words would put a descriptor in someone's face,
        // and treating it as the thing it almost is would accept content nothing validated.
        if (KitReactionMessage.isReactionText(durable.authenticatedText) && reaction == null) {
            return false
        }
        if (KitEditMessage.isEditText(durable.authenticatedText) && edit == null) {
            return false
        }
        // Reserved media-family text that strictly parses in no generation can never bind. Its
        // authenticated kind falls back to ordinary text, so without this refusal a zero-row
        // encrypted_message envelope would satisfy both the kind check and the empty row match
        // and project raw almost-descriptor bytes. Refusing here routes it into the same
        // tri-state placeholder disposition the history path applies.
        if (
            KitMediaFamily.isFamilyText(durable.authenticatedText) &&
            kitMediaAttachmentsFor(durable.authenticatedText).isEmpty()
        ) {
            return false
        }
        // The kind check is what stops a descriptor smuggled inside an ordinary encrypted_message
        // envelope (zero rows to validate against) from ever becoming an actionable album: media
        // text of either generation binds itself to encrypted_attachment, so the wrong outer kind
        // fails here no matter what the row set looks like.
        val authenticatedKind = authenticatedMessageKind(durable.authenticatedText)
        return envelope.kind == authenticatedKind &&
            kitMediaOuterAttachmentsMatch(durable.authenticatedText, envelope.attachments) &&
            (reaction == null || reaction.targetMessageId == envelope.replyToMessageId) &&
            (edit == null || edit.targetMessageId == envelope.replyToMessageId)
    }

    /**
     * Reserved media-family text other than a strictly valid v1 descriptor.
     *
     * When such text fails its envelope binding the message is authentic but its media can never
     * be actionable: it renders as the sanitized generic placeholder instead of being suppressed.
     * A strictly valid v1 descriptor keeps its historical handling — suppression on mismatch.
     */
    private fun isReservedUnsupportedMediaText(text: String): Boolean =
        requiresModernMediaSchemaFence(text)

    private suspend fun commitDecryption(
        session: RemoteSecureMessagingTransport.Session,
        request: SecureMessagingDecryptionRequest,
        intent: SecureMessagingCompanionStateIntent,
    ): SecureMessagingCommittedResult.Decrypted {
        val transaction = session.openCryptoTransaction(cryptoEngine)
        return try {
            transaction.stageDecryption(request, intent)
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
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.OutboundEvent,
    ) {
        state.withProjectionLease {
            val durable = readOutbound(event.clientMessageId) ?: return@withProjectionLease
            markOutboundSent(durable, event)
        }
    }

    private suspend fun processDeliveryReceipt(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.DeliveryReceiptEvent,
    ) {
        state.withProjectionLease {
            markAuthoredDelivered(
                conversationId = event.conversationId,
                messageId = event.messageId,
                currentUserId = state.session.binding.userId,
                deliveredAt = event.deliveredAt,
            )
        }
    }

    private suspend fun processReadReceipt(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.ReadReceiptEvent,
    ) {
        // A receipt only annotates messages this account already has. When the authoritative
        // conversation list omits its chat even after a fresh look — left or deleted before this
        // queued event was consumed — there is nothing the marker may honestly move: skip the
        // annotation instead of stalling every other conversation's synchronization behind it.
        val conversation = authoritativeConversation(state, event.conversationId) ?: return
        if (event.readerUserId == state.session.binding.userId) {
            // The backend broadcasts this account's marker to every enrolled device. It is not
            // peer-read evidence for self-authored bubbles, but it does clear authenticated inbound
            // messages from the other members on this account's other devices.
            state.withProjectionLease {
                markInboundReadThroughCanonicalIfKnown(
                    conversationId = event.conversationId,
                    senderUserIds = conversation.otherMemberUserIds(state.session.binding.userId),
                    canonicalLastReadMessageId = event.lastReadMessageId,
                    canonicalReadAt = event.readAt,
                )
            }
            return
        }
        if (!conversation.contains(event.readerUserId)) {
            // A reader outside the authenticated membership proves nothing about this account's
            // bubbles. Refuse the annotation without wedging the stream behind it.
            return
        }
        state.withProjectionLease {
            markAuthoredReadThrough(
                conversationId = event.conversationId,
                lastReadMessageId = event.lastReadMessageId,
                currentUserId = state.session.binding.userId,
                readAt = event.readAt,
                // In a direct chat the only other person having read it is exactly what the read
                // tick claims. In a group it is not: the backend reports one member's marker, and
                // showing "read" for the whole group off one reader would overclaim. The honest
                // thing that marker proves is that the message reached and was opened on at least
                // one member's device, so a group advances to delivered and stops there.
                authoredRead = !conversation.isGroup,
            )
        }
    }

    /**
     * The session-scoped authoritative conversation map, fetched on first use and after any
     * membership invalidation.
     */
    private suspend fun authoritativeConversations(
        state: SessionState,
    ): Map<String, RemoteSecureMessagingTransport.Session.SecureConversation> =
        state.conversations ?: state.session.conversations()
            .associateBy(RemoteSecureMessagingTransport.Session.SecureConversation::conversationId)
            .also { state.conversations = it }

    /**
     * Resolves one conversation against the authoritative list, refetching the cached map once
     * when a list fetched earlier omits it: a conversation created after the cache was populated
     * must never be mistaken for one this account left. Null therefore means a list fetched fresh
     * from the server still omits the conversation — the server's own statement that this account
     * no longer sees it — remembered for the rest of the batch so a departed conversation's
     * backlog drains at one refetch, not one per event. Transport and server failures still throw
     * and pin the cursor.
     */
    private suspend fun authoritativeConversation(
        state: SessionState,
        conversationId: String,
    ): RemoteSecureMessagingTransport.Session.SecureConversation? {
        if (conversationId in state.absentConversations) return null
        val cachedBefore = state.conversations != null
        authoritativeConversations(state)[conversationId]?.let { return it }
        if (cachedBefore) {
            state.conversations = null
            authoritativeConversations(state)[conversationId]?.let { return it }
        }
        state.absentConversations += conversationId
        return null
    }

    /**
     * The certified decryption inputs for one offline envelope, memoized per historical roster.
     * Null is the settled answer that no certified roster will ever exist for it: a fresh
     * authoritative list omits the conversation, or the transport refused to verify the stored
     * roster for its revision. Callers must consume the event — a retry only re-proves the same
     * refusal, wedging every conversation's synchronization behind one dead envelope.
     */
    private suspend fun historicalPlan(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
    ): HistoricalRosterPlan? {
        val key = HistoricalRosterKey(envelope.conversationId, envelope.transferRosterRevision)
        state.historicalPlans[key]?.let { return it }
        if (key in state.rejectedRosters) return null
        val conversation = authoritativeConversation(state, envelope.conversationId)
            ?: return null
        val roster = try {
            state.session.historicalRoster(
                conversation,
                envelope.transferRosterRevision,
            )
        } catch (_: SecureMessagingWireValidationException) {
            // The stored roster for this revision does not verify against the conversation it
            // claims — most ordinarily because membership moved on after the envelope was
            // sealed. The same fetch yields the same refusal on every retry, so the envelope
            // can never be decrypted: fail closed for this envelope, never for the stream.
            state.rejectedRosters += key
            return null
        }
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
        val version = state.withCursorLease {
            try {
                save(position, state.cursorRecordVersion)
            } catch (writeFailure: Throwable) {
                val loaded = try {
                    load()
                } catch (loadFailure: Throwable) {
                    writeFailure.addSuppressed(loadFailure)
                    throw writeFailure
                }
                val expected = requireSecureMessagingSyncResumePosition(position)
                val actual = loaded?.let { requireSecureMessagingSyncResumePosition(it.position) }
                if (loaded == null || actual != expected) throw writeFailure
                loaded.recordVersion
            }
        }
        state.cursorRecordVersion = version
        state.persistedBatchPosition = position
        return position
    }

    private companion object {
        const val ATTACHMENT_REFERENCE_INVALID = "ATTACHMENT_REFERENCE_INVALID"
        const val ATTACHMENT_ALREADY_ATTACHED = "ATTACHMENT_ALREADY_ATTACHED"
        val ATTACHMENT_COMPATIBILITY_FAILURES = setOf(
            "MESSAGING_ATTACHMENT_CLIENT_UPGRADE_REQUIRED",
            "MESSAGING_V2_CONTENT_PROFILE_UNAVAILABLE",
            // KITMEDIA2 (§5b): the album is valid, this build's version floor is not — park it.
            "MESSAGING_MEDIA_MESSAGE_V2_CLIENT_UPGRADE_REQUIRED",
        )

        /**
         * KITMEDIA2 422s that condemn this exact fanout: the album broke a structural limit the
         * server enforces per message, so retrying the same descriptor can never succeed and must
         * not wedge later outbox entries behind it.
         */
        val MEDIA_MESSAGE_V2_PERMANENT_BINDING_FAILURES = setOf(
            "MESSAGING_MEDIA_MESSAGE_V2_ATTACHMENT_LIMIT_EXCEEDED",
            "MESSAGING_MEDIA_MESSAGE_V2_AGGREGATE_LIMIT_EXCEEDED",
            "MESSAGING_MEDIA_MESSAGE_V2_ATTACHMENT_ORDER_INVALID",
        )
        const val SYNC_PAGE_SIZE = 50
        const val OUTBOX_PAGE_SIZE = 100
        const val MAX_OUTBOX_PAGES = 100
        const val HISTORY_PAGE_SIZE = 50
        const val MAX_HISTORY_TASKS_PER_BATCH = 4
        const val MAX_HISTORY_WORK_UNITS_PER_RUN = 16
        const val HISTORY_FAILURE_RETRY_DELAY_MILLIS = 30_000L
        const val MAX_SCHEDULED_PAYMENT_REASON_LENGTH = 280
        const val DEVICE_REVOKED_EVENT = "device.revoked"
        const val DEVICE_ENROLLED_EVENT = "device.enrolled"
        const val ALL_DEVICES_REVOKED_EVENT = "devices.revoked"
        const val IDENTITY_CHANGED_EVENT = "identity.changed"
        const val HISTORY_CURSOR_INVALID = "MESSAGING_HISTORY_CURSOR_INVALID"
        val TERMINAL_HISTORY_TASK_CODES = setOf(
            "MESSAGING_HISTORY_TARGET_INVALID",
            "MESSAGING_HISTORY_TARGET_STALE",
        )
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
