package com.kit.wallet

import com.kit.wallet.feature.calls.CallDurationAnchorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a call's timer counts from.
 *
 * The property under test throughout is that the device's own wall clock is never an
 * input. Every number here comes from two server timestamps and this device's monotonic
 * `elapsedRealtime()`, so a phone whose clock is wrong — freshly booted, mid-NTP-step,
 * timezone edited by hand — still shows the same duration as the person it is talking to.
 */
class CallDurationAnchorTest {
    private val callId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun `the answerer's own accept response anchors at the moment it arrives`() {
        // Answered and reported in the same instant: nothing has elapsed yet.
        val anchor = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:04Z",
            elapsedRealtimeMillis = 500_000,
        )

        assertEquals(0, CallDurationAnchorPolicy.seconds(anchor, 500_000))
        assertEquals(7, CallDurationAnchorPolicy.seconds(anchor, 507_400))
    }

    @Test
    fun `a signal that took time to arrive still reports the call's real age`() {
        // The server stamped this three seconds after the answer, so the call was already
        // three seconds old when the message was built.
        val anchor = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:07Z",
            elapsedRealtimeMillis = 500_000,
        )

        assertEquals(3, CallDurationAnchorPolicy.seconds(anchor, 500_000))
        assertEquals(13, CallDurationAnchorPolicy.seconds(anchor, 510_000))
    }

    @Test
    fun `the device's clock is not an input, so its drift cannot reach the timer`() {
        // Both timestamps are years away from anything this device could believe the time
        // to be. The duration is unaffected, because neither is ever compared to it.
        val anchor = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2019-01-01T00:00:00Z",
            serverTime = "2019-01-01T00:00:05Z",
            elapsedRealtimeMillis = 90_000,
        )

        assertEquals(5, CallDurationAnchorPolicy.seconds(anchor, 90_000))
    }

    @Test
    fun `the earliest anchor offered wins, so the timer only ever moves forward`() {
        // The same answer down three routes. Each is stamped at a different point, and each
        // arrives at a different point, so each proposes a different origin. The one that
        // implies the longest call is the one that spent least time in flight.
        val fromAccept = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:04Z",
            elapsedRealtimeMillis = 500_000,
        )
        val fromSocket = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:06Z",
            elapsedRealtimeMillis = 500_400,
            previous = fromAccept,
        )
        val fromPush = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:05Z",
            elapsedRealtimeMillis = 505_000,
            previous = fromSocket,
        )

        // The socket's route: two seconds of server-side age, 400ms after the accept.
        assertEquals(2, CallDurationAnchorPolicy.seconds(fromSocket, 500_400))
        // The push proposes a later origin, so it is discarded and the timer does not
        // jump backwards on the caller's screen.
        assertEquals(fromSocket, fromPush)
    }

    @Test
    fun `a repeated signal is idempotent`() {
        val first = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:06Z",
            elapsedRealtimeMillis = 500_000,
        )
        val replayed = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:06Z",
            elapsedRealtimeMillis = 512_000,
            previous = first,
        )

        assertEquals(first, replayed)
    }

    @Test
    fun `an answer that claims to be older than a call may be is refused`() {
        val held = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:04Z",
            elapsedRealtimeMillis = 500_000,
        )

        // Five hours, past the server's own four-hour ceiling on a call. Honouring it
        // would show a caller a duration that had never happened.
        val replayed = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T04:15:04Z",
            serverTime = "2026-08-27T09:15:04Z",
            elapsedRealtimeMillis = 501_000,
            previous = held,
        )

        assertEquals(held, replayed)
        assertEquals(1, CallDurationAnchorPolicy.seconds(replayed, 501_000))
    }

    @Test
    fun `an answer for a different call cannot move this call's anchor`() {
        val held = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:04Z",
            elapsedRealtimeMillis = 500_000,
        )

        val other = CallDurationAnchorPolicy.anchor(
            callId = "44444444-4444-4444-8444-444444444444",
            answeredAt = "2026-08-27T09:00:00Z",
            serverTime = "2026-08-27T09:15:04Z",
            elapsedRealtimeMillis = 500_000,
            previous = held,
        )

        // A fresh anchor for the other call, and the one being held is untouched.
        assertEquals("44444444-4444-4444-8444-444444444444", other?.callId)
        assertEquals(0, CallDurationAnchorPolicy.seconds(held, 500_000))
    }

    @Test
    fun `a malformed or absent answer leaves whatever was already held`() {
        val held = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:06Z",
            elapsedRealtimeMillis = 500_000,
        )
        assertNotNull(held)

        listOf(null, "", "not a timestamp", "2026-13-45T99:99:99Z").forEach { bad ->
            assertEquals(
                "A bad answered_at moved the anchor: $bad",
                held,
                CallDurationAnchorPolicy.anchor(
                    callId = callId,
                    answeredAt = bad,
                    serverTime = "2026-08-27T09:15:09Z",
                    elapsedRealtimeMillis = 503_000,
                    previous = held,
                ),
            )
        }
        assertNull(
            CallDurationAnchorPolicy.anchor(
                callId = callId,
                answeredAt = null,
                serverTime = "2026-08-27T09:15:09Z",
                elapsedRealtimeMillis = 503_000,
            ),
        )
    }

    @Test
    fun `a server that never sends its own clock still starts the timer, just at zero`() {
        // The pre-existing behaviour for an older backend: count from receipt. It loses the
        // transit time, which is the honest thing to lose — the alternative is reading this
        // device's clock, which is the one input that can be arbitrarily wrong.
        val anchor = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = null,
            elapsedRealtimeMillis = 500_000,
        )

        assertEquals(0, CallDurationAnchorPolicy.seconds(anchor, 500_000))
        assertEquals(4, CallDurationAnchorPolicy.seconds(anchor, 504_000))
    }

    @Test
    fun `timestamps stamped a moment apart out of order read as no time passed`() {
        // Two processes can stamp these milliseconds apart in either order. That is not a
        // reason to show a negative duration.
        val anchor = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04.900Z",
            serverTime = "2026-08-27T09:15:04.100Z",
            elapsedRealtimeMillis = 500_000,
        )

        assertEquals(0, CallDurationAnchorPolicy.seconds(anchor, 500_000))
    }

    @Test
    fun `connecting with nothing authoritative anchors here, and a later signal corrects it`() {
        val onConnect = CallDurationAnchorPolicy.anchorOnConnect(callId, 500_000)
        assertEquals(0, CallDurationAnchorPolicy.seconds(onConnect, 500_000))

        // The answer catches up a second later, and reveals that six seconds had already
        // passed when the server sent it. The timer jumps forward from one to six rather
        // than staying behind: the anchor moves back to where the call really began.
        val corrected = CallDurationAnchorPolicy.anchor(
            callId = callId,
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:10Z",
            elapsedRealtimeMillis = 501_000,
            previous = onConnect,
        )

        assertEquals(1, CallDurationAnchorPolicy.seconds(onConnect, 501_000))
        assertEquals(6, CallDurationAnchorPolicy.seconds(corrected, 501_000))
    }

    @Test
    fun `no anchor at all reads as zero rather than as a nonsense duration`() {
        assertEquals(0, CallDurationAnchorPolicy.seconds(null, 500_000))
    }

    @Test
    fun `an elapsed reading before the anchor never shows a negative duration`() {
        val anchor = CallDurationAnchorPolicy.anchorOnConnect(callId, 500_000)

        assertEquals(0, CallDurationAnchorPolicy.seconds(anchor, 499_000))
    }

    @Test
    fun `a blank call id is not something an anchor can belong to`() {
        assertNull(
            CallDurationAnchorPolicy.anchor(
                callId = "",
                answeredAt = "2026-08-27T09:15:04Z",
                serverTime = "2026-08-27T09:15:04Z",
                elapsedRealtimeMillis = 500_000,
            ),
        )
    }
}
