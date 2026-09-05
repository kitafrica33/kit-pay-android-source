package com.kit.wallet

import com.kit.wallet.feature.calls.BackendCallTerminationKind
import com.kit.wallet.feature.calls.CallTerminationQueue
import com.kit.wallet.feature.calls.PendingCallTermination
import com.kit.wallet.feature.calls.pendingLocalTermination
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

class CallTerminationQueueTest {
    @Test
    fun `notification hangup survives an offline backend attempt and retries the exact call`() = runTest {
        val callId = "550e8400-e29b-41d4-a716-446655440000"
        val bus = CallLifecycleEventBus()
        val queue = CallTerminationQueue()
        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }
        bus.publish(CallLifecycleEvent(
            callId, CallLifecycleKind.ENDED, reason = "completed", localEndRequested = true,
        ))
        val action = requireNotNull(received.await().pendingLocalTermination())
        queue.enqueue(action)

        val attempts = mutableListOf<PendingCallTermination>()
        assertFalse(queue.drain { attempts += it; false })
        assertEquals(listOf(PendingCallTermination(callId, BackendCallTerminationKind.END, "completed")), queue.snapshot())
        assertTrue(queue.drain { attempts += it; true })
        assertTrue(queue.isEmpty)
        assertEquals(listOf(action, action), attempts)
    }

    @Test
    fun `server ended and retained decline events cannot request local backend cleanup`() {
        val callId = "550e8400-e29b-41d4-a716-446655440000"
        val wireEvent = requireNotNull(CallLifecycleEvent.fromData(mapOf(
            "type" to "call.ended", "call_id" to callId, "end_reason" to "completed",
            "local_end_requested" to "true", "localEndRequested" to "true",
        )))
        assertFalse(wireEvent.localEndRequested)
        assertNull(wireEvent.pendingLocalTermination())
        assertNull(CallLifecycleEvent(
            callId, CallLifecycleKind.DECLINED, state = "declined", localEndRequested = true,
        ).pendingLocalTermination())
    }

    @Test
    fun `pending call ids and original endpoint choices are never overwritten`() {
        val queue = CallTerminationQueue()
        val first = PendingCallTermination("call-one", BackendCallTerminationKind.DECLINE)
        val second = PendingCallTermination("call-two", BackendCallTerminationKind.END, "network_error")

        queue.enqueue(first)
        queue.enqueue(second)
        queue.enqueue(PendingCallTermination("call-one", BackendCallTerminationKind.END))

        assertEquals(listOf(first, second), queue.snapshot())
        assertFalse(queue.isEmpty)
        queue.completed("call-one")
        assertEquals(listOf(second), queue.snapshot())
        queue.completed("call-two")
        assertTrue(queue.isEmpty)
    }
}
