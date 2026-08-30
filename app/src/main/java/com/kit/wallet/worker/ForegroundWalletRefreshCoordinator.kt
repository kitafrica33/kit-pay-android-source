package com.kit.wallet.worker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.time.ElapsedRealtimeClock
import com.kit.wallet.di.ApplicationScope
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reconciles the offline wallet projection whenever the application genuinely returns to the
 * foreground. The UI continues to render Room immediately; this is the background authority pass
 * that replaces an obsolete projection once the server can be reached.
 *
 * [WalletForegroundMonitor] is process-wide and ignores configuration replacement, so this does
 * not inherit Compose/activity resume storms. `collectLatest` gives the coordinator one refresh
 * flight: an account replacement or a real background transition cancels the obsolete flight
 * before another can begin.
 */
@Singleton
internal class ForegroundWalletRefreshCoordinator @Inject constructor(
    private val foregroundMonitor: WalletForegroundMonitor,
    private val sessions: SessionStore,
    private val walletSync: WalletSyncRepository,
    private val elapsedRealtimeClock: ElapsedRealtimeClock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        foregroundMonitor.start()
        scope.launch {
            observeForegroundWalletRefreshes(
                foregrounded = foregroundMonitor.foregrounded,
                sessionFences = sessions.session
                    .map { it?.fence() }
                    .distinctUntilChanged(),
                currentSession = { sessions.current()?.fence() },
                nowMillis = elapsedRealtimeClock::millis,
                refresh = { walletSync.refresh() },
                waitBeforeRetry = { delay(it) },
            )
        }
    }
}

/** Process visibility without a screen/Compose observer and without treating rotation as exit. */
@Singleton
internal class WalletForegroundMonitor @Inject constructor(
    private val application: Application,
) {
    private val state = MutableStateFlow(false)
    private val registered = AtomicBoolean(false)
    private var startedActivities = 0

    val foregrounded: StateFlow<Boolean> = state.asStateFlow()

    fun start() {
        if (!registered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) state.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0 && !activity.isChangingConfigurations) {
                state.value = false
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

@Suppress("LongParameterList")
internal suspend fun observeForegroundWalletRefreshes(
    foregrounded: Flow<Boolean>,
    sessionFences: Flow<SessionFence?>,
    currentSession: () -> SessionFence?,
    nowMillis: () -> Long,
    minimumRefreshIntervalMillis: Long = FOREGROUND_WALLET_REFRESH_MIN_INTERVAL_MILLIS,
    refresh: suspend () -> Unit,
    waitBeforeRetry: suspend (Long) -> Unit,
) {
    require(minimumRefreshIntervalMillis >= 0L)
    var lastSuccessfulFence: SessionFence? = null
    var lastSuccessfulAtMillis: Long? = null

    combine(
        foregrounded.distinctUntilChanged(),
        sessionFences.distinctUntilChanged(),
    ) { isForegrounded, fence -> isForegrounded to fence }
        .distinctUntilChanged()
        .collectLatest { (isForegrounded, expectedSession) ->
            if (!isForegrounded || expectedSession == null) return@collectLatest

            val now = nowMillis()
            val elapsedSinceSuccess = lastSuccessfulAtMillis?.let { now - it }
            if (
                lastSuccessfulFence == expectedSession &&
                elapsedSinceSuccess != null &&
                elapsedSinceSuccess >= 0L &&
                elapsedSinceSuccess < minimumRefreshIntervalMillis
            ) {
                return@collectLatest
            }

            val refreshed = refreshForegroundWalletWithRetries(
                expectedSession = expectedSession,
                currentSession = currentSession,
                refresh = refresh,
                waitBeforeRetry = waitBeforeRetry,
            )
            if (refreshed && currentSession() == expectedSession) {
                lastSuccessfulFence = expectedSession
                lastSuccessfulAtMillis = nowMillis()
            }
        }
}

internal suspend fun refreshForegroundWalletWithRetries(
    expectedSession: SessionFence,
    currentSession: () -> SessionFence?,
    attempts: Int = FOREGROUND_WALLET_REFRESH_ATTEMPTS,
    refresh: suspend () -> Unit,
    waitBeforeRetry: suspend (Long) -> Unit,
): Boolean {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        if (currentSession() != expectedSession) return false
        try {
            refresh()
            return currentSession() == expectedSession
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (currentSession() != expectedSession || !error.isRetryableWalletRefreshFailure()) {
                return false
            }
            if (attempt < attempts - 1) {
                waitBeforeRetry(FOREGROUND_WALLET_REFRESH_RETRY_MILLIS * (attempt + 1L))
            }
        }
    }
    return false
}

private fun Throwable.isRetryableWalletRefreshFailure(): Boolean = when (this) {
    is IOException -> true
    is KitWalletApiException ->
        statusCode == null || statusCode == 408 || statusCode == 425 || statusCode == 429 ||
            statusCode >= 500
    else -> false
}

internal const val FOREGROUND_WALLET_REFRESH_MIN_INTERVAL_MILLIS = 10_000L
private const val FOREGROUND_WALLET_REFRESH_ATTEMPTS = 3
private const val FOREGROUND_WALLET_REFRESH_RETRY_MILLIS = 1_000L
