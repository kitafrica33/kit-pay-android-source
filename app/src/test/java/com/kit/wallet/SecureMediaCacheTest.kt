package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMediaCache
import com.kit.wallet.data.session.SessionInvalidatedException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-local persistence and bounded retention for authenticated chat media. */
class SecureMediaCacheTest {

    private val directory: File =
        Files.createTempDirectory("kit-secure-media-cache-test").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun cacheRoot() = File(directory, "cache")

    private fun metadataRoot() = File(directory, "private-integrity")

    private fun cache(
        maximumBytes: Long = 4_096L,
        deleteFile: (File) -> Boolean = File::delete,
    ) = SecureMediaCache(
        preferredDirectory = null,
        fallbackDirectory = cacheRoot(),
        maximumBytes = maximumBytes,
        mediaChanged = { _, _ -> },
        privateMetadataDirectory = metadataRoot(),
        deleteFile = deleteFile,
    )

    private suspend fun SecureMediaCache.put(
        key: String,
        bytes: ByteArray,
        mediaType: String = "image/jpeg",
    ) = store(key, mediaType) { destination -> destination.writeBytes(bytes) }

    private suspend fun SecureMediaCache.putRetained(
        key: String,
        bytes: ByteArray,
        mediaType: String = "image/jpeg",
    ) = store(key, mediaType, retainUntilReleased = true) { destination ->
        destination.writeBytes(bytes)
    }

    private fun regularFiles(root: File): List<File> =
        root.walkTopDown().filter(File::isFile).toList()

    @Test
    fun `a stored attachment is handed straight back`() = runTest {
        val cache = cache()
        val bytes = ByteArray(64) { 3 }

        val stored = cache.put("descriptor-1", bytes)
        assertEquals(64L, stored.byteCount)
        assertTrue(stored.exists)

        val cached = checkNotNull(cache.cached("descriptor-1", "image/jpeg"))
        assertEquals(stored.file, cached.file)
        assertTrue(cached.file.readBytes().contentEquals(bytes))
    }

    @Test
    fun `media uses a type-specific folder and playable extension`() = runTest {
        val cache = cache()
        val cases = listOf(
            Triple("image/jpeg", "Kit Pay Images", "jpg"),
            Triple("video/mp4", "Kit Pay Video", "mp4"),
            Triple("audio/ogg", "Kit Pay Audio", "ogg"),
            Triple("application/pdf", "Kit Pay Documents", "pdf"),
        )

        cases.forEachIndexed { index, (mediaType, folder, extension) ->
            val stored = cache.put("descriptor-$index", ByteArray(16), mediaType)

            assertEquals(folder, stored.file.parentFile?.name)
            assertTrue(stored.file.name.endsWith(".$extension"))
        }
    }

    @Test
    fun `plaintext never lands under the descriptor's own name`() = runTest {
        val stored = cache().put("../escape/attempt", ByteArray(16))

        assertFalse(stored.file.name.contains("escape"))
        assertTrue(stored.file.name.matches(Regex("^[0-9a-f]{64}\\.jpg$")))
        assertTrue(stored.file.toPath().startsWith(cacheRoot().toPath()))
    }

    @Test
    fun `a new cache instance reuses the on-device copy after process restart`() = runTest {
        val firstProcess = cache()
        val bytes = ByteArray(64) { 7 }
        val stored = firstProcess.put("restart-stable-id", bytes)

        val restartedProcess = cache()
        val cached = checkNotNull(restartedProcess.cached("restart-stable-id", "image/jpeg"))

        assertEquals(stored.file, cached.file)
        assertTrue(cached.file.readBytes().contentEquals(bytes))
    }

    @Test
    fun `integrity metadata is private and survives a process restart`() = runTest {
        val external = File(directory, "external")
        val metadata = metadataRoot()
        val firstProcess = SecureMediaCache(
            preferredDirectory = external,
            fallbackDirectory = File(directory, "fallback"),
            mediaChanged = { _, _ -> },
            privateMetadataDirectory = metadata,
        )
        val stored = firstProcess.put("descriptor-1", ByteArray(64) { 5 })

        assertTrue(stored.file.toPath().startsWith(external.toPath()))
        assertEquals(1, regularFiles(metadata).count { it.name.endsWith(".integrity") })
        assertFalse(regularFiles(external).any { it.name.endsWith(".integrity") })

        val restartedProcess = SecureMediaCache(
            preferredDirectory = external,
            fallbackDirectory = File(directory, "fallback"),
            mediaChanged = { _, _ -> },
            privateMetadataDirectory = metadata,
        )
        assertNotNull(restartedProcess.cached("descriptor-1", "image/jpeg"))
    }

