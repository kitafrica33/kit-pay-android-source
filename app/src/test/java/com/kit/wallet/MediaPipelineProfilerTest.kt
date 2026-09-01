package com.kit.wallet

import com.kit.wallet.data.messaging.MediaPipelineMeasurement
import com.kit.wallet.data.messaging.MediaPipelineMilestone
import com.kit.wallet.data.messaging.MediaPipelineProfiler
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPipelineProfilerTest {
    @Test
    fun `measurement starts at capture boundary supplied by source`() {
        var now = 8_000_000_000L
        val measurements = mutableListOf<MediaPipelineMeasurement>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { now },
            emit = { _, measurement -> measurements += measurement },
        )

        profiler.begin("media", "video/mp4", originatedAtNanos = 5_000_000_000L)
        now = 8_250_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE)

        assertEquals(
            MediaPipelineMeasurement(MediaPipelineMilestone.LOCAL_PLAYABLE, 3_250),
            measurements.single(),
        )
    }

    @Test
    fun `future origin is clamped instead of emitting negative time`() {
        var now = 2_000_000L
        val measurements = mutableListOf<MediaPipelineMeasurement>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { now },
            emit = { _, measurement -> measurements += measurement },
        )

        profiler.begin("media", "image/jpeg", originatedAtNanos = Long.MAX_VALUE)
        now += 1_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PROJECTION_READY)

        assertEquals(1L, measurements.single().elapsedMillis)
    }

    @Test
    fun `each milestone emits only its first observation`() {
        var now = 1_000_000L
        val measurements = mutableListOf<MediaPipelineMeasurement>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { now },
            emit = { _, measurement -> measurements += measurement },
        )
        profiler.begin("media", "audio/mp4")

        now = 2_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE)
        now = 9_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE)

        assertEquals(
            listOf(MediaPipelineMeasurement(MediaPipelineMilestone.LOCAL_PLAYABLE, 1)),
            measurements,
        )
    }

    @Test
    fun `unfinished measurements are bounded and evict the oldest`() {
        var now = 1_000_000L
        val observedKinds = mutableListOf<String>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { now },
            emit = { kind, _ -> observedKinds += kind },
        )

        repeat(257) { index ->
            profiler.begin("media-$index", "kind-$index/file")
            now += 1_000_000L
        }
        profiler.mark("media-0", MediaPipelineMilestone.LOCAL_PLAYABLE)
        profiler.mark("media-256", MediaPipelineMilestone.LOCAL_PLAYABLE)

        assertEquals(listOf("kind-256"), observedKinds)
    }
}
