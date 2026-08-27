package com.kit.wallet

import com.kit.wallet.feature.chat.SwipeToReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSwipeToReplyTest {

    @Test
    fun `travel follows the finger one-for-one up to full travel`() {
        assertEquals(0f, SwipeToReply.travel(dragPx = 0f, maxPx = 200f), EPSILON)
        assertEquals(45f, SwipeToReply.travel(dragPx = 45f, maxPx = 200f), EPSILON)
        assertEquals(200f, SwipeToReply.travel(dragPx = 200f, maxPx = 200f), EPSILON)
    }

    @Test
    fun `dragging the other way travels the other way by the same amount`() {
        assertEquals(-45f, SwipeToReply.travel(dragPx = -45f, maxPx = 200f), EPSILON)
        assertEquals(-200f, SwipeToReply.travel(dragPx = -200f, maxPx = 200f), EPSILON)
        // Neither direction is privileged: the two are mirror images at every distance.
        assertEquals(
            SwipeToReply.travel(dragPx = 137f, maxPx = 200f),
            -SwipeToReply.travel(dragPx = -137f, maxPx = 200f),
            EPSILON,
        )
    }

    @Test
    fun `past full travel the bubble barely moves and then stops`() {
        // 100px further than full travel moves the bubble 18 more.
        assertEquals(218f, SwipeToReply.travel(dragPx = 300f, maxPx = 200f), EPSILON)
        // However far the finger goes, the row never gives up more than a fifth again.
        assertEquals(240f, SwipeToReply.travel(dragPx = 5_000f, maxPx = 200f), EPSILON)
        assertEquals(-240f, SwipeToReply.travel(dragPx = -5_000f, maxPx = 200f), EPSILON)
    }

    @Test
    fun `zero or negative full travel keeps the bubble still`() {
        assertEquals(0f, SwipeToReply.travel(dragPx = 500f, maxPx = 0f), EPSILON)
        assertEquals(0f, SwipeToReply.travel(dragPx = 500f, maxPx = -10f), EPSILON)
    }

    @Test
    fun `shouldReply is true only at or beyond the trigger, either way`() {
        assertFalse(SwipeToReply.shouldReply(travelPx = 51.9f, triggerPx = 52f))
        assertTrue(SwipeToReply.shouldReply(travelPx = 52f, triggerPx = 52f))
        assertTrue(SwipeToReply.shouldReply(travelPx = 90f, triggerPx = 52f))
        assertFalse(SwipeToReply.shouldReply(travelPx = -51.9f, triggerPx = 52f))
        assertTrue(SwipeToReply.shouldReply(travelPx = -52f, triggerPx = 52f))
    }

    @Test
    fun `zero or negative triggers never answer`() {
        assertFalse(SwipeToReply.shouldReply(travelPx = 900f, triggerPx = 0f))
        assertFalse(SwipeToReply.shouldReply(travelPx = 900f, triggerPx = -1f))
    }

    @Test
    fun `the arrow completes exactly when releasing would answer`() {
        val triggerPx = 52f
        assertEquals(0f, SwipeToReply.progress(travelPx = 0f, triggerPx = triggerPx), EPSILON)
        assertEquals(0.5f, SwipeToReply.progress(travelPx = 26f, triggerPx = triggerPx), EPSILON)
        assertEquals(1f, SwipeToReply.progress(travelPx = 52f, triggerPx = triggerPx), EPSILON)
        // Dragging on past the line cannot make the arrow more than finished.
        assertEquals(1f, SwipeToReply.progress(travelPx = 300f, triggerPx = triggerPx), EPSILON)
        // A leftward drag reads the same, because the arrow only shows how far along the gesture is.
        assertEquals(0.5f, SwipeToReply.progress(travelPx = -26f, triggerPx = triggerPx), EPSILON)

        // The two never disagree at the boundary, in either direction.
        listOf(-300f, -52f, -51.99f, 0f, 51.99f, 52f, 300f).forEach { travel ->
            assertEquals(
                "travel=$travel",
                SwipeToReply.shouldReply(travel, triggerPx),
                SwipeToReply.progress(travel, triggerPx) >= 1f,
            )
        }
    }

    @Test
    fun `zero triggers leave the arrow hidden rather than dividing by zero`() {
        assertEquals(0f, SwipeToReply.progress(travelPx = 900f, triggerPx = 0f), EPSILON)
        assertEquals(0f, SwipeToReply.progress(travelPx = 900f, triggerPx = -5f), EPSILON)
    }

    @Test
    fun `a full drag crosses the line once and answers on release`() {
        val maxPx = 68f * 3
        val triggerPx = 52f * 3

        // A single continuous drag, sampled the way the gesture detector reports it: the running
        // total of finger movement, not per-frame deltas re-eased against themselves.
        var dragged = 0f
        var armings = 0
        var armed = false
        val states = listOf(18f, 26f, 40f, 55f, 90f, 120f).map { delta ->
            dragged += delta
            val travel = SwipeToReply.travel(dragged, maxPx)
            val past = SwipeToReply.shouldReply(travel, triggerPx)
            if (past != armed) {
                armed = past
                if (past) armings++
            }
            past
        }

        assertEquals(listOf(false, false, false, false, true, true), states)
        // One buzz for the whole gesture, at the moment it became an answer.
        assertEquals(1, armings)
        assertTrue(SwipeToReply.shouldReply(SwipeToReply.travel(dragged, maxPx), triggerPx))
    }

    @Test
    fun `second thoughts on the way back leave the message unanswered`() {
        val maxPx = 204f
        val triggerPx = 156f

        var dragged = 180f
        assertTrue(SwipeToReply.shouldReply(SwipeToReply.travel(dragged, maxPx), triggerPx))

        // The finger comes back before lifting, so the gesture is abandoned and nothing is quoted.
        dragged -= 60f
        assertFalse(SwipeToReply.shouldReply(SwipeToReply.travel(dragged, maxPx), triggerPx))
        assertEquals(
            0.769f,
            SwipeToReply.progress(SwipeToReply.travel(dragged, maxPx), triggerPx),
            1e-3f,
        )
    }

    @Test
    fun `the shipped trigger is reached before the row runs out of travel`() {
        // Otherwise the gesture could never be completed: the bubble would stop moving short of
        // the distance that answers it, and the arrow would never finish filling in.
        assertTrue(SwipeToReply.TRIGGER_DP < SwipeToReply.MAX_TRAVEL_DP)
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
