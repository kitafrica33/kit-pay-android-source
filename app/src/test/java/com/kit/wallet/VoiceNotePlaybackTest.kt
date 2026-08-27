package com.kit.wallet

import com.kit.wallet.feature.chat.VoiceNoteChatContext
import com.kit.wallet.feature.chat.VoiceNoteMiniBarPolicy
import com.kit.wallet.feature.chat.VoiceNotePlaybackContext
import com.kit.wallet.feature.chat.VoiceNoteSeekPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finger on a voice note means, and when the floating bar is the thing that owns it.
 *
 * The counterpart of iOS `VoiceNotePlaybackTests`, case for case, so a note seeks the same distance
 * from the same gesture on either platform.
 */
class VoiceNotePlaybackTest {

    private val width = VoiceNoteSeekPolicy.WAVEFORM_WIDTH

    // MARK: Tap vs slide vs scroll

    @Test
    fun `a stationary touch is a tap`() {
        assertTrue(VoiceNoteSeekPolicy.isTap(0f, 0f))
        assertTrue(VoiceNoteSeekPolicy.isTap(3f, -4f))
        assertFalse(VoiceNoteSeekPolicy.isScrub(3f, -4f))
    }

    @Test
    fun `a horizontal slide past the slop is a scrub`() {
        assertFalse(VoiceNoteSeekPolicy.isTap(40f, 6f))
        assertTrue(VoiceNoteSeekPolicy.isScrub(40f, 6f))
        assertTrue(
            "Sliding back down the note is as much a scrub as sliding forward",
            VoiceNoteSeekPolicy.isScrub(-40f, 6f),
        )
    }

    /**
     * The waveform's gesture runs alongside the thread's scrolling. A drag that is mostly vertical
     * is the thread being scrolled past the note, and must not move playback.
     */
    @Test
    fun `a mostly vertical drag is not a seek`() {
        assertFalse(VoiceNoteSeekPolicy.isTap(12f, 90f))
        assertFalse(VoiceNoteSeekPolicy.isScrub(12f, 90f))
    }

    // MARK: Where a touch lands

    @Test
    fun `a tap positions the note at the fraction of the waveform touched`() {
        assertEquals(0f, VoiceNoteSeekPolicy.fractionAtX(0f, width), 0.0001f)
        assertEquals(0.5f, VoiceNoteSeekPolicy.fractionAtX(width / 2f, width), 0.0001f)
        assertEquals(1f, VoiceNoteSeekPolicy.fractionAtX(width, width), 0.0001f)
    }

    @Test
    fun `a tap outside the waveform rests at the nearest end`() {
        assertEquals(0f, VoiceNoteSeekPolicy.fractionAtX(-40f, width), 0.0001f)
        assertEquals(1f, VoiceNoteSeekPolicy.fractionAtX(width + 40f, width), 0.0001f)
    }

    @Test
    fun `an unmeasured waveform never divides by zero`() {
        assertEquals(0f, VoiceNoteSeekPolicy.fractionAtX(30f, 0f), 0.0001f)
        assertEquals(0.4f, VoiceNoteSeekPolicy.scrubbedFraction(0.4f, 30f, 0f), 0.0001f)
    }

    // MARK: Scrubbing

    /**
     * A slide moves the note relative to where the finger went down, so a half-played note nudged a
     * quarter of the waveform forward lands three-quarters in — not at the quarter mark.
     */
    @Test
    fun `a slide moves relative to where the finger went down`() {
        assertEquals(0.75f, VoiceNoteSeekPolicy.scrubbedFraction(0.5f, width / 4f, width), 0.0001f)
        assertEquals(0.25f, VoiceNoteSeekPolicy.scrubbedFraction(0.5f, -width / 4f, width), 0.0001f)
    }

