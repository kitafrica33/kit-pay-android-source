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

/** App-private ciphertext spool for local-first media sends; plaintext is never written to disk. */
@Singleton
internal class ImmediateMediaSpool internal constructor(
    private val directory: File,
    /** Test seam for proving that the UI publishes before expensive encryption starts. */
    private val beforeEncryption: suspend () -> Unit = {},
) {
    private val fileMutex = Mutex()
    private val stagedBeforeQueueSnapshot = mutableSetOf<String>()

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, DIRECTORY_NAME),
    )

    suspend fun stage(id: String, plaintext: ByteArray): ImmediateMediaMaterial {
        require(ImmediateSendIntent.CANONICAL_UUID.matches(id)) { "Invalid media-spool ID" }
        require(plaintext.isNotEmpty()) { "Choose a file to send securely" }
        require(plaintext.size <= MAX_IMAGE_PLAINTEXT_BYTES) {
            "Files up to ${MAX_IMAGE_PLAINTEXT_BYTES / (1024 * 1024)} MB are supported"
        }
        var encrypted: MediaAttachmentCipher.EncryptedAttachment? = null
        try {
            beforeEncryption()
            encrypted = withContext(Dispatchers.Default + NonCancellable) {
                MediaAttachmentCipher.encrypt(plaintext)
            }
            val owned = checkNotNull(encrypted)
            withContext(Dispatchers.IO + NonCancellable) {
                fileMutex.withLock {
                    directory.mkdirs()
                    check(directory.isDirectory) { "Secure media outbox is unavailable" }
                    val temporary = File(directory, ".$id.tmp")
                    val destination = file(id)
                    try {
                        FileOutputStream(temporary).use { output ->
                            output.write(owned.ciphertext)
                            output.fd.sync()
                        }
                        check(temporary.renameTo(destination)) {
                            "Secure media could not be committed to the local outbox"
                        }
                        // The encrypted queue record is persisted immediately after stage returns.
                        // Until a prune observes that record, an older snapshot must not delete
                        // this newly committed file out from under its caller.
                        stagedBeforeQueueSnapshot += id
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                }
            }
            return ImmediateMediaMaterial(
                plaintextBytes = owned.plaintextSize,
                ciphertextBytes = owned.ciphertext.size,
                keyBase64 = Base64.getEncoder().encodeToString(owned.keyMaterial),
                sha256Base64 = Base64.getEncoder().encodeToString(owned.sha256),
            )
        } finally {
            encrypted?.ciphertext?.fill(0)
            encrypted?.keyMaterial?.fill(0)
            encrypted?.sha256?.fill(0)
        }
    }

    suspend fun readCiphertext(intent: ImmediateSendIntent): ByteArray {
        require(intent.kind == ImmediateSendKind.MEDIA)
        return withContext(Dispatchers.IO) {
            fileMutex.withLock {
                val source = file(intent.id)
                if (!source.isFile || source.length() != intent.mediaCiphertextBytes.toLong()) {
                    throw ImmediateMediaSpoolUnavailableException(
                        "The queued secure attachment is no longer available",
                    )
                }
                val bytes = source.readBytes()
                val expected = intent.mediaSha256()
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                try {
                    if (!MessageDigest.isEqual(expected, actual)) {
                        bytes.fill(0)
                        throw ImmediateMediaSpoolUnavailableException(
                            "The queued secure attachment failed its integrity check",
                        )
                    }
                    bytes
                } finally {
                    expected.fill(0)
                    actual.fill(0)
                }
            }
        }
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        fileMutex.withLock {
            stagedBeforeQueueSnapshot.remove(id)
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
                    candidate.delete()
                }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO + NonCancellable) {
        fileMutex.withLock {
            stagedBeforeQueueSnapshot.clear()
            directory.listFiles().orEmpty().forEach(File::delete)
            directory.delete()
        }
    }

    private fun file(id: String): File = File(directory, "$id$FILE_SUFFIX")

    private companion object {
        const val DIRECTORY_NAME = "secure-message-outbox"
        const val FILE_SUFFIX = ".ciphertext"
    }
}
