package com.kit.wallet.data.realtime

/**
 * Monotonic elapsed milliseconds.
 *
 * Every deadline in this package — backoff, pong, typing expiry, presence
 * hold-over — is a duration, never a wall-clock instant, so none of them can be
 * skipped or stalled by an NTP correction or a user changing the device clock.
 * Injected rather than called statically so the whole package stays JVM-testable.
 */
internal fun interface KitRealtimeClock {
    fun elapsedMillis(): Long
}
