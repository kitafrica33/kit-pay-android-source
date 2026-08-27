package com.kit.wallet

import com.kit.wallet.feature.chat.VoiceNoteDraftPhase
import com.kit.wallet.feature.chat.VoiceNoteDraftPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The complete transition table for a voice-note draft. A draft is plaintext audio that
 * exists only on this device until Send, so the two properties every row here defends are:
 * nothing leaves the device except through an explicit Send, and nothing is thrown away
 * except through an explicit discard.
 */
class VoiceNoteDraftPolicyTest {
    @Test
    fun `a fresh recording begins only when no draft exists`() {
        assertEquals(
            VoiceNoteDraftPhase.RECORDING,
            VoiceNoteDraftPolicy.startRecording(VoiceNoteDraftPhase.IDLE),
        )
        assertNull(VoiceNoteDraftPolicy.startRecording(VoiceNoteDraftPhase.RECORDING))
        assertNull(VoiceNoteDraftPolicy.startRecording(VoiceNoteDraftPhase.PAUSED))
        assertNull(VoiceNoteDraftPolicy.startRecording(VoiceNoteDraftPhase.PREVIEWING))
    }

    @Test
    fun `pausing is only meaningful while the microphone is live`() {
        assertEquals(
            VoiceNoteDraftPhase.PAUSED,
            VoiceNoteDraftPolicy.pause(VoiceNoteDraftPhase.RECORDING),
        )
        assertNull(VoiceNoteDraftPolicy.pause(VoiceNoteDraftPhase.IDLE))
        assertNull(VoiceNoteDraftPolicy.pause(VoiceNoteDraftPhase.PAUSED))
        assertNull(VoiceNoteDraftPolicy.pause(VoiceNoteDraftPhase.PREVIEWING))
    }