    @Test
    fun `same-length external tampering is rejected and removed`() = runTest {
        val external = File(directory, "external")
        val cache = SecureMediaCache(
            preferredDirectory = external,
            fallbackDirectory = File(directory, "fallback"),
            mediaChanged = { _, _ -> },
            privateMetadataDirectory = metadataRoot(),
        )
        val stored = cache.put("descriptor-1", ByteArray(64) { 1 })
        stored.file.writeBytes(ByteArray(64) { 2 })

        assertNull(cache.cached("descriptor-1", "image/jpeg"))
        assertFalse(stored.file.exists())
        assertTrue(regularFiles(metadataRoot()).isEmpty())
    }

    @Test
    fun `media without its private integrity record fails closed`() = runTest {
        val cache = cache()
        val stored = cache.put("descriptor-1", ByteArray(64))
        metadataRoot().listFiles().orEmpty()
            .single { it.name.endsWith(".integrity") }
            .delete()

        assertNull(cache.cached("descriptor-1", "image/jpeg"))
        assertFalse(stored.file.exists())
    }

    @Test
    fun `production roots keep media grantable and integrity private`() {
        val androidMediaDirectory = File(directory, "Android/media/com.kit.wallet")
        val filesDirectory = File(directory, "data/files")
        val noBackupDirectory = File(directory, "data/no_backup")

        val mediaRoot = SecureMediaCache.externalDirectoryFor(androidMediaDirectory)
        val privateMediaRoot = SecureMediaCache.privateDirectoryFor(filesDirectory)
        val integrityRoot = SecureMediaCache.metadataDirectoryFor(noBackupDirectory)

        assertEquals(
            File(androidMediaDirectory, "Kit Pay/Media").absolutePath,
            mediaRoot.absolutePath,
        )
        assertEquals(File(filesDirectory, "Kit Pay/Media"), privateMediaRoot)
        assertEquals(File(noBackupDirectory, "secure-media-integrity"), integrityRoot)
    }

    @Test
    fun `writable external media is preferred over private fallback`() = runTest {
        val external = File(directory, "external")
        val fallback = File(directory, "fallback")
        val cache = SecureMediaCache(
            preferredDirectory = external,
            fallbackDirectory = fallback,
            mediaChanged = { _, _ -> },
        )

        val stored = cache.put("descriptor-1", ByteArray(32))

        assertTrue(stored.file.toPath().startsWith(external.toPath()))
        assertFalse(fallback.exists())
    }

    @Test
    fun `private storage is used when external media cannot be prepared`() = runTest {
        val unavailableExternal = File(directory, "external").apply { writeText("not a directory") }
        val fallback = File(directory, "fallback")
        val cache = SecureMediaCache(
            preferredDirectory = unavailableExternal,
            fallbackDirectory = fallback,
            mediaChanged = { _, _ -> },
        )

        val stored = cache.put("descriptor-1", ByteArray(32))

        assertTrue(stored.file.toPath().startsWith(fallback.toPath()))
    }

    @Test
    fun `external publication and deletion notify Android's media index`() = runTest {
        val events = mutableListOf<Pair<File, String?>>()
        val cache = SecureMediaCache(
            preferredDirectory = File(directory, "external"),
            fallbackDirectory = File(directory, "fallback"),
            mediaChanged = { file, mediaType -> events += file to mediaType },
        )

        val stored = cache.put("descriptor-1", ByteArray(32), "video/mp4")
        cache.remove("descriptor-1")

        assertEquals(
            listOf(stored.file to "video/mp4", stored.file to null),
            events,
        )
    }

