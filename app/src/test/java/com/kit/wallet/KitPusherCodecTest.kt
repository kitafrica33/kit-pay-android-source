package com.kit.wallet

import com.kit.wallet.data.realtime.KitPusherCodec
import com.kit.wallet.data.realtime.KitRealtimeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format, pinned in both directions.
 *
 * The case that motivates most of this file is the `data` asymmetry: Pusher's own protocol frames
 * carry `data` as a JSON-encoded *string*, application frames carry it as an object, and Reverb
 * relays whatever the signed HTTP events API was handed — which the PHP client encodes to a string.
 * A `kit.*` frame can therefore legitimately arrive in either shape, and a codec that assumed one
 * would silently drop half of production.
 */
class KitPusherCodecTest {

    @Test
    fun `connection established parses a double-encoded data string`() {
        val frame = KitPusherCodec.decode(
            """{"event":"pusher:connection_established",""" +
                """"data":"{\"socket_id\":\"123.456\",\"activity_timeout\":30}"}""",
        )

        assertEquals(KitRealtimeFrame.Established("123.456", 30), frame)
    }

    @Test
    fun `connection established also parses an object data member`() {
        val frame = KitPusherCodec.decode(
            """{"event":"pusher:connection_established",""" +
                """"data":{"socket_id":"123.456","activity_timeout":30}}""",
        )

        assertEquals(KitRealtimeFrame.Established("123.456", 30), frame)
    }

    @Test
    fun `an activity timeout outside the accepted band is clamped rather than trusted`() {
        val tiny = KitPusherCodec.decode(
            """{"event":"pusher:connection_established","data":{"socket_id":"a","activity_timeout":1}}""",
        )
        val vast = KitPusherCodec.decode(
            """{"event":"pusher:connection_established","data":{"socket_id":"a","activity_timeout":99999}}""",
        )
        val missing = KitPusherCodec.decode(
            """{"event":"pusher:connection_established","data":{"socket_id":"a"}}""",
        )

        assertEquals(KitRealtimeFrame.Established("a", 10), tiny)
        assertEquals(KitRealtimeFrame.Established("a", 300), vast)
        assertEquals(KitRealtimeFrame.Established("a", 30), missing)
    }

    @Test
    fun `an established frame without a socket id is dropped`() {
        assertNull(
            KitPusherCodec.decode(
                """{"event":"pusher:connection_established","data":{"activity_timeout":30}}""",
            ),
        )
        assertNull(
            KitPusherCodec.decode(
                """{"event":"pusher:connection_established","data":{"socket_id":"  "}}""",
            ),
        )
    }

    @Test
    fun `an activity timeout sent as a string is still read`() {
        val frame = KitPusherCodec.decode(
            """{"event":"pusher:connection_established","data":{"socket_id":"a","activity_timeout":"45"}}""",
        )

        assertEquals(KitRealtimeFrame.Established("a", 45), frame)
    }

    @Test
    fun `subscription succeeded seeds the roster from presence ids`() {
        val frame = KitPusherCodec.decode(
            """{"event":"pusher_internal:subscription_succeeded",""" +
                """"channel":"presence-kit.conv.c1",""" +
                """"data":"{\"presence\":{\"count\":2,\"ids\":[\"u1\",\"u2\"],""" +
                """\"hash\":{\"u1\":{\"v\":1},\"u2\":{\"v\":1}}}}"}""",
        )

        assertEquals(
            KitRealtimeFrame.SubscriptionSucceeded("presence-kit.conv.c1", setOf("u1", "u2")),
            frame,
        )
    }

    @Test
    fun `subscription succeeded falls back to the presence hash keys`() {
        val frame = KitPusherCodec.decode(
            """{"event":"pusher_internal:subscription_succeeded",""" +
                """"channel":"presence-kit.conv.c1",""" +
                """"data":{"presence":{"count":9,"hash":{"u1":{"v":1},"u2":{"v":1}}}}}""",
        )

        assertEquals(
            KitRealtimeFrame.SubscriptionSucceeded("presence-kit.conv.c1", setOf("u1", "u2")),
            frame,
        )
    }

