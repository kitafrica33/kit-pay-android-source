package com.kit.wallet.data.messaging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ImmediateMediaMaterial(
    val plaintextBytes: Int,
    val ciphertextBytes: Int,
    val keyBase64: String,
    val sha256Base64: String,
)

/** The local ciphertext is absent or no longer matches its hardware-encrypted queue metadata. */
internal class ImmediateMediaSpoolUnavailableException(message: String) :
    IllegalStateException(message)

/** Process-local proof that an app-private ciphertext file is the revision already authenticated. */
private data class VerifiedCiphertextFile(
    val canonicalPath: String,
    val byteCount: Long,
    val modifiedAtMillis: Long,
    val sha256Hex: String,
) {
    companion object {
        fun capture(file: File, sha256Hex: String) = VerifiedCiphertextFile(
            canonicalPath = file.canonicalPath,
            byteCount = file.length(),
            modifiedAtMillis = file.lastModified(),
            sha256Hex = sha256Hex,
        )
    }
}

/** App-private ciphertext spool; it never writes plaintext into the queued network outbox. */
@Singleton
internal class ImmediateMediaSpool internal constructor(
    private val directory: File,
    /** Test seam for proving that the UI publishes before expensive encryption starts. */
    private val beforeEncryption: suspend () -> Unit = {},
    private val fileDigest: (File) -> ByteArray = File::streamingSha256,
) {
    private val fileMutex = Mutex()
    private val stagedBeforeQueueSnapshot = mutableSetOf<String>()
    /** A spool file is immutable after publication, so one authenticated hash per process suffices. */
    private val verifiedFiles = mutableMapOf<String, VerifiedCiphertextFile>()

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, DIRECTORY_NAME),
    )

    /**
     * Encrypts [source] straight into the spool, never materializing its plaintext in heap.
     *
     * The bytes are read once, at this moment, because that is while the caller still holds
     * whatever permission opened them — a picker's content URI may not survive until the queue
     * drains. After this returns, only ciphertext is needed to finish the send.
     */
    suspend fun stage(id: String, source: SecureMediaSource): ImmediateMediaMaterial {
        require(ImmediateSendIntent.CANONICAL_UUID.matches(id)) { "Invalid media-spool ID" }
        var streamed: MediaAttachmentStreamCipher.StreamedAttachment? = null
        try {
            beforeEncryption()
            streamed = withContext(Dispatchers.IO + NonCancellable) {
                fileMutex.withLock {
                    directory.mkdirs()
                    check(directory.isDirectory) { "Secure media outbox is unavailable" }
                    val temporary = File(directory, ".$id.tmp")
                    val destination = file(id)
                    try {
                        verifiedFiles.remove(id)
                        val produced = FileOutputStream(temporary).use { output ->
                            val result = source.open().use { input ->
                                MediaAttachmentStreamCipher.encrypt(
                                    source = input.buffered(),
                                    destination = output.buffered(),
                                    maximumPlaintextBytes = MAX_IMAGE_PLAINTEXT_BYTES,
                                )
                            }
                            output.fd.sync()
                            result
                        }
                        check(temporary.renameTo(destination)) {
                            "Secure media could not be committed to the local outbox"
                        }
                        // The encrypted queue record is persisted immediately after stage returns.
                        // Until a prune observes that record, an older snapshot must not delete
                        // this newly committed file out from under its caller.
                        stagedBeforeQueueSnapshot += id
                        verifiedFiles[id] = VerifiedCiphertextFile.capture(
                            destination,
                            produced.sha256.toLowercaseHex(),
                        )
                        produced
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                }
            }
            val owned = checkNotNull(streamed)
            return ImmediateMediaMaterial(
                plaintextBytes = owned.plaintextByteSize,
                ciphertextBytes = owned.ciphertextByteSize.toInt(),
                keyBase64 = Base64.getEncoder().encodeToString(owned.keyMaterial),
                sha256Base64 = Base64.getEncoder().encodeToString(owned.sha256),
            )
        } finally {
            streamed?.keyMaterial?.fill(0)
            streamed?.sha256?.fill(0)
        }
    }

    /**
     * Confirms the spooled blob is still exactly the one the queue recorded and hands back the
     * file itself, so the upload can stream from disk instead of through a 200 MB array.
     */
    suspend fun ciphertextFile(intent: ImmediateSendIntent): File {
        require(intent.kind == ImmediateSendKind.MEDIA)
        return verifiedFile(intent.id, intent.mediaCiphertextBytes.toLong(), intent.mediaSha256())
    }

    /**
     * [ciphertextFile] for one attachment of a queued album; each item is spooled under its own
     * attachment id and verified against its own queue metadata.
     */
    suspend fun albumItemCiphertextFile(
        intent: ImmediateSendIntent,
        attachmentId: String,
    ): File {
        require(intent.kind == ImmediateSendKind.MEDIA_V2)
        val item = requireNotNull(intent.mediaItems.firstOrNull { it.attachmentId == attachmentId }) {
            "The requested attachment does not belong to this album"
        }
        return verifiedFile(item.attachmentId, item.ciphertextBytes, item.ciphertextSha256())
    }

    private suspend fun verifiedFile(
        id: String,
        expectedCiphertextBytes: Long,
        expectedSha256: ByteArray,
    ): File = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val source = file(id)
                if (!source.isFile || source.length() != expectedCiphertextBytes) {
                    verifiedFiles.remove(id)
                    throw ImmediateMediaSpoolUnavailableException(
                        "The queued secure attachment is no longer available",
                    )
                }
                val expectedHex = expectedSha256.toLowercaseHex()
                val cached = verifiedFiles[id]
                if (cached == VerifiedCiphertextFile.capture(source, expectedHex)) return@withLock source
                val actual = fileDigest(source)
                try {
                    if (!MessageDigest.isEqual(expectedSha256, actual)) {
                        verifiedFiles.remove(id)
                        throw ImmediateMediaSpoolUnavailableException(
                            "The queued secure attachment failed its integrity check",
                        )
                    }
                    verifiedFiles[id] = VerifiedCiphertextFile.capture(source, expectedHex)
                    source
                } finally {
                    actual.fill(0)
                }
            } finally {
                expectedSha256.fill(0)
            }
        }
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        fileMutex.withLock {
            stagedBeforeQueueSnapshot.remove(id)
            verifiedFiles.remove(id)
            if (ImmediateSendIntent.CANONICAL_UUID.matches(id)) file(id).delete()
        }
    }

    suspend fun prune(retainedIds: Set<String>) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (!directory.isDirectory) return@withLock
            // A retained snapshot is proof that the corresponding hardware-encrypted intent was
            // committed. A stale snapshot taken while stage() was finishing is not proof of the
            // opposite, so it receives one reservation-protected pass.
            val protectedByStage = stagedBeforeQueueSnapshot.toSet()
            stagedBeforeQueueSnapshot.removeAll(retainedIds)
            directory.listFiles().orEmpty().forEach { candidate ->
                val id = candidate.name.removeSuffix(FILE_SUFFIX)
                if (
                    !candidate.name.endsWith(FILE_SUFFIX) ||
                    !ImmediateSendIntent.CANONICAL_UUID.matches(id) ||
                    (id !in retainedIds && id !in protectedByStage)
                ) {
                    if (candidate.delete()) verifiedFiles.remove(id)
                }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO + NonCancellable) {
        fileMutex.withLock {
            stagedBeforeQueueSnapshot.clear()
            verifiedFiles.clear()
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
    }

    private fun file(id: String): File = File(directory, "$id$FILE_SUFFIX")

    private fun ByteArray.toLowercaseHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val DIRECTORY_NAME = "secure-message-outbox"
        const val FILE_SUFFIX = ".ciphertext"
    }
}

/**
 * The spool file ids this queued send owns: a single media send is spooled under its own id, an
 * album under each item's attachment id. Prune retention and commit-time discard must both use
 * this, or an album's ciphertext would be swept while its intent is still queued.
 */
internal fun ImmediateSendIntent.spoolIds(): List<String> = when (kind) {
    ImmediateSendKind.MEDIA -> listOf(id)
    ImmediateSendKind.MEDIA_V2 -> mediaItems.map(ImmediateSendMediaItem::attachmentId)
    else -> emptyList()
}
