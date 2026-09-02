package com.kit.wallet.data.notifications

import com.kit.wallet.feature.calls.ActiveCallUiState
import com.kit.wallet.feature.calls.applyIncomingCallRelayEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallRelayTest {
    @Test
    fun `final retirement retracts a duplicate published after terminal cleanup`() = runTest {
        val relay = IncomingCallRelay()
        val events = mutableListOf<IncomingCallRelayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            relay.events.take(3).toList(events)
        }

        // A terminal path wins first, then an already-admitted duplicate publishes its stale
        // waiting call. Final ledger reconciliation emits the second retirement after that ring.
        relay.retire(CALL_ID)
        relay.publish(incomingCall())
        relay.retire(CALL_ID)
        runCurrent()

        assertEquals(
            listOf(
                IncomingCallRelayEvent.Retired(CALL_ID),
                IncomingCallRelayEvent.Ringing(incomingCall()),
                IncomingCallRelayEvent.Retired(CALL_ID),
            ),
            events,
        )
        var state = apply(ActiveCallUiState(), events[0])
        assertNull(state.waitingCall)
        state = apply(state, events[1])
        assertEquals(CALL_ID, state.waitingCall?.callId)
        state = apply(state, events[2])
        assertNull(state.waitingCall)
    }

    @Test
    fun `ordinary terminal retirement follows and retracts an earlier waiting ring`() = runTest {
        val relay = IncomingCallRelay()
        val events = mutableListOf<IncomingCallRelayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            relay.events.take(2).toList(events)
        }

        relay.publish(incomingCall())
        relay.retire(CALL_ID)
        runCurrent()

        assertEquals(
            listOf(
                IncomingCallRelayEvent.Ringing(incomingCall()),
                IncomingCallRelayEvent.Retired(CALL_ID),
            ),
            events,
        )
        var state = apply(ActiveCallUiState(), events[0])
        assertEquals(CALL_ID, state.waitingCall?.callId)
        state = apply(state, events[1])
        assertNull(state.waitingCall)
    }

    private fun apply(
        state: ActiveCallUiState,
        event: IncomingCallRelayEvent,
    ): ActiveCallUiState = applyIncomingCallRelayEvent(
        state = state,
        activeCallId = ACTIVE_CALL_ID,
        terminated = false,
        event = event,
    )

    private fun incomingCall() = IncomingCallPayload(
        callId = CALL_ID,
        callerName = "Kit Pay contact",
        video = false,
        ringExpiresAt = "2026-09-02T12:00:30Z",
        serverTime = "2026-09-02T12:00:00Z",
    )

    private companion object {
        const val ACTIVE_CALL_ID = "22222222-2222-4222-8222-222222222222"
        const val CALL_ID = "11111111-1111-4111-8111-111111111111"
    }
}
