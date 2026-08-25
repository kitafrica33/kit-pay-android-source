package com.kit.wallet

import com.kit.wallet.data.realtime.KitRealtimeBackoff
import com.kit.wallet.data.realtime.KitRealtimeClock
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect ladder.
 *
 * Full jitter — a delay drawn from the *whole* interval, not the top of it —
 * because the failure this is most likely to meet is correlated: a carrier blip or
 * a socket-server restart drops every device at once, and only the full interval
 * de-synchronises a herd properly.
 *
 * The two rules that make the ladder honest are the two a naive implementation
 * gets wrong, and each has a test below saying why: resetting on a connection that
 * did not hold turns a flapping network into an unbounded retry loop at the base
 * delay, and charging an attempt for a lost network makes a tunnel cost a minute
 * of waiting on the far side.
 */
class KitRealtimeBackoffTest {

    @Test
    fun `each attempt draws from the whole doubling interval, capped at sixty seconds`() {
        // The ceiling is what the jitter is drawn from, so asking for the top of the
        // interval is how the ladder's shape is read out.
        val ceilings = mutableListOf<Long>()
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { 0L }, jitter = { bound ->
            ceilings += bound
            bound
        })

        repeat(10) { backoff.nextDelayMillis() }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L),
            ceilings,
        )
    }

    @Test
    fun `a real draw stays inside its interval at every rung`() {
        val random = Random(20260824)
        val backoff = KitRealtimeBackoff(
            clock = KitRealtimeClock { 0L },
            jitter = { bound -> if (bound <= 1L) 0L else random.nextLong(bound) },
        )

        val ceilings = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        ceilings.forEach { ceiling ->
            val delay = backoff.nextDelayMillis()
            assertTrue("$delay was not in [0, $ceiling)", delay in 0 until ceiling)
        }
    }

    @Test
    fun `a minimum floor raises a delay without changing the ladder`() {
        // How a `pusher:error` in the 4100-4199 band gets its mandated one-second
        // floor: the same ladder, clamped from below, rather than a second ladder.
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { 0L }, jitter = { 0L })

        assertEquals(1_000L, backoff.nextDelayMillis(minimumMillis = 1_000L))
        assertEquals(1, backoff.attempt)
        assertEquals(5_000L, backoff.nextDelayMillis(minimumMillis = 5_000L))
        assertEquals(2, backoff.attempt)
    }

    @Test
    fun `the counter resets only after a full minute of continuous Live`() {
        var now = 0L
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { now }, jitter = { it })

        repeat(4) { backoff.nextDelayMillis() }
        assertEquals(4, backoff.attempt)

        // A connection that establishes and dies a second later is a failure. Reset
        // on it and a flapping network retries forever at the base delay.
        backoff.onLive()
        now += 59_999L
        backoff.onLeftLive()
        assertEquals("A short-lived connection must not clear the ladder", 4, backoff.attempt)

        backoff.onLive()
        now += 60_000L
        backoff.onLeftLive()
        assertEquals(0, backoff.attempt)
    }

    @Test
    fun `leaving Live without having entered it does nothing`() {
        var now = 0L
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { now }, jitter = { it })

        repeat(3) { backoff.nextDelayMillis() }
        now += 10 * 60_000L
        backoff.onLeftLive()

        assertEquals(3, backoff.attempt)
    }

    @Test
    fun `a second onLeftLive cannot reset off the first stay`() {
        var now = 0L
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { now }, jitter = { it })

        backoff.onLive()
        now += 60_000L
        backoff.onLeftLive()

        repeat(3) { backoff.nextDelayMillis() }
        now += 60_000L
        backoff.onLeftLive()

        assertEquals("The stay was already spent", 3, backoff.attempt)
    }

    @Test
    fun `losing the network delays without spending an attempt`() {
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { 0L }, jitter = { it })

        backoff.nextDelayMillis()
        backoff.nextDelayMillis()
        assertEquals(2, backoff.attempt)

        // There is no server to fail against and nobody to be polite to, so a walk
        // through a tunnel must not leave the app waiting a minute once signal returns.
        assertEquals(4_000L, backoff.delayWithoutSpendingAnAttempt())
        assertEquals(4_000L, backoff.delayWithoutSpendingAnAttempt())
        assertEquals(2, backoff.attempt)
    }

    @Test
    fun `a network delay still honours a floor`() {
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { 0L }, jitter = { 0L })

        assertEquals(2_500L, backoff.delayWithoutSpendingAnAttempt(minimumMillis = 2_500L))
        assertEquals(0, backoff.attempt)
    }

    @Test
    fun `reset clears the ladder and any Live stay in progress`() {
        var now = 0L
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { now }, jitter = { it })

        repeat(5) { backoff.nextDelayMillis() }
        backoff.onLive()
        backoff.reset()
        assertEquals(0, backoff.attempt)

        // The stay was discarded along with the counter, so the minute that follows
        // is not a minute this ladder ever saw.
        now += 60_000L
        backoff.onLeftLive()
        backoff.nextDelayMillis()
        assertEquals(1, backoff.attempt)
    }

    @Test
    fun `the attempt counter is clamped so the shift cannot overflow`() {
        val backoff = KitRealtimeBackoff(clock = KitRealtimeClock { 0L }, jitter = { it })

        repeat(200) { backoff.nextDelayMillis() }

        assertEquals(16, backoff.attempt)
        assertEquals(60_000L, backoff.nextDelayMillis())
    }
}
