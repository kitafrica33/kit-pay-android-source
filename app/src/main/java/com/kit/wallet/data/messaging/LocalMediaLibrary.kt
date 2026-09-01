package com.kit.wallet.data.messaging

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which durable, account-scoped media collection owns an attachment. */
enum class LocalMediaCollection(internal val directoryName: String) {
    SENT("Sent Media"),
    RECEIVED("Received Media"),
}

enum class LocalMediaProcessingState { ORIGINAL_READY, PROCESSING, READY, FAILED }
enum class LocalMediaUploadState { NOT_REQUIRED, PENDING, UPLOADING, UPLOADED, FAILED }
enum class LocalMediaDownloadState { NOT_REQUIRED, PENDING, DOWNLOADING, DOWNLOADED, FAILED }
enum class LocalMediaEncryptionState { PENDING, ENCRYPTING, ENCRYPTED, FAILED }
enum class LocalMediaAvailabilityState { AVAILABLE, MISSING }

/**
 * Durable identity and lifecycle facts for one local media object.
 *
 * [localReference] is an opaque cache key rather than an absolute pathname. The cache may move a
 * file between private and app-owned external storage without invalidating this record, and the
 * encrypted state database never exposes a filesystem path. A remote object is deliberately only
 * one field: [mediaId] remains the permanent identity before, during and after upload.
 */
internal data class LocalMediaRecord(
    val ownerScopeId: String,
    val conversationId: String,
    val messageId: String,
    val mediaId: String,
    val collection: LocalMediaCollection,
    val localReference: String,
    val remoteEncryptedObjectReference: String? = null,
    val mimeType: String,
    val fileSize: Long,
    val durationMillis: Long? = null,
    val processingState: LocalMediaProcessingState,
    val uploadState: LocalMediaUploadState,
    val downloadState: LocalMediaDownloadState,
    val encryptionState: LocalMediaEncryptionState,
    val availabilityState: LocalMediaAvailabilityState,
    val selectedAtEpochMillis: Long,
    val visibleAtEpochMillis: Long,
    val localAvailableAtEpochMillis: Long?,
    val encryptionCompletedAtEpochMillis: Long? = null,
    val uploadedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(ownerScopeId.isNotBlank())
        require(ADDRESS.matches(conversationId)) { "Invalid local-media conversation" }
        require(ImmediateSendIntent.CANONICAL_UUID.matches(messageId)) {
            "Invalid local-media message ID"
        }
        require(ImmediateSendIntent.CANONICAL_UUID.matches(mediaId)) {
            "Invalid local-media ID"
        }
        require(localReference.isNotBlank() && localReference.length <= MAX_REFERENCE_CHARS)
        remoteEncryptedObjectReference?.let {
            require(it.isNotBlank() && it.length <= MAX_REFERENCE_CHARS)
        }
        require(normalizeLocalMediaType(mimeType) == mimeType)
        require(fileSize in 0..MAX_LOCAL_MEDIA_ORIGINAL_BYTES.toLong())
        require(
            fileSize > 0L ||
                availabilityState == LocalMediaAvailabilityState.MISSING &&
                processingState == LocalMediaProcessingState.PROCESSING,
        ) { "Only an unfinished local import or download can have an unknown size" }
        require(
            durationMillis == null ||
                durationMillis in 1..MAX_LOCAL_MEDIA_DURATION_MILLIS &&
                (mimeType.startsWith("audio/") || mimeType.startsWith("video/")),
        ) { "Invalid local-media duration" }
        require(selectedAtEpochMillis > 0L)
        require(visibleAtEpochMillis >= selectedAtEpochMillis)
        require(localAvailableAtEpochMillis == null || localAvailableAtEpochMillis >= selectedAtEpochMillis)
        require(encryptionCompletedAtEpochMillis == null || collection == LocalMediaCollection.SENT)
        require(uploadedAtEpochMillis == null || collection == LocalMediaCollection.SENT)
        require(updatedAtEpochMillis >= selectedAtEpochMillis)
        when (collection) {
            LocalMediaCollection.SENT -> {
                require(downloadState == LocalMediaDownloadState.NOT_REQUIRED)
                require(uploadState != LocalMediaUploadState.NOT_REQUIRED)
            }
            LocalMediaCollection.RECEIVED -> {
                require(uploadState == LocalMediaUploadState.NOT_REQUIRED)
                require(encryptionState == LocalMediaEncryptionState.ENCRYPTED)
            }
        }
    }

    companion object {
        private const val MAX_REFERENCE_CHARS = 1_024
        private val ADDRESS = Regex("^[A-Za-z0-9._:@-]{1,160}$")
    }
}

