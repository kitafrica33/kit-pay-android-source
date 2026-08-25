package com.kit.wallet

import com.kit.wallet.data.realtime.KitPresenceRegistry
import com.kit.wallet.data.realtime.KitPusherCodec
import com.kit.wallet.data.realtime.KitRealtimeClock
import com.kit.wallet.data.realtime.KitRealtimeFrame
import com.kit.wallet.data.realtime.KitTypingRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security properties of the inbound path, as opposed to its parsing rules.
 *
 * The one that carries the most weight: **Reverb does not authorize client events
 * at all.** It applies no attribution and no authorization to a `client-*` frame,
 * so anyone holding the public app key and a channel name can inject one. The only
 * defence available to us is to refuse to look at them, which is why the drop
 * happens on the event name alone, before the body is parsed, and why there is no
 * outbound function anywhere that takes an event name.
 *
 * Above that sits defence in depth on typing specifically. The server attributes
 * `data.user` from the authenticated session on the originating request and never
 * from a request body, so these filters are a second line — but a second line for
 * a claim about *who a person is* is worth having.
 */
class KitRealtimeFrameCodecTest {

    @Test
    fun `every client frame is dropped whatever its channel, name or body`() {
        val hostile = listOf(
            """{"event":"client-kit.typing","channel":"presence-kit.conv.c1","data":{"v":1,"user":"u2"}}""",
            """{"event":"client-kit.sync.nudge","channel":"private-kit.user.u1","data":{"v":1}}""",
            """{"event":"client-pusher_internal:member_added","channel":"presence-kit.conv.c1",""" +
                """"data":{"user_id":"u9"}}""",
            """{"event":"client-","channel":"presence-kit.conv.c1","data":{"v":1,"user":"u2"}}""",
            // A body that would throw if it were ever parsed. It must not be reached.
            """{"event":"client-anything","channel":"presence-kit.conv.c1","data":"{ not json at all"}""",
            """{"event":"client-kit.typing.stop","data":{"v":1,"user":"u2"}}""",
        )

        hostile.forEach { frame ->
            assertNull("A client-* frame reached the state machine: $frame", KitPusherCodec.decode(frame))
        }
    }

    @Test
    fun `the client prefix is matched exactly, so a legitimate lookalike still decodes`() {
        // `client-` is a prefix rule, not a substring one: dropping anything merely
        // containing the word would be a silent, growing hole in the real protocol.
        assertTrue(
            KitPusherCodec.decode(
                """{"event":"kit.typing","channel":"presence-kit.conv.client-side","data":{"v":1,"user":"u2"}}""",
            ) is KitRealtimeFrame.Typing,
        )
    }

    @Test
    fun `no encoder emits a client frame on any code path`() {
        val everythingWeCanEmit = listOf(
            KitPusherCodec.encodePing(),
            KitPusherCodec.encodePong(),
            KitPusherCodec.encodeUnsubscribe("presence-kit.conv.c1"),
            KitPusherCodec.encodeSubscribe("private-kit.user.u1", "key:sig"),
            KitPusherCodec.encodeSubscribe("presence-kit.conv.c1", "key:sig", """{"user_id":"u1"}"""),
        )

        everythingWeCanEmit.forEach { frame ->
            assertFalse("An outbound frame named a client event: $frame", frame.contains("client-"))
            assertTrue(frame.startsWith("""{"event":"pusher:"""))
        }
    }

    @Test
    fun `a typing frame naming somebody outside the roster is dropped`() {
        val registry = registry(roster = setOf("u1", "u2"), self = "u1")

        val accepted = registry.onTypingFrame(typingFrame(user = "u9"), CONVERSATION)

        assertFalse(accepted)
        assertTrue(registry.typing.value.isEmpty())
    }

    @Test
    fun `a typing frame naming our own public id is dropped`() {
        // Our other device typing is not a peer typing. Rendering it would be
        // visibly wrong, and it is the one attribution a peer could most easily
        // guess at, since our own id is not a secret.
        val registry = registry(roster = setOf("u1", "u2"), self = "u1")

        val accepted = registry.onTypingFrame(typingFrame(user = "u1"), CONVERSATION)

        assertFalse(accepted)
        assertTrue(registry.typing.value.isEmpty())
    }

    @Test
    fun `a typing frame from a roster peer is accepted`() {
        val registry = registry(roster = setOf("u1", "u2"), self = "u1")

        assertTrue(registry.onTypingFrame(typingFrame(user = "u2"), CONVERSATION))
        assertEquals(setOf("u2"), registry.typing.value[CONVERSATION].orEmpty())
    }

    @Test
    fun `an unknown self still leaves the roster filter standing`() {
        // Before the session command lands, `selfPublicId` is null. The roster check
        // has to hold on its own, because "we do not know who we are" must never
        // widen into "anyone may claim to be typing".
        val registry = registry(roster = setOf("u2"), self = null)

        assertFalse(registry.onTypingFrame(typingFrame(user = "u9"), CONVERSATION))
        assertTrue(registry.onTypingFrame(typingFrame(user = "u2"), CONVERSATION))
    }

    @Test
    fun `a conversation with no roster at all accepts nothing`() {
        val registry = registry(roster = null, self = "u1")

        assertFalse(registry.onTypingFrame(typingFrame(user = "u2"), CONVERSATION))
        assertTrue(registry.typing.value.isEmpty())
    }

    private fun registry(roster: Set<String>?, self: String?): KitTypingRegistry {
        val presence = KitPresenceRegistry()
        presence.selfPublicId = self
        if (roster != null) presence.onRoster(CONVERSATION, roster)
        return KitTypingRegistry(presence, KitRealtimeClock { 0L })
    }

    private fun typingFrame(user: String) =
        KitRealtimeFrame.Typing(channel = CHANNEL, user = user, active = true)

    private companion object {
        const val CONVERSATION = "c1"
        const val CHANNEL = "presence-kit.conv.c1"
    }
}
