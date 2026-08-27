package com.kit.wallet.feature.chat

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.session.SessionFence
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A validated, short-lived share request. Nothing shared is kept beyond the request that carries it. */
internal sealed interface IncomingTextShare {
    /**
     * Everything one trip through the share sheet produced: any text, plus the files that were
     * copied into Kit Pay's own cache so the share sheet's read grant can expire without taking the
     * share with it.
     */
    data class Accepted(val batch: SharedInboxBatch) : IncomingTextShare {
        /** The message body a text-only share becomes. */
        val text: String get() = batch.text.orEmpty()
    }

    data class Rejected(val reason: String) : IncomingTextShare
}

internal data class IncomingTextShareRequest(
    val token: String,
    val payload: IncomingTextShare,
)

/**
 * Reads a share from the system share sheet.
 *
 * Kit Pay now stands in that sheet for everything a chat can carry, so this accepts text, the three
 * media families and the document types the wire takes, and refuses everything else rather than
 * truncating it — the review screen always represents exactly what the user chose to share.
 *
 * Files are copied into this app's private cache here, while the caller still holds the sheet's read
 * grant. That grant dies with the relay activity; a copy does not, and the destination picker may
 * take a while.
 */
internal suspend fun Intent.parseIncomingShare(
    context: Context,
    owner: SessionFence,
    batchId: String = SharedInboxPolicy.newId(),
): IncomingTextShare {
    currentCoroutineContext().ensureActive()
    val multiple = action == Intent.ACTION_SEND_MULTIPLE
    if (action != Intent.ACTION_SEND && !multiple) {
        return IncomingTextShare.Rejected(UNSUPPORTED_SHARE_MESSAGE)
    }

    val text = when (val raw = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()) {
        null -> null
        else -> when (val problem = textProblem(raw)) {
            null -> SharedInboxPolicy.normalizedText(raw)
            else -> return IncomingTextShare.Rejected(problem)
        }
    }

    val uris = streamUris(multiple)
    if (uris.isEmpty()) {
        if (text.isNullOrEmpty()) return IncomingTextShare.Rejected("There is nothing to share.")
        return IncomingTextShare.Accepted(
            SharedInboxBatch(
                id = batchId,
                receivedAtMillis = System.currentTimeMillis(),
                items = emptyList(),
                text = text,
                owner = SharedInboxOwner.from(owner),
            ),
        )
    }
    if (uris.size > SharedInboxPolicy.MAXIMUM_ITEMS) {
        return IncomingTextShare.Rejected(
            "Share up to ${SharedInboxPolicy.MAXIMUM_ITEMS} files at a time.",
        )
    }

    val items = mutableListOf<SharedInboxItem>()
    var remainingBatchBytes = SharedInboxPolicy.MAXIMUM_BATCH_BYTES.toLong()
    for (uri in uris) {
        currentCoroutineContext().ensureActive()
        when (
            val staged = SharedInboxStore.stage(
                context = context,
                batchId = batchId,
                uri = uri,
                intentMediaType = type,
                maximumBytes = remainingBatchBytes,
            )
        ) {
            is SharedInboxStaging.Staged -> {
                items += staged.item
                remainingBatchBytes -= staged.item.byteCount.toLong()
            }
            is SharedInboxStaging.Failed -> {
                // A review must represent exactly what the user picked. Sending the readable half
                // of a multi-file selection would look successful while silently losing files.
                SharedInboxStore.remove(context, batchId)
                return IncomingTextShare.Rejected(staged.reason)
            }
        }
    }
    if (items.isEmpty()) {
        SharedInboxStore.remove(context, batchId)
        return IncomingTextShare.Rejected(UNREADABLE_SHARE_MESSAGE)
    }
    if (!SharedInboxPolicy.batchFits(items)) {
        SharedInboxStore.remove(context, batchId)
        return IncomingTextShare.Rejected(TOO_LARGE_MESSAGE)
    }
    return IncomingTextShare.Accepted(
        SharedInboxBatch(
            id = batchId,
            receivedAtMillis = System.currentTimeMillis(),
            items = items,
            text = text,
            owner = SharedInboxOwner.from(owner),
        ),
    )
}