    @Test
    fun `the oldest attachment is evicted once the budget is exceeded`() = runTest {
        val cache = cache(maximumBytes = 3_000L)

        val first = cache.put("descriptor-1", ByteArray(1_500))
        // Touch order is what eviction sorts on, and a test can run inside one filesystem
        // timestamp tick; stamp them apart so "oldest" means what the assertion says it means.
        first.file.setLastModified(1_000L)
        val second = cache.put("descriptor-2", ByteArray(1_500))
        second.file.setLastModified(2_000L)

        val third = cache.put("descriptor-3", ByteArray(1_500))

        assertTrue(third.exists)
        assertNull(cache.cached("descriptor-1", "image/jpeg"))
        assertNotNull(cache.cached("descriptor-2", "image/jpeg"))
    }

    @Test
    fun `the entry just written is never the one evicted`() = runTest {
        val cache = cache(maximumBytes = 1_024L)

        val only = cache.put("descriptor-1", ByteArray(4_096))

        // Over budget on its own, but evicting it would leave the caller holding a handle to a
        // file that never existed. It stays until something else pushes it out.
        assertTrue(only.exists)
        assertNotNull(cache.cached("descriptor-1", "image/jpeg"))
    }

    @Test
    fun `a durable retention pin survives restart and blocks eviction`() = runTest {
        val firstProcess = cache(maximumBytes = 100L)
        val retained = firstProcess.putRetained("retained", ByteArray(80))
        retained.file.setLastModified(1_000L)

        val restartedProcess = cache(maximumBytes = 100L)
        restartedProcess.put("new", ByteArray(80)).file.setLastModified(2_000L)
        restartedProcess.put("newer", ByteArray(80))

        assertNotNull(restartedProcess.cached("retained", "image/jpeg"))
        assertNull(restartedProcess.cached("new", "image/jpeg"))
        assertNotNull(restartedProcess.cached("newer", "image/jpeg"))
    }

    @Test
    fun `release removes only the pin and a later eviction may remove the media`() = runTest {
        val cache = cache(maximumBytes = 100L)
        val retained = cache.putRetained("retained", ByteArray(80))
        retained.file.setLastModified(1_000L)

        cache.releaseRetention("retained")

        assertTrue(retained.file.isFile)
        cache.put("new", ByteArray(80))
        assertNull(cache.cached("retained", "image/jpeg"))
        assertNotNull(cache.cached("new", "image/jpeg"))
    }

    @Test
    fun `release immediately restores the cache bound after pins caused an overage`() = runTest {
        val cache = cache(maximumBytes = 100L)
        val retained = cache.putRetained("retained", ByteArray(80))
        retained.file.setLastModified(1_000L)
        val newest = cache.put("new", ByteArray(80))

        // The old entry is protected and the just-written entry is kept, so this is the bounded
        // and intentional period in which durable queue data may exceed the ordinary cache cap.
        assertTrue(retained.file.isFile)
        assertTrue(newest.file.isFile)

        cache.releaseRetention("retained")

        assertNull(cache.cached("retained", "image/jpeg"))
        assertNotNull(cache.cached("new", "image/jpeg"))
        assertTrue(regularFiles(cacheRoot()).sumOf(File::length) <= 100L)
    }

    @Test
    fun `prune protects a new in-process pin until a queue snapshot observes it`() = runTest {
        val firstProcess = cache(maximumBytes = 100L)
        val retained = firstProcess.putRetained("retained", ByteArray(80))

        firstProcess.pruneRetentions(emptySet())
        firstProcess.put("new", ByteArray(80))
        assertTrue(retained.file.isFile)

        firstProcess.pruneRetentions(setOf("retained"))
        val restartedProcess = cache(maximumBytes = 100L)
        restartedProcess.pruneRetentions(emptySet())

        // Pruning releases only the orphan marker; it never removes the media itself.
        assertTrue(retained.file.isFile)
        retained.file.setLastModified(1_000L)
        restartedProcess.put("newer", ByteArray(80))
        assertNull(restartedProcess.cached("retained", "image/jpeg"))
    }

    @Test
    fun `a failed first write publishes no media file`() = runTest {
        val cache = cache()

        val failure = runCatching {
            cache.store("descriptor-1", "image/jpeg") { destination ->
                destination.writeBytes(ByteArray(32))
                throw IllegalStateException("download died halfway")
            }
        }.exceptionOrNull()

        assertEquals("download died halfway", failure?.message)
        assertNull(cache.cached("descriptor-1", "image/jpeg"))
        assertTrue(regularFiles(cacheRoot()).isEmpty())
    }

