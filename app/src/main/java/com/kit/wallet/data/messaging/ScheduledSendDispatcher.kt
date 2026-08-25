package com.kit.wallet.data.messaging

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one way a scheduled item becomes a real message.
 *
 * Deliberately narrow: the dispatcher knows nothing about conversations, rosters or ciphertext, and
 * the implementation reuses the ordinary send paths rather than a parallel one. A scheduled message
 * is sent exactly the way the same message typed at that moment would be, which is what keeps it
 * end-to-end encrypted under the roster that is current *then*, and what puts it in the same
 * durable outbox with the same retry and de-duplication behind it.
 */
internal interface ScheduledSendGateway {
    /** Whether a message can actually leave this device right now. */
    fun readyFor(owner: SessionFence): Boolean

    /**
     * Sends [text] into [conversationId], invoking [onDurablyCommitted] at the exact instant the
     * encrypted outbox takes ownership — before any network call, and therefore before anything
     * can go wrong that the outbox does not already handle.
     */
    suspend fun sendText(
        owner: SessionFence,
        conversationId: String,
        text: String,
        onDurablyCommitted: () -> Unit,
    )

    /**
     * Creates the payment request under [idempotencyKey] and shares its encrypted card into the
     * conversation, invoking [onDurablyCommitted] once that card is owned by the outbox.
     *
     * The key is the scheduled item's own identity, so a dispatch that failed after the request was
     * created reuses the same server-side request instead of minting a second one. Money is never
     * moved here; a request is only an ask.
     */
    suspend fun sendPaymentRequest(
        owner: SessionFence,
        conversationId: String,
        idempotencyKey: String,
        amountMinor: Long,
        note: String?,
        onDurablyCommitted: () -> Unit,
    )
}

/** What one dispatch run achieved, in the terms a background worker needs to decide what next. */
internal enum class ScheduledSendDispatchOutcome {
    /** Nothing was due. */
    IDLE,

    /** At least one item was handed to the outbox, and none was left needing another attempt. */
    COMMITTED,

    /** Something is due but secure messaging is not ready, so this run achieved nothing. */
    NOT_READY,

    /** At least one attempt ended without committing; the item is still scheduled. */
    RETRY,
}

/**
 * Sends scheduled items whose time has come, at most once each.
 *
 * The duplicate-prevention rule is the whole design. Each item is *claimed* with a
 * compare-and-set against its durable record before it is sent, so two dispatches racing on the
 * same item cannot both proceed. The claim is released — by deleting the record — the moment the
 * outbox durably owns the message, which is the earliest point at which a resend would be a
 * duplicate. An attempt that never reached that point committed nothing, so returning it to the
 * queue is always safe.
 *
 * The one case the code cannot decide is a process that dies between those two facts. Its claim is
 * left behind, and rather than guess, [ScheduledSendState.UNCONFIRMED] hands the decision to the
 * person who can see the conversation.
 */
