package com.kit.wallet.data.messaging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One decrypted attachment, held where the platform can actually use it.
 *
 * A `ByteArray` is the wrong shape for a 200 MB video: `MediaPlayer`, `VideoView` and every
 * document viewer want something they can seek, and a heap array of that size is a crash waiting
 * for a second one. So an opened attachment is a file in app-private storage, and this handle is
 * what the UI passes around instead of the bytes.
 */
data class SecureMediaFile(
    val file: File,
    val mediaType: String,
    val byteCount: Long,
) {
    val exists: Boolean get() = file.isFile
}

/**
 * App-private, size-bounded store for attachments that have already been decrypted once.
 *
 * The Android counterpart of iOS's `SecureMediaFileCache`, with one deliberate difference. iOS
 * re-seals each blob under a device-only Keychain key because its cache sits beside a state file
 * it must not bloat; here the files are plaintext, inside the app's own no-backup storage, and
 * the protection is the platform's file-based encryption plus the app sandbox — the same
 * protection the temporary files this app already writes for video playback and document sharing
 * rely on. The alternative, re-decrypting hundreds of megabytes on every scroll back to a video,
 * buys no real secrecy against an attacker who can already read app-private storage.
 *
 * Everything here is re-downloadable from the server's encrypted copy, so eviction is always safe
 * and the whole directory is dropped on sign-out.
 */
@Singleton
class SecureMediaCache internal constructor(
    private val directory: File,
    private val maximumBytes: Long = MAXIMUM_BYTES,
) {
    private val fileMutex = Mutex()

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, DIRECTORY_NAME),
    )

    /** The already-decrypted copy of [cacheKey], or null when it has to be fetched again. */
    suspend fun cached(cacheKey: String, mediaType: String): SecureMediaFile? =
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                val candidate = file(cacheKey)
                if (!candidate.isFile || candidate.length() <= 0) return@withLock null
                // Touch on read so eviction is least-recently-*used*, not least-recently-written.
                candidate.setLastModified(System.currentTimeMillis())
                SecureMediaFile(candidate, mediaType, candidate.length())
            }
        }

    /**
     * Runs [write] against a private scratch file and publishes it as [cacheKey] only if it
     * succeeds, so a failed or cancelled download never leaves a half-written attachment behind
     * that a later read would happily hand to a video player.
     */
    suspend fun store(
        cacheKey: String,
        mediaType: String,
        write: suspend (File) -> Unit,
    ): SecureMediaFile = withContext(Dispatchers.IO + NonCancellable) {
        directory.mkdirs()
        check(directory.isDirectory) { "Secure media storage is unavailable" }
        val scratch = File(directory, ".${token(cacheKey)}.$SCRATCH_SUFFIX")
        try {
            scratch.delete()
            write(scratch)
            check(scratch.isFile && scratch.length() > 0) {
                "The secure attachment could not be prepared"
            }
            fileMutex.withLock {
                val destination = file(cacheKey)
                destination.delete()
                check(scratch.renameTo(destination)) {
                    "The secure attachment could not be stored"
                }
                evictDownTo(maximumBytes, keep = destination)
                SecureMediaFile(destination, mediaType, destination.length())
            }
        } finally {
            if (scratch.exists()) scratch.delete()
        }
    }

    suspend fun remove(cacheKey: String) = withContext(Dispatchers.IO + NonCancellable) {
        fileMutex.withLock { file(cacheKey).delete() }
        Unit
    }

    /** Drops every decrypted attachment; used at sign-out and at process start. */
    suspend fun clear() = withContext(Dispatchers.IO + NonCancellable) {
        fileMutex.withLock {
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
        Unit
    }

    /** Oldest-first eviction until the directory fits, never touching the entry just written. */
    private fun evictDownTo(limit: Long, keep: File) {
        val entries = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            .sortedBy(File::lastModified)
        var total = entries.sumOf(File::length)
        for (candidate in entries) {
            if (total <= limit) return
            if (candidate == keep) continue
            val size = candidate.length()
            if (candidate.delete()) total -= size
        }
    }

    private fun file(cacheKey: String): File = File(directory, "${token(cacheKey)}$FILE_SUFFIX")

    /**
     * Cache keys arrive from message descriptors, so they are never allowed to name a path. A
     * digest of the key is both a safe file name and a fixed-length one.
     */
    private fun token(cacheKey: String): String {
        require(cacheKey.isNotBlank()) { "A secure attachment needs a cache key" }
        return MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal companion object {
        const val DIRECTORY_NAME = "secure-media-cache"
        const val FILE_SUFFIX = ".media"
        const val SCRATCH_SUFFIX = "partial"

        /**
         * Room for a handful of the largest attachments the wire allows, so scrolling back through
         * a conversation of big videos does not re-download every one of them.
         */
        const val MAXIMUM_BYTES = 6L * 200L * 1024L * 1024L
    }
}
