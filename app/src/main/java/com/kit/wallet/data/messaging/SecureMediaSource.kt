package com.kit.wallet.data.messaging

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Durable, device-only work applied to an original before its E2EE upload representation. */
enum class SecureMediaProcessingPlan(internal val persistenceCode: Int) {
    /** Encrypt the retained original byte-for-byte. */
    PASSTHROUGH(0),

    /** Decode/orient/downscale and encode a metadata-free JPEG in background work. */
    CHAT_IMAGE_JPEG(1),

    /** Content-validate and remux the complete video or selected edit window to real MP4. */
    CHAT_VIDEO_MP4(2),

    ;

    companion object {
        internal fun fromPersistenceCode(code: Int): SecureMediaProcessingPlan? = when (code) {
            0 -> PASSTHROUGH
            1 -> CHAT_IMAGE_JPEG
            2 -> CHAT_VIDEO_MP4
            else -> null
        }
    }
}

/** Restart-safe edits applied only to a video's disposable upload representation. */
data class SecureMediaVideoEditPlan(
    val startMicros: Long,
    val endMicros: Long,
    val keepAudio: Boolean,
) {
    val durationMicros: Long get() = endMicros - startMicros

    init {
        require(startMicros >= 0L) { "A video edit cannot start before the source" }
        require(endMicros > startMicros) { "A video edit needs a non-empty window" }
        require(durationMicros >= MIN_VIDEO_EDIT_MICROS) { "A video edit is too short" }
        require(endMicros <= MAX_VIDEO_EDIT_END_MICROS) { "A video edit is out of range" }
    }

    private companion object {
        const val MIN_VIDEO_EDIT_MICROS = 500_000L
        const val MAX_VIDEO_EDIT_END_MICROS = 24L * 60L * 60L * 1_000_000L
    }
}

/** A bounded device-local original may be larger than the optimized cross-device wire object. */
internal const val MAX_LOCAL_MEDIA_ORIGINAL_BYTES = 1_073_741_824
internal const val MAX_LOCAL_MEDIA_DURATION_MILLIS = 24L * 60L * 60L * 1_000L

/** MIME types valid for a local original even when the cross-platform wire needs a JPEG. */
internal fun normalizeLocalMediaType(value: String): String? {
    val normalized = value.substringBefore(';').trim().lowercase(Locale.US)
    return normalized.takeIf {
        it in KitMediaMessage.SUPPORTED_MEDIA_TYPES ||
            it in LOCAL_ONLY_IMAGE_MEDIA_TYPES ||
            SAFE_LOCAL_VIDEO_MEDIA_TYPE.matches(it)
    }
}

/** Unknown video subtypes stay truthful locally while their bytes face canonical wire checks. */
private val SAFE_LOCAL_VIDEO_MEDIA_TYPE = Regex("^video/[a-z0-9][a-z0-9.+_-]{0,126}$")

private val LOCAL_ONLY_IMAGE_MEDIA_TYPES = setOf(
    "image/heic",
    "image/heif",
    "image/avif",
    "image/bmp",
    "image/x-ms-bmp",
    "image/vnd.wap.wbmp",
)

/**
 * Where an attachment's plaintext comes from, opened only at the moment it is encrypted.
 *
 * A 200 MB video already exists somewhere the user put it — in the gallery, in another app's
 * share, in a capture file. Handing the send path a way to *open* it, rather than a `ByteArray`
 * of it, lets the local-first pipeline stream it into the device media store and then stream that
 * durable copy through the cipher. A heap-sized attachment therefore never becomes a heap-sized
 * problem, while the sender keeps an immediately reusable local copy.
 *
 * A source may be opened more than once, so an implementation must return a fresh stream each
 * time rather than handing back one it has already drained.
 */
class SecureMediaSource(
    /**
     * What the source claims it is about to produce, used only to draw the placeholder bubble
     * while encryption runs. The cipher counts the real bytes and the descriptor records those,
     * so an optimistic or stale claim here can never reach the wire.
    */
    val declaredByteCount: Long,
    /** Monotonic capture/selection boundary used only for PII-free local performance timing. */
    internal val originatedAtNanos: Long = System.nanoTime(),
    /** MIME of the retained original; null means it is byte-identical to the wire media. */
    internal val originalMediaType: String? = null,
    /** Known local playback duration for audio/video; null when the source cannot provide it. */
    internal val durationMillis: Long? = null,
    /** Restart-safe processing the background worker applies before encryption. */
    internal val processingPlan: SecureMediaProcessingPlan = SecureMediaProcessingPlan.PASSTHROUGH,
    /** Optional trim/mute parameters; null means canonicalize the complete retained video. */
    internal val videoEditPlan: SecureMediaVideoEditPlan? = null,
    /**
     * Optional seekable original already owned by the caller. The sender UI may play this file
     * while the durable Sent Media copy is still being published; it is never used as remote state.
     */
    internal val localPlaybackFile: File? = null,
    private val opener: () -> InputStream,
) {
    init {
        require(
            videoEditPlan == null || processingPlan == SecureMediaProcessingPlan.CHAT_VIDEO_MP4,
        ) { "Video edits require the canonical MP4 processing plan" }
        require(durationMillis == null || durationMillis in 1..MAX_LOCAL_MEDIA_DURATION_MILLIS) {
            "A media duration is out of range"
        }
    }

    fun open(): InputStream = opener()

    companion object {
        /** For plaintext that is genuinely already in heap, such as a re-encoded photo. */
        fun ofBytes(
            bytes: ByteArray,
            originatedAtNanos: Long = System.nanoTime(),
            originalMediaType: String? = null,
            durationMillis: Long? = null,
            processingPlan: SecureMediaProcessingPlan = SecureMediaProcessingPlan.PASSTHROUGH,
            videoEditPlan: SecureMediaVideoEditPlan? = null,
        ): SecureMediaSource = SecureMediaSource(
            declaredByteCount = bytes.size.toLong(),
            originatedAtNanos = originatedAtNanos,
            originalMediaType = originalMediaType,
            durationMillis = durationMillis,
            processingPlan = processingPlan,
            videoEditPlan = videoEditPlan,
        ) { ByteArrayInputStream(bytes) }

        /** For a capture or recording this app wrote itself. */
        fun ofFile(
            file: File,
            originatedAtNanos: Long = System.nanoTime(),
            originalMediaType: String? = null,
            durationMillis: Long? = null,
            processingPlan: SecureMediaProcessingPlan = SecureMediaProcessingPlan.PASSTHROUGH,
            videoEditPlan: SecureMediaVideoEditPlan? = null,
        ): SecureMediaSource = SecureMediaSource(
            declaredByteCount = file.length(),
            originatedAtNanos = originatedAtNanos,
            originalMediaType = originalMediaType,
            durationMillis = durationMillis,
            processingPlan = processingPlan,
            videoEditPlan = videoEditPlan,
            localPlaybackFile = file,
        ) { file.inputStream() }
    }
}

/** One attachment of a media album: where its plaintext comes from and what it is. */
data class SecureMediaAlbumSource(
    val source: SecureMediaSource,
    val mediaType: String,
)
