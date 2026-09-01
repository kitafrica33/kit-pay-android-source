package com.kit.wallet.data.messaging

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.kit.wallet.data.session.SessionInvalidatedException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One candidate location for locally retained, already-authenticated media. */
private data class SecureMediaLocation(
    val root: File,
    /** Only Android's external-media location should be published to MediaStore. */
    val scanChanges: Boolean,
    /** A legacy location remains readable/deletable but never receives a new plaintext file. */
    val acceptsWrites: Boolean = true,
)

/** Authenticated facts kept in credential-encrypted app-private storage, never beside plaintext. */
private data class SecureMediaIntegrity(
    val byteCount: Long,
    val sha256: String,
    val mediaType: String,
) {
    fun encode(): ByteArray = listOf(
        INTEGRITY_VERSION,
        byteCount.toString(),
        sha256,
        mediaType,
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        private const val INTEGRITY_VERSION = "kit-secure-media-integrity-v1"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        fun parse(bytes: ByteArray): SecureMediaIntegrity? {
            val lines = bytes.toString(Charsets.UTF_8).lines()
            if (lines.size != 4 || lines[0] != INTEGRITY_VERSION) return null
            val byteCount = lines[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val sha256 = lines[2].takeIf(SHA256_HEX::matches) ?: return null
            val mediaType = normalizeLocalMediaType(lines[3])
                ?.takeIf { it == lines[3] }
                ?: return null
            return SecureMediaIntegrity(byteCount, sha256, mediaType)
        }
    }
}

/** Process-local authority captured before a cache write can suspend behind filesystem work. */
private data class SecureMediaWriteFence(
    val generation: Long,
    val ownerScopeId: String?,
)

/** Cheap process-local proof that an app-private file is the same revision already hashed. */
private data class VerifiedPrivateFile(
    val canonicalPath: String,
    val byteCount: Long,
    val modifiedAtMillis: Long,
    val integritySha256: String,
) {
    companion object {
        fun capture(file: File, integritySha256: String) = VerifiedPrivateFile(
            canonicalPath = file.canonicalPath,
            byteCount = file.length(),
            modifiedAtMillis = file.lastModified(),
            integritySha256 = integritySha256,
        )
    }
}

private fun streamingSha256(file: File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } finally {
            buffer.fill(0)
        }
    }
    return digest.digest()
}

/**
 * One decrypted attachment, held where the platform can actually use it.
 *
 * A `ByteArray` is the wrong shape for a 200 MB video: `MediaPlayer`, `VideoView` and every
 * document viewer want something they can seek, and a heap array of that size is a crash waiting
 * for a second one. So an opened attachment is a file, and this handle is what the UI passes around
 * instead of the bytes.
 */
data class SecureMediaFile(
    val file: File,
    val mediaType: String,
    val byteCount: Long,
) {
    val exists: Boolean get() = file.isFile
}

/**
 * Size-bounded device store for attachments that have already passed E2E authentication.
 *
 * Production prefers credential-encrypted app-private files, partitioned into Sent Media and
 * Received Media. App-owned Android media storage is only a fallback for devices whose private
 * files area cannot be prepared. Integrity metadata remains in no-backup storage and neither
 * location is cloud-backed by Kit Pay (`allowBackup` is disabled application-wide).
 *
 * Files are plaintext at this endpoint: transport ciphertext has already passed the descriptor's
 * SHA-256 and HMAC before [store] is allowed to publish it. A length and SHA-256 anchor kept in
 * private no-backup storage is checked before first reuse in each process. A stable fingerprint
 * avoids re-hashing an unchanged app-private video on every play; externally visible fallback
 * media is re-hashed every time. External media is deliberately scanned so it behaves like
 * ordinary locally stored phone media.
 *
 * Received Media is re-downloadable from the server's encrypted copy and remains LRU-bounded.
 * Sent Media is not evicted automatically: it is the sender's independent original and remains
 * available regardless of upload/CDN lifecycle until explicit application cleanup or sign-out.
 * Callers must continue including their account/session scope in the opaque [cacheKey] so a stale
 * directory can never bridge two authenticated owners.
 */