@Singleton
internal class ScheduledSendDispatcher @Inject constructor(
    private val store: ScheduledSendStore,
    private val gateway: ScheduledSendGateway,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Sends everything due now. Safe to call from anywhere, as often as anything likes. */
    suspend fun dispatchDue(): ScheduledSendDispatchOutcome = mutex.withLock {
        val owner = store.loadForCurrentOwner()
            ?: return@withLock ScheduledSendDispatchOutcome.IDLE
        dispatchDueLocked(owner)
    }

    /** Dispatches only for the owner captured by a long-lived UI or worker operation. */
    internal suspend fun dispatchDueForOwner(
        owner: SessionFence,
    ): ScheduledSendDispatchOutcome = mutex.withLock {
        store.loadForOwner(owner)
        dispatchDueLocked(owner)
    }

    private suspend fun dispatchDueLocked(owner: SessionFence): ScheduledSendDispatchOutcome {
        retireStaleClaims(owner)

        val now = clock.millis()
        val due = store.itemsForOwner(owner).filter {
            it.state == ScheduledSendState.WAITING && it.nextEligibleAtEpochMillis() <= now
        }
        if (due.isEmpty()) return ScheduledSendDispatchOutcome.IDLE
        if (!gateway.readyFor(owner)) {
            // Being unable to start is still an attempt, and it has to be recorded as one. The
            // backoff it produces is what keeps a device that never reaches readiness from being
            // woken every time the queue is looked at.
            due.forEach { item ->
                store.compareAndSetForOwner(owner, item, item.attempted(now))
            }
            return ScheduledSendDispatchOutcome.NOT_READY
        }

        var committed = false
        var retry = false
        due.forEach { item ->
            when (dispatchOne(owner, item)) {
                DispatchResult.COMMITTED -> committed = true
                DispatchResult.RETRY -> retry = true
                DispatchResult.SKIPPED -> Unit
            }
        }
        return when {
            retry -> ScheduledSendDispatchOutcome.RETRY
            committed -> ScheduledSendDispatchOutcome.COMMITTED
            else -> ScheduledSendDispatchOutcome.IDLE
        }
    }

    /**
     * Moves [id] to the front of the queue and sends it now.
     *
     * This is the "Send now" action on a scheduled item, and the only way an
     * [ScheduledSendState.UNCONFIRMED] item ever moves again — after a person has looked at the
     * conversation and decided it did not arrive.
     */
    suspend fun sendNow(id: String) {
        val owner = store.loadForCurrentOwner() ?: return
        sendNowForOwner(owner, id)
    }

    /** The UI form: a stale conversation cannot act on a successor owner's same-ID item. */
    internal suspend fun sendNowForOwner(owner: SessionFence, id: String): Unit = mutex.withLock {
        store.loadForOwner(owner)
        val item = store.itemsForOwner(owner).firstOrNull { it.id == id } ?: return@withLock
        // A live claim belongs to a dispatch that is sending this very item; leave it alone rather
        // than race it, which is exactly the duplicate this design exists to avoid.
        if (item.state == ScheduledSendState.SENDING && !item.claimIsStale(clock.millis())) {
            return@withLock
        }
        val armed = item.copy(
            state = ScheduledSendState.WAITING,
            scheduledAtEpochMillis = clock.millis(),
            attempts = 0,
            claimedAtEpochMillis = 0,
            lastAttemptAtEpochMillis = 0,
        )
        if (!store.compareAndSetForOwner(owner, item, armed)) return@withLock
        dispatchDueLocked(owner)
    }

    private enum class DispatchResult { COMMITTED, RETRY, SKIPPED }

    private suspend fun dispatchOne(owner: SessionFence, item: ScheduledSend): DispatchResult {
        val claimed = item.copy(
            state = ScheduledSendState.SENDING,
            claimedAtEpochMillis = clock.millis(),
        )
        // Losing the claim means another dispatch owns this item. Not an error, and not a retry:
        // whoever won will finish it or return it to the queue itself.
        if (!store.compareAndSetForOwner(owner, item, claimed)) return DispatchResult.SKIPPED

        var durablyCommitted = false
        val onCommitted = { durablyCommitted = true }
        val failure = try {
            if (!store.isCurrentOwner(owner)) throw SessionInvalidatedException()
            when (item.kind) {
                ScheduledSendKind.TEXT -> gateway.sendText(
                    owner = owner,
                    conversationId = item.conversationId,
                    text = item.text,
                    onDurablyCommitted = onCommitted,
                )
                ScheduledSendKind.PAYMENT_REQUEST -> gateway.sendPaymentRequest(
                    owner = owner,
                    conversationId = item.conversationId,
                    idempotencyKey = item.id,
                    amountMinor = item.amountMinor,
                    note = item.note,
                    onDurablyCommitted = onCommitted,
                )
            }
            null
        } catch (error: Throwable) {
            error
        }

        // `onDurablyCommitted` runs synchronously inside the send, before its network call, so this
        // flag is a fact by the time control is back here — cancellation included.
        return withContext(NonCancellable) {
            if (durablyCommitted) {
                store.removeIfUnchangedForOwner(owner, claimed)
                DispatchResult.COMMITTED
            } else {
                // Nothing was committed, so returning this to the queue cannot duplicate anything.
                // A cancelled run is not the item's fault and does not count against its backoff.
                val released = if (failure is CancellationException) {
                    claimed.copy(state = ScheduledSendState.WAITING, claimedAtEpochMillis = 0)
                } else {
                    claimed.attempted(clock.millis())
                }
                store.compareAndSetForOwner(owner, claimed, released)
                DispatchResult.RETRY
            }
        }.also { if (failure is CancellationException) throw failure }
    }

    /**
     * Claims nobody is holding any more belong to a process that died mid-send.
     *
     * There is no way to tell from here whether that send reached the outbox, and a wrong guess
     * either loses a message or sends it twice. It becomes a question for the user instead.
     */
    private suspend fun retireStaleClaims(owner: SessionFence) {
        val now = clock.millis()
        store.itemsForOwner(owner).filter { it.claimIsStale(now) }.forEach { stale ->
            store.compareAndSetForOwner(
                owner,
                stale,
                stale.copy(state = ScheduledSendState.UNCONFIRMED, claimedAtEpochMillis = 0),
            )
        }
    }
}
