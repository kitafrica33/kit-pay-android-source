package com.kit.wallet

import com.kit.wallet.data.notifications.CallAnswerSignalPolicy
import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared admission rules for a `call.answered` signal, and the proof that the socket
 * frame and the push are held to them equally.
 *
 * Acting on one of these cancels a ring deadline and moves a call's state, so anything that
 * is not the shape the server actually sends has to stop here rather than downstream.
 */
class CallAnswerSignalPolicyTest {
    @Test
    fun `only the active state announces an answer`() {
        assertTrue(CallAnswerSignalPolicy.announcesActive("active"))
        assertFalse(CallAnswerSignalPolicy.announcesActive(null))
        assertFalse(CallAnswerSignalPolicy.announcesActive(""))
        assertFalse(CallAnswerSignalPolicy.announcesActive("Active"))
        assertFalse(CallAnswerSignalPolicy.announcesActive("ringing"))
        assertFalse(CallAnswerSignalPolicy.announcesActive("ended"))
    }

    @Test
    fun `a call id is accepted only as the uuid the server issues`() {
        assertEquals(CALL_ID, CallAnswerSignalPolicy.callId(CALL_ID))
        assertEquals(CALL_ID, CallAnswerSignalPolicy.callId("  $CALL_ID  "))
        // Normalised, so the same call never presents as two different ids downstream.
        assertEquals(CALL_ID, CallAnswerSignalPolicy.callId(CALL_ID.uppercase()))
    }

    @Test
    fun `anything that is not a uuid is refused as a call id`() {
        assertNull(CallAnswerSignalPolicy.callId(null))
        assertNull(CallAnswerSignalPolicy.callId(""))
        assertNull(CallAnswerSignalPolicy.callId("   "))
        assertNull(CallAnswerSignalPolicy.callId("not-a-uuid"))
        assertNull(CallAnswerSignalPolicy.callId("../../calls/$CALL_ID"))
        assertNull(CallAnswerSignalPolicy.callId("$CALL_ID $CALL_ID"))
    }

    @Test
    fun `a truncated call id is refused rather than silently repaired`() {
        // `UUID.fromString` is permissive about short hex groups and zero-pads them, so a
        // truncated id parses cleanly and comes back as a different, valid-looking id.
        // Requiring the parse to round-trip is what makes this validation, not repair.
        val truncated = CALL_ID.dropLast(1)

        assertNull(CallAnswerSignalPolicy.callId(truncated))
        assertNull(CallAnswerSignalPolicy.callId("3333333-3333-4333-8333-333333333333"))
        assertNull(CallLifecycleEvent.fromData(push() + ("call_id" to truncated)))
    }

    @Test
    fun `a well formed pair keeps both instants verbatim`() {
        val anchor = CallAnswerSignalPolicy.anchor("2026-08-27T09:15:04Z", "2026-08-27T09:15:05Z")

        assertNotNull(anchor)
        assertEquals("2026-08-27T09:15:04Z", anchor?.answeredAt)
        assertEquals("2026-08-27T09:15:05Z", anchor?.serverTime)
    }

    @Test
    fun `an older server that sends no send instant still anchors`() {
        // The push predates `server_time`, and an install that answers through it must not
        // lose its timer origin just because the second field is missing.
        val anchor = CallAnswerSignalPolicy.anchor("2026-08-27T09:15:04Z", null)

        assertEquals("2026-08-27T09:15:04Z", anchor?.answeredAt)
        assertNull(anchor?.serverTime)
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-27T09:15:04Z", "  ")?.serverTime)
    }

    @Test
    fun `a send instant that is present but unreadable refuses the whole pair`() {
        // Distinct from absent: this is a malformed message, not an old one, and taking
        // half of it would anchor the timer to a number the server never sent.
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-27T09:15:04Z", "yesterday"))
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-27T09:15:04Z", "2026-08-27 09:15:05"))
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-27T09:15:04Z", "0"))
    }

    @Test
    fun `an unreadable or absent answer instant anchors nothing`() {
        assertNull(CallAnswerSignalPolicy.anchor(null, "2026-08-27T09:15:05Z"))
        assertNull(CallAnswerSignalPolicy.anchor("", "2026-08-27T09:15:05Z"))
        assertNull(CallAnswerSignalPolicy.anchor("   ", "2026-08-27T09:15:05Z"))
        assertNull(CallAnswerSignalPolicy.anchor("not-a-date", "2026-08-27T09:15:05Z"))
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-27", "2026-08-27T09:15:05Z"))
    }

    @Test
    fun `a claimed age within the server's four hour cap is accepted`() {
        val fourHours = CallAnswerSignalPolicy.anchor("2026-08-27T05:15:05Z", "2026-08-27T09:15:05Z")

        assertNotNull(fourHours)
        assertEquals("2026-08-27T05:15:05Z", fourHours?.answeredAt)
    }

    @Test
    fun `a claimed age beyond the four hour cap is a replay and is refused`() {
        // The server hard-caps a call at four hours, so a signal claiming to have been
        // answered longer ago than that describes a call that cannot still be running.
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-27T05:15:04Z", "2026-08-27T09:15:05Z"))
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-20T09:15:05Z", "2026-08-27T09:15:05Z"))
    }