    @Test
    fun `a slide past either end rests at that end`() {
        assertEquals(1f, VoiceNoteSeekPolicy.scrubbedFraction(0.9f, width, width), 0.0001f)
        assertEquals(0f, VoiceNoteSeekPolicy.scrubbedFraction(0.1f, -width, width), 0.0001f)
    }

    @Test
    fun `a non finite fraction collapses to the start`() {
        // A non-finite fraction is garbage, never a real gesture: seeking to the end would silently
        // skip the message, so both collapse to the start.
        assertEquals(0f, VoiceNoteSeekPolicy.clamped(Float.NaN), 0.0001f)
        assertEquals(0f, VoiceNoteSeekPolicy.clamped(Float.POSITIVE_INFINITY), 0.0001f)
    }

    // MARK: Fraction to time

    @Test
    fun `fraction and time agree`() {
        assertEquals(10_000L, VoiceNoteSeekPolicy.timeForFraction(0.25f, 40_000L))
        assertEquals(0.25f, VoiceNoteSeekPolicy.fractionForTime(10_000L, 40_000L), 0.0001f)
    }

    @Test
    fun `skipping past either end of the note is clamped`() {
        assertEquals(0f, VoiceNoteSeekPolicy.fractionForTime(-15_000L, 40_000L), 0.0001f)
        assertEquals(1f, VoiceNoteSeekPolicy.fractionForTime(90_000L, 40_000L), 0.0001f)
    }

    /** A note whose duration is not known yet must not turn a seek into a division by zero. */
    @Test
    fun `an unknown duration seeks nowhere`() {
        assertEquals(0L, VoiceNoteSeekPolicy.timeForFraction(0.5f, 0L))
        assertEquals(0f, VoiceNoteSeekPolicy.fractionForTime(12_000L, 0L), 0.0001f)
    }

    // MARK: What the floating bar says

    @Test
    fun `a group note names the speaker over the group`() {
        val context = VoiceNotePlaybackContext(
            conversationId = "c1",
            speaker = "Ama",
            conversationTitle = "Family",
        )
        assertEquals("Ama", context.title)
        assertEquals("Family", context.subtitle)
    }

    /**
     * A one-to-one chat is titled after the person speaking, so repeating the name underneath would
     * read as a stutter.
     */
    @Test
    fun `a direct chat does not repeat the speakers name`() {
        val context = VoiceNotePlaybackContext(
            conversationId = "c1",
            speaker = "Ama",
            conversationTitle = "Ama",
        )
        assertEquals("Ama", context.title)
        assertEquals("Voice note", context.subtitle)
    }

    @Test
    fun `an unnamed context still reads as a voice note`() {
        val context = VoiceNotePlaybackContext()
        assertEquals("Voice note", context.title)
        assertEquals("Voice note", context.subtitle)
    }

    @Test
    fun `the chat context resolves the speaker through the thread`() {
        val context = VoiceNoteChatContext(
            conversationId = "c1",
            conversationTitle = "Family",
            displayName = { if (it == "me") "You" else "Ama" },
        )
        assertEquals("You", context.playbackContext("me").title)
        assertEquals("Ama", context.playbackContext("other").title)
        assertEquals("Family", context.playbackContext("other").subtitle)
    }

    // MARK: When the bar is on screen

    @Test
    fun `the bar stays away while the notes own bubble is visible`() {
        assertFalse(VoiceNoteMiniBarPolicy.isVisible(hasPlayback = true, isSourceOnScreen = true))
    }

    @Test
    fun `the bar takes over once the bubble is gone`() {
        assertTrue(VoiceNoteMiniBarPolicy.isVisible(hasPlayback = true, isSourceOnScreen = false))
    }

    @Test
    fun `nothing playing shows no bar`() {
        assertFalse(VoiceNoteMiniBarPolicy.isVisible(hasPlayback = false, isSourceOnScreen = false))
        assertFalse(VoiceNoteMiniBarPolicy.isVisible(hasPlayback = false, isSourceOnScreen = true))
    }
}
