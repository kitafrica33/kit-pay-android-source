package com.kit.wallet.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kit.wallet.BuildConfig
import com.kit.wallet.R
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.ImmediateSendDispatcher
import com.kit.wallet.data.messaging.ImmediateSendDispatchOutcome
import com.kit.wallet.data.messaging.ImmediateMediaPreparationOutcome
import com.kit.wallet.data.messaging.SecureMessagingAuthenticationEpochChangedException
import com.kit.wallet.data.messaging.SecureMessagingCryptographicFailureException
import com.kit.wallet.data.messaging.SecureMessagingProtocolUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.WalletTransferChatReceiptCoordinator
import com.kit.wallet.data.messaging.WalletTransferReceiptRecoveryOutcome
import com.kit.wallet.data.messaging.PendingFinancialEventCoordinator
import com.kit.wallet.data.messaging.PendingFinancialEventRecoveryOutcome
import com.kit.wallet.data.messaging.FinancialCreationReceiptCoordinator
import com.kit.wallet.data.messaging.FinancialCreationRecoveryOutcome
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
internal class SecureMessagingSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val sessions: SessionStore,
    private val syncEngine: SecureMessagingSyncEngine,
    private val wakeCoalescer: SecureMessagingWakeCoalescer,
    private val immediateSends: ImmediateSendDispatcher,
    private val transferChatReceipts: WalletTransferChatReceiptCoordinator,
    private val pendingFinancialEvents: PendingFinancialEventCoordinator,
    private val financialCreationReceipts: FinancialCreationReceiptCoordinator,
) : CoroutineWorker(appContext, workerParameters) {
    /** Required when WorkManager implements expedited work as a foreground service before S. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                EXPEDITED_SYNC_CHANNEL_ID,
                "Secure message delivery",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background delivery of end-to-end encrypted messages"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            },
        )
        val notification = NotificationCompat.Builder(
            applicationContext,
            EXPEDITED_SYNC_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_kit_mark)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Checking for secure messages")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()
        return ForegroundInfo(EXPEDITED_SYNC_NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val urgent = inputData.getBoolean(URGENT_MESSAGING_WAKE_INPUT_KEY, false)
        // This run now covers every wake observed before it started. A wake arriving from this
        // point onward appends exactly one sequential follow-up instead of being dropped by KEEP.
        wakeCoalescer.workerStarted(urgent)
        if (sessions.current() == null || !syncEngine.isReady) return Result.success()

        return try {
            syncEngine.synchronize()
            val receiptRecovery = transferChatReceipts.recover()
            val creationRecovery = financialCreationReceipts.recover()
            val pendingRecovery = pendingFinancialEvents.recover()
            val immediateOutcome = immediateSends.dispatch()
            if (
                receiptRecovery == WalletTransferReceiptRecoveryOutcome.RETRY ||
                creationRecovery == FinancialCreationRecoveryOutcome.RETRY ||
                pendingRecovery == PendingFinancialEventRecoveryOutcome.RETRY ||
                immediateOutcome == ImmediateSendDispatchOutcome.RETRY
            ) Result.retry() else Result.success()
        } catch (error: Throwable) {
            debugSecureMessagingWorkerFailure(error)
            when (secureMessagingSyncFailureDisposition(error)) {
                SecureMessagingSyncFailureDisposition.SUCCESS -> Result.success()
                SecureMessagingSyncFailureDisposition.RETRY -> Result.retry()
                SecureMessagingSyncFailureDisposition.FAILURE -> Result.failure()
                SecureMessagingSyncFailureDisposition.RETHROW -> throw error
            }
        }
    }
}

/**
 * Device-only media preparation. Deliberately has no network constraint: an offline capture can
 * be encrypted and checkpointed while upload remains queued for [SecureMessagingSyncWorker].
 */
@HiltWorker
internal class LocalMediaPreparationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val sessions: SessionStore,
    private val immediateSends: ImmediateSendDispatcher,
    private val syncScheduler: SecureMessagingSyncScheduler,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        if (sessions.current() == null) return Result.success()
        return try {
            val outcome = immediateSends.prepareLocalMedia()
            if (outcome.progressed) {
                // The network worker may already have observed a preparation barrier and
                // completed. Whether that row became uploadable or terminally failed, give the
                // conversation's newly unblocked tail an immediate pass. This remains necessary
                // when a different attachment in the same preparation batch still needs retry.
                syncScheduler.scheduleAfterMediaPreparationProgress()
            }
            if (outcome.retryNeeded) Result.retry() else Result.success()
        } catch (error: Throwable) {
            debugSecureMessagingWorkerFailure(error)
            when (secureMessagingSyncFailureDisposition(error)) {
                SecureMessagingSyncFailureDisposition.SUCCESS -> Result.success()
                SecureMessagingSyncFailureDisposition.RETRY -> Result.retry()
                SecureMessagingSyncFailureDisposition.FAILURE -> Result.failure()
                SecureMessagingSyncFailureDisposition.RETHROW -> throw error
            }
        }
    }
}

