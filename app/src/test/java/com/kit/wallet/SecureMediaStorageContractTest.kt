package com.kit.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMediaStorageContractTest {
    @Test
    fun `file provider exposes only the two retained-media roots`() {
        val paths = source("app/src/main/res/xml/chat_media_paths.xml")

        assertTrue(
            paths.contains(
                "<external-media-path name=\"retained-chat-media\" path=\"Kit Pay/Media/\" />",
            ),
        )
        assertTrue(
            paths.contains(
                "<files-path name=\"retained-chat-media-private\" path=\"Kit Pay/Media/\" />",
            ),
        )
        assertFalse(paths.contains("<external-path"))
        assertFalse(paths.contains("<root-path"))
    }

    @Test
    fun `viewer lease is no-copy bounded and has a pin fallback`() {
        val lease = source(
            "app/src/main/java/com/kit/wallet/data/messaging/SecureMediaLease.kt",
        )
        val playback = source(
            "app/src/main/java/com/kit/wallet/feature/chat/ChatMediaPlayback.kt",
        )

        assertTrue(lease.contains("Os.link(source.absolutePath, linked.absolutePath)"))
        assertTrue(lease.contains("SecureMediaLease(source, marker, purpose)"))
        assertTrue(lease.contains("EXTERNAL_LEASE_MAX_AGE_MILLIS"))
        assertTrue(lease.contains("MAXIMUM_EXTERNAL_LEASES_PER_STORE"))
        assertFalse(lease.contains("copyTo"))
        assertFalse(lease.contains("source.inputStream"))

        assertTrue(playback.contains("SecureMediaLease.forPlayback"))
        assertTrue(playback.contains("SecureMediaLease.forExternalHandoff"))
        assertTrue(playback.contains("lease.file,"))
        assertTrue(playback.contains("lease.detachForExternalConsumer()"))
        assertTrue(playback.contains("lease.close()"))
        assertFalse(playback.contains("FileProvider.getUriForFile(context, authority, source)"))
    }

    @Test
    fun `cache eviction and process cleanup honor media lease lifecycle`() {
        val cache = source(
            "app/src/main/java/com/kit/wallet/data/messaging/SecureMediaCache.kt",
        )
        val playback = source(
            "app/src/main/java/com/kit/wallet/feature/chat/ChatMediaPlayback.kt",
        )

        assertTrue(cache.contains("SecureMediaLease.deleteIfNotPinned(candidate)"))
        assertTrue(playback.contains("SecureMediaLease.purgeAfterProcessRestart"))
    }

    @Test
    fun `video document and share consumers receive leases instead of evictable paths`() {
        val bubbles = source(
            "app/src/main/java/com/kit/wallet/feature/chat/ChatMediaBubbles.kt",
        )
        val gallery = source(
            "app/src/main/java/com/kit/wallet/feature/chat/MediaGalleryViewer.kt",
        )
        val player = source("app/src/main/java/com/kit/wallet/feature/chat/ChatVideoPlayer.kt")

        assertTrue(bubbles.split("chatMediaPlaybackLease(context, media)").size - 1 >= 2)
        assertTrue(bubbles.contains("DisposableEffect(lease)"))
        assertTrue(bubbles.contains("file = lease.file"))
        assertTrue(bubbles.split("launchWithChatMediaUri(context, media)").size - 1 >= 2)
        assertFalse(bubbles.contains("playerFile = media.file"))
        assertFalse(bubbles.contains("viewerFile = media.file"))

        assertTrue(gallery.contains("chatMediaPlaybackLease(context, media)"))
        assertTrue(gallery.contains("file = lease.file"))
        assertTrue(player.contains("setVideoPath(file.absolutePath)"))
        assertTrue(gallery.contains("launchWithChatMediaUri(context, media)"))
        assertFalse(gallery.contains("setVideoPath(media.file.absolutePath)"))
    }

    private fun source(relativePath: String): String = File(repositoryRoot(), relativePath).readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root from $workingDirectory")
    }
}
