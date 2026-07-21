package com.kit.wallet

import com.kit.wallet.feature.calls.BackendCallTerminationKind
import com.kit.wallet.feature.calls.CallTerminationQueue
import com.kit.wallet.feature.calls.PendingCallTermination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTerminationQueueTest {
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