private fun Intent.streamUris(multiple: Boolean): List<Uri> {
    val extras = if (multiple) {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    } else {
        @Suppress("DEPRECATION")
        listOfNotNull(getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }
    if (extras.isNotEmpty()) return extras.filterNotNull()
    // Some senders only populate the clip, never the extra.
    val clip = clipData ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
}

private fun textProblem(text: String): String? {
    // Check UTF-16 length first so an explicitly targeted, abnormally large Intent cannot cause a
    // second large allocation while calculating its UTF-8 size.
    if (text.length > MAX_SHARED_TEXT_UTF16_UNITS) return SHARED_TEXT_TOO_LONG_MESSAGE
    val codePoints = text.codePointCount(0, text.length)
    if (
        codePoints > SharedInboxPolicy.MAXIMUM_TEXT_CHARACTERS ||
        text.toByteArray(Charsets.UTF_8).size > MAX_SHARED_TEXT_BYTES
    ) {
        return SHARED_TEXT_TOO_LONG_MESSAGE
    }
    return null
}

/** The outcome of copying one shared file in. */
internal sealed interface SharedInboxStaging {
    data class Staged(val item: SharedInboxItem) : SharedInboxStaging

    data class Failed(val reason: String) : SharedInboxStaging
}

/**
 * The no-backup directory a share is copied into on its way to a chat.
 *
 * Plaintext the user has just chosen to send lives here, in app-private storage, and is deleted the
 * moment it has been sent or the request is dismissed. A batch nobody ever delivered is retired on
 * the next launch by [purgeExpired].
 */
internal object SharedInboxStore {
    private const val DIRECTORY = "shared-inbox"

    internal fun root(context: Context) = File(context.noBackupFilesDir, DIRECTORY)

    suspend fun stage(
        context: Context,
        batchId: String,
        uri: Uri,
        intentMediaType: String?,
        maximumBytes: Long = SharedInboxPolicy.MAXIMUM_BATCH_BYTES.toLong(),
    ): SharedInboxStaging {
        require(maximumBytes in 0..SharedInboxPolicy.MAXIMUM_BYTES.toLong())
        val resolver = context.contentResolver
        val reportedMediaType = resolver.getType(uri) ?: intentMediaType
        val mediaType = SharedInboxPolicy.normalizedMediaType(reportedMediaType)
        val requiresImageTranscode = SharedInboxPolicy.requiresImageTranscode(reportedMediaType)
        val suggestedName = resolver.suggestedName(uri)
        val id = SharedInboxPolicy.newId()
        val fileName = SharedInboxPolicy.storageFileName(id, suggestedName)
        val directory = File(root(context), batchId)
        if (!directory.exists() && !directory.mkdirs()) {
            return SharedInboxStaging.Failed("Kit Pay could not open its shared storage. Try sharing again.")
        }
        directory.restrictSharedInboxFileToOwner()
        val destination = File(directory, fileName)
        val copied = runCatching {
            currentCoroutineContext().ensureActive()
            if (requiresImageTranscode) {
                val jpeg = transcodeChatImage(resolver, uri) ?: return@runCatching null
                try {
                    currentCoroutineContext().ensureActive()
                    if (jpeg.size.toLong() > maximumBytes) return@runCatching -1L
                    destination.outputStream().use { output -> output.write(jpeg) }
                    destination.restrictSharedInboxFileToOwner()
                    jpeg.size.toLong()
                } finally {
                    jpeg.fill(0)
                }
            } else {
                resolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output ->
                        input.copyBounded(output, maximumBytes)
                    }
                }
            }
        }.getOrElse { error ->
            destination.delete()
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        if (copied == null) {
            destination.delete()
            return SharedInboxStaging.Failed(UNREADABLE_SHARE_MESSAGE)
        }
        if (copied < 0) {
            destination.delete()
            return SharedInboxStaging.Failed(TOO_LARGE_MESSAGE)
        }
        if (!SharedInboxPolicy.fits(copied)) {
            destination.delete()
            return SharedInboxStaging.Failed(UNREADABLE_SHARE_MESSAGE)
        }
        destination.restrictSharedInboxFileToOwner()
        return SharedInboxStaging.Staged(
            SharedInboxItem(
                id = id,
                fileName = fileName,
                mediaType = mediaType,
                displayName = SharedInboxPolicy.displayName(suggestedName, mediaType),
                byteCount = copied.toInt(),
            ),
        )
    }

    /**
     * Locates one staged file for sending. The name is checked again: it came off a disk, so it is
     * input. What is returned is a way to open the file, not its contents — the send path streams
     * it through the cipher, so sharing a large video costs no heap at all.
     */
    fun source(context: Context, batchId: String, item: SharedInboxItem): SecureMediaSource {
        require(SharedInboxPolicy.isSafeFileName(item.fileName)) { "That shared file could no longer be read." }
        val file = File(File(root(context), batchId), item.fileName)
        require(
            file.isFile &&
                file.length() == item.byteCount.toLong() &&
                SharedInboxPolicy.fits(file.length()),
        ) {
            "That shared file could no longer be read."
        }
        return SecureMediaSource.ofFile(file)
    }

    fun remove(context: Context, batchId: String) {
        runCatching { File(root(context), batchId).deleteRecursively() }
    }

    /** Retires shares nobody ever delivered, and anything left behind by a previous process. */
    fun purgeExpired(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        IncomingShareRequestPersistence(root(context)).purgeExpired(nowMillis)
        // Builds before the durable hand-off used cacheDir. An upgrade must not strand plaintext
        // there forever now that the authoritative store is in the no-backup directory.
        runCatching { File(context.cacheDir, DIRECTORY).deleteRecursively() }
    }

    private fun ContentResolver.suggestedName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        val queried = runCatching {
            query(uri, projection, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    /**
     * Copies at most [limit] bytes, returning -1 as soon as the source proves to be larger. A share
     * that does not fit is refused rather than truncated: half a video is not the file the user
     * chose to send.
     */
    private suspend fun java.io.InputStream.copyBounded(
        output: java.io.OutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(64 * 1_024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) return -1
            output.write(buffer, 0, read)
        }
    }
}

internal fun File.restrictSharedInboxFileToOwner() {
    // Internal app storage is already sandboxed; make the intended mode explicit as defense in
    // depth and keep staged plaintext unavailable to backup/restore via noBackupFilesDir.
    setReadable(false, false)
    setWritable(false, false)
    if (isDirectory) setExecutable(false, false)
    setReadable(true, true)
    setWritable(true, true)
    if (isDirectory) setExecutable(true, true)
}

/** [SharedInboxAccess] over private credential-encrypted, no-backup storage. */
internal class CacheSharedInboxAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : SharedInboxAccess {
    override fun source(batch: SharedInboxBatch, item: SharedInboxItem): SecureMediaSource =
        SharedInboxStore.source(context, batch.id, item)

    override fun pinDestination(
        batch: SharedInboxBatch,
        conversationId: String,
    ): SharedInboxBatch = IncomingTextShareStore.pinDestination(context, batch, conversationId)

    override fun discard(batch: SharedInboxBatch) {
        if (batch.items.isEmpty()) return
        SharedInboxStore.remove(context, batch.id)
    }
}

internal const val ACTION_OPEN_TEXT_SHARE = "com.kit.wallet.action.OPEN_TEXT_SHARE"
internal const val EXTRA_TEXT_SHARE_TOKEN = "com.kit.wallet.extra.TEXT_SHARE_TOKEN"

private const val MAX_SHARED_TEXT_BYTES = 16_000
private const val MAX_SHARED_TEXT_UTF16_UNITS = SharedInboxPolicy.MAXIMUM_TEXT_CHARACTERS * 2
private const val SHARED_TEXT_TOO_LONG_MESSAGE =
    "This text is too long. Share up to 4,000 characters at a time."
private const val UNSUPPORTED_SHARE_MESSAGE =
    "Kit Pay can share text, photos, videos, audio and documents."
private const val UNREADABLE_SHARE_MESSAGE = "That shared file could no longer be read."
// Stated from the enforced cap, so the number the user is told is the number that was applied.
private val TOO_LARGE_MESSAGE =
    "Share up to ${SharedInboxPolicy.MAXIMUM_BATCH_BYTES / (1_024 * 1_024)} MB total at a time."