    @Test
    fun `a presence count that disagrees with the roster does not change the roster`() {
        // `count` is derivable, so a mismatch is only ever a reason to distrust it — never a reason
        // to invent members we were not told about.
        val frame = KitPusherCodec.decode(
            """{"event":"pusher_internal:subscription_succeeded",""" +
                """"channel":"presence-kit.conv.c1","data":{"presence":{"count":7,"ids":["u1"]}}}""",
        )

        assertEquals(
            KitRealtimeFrame.SubscriptionSucceeded("presence-kit.conv.c1", setOf("u1")),
            frame,
        )
    }

    @Test
    fun `member added and removed carry the channel and the user`() {
        val added = KitPusherCodec.decode(
            """{"event":"pusher_internal:member_added","channel":"presence-kit.conv.c1",""" +
                """"data":{"user_id":"u2","user_info":{"v":1}}}""",
        )
        val removed = KitPusherCodec.decode(
            """{"event":"pusher_internal:member_removed","channel":"presence-kit.conv.c1",""" +
                """"data":"{\"user_id\":\"u2\"}"}""",
        )

        assertEquals(KitRealtimeFrame.MemberAdded("presence-kit.conv.c1", "u2"), added)
        assertEquals(KitRealtimeFrame.MemberRemoved("presence-kit.conv.c1", "u2"), removed)
    }

    @Test
    fun `a membership frame without a channel or a user is dropped`() {
        assertNull(
            KitPusherCodec.decode("""{"event":"pusher_internal:member_added","data":{"user_id":"u2"}}"""),
        )
        assertNull(
            KitPusherCodec.decode(
                """{"event":"pusher_internal:member_added","channel":"presence-kit.conv.c1","data":{}}""",
            ),
        )
    }

    @Test
    fun `an unknown event name is dropped`() {
        assertNull(KitPusherCodec.decode("""{"event":"kit.something.new","data":{"v":1}}"""))
        assertNull(KitPusherCodec.decode("""{"event":"pusher:cache_miss","data":{}}"""))
        assertNull(KitPusherCodec.decode("""{"data":{"v":1}}"""))
    }

    @Test
    fun `a nudge at an unknown frame version is dropped and cannot drive a sync`() {
        assertEquals(
            KitRealtimeFrame.SyncNudge,
            KitPusherCodec.decode(
                """{"event":"kit.sync.nudge","channel":"private-kit.user.u1","data":{"v":1}}""",
            ),
        )
        assertNull(
            KitPusherCodec.decode(
                """{"event":"kit.sync.nudge","channel":"private-kit.user.u1","data":{"v":2}}""",
            ),
        )
        assertNull(
            KitPusherCodec.decode(
                """{"event":"kit.sync.nudge","channel":"private-kit.user.u1","data":{"reason":"message"}}""",
            ),
        )
    }

    @Test
    fun `a nudge is content free and rejects every extra field`() {
        assertNull(
            KitPusherCodec.decode(
                """{"event":"kit.sync.nudge","data":{"v":1,"reason":"something-invented-later"}}""",
            ),
        )
        assertEquals(
            KitRealtimeFrame.SyncNudge,
            KitPusherCodec.decode("""{"event":"kit.sync.nudge","data":{"v":1}}"""),
        )
    }

    @Test
    fun `typing frames carry the server's attribution and their stop counterpart`() {
        val start = KitPusherCodec.decode(
            """{"event":"kit.typing","channel":"presence-kit.conv.c1","data":{"v":1,"user":"u2"}}""",
        )
        val stop = KitPusherCodec.decode(
            """{"event":"kit.typing.stop","channel":"presence-kit.conv.c1","data":"{\"v\":1,\"user\":\"u2\"}"}""",
        )

        assertEquals(KitRealtimeFrame.Typing("presence-kit.conv.c1", "u2", active = true), start)
        assertEquals(KitRealtimeFrame.Typing("presence-kit.conv.c1", "u2", active = false), stop)
    }

