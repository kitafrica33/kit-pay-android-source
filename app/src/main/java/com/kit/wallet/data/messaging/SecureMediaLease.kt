package com.kit.wallet.data.messaging

import android.content.Context
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Why a retained attachment needs a stable second name. */
private enum class SecureMediaLeasePurpose(val tag: String) {
    PLAYBACK("playback"),
    EXTERNAL_HANDOFF("external"),
}

/**
 * A no-copy snapshot of one authenticated local attachment.
 *
 * Cache entries are intentionally evictable. Passing their pathname directly to a [android.widget.VideoView]
 * or [androidx.core.content.FileProvider] creates a race: another download can unlink that pathname
 * before the asynchronous consumer opens it. A hard link gives the consumer a distinct stable name
 * for the same inode and consumes no second copy of a potentially 200 MiB attachment.
 *
 * Android's emulated external-media filesystem can reject hard links on some devices. In that case
 * a tiny durable marker pins the original cache name instead. [deleteIfNotPinned] serializes marker
 * creation with LRU deletion, so a successfully acquired lease can never lose that race.
 */
internal class SecureMediaLease private constructor(
    val file: File,
    private val artifact: File,
    private val purpose: SecureMediaLeasePurpose,
) : Closeable {
    private val disposition = AtomicInteger(OPEN)

    /**
     * Transfers lifetime to an external app after Android accepted its content-URI intent.
     *
     * There is no reliable callback for when a chooser/document viewer has opened its URI. The
     * lease therefore remains for a bounded period and is reaped on a later handoff or process
     * start. Once the consumer has opened it, Unix descriptor semantics keep the bytes readable
     * even after that cleanup.
     */
    fun detachForExternalConsumer() {
        check(purpose == SecureMediaLeasePurpose.EXTERNAL_HANDOFF) {
            "Only an external media lease can be detached"
        }
        if (disposition.compareAndSet(OPEN, DETACHED)) {
            synchronized(FILE_GUARD) { ACTIVE_ARTIFACTS.remove(artifact.absolutePath) }
        }
    }

    override fun close() {
        if (!disposition.compareAndSet(OPEN, CLOSED)) return
        synchronized(FILE_GUARD) {
            ACTIVE_ARTIFACTS.remove(artifact.absolutePath)
            deleteArtifactLocked(artifact)
        }
    }

    companion object {
        internal const val LEASE_DIRECTORY_NAME = ".viewer-leases"
        internal const val EXTERNAL_LEASE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        internal const val MAXIMUM_EXTERNAL_LEASES_PER_STORE = 12

        private const val OPEN = 0
        private const val DETACHED = 1
        private const val CLOSED = 2
        private const val MAXIMUM_CLOCK_SKEW_MILLIS = 5L * 60L * 1_000L
        private val FILE_GUARD = Any()
        /** Artifacts still owned by live in-process callers must never be reaped by maintenance. */
        private val ACTIVE_ARTIFACTS = mutableSetOf<String>()

        fun forPlayback(context: Context, media: SecureMediaFile): SecureMediaLease =
            acquire(context, media, SecureMediaLeasePurpose.PLAYBACK)

        fun forExternalHandoff(context: Context, media: SecureMediaFile): SecureMediaLease =
            acquire(context, media, SecureMediaLeasePurpose.EXTERNAL_HANDOFF)

        /**
         * Runs an LRU deletion only when no marker-based lease owns [source].
         *
         * Hard-link leases need no marker and remain valid after deletion. The shared guard closes
         * the only race in the fallback path: either acquisition pins first, or deletion wins and
         * acquisition reports that the media must be loaded again.
         */
        fun deleteIfNotPinned(source: File, delete: () -> Boolean): Boolean =
            synchronized(FILE_GUARD) {
                val leaseDirectory = inferredLeaseDirectory(source)
                val now = System.currentTimeMillis()
                purgeExpiredExternalArtifactsLocked(leaseDirectory, now)
                if (activePinFiles(source, leaseDirectory, now).isNotEmpty()) false else delete()
            }

        /** Removes leases that cannot still have an in-process owner, while preserving fresh grants. */
        fun purgeAfterProcessRestart(context: Context) = synchronized(FILE_GUARD) {
            val now = System.currentTimeMillis()
            providerVisibleMediaRoots(context).forEach { root ->
                val leaseDirectory = File(root, LEASE_DIRECTORY_NAME)
                leaseDirectory.listFiles().orEmpty().forEach { artifact ->
                    if (artifact.absolutePath in ACTIVE_ARTIFACTS) return@forEach
                    when {
                        artifact.isPlaybackArtifact() -> deleteArtifactLocked(artifact)
                        artifact.isExternalArtifact() && artifact.isExpired(now) ->
                            deleteArtifactLocked(artifact)
                        !artifact.isRecognizedArtifact() -> deleteArtifactLocked(artifact)
                    }
                }
                trimExternalArtifactsLocked(leaseDirectory)
                leaseDirectory.takeIf { it.list().isNullOrEmpty() }?.delete()
            }
        }

        private fun acquire(
            context: Context,
            media: SecureMediaFile,
            purpose: SecureMediaLeasePurpose,
        ): SecureMediaLease = synchronized(FILE_GUARD) {
            val source = media.file.absoluteFile
            check(source.isFile && source.length() == media.byteCount && media.byteCount > 0L) {
                "This locally stored media is no longer available"
            }
            val location = resolveLocation(context.applicationContext, source)
            val leaseDirectory = location.leaseDirectory
            check(leaseDirectory.isDirectory || leaseDirectory.mkdirs()) {
                "The media viewer could not prepare storage"
            }
            val now = System.currentTimeMillis()
            purgeExpiredExternalArtifactsLocked(leaseDirectory, now)
            if (purpose == SecureMediaLeasePurpose.EXTERNAL_HANDOFF) {
                trimExternalArtifactsLocked(
                    leaseDirectory,
                    keepAtMost = MAXIMUM_EXTERNAL_LEASES_PER_STORE - 1,
                )
            }

            val id = UUID.randomUUID().toString()
            val extension = source.extension.takeIf(String::isNotBlank) ?: "media"
            val linked = File(
                leaseDirectory,
                "${source.nameWithoutExtension}--$now-$id--${purpose.tag}.lease.$extension",
            )
            val linkedSuccessfully = runCatching {
                Os.link(source.absolutePath, linked.absolutePath)
                check(linked.isFile && linked.length() == media.byteCount) {
                    "The stable media link is incomplete"
                }
            }.isSuccess
            if (linkedSuccessfully) {
                ACTIVE_ARTIFACTS += linked.absolutePath
                return@synchronized SecureMediaLease(linked, linked, purpose)
            }
            runCatching { linked.delete() }

            // noBackup -> filesDir is always the same private filesystem and should support links.
            // Its original path is intentionally absent from FileProvider, so direct pinning would
            // either be unusable or require exposing a broad private root. Fail closed instead.
            check(location.canPinOriginal) { "The media viewer could not prepare storage" }

            val marker = File(
                leaseDirectory,
                "${source.name}--$now-$id--${purpose.tag}.pin",
            )
            try {
                check(marker.createNewFile()) { "The media viewer could not reserve storage" }
                FileOutputStream(marker).use { output ->
                    output.write("kit-secure-media-lease-v1\n".toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                // LRU deletion uses FILE_GUARD as well, so this second check proves the source
                // stayed present for the complete transition from cache file to pinned lease.
                check(source.isFile && source.length() == media.byteCount) {
                    "This locally stored media is no longer available"
                }
                ACTIVE_ARTIFACTS += marker.absolutePath
                SecureMediaLease(source, marker, purpose)
            } catch (error: Throwable) {
                deleteArtifactLocked(marker)
                throw error
            }
        }

        private fun resolveLocation(context: Context, source: File): LeaseLocation {
            val externalRoots = context.externalMediaDirs
                .mapNotNull { directory ->
                    directory?.let { SecureMediaCache.externalDirectoryFor(it) }
                }
            val privateRoot = SecureMediaCache.privateDirectoryFor(context.filesDir)
            val legacyRoot = File(context.noBackupFilesDir, SecureMediaCache.DIRECTORY_NAME)
            val directRoot = (externalRoots + privateRoot)
                .sortedByDescending { it.absolutePath.length }
                .firstOrNull { source.isWithin(it) }
            if (directRoot != null) {
                return LeaseLocation(
                    leaseDirectory = File(directRoot, LEASE_DIRECTORY_NAME),
                    canPinOriginal = true,
                )
            }
            check(source.isWithin(legacyRoot)) {
                "Kit Pay could not grant this locally stored media"
            }
            return LeaseLocation(
                // Both directories are credential-encrypted app-private paths on /data. The link
                // moves no bytes while placing the legacy inode under the narrow provider root.
                leaseDirectory = File(privateRoot, LEASE_DIRECTORY_NAME),
                canPinOriginal = false,
            )
        }

        private fun providerVisibleMediaRoots(context: Context): List<File> =
            buildList {
                context.externalMediaDirs.mapNotNullTo(this) { directory ->
                    directory?.let { SecureMediaCache.externalDirectoryFor(it) }
                }
                add(SecureMediaCache.privateDirectoryFor(context.filesDir))
            }.distinctBy { it.absolutePath }

        private fun inferredLeaseDirectory(source: File): File {
            val parent = source.absoluteFile.parentFile
                ?: return File(source.absoluteFile, LEASE_DIRECTORY_NAME)
            val storeRoot = if (parent.name in SecureMediaCache.CATEGORY_DIRECTORIES) {
                parent.parentFile ?: parent
            } else {
                parent
            }
            return File(storeRoot, LEASE_DIRECTORY_NAME)
        }

        private fun activePinFiles(
            source: File,
            leaseDirectory: File,
            now: Long,
        ): List<File> {
            val prefix = "${source.name}--"
            return leaseDirectory.listFiles().orEmpty().filter { artifact ->
                artifact.isFile && artifact.name.startsWith(prefix) &&
                    artifact.name.endsWith(".pin") && when {
                        artifact.isPlaybackArtifact() ->
                            artifact.absolutePath in ACTIVE_ARTIFACTS
                        artifact.isExternalArtifact() -> !artifact.isExpired(now)
                        else -> false
                    }
            }
        }

        private fun purgeExpiredExternalArtifactsLocked(directory: File, now: Long) {
            directory.listFiles().orEmpty()
                .filter { it.isExternalArtifact() && it.isExpired(now) }
                .forEach(::deleteArtifactLocked)
        }

        private fun trimExternalArtifactsLocked(
            directory: File,
            keepAtMost: Int = MAXIMUM_EXTERNAL_LEASES_PER_STORE,
        ) {
            val external = directory.listFiles().orEmpty()
                .filter { artifact ->
                    artifact.isExternalArtifact() &&
                        artifact.absolutePath !in ACTIVE_ARTIFACTS
                }
                .sortedBy { artifact -> artifact.createdAtMillis() ?: Long.MIN_VALUE }
            external.take((external.size - keepAtMost).coerceAtLeast(0))
                .forEach(::deleteArtifactLocked)
        }

        private fun File.isExpired(now: Long): Boolean {
            val created = createdAtMillis() ?: return true
            return created <= 0L || created > now + MAXIMUM_CLOCK_SKEW_MILLIS ||
                now - created > EXTERNAL_LEASE_MAX_AGE_MILLIS
        }

        /** Link inode timestamps also belong to the cache source, so lease age lives in its name. */
        private fun File.createdAtMillis(): Long? =
            name.substringAfter("--", missingDelimiterValue = "")
                .substringBefore('-', missingDelimiterValue = "")
                .toLongOrNull()

        private fun File.isPlaybackArtifact(): Boolean =
            name.contains("--${SecureMediaLeasePurpose.PLAYBACK.tag}.")

        private fun File.isExternalArtifact(): Boolean =
            name.contains("--${SecureMediaLeasePurpose.EXTERNAL_HANDOFF.tag}.")

        private fun File.isRecognizedArtifact(): Boolean =
            isPlaybackArtifact() || isExternalArtifact()

        private fun File.isWithin(root: File): Boolean = runCatching {
            canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
        }.getOrDefault(false)

        private fun deleteArtifactLocked(artifact: File) {
            runCatching { artifact.delete() }
            artifact.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }

        private data class LeaseLocation(
            val leaseDirectory: File,
            val canPinOriginal: Boolean,
        )
    }
}
