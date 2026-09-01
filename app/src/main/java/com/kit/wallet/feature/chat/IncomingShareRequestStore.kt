package com.kit.wallet.feature.chat

import android.content.Context
import com.kit.wallet.data.messaging.SecureMediaProcessingPlan
import com.kit.wallet.data.messaging.normalizeLocalMediaType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class IncomingShareQueueFullException : IllegalStateException(
    "Finish or discard an existing shared item before sharing another one.",
)

/**
 * Durable, single-use hand-off from the exported share activity to MainActivity.
 *
 * The Intent carries only the batch UUID. The bounded manifest and staged files stay in private,
 * credential-encrypted app storage, so killing the process between activities cannot make a share
 * disappear. `claim` deliberately does not delete anything: only the user's explicit cancel or a
 * fully durable outbox commit calls [acknowledge].
 */
internal class IncomingShareRequestPersistence(private val root: File) {
    fun publish(
        batch: SharedInboxBatch,
        nowMillis: Long = batch.receivedAtMillis,
    ): IncomingTextShareRequest {
        requireValid(batch)
        require(batch.pinnedConversationId == null) { "A new share cannot already be routed" }
        require(batch.albumDelivery == null) { "A new share cannot already be shaped" }
        val directory = directory(batch.id)
        check(!manifest(directory).exists()) { "That shared batch already exists" }
        try {
            check(directory.exists() || directory.mkdirs()) { "Kit Pay could not save this share" }
            directory.restrictSharedInboxFileToOwner()
            for (item in batch.items) requireValidStagedFile(directory, item)
            // A share still waiting to be claimed may be superseded safely. Claimed batches — in
            // particular a partially queued pinned batch — are never deleted to make room.
            pruneSupersededUnclaimed(exceptBatchId = batch.id)
            val retained = root.listFiles().orEmpty().count { candidate ->
                candidate.isDirectory &&
                    candidate.name != batch.id &&
                    manifest(candidate).isFile &&
                    readBatch(candidate, nowMillis, deleteInvalid = true) != null
            }
            if (retained >= MAX_RETAINED_REQUESTS) throw IncomingShareQueueFullException()
            writeManifest(directory, batch)
            return IncomingTextShareRequest(batch.id, IncomingTextShare.Accepted(batch))
        } catch (error: Exception) {
            // The directory belongs to this not-yet-published batch. A failed admission must not
            // leave its plaintext behind or let a later token claim it.
            directory.deleteRecursively()
            throw error
        }
    }

    fun claim(token: String, nowMillis: Long = System.currentTimeMillis()): IncomingTextShareRequest? {
        val canonical = canonicalUuid(token) ?: return null
        val directory = directory(canonical)
        val batch = readBatch(directory, nowMillis) ?: return null
        writeClaimMarker(directory)
        return IncomingTextShareRequest(canonical, IncomingTextShare.Accepted(batch))
    }

    /** Restores every retained request oldest first. New publication enforces the finite bound. */
    fun restore(nowMillis: Long = System.currentTimeMillis()): List<IncomingTextShareRequest> {
        val directories = root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .take(MAX_DIRECTORIES_TO_SCAN)
        val requests = mutableListOf<IncomingTextShareRequest>()
        for (directory in directories) {
            val batch = readBatch(directory, nowMillis) ?: continue
            writeClaimMarker(directory)
            requests += IncomingTextShareRequest(
                token = batch.id,
                payload = IncomingTextShare.Accepted(batch),
            )
        }
        return requests.sortedWith(compareBy({
            (it.payload as IncomingTextShare.Accepted).batch.receivedAtMillis
        }, IncomingTextShareRequest::token))
    }

    /**
     * Atomically commits the first validated chat choice. Repeating the same choice is safe;
     * attempting to re-route a retained/partially queued batch is refused.
     */
    fun pinDestination(
        expected: SharedInboxBatch,
        conversationId: String,
        albumDelivery: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): SharedInboxBatch {
        val canonicalConversationId = SharedInboxPolicy.canonicalConversationId(conversationId)
        require(canonicalConversationId == conversationId) { "Invalid shared destination" }
        val canonicalBatchId = canonicalUuid(expected.id)
        require(canonicalBatchId == expected.id) { "Invalid shared batch ID" }
        val directory = directory(canonicalBatchId)
        val current = checkNotNull(readBatch(directory, nowMillis)) {
            "That shared content is no longer available"
        }
        check(
            current.copy(
                pinnedConversationId = expected.pinnedConversationId,
                albumDelivery = expected.albumDelivery,
            ) == expected,
        ) {
            "The shared content changed before it could be queued"
        }
        current.pinnedConversationId?.let { pinned ->
            check(pinned == canonicalConversationId) {
                "This share is already assigned to another conversation"
            }
            // The recorded shape wins over the caller's fresh preference: a batch that already
            // queued components under one shape must never re-decide it, and a manifest written
            // before shapes existed reads back null, which the send path treats as per-item.
            return current
        }
        return current.copy(
            pinnedConversationId = canonicalConversationId,
            albumDelivery = albumDelivery,
        ).also {
            writeManifest(directory, it)
        }
    }

