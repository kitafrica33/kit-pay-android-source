package com.kit.wallet.feature.chat

import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitUserAuthoredTextPolicy
import com.kit.wallet.data.messaging.MAX_IMAGE_PLAINTEXT_BYTES
import com.kit.wallet.data.messaging.MAX_LOCAL_MEDIA_DURATION_MILLIS
import com.kit.wallet.data.messaging.SecureMediaProcessingPlan
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.messaging.normalizeLocalMediaType
import com.kit.wallet.data.session.SessionFence
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/** One file the user shared into Kit Pay. */
internal data class SharedInboxItem(
    val id: String,
    /** Name of the file inside the batch directory. Never a path. */
    val fileName: String,
    /** Wire MIME type, already normalized by [SharedInboxPolicy.normalizedMediaType]. */
    val mediaType: String,
    /** What the review screen should call it — the original filename for documents. */
    val displayName: String,
    val byteCount: Int,
    /** MIME of the retained source when the wire representation is produced later. */
    val originalMediaType: String? = null,
    /** Restart-safe transform applied only after the original is durably admitted. */
    val processingPlan: SecureMediaProcessingPlan = SecureMediaProcessingPlan.PASSTHROUGH,
    /** Playback duration captured from the app-owned copy when this is audio or video. */
    val durationMillis: Long? = null,
) {
    val localMediaType: String get() = originalMediaType ?: mediaType

    init {
        require(
            durationMillis == null ||
                durationMillis in 1..MAX_LOCAL_MEDIA_DURATION_MILLIS &&
                (localMediaType.startsWith("audio/") || localMediaType.startsWith("video/")),
        ) { "Invalid shared-media duration" }
    }
}

/** Everything one trip through the share sheet produced. */
internal data class SharedInboxBatch(
    val id: String,
    val receivedAtMillis: Long,
    val items: List<SharedInboxItem>,
    /**
     * A shared link or selection of text. A browser hands over text rather than a file, and a link
     * saved as a `.txt` attachment would be useless to the person receiving it — so text travels as
     * the message, where the user can read it before sending.
     */
    val text: String? = null,
    /** Exact authenticated epoch present when the share was accepted. */
    val owner: SharedInboxOwner,
    /**
     * Immutable route selected before the first outbox write.
     *
     * A multi-file send can be interrupted after only some components reach the durable outbox.
     * Persisting this choice prevents a restored remainder from being sent to another chat.
     */
    val pinnedConversationId: String? = null,
    /**
     * Immutable delivery shape, pinned together with the destination: true for one `KITMEDIA2`
     * album carrying every item and the text as its caption, false for the per-item sends, null
     * while unpinned (and for batches persisted before the field existed, which were per-item).
     *
     * This must be durable for the same reason the destination is. The album capability flips
     * with session state, so a batch restored after process death could otherwise re-decide the
     * shape and queue the same content again under different component message ids — a duplicate
     * the server's idempotency could never catch.
     */
    val albumDelivery: Boolean? = null,
) {
    /** True when there is something for a chat to receive. */
    val isDeliverable: Boolean get() = items.isNotEmpty() || !text.isNullOrEmpty()
}

/** Non-secret session fence that prevents a staged share crossing an account switch. */
internal data class SharedInboxOwner(
    val sessionId: String,
    val cacheScopeId: String,
    val accountId: String?,
) {
    fun matches(fence: SessionFence?): Boolean = fence != null &&
        sessionId == fence.sessionId &&
        cacheScopeId == fence.cacheScopeId &&
        accountId == fence.accountId

    companion object {
        fun from(fence: SessionFence): SharedInboxOwner =
            SharedInboxOwner(fence.sessionId, fence.cacheScopeId, fence.accountId)
    }
}

/**
 * Reading a staged share back, and retiring it.
 *
 * The one place the send path touches the disk, kept behind an interface so the review screen's
 * decisions — what is deliverable, what order it goes in, when staged plaintext is destroyed — stay
 * testable without a `Context`.
 */
