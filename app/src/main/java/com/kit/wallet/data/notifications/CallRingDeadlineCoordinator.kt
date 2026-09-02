package com.kit.wallet.data.notifications

import android.app.NotificationManager
import android.content.Context
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.feature.calls.KitTelecomBridge
import com.kit.wallet.feature.calls.KitTelecomDisconnect
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-local fallback for a missing terminal call push. The server-authenticated ring window is
 * translated onto the current boot's monotonic clock; this coordinator removes stale local
 * notification, UI and Telecom ringing when that lease ends.
 */
@Singleton
class CallRingDeadlineCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callEvents: CallLifecycleEventBus,
    private val incomingCallRelay: IncomingCallRelay,
    private val telecom: KitTelecomBridge,
    private val replayLedger: IncomingCallReplayLedger,
    elapsedRealtimeClock: ElapsedRealtimeClock,
    bootSessionIdProvider: BootSessionIdProvider,
    @ApplicationScope scope: CoroutineScope,
) {
    private val scheduler = RingDeadlineScheduler(
        scope = scope,
        nowElapsedRealtimeMillis = elapsedRealtimeClock::millis,
        currentBootSessionId = bootSessionIdProvider::currentBootId,
        onExpired = ::expire,
    )

    fun schedule(callId: String, ringLease: CallRingLease): Boolean =
        scheduler.schedule(callId, ringLease)

    fun cancel(callId: String) = scheduler.cancel(callId)

    /** Atomically fences future ring deliveries before cancelling the process-local deadline. */
    internal fun retire(callId: String, disposition: IncomingCallRetirementDisposition) {
        dispatchRingRetirement(
            callId = callId,
            retireReplay = { replayLedger.retire(it, disposition) },
            retireRelay = incomingCallRelay::retire,
            cancelDeadline = scheduler::cancel,
            cancelNotification = {
                context.getSystemService(NotificationManager::class.java)?.cancel(
                    CallActionReceiver.notificationTag(it),
                    CallActionReceiver.NOTIFICATION_ID,
                )
            },
        )
    }

    private fun expire(callId: String) {
        // A terminal action that won the replay-ledger race owns cleanup and classification.
        if (!replayLedger.claimExpiry(callId)) return
        incomingCallRelay.retire(callId)
        dispatchRingDeadlineExpiry(
            callId = callId,
            cancelNotification = {
                context.getSystemService(NotificationManager::class.java)?.cancel(
                    CallActionReceiver.notificationTag(it),
                    CallActionReceiver.NOTIFICATION_ID,
                )
            },
            finishTelecom = { telecom.finish(it, KitTelecomDisconnect.MISSED) },
            publishLifecycle = { callEvents.publish(it) },
        )
    }
}

internal fun dispatchRingRetirement(
    callId: String,
    retireReplay: (String) -> Unit,
    retireRelay: (String) -> Unit,
    cancelDeadline: (String) -> Unit,
    cancelNotification: (String) -> Unit,
) {
    retireReplay(callId)
    retireRelay(callId)
    cancelDeadline(callId)
    cancelNotification(callId)
}

/**
 * Performs the deadline cleanup in a strict order: remove the alert, tombstone Telecom, then wake
 * the matching foreground UI with a local missed event.
 */
internal fun dispatchRingDeadlineExpiry(
    callId: String,
    cancelNotification: (String) -> Unit,
    finishTelecom: (String) -> Unit,
    publishLifecycle: (CallLifecycleEvent) -> Unit,
) {
    cancelNotification(callId)
    // Finish Telecom before publishing so a foreground call screen cannot leave a late
    // createConnection callback ringing while it handles the synthetic missed event.
    finishTelecom(callId)
    publishLifecycle(
        CallLifecycleEvent(
            callId = callId,
            kind = CallLifecycleKind.MISSED,
            state = "missed",
            reason = "ring_timeout",
        ),
    )
}

/** Coroutine-only deadline primitive kept free of Android dependencies for deterministic tests. */
internal class RingDeadlineScheduler(
    private val scope: CoroutineScope,
    private val nowElapsedRealtimeMillis: () -> Long,
    private val currentBootSessionId: () -> Long?,
    private val onExpired: (callId: String) -> Unit,
) {
    private val lock = Any()
    private val scheduled = mutableMapOf<String, ScheduledDeadline>()

    fun schedule(callId: String, ringLease: CallRingLease): Boolean {
        if (!ringLease.isStructurallyValid()) return false
        val now = nowElapsedRealtimeMillis()
        if (
            currentBootSessionId() != ringLease.bootSessionId ||
            now < ringLease.receivedElapsedRealtimeMillis
        ) {
            return false
        }
        val delayMillis = runCatching {
            Math.subtractExact(ringLease.deadlineElapsedRealtimeMillis, now).coerceAtLeast(0L)
        }.getOrNull() ?: return false
        val token = Any()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMillis)
            val ownsDeadline = synchronized(lock) {
                val current = scheduled[callId]
                if (current?.token !== token) {
                    false
                } else {
                    scheduled.remove(callId)
                    true
                }
            }
            if (ownsDeadline) onExpired(callId)
        }
        var retainedExisting = false
        var rejectedReplacement = false
        val previous = synchronized(lock) {
            val current = scheduled[callId]
            when {
                current == null -> {
                    scheduled.put(callId, ScheduledDeadline(token, job, ringLease))?.job
                }
                current.ringLease.bootSessionId != ringLease.bootSessionId ||
                    current.ringLease.sourceRingExpiresAt != ringLease.sourceRingExpiresAt -> {
                    rejectedReplacement = true
                    null
                }
                current.ringLease.deadlineElapsedRealtimeMillis <=
                    ringLease.deadlineElapsedRealtimeMillis -> {
                    retainedExisting = true
                    null
                }
                else -> scheduled.put(callId, ScheduledDeadline(token, job, ringLease))?.job
            }
        }
        if (retainedExisting || rejectedReplacement) {
            job.cancel()
            return retainedExisting
        }
        previous?.cancel()
        job.start()
        return true
    }

    fun cancel(callId: String) {
        synchronized(lock) { scheduled.remove(callId) }?.job?.cancel()
    }

    private data class ScheduledDeadline(
        val token: Any,
        val job: Job,
        val ringLease: CallRingLease,
    )
}
