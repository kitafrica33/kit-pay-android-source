package com.kit.wallet.data.time

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** Monotonic time since boot; unaffected by user, network, or timezone wall-clock changes. */
fun interface ElapsedRealtimeClock {
    fun millis(): Long
}

@Singleton
class AndroidElapsedRealtimeClock @Inject constructor() : ElapsedRealtimeClock {
    override fun millis(): Long = SystemClock.elapsedRealtime()
}
