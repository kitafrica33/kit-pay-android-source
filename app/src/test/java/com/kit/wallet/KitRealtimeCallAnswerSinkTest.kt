package com.kit.wallet

import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleEventBus
import com.kit.wallet.data.notifications.CallLifecycleKind
import com.kit.wallet.data.realtime.KitRealtimeCallAnswerSink
import com.kit.wallet.data.realtime.KitRealtimeFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only route from a `kit.call.answered` frame into the call screen.
 *
 * It is closed unless the socket is live, and it validates what the frame says before
 * anything acts on it, because acting on it cancels a ring deadline and moves a call's
 * state. The socket must not be the softer of the two ways in.
 */
class KitRealtimeCallAnswerSinkTest {
    @Test
    fun `a frame is dropped until the socket is live`() = runTest {
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)

        assertFalse(sink.onCallAnswered(frame()))
        runCurrent()

        assertEquals(emptyList<CallLifecycleEvent>(), seen)
    }

    @Test
    fun `a live socket publishes the answer to the call screen`() = runTest {
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()

        assertTrue(sink.onCallAnswered(frame()))
        runCurrent()

        assertEquals(1, seen.size)
        val event = seen.single()
        assertEquals(CALL_ID, event.callId)
        assertEquals(CallLifecycleKind.ANSWERED, event.kind)
        assertEquals("active", event.state)
        assertEquals(ANSWERED_AT, event.answeredAt)
        assertEquals(SERVER_TIME, event.serverTime)
    }

    @Test
    fun `a frame arriving during teardown is dropped`() = runTest {
        // The socket closes when the session ends or the app stops; a frame still in the
        // reader's hands at that moment must not reach a screen that is going away.
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()
        sink.close()

        assertFalse(sink.onCallAnswered(frame()))
        runCurrent()

        assertEquals(emptyList<CallLifecycleEvent>(), seen)
    }

    @Test
    fun `a reconnected socket accepts answers again`() = runTest {
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()
        sink.close()
        sink.open()

        assertTrue(sink.onCallAnswered(frame()))
        runCurrent()

        assertEquals(listOf(CALL_ID), seen.map(CallLifecycleEvent::callId))
    }

    @Test
    fun `a call id that is not the uuid the server issues never reaches the screen`() = runTest {
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()

        assertFalse(sink.onCallAnswered(frame(callId = "not-a-uuid")))
        assertFalse(sink.onCallAnswered(frame(callId = CALL_ID.dropLast(1))))
        assertFalse(sink.onCallAnswered(frame(callId = "   ")))
        runCurrent()

        assertEquals(emptyList<CallLifecycleEvent>(), seen)
    }

    @Test
    fun `an unreadable instant never reaches the screen`() = runTest {
        // The frame's timestamps are part of the shape the current server promises, so a
        // frame without usable ones is refused outright — and the push route refuses a
        // present-but-unusable pair the same way, so neither is the softer way in. Only
        // a push with no timestamps at all (an older server) is answered without them.
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()

        assertFalse(sink.onCallAnswered(frame(answeredAt = "yesterday")))
        assertFalse(sink.onCallAnswered(frame(serverTime = "yesterday")))
        assertFalse(sink.onCallAnswered(frame(answeredAt = "2026-08-27")))
        runCurrent()

        assertEquals(emptyList<CallLifecycleEvent>(), seen)
    }

    @Test
    fun `a replayed answer older than a call can be never reaches the screen`() = runTest {
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()

        assertFalse(
            sink.onCallAnswered(
                frame(answeredAt = "2026-08-20T09:15:05Z", serverTime = "2026-08-27T09:15:05Z"),
            ),
        )
        runCurrent()

        assertEquals(emptyList<CallLifecycleEvent>(), seen)
    }

    @Test
    fun `an answer claimed far in the future of the server's own clock is refused`() = runTest {
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()

        assertFalse(
            sink.onCallAnswered(
                frame(answeredAt = "2026-08-27T10:15:05Z", serverTime = "2026-08-27T09:15:05Z"),
            ),
        )
        runCurrent()

        assertEquals(emptyList<CallLifecycleEvent>(), seen)
    }

    @Test
    fun `a call id is normalised so one call never presents as two`() = runTest {
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()

        assertTrue(sink.onCallAnswered(frame(callId = "  ${CALL_ID.uppercase()}  ")))
        runCurrent()

        assertEquals(listOf(CALL_ID), seen.map(CallLifecycleEvent::callId))
    }

    @Test
    fun `both routes publish the same answer, so the duplicate is indistinguishable`() = runTest {
        // The socket frame and the push carry the same instants by construction. Whichever
        // lands second must look exactly like the first, or the screen would have to tell
        // them apart to avoid anchoring twice.
        val bus = CallLifecycleEventBus()
        val seen = collect(bus)
        val sink = KitRealtimeCallAnswerSink(bus)
        sink.open()

        sink.onCallAnswered(frame())
        bus.publish(
            mapOf(
                "type" to "call.answered",
                "call_id" to CALL_ID,
                "state" to "active",
                "answered_at" to ANSWERED_AT,
                "server_time" to SERVER_TIME,
            ),
        )
        runCurrent()

        assertEquals(2, seen.size)
        assertEquals(seen.first(), seen.last())
    }

    private fun TestScope.collect(bus: CallLifecycleEventBus): List<CallLifecycleEvent> {
        val seen = mutableListOf<CallLifecycleEvent>()
        backgroundScope.launch { bus.events.collect { seen += it } }
        // The bus replays nothing, so the collector has to be subscribed before anything
        // is published or the test would pass for the wrong reason.
        runCurrent()
        return seen
    }

    private fun frame(
        callId: String = CALL_ID,
        answeredAt: String = ANSWERED_AT,
        serverTime: String = SERVER_TIME,
    ) = KitRealtimeFrame.CallAnswered(
        channel = "private-kit.user.$ANSWERER_ID",
        callId = callId,
        answeredAt = answeredAt,
        answeredBy = ANSWERER_ID,
        serverTime = serverTime,
    )

    private companion object {
        // Contains alphabetic hex digits on purpose: an all-digit id is its own uppercase,
        // which would make the normalisation assertions pass vacuously.
        const val CALL_ID = "a3b3c3d3-e3f3-4333-8333-333333333333"
        const val ANSWERER_ID = "44444444-4444-4444-8444-444444444444"
        const val ANSWERED_AT = "2026-08-27T09:15:04Z"
        const val SERVER_TIME = "2026-08-27T09:15:05Z"
    }
}
