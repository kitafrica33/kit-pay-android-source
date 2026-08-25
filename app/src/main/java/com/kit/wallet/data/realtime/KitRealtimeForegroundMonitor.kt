package com.kit.wallet.data.realtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.kit.wallet.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether the app is on screen, behind an interface for the same reason
 * [KitRealtimeTransport] is one: the state machine's hardest cases are a
 * background transition arriving mid-handshake and a foreground bounce racing a
 * reconnect, and neither is reachable from a JVM test if the only implementation
 * needs an `Application` to register callbacks against.
 */
internal interface KitForegroundSource {
    val foregrounded: StateFlow<Boolean>

    fun start()
}

/**
 * Whether any Kit Pay activity is currently on screen.
 *
 * A socket exists **only** while the app is in the foreground, and that single
 * rule is what keeps this whole feature off Play's radar: no foreground service,
 * no new permission, no wake locks, and Doze is a non-event because there is
 * nothing running to be dozed. Background delivery is unchanged — the data-only
 * FCM wake into WorkManager, exactly as before.
 *
 * A started-activity counter rather than `ProcessLifecycleOwner`, which would mean
 * declaring `androidx.lifecycle:lifecycle-process` as a direct dependency and
 * rebinding the reviewed runtime graph for a component the platform already
 * provides. `registerActivityLifecycleCallbacks` has been on `Application` since
 * API 14.
 *
 * Backgrounding is reported after a grace period. A rotation, a permission dialog
 * or a camera hop all pass through zero started activities for a few hundred
 * milliseconds, and tearing the socket down and redialling on each of those would
 * repeat channel authorization and cause a visible presence flap for no reason.
 */
@Singleton
internal class KitRealtimeForegroundMonitor @Inject constructor(
    private val application: Application,
    @ApplicationScope private val scope: CoroutineScope,
) : KitForegroundSource {
    private val state = MutableStateFlow(false)

    override val foregrounded: StateFlow<Boolean> = state.asStateFlow()

    private var startedActivities: Int = 0
    private var graceJob: Job? = null
    private var registered: Boolean = false

    override fun start() {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            graceJob?.cancel()
            graceJob = null
            startedActivities++
            if (startedActivities == 1) state.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities != 0) return

            graceJob?.cancel()
            graceJob = scope.launch {
                delay(BACKGROUND_GRACE_MILLIS)
                // Re-checked rather than assumed: the counter can have gone back up
                // while this was waiting, and the cancellation above races it.
                if (startedActivities == 0) state.value = false
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        /** Long enough to cover a rotation or a system dialog, short enough to be honest. */
        const val BACKGROUND_GRACE_MILLIS: Long = 30_000L
    }
}