internal interface SharedInboxAccess {
    /** How to open the staged file, so a 200 MB share is never held in heap to be sent. */
    fun source(batch: SharedInboxBatch, item: SharedInboxItem): SecureMediaSource

    /**
     * Atomically fixes this batch to one validated conversation and one delivery shape before
     * any outbox write. A batch already pinned keeps its recorded shape; [albumDelivery] is only
     * the preference for a first pin.
     */
    fun pinDestination(
        batch: SharedInboxBatch,
        conversationId: String,
        albumDelivery: Boolean,
    ): SharedInboxBatch

    /** Destroys everything staged for [batch]. Safe to call for a batch that staged nothing. */
    fun discard(batch: SharedInboxBatch)
}

/**
 * The rules the share sheet hand-off agrees on, kept pure so what a share will do can be tested
 * without an Intent, a resolver or a disk.
 *
 * Rule for rule the same as iOS `SharedInboxPolicy`, because a file shared into Kit Pay has to
 * arrive as the same message whichever phone sent it.
 */
internal object SharedInboxPolicy {
    /**
     * The same hard cap any other attachment has, taken from the send path itself rather than
     * restated: a share this screen accepts has to be a share the wire will carry. Refusing at the
     * share sheet is far kinder than copying a file in and failing on Send. `SharedInboxTest` fails
     * if the two ever drift apart.
     */
    const val MAXIMUM_BYTES = MAX_IMAGE_PLAINTEXT_BYTES

    /**
     * The entire share is bounded as tightly as one supported attachment. Without an aggregate
     * cap, eight individually valid 200 MiB files could consume well over a gigabyte of private
     * storage before the recipient picker appeared.
     */
    const val MAXIMUM_BATCH_BYTES = MAXIMUM_BYTES

    /** How many files one trip through the share sheet may carry into the review screen. */
    const val MAXIMUM_ITEMS = 8

    /** An undelivered share is a file the user has forgotten about. It goes. */
    const val RETENTION_MILLIS = 24L * 60 * 60 * 1_000

    const val FALLBACK_MEDIA_TYPE = "application/octet-stream"

    /**
     * The longest shared text Kit Pay will carry into the review screen. Long enough for a link and
     * a sentence about it, short enough that a whole document pasted as "text" is not silently
     * turned into a message.
     */
    const val MAXIMUM_TEXT_CHARACTERS = 4_000

    /**
     * The media types the encrypted wire accepts — the wire's own list, not a copy of it, so an
     * allowlist change can never leave the share sheet accepting something a chat would refuse.
     */
    val ALLOWED_MEDIA_TYPES: Set<String> get() = KitMediaMessage.SUPPORTED_MEDIA_TYPES

    /**
     * What a shared file will travel as.
     *
     * Images and videos are the exceptions to the allowlist. A camera-native HEIC is assigned the
     * JPEG wire type; every provider-declared video is assigned canonical MP4. The untouched
     * originals remain local while the durable background worker derives truthful wire bytes.
     * Anything else the wire does not accept is sent as a document, which is lossless and works
     * through the profile's generic document type.
     */
    fun normalizedMediaType(raw: String?): String {
        val normalized = raw.orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
        if (normalized.isEmpty()) return FALLBACK_MEDIA_TYPE
        // Every still image follows the normal composer path: decode, orient, resize and re-encode
        // to JPEG. Besides bounding the image, that strips EXIF/location metadata consistently.
        if (normalized.startsWith("image/")) return "image/jpeg"
        // Provider MIME is only a routing hint. The background worker inspects compressed tracks
        // and writes a real MP4, so QuickTime bytes can never merely be relabelled as MP4.
        if (normalized.startsWith("video/")) return "video/mp4"
        if (normalized in ALLOWED_MEDIA_TYPES) return normalized
        return FALLBACK_MEDIA_TYPE
    }

    /** True when the durable sender pipeline must derive a JPEG wire copy from the local original. */
    fun requiresImageTranscode(raw: String?): Boolean {
        val normalized = raw.orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
        return normalized.startsWith("image/")
    }

