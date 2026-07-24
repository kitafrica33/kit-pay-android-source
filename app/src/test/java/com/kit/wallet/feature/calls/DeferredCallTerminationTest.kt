package com.kit.wallet.feature.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredCallTerminationTest {
    @Test
    fun `termination requested during create call finishes late tracked Telecom call exactly once`() {
        val finishes = mutableListOf<Pair<String, String>>()
        val termination = DeferredCallTermination<String>(
            finish = { callId, disconnect -> finishes += callId to disconnect },
        )

        termination.terminate("local")
        assertTrue(finishes.isEmpty())

        // POST /calls completed and trackOutgoing registered the call before resolving its id.
        termination.resolveCallId("late-call")
        termination.resolveCallId("late-call")
        termination.terminate("error")

        assertEquals(listOf("late-call" to "local"), finishes)
    }

    @Test
    fun `known call is finished immediately and duplicate terminal requests are ignored`() {
        val finishes = mutableListOf<Pair<String, String>>()
        val termination = DeferredCallTermination<String>(
            finish = { callId, disconnect -> finishes += callId to disconnect },
            initialCallId = "incoming-call",
        )

        termination.terminate("rejected")
        termination.terminate("remote")

        assertEquals(listOf("incoming-call" to "rejected"), finishes)
    }
}
