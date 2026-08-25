package com.kit.wallet

import com.kit.wallet.feature.chat.camera.CameraPull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCameraPullTest {

    @Test
    fun `upward leftover delta grows the reveal`() {
        assertEquals(35f, CameraPull.pull(revealPx = 10f, availableY = -25f, maxPx = 200f), EPSILON)
        assertEquals(40f, CameraPull.pull(revealPx = 0f, availableY = -40f, maxPx = 200f), EPSILON)
    }

    @Test
    fun `reveal clamps at maxPx`() {
        assertEquals(200f, CameraPull.pull(revealPx = 190f, availableY = -50f, maxPx = 200f), EPSILON)
        assertEquals(200f, CameraPull.pull(revealPx = 200f, availableY = -1f, maxPx = 200f), EPSILON)
    }

    @Test
    fun `downward or zero deltas leave the reveal unchanged`() {
        assertEquals(40f, CameraPull.pull(revealPx = 40f, availableY = 0f, maxPx = 200f), EPSILON)
        assertEquals(40f, CameraPull.pull(revealPx = 40f, availableY = 15f, maxPx = 200f), EPSILON)
    }

    @Test
    fun `zero or negative maxPx leaves the reveal unchanged`() {
        assertEquals(40f, CameraPull.pull(revealPx = 40f, availableY = -10f, maxPx = 0f), EPSILON)
        assertEquals(40f, CameraPull.pull(revealPx = 40f, availableY = -10f, maxPx = -5f), EPSILON)
    }

    @Test
    fun `collapse pays the reveal down before the list scrolls`() {
        val partial = CameraPull.collapse(revealPx = 30f, availableY = 12f)
        assertEquals(18f, partial.revealPx, EPSILON)
        assertEquals(12f, partial.consumedY, EPSILON)

        val exhausting = CameraPull.collapse(revealPx = 30f, availableY = 45f)
        assertEquals(0f, exhausting.revealPx, EPSILON)
        assertEquals(30f, exhausting.consumedY, EPSILON)
    }

    @Test
    fun `collapse is a no-op when there is no reveal or the delta is upward`() {
        val noReveal = CameraPull.collapse(revealPx = 0f, availableY = 20f)
        assertEquals(0f, noReveal.revealPx, EPSILON)
        assertEquals(0f, noReveal.consumedY, EPSILON)

        val upward = CameraPull.collapse(revealPx = 25f, availableY = -10f)
        assertEquals(25f, upward.revealPx, EPSILON)
        assertEquals(0f, upward.consumedY, EPSILON)

        val zeroDelta = CameraPull.collapse(revealPx = 25f, availableY = 0f)
        assertEquals(25f, zeroDelta.revealPx, EPSILON)
        assertEquals(0f, zeroDelta.consumedY, EPSILON)
    }

    @Test
    fun `shouldOpen is true only at or above the threshold`() {
        assertFalse(CameraPull.shouldOpen(revealPx = 99.9f, thresholdPx = 100f))
        assertTrue(CameraPull.shouldOpen(revealPx = 100f, thresholdPx = 100f))
        assertTrue(CameraPull.shouldOpen(revealPx = 150f, thresholdPx = 100f))
    }

    @Test
    fun `zero or negative thresholds never open`() {
        assertFalse(CameraPull.shouldOpen(revealPx = 500f, thresholdPx = 0f))
        assertFalse(CameraPull.shouldOpen(revealPx = 500f, thresholdPx = -1f))
    }

    @Test
    fun `crossing the threshold does not open the camera while a finger is still down`() {
        // The peek panel already promises "release to open the camera" at this point, so the
        // label and the behaviour have to agree: past the threshold, still pressed, still closed.
        assertTrue(CameraPull.shouldOpen(revealPx = 250f, thresholdPx = 120f))
        assertFalse(
            CameraPull.shouldOpenOnRelease(
                revealPx = 250f,
                thresholdPx = 120f,
                pointerDown = true,
            ),
        )
    }

    @Test
    fun `releasing past the threshold opens the camera`() {
        assertTrue(
            CameraPull.shouldOpenOnRelease(
                revealPx = 120f,
                thresholdPx = 120f,
                pointerDown = false,
            ),
        )
    }

    @Test
    fun `releasing below the threshold does not open the camera`() {
        assertFalse(
            CameraPull.shouldOpenOnRelease(
                revealPx = 119.9f,
                thresholdPx = 120f,
                pointerDown = false,
            ),
        )
    }

    @Test
    fun `a full drag only opens at the moment of release`() {
        val maxPx = 300f
        val thresholdPx = 120f
        var reveal = 0f
        var opened = 0

        // Every intermediate sample of a single continuous drag, with the finger still down.
        for (delta in listOf(-40f, -50f, -35f, -60f, -80f)) {
            reveal = CameraPull.pull(reveal, availableY = delta, maxPx = maxPx)
            if (CameraPull.shouldOpenOnRelease(reveal, thresholdPx, pointerDown = true)) opened++
        }
        assertEquals(265f, reveal, EPSILON)
        assertEquals(0, opened)

        // The finger lifts.
        if (CameraPull.shouldOpenOnRelease(reveal, thresholdPx, pointerDown = false)) opened++
        assertEquals(1, opened)
    }

    @Test
    fun `a realistic pull, partial collapse and second pull sequence`() {
        val maxPx = 300f
        val thresholdPx = 120f

        var reveal = 0f
        reveal = CameraPull.pull(reveal, availableY = -40f, maxPx = maxPx)
        reveal = CameraPull.pull(reveal, availableY = -50f, maxPx = maxPx)
        reveal = CameraPull.pull(reveal, availableY = -35f, maxPx = maxPx)
        assertEquals(125f, reveal, EPSILON)

        // The finger reverses: the reveal absorbs the downward scroll before the list moves.
        val collapse = CameraPull.collapse(reveal, availableY = 20f)
        assertEquals(20f, collapse.consumedY, EPSILON)
        reveal = collapse.revealPx
        assertEquals(105f, reveal, EPSILON)

        // Releasing below the threshold does not open the camera.
        assertFalse(CameraPull.shouldOpen(reveal, thresholdPx))

        // Pulling further past the threshold does.
        reveal = CameraPull.pull(reveal, availableY = -30f, maxPx = maxPx)
        assertTrue(CameraPull.shouldOpen(reveal, thresholdPx))
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