    /** True when a provider-routed video must be content-validated and remuxed before upload. */
    fun requiresVideoCanonicalization(raw: String?): Boolean {
        val normalized = raw.orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
        return normalized.startsWith("video/")
    }

    fun fits(byteCount: Long): Boolean = byteCount > 0 && byteCount <= MAXIMUM_BYTES

    fun batchFits(items: List<SharedInboxItem>): Boolean {
        var total = 0L
        for (item in items) {
            val next = total + item.byteCount.toLong()
            if (next < total || next > MAXIMUM_BATCH_BYTES.toLong()) return false
            total = next
        }
        return true
    }

    fun isExpired(receivedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - receivedAtMillis >= RETENTION_MILLIS ||
            // A batch stamped in the future is a clock change, not a fresh share.
            receivedAtMillis - nowMillis > RETENTION_MILLIS

    /**
     * A staged name is read back as untrusted input: a name that could climb out of the batch
     * directory is refused rather than sanitized into something else.
     */
    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= 255 &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains('\u0000')

    /**
     * The filename a shared item is stored under: our own id plus the original extension, so a
     * hostile name from another app never becomes a path in our cache.
     */
    fun storageFileName(id: String, suggestedName: String?): String {
        val extension = suggestedName.orEmpty()
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        val safe = extension.length in 1..12 && extension.all(Char::isLetterOrDigit)
        return if (safe) "$id.$extension" else id
    }

    /** What the review screen calls the file. */
    fun displayName(suggestedName: String?, mediaType: String): String {
        val trimmed = suggestedName.orEmpty()
            .map { if (it == '/' || it == '\\' || it == ':' || it == '\u0000') '-' else it }
            .joinToString("")
            .trim()
        if (trimmed.isNotEmpty()) return trimmed.take(120)
        return when {
            mediaType.startsWith("image/") -> "Photo"
            mediaType.startsWith("video/") -> "Video"
            mediaType.startsWith("audio/") -> "Audio"
            else -> "Document"
        }
    }

    fun normalizedText(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAXIMUM_TEXT_CHARACTERS)
    }

    /** Mirrors the repository's user-authored text gate, before any file can be queued. */
    fun allowsUserAuthoredText(text: String): Boolean {
        return KitUserAuthoredTextPolicy.allows(text)
    }

    fun canonicalConversationId(raw: String?): String? {
        val normalized = raw?.trim() ?: return null
        return runCatching { UUID.fromString(normalized).toString() }.getOrNull()
    }

    /** The one line the destination picker shows above the chat list. */
    fun summary(itemCount: Int, hasText: Boolean): String = when {
        itemCount == 0 && hasText -> "Text ready to send"
        itemCount == 0 -> "Nothing to send"
        itemCount == 1 && !hasText -> "1 item ready to send"
        itemCount == 1 -> "1 item and text ready to send"
        !hasText -> "$itemCount items ready to send"
        else -> "$itemCount items and text ready to send"
    }

    /**
     * Stable, recipient-scoped identity for one item in a retained share batch.
     *
     * The same batch retried in the same conversation resolves to the same UUID; choosing a
     * different conversation cannot collide with a send already queued for the first one.
     */
    fun deliveryMessageId(batchId: String, conversationId: String, componentId: String): String {
        require(runCatching { UUID.fromString(batchId).toString() }.getOrNull() == batchId)
        require(canonicalConversationId(conversationId) == conversationId)
        require(componentId == TEXT_COMPONENT ||
            componentId == ALBUM_COMPONENT ||
            runCatching { UUID.fromString(componentId).toString() }.getOrNull() == componentId
        )
        val seed = "kit-share-v1\u0000$batchId\u0000$conversationId\u0000$componentId"
        return UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    const val TEXT_COMPONENT = "text"

    /**
     * Component id for the single message an album-shaped batch becomes. Item ids are random
     * UUIDs and [TEXT_COMPONENT] is taken, so this can never collide with a per-item identity —
     * which matters if the same batch and conversation ever hold both shapes' queue records.
     */
    const val ALBUM_COMPONENT = "album"

    fun newId(): String = UUID.randomUUID().toString()
}