/**
 * Account-fenced, hardware-encrypted local Sent/Received Media index.
 *
 * Bytes live in [SecureMediaCache]; this index lives in the same Android-Keystore-protected Room
 * boundary as the secure outbox. Updates are idempotent and compare-and-set, so a retry or a
 * process restart advances one permanent media ID instead of creating another object.
 */
@Singleton
internal class LocalMediaLibrary @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
    private val sessions: SessionStore,
) {
    private val mutex = Mutex()

    /**
     * Commits the permanent local-media identity before a potentially large provider copy starts.
     * A crash can therefore be reconciled from the matching IMPORTING send intent rather than
     * silently losing the attachment. The opaque reference already names its eventual final file.
     */
    suspend fun recordImportPending(owner: SessionFence, record: LocalMediaRecord) {
        require(record.ownerScopeId == owner.cacheScopeId)
        require(record.collection == LocalMediaCollection.SENT)
        require(record.processingState == LocalMediaProcessingState.PROCESSING)
        require(record.availabilityState == LocalMediaAvailabilityState.MISSING)
        mutate(owner, record.mediaId) { existing ->
            if (existing == null) {
                record
            } else {
                require(existing.sameIdentityAs(record)) {
                    "A local-media identity belongs to another attachment"
                }
                // Never regress an original that a concurrent import/recovery already published.
                if (existing.availabilityState == LocalMediaAvailabilityState.AVAILABLE) {
                    existing
                } else {
                    existing.copy(
                        localReference = record.localReference,
                        mimeType = record.mimeType,
                        fileSize = maxOf(existing.fileSize, record.fileSize),
                        updatedAtEpochMillis =
                            maxOf(existing.updatedAtEpochMillis, record.updatedAtEpochMillis),
                    )
                }
            }
        }
    }

    suspend fun recordLocalOriginal(owner: SessionFence, record: LocalMediaRecord) {
        require(record.ownerScopeId == owner.cacheScopeId)
        require(record.collection == LocalMediaCollection.SENT)
        mutate(owner, record.mediaId) { existing ->
            if (existing == null) {
                record
            } else {
                require(existing.sameIdentityAs(record)) {
                    "A local-media identity belongs to another attachment"
                }
                existing.copy(
                    localReference = record.localReference,
                    mimeType = record.mimeType,
                    fileSize = record.fileSize,
                    durationMillis = record.durationMillis ?: existing.durationMillis,
                    processingState = LocalMediaProcessingState.ORIGINAL_READY,
                    uploadState = if (existing.uploadState == LocalMediaUploadState.UPLOADED) {
                        LocalMediaUploadState.UPLOADED
                    } else {
                        LocalMediaUploadState.PENDING
                    },
                    encryptionState = if (
                        existing.encryptionState == LocalMediaEncryptionState.FAILED
                    ) {
                        LocalMediaEncryptionState.PENDING
                    } else {
                        existing.encryptionState
                    },
                    availabilityState = LocalMediaAvailabilityState.AVAILABLE,
                    localAvailableAtEpochMillis =
                        existing.localAvailableAtEpochMillis ?: record.localAvailableAtEpochMillis,
                    updatedAtEpochMillis = maxOf(existing.updatedAtEpochMillis, record.updatedAtEpochMillis),
                )
            }
        }
    }

    suspend fun markEncrypting(owner: SessionFence, mediaId: String, atEpochMillis: Long) =
        update(owner, mediaId) {
            it.copy(
                processingState = LocalMediaProcessingState.PROCESSING,
                encryptionState = LocalMediaEncryptionState.ENCRYPTING,
                updatedAtEpochMillis = atEpochMillis,
            )
        }

    suspend fun markEncrypted(owner: SessionFence, mediaId: String, atEpochMillis: Long) =
        update(owner, mediaId) {
            it.copy(
                processingState = LocalMediaProcessingState.READY,
                encryptionState = LocalMediaEncryptionState.ENCRYPTED,
                encryptionCompletedAtEpochMillis =
                    it.encryptionCompletedAtEpochMillis ?: atEpochMillis,
                updatedAtEpochMillis = atEpochMillis,
            )
        }

    suspend fun markUploadStarted(owner: SessionFence, mediaId: String, atEpochMillis: Long) =
        update(owner, mediaId) {
            it.copy(
                uploadState = LocalMediaUploadState.UPLOADING,
                updatedAtEpochMillis = atEpochMillis,
            )
        }

    suspend fun markUploaded(
        owner: SessionFence,
        mediaId: String,
        remoteEncryptedObjectReference: String,
        atEpochMillis: Long,
    ) = update(owner, mediaId) {
        it.copy(
            remoteEncryptedObjectReference = remoteEncryptedObjectReference,
            uploadState = LocalMediaUploadState.UPLOADED,
            uploadedAtEpochMillis = it.uploadedAtEpochMillis ?: atEpochMillis,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    suspend fun markSendFailure(
        owner: SessionFence,
        mediaId: String,
        permanent: Boolean,
        atEpochMillis: Long,
    ) = update(owner, mediaId) {
        it.copy(
            processingState = if (permanent && it.fileSize > 0L) {
                LocalMediaProcessingState.FAILED
            } else {
                it.processingState
            },
            uploadState = if (permanent) LocalMediaUploadState.FAILED else LocalMediaUploadState.PENDING,
            encryptionState = if (permanent && it.encryptionState != LocalMediaEncryptionState.ENCRYPTED) {
                LocalMediaEncryptionState.FAILED
            } else {
                it.encryptionState
            },
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    suspend fun recordReceivePending(owner: SessionFence, record: LocalMediaRecord) {
        require(record.ownerScopeId == owner.cacheScopeId)
        require(record.collection == LocalMediaCollection.RECEIVED)
        mutate(owner, record.mediaId) { existing ->
            if (existing == null) record else {
                require(existing.sameIdentityAs(record)) {
                    "A local-media identity belongs to another attachment"
                }
                existing.copy(
                    remoteEncryptedObjectReference =
                        record.remoteEncryptedObjectReference ?: existing.remoteEncryptedObjectReference,
                    downloadState = if (existing.availabilityState == LocalMediaAvailabilityState.AVAILABLE) {
                        LocalMediaDownloadState.DOWNLOADED
                    } else {
                        LocalMediaDownloadState.DOWNLOADING
                    },
                    updatedAtEpochMillis = maxOf(existing.updatedAtEpochMillis, record.updatedAtEpochMillis),
                )
            }
        }
    }

    suspend fun markReceivedAvailable(
        owner: SessionFence,
        mediaId: String,
        fileSize: Long,
        durationMillis: Long?,
        atEpochMillis: Long,
    ) = update(owner, mediaId) {
        it.copy(
            fileSize = fileSize,
            durationMillis = durationMillis ?: it.durationMillis,
            processingState = LocalMediaProcessingState.READY,
            downloadState = LocalMediaDownloadState.DOWNLOADED,
            availabilityState = LocalMediaAvailabilityState.AVAILABLE,
            localAvailableAtEpochMillis = it.localAvailableAtEpochMillis ?: atEpochMillis,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    suspend fun markDownloadFailed(owner: SessionFence, mediaId: String, atEpochMillis: Long) =
        update(owner, mediaId) {
            it.copy(
                downloadState = LocalMediaDownloadState.FAILED,
                availabilityState = LocalMediaAvailabilityState.MISSING,
                updatedAtEpochMillis = atEpochMillis,
            )
        }

    suspend fun find(owner: SessionFence, mediaId: String): LocalMediaRecord? =
        withOwner(owner) {
            val stored = stateStore.read(NAMESPACE, recordKey(mediaId)) ?: return@withOwner null
            try {
                LocalMediaRecordCodec.decode(stored.bytes)?.takeIf {
                    it.ownerScopeId == owner.cacheScopeId && it.mediaId == mediaId
                }
            } finally {
                stored.bytes.fill(0)
            }
        }

    private suspend fun update(
        owner: SessionFence,
        mediaId: String,
        transform: (LocalMediaRecord) -> LocalMediaRecord,
    ) = mutate(owner, mediaId) { existing -> existing?.let(transform) }

    private suspend fun mutate(
        owner: SessionFence,
        mediaId: String,
        transform: (LocalMediaRecord?) -> LocalMediaRecord?,
    ) = withOwner(owner) {
        mutex.withLock {
            val stored = stateStore.read(NAMESPACE, recordKey(mediaId))
            val existing = stored?.let {
                try {
                    LocalMediaRecordCodec.decode(it.bytes)
                } finally {
                    it.bytes.fill(0)
                }
            }
            val updated = transform(existing) ?: return@withLock
            require(updated.ownerScopeId == owner.cacheScopeId && updated.mediaId == mediaId)
            val encoded = LocalMediaRecordCodec.encode(updated)
            try {
                stateStore.write(
                    namespace = NAMESPACE,
                    recordKey = recordKey(mediaId),
                    expectedVersion = stored?.version,
                    bytes = encoded,
                )
            } finally {
                encoded.fill(0)
            }
        }
    }

    private suspend fun <T> withOwner(owner: SessionFence, block: suspend () -> T): T =
        sessions.withCurrentSession(owner) { current ->
            if (current.fence() != owner) throw SessionInvalidatedException()
            stateStore.withStateLease(block)
        }

    private fun LocalMediaRecord.sameIdentityAs(other: LocalMediaRecord): Boolean =
        ownerScopeId == other.ownerScopeId &&
            conversationId == other.conversationId &&
            messageId == other.messageId &&
            mediaId == other.mediaId &&
            collection == other.collection

    private fun recordKey(mediaId: String): String {
        require(ImmediateSendIntent.CANONICAL_UUID.matches(mediaId)) { "Invalid local-media ID" }
        return "media:$mediaId"
    }

    private companion object {
        const val NAMESPACE = "local-media-library"
    }
}

/** Strict bounded codec; unknown versions and malformed lifecycle combinations fail closed. */
internal object LocalMediaRecordCodec {
    private const val VERSION = 1
    private const val MAX_RECORD_BYTES = 16 * 1024
    private const val MAX_STRING_BYTES = 4 * 1024

    fun encode(record: LocalMediaRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(VERSION)
            data.writeString(record.ownerScopeId)
            data.writeString(record.conversationId)
            data.writeString(record.messageId)
            data.writeString(record.mediaId)
            data.writeEnum(record.collection)
            data.writeString(record.localReference)
            data.writeNullableString(record.remoteEncryptedObjectReference)
            data.writeString(record.mimeType)
            data.writeLong(record.fileSize)
            data.writeNullableLong(record.durationMillis)
            data.writeEnum(record.processingState)
            data.writeEnum(record.uploadState)
            data.writeEnum(record.downloadState)
            data.writeEnum(record.encryptionState)
            data.writeEnum(record.availabilityState)
            data.writeLong(record.selectedAtEpochMillis)
            data.writeLong(record.visibleAtEpochMillis)
            data.writeNullableLong(record.localAvailableAtEpochMillis)
            data.writeNullableLong(record.encryptionCompletedAtEpochMillis)
            data.writeNullableLong(record.uploadedAtEpochMillis)
            data.writeLong(record.updatedAtEpochMillis)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_RECORD_BYTES) { "Local-media record is too large" }
        }
    }

    fun decode(bytes: ByteArray): LocalMediaRecord? {
        if (bytes.isEmpty() || bytes.size > MAX_RECORD_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readUnsignedByte() != VERSION) return null
                val ownerScopeId = data.readString()
                val conversationId = data.readString()
                val messageId = data.readString()
                val mediaId = data.readString()
                val collection: LocalMediaCollection = data.readEnum()
                val localReference = data.readString()
                val remoteEncryptedObjectReference = data.readNullableString()
                val mimeType = data.readString()
                val fileSize = data.readLong()
                // Version 1 originally accepted zero, unbounded, and non-audio/video durations.
                // Keep those records readable after the stricter invariant shipped: duration is
                // presentation metadata, so discarding only the invalid value is safer than
                // orphaning an otherwise valid local original during an app upgrade.
                val durationMillis = data.readNullableLong()?.takeIf { duration ->
                    duration in 1..MAX_LOCAL_MEDIA_DURATION_MILLIS &&
                        (mimeType.startsWith("audio/") || mimeType.startsWith("video/"))
                }
                val decoded = LocalMediaRecord(
                    ownerScopeId = ownerScopeId,
                    conversationId = conversationId,
                    messageId = messageId,
                    mediaId = mediaId,
                    collection = collection,
                    localReference = localReference,
                    remoteEncryptedObjectReference = remoteEncryptedObjectReference,
                    mimeType = mimeType,
                    fileSize = fileSize,
                    durationMillis = durationMillis,
                    processingState = data.readEnum(),
                    uploadState = data.readEnum(),
                    downloadState = data.readEnum(),
                    encryptionState = data.readEnum(),
                    availabilityState = data.readEnum(),
                    selectedAtEpochMillis = data.readLong(),
                    visibleAtEpochMillis = data.readLong(),
                    localAvailableAtEpochMillis = data.readNullableLong(),
                    encryptionCompletedAtEpochMillis = data.readNullableLong(),
                    uploadedAtEpochMillis = data.readNullableLong(),
                    updatedAtEpochMillis = data.readLong(),
                )
                if (data.available() != 0) return null
                decoded
            }
        }.getOrNull()
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) { "Local-media field is too large" }
        writeInt(encoded.size)
        write(encoded)
        encoded.fill(0)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES && length <= available())
        val encoded = ByteArray(length)
        readFully(encoded)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(encoded))
                .toString()
        } finally {
            encoded.fill(0)
        }
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private inline fun <reified T : Enum<T>> DataOutputStream.writeEnum(value: T) =
        writeString(value.name)

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T =
        enumValueOf(readString())
}
