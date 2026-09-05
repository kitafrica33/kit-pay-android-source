package com.kit.wallet.feature.chat

import com.kit.wallet.data.session.SessionFence

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Who is speaking and where, kept alongside a playing voice note so the floating bar can name it
 * after the bubble that started it has scrolled away or the chat has been left entirely.
 *
 * Rule for rule the same as iOS `VoiceNotePlaybackContext`.
 */
internal data class VoiceNotePlaybackContext(
    /**
     * Conversation the note belongs to. Playback is not resumable across conversations, so this is
     * identity only — the bar never fetches with it.
     */
    val conversationId: String = "",
    /** Speaker, already resolved to a display name ("You", a contact, or a neutral fallback). */
    val speaker: String = "",
    /**
     * Chat the note was sent in, shown under the speaker so a note playing after the user has left
     * the thread still says where it came from.
     */
    val conversationTitle: String = "",
    val sessionOwner: SessionFence? = null,
) {
    /** What the floating bar puts on its first line: who is speaking, never the note itself. */
    val title: String get() = speaker.ifBlank { "Voice note" }

    /**
     * The bar's second line. A one-to-one chat already says who the speaker is in its title, so
     * repeating it underneath would read as a stutter.
     */
    val subtitle: String
        get() = if (conversationTitle.isBlank() || conversationTitle == title) {
            "Voice note"
        } else {
            conversationTitle
        }
}

/**
 * The conversation a voice-note bubble is being drawn in.
 *
 * Held by the conversation screen rather than passed down every media composable, because only the
 * thread can resolve a sender id to "You", a member's name, or a neutral fallback.
 */
internal data class VoiceNoteChatContext(
    val conversationId: String = "",
    val conversationTitle: String = "",
    val displayName: (String) -> String = { "Kit Pay user" },
    val sessionOwner: SessionFence? = null,
) {
    fun playbackContext(senderUserId: String?): VoiceNotePlaybackContext = VoiceNotePlaybackContext(
        conversationId = conversationId,
        speaker = displayName(senderUserId.orEmpty()),
        conversationTitle = conversationTitle,
        sessionOwner = sessionOwner,
    )
}

/**
 * Reading a finger on a voice note's waveform.
 *
 * A tap positions playback at the point touched; a horizontal slide scrubs relative to where the
 * finger went down, so a long note can be nudged a second at a time instead of only jumped to. A
 * mostly-vertical drag is the thread being scrolled and is deliberately not a seek — the waveform's
 * gesture runs *alongside* the list's scrolling rather than replacing it.
 */
internal object VoiceNoteSeekPolicy {
    /**
     * Drawn width of the in-bubble waveform, in dp. One waveform-width of travel is the whole note,
     * so the gesture's sensitivity is stated here rather than derived from a measured frame.
     */
    const val WAVEFORM_WIDTH = 138f

    /** Movement under this is a stationary tap, not a slide. */
    const val TAP_SLOP = 6f

    fun isTap(translationX: Float, translationY: Float): Boolean =
        abs(translationX) < TAP_SLOP && abs(translationY) < TAP_SLOP

    /** A slide the note should follow: past the tap slop and more horizontal than vertical. */
    fun isScrub(translationX: Float, translationY: Float): Boolean =
        !isTap(translationX, translationY) && abs(translationX) > abs(translationY)

    /** Absolute position of a tap inside a waveform of [width]. */
    fun fractionAtX(x: Float, width: Float): Float {
        if (width <= 0f) return 0f
        return clamped(x / width)
    }

    /**
     * Position a slide has reached, measured from where the finger went down. Full-width travel
     * covers the whole note in either direction.
     */
    fun scrubbedFraction(start: Float, translationX: Float, width: Float): Float {
        if (width <= 0f) return clamped(start)
        return clamped(start + translationX / width)
    }

    fun clamped(fraction: Float): Float {
        if (!fraction.isFinite()) return 0f
        return min(1f, max(0f, fraction))
    }

    /** Milliseconds a fraction of a note of [durationMillis] corresponds to. */
    fun timeForFraction(fraction: Float, durationMillis: Long): Long {
        if (durationMillis <= 0) return 0
        return (clamped(fraction) * durationMillis).toLong()
    }

    fun fractionForTime(timeMillis: Long, durationMillis: Long): Float {
        if (durationMillis <= 0) return 0f
        return clamped(timeMillis.toFloat() / durationMillis)
    }
}

/**
 * When the floating voice-note bar is on screen.
 *
 * The bar exists for a note that is still playing somewhere the user can no longer see or stop it:
 * scrolled past in the thread, or left behind entirely. While the bubble that owns the note is
 * visible the bubble *is* the control, so a second one on top of it would be noise.
 */
internal object VoiceNoteMiniBarPolicy {
    /**
     * Height of the bar's content, matching the minimized call strip so the two surfaces push the
     * conversation down identically.
     */
    const val CONTENT_HEIGHT_DP = 60

    /**
     * Breathing space between the bar and whatever the app draws under it. Without it the screen's
     * first row sits hard against the bar's bottom edge and the two read as one squeezed block.
     */
    const val CONTENT_GAP_DP = 8

    fun isVisible(hasPlayback: Boolean, isSourceOnScreen: Boolean): Boolean =
        hasPlayback && !isSourceOnScreen
}
