package com.kit.wallet

import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleEventBus
import com.kit.wallet.data.notifications.CallLifecycleKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLifecycleEventBusTest {
    private val callId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `validated call lifecycle event is delivered to an active subscriber`() = runTest {
        val bus = CallLifecycleEventBus()
        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

        assertTrue(
            bus.publish(
                mapOf(
                    "type" to "call.ended",
                    "call_id" to callId,
                    "state" to "ended",
                    "end_reason" to "completed",
                ),
            ),
        )

        assertEquals(
            CallLifecycleEvent(
                callId = callId,
                kind = CallLifecycleKind.ENDED,
                state = "ended",
                reason = "completed",
            ),
            received.await(),
        )
    }

    @Test
    fun `invalid and ringing payloads are not lifecycle events`() {
        val bus = CallLifecycleEventBus()

        assertFalse(bus.publish(mapOf("type" to "call.ringing", "call_id" to callId)))
        assertFalse(bus.publish(mapOf("type" to "call.ended", "call_id" to "invalid")))
        assertNull(
            CallLifecycleEvent.fromData(
                mapOf("type" to "wallet.transfer", "call_id" to callId),
            ),
        )
    }

    @Test
    fun `answered is transitional while decline end and missed are terminal`() {
        val events = CallLifecycleKind.entries.associateWith { kind ->
            CallLifecycleEvent(
                callId = callId,
                kind = kind,
                state = if (kind == CallLifecycleKind.DECLINED) "declined" else null,
            ).terminal
        }

        assertFalse(events.getValue(CallLifecycleKind.ANSWERED))
        assertTrue(events.getValue(CallLifecycleKind.DECLINED))
        assertTrue(events.getValue(CallLifecycleKind.ENDED))
        assertTrue(events.getValue(CallLifecycleKind.MISSED))
        assertFalse(
            CallLifecycleEvent(callId, CallLifecycleKind.DECLINED, state = "ringing").terminal,
        )
    }
}