    @Test
    fun `an answer stamped slightly after the send instant is ordinary and is accepted`() {
        // Two processes, two clocks; a small inversion means "no time has passed", not a lie.
        assertNotNull(CallAnswerSignalPolicy.anchor("2026-08-27T09:15:05Z", "2026-08-27T09:15:04Z"))
        assertNotNull(CallAnswerSignalPolicy.anchor("2026-08-27T09:16:05Z", "2026-08-27T09:15:05Z"))
    }

    @Test
    fun `an answer stamped far in the future of the send instant is refused`() {
        assertNull(CallAnswerSignalPolicy.anchor("2026-08-27T09:16:06Z", "2026-08-27T09:15:05Z"))
        assertNull(CallAnswerSignalPolicy.anchor("2027-08-27T09:15:05Z", "2026-08-27T09:15:05Z"))
    }

    @Test
    fun `the rules never consult this device's clock`() {
        // A phone whose clock is years out must still take a genuine answer. Every rule is
        // a comparison between the two server instants, so a signal from the far past or
        // the far future of "now" is judged the same way.
        assertNotNull(CallAnswerSignalPolicy.anchor("1999-01-01T00:00:00Z", "1999-01-01T00:00:01Z"))
        assertNotNull(CallAnswerSignalPolicy.anchor("2199-01-01T00:00:00Z", "2199-01-01T00:00:01Z"))
    }

    @Test
    fun `a push answer must announce the active state to be understood`() {
        assertNotNull(CallLifecycleEvent.fromData(push()))
        assertNull(CallLifecycleEvent.fromData(push(state = "ringing")))
        assertNull(CallLifecycleEvent.fromData(push(state = "ended")))
        assertNull(CallLifecycleEvent.fromData(push(state = null)))
    }

    @Test
    fun `a push answer carries the validated instants through to the screen`() {
        val event = CallLifecycleEvent.fromData(push())

        assertEquals(CallLifecycleKind.ANSWERED, event?.kind)
        assertEquals(CALL_ID, event?.callId)
        assertEquals("2026-08-27T09:15:04Z", event?.answeredAt)
        assertEquals("2026-08-27T09:15:05Z", event?.serverTime)
    }

    @Test
    fun `a push whose instants are present but refused is refused whole`() {
        // The socket drops the identical payload, and the push must not be the softer way
        // in: a pair the policy calls a replay or a forgery moves no call state at all.
        assertNull(
            CallLifecycleEvent.fromData(
                push(answeredAt = "2026-08-20T09:15:05Z", serverTime = "2026-08-27T09:15:05Z"),
            ),
        )
        assertNull(
            CallLifecycleEvent.fromData(
                push(answeredAt = "not-a-date", serverTime = "2026-08-27T09:15:05Z"),
            ),
        )
        assertNull(
            CallLifecycleEvent.fromData(
                push(answeredAt = "2026-08-27T09:15:04Z", serverTime = "yesterday"),
            ),
        )
        // A send instant with no answer instant is a payload no server version produces.
        assertNull(
            CallLifecycleEvent.fromData(
                push(answeredAt = null, serverTime = "2026-08-27T09:15:05Z"),
            ),
        )
    }

    @Test
    fun `an older server's push without instants still answers but anchors nothing`() {
        // This route is authenticated by FCM and by the server that sent it, and an older
        // server sends no timestamps at all: there is nothing to validate, so the answer
        // is acted on and only the timer goes without an authoritative origin.
        val old = CallLifecycleEvent.fromData(push(answeredAt = null, serverTime = null))

        assertEquals(CallLifecycleKind.ANSWERED, old?.kind)
        assertNull(old?.answeredAt)
        assertNull(old?.serverTime)

        // A server that sends only the answer instant still anchors from it.
        val answeredOnly = CallLifecycleEvent.fromData(push(serverTime = null))

        assertEquals(CallLifecycleKind.ANSWERED, answeredOnly?.kind)
        assertEquals("2026-08-27T09:15:04Z", answeredOnly?.answeredAt)
        assertNull(answeredOnly?.serverTime)
    }

    @Test
    fun `other lifecycle kinds never take an answer anchor`() {
        val ended = CallLifecycleEvent.fromData(
            push(type = "call.ended", state = "ended") + ("end_reason" to "hangup"),
        )

        assertEquals(CallLifecycleKind.ENDED, ended?.kind)
        assertNull(ended?.answeredAt)
        assertNull(ended?.serverTime)
        assertEquals("hangup", ended?.reason)
    }

    private fun push(
        type: String = "call.answered",
        state: String? = "active",
        answeredAt: String? = "2026-08-27T09:15:04Z",
        serverTime: String? = "2026-08-27T09:15:05Z",
    ): Map<String, String> = buildMap {
        put("type", type)
        put("call_id", CALL_ID)
        state?.let { put("state", it) }
        answeredAt?.let { put("answered_at", it) }
        serverTime?.let { put("server_time", it) }
    }

    private companion object {
        // Contains alphabetic hex digits on purpose: an all-digit id is its own uppercase,
        // which would make the normalisation assertions pass vacuously.
        const val CALL_ID = "a3b3c3d3-e3f3-4333-8333-333333333333"
    }
}
