package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ENCRYPTED_ATTACHMENT_MESSAGE_KIND
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.KitWalletApiException
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
    @Suppress("UNUSED_PARAMETER")
    currentActivationRevocation: SecureMessagingCurrentActivationRevocation =
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
                // Attachment metadata is server-visible but is authenticated inside the durable
                // Signal plaintext descriptor. Re-derive it on every retry just as the initial
                // send path does; retrying an attachment as ordinary encrypted text would create
                // a message that the recipient can decrypt but can never authorize/download.
                val attachments = KitMediaMessage.attachmentsFor(durable.authenticatedText)
                val receipt = try {
                    session.send(conversation, encrypted, attachments)
                } catch (error: KitWalletApiException) {
                    if (attachments.isNotEmpty()) {
                        if (error.isPermanentAttachmentBindingFailure()) {
                            // An unclaimed blob expires after 24 hours, and a handle claimed by
                            // another accepted message can never be reused. Retire only this
                            // durable media fanout so it cannot starve later outbox entries.
                            projections.markOutboundPermanentFailure(durable)
                            return@forEach
                        }
                        if (error.isAttachmentCompatibilityFailure()) {
                            // The blob can still be valid, but a code-12 roster device or a
                            // temporarily disabled content profile cannot accept it today. Stop
                            // automatic recovery from wedging later text; retain explicit retry
                            // for after every device/server profile supports attachments.
                            projections.markOutboundRetryRequired(durable)
                            return@forEach
                        }
                    }
                    throw error
                }
                projections.markOutboundSent(durable, receipt)
            }
        } catch (error: SecureMessagingCryptographicFailureException) {
            runCatching { session.quarantine(error) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /**
     * Advances a bounded amount of durable history work after ordinary sync is safely committed.
     * History failures remain pending and never hold the live message cursor or current outbox.
     */
    suspend fun recoverPendingHistory(
        session: RemoteSecureMessagingTransport.Session,
    ) = mutex.withLock {
        val pending = try {
            projections.pendingHistoryBackfills(MAX_HISTORY_TASKS_PER_RUN)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock
        }
        pending.forEach { task ->
            try {
                backfillRetainedHistory(session, task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: SecureMessagingAuthenticationEpochChangedException) {
                throw changed
            } catch (error: KitWalletApiException) {
                if (error.code in TERMINAL_HISTORY_TASK_CODES) {
                    updateHistoryTaskBestEffort(task, nextCursor = null, completed = true)
                } else if (error.code == HISTORY_CURSOR_INVALID) {
                    updateHistoryTaskBestEffort(task, nextCursor = null, completed = false)
                }
                // Retry every other remote failure on a later sync. The ordinary stream and
                // outbox have already completed before this best-effort stage begins.
            } catch (_: Exception) {
                // Malformed retained history, target-only crypto failures, state contention and
                // transport validation are isolated to this durable task and retried later.
            }
        }
    }

    private suspend fun updateHistoryTaskBestEffort(
        task: SecureMessagingHistoryBackfillTask,
        nextCursor: String?,
        completed: Boolean,
    ) {
        try {
            projections.updateHistoryBackfill(task, nextCursor, completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A later synchronization re-reads the still-durable task.
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

    private fun KitWalletApiException.isPermanentAttachmentBindingFailure(): Boolean = when (code) {
        ATTACHMENT_REFERENCE_INVALID -> statusCode == 422
        ATTACHMENT_ALREADY_ATTACHED -> statusCode == 409
        else -> false
    }

    private fun KitWalletApiException.isAttachmentCompatibilityFailure(): Boolean =
        statusCode == 409 && code in ATTACHMENT_COMPATIBILITY_FAILURES

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
                is RemoteSecureMessagingTransport.Session.DeliveryReceiptEvent ->
                    processDeliveryReceipt(state, event)
                is RemoteSecureMessagingTransport.Session.ReadReceiptEvent ->
                    processReadReceipt(state, event)
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
            projections.enqueueHistoryBackfill(
                conversationId = event.conversationId,
                targetDeviceId = event.affectedDeviceId,
                targetEnrollmentEpoch = event.affectedEnrollmentEpoch,
            )
        }
    }

    private suspend fun backfillRetainedHistory(
        session: RemoteSecureMessagingTransport.Session,
        initialTask: SecureMessagingHistoryBackfillTask,
    ) {
        val conversations = session.directConversations()
            .associateBy(RemoteSecureMessagingTransport.Session.DirectConversation::conversationId)
        val conversation = conversations[initialTask.conversationId]
        if (conversation == null) {
            projections.updateHistoryBackfill(initialTask, nextCursor = null, completed = true)
            return
        }
        val roster = session.roster(conversation)
        var task = initialTask
        repeat(MAX_HISTORY_PAGES_PER_RUN) {
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
                val projected = projections.retainedHistorySource(
                    messageId = candidate.messageId,
                    clientMessageId = candidate.clientMessageId,
                ) ?: return@forEach
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
                val encrypted = projections.readHistoryOutbound(transferId)?.let { durable ->
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
                        session = session,
                        conversation = conversation,
                        roster = roster,
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
                projections.updateHistoryBackfill(task, nextCursor = null, completed = true)
                return
            }
            val next = checkNotNull(page.nextAfter) {
                "History candidate page omitted its continuation"
            }
            check(task.nextCursor == null || next != task.nextCursor) {
                "History candidate pagination did not advance"
            }
            task = projections.updateHistoryBackfill(task, nextCursor = next, completed = false)
        }
    }

    private suspend fun commitHistoryEncryption(
        session: RemoteSecureMessagingTransport.Session,
        conversation: RemoteSecureMessagingTransport.Session.DirectConversation,
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
                decrypted = commitDecryption(
                    session = state.session,
                    request = request,
                    intent = projections.inboundIntent(envelope.messageId),
                )
                state.pendingDecryption = null
                durable = checkNotNull(projections.readInbound(envelope.messageId)) {
                    "Committed incoming message omitted its durable projection"
                }
            }

            val persisted = checkNotNull(durable)
            if (!hasAuthenticatedAttachmentBinding(envelope, persisted)) {
                // A peer controls both the outer metadata and encrypted plaintext it sends. A
                // mismatch is therefore message-local hostile input, not evidence that this
                // account activation or ratchet is corrupt. Keep the durable crypto commit,
                // suppress projection/opening, acknowledge the envelope and advance the cursor
                // so the peer cannot remotely quarantine or permanently wedge this recipient.
                // Commit the tombstone before acknowledgement/cursor advance. If this write is
                // interrupted, replay sees the companion commit, revalidates the same binding,
                // and retries suppression without re-running the ratchet.
                projections.recordInboundSuppressed(persisted, envelope.sentAt)
                val token = if (decrypted != null) {
                    state.session.deliveryToken(envelope, decrypted)
                } else {
                    state.session.deliveryTokenFromDurableState(envelope, persisted)
                }
                state.deliveryTokens += token
                return
            }
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

    private suspend fun processIncomingHistory(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
    ) {
        val transferId = checkNotNull(envelope.transferClientMessageId) {
            "Validated history envelope omitted its transfer ID"
        }
        var wrapper = projections.readHistoryInbound(transferId)
        var decrypted: SecureMessagingCommittedResult.Decrypted? = null
        try {
            if (wrapper == null) {
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
                decrypted = commitDecryption(
                    session = state.session,
                    request = request,
                    intent = projections.historyInboundIntent(transferId),
                )
                state.pendingDecryption = null
                wrapper = checkNotNull(projections.readHistoryInbound(transferId)) {
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

            val recovered = try {
                projections.recordRecoveredHistory(authenticated)
            } catch (unavailable: SecureMessagingStateUnavailableException) {
                throw unavailable
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
            if (!projections.isInboundPublicationRecorded(recovered, authenticated.sentAt)) {
                try {
                    notifications.publish(
                        SecureMessagingIncomingNotification(
                            messageId = authenticated.messageId,
                            conversationId = authenticated.conversationId,
                            sessionEpoch = state.session.binding.sessionEpoch,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw SecureMessagingNotificationPublicationException(error)
                }
            }
            projections.recordInbound(recovered, authenticated.sentAt)
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
        val descriptor = KitMediaMessage.parse(durable.authenticatedText)
        val authenticatedAttachments = descriptor?.let { listOf(it.toAttachmentRequest()) }
            ?: emptyList()
        val authenticatedKind = if (authenticatedAttachments.isEmpty()) {
            ENCRYPTED_MESSAGE_KIND
        } else {
            ENCRYPTED_ATTACHMENT_MESSAGE_KIND
        }
        return envelope.kind == authenticatedKind &&
            envelope.attachments == authenticatedAttachments
    }

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
        event: RemoteSecureMessagingTransport.Session.OutboundEvent,
    ) {
        val durable = projections.readOutbound(event.clientMessageId) ?: return
        projections.markOutboundSent(durable, event)
    }

    private suspend fun processDeliveryReceipt(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.DeliveryReceiptEvent,
    ) {
        projections.markAuthoredDelivered(
            conversationId = event.conversationId,
            messageId = event.messageId,
            currentUserId = state.session.binding.userId,
            deliveredAt = event.deliveredAt,
        )
    }

    private suspend fun processReadReceipt(
        state: SessionState,
        event: RemoteSecureMessagingTransport.Session.ReadReceiptEvent,
    ) {
        val conversations = state.conversations ?: state.session.directConversations()
            .associateBy(RemoteSecureMessagingTransport.Session.DirectConversation::conversationId)
            .also { state.conversations = it }
        val conversation = checkNotNull(conversations[event.conversationId]) {
            "Read receipt belongs to an unavailable direct conversation"
        }
        if (event.readerUserId == state.session.binding.userId) {
            // The backend broadcasts this account's marker to every enrolled device. It is not
            // peer-read evidence for self-authored bubbles, but it does clear authenticated inbound
            // messages from the direct peer on this account's other devices.
            projections.markInboundReadThroughCanonicalIfKnown(
                conversationId = event.conversationId,
                peerUserId = conversation.peerUserId,
                canonicalLastReadMessageId = event.lastReadMessageId,
                canonicalReadAt = event.readAt,
            )
            return
        }
        check(conversation.peerUserId == event.readerUserId) {
            "Read receipt actor is not the authenticated direct peer"
        }
        projections.markAuthoredReadThrough(
            conversationId = event.conversationId,
            lastReadMessageId = event.lastReadMessageId,
            currentUserId = state.session.binding.userId,
            readAt = event.readAt,
        )
    }

    private suspend fun historicalPlan(
        state: SessionState,
        envelope: RemoteSecureMessagingTransport.Session.IncomingEnvelope,
    ): HistoricalRosterPlan {
        val key = HistoricalRosterKey(envelope.conversationId, envelope.transferRosterRevision)
        state.historicalPlans[key]?.let { return it }
        val conversations = state.conversations ?: state.session.directConversations()
            .associateBy(RemoteSecureMessagingTransport.Session.DirectConversation::conversationId)
            .also { state.conversations = it }
        val conversation = checkNotNull(conversations[envelope.conversationId]) {
            "Incoming secure message belongs to an unavailable direct conversation"
        }
        val roster = state.session.historicalRoster(
            conversation,
            envelope.transferRosterRevision,
        )
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
        const val ATTACHMENT_REFERENCE_INVALID = "ATTACHMENT_REFERENCE_INVALID"
        const val ATTACHMENT_ALREADY_ATTACHED = "ATTACHMENT_ALREADY_ATTACHED"
        val ATTACHMENT_COMPATIBILITY_FAILURES = setOf(
            "MESSAGING_ATTACHMENT_CLIENT_UPGRADE_REQUIRED",
            "MESSAGING_V2_CONTENT_PROFILE_UNAVAILABLE",
        )
        const val SYNC_PAGE_SIZE = 50
        const val OUTBOX_PAGE_SIZE = 100
        const val MAX_OUTBOX_PAGES = 100
        const val HISTORY_PAGE_SIZE = 50
        const val MAX_HISTORY_PAGES_PER_RUN = 4
        const val MAX_HISTORY_TASKS_PER_RUN = 4
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
