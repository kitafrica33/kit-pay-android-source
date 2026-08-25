package com.kit.wallet.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kit.wallet.data.backup.MessageBackupException
import com.kit.wallet.data.backup.MessageBackupFrequency
import com.kit.wallet.data.backup.MessageBackupService
import com.kit.wallet.data.backup.MessageBackupTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the scheduled backup when one is due.
 *
 * Every run checks whether a backup is actually due rather than assuming the schedule fired at the
 * right moment. WorkManager batches and delays work to save battery, and a periodic job that
 * blindly uploaded on every wake would spend a user's data allowance on backups nobody asked for.
 */
@HiltWorker
class MessageBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val backups: MessageBackupService,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        if (!backups.isDue(now)) return Result.success()
        return try {
            backups.backUpNow(now)
            Result.success()
        } catch (signIn: MessageBackupException) {
            // The grant is gone or there is nothing to back up. Retrying cannot change either, and
            // the screen already shows the user what happened.
            if (signIn.requiresSignIn) Result.failure() else Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}

/**
 * Keeps WorkManager's schedule in step with what the user chose.
 *
 * The period is deliberately a fraction of the chosen frequency: WorkManager only guarantees that
 * periodic work runs *some time* within its interval, so a daily backup asked for exactly every
 * 24 hours would drift later and later. Waking more often and doing nothing costs almost nothing,
 * and it means "Daily" is honest.
 */
@Singleton
class MessageBackupScheduler @Inject constructor(
    private val workManager: WorkManager,
) : MessageBackupTrigger {
    override fun apply(frequency: MessageBackupFrequency, requiresUnmeteredNetwork: Boolean) {
        val interval = frequency.intervalMillis
        if (interval == null) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (requiresUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .build()
        val periodMinutes = (interval / CHECKS_PER_INTERVAL / 60_000L)
            .coerceAtLeast(MINIMUM_PERIOD_MINUTES)
        val periodic = PeriodicWorkRequestBuilder<MessageBackupWorker>(
            periodMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    override fun cancel() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }

    private companion object {
        const val PERIODIC_WORK = "kit-message-backup-periodic"
        const val CHECKS_PER_INTERVAL = 4
        /** WorkManager's own floor for periodic work. */
        const val MINIMUM_PERIOD_MINUTES = 15L
    }
}