    @Test
    fun `a paused draft resumes, and hearing it first does not cost the resume`() {
        assertEquals(
            VoiceNoteDraftPhase.RECORDING,
            VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.PAUSED, recordedMillis = 5_000),
        )
        // Listen, decide to keep talking: the flow this whole feature exists for.
        assertEquals(
            VoiceNoteDraftPhase.RECORDING,
            VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.PREVIEWING, recordedMillis = 5_000),
        )
        assertNull(VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.IDLE, recordedMillis = 5_000))
        assertNull(
            VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.RECORDING, recordedMillis = 5_000),
        )
    }

    @Test
    fun `a draft at the maximum length never resumes`() {
        val max = VoiceNoteDraftPolicy.MAX_DURATION_MILLIS

        assertNull(VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.PAUSED, recordedMillis = max))
        assertNull(
            VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.PAUSED, recordedMillis = max + 1),
        )
        assertEquals(
            VoiceNoteDraftPhase.RECORDING,
            VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.PAUSED, recordedMillis = max - 1),
        )
    }

    @Test
    fun `listening back requires a paused draft with something to play`() {
        assertEquals(
            VoiceNoteDraftPhase.PREVIEWING,
            VoiceNoteDraftPolicy.beginPreview(VoiceNoteDraftPhase.PAUSED, hasSegments = true),
        )
        assertNull(
            VoiceNoteDraftPolicy.beginPreview(VoiceNoteDraftPhase.PAUSED, hasSegments = false),
        )
        assertNull(
            VoiceNoteDraftPolicy.beginPreview(VoiceNoteDraftPhase.RECORDING, hasSegments = true),
        )
        assertNull(
            VoiceNoteDraftPolicy.beginPreview(VoiceNoteDraftPhase.IDLE, hasSegments = true),
        )
        assertNull(
            VoiceNoteDraftPolicy.beginPreview(VoiceNoteDraftPhase.PREVIEWING, hasSegments = true),
        )
    }

    @Test
    fun `a preview ends back on the paused draft and nowhere else`() {
        assertEquals(
            VoiceNoteDraftPhase.PAUSED,
            VoiceNoteDraftPolicy.endPreview(VoiceNoteDraftPhase.PREVIEWING),
        )
        assertNull(VoiceNoteDraftPolicy.endPreview(VoiceNoteDraftPhase.IDLE))
        assertNull(VoiceNoteDraftPolicy.endPreview(VoiceNoteDraftPhase.RECORDING))
        assertNull(VoiceNoteDraftPolicy.endPreview(VoiceNoteDraftPhase.PAUSED))
    }

    @Test
    fun `send takes any draft that has reached the one second minimum`() {
        val min = VoiceNoteDraftPolicy.MIN_DURATION_MILLIS

        // From live recording, from paused, and from mid-listen alike.
        assertTrue(VoiceNoteDraftPolicy.sendable(VoiceNoteDraftPhase.RECORDING, min))
        assertTrue(VoiceNoteDraftPolicy.sendable(VoiceNoteDraftPhase.PAUSED, min))
        assertTrue(VoiceNoteDraftPolicy.sendable(VoiceNoteDraftPhase.PREVIEWING, min))

        assertFalse(VoiceNoteDraftPolicy.sendable(VoiceNoteDraftPhase.RECORDING, min - 1))
        // No draft, nothing to send, whatever a stale counter claims.
        assertFalse(VoiceNoteDraftPolicy.sendable(VoiceNoteDraftPhase.IDLE, min))
    }

    @Test
    fun `the cap pauses the draft instead of sending it`() {
        val max = VoiceNoteDraftPolicy.MAX_DURATION_MILLIS

        assertFalse(VoiceNoteDraftPolicy.capacityReached(max - 1))
        assertTrue(VoiceNoteDraftPolicy.capacityReached(max))
        assertTrue(VoiceNoteDraftPolicy.capacityReached(max + 1))
        // The capped draft stays a draft: still listenable, still explicitly the user's to
        // send or discard. There is no transition from the cap to "sent".
        assertNull(VoiceNoteDraftPolicy.resume(VoiceNoteDraftPhase.PAUSED, max))
        assertTrue(VoiceNoteDraftPolicy.sendable(VoiceNoteDraftPhase.PAUSED, max))
    }

    @Test
    fun `an ordinary interruption pauses a draft and never discards one`() {
        // Navigation, backgrounding, recomposition: the microphone stops, the audio stays.
        assertEquals(
            VoiceNoteDraftPhase.PAUSED,
            VoiceNoteDraftPolicy.phaseAfterInterruption(VoiceNoteDraftPhase.RECORDING),
        )
        assertEquals(
            VoiceNoteDraftPhase.PAUSED,
            VoiceNoteDraftPolicy.phaseAfterInterruption(VoiceNoteDraftPhase.PREVIEWING),
        )
        assertEquals(
            VoiceNoteDraftPhase.PAUSED,
            VoiceNoteDraftPolicy.phaseAfterInterruption(VoiceNoteDraftPhase.PAUSED),
        )
        assertEquals(
            VoiceNoteDraftPhase.IDLE,
            VoiceNoteDraftPolicy.phaseAfterInterruption(VoiceNoteDraftPhase.IDLE),
        )
    }

    @Test
    fun `every phase accepts exactly the transitions the table promises`() {
        // The exhaustive sweep: a phase added later without a decision fails here.
        val starts = VoiceNoteDraftPhase.entries
            .filter { VoiceNoteDraftPolicy.startRecording(it) != null }
        val pauses = VoiceNoteDraftPhase.entries
            .filter { VoiceNoteDraftPolicy.pause(it) != null }
        val resumes = VoiceNoteDraftPhase.entries
            .filter { VoiceNoteDraftPolicy.resume(it, recordedMillis = 5_000) != null }
        val previews = VoiceNoteDraftPhase.entries
            .filter { VoiceNoteDraftPolicy.beginPreview(it, hasSegments = true) != null }
        val endings = VoiceNoteDraftPhase.entries
            .filter { VoiceNoteDraftPolicy.endPreview(it) != null }

        assertEquals(listOf(VoiceNoteDraftPhase.IDLE), starts)
        assertEquals(listOf(VoiceNoteDraftPhase.RECORDING), pauses)
        assertEquals(listOf(VoiceNoteDraftPhase.PAUSED, VoiceNoteDraftPhase.PREVIEWING), resumes)
        assertEquals(listOf(VoiceNoteDraftPhase.PAUSED), previews)
        assertEquals(listOf(VoiceNoteDraftPhase.PREVIEWING), endings)
    }
}