@Singleton
class SecureMediaCache private constructor(
    private val locations: List<SecureMediaLocation>,
    private val privateMetadataDirectory: File,
    private val maximumBytes: Long,
    private val mediaChanged: (file: File, mediaType: String?) -> Unit,
    private val deleteFile: (File) -> Boolean,
    private val fileDigest: (File) -> ByteArray,
) {
    /** Protects only publication metadata, eviction and lifecycle mutation — never bulk copies. */
    private val fileMutex = Mutex()
    private class TokenLock {
        val mutex = Mutex()
        var users: Int = 0
    }
    private val tokenLockGuard = Any()
    private val tokenLocks = mutableMapOf<String, TokenLock>()
    private val activeScratchPaths = ConcurrentHashMap.newKeySet<String>()
    /** Private app-owned files are hashed once per process, then checked by stable file facts. */
    private val verifiedPrivateFiles = mutableMapOf<String, VerifiedPrivateFile>()
    private val lifecycleLock = Any()
    private var lifecycleGeneration = 0L
    private var activeClearCount = 0
    private var clearBarrier: CompletableDeferred<Unit>? = null
    /** A retired session scope stays fenced until process death; a fresh login receives a new one. */
    private val retiredOwnerScopes = mutableSetOf<String>()
    /** Protects the narrow store-to-queue-commit window from a stale activation snapshot. */
    private val retainedBeforeQueueSnapshot = mutableSetOf<String>()

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        locations = productionLocations(context),
        privateMetadataDirectory = metadataDirectoryFor(context.noBackupFilesDir),
        maximumBytes = MAXIMUM_BYTES,
        mediaChanged = mediaScanner(context),
        deleteFile = File::delete,
        fileDigest = ::streamingSha256,
    )

    /** Keeps the original test/source construction contract: one deterministic local directory. */
    internal constructor(
        directory: File,
        maximumBytes: Long = MAXIMUM_BYTES,
    ) : this(
        locations = listOf(SecureMediaLocation(directory, scanChanges = false)),
        privateMetadataDirectory = defaultMetadataDirectory(directory),
        maximumBytes = maximumBytes,
        mediaChanged = { _, _ -> },
        deleteFile = File::delete,
        fileDigest = ::streamingSha256,
    )

    /** Test seam for proving external-first selection, fallback, and scanner publication. */
    internal constructor(
        preferredDirectory: File?,
        fallbackDirectory: File,
        maximumBytes: Long = MAXIMUM_BYTES,
        mediaChanged: (file: File, mediaType: String?) -> Unit,
        privateMetadataDirectory: File = defaultMetadataDirectory(fallbackDirectory),
        deleteFile: (File) -> Boolean = File::delete,
        fileDigest: (File) -> ByteArray = ::streamingSha256,
    ) : this(
        locations = buildList {
            preferredDirectory?.let { add(SecureMediaLocation(it, scanChanges = true)) }
            if (preferredDirectory?.absoluteFile != fallbackDirectory.absoluteFile) {
                add(SecureMediaLocation(fallbackDirectory, scanChanges = false))
            }
        },
        privateMetadataDirectory = privateMetadataDirectory,
        maximumBytes = maximumBytes,
        mediaChanged = mediaChanged,
        deleteFile = deleteFile,
        fileDigest = fileDigest,
    )

    /** The already-decrypted copy of [cacheKey], or null when it has to be fetched again. */
    suspend fun cached(cacheKey: String, mediaType: String): SecureMediaFile? =
        cached(cacheKey, mediaType, collection = null)

    /**
     * Direction-aware lookup for the app-managed Sent/Received Media collections.
     *
     * A legacy unpartitioned candidate is still considered so an update never strands files that
     * older builds retained. New production writes always provide [collection].
     */
    suspend fun cached(
        cacheKey: String,
        mediaType: String,
        collection: LocalMediaCollection?,
    ): SecureMediaFile? =
        withContext(Dispatchers.IO) {
            val normalizedType = requireMediaType(mediaType)
            val keyToken = token(cacheKey)
            withTokenLock(keyToken) {
                cachedForToken(keyToken, normalizedType, collection)
            }
        }

    /**
     * Runs [write] against a private scratch file and atomically publishes it as [cacheKey] only if
     * it succeeds. A failed, cancelled, killed, or storage-interrupted download therefore never
     * becomes a media entry that a later decoder mistakes for a complete attachment. Production
     * callers pair [ownerScopeId] with [ownerIsCurrent], which is checked at publication as well as
     * the cache's own logout generation.
     */
    suspend fun store(
        cacheKey: String,
        mediaType: String,
        retainUntilReleased: Boolean = false,
        ownerScopeId: String? = null,
        ownerIsCurrent: (() -> Boolean)? = null,
        collection: LocalMediaCollection? = null,
        write: suspend (File) -> Unit,
    ): SecureMediaFile {
        require((ownerScopeId == null) == (ownerIsCurrent == null)) {
            "A secure-media owner scope and validator must be supplied together"
        }
        val writeFence = awaitWriteFence(ownerScopeId)
        return withContext(Dispatchers.IO + NonCancellable) {
            val normalizedType = requireMediaType(mediaType)
            val keyToken = token(cacheKey)
            withTokenLock(keyToken) {
                storeForToken(
                    keyToken = keyToken,
                    normalizedType = normalizedType,
                    retainUntilReleased = retainUntilReleased,
                    ownerIsCurrent = ownerIsCurrent,
                    collection = collection,
                    writeFence = writeFence,
                    write = write,
                )
            }
        }
    }

    /**
     * A different attachment may copy/hash concurrently; only this token and the short atomic
     * publication are serialized. This keeps one 200 MiB receive from blocking an unrelated
     * photo, voice note or sender-original admission.
     */
    private suspend fun storeForToken(
        keyToken: String,
        normalizedType: String,
        retainUntilReleased: Boolean,
        ownerIsCurrent: (() -> Boolean)?,
        collection: LocalMediaCollection?,
        writeFence: SecureMediaWriteFence,
        write: suspend (File) -> Unit,
    ): SecureMediaFile {
        val (location, mediaDirectory) = fileMutex.withLock {
            requireWriteFence(writeFence, ownerIsCurrent)
            removeInterruptedWritesLocked()
            firstWritableDirectory(normalizedType, collection)
        }
        val destination = File(
            mediaDirectory,
            "$keyToken.${chatMediaFileExtension(normalizedType)}",
        )
        val scratch = File(
            mediaDirectory,
            ".$keyToken-${UUID.randomUUID()}.$SCRATCH_SUFFIX",
        )
        val scratchPath = scratch.absolutePath
        activeScratchPaths += scratchPath
        try {
            check(scratch.createNewFile()) { "The secure attachment could not be prepared" }
            write(scratch)
            // A concurrent logout may unlink the open scratch. Report the ownership loss rather
            // than misclassifying that expected erasure as corrupt media.
            requireWriteFence(writeFence, ownerIsCurrent)
            check(scratch.isFile && scratch.length() > 0) {
                "The secure attachment could not be prepared"
            }
            // All O(n) work stays outside [fileMutex]. The final name remains absent throughout.
            FileOutputStream(scratch, true).use { it.fd.sync() }
            val integrity = SecureMediaIntegrity(
                byteCount = scratch.length(),
                sha256 = fileDigest(scratch).toHex(),
                mediaType = normalizedType,
            )
            return fileMutex.withLock {
                // clear() advances this generation before it waits for [fileMutex]. An old copy
                // therefore cannot publish after logout even though its bulk I/O was concurrent.
                requireWriteFence(writeFence, ownerIsCurrent)
                atomicReplace(scratch, destination)
                try {
                    writePrivateRecord(integrityFile(keyToken), integrity.encode())
                    if (retainUntilReleased) {
                        writePrivateRecord(retentionFile(keyToken), RETENTION_MARKER_BYTES)
                        retainedBeforeQueueSnapshot += keyToken
                    }
                    requireWriteFence(writeFence, ownerIsCurrent)
                } catch (error: Throwable) {
                    retainedBeforeQueueSnapshot.remove(keyToken)
                    runCatching { deletePublished(location, destination) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                    runCatching { deletePrivateRecord(integrityFile(keyToken)) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                    runCatching { deletePrivateRecord(retentionFile(keyToken)) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                    throw error
                }
                verifiedPrivateFiles.remove(destination.absolutePath)
                if (location.scanChanges) notifyMediaChanged(destination, normalizedType)
                evictDownTo(maximumBytes, keep = destination)
                SecureMediaFile(destination, normalizedType, destination.length())
            }
        } finally {
            activeScratchPaths -= scratchPath
            if (scratch.exists()) scratch.delete()
        }
    }

    private suspend fun cachedForToken(
        keyToken: String,
        normalizedType: String,
        collection: LocalMediaCollection?,
    ): SecureMediaFile? {
        val integrity = fileMutex.withLock {
            removeInterruptedWritesLocked()
            readIntegrity(keyToken).also { found ->
                if (found == null) invalidateTokenLocked(keyToken)
            }
        } ?: return null
        // A cache key is bound to its authenticated type as well as its bytes. Asking for another
        // type gets no file, but cannot destroy the valid entry.
        if (integrity.mediaType != normalizedType) return null

        for (candidate in candidateFiles(keyToken, normalizedType, collection)) {
            val snapshot = fileMutex.withLock {
                if (!candidate.file.isFile) return@withLock null
                if (candidate.file.length() != integrity.byteCount) {
                    deletePublished(candidate.location, candidate.file)
                    verifiedPrivateFiles.remove(candidate.file.absolutePath)
                    return@withLock null
                }
                val observed = VerifiedPrivateFile.capture(candidate.file, integrity.sha256)
                if (!candidate.location.scanChanges && verifiedPrivateFiles[candidate.file.absolutePath] == observed) {
                    candidate.file.setLastModified(System.currentTimeMillis())
                    val touched = VerifiedPrivateFile.capture(candidate.file, integrity.sha256)
                    verifiedPrivateFiles[candidate.file.absolutePath] = touched
                    return SecureMediaFile(candidate.file, normalizedType, integrity.byteCount)
                }
                observed
            } ?: continue

            val valid = runCatching {
                MessageDigest.isEqual(
                    fileDigest(candidate.file),
                    integrity.sha256.hexToBytes(),
                )
            }.getOrDefault(false)
            val accepted = fileMutex.withLock {
                val unchanged = candidate.file.isFile &&
                    VerifiedPrivateFile.capture(candidate.file, integrity.sha256) == snapshot &&
                    readIntegrity(keyToken) == integrity
                if (!valid || !unchanged) {
                    verifiedPrivateFiles.remove(candidate.file.absolutePath)
                    if (candidate.file.isFile) deletePublished(candidate.location, candidate.file)
                    null
                } else {
                    candidate.file.setLastModified(System.currentTimeMillis())
                    if (!candidate.location.scanChanges) {
                        verifiedPrivateFiles[candidate.file.absolutePath] =
                            VerifiedPrivateFile.capture(candidate.file, integrity.sha256)
                    }
                    SecureMediaFile(candidate.file, normalizedType, integrity.byteCount)
                }
            }
            if (accepted != null) return accepted
        }
        fileMutex.withLock { invalidateTokenLocked(keyToken) }
        return null
    }

    private suspend fun <T> withTokenLock(keyToken: String, action: suspend () -> T): T {
        val entry = synchronized(tokenLockGuard) {
            tokenLocks.getOrPut(keyToken, ::TokenLock).also { it.users += 1 }
        }
        return try {
            entry.mutex.withLock { action() }
        } finally {
            synchronized(tokenLockGuard) {
                entry.users -= 1
                if (entry.users == 0 && tokenLocks[keyToken] === entry) tokenLocks.remove(keyToken)
            }
        }
    }

    suspend fun remove(cacheKey: String) = withContext(Dispatchers.IO + NonCancellable) {
        val keyToken = token(cacheKey)
        withTokenLock(keyToken) {
            fileMutex.withLock {
                retainedBeforeQueueSnapshot.remove(keyToken)
                deleteTokenMediaLocked(keyToken)
                deletePrivateRecord(integrityFile(keyToken))
                deletePrivateRecord(retentionFile(keyToken))
            }
        }
        Unit
    }

    /** Releases one durable pin and immediately restores the LRU bound when possible. */
    suspend fun releaseRetention(cacheKey: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            val keyToken = token(cacheKey)
            withTokenLock(keyToken) {
                fileMutex.withLock {
                    retainedBeforeQueueSnapshot.remove(keyToken)
                    deletePrivateRecord(retentionFile(keyToken))
                    // A durable pin can be the only reason the store remains over budget. Restore
                    // the bound now instead of waiting for an unrelated future attachment.
                    evictDownTo(maximumBytes, keep = null)
                }
            }
        }

    /**
     * Reconciles durable pins against PREPARING intents restored at activation.
     *
     * A newly stored source receives an in-process reservation until a snapshot observes its
     * queue record. After process death that reservation intentionally disappears, allowing the
     * first activation pass to release a pin orphaned before enqueue. Released media stays cached
     * when it fits the budget and is otherwise immediately eligible for ordinary LRU eviction.
     */
    suspend fun pruneRetentions(retainedCacheKeys: Set<String>) =
        withContext(Dispatchers.IO + NonCancellable) {
            fileMutex.withLock {
                val retainedTokens = retainedCacheKeys.mapTo(mutableSetOf(), ::token)
                val protectedByStore = retainedBeforeQueueSnapshot.toSet()
                retainedBeforeQueueSnapshot.removeAll(retainedTokens)
                privateMetadataDirectory.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(RETENTION_SUFFIX) }
                    .forEach { marker ->
                        val markerToken = marker.name.removeSuffix(RETENTION_SUFFIX)
                        if (markerToken !in retainedTokens && markerToken !in protectedByStore) {
                            deletePrivateRecord(marker)
                        }
                    }
                evictDownTo(maximumBytes, keep = null)
            }
        }

    /** Drops every decrypted attachment; called when an authenticated owner signs out. */
    suspend fun clear(retiredOwnerScopeId: String? = null) {
        // Invalidate before switching dispatcher or waiting for a writer holding fileMutex.
        val barrier = beginClear(retiredOwnerScopeId)
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                fileMutex.withLock {
                    retainedBeforeQueueSnapshot.clear()
                    verifiedPrivateFiles.clear()
                    val failures = mutableListOf<Throwable>()
                    locations.forEach { location ->
                        val published = filesOwnedBy(location.root)
                        runCatching { deleteOwnedTree(location.root) }
                            .exceptionOrNull()
                            ?.let(failures::add)
                        if (location.scanChanges) {
                            published.filterNot(File::exists).forEach { notifyMediaChanged(it, null) }
                        }
                    }
                    runCatching { deleteOwnedTree(privateMetadataDirectory) }
                        .exceptionOrNull()
                        ?.let(failures::add)
                    if (failures.isNotEmpty()) {
                        val failure = IllegalStateException(
                            "Secure media could not be cleared completely",
                        )
                        failures.forEach { cause -> failure.addSuppressed(cause) }
                        throw failure
                    }
                }
                Unit
            }
        } finally {
            finishClear(barrier)
        }
    }

    /** Oldest-first eviction across external and fallback stores, retaining the entry just written. */
    private fun evictDownTo(limit: Long, keep: File?) {
        val entries = locations.flatMap { location ->
            filesOwnedBy(location.root).map { location to it }
        }.sortedBy { it.second.lastModified() }
        var total = entries.sumOf { it.second.length() }
        for ((location, candidate) in entries) {
            if (total <= limit) return
            if (candidate == keep) continue
            // A sender must never have to download content this device already possessed merely
            // to view it again. Sent Media has an explicit durable retention policy; only received
            // and legacy cache entries participate in automatic pressure eviction.
            if (isSentMedia(candidate)) continue
            val candidateToken = tokenFromFile(candidate) ?: continue
            if (retentionFile(candidateToken).isFile) continue
            val size = candidate.length()
            val deleted = SecureMediaLease.deleteIfNotPinned(candidate) {
                deletePublished(location, candidate)
            }
            if (deleted) {
                verifiedPrivateFiles.remove(candidate.absolutePath)
                total -= size
                if (!hasPublishedFile(candidateToken)) {
                    deletePrivateRecord(integrityFile(candidateToken))
                }
            }
        }
    }

    private fun isSentMedia(file: File): Boolean {
        val candidate = file.toPath().toAbsolutePath().normalize()
        return locations.any { location ->
            val sentRoot = File(location.root, LocalMediaCollection.SENT.directoryName)
                .toPath()
                .toAbsolutePath()
                .normalize()
            candidate.startsWith(sentRoot)
        }
    }

    private fun firstWritableDirectory(
        mediaType: String,
        collection: LocalMediaCollection?,
    ): Pair<SecureMediaLocation, File> {
        for (location in locations) {
            if (!location.acceptsWrites) continue
            val categoryRoot = collection?.let { File(location.root, it.directoryName) }
                ?: location.root
            val directory = File(categoryRoot, categoryDirectory(mediaType))
            val usable = runCatching {
                (directory.isDirectory || directory.mkdirs()) && directory.isDirectory &&
                    directory.canWrite()
            }.getOrDefault(false)
            if (usable) return location to directory
        }
        error("Secure media storage is unavailable")
    }

    private suspend fun awaitWriteFence(ownerScopeId: String?): SecureMediaWriteFence {
        while (true) {
            val barrier = synchronized(lifecycleLock) {
                ownerScopeId?.let {
                    require(it.isNotBlank()) { "A secure-media owner scope must not be blank" }
                    if (it in retiredOwnerScopes) throw SessionInvalidatedException()
                }
                clearBarrier ?: return SecureMediaWriteFence(
                    lifecycleGeneration,
                    ownerScopeId,
                )
            }
            // A successor account may start immediately after credential adoption. Let it wait
            // for the already-started global plaintext purge rather than publish and be erased.
            barrier.await()
        }
    }

    private fun requireWriteFence(
        fence: SecureMediaWriteFence,
        ownerIsCurrent: (() -> Boolean)?,
    ) {
        val cacheStillOwnsWrite = synchronized(lifecycleLock) {
            lifecycleGeneration == fence.generation &&
                clearBarrier == null &&
                (fence.ownerScopeId == null || fence.ownerScopeId !in retiredOwnerScopes)
        }
        if (!cacheStillOwnsWrite || ownerIsCurrent?.invoke() == false) {
            throw SessionInvalidatedException()
        }
    }

    private fun beginClear(ownerScopeId: String?): CompletableDeferred<Unit> =
        synchronized(lifecycleLock) {
            ownerScopeId?.let {
                require(it.isNotBlank()) { "A secure-media owner scope must not be blank" }
                retiredOwnerScopes += it
            }
            lifecycleGeneration += 1L
            activeClearCount += 1
            clearBarrier ?: CompletableDeferred<Unit>().also { clearBarrier = it }
        }

    private fun finishClear(barrier: CompletableDeferred<Unit>) {
        val release = synchronized(lifecycleLock) {
            check(activeClearCount > 0) { "Secure-media clear ownership was lost" }
            activeClearCount -= 1
            if (activeClearCount == 0) {
                check(clearBarrier === barrier) { "Secure-media clear barrier changed unexpectedly" }
                clearBarrier = null
                true
            } else {
                false
            }
        }
        if (release) barrier.complete(Unit)
    }

    private fun candidateFiles(
        keyToken: String,
        mediaType: String,
        collection: LocalMediaCollection?,
    ): List<LocatedFile> {
        val extension = chatMediaFileExtension(mediaType)
        return buildList {
            locations.forEach { location ->
                val collections = if (collection == null) {
                    LocalMediaCollection.entries
                } else {
                    listOf(collection)
                }
                collections.forEach { candidateCollection ->
                    add(
                        LocatedFile(
                            location,
                            File(
                                File(
                                    File(location.root, candidateCollection.directoryName),
                                    categoryDirectory(mediaType),
                                ),
                                "$keyToken.$extension",
                            ),
                        ),
                    )
                }
                // Read compatibility with the pre-collection type-specific layout.
                add(
                    LocatedFile(
                        location,
                        File(File(location.root, categoryDirectory(mediaType)), "$keyToken.$extension"),
                    ),
                )
                // Read-only compatibility with builds that retained every type as `<hash>.media`
                // directly under the no-backup root. A subsequent eviction or sign-out removes it.
                add(LocatedFile(location, File(location.root, "$keyToken$LEGACY_FILE_SUFFIX")))
            }
        }
    }

    private fun filesOwnedBy(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val legacy = root.listFiles().orEmpty().filter {
            it.isFile && it.name.endsWith(LEGACY_FILE_SUFFIX)
        }
        val categoryRoots = buildList {
            add(root)
            LocalMediaCollection.entries.forEach { add(File(root, it.directoryName)) }
        }
        val categorized = categoryRoots.flatMap { categoryRoot ->
            CATEGORY_DIRECTORIES.flatMap { category ->
                File(categoryRoot, category).listFiles().orEmpty().filter { candidate ->
                    candidate.isFile && !candidate.name.startsWith('.')
                }
            }
        }
        return legacy + categorized
    }

    private fun tokenFromFile(file: File): String? =
        file.name.substringBefore('.').takeIf(TOKEN_HEX::matches)

    private fun hasPublishedFile(keyToken: String): Boolean = locations.any { location ->
        filesOwnedBy(location.root).any { tokenFromFile(it) == keyToken }
    }

    private fun deleteTokenMediaLocked(keyToken: String) {
        locations.forEach { location ->
            filesOwnedBy(location.root)
                .filter { tokenFromFile(it) == keyToken }
                .forEach { candidate -> deletePublished(location, candidate) }
        }
    }

    private fun invalidateTokenLocked(keyToken: String) {
        retainedBeforeQueueSnapshot.remove(keyToken)
        deleteTokenMediaLocked(keyToken)
        deletePrivateRecord(integrityFile(keyToken))
        deletePrivateRecord(retentionFile(keyToken))
    }

    private fun removeInterruptedWritesLocked() {
        locations.forEach { location ->
            val categoryRoots = buildList {
                add(location.root)
                LocalMediaCollection.entries.forEach { add(File(location.root, it.directoryName)) }
            }
            (
                listOf(location.root) + categoryRoots.flatMap { root ->
                    CATEGORY_DIRECTORIES.map { File(root, it) }
                }
            )
                .forEach { directory ->
                    directory.listFiles().orEmpty()
                        .filter {
                            it.isFile && it.name.startsWith('.') &&
                                it.name.endsWith(".$SCRATCH_SUFFIX") &&
                                it.absolutePath !in activeScratchPaths
                        }
                        .forEach(File::delete)
                }
        }
        privateMetadataDirectory.listFiles().orEmpty()
            .filter {
                it.isFile && it.name.startsWith('.') && it.name.endsWith(".$SCRATCH_SUFFIX")
            }
            .forEach(File::delete)
    }

    private fun deletePublished(location: SecureMediaLocation, file: File): Boolean {
        if (!existsWithoutFollowingLinks(file)) return false
        val deleted = deleteFile(file)
        check(deleted || !existsWithoutFollowingLinks(file)) {
            "Secure media could not be deleted"
        }
        if (deleted) verifiedPrivateFiles.remove(file.absolutePath)
        if (deleted && location.scanChanges) notifyMediaChanged(file, null)
        return true
    }

    private fun notifyMediaChanged(file: File, mediaType: String?) {
        runCatching { mediaChanged(file, mediaType) }
    }

    private fun requireMediaType(mediaType: String): String =
        requireNotNull(normalizeLocalMediaType(mediaType)) {
            "Choose a supported photo, voice note, video or document"
        }

    private fun token(cacheKey: String): String {
        require(cacheKey.isNotBlank()) { "A secure attachment needs a cache key" }
        return MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun readIntegrity(keyToken: String): SecureMediaIntegrity? = runCatching {
        val file = integrityFile(keyToken)
        if (!file.isFile || file.length() !in 1L..MAXIMUM_METADATA_BYTES) return@runCatching null
        SecureMediaIntegrity.parse(file.readBytes())
    }.getOrNull()

    private fun integrityFile(keyToken: String): File =
        File(privateMetadataDirectory, "$keyToken$INTEGRITY_SUFFIX")

    private fun retentionFile(keyToken: String): File =
        File(privateMetadataDirectory, "$keyToken$RETENTION_SUFFIX")

    private fun writePrivateRecord(destination: File, bytes: ByteArray) {
        check(
            privateMetadataDirectory.isDirectory || privateMetadataDirectory.mkdirs(),
        ) { "Secure media metadata is unavailable" }
        val scratch = File(
            privateMetadataDirectory,
            ".${destination.name}-${UUID.randomUUID()}.$SCRATCH_SUFFIX",
        )
        try {
            check(scratch.createNewFile()) { "Secure media metadata could not be prepared" }
            FileOutputStream(scratch).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            atomicReplace(scratch, destination)
        } finally {
            if (scratch.exists()) scratch.delete()
        }
    }

    private fun deletePrivateRecord(file: File) {
        if (!existsWithoutFollowingLinks(file)) return
        val deleted = deleteFile(file)
        check(deleted || !existsWithoutFollowingLinks(file)) {
            "Secure media metadata could not be deleted"
        }
    }

    private fun atomicReplace(scratch: File, destination: File) {
        try {
            Files.move(
                scratch.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Both paths are in one app-owned directory. Providers that do not advertise
            // ATOMIC_MOVE still implement the same-directory replacement as one filesystem move.
            Files.move(
                scratch.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun deleteOwnedTree(root: File) {
        if (!existsWithoutFollowingLinks(root)) return
        // Files.walk does not follow symbolic links unless explicitly asked to. That matters for an
        // external-media directory: remove a hostile link itself, never anything it points at.
        val ownedPaths = mutableListOf<java.nio.file.Path>()
        Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> ownedPaths.add(path) }
        }
        val failures = mutableListOf<Throwable>()
        ownedPaths.forEach { path ->
            runCatching {
                val file = path.toFile()
                val deleted = deleteFile(file)
                check(deleted || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Secure media path could not be deleted"
                }
            }.exceptionOrNull()?.let(failures::add)
        }
        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("Secure media path could not be deleted completely")
            failures.forEach { cause -> failure.addSuppressed(cause) }
            throw failure
        }
    }

    private fun existsWithoutFollowingLinks(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private data class LocatedFile(val location: SecureMediaLocation, val file: File)

    internal companion object {
        const val DIRECTORY_NAME = "secure-media-cache"
        const val EXTERNAL_RELATIVE_DIRECTORY = "Kit Pay/Media"
        const val PRIVATE_RELATIVE_DIRECTORY = "Kit Pay/Media"
        const val METADATA_DIRECTORY_NAME = "secure-media-integrity"
        const val LEGACY_FILE_SUFFIX = ".media"
        // Source compatibility for callers that still inspect the pre-folder cache format.
        const val FILE_SUFFIX = LEGACY_FILE_SUFFIX
        const val SCRATCH_SUFFIX = "partial"
        const val INTEGRITY_SUFFIX = ".integrity"
        const val RETENTION_SUFFIX = ".retained"

        private const val MAXIMUM_METADATA_BYTES = 1_024L
        private val RETENTION_MARKER_BYTES = "kit-secure-media-retained-v1"
            .toByteArray(Charsets.UTF_8)
        private val TOKEN_HEX = Regex("^[0-9a-f]{64}$")

        internal val CATEGORY_DIRECTORIES = listOf(
            "Kit Pay Images",
            "Kit Pay Video",
            "Kit Pay Audio",
            "Kit Pay Documents",
        )

        internal val COLLECTION_DIRECTORIES =
            LocalMediaCollection.entries.map(LocalMediaCollection::directoryName)

        /**
         * Room for a handful of the largest attachments the wire allows, so scrolling back through
         * a conversation of big videos does not re-download every one of them.
         */
        const val MAXIMUM_BYTES = 6L * 200L * 1024L * 1024L

        private fun productionLocations(context: Context): List<SecureMediaLocation> {
            val external = runCatching {
                context.externalMediaDirs
                    .firstOrNull()
                    ?.takeIf {
                        Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                    }
                    ?.let(::externalDirectoryFor)
            }.getOrNull()
            val fallback = privateDirectoryFor(context.filesDir)
            val legacyPrivate = File(context.noBackupFilesDir, DIRECTORY_NAME)
            return buildList {
                // Local-first does not mean public-first. Keep plaintext originals inside the
                // credential-encrypted app sandbox whenever it is available.
                add(SecureMediaLocation(fallback, scanChanges = false))
                if (external?.absoluteFile != fallback.absoluteFile) {
                    external?.let { add(SecureMediaLocation(it, scanChanges = true)) }
                }
                if (
                    legacyPrivate.absoluteFile != external?.absoluteFile &&
                    legacyPrivate.absoluteFile != fallback.absoluteFile
                ) {
                    add(
                        SecureMediaLocation(
                            legacyPrivate,
                            scanChanges = false,
                            acceptsWrites = false,
                        ),
                    )
                }
            }
        }

        internal fun externalDirectoryFor(androidMediaAppDirectory: File): File =
            File(androidMediaAppDirectory, EXTERNAL_RELATIVE_DIRECTORY)

        internal fun privateDirectoryFor(filesDirectory: File): File =
            File(filesDirectory, PRIVATE_RELATIVE_DIRECTORY)

        internal fun metadataDirectoryFor(noBackupFilesDirectory: File): File =
            File(noBackupFilesDirectory, METADATA_DIRECTORY_NAME)

        private fun defaultMetadataDirectory(mediaDirectory: File): File {
            val parent = mediaDirectory.absoluteFile.parentFile ?: mediaDirectory.absoluteFile
            return File(parent, ".${mediaDirectory.name}-$METADATA_DIRECTORY_NAME")
        }

        private fun mediaScanner(context: Context): (File, String?) -> Unit =
            { file, mediaType ->
                runCatching {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        mediaType?.let { arrayOf(it) },
                        null,
                    )
                }
            }

        private fun categoryDirectory(mediaType: String): String = when {
            mediaType.startsWith("image/") -> CATEGORY_DIRECTORIES[0]
            mediaType.startsWith("video/") -> CATEGORY_DIRECTORIES[1]
            mediaType.startsWith("audio/") -> CATEGORY_DIRECTORIES[2]
            else -> CATEGORY_DIRECTORIES[3]
        }
    }
}
