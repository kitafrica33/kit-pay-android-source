package com.kit.wallet.feature.chat.camera

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.kit.wallet.data.media.MediaVideoRemuxPlan
import com.kit.wallet.data.media.MediaVideoRemuxer
import com.kit.wallet.data.media.decodeVideoFrame
import java.io.ByteArrayOutputStream
import java.io.File

internal const val MIN_CLIP_MILLIS = 500L

/**
 * The largest library video the trim editor will copy into cache. A disk guard, not a wire
 * cap: the wire cap still applies to the trimmed clip, and stream-copy trimming means a short
 * window cut from a long, heavy video is exactly what this editor is for.
 */
internal const val MAX_LIBRARY_VIDEO_SOURCE_BYTES = 1_073_741_824L

internal data class VideoTrimPlan(
    val startMicros: Long,
    val endMicros: Long,
    val keepAudio: Boolean,
) {
    val durationMicros: Long get() = (endMicros - startMicros).coerceAtLeast(0L)
}

/**
 * Clamps a requested trim window to the real track duration and enforces the minimum
 * clip length. Returns null when the request cannot produce a playable clip.
 */
internal fun planVideoTrim(
    requestedStartMillis: Long,
    requestedEndMillis: Long,
    trackDurationMillis: Long,
    keepAudio: Boolean,
): VideoTrimPlan? {
    if (trackDurationMillis <= 0L) return null
    val startMillis = requestedStartMillis.coerceIn(0L, trackDurationMillis)
    val endMillis = requestedEndMillis.coerceIn(0L, trackDurationMillis)
    if (endMillis - startMillis < MIN_CLIP_MILLIS) return null
    return VideoTrimPlan(
        startMicros = startMillis * 1_000,
        endMicros = endMillis * 1_000,
        keepAudio = keepAudio,
    )
}

/**
 * The wire type a video leaves with. Every outbound video takes the canonical remux plan, so this
 * label describes MediaMuxer's real MP4 output rather than a provider's MIME or a filename.
 */
internal fun videoSendMediaType(): String = "video/mp4"

/**
 * Stream-copies compressed samples from a recorded clip into a trimmed MP4 without
 * decoding, so trimming and muting stay fast and generation-loss free. The window opens
 * at the first sync frame AT OR AFTER the requested start, so frames the sender trimmed
 * away are never sent — the clip may start up to one GOP later than requested, never
 * earlier. Only when the window holds no sync frame at all (a sub-GOP window at the very
 * end of the clip) does it fall back to the previous sync frame.
 */
internal object ChatVideoTranscoder {

    fun trim(source: File, destination: File, plan: VideoTrimPlan): Boolean =
        MediaVideoRemuxer.remux(
            source,
            destination,
            MediaVideoRemuxPlan(plan.startMicros, plan.endMicros, plan.keepAudio),
        )

    /** Whether the clip carries an audio track at all (microphone permission may be denied). */
    fun hasAudioTrack(source: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            trackIndex(extractor, "audio/") != null
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun durationMillis(source: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Extracts a poster frame at [atMillis] as a JPEG, or null. */
    fun posterFrame(source: File, atMillis: Long, maxDimension: Int): ByteArray? {
        val frame = decodeVideoFrame(
            source,
            timeMicros = atMillis.coerceIn(0L, Long.MAX_VALUE / 1_000) * 1_000,
            maxDimension = maxDimension,
        ) ?: return null
        return try {
            val output = ByteArrayOutputStream()
            if (!frame.compress(Bitmap.CompressFormat.JPEG, 85, output)) return null
            output.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            frame.recycle()
        }
    }

    private fun trackIndex(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return index
        }
        return null
    }

}