/** Debug builds report only exception class names; no account, message, or key data is logged. */
private fun debugSecureMessagingWorkerFailure(error: Throwable) {
    if (!BuildConfig.DEBUG) return
    val causes = generateSequence(error) { current ->
        current.cause?.takeUnless { it === current }
    }
        .take(MAX_WORKER_DIAGNOSTIC_CAUSES)
        .toList()
    val classes = causes.joinToString(" <- ") { it::class.java.simpleName }
    val api = causes.filterIsInstance<KitWalletApiException>().firstOrNull()
    val apiStatus = api?.let { " status=${it.statusCode} connectivity=${it.connectivity}" }
        .orEmpty()
    Log.w(WORKER_DIAGNOSTIC_TAG, "Secure messaging sync failure: $classes$apiStatus")
}

private const val WORKER_DIAGNOSTIC_TAG = "KitMessagingWorker"
private const val MAX_WORKER_DIAGNOSTIC_CAUSES = 8
private const val EXPEDITED_SYNC_CHANNEL_ID = "kit-secure-message-delivery"
private const val EXPEDITED_SYNC_NOTIFICATION_ID = 4_104

@Singleton
class SecureMessagingSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val wakeCoalescer: SecureMessagingWakeCoalescer,
) {
    fun schedule() {
        enqueueLocalMediaPreparation()
        enqueueSync(initialDelayMillis = 0L, urgent = false)
    }

    /**
     * Starts an authenticated message wake with Android's expedited-job allowance.
     *
     * A data-only high-priority FCM gives the process only a short execution window. Enqueuing an
     * ordinary constrained job here can leave the ciphertext untouched until the person opens the
     * app, which in turn delays the only notification whose sender/content the client can trust.
     * The urgent lane is deliberately separate from maintenance work: an already queued ordinary
     * sync must not prevent a new-message wake from asking the OS for immediate execution. The
     * sync engine and outbox dispatcher retain their own serialization, so two lanes can never
     * race ratchet or durable queue state.
     */
    fun scheduleUrgentMessageWake() {
        enqueueLocalMediaPreparation()
        enqueueSync(initialDelayMillis = 0L, urgent = true)
    }

    fun scheduleHistoryContinuation(delayMillis: Long) {
        require(delayMillis >= 0L)
        enqueueSync(initialDelayMillis = delayMillis, urgent = false)
    }

    internal fun scheduleAfterMediaPreparationProgress() {
        enqueueSync(initialDelayMillis = 0L, urgent = false)
    }

    private fun enqueueLocalMediaPreparation() {
        val request = OneTimeWorkRequestBuilder<LocalMediaPreparationWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Every enqueue joins the durable chain. The first worker drains the whole current
        // snapshot; a successor covers an attachment accepted after that snapshot was taken.
        workManager.enqueueUniqueWork(
            LOCAL_MEDIA_PREPARATION_WORK_NAME,
            LOCAL_MEDIA_PREPARATION_WORK_POLICY,
            request,
        )
    }

    private fun enqueueSync(initialDelayMillis: Long, urgent: Boolean) {
        wakeCoalescer.enqueueOnce(urgent) {
            val builder = OneTimeWorkRequestBuilder<SecureMessagingSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(URGENT_MESSAGING_WAKE_INPUT_KEY to urgent))
            if (initialDelayMillis > 0L) {
                builder.setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            }
            if (urgent) {
                check(initialDelayMillis == 0L) { "An expedited message wake cannot be delayed" }
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = builder.build()
            workManager.enqueueUniqueWork(
                if (urgent) URGENT_WORK_NAME else WORK_NAME,
                SECURE_MESSAGING_WORK_POLICY,
                request,
            )
        }
    }

    private companion object {
        const val WORK_NAME = "kit-secure-messaging-sync"
        const val URGENT_WORK_NAME = "kit-secure-messaging-urgent-sync"
        const val LOCAL_MEDIA_PREPARATION_WORK_NAME = "kit-local-media-preparation"
    }
}

/**
 * Coalesces any number of wakes into at most one queued WorkManager successor per process.
 * WorkManager persists the actual chain; process death can add a harmless duplicate, not lose a
 * wake. APPEND_OR_REPLACE preserves the active atomic sync and runs its successor sequentially.
 */
@Singleton
class SecureMessagingWakeCoalescer @Inject constructor() {
    private val ordinaryEnqueuePending = AtomicBoolean(false)
    private val urgentEnqueuePending = AtomicBoolean(false)

    fun enqueueOnce(urgent: Boolean = false, enqueue: () -> Unit) {
        val lane = if (urgent) urgentEnqueuePending else ordinaryEnqueuePending
        if (!lane.compareAndSet(false, true)) return
        try {
            enqueue()
        } catch (error: Throwable) {
            lane.set(false)
            throw error
        }
    }

    fun workerStarted(urgent: Boolean = false) {
        (if (urgent) urgentEnqueuePending else ordinaryEnqueuePending).set(false)
    }
}

@VisibleForTesting
internal const val URGENT_MESSAGING_WAKE_INPUT_KEY = "kit.messaging.urgent_wake"

@VisibleForTesting
internal val SECURE_MESSAGING_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

@VisibleForTesting
internal val LOCAL_MEDIA_PREPARATION_WORK_POLICY: ExistingWorkPolicy =
    ExistingWorkPolicy.APPEND_OR_REPLACE

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
