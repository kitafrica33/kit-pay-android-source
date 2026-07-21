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
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.SecureMessagingAuthenticationEpochChangedException
import com.kit.wallet.data.messaging.SecureMessagingCryptographicFailureException
import com.kit.wallet.data.messaging.SecureMessagingProtocolUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.SessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class SecureMessagingSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val sessions: SessionStore,
    private val syncEngine: SecureMessagingSyncEngine,
    private val wakeCoalescer: SecureMessagingWakeCoalescer,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        // This run now covers every wake observed before it started. A wake arriving from this
        // point onward appends exactly one sequential follow-up instead of being dropped by KEEP.
        wakeCoalescer.workerStarted()
        if (sessions.current() == null || !syncEngine.isReady) return Result.success()

        return try {
            syncEngine.synchronize()
            Result.success()
        } catch (error: Throwable) {
            when (secureMessagingSyncFailureDisposition(error)) {
                SecureMessagingSyncFailureDisposition.SUCCESS -> Result.success()
                SecureMessagingSyncFailureDisposition.RETRY -> Result.retry()
                SecureMessagingSyncFailureDisposition.FAILURE -> Result.failure()
                SecureMessagingSyncFailureDisposition.RETHROW -> throw error
            }
        }
    }
}

@Singleton
class SecureMessagingSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val wakeCoalescer: SecureMessagingWakeCoalescer,
) {
    fun schedule() {
        wakeCoalescer.enqueueOnce {
            val request = OneTimeWorkRequestBuilder<SecureMessagingSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, SECURE_MESSAGING_WORK_POLICY, request)
        }
    }

    private companion object {
        const val WORK_NAME = "kit-secure-messaging-sync"
    }
}

/**
 * Coalesces any number of wakes into at most one queued WorkManager successor per process.
 * WorkManager persists the actual chain; process death can add a harmless duplicate, not lose a
 * wake. APPEND_OR_REPLACE preserves the active atomic sync and runs its successor sequentially.
 */
@Singleton
class SecureMessagingWakeCoalescer @Inject constructor() {
    private val enqueuePending = AtomicBoolean(false)

    fun enqueueOnce(enqueue: () -> Unit) {
        if (!enqueuePending.compareAndSet(false, true)) return
        try {
            enqueue()
        } catch (error: Throwable) {
            enqueuePending.set(false)
            throw error
        }
    }

    fun workerStarted() {
        enqueuePending.set(false)
    }
}

@VisibleForTesting
internal val SECURE_MESSAGING_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

@VisibleForTesting
internal enum class SecureMessagingSyncFailureDisposition {
    SUCCESS,
    RETRY,
    FAILURE,
    RETHROW,
}

/** Keeps disabled protocol/obsolete-login outcomes fail closed without crashing CoroutineWorker. */
@VisibleForTesting
internal fun secureMessagingSyncFailureDisposition(
    error: Throwable,
): SecureMessagingSyncFailureDisposition = when (error) {
    is SecureMessagingProtocolUnavailableException,
    is SecureMessagingAuthenticationEpochChangedException,
    -> SecureMessagingSyncFailureDisposition.SUCCESS
    is SecureMessagingCryptographicFailureException ->
        SecureMessagingSyncFailureDisposition.FAILURE
    is IOException,
    is SecureMessagingStateConflictException,
    -> SecureMessagingSyncFailureDisposition.RETRY
    is KitWalletApiException -> if (
        error.statusCode == null || error.statusCode == 408 || error.statusCode == 425 ||
        error.statusCode == 429 || error.statusCode >= 500
    ) {
        SecureMessagingSyncFailureDisposition.RETRY
    } else {
        SecureMessagingSyncFailureDisposition.FAILURE
    }
    else -> SecureMessagingSyncFailureDisposition.RETHROW
}

@VisibleForTesting
internal fun scheduleAuthenticatedMessagingCatchUp(
    hasSession: Boolean,
    schedule: () -> Unit,
) {
    if (hasSession) schedule()
}