    /** Makes replay impossible before recursively retiring any staged plaintext. */
    fun acknowledge(token: String) {
        val canonical = canonicalUuid(token) ?: return
        val directory = directory(canonical)
        manifest(directory).delete()
        claimMarker(directory).delete()
        directory.deleteRecursively()
    }

    fun purgeExpired(nowMillis: Long = System.currentTimeMillis()) {
        if (!root.isDirectory) return
        val directories = root.listFiles().orEmpty().filter(File::isDirectory)
        directories.forEach { directory ->
            // A present but malformed/expired manifest is retired immediately. A directory with
            // no manifest may still be an active relay copy, so only age can retire that case.
            val batch = readBatch(directory, nowMillis, deleteInvalid = true)
            val expired = batch == null &&
                SharedInboxPolicy.isExpired(directory.lastModified(), nowMillis)
            if (directory.exists() && batch == null && expired) directory.deleteRecursively()
        }
        // New publication is capped. Do not enforce legacy overflow by age here: that could erase
        // a pinned partial send which is the only surviving source for its unfinished files.
    }

    private fun pruneSupersededUnclaimed(exceptBatchId: String) {
        root.listFiles().orEmpty().forEach { candidate ->
            if (
                candidate.isDirectory &&
                candidate.name != exceptBatchId &&
                manifest(candidate).isFile &&
                !claimMarker(candidate).isFile
            ) {
                candidate.deleteRecursively()
            }
        }
    }

    private fun readBatch(
        directory: File,
        nowMillis: Long,
        deleteInvalid: Boolean = true,
    ): SharedInboxBatch? {
        val id = canonicalUuid(directory.name)
        val file = manifest(directory)
        val batch = if (
            id != null && file.isFile && file.length() in 1..MAX_MANIFEST_BYTES
        ) {
            runCatching { file.inputStream().buffered().use(::decode) }.getOrNull()
        } else {
            null
        }
        val valid = batch?.takeIf {
            it.id == id &&
                !SharedInboxPolicy.isExpired(it.receivedAtMillis, nowMillis) &&
                runCatching { requireValid(it) }.isSuccess &&
                it.items.all { item ->
                    runCatching { requireValidStagedFile(directory, item) }.isSuccess
                }
        }
        if (valid == null && deleteInvalid && file.exists()) directory.deleteRecursively()
        return valid
    }

