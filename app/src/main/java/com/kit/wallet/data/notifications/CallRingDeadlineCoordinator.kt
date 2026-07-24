package com.kit.wallet.data.notifications

import android.app.NotificationManager
import android.content.Context
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.feature.calls.KitTelecomBridge
import com.kit.wallet.feature.calls.KitTelecomDisconnect
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-local fallback for a missing terminal call push. The server's absolute ring deadline is
 * authoritative; this coordinator only removes stale local notification, UI and Telecom ringing.
 */
@Singleton
class CallRingDeadlineCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callEvents: CallLifecycleEventBus,
    private val telecom: KitTelecomBridge,
    @ApplicationScope scope: CoroutineScope,
    clock: Clock,
) {
    private val scheduler = RingDeadlineScheduler(
        scope = scope,
        now = { Instant.now(clock) },
        onExpired = ::expire,
    )

    fun schedule(callId: String, ringExpiresAt: String?): Boolean =
        scheduler.schedule(callId, ringExpiresAt)

    fun cancel(callId: String) = scheduler.cancel(callId)

    private fun expire(callId: String) {
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
    private val now: () -> Instant,
    private val onExpired: (callId: String) -> Unit,
) {
    private val lock = Any()
    private val scheduled = mutableMapOf<String, ScheduledDeadline>()

    fun schedule(callId: String, ringExpiresAt: String?): Boolean {
        val expiresAt = ringExpiresAt
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return false
        val delayMillis = runCatching {
            Duration.between(now(), expiresAt).toMillis().coerceAtLeast(0L)
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
        val previous = synchronized(lock) {
            scheduled.put(callId, ScheduledDeadline(token, job))?.job
        }
        previous?.cancel()
        job.start()
        return true
    }

    fun cancel(callId: String) {
        synchronized(lock) { scheduled.remove(callId) }?.job?.cancel()
    }

    private data class ScheduledDeadline(val token: Any, val job: Job)
}
