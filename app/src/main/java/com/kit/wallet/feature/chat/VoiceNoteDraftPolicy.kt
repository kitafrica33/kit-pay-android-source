package com.kit.wallet.feature.chat

import com.kit.wallet.data.messaging.KitChatMediaLimits

/**
 * Phases of one voice-note draft. A draft is plaintext audio that exists only on this
 * device: nothing about it is encrypted, uploaded, or sent until the user presses Send,
 * and only an explicit discard throws it away.
 */
internal enum class VoiceNoteDraftPhase {
    /** No draft. The composer shows the ordinary message field. */
    IDLE,

    /** The microphone is capturing an active segment. */
    RECORDING,

    /**
     * Capture is stopped mid-draft. What exists so far is a row of finalized, individually
     * playable segments; the user may listen back, resume recording, send, or discard.
     */
    PAUSED,

    /** Playing the draft back locally, from [PAUSED]. Nothing leaves the device. */
    PREVIEWING,
}

/**
 * The transition table for a voice-note draft, kept pure so every row is pinned by a unit
 * test rather than by a microphone.
 *
 * Durations are the caller's fact (the recorder owns the segment files and their summed
 * length); this object owns only what is allowed to happen next. Every transition function
 * returns the next phase, or null when the request is not valid from the current phase —
 * a null is a refused transition, never an error state.
 */
internal object VoiceNoteDraftPolicy {
    const val MIN_DURATION_MILLIS = KitChatMediaLimits.VOICE_NOTE_MIN_DURATION_MILLIS
    const val MAX_DURATION_MILLIS = KitChatMediaLimits.VOICE_NOTE_MAX_DURATION_MILLIS

    /** A fresh recording may only begin when no draft exists. */
    fun startRecording(phase: VoiceNoteDraftPhase): VoiceNoteDraftPhase? =
        if (phase == VoiceNoteDraftPhase.IDLE) VoiceNoteDraftPhase.RECORDING else null

    /** Pausing is only meaningful while the microphone is live. */
    fun pause(phase: VoiceNoteDraftPhase): VoiceNoteDraftPhase? =
        if (phase == VoiceNoteDraftPhase.RECORDING) VoiceNoteDraftPhase.PAUSED else null

    /**
     * Resuming appends a new segment to the paused draft. Allowed from a preview too —
     * hearing the draft and continuing it is the whole flow this exists for — but never
     * once the draft has reached the maximum length a note may be.
     */
    fun resume(phase: VoiceNoteDraftPhase, recordedMillis: Long): VoiceNoteDraftPhase? =
        if (phase in setOf(VoiceNoteDraftPhase.PAUSED, VoiceNoteDraftPhase.PREVIEWING) &&
            recordedMillis < MAX_DURATION_MILLIS
        ) {
            VoiceNoteDraftPhase.RECORDING
        } else {
            null
        }

    /** Listening back requires a paused draft with at least one finalized segment. */
    fun beginPreview(phase: VoiceNoteDraftPhase, hasSegments: Boolean): VoiceNoteDraftPhase? =
        if (phase == VoiceNoteDraftPhase.PAUSED && hasSegments) {
            VoiceNoteDraftPhase.PREVIEWING
        } else {
            null
        }

    /** A finished or interrupted preview settles back onto the paused draft. */
    fun endPreview(phase: VoiceNoteDraftPhase): VoiceNoteDraftPhase? =
        if (phase == VoiceNoteDraftPhase.PREVIEWING) VoiceNoteDraftPhase.PAUSED else null

    /**
     * Whether Send may take the draft right now. Sending is allowed while still recording
     * — the tap finalizes the active segment on its way out — as long as the draft has
     * reached the one-second minimum a note must be.
     */
    fun sendable(phase: VoiceNoteDraftPhase, recordedMillis: Long): Boolean =
        phase != VoiceNoteDraftPhase.IDLE && recordedMillis >= MIN_DURATION_MILLIS

    /**
     * What a live recording does when it reaches the maximum length: it pauses. It used to
     * send itself, but a send the user never asked for sits badly with a draft flow whose
     * whole point is that encryption and upload happen strictly at Send — the capped draft
     * stays local, listenable, and explicitly theirs to send or discard.
     */
    fun capacityReached(recordedMillis: Long): Boolean = recordedMillis >= MAX_DURATION_MILLIS

    /**
     * What an ordinary UI interruption — navigation, backgrounding, recomposition — does
     * to each phase. Live capture pauses (the microphone must not keep running behind the
     * user's back, and a finalized segment survives anything short of process death); a
     * preview stops for the same reason; a paused draft is simply kept. Nothing here ever
     * discards: only the user does that, explicitly.
     */
    fun phaseAfterInterruption(phase: VoiceNoteDraftPhase): VoiceNoteDraftPhase =
        when (phase) {
            VoiceNoteDraftPhase.IDLE -> VoiceNoteDraftPhase.IDLE
            VoiceNoteDraftPhase.RECORDING,
            VoiceNoteDraftPhase.PAUSED,
            VoiceNoteDraftPhase.PREVIEWING,
            -> VoiceNoteDraftPhase.PAUSED
        }
}
