package com.kit.wallet.data.realtime

import kotlin.random.Random

/**
 * The reconnect ladder: full jitter, base 1 s, cap 60 s.
 *
 * Full jitter rather than exponential-with-jitter because the failure we most
 * expect is correlated — a carrier blip or a socket-server restart drops every
 * device at once — and only a delay drawn from the whole interval de-synchronises
 * a herd properly.
 *
 * Two rules keep the ladder honest and both are the whole reason this is a class
 * rather than a formula:
 *
 * - The attempt counter resets **only** after [STABLE_LIVE_MILLIS] of continuous
 *   `Live`. A connection that establishes and dies a second later is a failure,
 *   and resetting on it would turn a flapping network into an unbounded retry loop
 *   at the base delay.
 * - Losing the network does **not** spend an attempt. There is nothing to retry
 *   against and no server to be polite to, so a tunnel walk must not leave the app
 *   waiting a minute once the signal returns.
 *
 * Pure Kotlin: the clock and the randomness are both injected, so the bounds are
 * pinned by an ordinary unit test.
 */
internal class KitRealtimeBackoff(
    private val clock: KitRealtimeClock,
    private val jitter: (Long) -> Long = { bound -> if (bound <= 1L) 0L else Random.nextLong(bound) },
) {
    private var attempts: Int = 0
    private var liveSinceMillis: Long? = null

    val attempt: Int get() = attempts

    /**
     * Spends an attempt and returns how long to wait, never less than
     * [minimumMillis] — which is how a `pusher:error` in the 4100-4199 band gets
     * its mandated one-second floor without a second ladder.
     */
    fun nextDelayMillis(minimumMillis: Long = 0L): Long {
        val ceiling = ceilingFor(attempts)
        attempts = (attempts + 1).coerceAtMost(MAX_ATTEMPTS)
        return maxOf(jitter(ceiling), minimumMillis)
    }

    /**
     * A delay for a cause that is not the server's fault — losing the default
     * network — leaving the counter where it was.
     */
    fun delayWithoutSpendingAnAttempt(minimumMillis: Long = 0L): Long =
        maxOf(jitter(ceilingFor(attempts)), minimumMillis)

    /** Entering `Live`. Starts the clock that can later clear the counter. */
    fun onLive() {
        liveSinceMillis = clock.elapsedMillis()
    }

    /**
     * Leaving `Live` for any reason. Clears the counter only if the connection had
     * actually held up; a short-lived one leaves the ladder exactly where it was.
     * Returns whether this stay crossed the stability threshold.
     */
    fun onLeftLive(): Boolean {
        val since = liveSinceMillis ?: return false
        liveSinceMillis = null
        val wasStable = clock.elapsedMillis() - since >= STABLE_LIVE_MILLIS
        if (wasStable) attempts = 0
        return wasStable
    }

    /**
     * Regaining a network, or signing in again. Both are new information that the
     * previous failures are no longer predictive, so the ladder starts over.
     */
    fun reset() {
        attempts = 0
        liveSinceMillis = null
    }

    private fun ceilingFor(attempt: Int): Long =
        (BASE_DELAY_MILLIS shl attempt.coerceIn(0, MAX_ATTEMPTS)).coerceAtMost(MAX_DELAY_MILLIS)

    companion object {
        const val BASE_DELAY_MILLIS: Long = 1_000L
        const val MAX_DELAY_MILLIS: Long = 60_000L
        const val STABLE_LIVE_MILLIS: Long = 60_000L

        /** `1 s shl 16` already exceeds the cap; the clamp only prevents overflow. */
        private const val MAX_ATTEMPTS: Int = 16
    }
}
