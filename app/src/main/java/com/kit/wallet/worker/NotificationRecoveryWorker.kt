package com.kit.wallet.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kit.wallet.data.notifications.NotificationInboxRecovery
import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.notifications.isTransientPushRegistrationFailure
import com.kit.wallet.data.session.SessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@HiltWorker
internal class NotificationRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val sessions: SessionStore,
    private val tokens: PushTokenCoordinator,
    private val inbox: NotificationInboxRecovery,
    private val messaging: SecureMessagingSyncScheduler,
    private val scheduler: NotificationRecoveryScheduler,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        scheduler.workerStarted()
        if (sessions.current() == null) return Result.success()
        // Independent encrypted recovery must not wait for token registration or inbox policy.
        messaging.schedule()
        var retry = false
        try {
            tokens.recoverLatestRegistration()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            retry = error.isTransientPushRegistrationFailure()
        }
        try {
            if (inbox.recover()) scheduler.schedule(continuation = true)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            retry = retry || error.isTransientPushRegistrationFailure()
        }
        return if (retry) Result.retry() else Result.success()
    }
}

/** APPEND_OR_REPLACE retains a wake received while an existing worker is finishing its pull. */
@Singleton
internal class NotificationRecoveryScheduler @Inject constructor(private val workManager: WorkManager) {
    private val pending = AtomicBoolean(false)

    fun schedule(continuation: Boolean = false) {
        if (!pending.compareAndSet(false, true)) return
        try {
            val builder = OneTimeWorkRequestBuilder<NotificationRecoveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            if (continuation) builder.setInitialDelay(30, TimeUnit.SECONDS)
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
        } catch (error: Exception) {
            pending.set(false)
            throw error
        }
    }

    fun workerStarted() { pending.set(false) }

    private companion object {
        const val WORK_NAME = "kit-notification-recovery"
    }
}
