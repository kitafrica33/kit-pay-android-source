package com.kit.wallet

import com.kit.wallet.data.messaging.MediaPipelineDiagnosticJournal
import com.kit.wallet.data.messaging.MediaPipelineMeasurement
import com.kit.wallet.data.messaging.MediaPipelineMilestone
import com.kit.wallet.data.messaging.MediaPipelineProfiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipelineDiagnosticJournalTest {
    @Test
    fun `journal retains a fixed number of owner-scoped bucketed measurements`() {
        var persisted: String? = null
        val journal = journal(
            read = { persisted },
            write = {
                persisted = it
                true
            },
            clear = {
                persisted = null
                true
            },
            maxEntries = 2,
        )

        journal.record(
            "image",
            measurement(MediaPipelineMilestone.LOCAL_PROJECTION_READY, 2),
        )
        journal.record(
            "video",
            measurement(MediaPipelineMilestone.LOCAL_PLAYABLE, 3, 12_000, 10_000),
        )
        journal.record("audio", measurement(MediaPipelineMilestone.UPLOADED, 4, 40_000, 30_000))

        assertEquals(
            listOf(
                "video" to MediaPipelineMilestone.LOCAL_PLAYABLE,
                "audio" to MediaPipelineMilestone.UPLOADED,
            ),
            journal.snapshot().map { it.mediaKind to it.milestone },
        )
        assertEquals("0-1mib", journal.snapshot().first().declaredByteBucket)
        assertEquals("0-15s", journal.snapshot().first().durationBucket)
        assertFalse(checkNotNull(persisted).contains(TEST_OWNER))
        assertTrue(journal.clear())
        assertTrue(journal.snapshot().isEmpty())
        assertEquals(null, persisted)
    }

    @Test
    fun `profiler export cannot contain correlation ids paths exact media dimensions or model`() {
        var persisted: String? = null
        var now = 1_000_000L
        val journal = journal(
            read = { persisted },
            write = {
                persisted = it
                true
            },
            clear = {
                persisted = null
                true
            },
        )
        val profiler = MediaPipelineProfiler(
            nanoTime = { now },
            emit = { _, _ -> },
            diagnosticJournal = journal,
        )

        profiler.begin(
            mediaId = "private-media-id",
            mediaType = "customer@example.test/private/path",
            declaredByteCount = 80_000,
            durationMillis = 120_000,
            ownerScopeId = TEST_OWNER,
        )
        now += 7_000_000L
        profiler.mark(
            "private-media-id",
            MediaPipelineMilestone.LOCAL_PLAYABLE,
            TEST_OWNER,
        )
        val report = journal.exportReport(
            versionName = "0.2.43/private",
            versionCode = 54,
            androidApi = 35,
        )

        assertTrue(report.contains("build=0.2.43private (54)"))
        assertTrue(report.contains("other,local_playable,7,0-1mib,15s-3m"))
        assertFalse(report.contains("private-media-id"))
        assertFalse(report.contains("customer@example.test"))
        assertFalse(report.contains("/private/path"))
        assertFalse(report.contains("80000"))
        assertFalse(report.contains("120000"))
        assertFalse(report.contains("device_class="))
    }

    @Test
    fun `export discards malformed persisted text rather than reproducing it`() {
        var persisted: String? = null
        val journal = journal(
            read = { persisted },
            write = {
                persisted = it
                true
            },
            clear = {
                persisted = null
                true
            },
        )
        journal.record("video", measurement(MediaPipelineMilestone.UPLOADED, 12, 24_000, 10_000))
        persisted += "\nsecret-account|LOCAL_PLAYABLE|9|0-1mib|0-15s" +
            "\nvideo|UPLOADED|-1|0-1mib|0-15s"

        val report = journal.exportReport(
            versionName = "test",
            versionCode = 1,
            androidApi = 26,
        )

        assertTrue(report.contains("video,uploaded,12,0-1mib,0-15s"))
        assertFalse(report.contains("secret-account"))
        assertFalse(report.contains("video,uploaded,-1"))
    }

    @Test
    fun `failed clear remains invisible after an owner replacement and is overwritten by new data`() {
        var owner = "owner-a:session-a"
        var persisted: String? = null
        var clearSucceeds = true
        val journal = journal(
            read = { persisted },
            write = {
                persisted = it
                true
            },
            clear = {
                if (clearSucceeds) persisted = null
                clearSucceeds
            },
            owner = { owner },
        )
        journal.record("video", measurement(MediaPipelineMilestone.UPLOADED, 12, 24_000, 10_000))
        val retiredOwnerBytes = persisted

        owner = "owner-b:session-b"
        clearSucceeds = false

        assertFalse(journal.clear())
        assertEquals(retiredOwnerBytes, persisted)
        assertTrue(journal.snapshot().isEmpty())
        assertFalse(journal.exportReport(versionName = "test", versionCode = 1, androidApi = 35)
            .contains("video,uploaded"))

        journal.record("audio", measurement(MediaPipelineMilestone.LOCAL_PLAYABLE, 7, 2_000_000, 8_000))

        assertEquals(listOf("audio"), journal.snapshot().map { it.mediaKind })
        assertFalse(checkNotNull(persisted).contains("owner-a"))
        assertFalse(checkNotNull(persisted).contains("owner-b"))
    }

    @Test
    fun `old producer cannot repopulate the journal after owner replacement`() {
        var owner = "owner-a:session-a"
        var persisted: String? = null
        val scheduled = mutableListOf<() -> Unit>()
        val journal = journal(
            read = { persisted },
            write = {
                persisted = it
                true
            },
            clear = {
                persisted = null
                true
            },
            owner = { owner },
            schedule = scheduled::add,
        )
        val profiler = MediaPipelineProfiler(
            nanoTime = { 2_000_000L },
            emit = { _, _ -> },
            diagnosticJournal = journal,
        )
        val oldOwner = owner
        profiler.begin("old-media", "video/mp4", ownerScopeId = oldOwner)

        owner = "owner-b:session-b"
        assertTrue(journal.clear())
        profiler.mark("old-media", MediaPipelineMilestone.UPLOADED, oldOwner)
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).invoke()

        assertTrue(journal.snapshot().isEmpty())

        profiler.begin("new-media", "audio/mp4", ownerScopeId = owner)
        profiler.mark("new-media", MediaPipelineMilestone.LOCAL_PLAYABLE, owner)
        scheduled.removeAt(0).invoke()

        assertEquals(listOf("audio"), journal.snapshot().map { it.mediaKind })
    }

    @Test
    fun `hot path coalesces writes and retries buffered measurements after storage recovers`() {
        val scheduled = mutableListOf<() -> Unit>()
        var persisted: String? = null
        var writes = 0
        var failWrites = true
        val journal = journal(
            read = { persisted },
            write = {
                writes++
                if (failWrites) error("diagnostic storage unavailable")
                persisted = it
                true
            },
            clear = { true },
            schedule = scheduled::add,
        )
        val emitted = mutableListOf<MediaPipelineMeasurement>()
        val profiler = MediaPipelineProfiler(
            nanoTime = { 2_000_000L },
            emit = { _, measurement -> emitted += measurement },
            diagnosticJournal = journal,
        )
        repeat(3) { index ->
            profiler.begin("media-$index", "image/jpeg", ownerScopeId = TEST_OWNER)
            profiler.mark(
                "media-$index",
                MediaPipelineMilestone.LOCAL_PROJECTION_READY,
                TEST_OWNER,
            )
        }

        assertEquals(1, scheduled.size)
        assertEquals(0, writes)
        val failure = runCatching { scheduled.removeAt(0).invoke() }.exceptionOrNull()

        assertEquals(null, failure)
        assertEquals(1, writes)
        assertEquals(3, emitted.size)
        assertEquals(3, journal.snapshot().size)

        failWrites = false
        profiler.begin("media-3", "image/jpeg", ownerScopeId = TEST_OWNER)
        profiler.mark(
            "media-3",
            MediaPipelineMilestone.LOCAL_PROJECTION_READY,
            TEST_OWNER,
        )
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).invoke()

        assertEquals(2, writes)
        assertEquals(4, journal.snapshot().size)
    }

    @Test
    fun `false write result after mutation consumes the batch without duplicating it`() {
        val scheduled = mutableListOf<() -> Unit>()
        var persisted: String? = null
        var writes = 0
        val journal = journal(
            read = { persisted },
            write = { encoded ->
                writes++
                persisted = encoded
                writes > 1
            },
            clear = { true },
            schedule = scheduled::add,
        )

        journal.record("video", measurement(MediaPipelineMilestone.LOCAL_PLAYABLE, 5))
        scheduled.removeAt(0).invoke()

        assertEquals(1, writes)
        assertEquals(1, journal.snapshot().size)

        journal.record("audio", measurement(MediaPipelineMilestone.UPLOADED, 8))
        scheduled.removeAt(0).invoke()

        assertEquals(2, writes)
        assertEquals(
            listOf("video", "audio"),
            journal.snapshot().map { it.mediaKind },
        )
    }

    @Test
    fun `write throwing before mutation requeues once and recovery creates no duplicates`() {
        val scheduled = mutableListOf<() -> Unit>()
        var persisted: String? = null
        var writes = 0
        val journal = journal(
            read = { persisted },
            write = { encoded ->
                writes++
                if (writes == 1) error("write failed before mutation")
                persisted = encoded
                true
            },
            clear = { true },
            schedule = scheduled::add,
        )

        journal.record("video", measurement(MediaPipelineMilestone.LOCAL_PLAYABLE, 5))
        scheduled.removeAt(0).invoke()

        assertEquals(1, writes)
        assertEquals(null, persisted)
        assertEquals(1, journal.snapshot().size)

        journal.record("audio", measurement(MediaPipelineMilestone.UPLOADED, 8))
        scheduled.removeAt(0).invoke()

        assertEquals(2, writes)
        assertEquals(
            listOf("video", "audio"),
            journal.snapshot().map { it.mediaKind },
        )
    }

    private fun journal(
        read: () -> String?,
        write: (String) -> Boolean,
        clear: () -> Boolean,
        owner: () -> String? = { TEST_OWNER },
        schedule: ((() -> Unit) -> Unit) = { it() },
        maxEntries: Int = 128,
    ) = MediaPipelineDiagnosticJournal(
        readPersisted = read,
        writePersisted = write,
        clearPersisted = clear,
        currentOwnerScopeId = owner,
        schedulePersist = schedule,
        maxEntries = maxEntries,
    )

    private fun measurement(
        milestone: MediaPipelineMilestone,
        elapsedMillis: Long,
        declaredByteCount: Long? = null,
        durationMillis: Long? = null,
    ) = MediaPipelineMeasurement(
        milestone,
        elapsedMillis,
        declaredByteCount,
        durationMillis,
    )

    private companion object {
        const val TEST_OWNER = "account-a:session-a"
    }
}