    @Test
    fun `a failed replacement keeps the previously complete media`() = runTest {
        val cache = cache()
        val original = ByteArray(32) { 4 }
        val replacement = ByteArray(64) { 9 }
        cache.put("descriptor-1", original)

        val failure = runCatching {
            cache.store("descriptor-1", "image/jpeg") { destination ->
                destination.writeBytes(replacement)
                throw IllegalStateException("replacement interrupted")
            }
        }.exceptionOrNull()

        assertEquals("replacement interrupted", failure?.message)
        val cached = checkNotNull(cache.cached("descriptor-1", "image/jpeg"))
        assertTrue(cached.file.readBytes().contentEquals(original))
    }

    @Test
    fun `a zero-length write is refused rather than cached`() = runTest {
        val cache = cache()

        val failure = runCatching {
            cache.store("descriptor-1", "image/jpeg") { destination ->
                destination.createNewFile()
            }
        }.exceptionOrNull()

        assertEquals("The secure attachment could not be prepared", failure?.message)
        assertNull(cache.cached("descriptor-1", "image/jpeg"))
    }

    @Test
    fun `stale partial files from an interrupted process are removed`() = runTest {
        val cacheRoot = cacheRoot().apply { mkdirs() }
        val imageDirectory = File(cacheRoot, "Kit Pay Images").apply { mkdirs() }
        val legacyPartial = File(cacheRoot, ".legacy.partial").apply { writeBytes(ByteArray(8)) }
        val currentPartial = File(imageDirectory, ".current.partial").apply {
            writeBytes(ByteArray(8))
        }
        val metadataPartial = File(metadataRoot(), ".integrity.partial").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }

        assertNull(cache().cached("missing", "image/jpeg"))

