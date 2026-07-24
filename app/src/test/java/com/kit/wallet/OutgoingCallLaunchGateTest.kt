package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.feature.calls.OutgoingCallLaunchAction
import com.kit.wallet.feature.calls.OutgoingCallLaunchGate
import org.junit.Assert.assertEquals
import org.junit.Test

class OutgoingCallLaunchGateTest {
    @Test
    fun `retained ViewModel consumes a fresh outgoing route only once`() {
        val state = SavedStateHandle()
        val gate = OutgoingCallLaunchGate(state)

        assertEquals(OutgoingCallLaunchAction.START, gate.consume())
        assertEquals(OutgoingCallLaunchAction.KEEP_CURRENT_ROUTE, gate.consume())
    }

    @Test
    fun `process restored call route cannot silently redial`() {
        val state = SavedStateHandle()
        OutgoingCallLaunchGate(state)
        val restored = OutgoingCallLaunchGate(state)

        assertEquals(OutgoingCallLaunchAction.EXIT_STALE_ROUTE, restored.consume())
    }
}
