package com.kit.wallet.data.realtime

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only route from a socket frame into the sync engine, and the gate on it.
 *
 * `KitForegroundSyncTrigger` is exactly what its name says — a *foreground* one.
 * The background path is the data-only FCM wake into WorkManager, and it has its
 * own coalescer, its own constraints and its own retry policy. Letting a nudge
 * start a sync outside `Live` would add a second, ungoverned background sync path
 * that nothing in the app's power or network budget accounts for.
 *
 * So the sink is closed by default and opened only while the socket is `Live`. A
 * nudge that arrives outside that window is dropped and nothing is lost: the
 * cursor cannot regress, and every transition back into `Live` requests a sync
 * unconditionally.
 */
@Singleton
internal class KitRealtimeNudgeSink @Inject constructor(
    private val trigger: KitForegroundSyncTrigger,
) {
    @Volatile
    private var accepting: Boolean = false

    fun open() {
        accepting = true
    }

    fun close() {
        accepting = false
    }

    /** Returns whether the nudge was acted on, which is what the tests assert. */
    fun onNudge(): Boolean {
        if (!accepting) return false
        trigger.request()
        return true
    }
}