    @Test
    fun `a typing frame at an unknown version, or without a user, is dropped`() {
        assertNull(
            KitPusherCodec.decode(
                """{"event":"kit.typing","channel":"presence-kit.conv.c1","data":{"v":2,"user":"u2"}}""",
            ),
        )
        assertNull(
            KitPusherCodec.decode(
                """{"event":"kit.typing","channel":"presence-kit.conv.c1","data":{"v":1}}""",
            ),
        )
        assertNull(KitPusherCodec.decode("""{"event":"kit.typing","data":{"v":1,"user":"u2"}}"""))
    }

    @Test
    fun `an oversize frame is dropped unread`() {
        val padding = "x".repeat(KitPusherCodec.MAX_FRAME_BYTES)
        assertNull(
            KitPusherCodec.decode(
                """{"event":"kit.sync.nudge","data":{"v":1,"reason":"$padding"}}""",
            ),
        )
    }

    @Test
    fun `malformed input is dropped without throwing`() {
        assertNull(KitPusherCodec.decode(""))
        assertNull(KitPusherCodec.decode("not json"))
        assertNull(KitPusherCodec.decode("[]"))
        assertNull(KitPusherCodec.decode("""{"event":"kit.sync.nudge","data":"{ truncated"}"""))
        assertNull(KitPusherCodec.decode("""{"event":42,"data":{"v":1}}"""))
    }

    @Test
    fun `ping pong and error decode from either data shape`() {
        assertEquals(KitRealtimeFrame.Ping, KitPusherCodec.decode("""{"event":"pusher:ping","data":{}}"""))
        assertEquals(KitRealtimeFrame.Pong, KitPusherCodec.decode("""{"event":"pusher:pong","data":"{}"}"""))
        assertEquals(
            KitRealtimeFrame.Failure(4009, "Connection is unauthorized"),
            KitPusherCodec.decode(
                """{"event":"pusher:error","data":"{\"code\":4009,\"message\":\"Connection is unauthorized\"}"}""",
            ),
        )
    }

    @Test
    fun `a subscription error surfaces its status so the auth ladder can branch on it`() {
        assertEquals(
            KitRealtimeFrame.SubscriptionFailed("presence-kit.conv.c1", 403),
            KitPusherCodec.decode(
                """{"event":"pusher:subscription_error","channel":"presence-kit.conv.c1",""" +
                    """"data":{"type":"AuthError","error":"","status":403}}""",
            ),
        )
    }

    @Test
    fun `encoded frames are well formed and forward signed blobs verbatim`() {
        assertEquals("""{"event":"pusher:pong","data":{}}""", KitPusherCodec.encodePong())
        assertEquals("""{"event":"pusher:ping","data":{}}""", KitPusherCodec.encodePing())
        assertEquals(
            """{"event":"pusher:unsubscribe","data":{"channel":"presence-kit.conv.c1"}}""",
            KitPusherCodec.encodeUnsubscribe("presence-kit.conv.c1"),
        )

        // The server's channel_data string is covered by the HMAC, so re-encoding it here would
        // invalidate the signature. It travels as an opaque string.
        val subscribe = KitPusherCodec.encodeSubscribe(
            channel = "presence-kit.conv.c1",
            auth = "key:sig",
            channelData = """{"user_id":"u1"}""",
        )
        assertTrue(subscribe.contains(""""channel":"presence-kit.conv.c1""""))
        assertTrue(subscribe.contains(""""channel_data":"{\"user_id\":\"u1\"}""""))

        // A private channel has no channel_data, and the member must be absent rather than null.
        val privateSubscribe = KitPusherCodec.encodeSubscribe("private-kit.user.u1", "key:sig")
        assertTrue(!privateSubscribe.contains("channel_data"))
    }
}
