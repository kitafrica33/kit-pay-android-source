package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.feature.calls.OutgoingCallLaunchAction
import com.kit.wallet.feature.calls.OutgoingCallLaunchGate
import com.kit.wallet.feature.calls.offlineCallRetryDelayMillis
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

    @Test
    fun `offline call retries back off while remaining process local`() {
        assertEquals(2_000L, offlineCallRetryDelayMillis(0))
        assertEquals(4_000L, offlineCallRetryDelayMillis(1))
        assertEquals(16_000L, offlineCallRetryDelayMillis(3))
        assertEquals(30_000L, offlineCallRetryDelayMillis(4))
        assertEquals(30_000L, offlineCallRetryDelayMillis(100))
    }
}