    private fun writeManifest(directory: File, batch: SharedInboxBatch) {
        val destination = manifest(directory)
        val temporary = File(directory, MANIFEST_TEMP)
        try {
            FileOutputStream(temporary).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use { output ->
                    encode(output, batch)
                    output.flush()
                    stream.fd.sync()
                }
            }
            temporary.restrictSharedInboxFileToOwner()
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            destination.restrictSharedInboxFileToOwner()
        } finally {
            temporary.delete()
        }
    }

    private fun writeClaimMarker(directory: File) {
        val marker = claimMarker(directory)
        if (marker.isFile) return
        runCatching {
            FileOutputStream(marker).use { stream ->
                stream.write(byteArrayOf(CLAIM_MARKER_VERSION))
                stream.fd.sync()
            }
            marker.restrictSharedInboxFileToOwner()
        }
    }

    private fun requireValid(batch: SharedInboxBatch) {
        require(canonicalUuid(batch.id) == batch.id) { "Invalid shared batch ID" }
        require(batch.receivedAtMillis > 0L) { "Invalid shared batch time" }
        require(batch.isDeliverable) { "A shared batch is empty" }
        require(batch.items.size <= SharedInboxPolicy.MAXIMUM_ITEMS) { "Too many shared files" }
        require(SharedInboxPolicy.batchFits(batch.items)) { "Shared files exceed the batch limit" }
        require(batch.items.map(SharedInboxItem::id).toSet().size == batch.items.size)
        require(batch.items.map(SharedInboxItem::fileName).toSet().size == batch.items.size)
        batch.items.forEach { item ->
            require(canonicalUuid(item.id) == item.id)
            require(SharedInboxPolicy.isSafeFileName(item.fileName))
            require(SharedInboxPolicy.normalizedMediaType(item.mediaType) == item.mediaType)
            require(normalizeLocalMediaType(item.localMediaType) == item.localMediaType)
            require(
                item.processingPlan != SecureMediaProcessingPlan.CHAT_IMAGE_JPEG ||
                    item.mediaType == "image/jpeg" && item.localMediaType.startsWith("image/"),
            )
            require(item.processingPlan != SecureMediaProcessingPlan.CHAT_VIDEO_MP4) {
                "Shared video items cannot carry an editor-only processing plan"
            }
            require(item.displayName.isNotBlank() && item.displayName.length <= 120)
            require(SharedInboxPolicy.fits(item.byteCount.toLong()))
        }
        batch.text?.let {
            require(SharedInboxPolicy.normalizedText(it) == it)
            require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES)
        }
        requireValidOwnerValue(batch.owner.sessionId)
        requireValidOwnerValue(batch.owner.cacheScopeId)
        batch.owner.accountId?.let(::requireValidOwnerValue)
        batch.pinnedConversationId?.let { conversationId ->
            require(SharedInboxPolicy.canonicalConversationId(conversationId) == conversationId)
        }
    }

    private fun requireValidStagedFile(directory: File, item: SharedInboxItem) {
        val candidate = File(directory, item.fileName)
        require(candidate.canonicalFile.parentFile == directory.canonicalFile)
        require(candidate.isFile && candidate.length() == item.byteCount.toLong())
    }

    private fun encode(output: DataOutputStream, batch: SharedInboxBatch) {
        output.writeByte(VERSION)
        output.writeString(batch.id)
        output.writeLong(batch.receivedAtMillis)
        output.writeString(batch.owner.sessionId)
        output.writeString(batch.owner.cacheScopeId)
        output.writeNullableString(batch.owner.accountId)
        output.writeNullableString(batch.pinnedConversationId)
        output.writeBoolean(batch.albumDelivery != null)
        batch.albumDelivery?.let(output::writeBoolean)
        output.writeInt(batch.items.size)
        batch.items.forEach { item ->
            output.writeString(item.id)
            output.writeString(item.fileName)
            output.writeString(item.mediaType)
            output.writeString(item.displayName)
            output.writeInt(item.byteCount)
            output.writeNullableString(item.originalMediaType)
            output.writeByte(item.processingPlan.persistenceCode)
            output.writeNullableLong(item.durationMillis)
        }
        output.writeNullableString(batch.text)
    }

    private fun decode(input: java.io.InputStream): SharedInboxBatch {
        DataInputStream(BufferedInputStream(input)).use { data ->
            val version = data.readUnsignedByte()
            check(version in LEGACY_VERSION_WITHOUT_SHAPE..VERSION) {
                "Unsupported share manifest"
            }
            val id = data.readString(MAX_IDENTIFIER_BYTES)
            val receivedAt = data.readLong()
            val owner = SharedInboxOwner(
                sessionId = data.readString(MAX_OWNER_VALUE_BYTES),
                cacheScopeId = data.readString(MAX_OWNER_VALUE_BYTES),
                accountId = data.readNullableString(MAX_OWNER_VALUE_BYTES),
            )
            val pinnedConversationId = data.readNullableString(MAX_IDENTIFIER_BYTES)
            // A manifest from before delivery shapes existed reads back with no shape; the send
            // path takes null as the per-item delivery those batches may already have started.
            val albumDelivery = if (version == LEGACY_VERSION_WITHOUT_SHAPE) {
                null
            } else if (data.readBoolean()) {
                data.readBoolean()
            } else {
                null
            }
            val itemCount = data.readInt()
            check(itemCount in 0..SharedInboxPolicy.MAXIMUM_ITEMS)
            val items = List(itemCount) {
                val itemId = data.readString(MAX_IDENTIFIER_BYTES)
                val fileName = data.readString(MAX_FILE_NAME_BYTES)
                val mediaType = data.readString(MAX_MEDIA_TYPE_BYTES)
                val displayName = data.readString(MAX_DISPLAY_NAME_BYTES)
                val byteCount = data.readInt()
                SharedInboxItem(
                    id = itemId,
                    fileName = fileName,
                    mediaType = mediaType,
                    displayName = displayName,
                    byteCount = byteCount,
                    originalMediaType = if (version >= VERSION_WITH_LOCAL_ORIGINAL) {
                        data.readNullableString(MAX_MEDIA_TYPE_BYTES)
                    } else {
                        null
                    },
                    processingPlan = if (version >= VERSION_WITH_LOCAL_ORIGINAL) {
                        SecureMediaProcessingPlan.fromPersistenceCode(data.readUnsignedByte())
                            ?: error("Invalid shared-media processing plan")
                    } else {
                        SecureMediaProcessingPlan.PASSTHROUGH
                    },
                    durationMillis = if (version >= VERSION_WITH_DURATION) {
                        data.readNullableLong()
                    } else {
                        null
                    },
                )
            }
            val text = data.readNullableString(MAX_TEXT_BYTES)
            check(data.read() == -1) { "Trailing share manifest bytes" }
            return SharedInboxBatch(
                id = id,
                receivedAtMillis = receivedAt,
                items = items,
                text = text,
                owner = owner,
                pinnedConversationId = pinnedConversationId,
                albumDelivery = albumDelivery,
            )
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readString(maxBytes: Int): String {
        val size = readInt()
        check(size in 0..maxBytes) { "Invalid share manifest string" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataInputStream.readNullableString(maxBytes: Int): String? =
        if (readBoolean()) readString(maxBytes) else null

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private fun requireValidOwnerValue(value: String) {
        require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= MAX_OWNER_VALUE_BYTES)
        require(value.none(Char::isISOControl))
    }

    private fun directory(batchId: String) = File(root, batchId)
    private fun manifest(directory: File) = File(directory, MANIFEST)
    private fun claimMarker(directory: File) = File(directory, CLAIM_MARKER)

    private fun canonicalUuid(raw: String): String? =
        runCatching { UUID.fromString(raw).toString() }.getOrNull()

    private companion object {
        const val VERSION = 6
        const val VERSION_WITH_LOCAL_ORIGINAL = 5
        const val VERSION_WITH_DURATION = 6

        /**
         * Manifests written before [SharedInboxBatch.albumDelivery] existed. Still readable —
         * deleting a claimed batch mid-flight would orphan a partially queued send — but every
         * write is the current version.
         */
        const val LEGACY_VERSION_WITHOUT_SHAPE = 3
        const val CLAIM_MARKER_VERSION: Byte = 1
        const val MANIFEST = ".request-v1"
        const val MANIFEST_TEMP = ".request-v1.tmp"
        const val CLAIM_MARKER = ".claimed-v1"
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val MAX_TEXT_BYTES = 16 * 1024
        const val MAX_IDENTIFIER_BYTES = 64
        const val MAX_OWNER_VALUE_BYTES = 512
        const val MAX_FILE_NAME_BYTES = 255
        const val MAX_MEDIA_TYPE_BYTES = 160
        const val MAX_DISPLAY_NAME_BYTES = 512
        const val MAX_DIRECTORIES_TO_SCAN = 32
        const val MAX_RETAINED_REQUESTS = 4
    }
}

/** Process façade; rejected requests need no durable storage because they contain no user bytes. */
internal object IncomingTextShareStore {
    private var pendingRejected: IncomingTextShareRequest? = null

    @Synchronized
    fun publish(context: Context, payload: IncomingTextShare): String = when (payload) {
        is IncomingTextShare.Accepted ->
            persistence(context).publish(payload.batch).token
        is IncomingTextShare.Rejected -> {
            val token = UUID.randomUUID().toString()
            pendingRejected = IncomingTextShareRequest(token, payload)
            token
        }
    }

    @Synchronized
    fun claim(context: Context, token: String): IncomingTextShareRequest? {
        pendingRejected?.takeIf { it.token == token }?.let {
            pendingRejected = null
            return it
        }
        return persistence(context).claim(token)
    }

    @Synchronized
    fun restore(context: Context): List<IncomingTextShareRequest> =
        persistence(context).restore()

    @Synchronized
    fun acknowledge(context: Context, token: String) {
        if (pendingRejected?.token == token) pendingRejected = null
        persistence(context).acknowledge(token)
    }

    @Synchronized
    fun pinDestination(
        context: Context,
        batch: SharedInboxBatch,
        conversationId: String,
        albumDelivery: Boolean,
    ): SharedInboxBatch = persistence(context).pinDestination(batch, conversationId, albumDelivery)

    private fun persistence(context: Context) = IncomingShareRequestPersistence(
        SharedInboxStore.root(context),
    )
}