        assertFalse(legacyPartial.exists())
        assertFalse(currentPartial.exists())
        assertFalse(metadataPartial.exists())
    }

    @Test
    fun `sign-out drops decrypted attachments from both storage locations`() = runTest {
        val external = File(directory, "external")
        val fallback = File(directory, "fallback")
        File(external, "Kit Pay Images/photo.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(16))
        }
        File(fallback, "Kit Pay Documents/statement.pdf").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(16))
        }
        metadataRoot().mkdirs()
        File(metadataRoot(), "record.integrity").writeText("private")
        val cache = SecureMediaCache(
            preferredDirectory = external,
            fallbackDirectory = fallback,
            mediaChanged = { _, _ -> },
            privateMetadataDirectory = metadataRoot(),
        )

        cache.clear()

        assertFalse(external.exists())
        assertFalse(fallback.exists())
        assertFalse(metadataRoot().exists())
    }

    @Test
    fun `retired owner cannot repopulate a cache after clear completed`() = runTest {
        val cache = cache()
        cache.clear(retiredOwnerScopeId = "old-owner")

        val staleFailure = runCatching {
            cache.store(
                cacheKey = "kit-media:old-owner:chat:attachment",
                mediaType = "image/jpeg",
                ownerScopeId = "old-owner",
                // Even a delayed caller whose local check is stale cannot reclaim a retired scope.
                ownerIsCurrent = { true },
            ) { destination -> destination.writeBytes(ByteArray(64)) }
        }.exceptionOrNull()

        assertTrue(staleFailure is SessionInvalidatedException)
        assertTrue(regularFiles(cacheRoot()).isEmpty())
        assertTrue(regularFiles(metadataRoot()).isEmpty())

        val successor = cache.store(
            cacheKey = "kit-media:new-owner:chat:attachment",
            mediaType = "image/jpeg",
            ownerScopeId = "new-owner",
            ownerIsCurrent = { true },
        ) { destination -> destination.writeBytes(ByteArray(64)) }
        assertTrue(successor.exists)
    }

    @Test
    fun `owner switch during copy rejects publication without needing clear`() = runTest {
        val cache = cache()
        val currentOwner = AtomicBoolean(true)
        val copyStarted = CompletableDeferred<Unit>()
        val finishCopy = CompletableDeferred<Unit>()
        val write = backgroundScope.async {
            runCatching {
                cache.store(
                    cacheKey = "kit-media:old-owner:chat:attachment",
                    mediaType = "image/jpeg",
                    ownerScopeId = "old-owner",
                    ownerIsCurrent = currentOwner::get,
                ) { destination ->
                    destination.writeBytes(ByteArray(64))
                    copyStarted.complete(Unit)
                    finishCopy.await()
                }
            }
        }
        copyStarted.await()

        currentOwner.set(false)
        finishCopy.complete(Unit)
        val failure = write.await().exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertTrue(regularFiles(cacheRoot()).isEmpty())
        assertTrue(regularFiles(metadataRoot()).isEmpty())
    }

    @Test
    fun `clear generation rejects a copy that was already in flight`() = runTest {
        val cache = cache()
        val copyStarted = CompletableDeferred<Unit>()
        val finishCopy = CompletableDeferred<Unit>()
        val staleWrite = backgroundScope.async {
            runCatching {
                cache.store(
                    cacheKey = "kit-media:old-owner:chat:attachment",
                    mediaType = "image/jpeg",
                    ownerScopeId = "old-owner",
                    ownerIsCurrent = { true },
                ) { destination ->
                    destination.writeBytes(ByteArray(64))
                    copyStarted.complete(Unit)
                    finishCopy.await()
                }
            }
        }
        copyStarted.await()

        // UNDISPATCHED guarantees clear advances the generation before this test releases the
        // writer that currently owns the filesystem mutex.
        val clearing = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            cache.clear(retiredOwnerScopeId = "old-owner")
        }
        finishCopy.complete(Unit)
        val staleFailure = staleWrite.await().exceptionOrNull()
        clearing.await()

        assertTrue(staleFailure is SessionInvalidatedException)
        assertTrue(regularFiles(cacheRoot()).isEmpty())
        assertTrue(regularFiles(metadataRoot()).isEmpty())
    }

    @Test
    fun `successor owner waits for an active clear before publishing`() = runTest {
        val deletionStarted = CountDownLatch(1)
        val finishDeletion = CountDownLatch(1)
        val holdMediaDeletion = AtomicBoolean(false)
        val cache = cache(
            deleteFile = { file ->
                if (holdMediaDeletion.get() && file.extension == "jpg") {
                    deletionStarted.countDown()
                    check(finishDeletion.await(5, TimeUnit.SECONDS))
                }
                file.delete()
            },
        )
        cache.put("old", ByteArray(64))
        holdMediaDeletion.set(true)

        val clearing = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            cache.clear(retiredOwnerScopeId = "old-owner")
        }
        assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))

        val successorWriteStarted = CompletableDeferred<Unit>()
        val successor = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            cache.store(
                cacheKey = "kit-media:new-owner:chat:attachment",
                mediaType = "image/jpeg",
                ownerScopeId = "new-owner",
                ownerIsCurrent = { true },
            ) { destination ->
                successorWriteStarted.complete(Unit)
                destination.writeBytes(ByteArray(64))
            }
        }
        assertFalse(successorWriteStarted.isCompleted)
        assertFalse(successor.isCompleted)

        finishDeletion.countDown()
        clearing.await()
        val stored = successor.await()

        assertTrue(successorWriteStarted.isCompleted)
        assertTrue(stored.exists)
        assertNotNull(cache.cached("kit-media:new-owner:chat:attachment", "image/jpeg"))
    }

    @Test
    fun `sign-out reports a deletion failure instead of claiming success`() = runTest {
        var refuseMediaDeletion = false
        val cache = cache(
            deleteFile = { file ->
                if (refuseMediaDeletion && file.extension == "jpg") false else file.delete()
            },
        )
        val stored = cache.put("descriptor-1", ByteArray(64))
        refuseMediaDeletion = true

        val failure = runCatching { cache.clear() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Secure media could not be cleared completely", failure?.message)
        assertTrue(stored.file.exists())
        // Its private authenticity record was still cleared, so the surviving public file can
        // never be mistaken for authenticated content on the next session.
        assertFalse(metadataRoot().exists())
    }

    @Test
    fun `a cleared cache still accepts the next attachment`() = runTest {
        val cache = cache()
        cache.put("descriptor-1", ByteArray(64))
        cache.clear()

        val stored = cache.put("descriptor-2", ByteArray(64))

        assertTrue(stored.exists)
        assertNotNull(cache.cached("descriptor-2", "image/jpeg"))
    }
}
