package com.kit.wallet

import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleKind
import com.kit.wallet.feature.calls.CallAnswerAction
import com.kit.wallet.feature.calls.CallAnswerRouting
import com.kit.wallet.feature.calls.CallPhase
import com.kit.wallet.feature.calls.PendingCallAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one `call.answered` means to each of the three kinds of device that receive it.
 *
 * The caller advances, this account's other ringing devices stop, and the device that
 * actually answered does nothing but take the timestamp. Every phase is covered here
 * because getting it wrong either way is visible on a real handset — a device that ignores
 * the signal keeps ringing for a call the user is already on, and one that over-applies it
 * hangs up the call it has just answered.
 */
class CallAnswerRoutingTest {
    @Test
    fun `a caller whose call is ringing starts connecting`() {
        assertEquals(
            CallAnswerAction.ADVANCE_TO_CONNECTING,
            CallAnswerRouting.actionFor(CallPhase.RINGING, hasConnection = true, starting = false),
        )
    }

    @Test
    fun `a sibling device still validating the invite stops ringing`() {
        assertEquals(
            CallAnswerAction.SUPERSEDE_LOCAL_RING,
            CallAnswerRouting.actionFor(
                CallPhase.VALIDATING,
                hasConnection = false,
                starting = false,
            ),
        )
    }

    @Test
    fun `a sibling device already showing the incoming call stops ringing`() {
        assertEquals(
            CallAnswerAction.SUPERSEDE_LOCAL_RING,
            CallAnswerRouting.actionFor(CallPhase.INCOMING, hasConnection = false, starting = false),
        )
    }

    @Test
    fun `the device that answered is not torn down by its own answer`() {
        // `connect` moves the phase and takes out a start job before it sends the request,
        // so on the answering device the echo arrives with a start already in flight. This
        // is the case that would otherwise hang up the call the user has just taken.
        assertEquals(
            CallAnswerAction.ANCHOR_ONLY,
            CallAnswerRouting.actionFor(CallPhase.INCOMING, hasConnection = false, starting = true),
        )
        assertEquals(
            CallAnswerAction.ANCHOR_ONLY,
            CallAnswerRouting.actionFor(
                CallPhase.VALIDATING,
                hasConnection = false,
                starting = true,
            ),
        )
    }

    @Test
    fun `a device holding a connection is never treated as still offering the call`() {
        assertEquals(
            CallAnswerAction.ANCHOR_ONLY,
            CallAnswerRouting.actionFor(CallPhase.INCOMING, hasConnection = true, starting = false),
        )
        assertEquals(
            CallAnswerAction.ANCHOR_ONLY,
            CallAnswerRouting.actionFor(
                CallPhase.VALIDATING,
                hasConnection = true,
                starting = false,
            ),
        )
    }

    @Test
    fun `a connected or reconnecting call only takes the timestamp`() {
        // A late duplicate of the answer — the other route arriving second — reaches a call
        // that is already up. It must not move anything.
        listOf(CallPhase.CONNECTED, CallPhase.RECONNECTING, CallPhase.CONNECTING).forEach { phase ->
            assertEquals(
                phase.name,
                CallAnswerAction.ANCHOR_ONLY,
                CallAnswerRouting.actionFor(phase, hasConnection = true, starting = false),
            )
        }
    }

    @Test
    fun `an ending or ended call is not revived by a late answer`() {
        listOf(CallPhase.ENDING, CallPhase.ENDED, CallPhase.ERROR, CallPhase.IDLE).forEach { phase ->
            assertEquals(
                phase.name,
                CallAnswerAction.ANCHOR_ONLY,
                CallAnswerRouting.actionFor(phase, hasConnection = false, starting = false),
            )
            assertEquals(
                phase.name,
                CallAnswerAction.ANCHOR_ONLY,
                CallAnswerRouting.actionFor(phase, hasConnection = true, starting = false),
            )
        }
    }

    @Test
    fun `every phase has a decision on a device with no connection and no start`() {
        // The exhaustive sweep: exactly the two offering phases stop, ringing advances, and
        // nothing else moves. A phase added later without a decision fails here.
        val superseded = CallPhase.entries.filter {
            CallAnswerRouting.actionFor(it, hasConnection = false, starting = false) ==
                CallAnswerAction.SUPERSEDE_LOCAL_RING
        }
        val advanced = CallPhase.entries.filter {
            CallAnswerRouting.actionFor(it, hasConnection = false, starting = false) ==
                CallAnswerAction.ADVANCE_TO_CONNECTING
        }

        assertEquals(listOf(CallPhase.VALIDATING, CallPhase.INCOMING), superseded)
        assertEquals(listOf(CallPhase.RINGING), advanced)
    }

