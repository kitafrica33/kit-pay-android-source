package com.kit.wallet

import com.kit.wallet.data.messaging.MediaPipelineDiagnosticJournal
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

        profiler.begin(
            "media",
            "video/mp4",
            originatedAtNanos = 5_000_000_000L,
            ownerScopeId = OWNER_A,
        )
        now = 8_250_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)

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

        profiler.begin(
            "media",
            "image/jpeg",
            originatedAtNanos = Long.MAX_VALUE,
            ownerScopeId = OWNER_A,
        )
        now += 1_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PROJECTION_READY, OWNER_A)

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
        profiler.begin("media", "audio/mp4", ownerScopeId = OWNER_A)

        now = 2_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)
        now = 9_000_000L
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)

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
            profiler.begin("media-$index", "video/mp4", ownerScopeId = OWNER_A)
            now += 1_000_000L
        }
        profiler.mark("media-0", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)
        profiler.mark("media-256", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)

        assertEquals(listOf("video"), observedKinds)
    }

    @Test
    fun `late prior owner callbacks cannot suppress or remove same id for replacement owner`() {
        val observedKinds = mutableListOf<String>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { 2_000_000L },
            emit = { kind, _ -> observedKinds += kind },
        )

        profiler.begin("same-media-id", "image/jpeg", ownerScopeId = OWNER_A)
        profiler.begin("same-media-id", "video/mp4", ownerScopeId = OWNER_B)

        // Both operations arrive after B has started the same client-generated ID. They may touch
        // A's measurement only; in particular A's forget must not remove B's active measurement.
        profiler.mark(
            "same-media-id",
            MediaPipelineMilestone.LOCAL_PROJECTION_READY,
            OWNER_A,
        )
        profiler.forget("same-media-id", OWNER_A)
        profiler.mark("same-media-id", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_B)
        profiler.mark("same-media-id", MediaPipelineMilestone.ENCRYPTED, OWNER_A)

        assertEquals(listOf("image", "video"), observedKinds)
    }

    @Test
    fun `blank diagnostic identifiers are no ops that cannot mutate valid tracking`() {
        val observedKinds = mutableListOf<String>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { 2_000_000L },
            emit = { kind, _ -> observedKinds += kind },
        )
        profiler.begin("tracked", "image/jpeg", ownerScopeId = OWNER_A)

        // If either invalid dimension were tracked, these distinct composite keys would evict the
        // valid oldest measurement at the profiler's fixed capacity.
        repeat(256) { index ->
            profiler.begin("invalid-$index", "video/mp4", ownerScopeId = " ")
            profiler.begin(" ", "video/mp4", ownerScopeId = "owner-$index")
        }
        profiler.mark("tracked", MediaPipelineMilestone.LOCAL_PLAYABLE, " ")
        profiler.forget("tracked", " ")
        profiler.mark(" ", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)
        profiler.forget(" ", OWNER_A)

        profiler.mark("tracked", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)

        assertEquals(listOf("image"), observedKinds)
    }

    @Test
    fun `throwing emit cannot escape mark or change duplicate and upload semantics`() {
        var emitCalls = 0
        val profiler = MediaPipelineProfiler(
            nanoTime = { 2_000_000L },
            emit = { _, _ ->
                emitCalls++
                error("diagnostic sink unavailable")
            },
        )
        profiler.begin("media", "video/mp4", ownerScopeId = OWNER_A)

        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)
        profiler.mark("media", MediaPipelineMilestone.UPLOADED, OWNER_A)
        profiler.mark("media", MediaPipelineMilestone.ENCRYPTED, OWNER_A)

        assertEquals(2, emitCalls)
    }

    @Test
    fun `throwing journal persistence cannot escape mark or suppress emit`() {
        var writes = 0
        val journal = MediaPipelineDiagnosticJournal(
            readPersisted = { null },
            writePersisted = {
                writes++
                error("diagnostic storage unavailable")
            },
            clearPersisted = { true },
            currentOwnerScopeId = { OWNER_A },
        )
        val observedMilestones = mutableListOf<MediaPipelineMilestone>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { 2_000_000L },
            emit = { _, measurement -> observedMilestones += measurement.milestone },
            diagnosticJournal = journal,
        )
        profiler.begin("media", "audio/mp4", ownerScopeId = OWNER_A)

        profiler.mark("media", MediaPipelineMilestone.UPLOADED, OWNER_A)
        profiler.mark("media", MediaPipelineMilestone.LOCAL_PLAYABLE, OWNER_A)

        assertEquals(1, writes)
        assertEquals(listOf(MediaPipelineMilestone.UPLOADED), observedMilestones)
    }

    private companion object {
        const val OWNER_A = "owner-a:session-a"
        const val OWNER_B = "owner-b:session-b"
    }
}
