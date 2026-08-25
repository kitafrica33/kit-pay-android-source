package com.kit.wallet

import com.kit.wallet.data.realtime.KitPresenceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presence, which is defined as membership of the conversation's channel and
 * nothing else — not "has a socket", not "app is open", not a `last_seen_at`
 * column.
 *
 * Two consequences the tests below hold to. Multi-device resolution is a union
 * keyed by `public_id`, so three devices are one entry and a peer therefore never
 * learns how many devices you have. And a drop we did not choose clears everything
 * at once, because an unclean drop is exactly the case where the server's own
 * `member_removed` can lag by up to a minute — a frozen dot would be wrong for
 * precisely as long as it was most visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KitPresenceRegistryTest {

    @Test
    fun `a roster seeds the conversation and a peer on three devices is one entry`() {
        val registry = KitPresenceRegistry()

        // `member_added` fires on a user's *first* connection and `member_removed` on
        // their *last*, so this is an add and a remove, never a counter. That is what
        // makes the union free rather than something we have to maintain.
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.onMemberAdded(CONVERSATION, "u2")
        registry.onMemberAdded(CONVERSATION, "u2")

        assertEquals(setOf("u1", "u2"), registry.membersOf(CONVERSATION))

        registry.onMemberRemoved(CONVERSATION, "u2")
        assertEquals(setOf("u1"), registry.membersOf(CONVERSATION))
    }

    @Test
    fun `membership of a conversation we hold no roster for is ignored`() {
        // Without a roster there is no baseline to add to, and inventing one from a
        // membership frame would show a peer as online in a conversation the server
        // never confirmed we are watching.
        val registry = KitPresenceRegistry()

        registry.onMemberAdded(CONVERSATION, "u2")

        assertTrue(registry.presence.value.isEmpty())
        assertEquals(emptySet<String>(), registry.membersOf(CONVERSATION))
    }

    @Test
    fun `peersOnlineIn excludes us and collapses to one boolean`() = runTest {
        val registry = KitPresenceRegistry()
        registry.selfPublicId = "u1"

        val seen = mutableListOf<Boolean>()
        val collector = launch { registry.peersOnlineIn(CONVERSATION).toList(seen) }
        advanceUntilIdle()

        // Only us: not online. Rendering our own dot would be a peer's status we made up.
        registry.onRoster(CONVERSATION, setOf("u1"))
        advanceUntilIdle()

        registry.onMemberAdded(CONVERSATION, "u2")
        advanceUntilIdle()

        // A second peer is still just "someone is here" — the count is never exposed.
        registry.onMemberAdded(CONVERSATION, "u3")
        advanceUntilIdle()

        registry.onMemberRemoved(CONVERSATION, "u2")
        registry.onMemberRemoved(CONVERSATION, "u3")
        advanceUntilIdle()

        collector.cancel()
        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun `presence in one conversation says nothing about another`() = runTest {
        val registry = KitPresenceRegistry()
        registry.selfPublicId = "u1"

        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.onRoster(OTHER_CONVERSATION, setOf("u1"))

        assertTrue(registry.peersOnlineIn(CONVERSATION).first())
        assertFalse(registry.peersOnlineIn(OTHER_CONVERSATION).first())
    }

    @Test
    fun `a clean reconnect holds the rosters over for five seconds`() = runTest {
        val registry = KitPresenceRegistry()
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))

        // Our own bounded-lifetime reconnect. Nobody asked for it, so it must not
        // blink every peer offline and back again.
        registry.beginHoldOver()
        assertEquals(setOf("u1", "u2"), registry.membersOf(CONVERSATION))

        registry.expireHoldOver()
        assertTrue("The hold-over outlived its window", registry.presence.value.isEmpty())
    }

    @Test
    fun `a fresh roster supersedes the hold-over`() = runTest {
        val registry = KitPresenceRegistry()
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.beginHoldOver()

        registry.onRoster(CONVERSATION, setOf("u1", "u3"))
        // The hold-over is over, so its expiry — which can still be in flight on the
        // tick — must no longer be able to wipe a roster the server just confirmed.
        registry.expireHoldOver()

        assertEquals(setOf("u1", "u3"), registry.membersOf(CONVERSATION))
    }

    @Test
    fun `a hold-over over nothing is not a hold-over`() = runTest {
        val registry = KitPresenceRegistry()

        registry.beginHoldOver()
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.expireHoldOver()

        assertEquals(setOf("u1", "u2"), registry.membersOf(CONVERSATION))
    }

    @Test
    fun `a hard drop clears every conversation immediately`() = runTest {
        val registry = KitPresenceRegistry()
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.onRoster(OTHER_CONVERSATION, setOf("u1", "u3"))

        registry.onHardDrop()

        assertTrue(registry.presence.value.isEmpty())
    }

    @Test
    fun `a hard drop during a hold-over ends it rather than being absorbed by it`() = runTest {
        val registry = KitPresenceRegistry()
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.beginHoldOver()

        registry.onHardDrop()
        assertTrue(registry.presence.value.isEmpty())

        // And the ended hold-over cannot then wipe a roster from the reconnect.
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.expireHoldOver()
        assertEquals(setOf("u1", "u2"), registry.membersOf(CONVERSATION))
    }

    @Test
    fun `forgetting one conversation leaves the others alone`() = runTest {
        val registry = KitPresenceRegistry()
        registry.onRoster(CONVERSATION, setOf("u1", "u2"))
        registry.onRoster(OTHER_CONVERSATION, setOf("u1", "u3"))

        registry.forget(CONVERSATION)

        assertEquals(emptySet<String>(), registry.membersOf(CONVERSATION))
        assertEquals(setOf("u1", "u3"), registry.membersOf(OTHER_CONVERSATION))
    }

    private companion object {
        const val CONVERSATION = "c1"
        const val OTHER_CONVERSATION = "c2"
    }
}