    @Test
    fun `no phase supersedes the local ring while a start is in flight`() {
        // A start in flight means this device is the one answering, whatever phase it is
        // showing at the moment the echo lands. Nothing may tear it down.
        val superseded = CallPhase.entries.filter { phase ->
            listOf(true, false).any { holdsConnection ->
                CallAnswerRouting.actionFor(phase, holdsConnection, starting = true) ==
                    CallAnswerAction.SUPERSEDE_LOCAL_RING
            }
        }

        assertEquals(emptyList<CallPhase>(), superseded)
    }

    @Test
    fun `a connected room with the other side already in it is connected`() {
        listOf(true, false).forEach { incoming ->
            listOf(true, false).forEach { answered ->
                assertEquals(
                    CallPhase.CONNECTED,
                    CallAnswerRouting.phaseAfterConnect(
                        hasRemoteParticipants = true,
                        incoming = incoming,
                        alreadyAnswered = answered,
                    ),
                )
            }
        }
    }

    @Test
    fun `an answerer alone in the room is connecting while the caller joins`() {
        assertEquals(
            CallPhase.CONNECTING,
            CallAnswerRouting.phaseAfterConnect(
                hasRemoteParticipants = false,
                incoming = true,
                alreadyAnswered = false,
            ),
        )
    }

    @Test
    fun `a caller alone in the room with no answer yet is ringing`() {
        assertEquals(
            CallPhase.RINGING,
            CallAnswerRouting.phaseAfterConnect(
                hasRemoteParticipants = false,
                incoming = false,
                alreadyAnswered = false,
            ),
        )
    }

    @Test
    fun `a caller whose call was answered before the room came up is connecting not ringing`() {
        // No second answer signal is coming to correct a "Ringing…" shown here, so showing
        // it would hand back exactly the delay the answer buffer exists to remove.
        assertEquals(
            CallPhase.CONNECTING,
            CallAnswerRouting.phaseAfterConnect(
                hasRemoteParticipants = false,
                incoming = false,
                alreadyAnswered = true,
            ),
        )
    }

    @Test
    fun `only an unanswered outgoing call arms the ring window`() {
        assertTrue(CallAnswerRouting.armsRingDeadline(incoming = false, alreadyAnswered = false))
        // Answering ends the window: a deadline armed here would expire mid-call and mark
        // a call the user is on as missed.
        assertFalse(CallAnswerRouting.armsRingDeadline(incoming = false, alreadyAnswered = true))
        assertFalse(CallAnswerRouting.armsRingDeadline(incoming = true, alreadyAnswered = false))
        assertFalse(CallAnswerRouting.armsRingDeadline(incoming = true, alreadyAnswered = true))
    }

    @Test
    fun `an answer that lands before the start response arms no deadline and never shows ringing`() {
        // The whole early-answer sequence, in the order the screen really runs it. The
        // callee picks up while `POST /calls` is still in flight, so the answer arrives
        // while the screen cannot yet name its own call and is buffered; the response then
        // claims it by the exact id it names, records the call as answered, and only after
        // that decides the ring window and the post-connect phase.
        val pending = PendingCallAnswers()
        val early = CallLifecycleEvent(
            callId = CALL_ID,
            kind = CallLifecycleKind.ANSWERED,
            state = "active",
            answeredAt = "2026-08-27T09:15:04Z",
            serverTime = "2026-08-27T09:15:05Z",
        )

        assertTrue(pending.remember(early))

        // The start response lands and names the call. The claim is by exact id, so an
        // answer for any other call would not have been applied to this one.
        assertNull(pending.claim("44444444-4444-4444-8444-444444444444"))
        val claimed = pending.claim(CALL_ID)
        assertNotNull(claimed)
        val answeredCallId = claimed?.callId

        // The response resumes: with the answer already recorded, no ring deadline is
        // armed over the answered call…
        assertFalse(
            CallAnswerRouting.armsRingDeadline(
                incoming = false,
                alreadyAnswered = answeredCallId == CALL_ID,
            ),
        )
        // …and the phase after the room connects is CONNECTING, never RINGING.
        assertEquals(
            CallPhase.CONNECTING,
            CallAnswerRouting.phaseAfterConnect(
                hasRemoteParticipants = false,
                incoming = false,
                alreadyAnswered = answeredCallId == CALL_ID,
            ),
        )
    }

    private companion object {
        const val CALL_ID = "33333333-3333-4333-8333-333333333333"
    }
}
