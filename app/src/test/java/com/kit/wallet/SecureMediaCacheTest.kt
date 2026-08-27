package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMediaCache
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte budget for decrypted attachments lives here rather than in the ViewModel, which now
 * holds handles only. These are the assertions that used to guard the in-memory cache.
 */
class SecureMediaCacheTest {

    private val directory: File =
        Files.createTempDirectory("kit-secure-media-cache-test").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun cache(maximumBytes: Long = 4_096L) =
        SecureMediaCache(File(directory, "cache"), maximumBytes)

    private suspend fun SecureMediaCache.put(key: String, bytes: ByteArray) =
        store(key, "image/jpeg") { destination -> destination.writeBytes(bytes) }

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
    fun `plaintext never lands under the descriptor's own name`() = runTest {
        val cache = cache()
        cache.put("../escape/attempt", ByteArray(16))

        val names = File(directory, "cache").listFiles().orEmpty().map(File::getName)
        assertEquals(1, names.size)
        assertFalse(names.single().contains("escape"))
        assertTrue(names.single().endsWith(SecureMediaCache.FILE_SUFFIX))
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
    fun `a failed write publishes nothing`() = runTest {
        val cache = cache()

        val failure = runCatching {
            cache.store("descriptor-1", "image/jpeg") { destination ->
                destination.writeBytes(ByteArray(32))
                throw IllegalStateException("download died halfway")
            }
        }.exceptionOrNull()

        assertEquals("download died halfway", failure?.message)
        assertNull(cache.cached("descriptor-1", "image/jpeg"))
        assertTrue(File(directory, "cache").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a zero-length write is refused rather than cached`() = runTest {
        val cache = cache()

        val failure = runCatching {
            cache.store("descriptor-1", "image/jpeg") { destination -> destination.createNewFile() }
        }.exceptionOrNull()

        assertEquals("The secure attachment could not be prepared", failure?.message)
        assertNull(cache.cached("descriptor-1", "image/jpeg"))
    }

    @Test
    fun `sign-out drops every decrypted attachment`() = runTest {
        val cache = cache()
        cache.put("descriptor-1", ByteArray(64))
        cache.put("descriptor-2", ByteArray(64))

        cache.clear()

        assertNull(cache.cached("descriptor-1", "image/jpeg"))
        assertNull(cache.cached("descriptor-2", "image/jpeg"))
        assertFalse(File(directory, "cache").exists())
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
