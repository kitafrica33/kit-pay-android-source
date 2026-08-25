package com.kit.wallet.worker

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kit.wallet.data.messaging.ScheduledSendAlarm
import com.kit.wallet.data.messaging.ScheduledSendDispatchOutcome
import com.kit.wallet.data.messaging.ScheduledSendDispatcher
import com.kit.wallet.data.session.SessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wakes at the time the user chose and sends whatever is due.
 *
 * The worker owns no policy of its own. When to run next is always the queue's answer, republished
 * through [ScheduledSendAlarm] as each item is claimed, released or removed, so a run that achieves
 * nothing still leaves the next wake correctly armed on the item's own backoff.
 */
@HiltWorker
internal class ScheduledSendWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val sessions: SessionStore,
    private val dispatcher: ScheduledSendDispatcher,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        // Signed out, there is nothing to read: the scheduled queue lives inside the messaging
        // state that sign-out cryptographically erases. Rearming from here would be a wake that
        // can only ever find an empty namespace.
        if (sessions.current() == null) return Result.success()
        return try {
            when (dispatcher.dispatchDue()) {
                ScheduledSendDispatchOutcome.IDLE,
                ScheduledSendDispatchOutcome.COMMITTED,
                ScheduledSendDispatchOutcome.NOT_READY,
                ScheduledSendDispatchOutcome.RETRY,
                -> Result.success()
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Gateway failures are recorded by the dispatcher and return a normal outcome. An
            // exception escaping this boundary therefore came from loading or committing the
            // durable queue itself; no replacement wake is guaranteed, so WorkManager must retry.
            Result.retry()
        }
    }
}

/**
 * Timer-only half of scheduled delivery.
 *
 * The wake and the dispatcher deliberately use different unique-work names. Queue mutations made
 * by [ScheduledSendWorker] rearm the next wake; if the timer and dispatcher shared a name,
 * [ExistingWorkPolicy.REPLACE] would cancel the worker that had just claimed the message before it
 * could hand that message to the encrypted outbox.
 */
@HiltWorker
internal class ScheduledSendWakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = try {
        val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setConstraints(scheduledSendNetworkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SCHEDULED_SEND_DISPATCH_WORK_NAME,
            SCHEDULED_SEND_DISPATCH_WORK_POLICY,
            request,
        )
        Result.success()
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result.retry()
    }
}

/**
 * Arms and cancels the single wake that the scheduled queue needs.
 *
 * One unique work item, always replaced rather than appended: there is only ever one "next" time,
 * and the queue recomputes it from scratch whenever anything changes.
 */
@Singleton
internal class ScheduledSendScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val clock: Clock,
) : ScheduledSendAlarm {
    override fun rearm(nextDueAtEpochMillis: Long?) {
        if (nextDueAtEpochMillis == null) {
            workManager.cancelUniqueWork(SCHEDULED_SEND_WAKE_WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<ScheduledSendWakeWorker>()
            .setConstraints(scheduledSendNetworkConstraints())
            .setInitialDelay(
                scheduledSendWakeDelayMillis(nextDueAtEpochMillis, clock.millis()),
                TimeUnit.MILLISECONDS,
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SCHEDULED_SEND_WAKE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

@VisibleForTesting
internal const val SCHEDULED_SEND_WAKE_WORK_NAME = "kit-scheduled-send-wake"

@VisibleForTesting
internal const val SCHEDULED_SEND_DISPATCH_WORK_NAME = "kit-scheduled-send-dispatch"

/** A wake that races an active dispatch queues one successor instead of disappearing. */
@VisibleForTesting
internal val SCHEDULED_SEND_DISPATCH_WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

private fun scheduledSendNetworkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

/**
 * How long to wait before the next wake.
 *
 * Clamped at zero because an item that is already due should be sent as soon as the system will
 * run anything, and clamped from above by nothing at all — a message scheduled for next month is
 * WorkManager's to persist across the reboots in between.
 */
@VisibleForTesting
internal fun scheduledSendWakeDelayMillis(
    nextDueAtEpochMillis: Long,
    nowEpochMillis: Long,
): Long = (nextDueAtEpochMillis - nowEpochMillis).coerceAtLeast(0L)
