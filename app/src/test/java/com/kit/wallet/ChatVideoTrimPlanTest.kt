package com.kit.wallet

import com.kit.wallet.feature.chat.camera.MIN_CLIP_MILLIS
import com.kit.wallet.feature.chat.camera.VideoTrimPlan
import com.kit.wallet.feature.chat.camera.planVideoTrim
import com.kit.wallet.feature.chat.camera.videoSendMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVideoTrimPlanTest {
    @Test
    fun `end past the track duration clamps to the track end`() {
        val plan = planVideoTrim(
            requestedStartMillis = 1_000,
            requestedEndMillis = 90_000,
            trackDurationMillis = 4_000,
            keepAudio = true,
        )

        assertNotNull(plan)
        assertEquals(1_000_000L, plan!!.startMicros)
        assertEquals(4_000_000L, plan.endMicros)
    }

    @Test
    fun `negative start clamps to zero`() {
        val plan = planVideoTrim(
            requestedStartMillis = -3_000,
            requestedEndMillis = 2_000,
            trackDurationMillis = 5_000,
            keepAudio = true,
        )

        assertNotNull(plan)
        assertEquals(0L, plan!!.startMicros)
        assertEquals(2_000_000L, plan.endMicros)
    }

    @Test
    fun `window shorter than the minimum clip returns null`() {
        assertNull(
            planVideoTrim(
                requestedStartMillis = 1_000,
                requestedEndMillis = 1_000 + MIN_CLIP_MILLIS - 1,
                trackDurationMillis = 5_000,
                keepAudio = true,
            ),
        )
        assertNotNull(
            planVideoTrim(
                requestedStartMillis = 1_000,
                requestedEndMillis = 1_000 + MIN_CLIP_MILLIS,
                trackDurationMillis = 5_000,
                keepAudio = true,
            ),
        )
    }

    @Test
    fun `zero or negative track duration returns null`() {
        assertNull(planVideoTrim(0, 2_000, 0, keepAudio = true))
        assertNull(planVideoTrim(0, 2_000, -1, keepAudio = true))
    }

    @Test
    fun `keepAudio passes through to the plan`() {
        assertTrue(planVideoTrim(0, 2_000, 5_000, keepAudio = true)!!.keepAudio)
        assertFalse(planVideoTrim(0, 2_000, 5_000, keepAudio = false)!!.keepAudio)
    }

    @Test
    fun `every video wire representation is a real canonical MP4`() {
        // The retained sender original keeps its own bytes. The background plan content-validates
        // and remuxes both untouched and edited sends before this wire label is used.
        assertEquals("video/mp4", videoSendMediaType())
    }

    @Test
    fun `durationMicros spans the window and never goes negative`() {
        val plan = planVideoTrim(
            requestedStartMillis = 1_500,
            requestedEndMillis = 4_000,
            trackDurationMillis = 10_000,
            keepAudio = false,
        )

        assertEquals(2_500_000L, plan!!.durationMicros)
        assertEquals(0L, VideoTrimPlan(startMicros = 5_000_000, endMicros = 4_000_000, keepAudio = false).durationMicros)
    }
}
