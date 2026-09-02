package com.kit.wallet.data.messaging

import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.GroupPaymentRequestStatus
import com.kit.wallet.data.remote.KitGroupPaymentRequestAction
import com.kit.wallet.data.remote.KitGroupPaymentRequestMessage
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.GroupPaymentShareStatus
import com.kit.wallet.ui.model.TransferClaimStatus
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Outcome of exact-read recovery for staged, conversation-owned financial events. */
internal enum class PendingFinancialEventRecoveryOutcome { IDLE, COMMITTED, RETRY }

/**
 * Stages deterministic conversation events before their financial command and activates them only
 * after an exact authoritative result. The ordinary immediate-send dispatcher ignores staged
 * records, so a failed command can never publish a false payment event.
 */
@Singleton
internal class PendingFinancialEventCoordinator @Inject constructor(
    private val store: ImmediateSendIntentStore,
    private val sessions: SessionStore,
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val clock: Clock,
    private val scheduler: SecureMessagingSyncScheduler? = null,
) {
    /** A live foreground mutation owns its staged event until it commits or yields to recovery. */
    private val activeSubmissions = ConcurrentHashMap.newKeySet<Pair<SessionFence, String>>()

    suspend fun stagePaymentEvent(
        owner: SessionFence,
        conversationId: String,
        event: KitPaymentMessage,
    ): String = stage(
        owner = owner,
        conversationId = conversationId,
        kind = ImmediateSendKind.PAYMENT_EVENT,
        descriptor = event.encode(),
        clientMessageId = event.deterministicMessageId(),
    )

    suspend fun stageGroupPaymentEvent(
        owner: SessionFence,
        conversationId: String,
        event: KitGroupPaymentMessage,
        clientMessageId: String,
    ): String = stage(
        owner = owner,
        conversationId = conversationId,
        kind = ImmediateSendKind.GROUP_PAYMENT_EVENT,
        descriptor = event.encode(),
        clientMessageId = clientMessageId,
    )

    suspend fun stageGroupPaymentRequestEvent(
        owner: SessionFence,
        conversationId: String,
        event: KitGroupPaymentRequestMessage,
    ): String = stage(
        owner = owner,
        conversationId = conversationId,
        kind = ImmediateSendKind.GROUP_PAYMENT_REQUEST_EVENT,
        descriptor = event.encode(),
        clientMessageId = event.deterministicMessageId(),
    )

    suspend fun commit(owner: SessionFence, clientMessageId: String): Boolean = try {
        val current = store.findForOwner(owner, clientMessageId) ?: return false
        when (current.state) {
            ImmediateSendState.FINANCIAL_PENDING ->
                store.replaceForOwner(owner, current, current.copy(state = ImmediateSendState.WAITING))
            ImmediateSendState.WAITING,
            ImmediateSendState.RETRY_REQUIRED,
            ImmediateSendState.FAILED,
            -> true
            ImmediateSendState.IMPORTING,
            ImmediateSendState.PREPARING,
            -> false
        }
    } finally {
        if (activeSubmissions.remove(owner to clientMessageId)) {
            runCatching { scheduler?.schedule() }
        }
    }

    /** Removes only an event which has not been authorized for delivery. */
    suspend fun discard(owner: SessionFence, clientMessageId: String) {
        try {
            val current = store.findForOwner(owner, clientMessageId) ?: return
            if (current.state == ImmediateSendState.FINANCIAL_PENDING) {
                store.removeForOwner(owner, clientMessageId)
            }
        } finally {
            activeSubmissions -= owner to clientMessageId
        }
    }

    /** Hands an ambiguous foreground failure to exact read-only recovery without deleting it. */
    suspend fun releaseForRecovery(owner: SessionFence, clientMessageId: String) {
        if (
            activeSubmissions.remove(owner to clientMessageId) &&
            sessions.current()?.fence() == owner
        ) {
            runCatching { scheduler?.schedule() }
        }
    }

    /**
     * Exact-read restart recovery. It performs no financial mutation and no list/history scan.
     * An unresolved record is retained indefinitely; only a contradictory terminal state or an
     * explicit caller-side pre-submit failure may discard it.
     */
    suspend fun recover(): PendingFinancialEventRecoveryOutcome {
        val owner = store.loadForCurrentOwner()
            ?: return PendingFinancialEventRecoveryOutcome.IDLE
        val pending = store.itemsForOwner(owner)
            .asSequence()
            .filter { it.state == ImmediateSendState.FINANCIAL_PENDING }
            .take(MAX_RECOVERY_BATCH)
            .toList()
        if (pending.isEmpty()) return PendingFinancialEventRecoveryOutcome.IDLE
        var progressed = false
        var retry = false
        for (intent in pending) {
            if (sessions.current()?.fence() != owner) break
            if (owner to intent.id in activeSubmissions) {
                retry = true
                continue
            }
            try {
                when (resolve(owner, intent)) {
                    Resolution.CONFIRMED -> {
                        commit(owner, intent.id)
                        progressed = true
                    }
                    Resolution.CONTRADICTED -> {
                        discard(owner, intent.id)
                        progressed = true
                    }
                    Resolution.UNRESOLVED -> retry = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalidated: SessionInvalidatedException) {
                break
            } catch (_: Exception) {
                retry = true
            }
        }
        return when {
            retry || pending.size == MAX_RECOVERY_BATCH ->
                PendingFinancialEventRecoveryOutcome.RETRY
            progressed -> PendingFinancialEventRecoveryOutcome.COMMITTED
            else -> PendingFinancialEventRecoveryOutcome.IDLE
        }
    }

    private suspend fun stage(
        owner: SessionFence,
        conversationId: String,
        kind: ImmediateSendKind,
        descriptor: String,
        clientMessageId: String,
    ): String {
        require(kind in RECOVERABLE_KINDS) { "Unsupported financial event kind" }
        require(recoverableDescriptor(kind, descriptor)) {
            "This financial event cannot be recovered from exact state"
        }
        activeSubmissions += owner to clientMessageId
        try {
            store.enqueueIdempotentForOwner(
                owner,
                ImmediateSendIntent(
                    id = clientMessageId,
                    conversationId = conversationId,
                    kind = kind,
                    createdAtEpochMillis = clock.millis(),
                    state = ImmediateSendState.FINANCIAL_PENDING,
                    text = descriptor,
                ),
            )
        } catch (error: Throwable) {
            activeSubmissions -= owner to clientMessageId
            throw error
        }
        return clientMessageId
    }

    private suspend fun resolve(owner: SessionFence, intent: ImmediateSendIntent): Resolution {
        sessions.withCurrentSession(owner) { }
        val resolution = when (intent.kind) {
            ImmediateSendKind.PAYMENT_EVENT -> resolvePaymentEvent(owner, intent.text)
            ImmediateSendKind.GROUP_PAYMENT_EVENT -> resolveGroupPaymentEvent(owner, intent.text)
            ImmediateSendKind.GROUP_PAYMENT_REQUEST_EVENT ->
                resolveGroupPaymentRequestEvent(owner, intent.text)
            else -> error("A non-financial event entered financial recovery")
        }
        sessions.withCurrentSession(owner) { }
        return resolution
    }

    private suspend fun resolvePaymentEvent(owner: SessionFence, text: String): Resolution {
        val event = checkNotNull(KitPaymentMessage.parse(text))
        return when (event.action) {
            KitPaymentAction.PAID,
            KitPaymentAction.CANCELLED,
            -> {
                val request = apiCalls.execute {
                    api.paymentRequest(event.referenceId, owner)
                }
                check(request.id.equals(event.referenceId, ignoreCase = true)) {
                    "Payment-request recovery returned another request"
                }
                check(request.type == "payment_request") {
                    "Payment-request recovery returned another resource"
                }
                val scale = request.currency.scale.toIntOrNull()
                    ?: error("Payment-request recovery returned an invalid currency")
                check(
                    request.currency.code == event.currencyCode && scale == event.currencyScale &&
                        DecimalMoney.toMinor(request.amount, scale) == event.amountMinor
                ) { "Payment-request recovery changed the event amount" }
                when (request.status.lowercase()) {
                    "paid" -> if (event.action == KitPaymentAction.PAID &&
                        !request.walletTransactionId.isNullOrBlank()
                    ) Resolution.CONFIRMED else Resolution.CONTRADICTED
                    "cancelled" -> if (event.action == KitPaymentAction.CANCELLED) {
                        Resolution.CONFIRMED
                    } else {
                        Resolution.CONTRADICTED
                    }
                    "pending" -> Resolution.UNRESOLVED
                    else -> Resolution.CONTRADICTED
                }
            }
            KitPaymentAction.ACCEPTED,
            KitPaymentAction.REJECTED,
            KitPaymentAction.REVERSED,
            -> {
                val claim = apiCalls.execute {
                    api.transferClaim(event.referenceId, owner)
                }.toUiModel() ?: error("Transfer recovery returned an invalid claim")
                check(
                    claim.id.equals(event.referenceId, ignoreCase = true) &&
                        claim.amountMinor == event.amountMinor &&
                        claim.currencyCode == event.currencyCode &&
                        claim.currencyScale == event.currencyScale
                ) { "Transfer recovery changed the event identity" }
                val expected = when (event.action) {
                    KitPaymentAction.ACCEPTED -> TransferClaimStatus.ACCEPTED
                    KitPaymentAction.REJECTED -> TransferClaimStatus.REJECTED
                    KitPaymentAction.REVERSED -> TransferClaimStatus.REVERSED
                    else -> error("unreachable")
                }
                when {
                    claim.status == expected -> Resolution.CONFIRMED
                    claim.status == TransferClaimStatus.PENDING -> Resolution.UNRESOLVED
                    else -> Resolution.CONTRADICTED
                }
            }
            else -> error("This payment action is not a staged financial outcome")
        }
    }

    private suspend fun resolveGroupPaymentEvent(
        owner: SessionFence,
        text: String,
    ): Resolution {
        val event = checkNotNull(KitGroupPaymentMessage.parse(text))
        val payment = apiCalls.execute {
            api.groupPayment(event.groupPaymentId, owner)
        }.toUiModel() ?: error("Group-payment recovery returned invalid state")
        check(payment.id.equals(event.groupPaymentId, ignoreCase = true)) {
            "Group-payment recovery returned another payment"
        }
        return when (event.action) {
            KitGroupPaymentAction.ACCEPTED,
            KitGroupPaymentAction.REJECTED,
            -> {
                val expected = if (event.action == KitGroupPaymentAction.ACCEPTED) {
                    GroupPaymentShareStatus.ACCEPTED
                } else {
                    GroupPaymentShareStatus.REJECTED
                }
                when (payment.yourShare?.status) {
                    expected -> Resolution.CONFIRMED
                    GroupPaymentShareStatus.PENDING -> Resolution.UNRESOLVED
                    else -> Resolution.CONTRADICTED
                }
            }
            KitGroupPaymentAction.RETURNED -> when {
                payment.pendingCount > 0 -> Resolution.UNRESOLVED
                payment.recipients.any { it.status == GroupPaymentShareStatus.REVERSED } ->
                    Resolution.CONFIRMED
                else -> Resolution.CONTRADICTED
            }
            KitGroupPaymentAction.SENT -> error("A group-payment creation uses result recovery")
        }
    }

    private suspend fun resolveGroupPaymentRequestEvent(
        owner: SessionFence,
        text: String,
    ): Resolution {
        val event = checkNotNull(KitGroupPaymentRequestMessage.parse(text))
        check(event.action == KitGroupPaymentRequestAction.CANCELLED) {
            "Only cancellation has a precomputable group-request event"
        }
        val request = apiCalls.execute {
            api.groupPaymentRequest(event.requestId, owner)
        }
        check(request.id.equals(event.requestId, ignoreCase = true) && request.isStructurallyValid()) {
            "Group-request recovery returned another request"
        }
        return when (request.knownStatus) {
            GroupPaymentRequestStatus.CANCELLED -> Resolution.CONFIRMED
            GroupPaymentRequestStatus.OPEN -> Resolution.UNRESOLVED
            else -> Resolution.CONTRADICTED
        }
    }

    private fun recoverableDescriptor(kind: ImmediateSendKind, text: String): Boolean = when (kind) {
        ImmediateSendKind.PAYMENT_EVENT -> KitPaymentMessage.parse(text)?.action in setOf(
            KitPaymentAction.PAID,
            KitPaymentAction.CANCELLED,
            KitPaymentAction.ACCEPTED,
            KitPaymentAction.REJECTED,
            KitPaymentAction.REVERSED,
        )
        ImmediateSendKind.GROUP_PAYMENT_EVENT ->
            KitGroupPaymentMessage.parse(text)?.action != KitGroupPaymentAction.SENT
        ImmediateSendKind.GROUP_PAYMENT_REQUEST_EVENT ->
            KitGroupPaymentRequestMessage.parse(text)?.action ==
                KitGroupPaymentRequestAction.CANCELLED
        else -> false
    }

    private enum class Resolution { CONFIRMED, UNRESOLVED, CONTRADICTED }

    private companion object {
        val RECOVERABLE_KINDS = setOf(
            ImmediateSendKind.PAYMENT_EVENT,
            ImmediateSendKind.GROUP_PAYMENT_EVENT,
            ImmediateSendKind.GROUP_PAYMENT_REQUEST_EVENT,
        )
        const val MAX_RECOVERY_BATCH = 16
    }
}
