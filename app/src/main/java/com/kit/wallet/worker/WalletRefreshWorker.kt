package com.kit.wallet.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.WalletRefreshTrigger
import com.kit.wallet.data.session.SessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class WalletRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val sessions: SessionStore,
    private val walletSync: WalletSyncRepository,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        if (sessions.current() == null) return Result.success()
        return try {
            walletSync.refresh()
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (error: KitWalletApiException) {
            if (error.statusCode == null || error.statusCode >= 500) Result.retry()
            else Result.failure()
        }
    }
}

@Singleton
class WalletRefreshScheduler @Inject constructor(
    private val workManager: WorkManager,
) : WalletRefreshTrigger {
    fun schedule() {
        refreshNow()
        schedulePeriodic()
    }

    override fun refreshNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val immediate = OneTimeWorkRequestBuilder<WalletRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            immediate,
        )
    }

    private fun schedulePeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<WalletRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    private companion object {
        const val IMMEDIATE_WORK = "kit-wallet-refresh-now"
        const val PERIODIC_WORK = "kit-wallet-refresh-periodic"
    }
}
