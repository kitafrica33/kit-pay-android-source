package com.kit.wallet

import com.kit.wallet.data.realtime.KitPresenceRegistry
import com.kit.wallet.data.realtime.KitRealtimeClock
import com.kit.wallet.data.realtime.KitRealtimeFrame
import com.kit.wallet.data.realtime.KitTypingRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inbound half of typing.
 *
 * The expiry is the whole reason a bubble cannot get stuck: a peer whose process is
 * killed mid-word sends no `stop`, and the bubble has to disappear anyway. Six
 * seconds is 1.5× the sender's four-second throttle, so one lost request cannot
 * make it flicker.
 *
 * None of this is ever persisted. A typing signal is not content — it has no id, no
 * order and no durability — and must never be replayable into a conversation or
 * resumable from a cursor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KitTypingRegistryTest {

    @Test
    fun `a bubble expires six seconds after the last accepted frame`() {
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        assertTrue(registry.onTypingFrame(start("u2"), CONVERSATION))
        assertEquals(setOf("u2"), registry.typing.value[CONVERSATION].orEmpty())

        clock.now = 5_999L
        registry.prune()
        assertEquals("The bubble expired early", setOf("u2"), registry.typing.value[CONVERSATION].orEmpty())

        clock.now = 6_000L
        registry.prune()
        assertTrue("A peer killed mid-word must not leave a bubble", registry.typing.value.isEmpty())
    }

    @Test
    fun `the flood guard drops extra frames inside two seconds`() {
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        assertTrue(registry.onTypingFrame(start("u2"), CONVERSATION))

        // A peer ignoring its own throttle — or a compromised one trying to keep the
        // connection busy — must not get a recomposition per frame.
        clock.now = 1_999L
        assertFalse(registry.onTypingFrame(start("u2"), CONVERSATION))

        clock.now = 2_000L
        assertTrue(registry.onTypingFrame(start("u2"), CONVERSATION))
    }

    @Test
    fun `an accepted refresh pushes the expiry out rather than resetting it`() {
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        registry.onTypingFrame(start("u2"), CONVERSATION)
        clock.now = 4_000L
        assertTrue(registry.onTypingFrame(start("u2"), CONVERSATION))

        // Would have expired at 6 000 on the first frame; the refresh moves it to 10 000.
        clock.now = 6_000L
        registry.prune()
        assertEquals(setOf("u2"), registry.typing.value[CONVERSATION].orEmpty())

        clock.now = 10_000L
        registry.prune()
        assertTrue(registry.typing.value.isEmpty())
    }

    @Test
    fun `a dropped frame inside the throttle window cannot flicker a bubble`() {
        // The 1.5x margin, stated as the scenario it exists for: the peer sends at
        // 0 and 4 000, the 4 000 is lost, the next lands at 8 000. Without the margin
        // the bubble would blink out at 6 000.
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        registry.onTypingFrame(start("u2"), CONVERSATION)
        clock.now = 4_000L
        registry.onTypingFrame(start("u2"), CONVERSATION)

        clock.now = 8_000L
        registry.prune()
        assertEquals(setOf("u2"), registry.typing.value[CONVERSATION].orEmpty())
    }

    @Test
    fun `a stop clears the bubble at once and is idempotent`() {
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        registry.onTypingFrame(start("u2"), CONVERSATION)
        assertTrue(registry.onTypingFrame(stop("u2"), CONVERSATION))
        assertTrue(registry.typing.value.isEmpty())

        assertFalse("A second stop has nothing to clear", registry.onTypingFrame(stop("u2"), CONVERSATION))
    }

    @Test
    fun `a stop is not subject to the flood guard`() {
        // The guard exists to cap recompositions from repeated *starts*. Applying it
        // to a stop would leave a bubble up for the rest of the expiry window after
        // the peer has already sent their message.
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        registry.onTypingFrame(start("u2"), CONVERSATION)
        clock.now = 100L

        assertTrue(registry.onTypingFrame(stop("u2"), CONVERSATION))
        assertTrue(registry.typing.value.isEmpty())
    }

    @Test
    fun `several peers type at once and expire independently`() {
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2", "u3"), self = "u1")

        registry.onTypingFrame(start("u2"), CONVERSATION)
        clock.now = 3_000L
        registry.onTypingFrame(start("u3"), CONVERSATION)

        assertEquals(setOf("u2", "u3"), registry.typing.value[CONVERSATION].orEmpty())

        clock.now = 6_000L
        registry.prune()
        assertEquals(setOf("u3"), registry.typing.value[CONVERSATION].orEmpty())

        clock.now = 9_000L
        registry.prune()
        assertTrue(registry.typing.value.isEmpty())
    }

    @Test
    fun `typing in one conversation does not surface in another`() {
        val clock = MutableClock()
        val presence = KitPresenceRegistry()
        presence.selfPublicId = "u1"
        presence.onRoster(CONVERSATION, setOf("u1", "u2"))
        presence.onRoster(OTHER_CONVERSATION, setOf("u1", "u2"))
        val registry = KitTypingRegistry(presence, clock)

        registry.onTypingFrame(start("u2"), CONVERSATION)

        assertEquals(setOf("u2"), registry.typing.value[CONVERSATION].orEmpty())
        assertEquals(emptySet<String>(), registry.typing.value[OTHER_CONVERSATION].orEmpty())
    }

    @Test
    fun `forget drops one conversation and clear drops them all`() {
        val clock = MutableClock()
        val presence = KitPresenceRegistry()
        presence.selfPublicId = "u1"
        presence.onRoster(CONVERSATION, setOf("u1", "u2"))
        presence.onRoster(OTHER_CONVERSATION, setOf("u1", "u3"))
        val registry = KitTypingRegistry(presence, clock)

        registry.onTypingFrame(start("u2"), CONVERSATION)
        registry.onTypingFrame(start("u3"), OTHER_CONVERSATION)

        registry.forget(CONVERSATION)
        assertEquals(emptySet<String>(), registry.typing.value[CONVERSATION].orEmpty())
        assertEquals(setOf("u3"), registry.typing.value[OTHER_CONVERSATION].orEmpty())

        // The socket went away: every bubble was predicated on a live channel we no
        // longer have, so none of them can be trusted to still be true.
        registry.clear()
        assertTrue(registry.typing.value.isEmpty())
    }

    @Test
    fun `peersTypingIn emits only when the set actually changes`() = runTest {
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        val seen = mutableListOf<Set<String>>()
        val collector = launch { registry.peersTypingIn(CONVERSATION).toList(seen) }
        advanceUntilIdle()

        registry.onTypingFrame(start("u2"), CONVERSATION)
        advanceUntilIdle()

        // Accepted, and the same set: a refresh must not recompose the bubble.
        clock.now = 2_000L
        registry.onTypingFrame(start("u2"), CONVERSATION)
        advanceUntilIdle()

        clock.now = 8_001L
        registry.prune()
        advanceUntilIdle()

        collector.cancel()
        assertEquals(listOf(emptySet(), setOf("u2"), emptySet<String>()), seen)
    }

    @Test
    fun `prune with nothing to expire publishes nothing`() = runTest {
        val clock = MutableClock()
        val registry = registryFor(clock, roster = setOf("u1", "u2"), self = "u1")

        val seen = mutableListOf<Map<String, Set<String>>>()
        val collector = launch { registry.typing.toList(seen) }
        advanceUntilIdle()

        // The one-second tick runs for as long as any conversation is subscribed, so
        // a prune that finds nothing has to be free.
        repeat(10) { registry.prune() }
        advanceUntilIdle()

        collector.cancel()
        assertEquals(1, seen.size)
    }

    private fun registryFor(
        clock: KitRealtimeClock,
        roster: Set<String>,
        self: String?,
    ): KitTypingRegistry {
        val presence = KitPresenceRegistry()
        presence.selfPublicId = self
        presence.onRoster(CONVERSATION, roster)
        return KitTypingRegistry(presence, clock)
    }

    private fun start(user: String) = KitRealtimeFrame.Typing(CHANNEL, user, active = true)

    private fun stop(user: String) = KitRealtimeFrame.Typing(CHANNEL, user, active = false)

    private class MutableClock(var now: Long = 0L) : KitRealtimeClock {
        override fun elapsedMillis(): Long = now
    }

    private companion object {
        const val CONVERSATION = "c1"
        const val OTHER_CONVERSATION = "c2"
        const val CHANNEL = "presence-kit.conv.c1"
    }
}
