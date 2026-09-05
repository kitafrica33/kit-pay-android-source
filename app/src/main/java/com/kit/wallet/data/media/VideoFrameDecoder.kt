package com.kit.wallet.data.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

internal data class VideoFrameSize(val width: Int, val height: Int)

/** Bound the native decode itself; scaling a full 4K/8K bitmap afterwards is already too late. */
internal fun videoFrameDecodeSize(width: Int, height: Int, maxDimension: Int): VideoFrameSize? {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return null
    val longest = maxOf(width, height)
    val bound = minOf(maxDimension, MAX_VIDEO_POSTER_DIMENSION)
    if (longest <= bound) return VideoFrameSize(width, height)
    return VideoFrameSize(
        maxOf(1, (width.toLong() * bound / longest).toInt()),
        maxOf(1, (height.toLong() * bound / longest).toInt()),
    )
}

internal const val MAX_VIDEO_POSTER_DIMENSION = 720
private val videoFrameDecoderLock = Any()

/**
 * Serializes poster/filmstrip decoders so a screen of clips cannot exhaust hardware codecs or
 * allocate several native full-resolution decode buffers at once. API 26 cannot request a scaled
 * decode; large clips keep their play button there instead of allocating an unsafe poster.
 */
internal fun decodeVideoFrame(
    source: File,
    timeMicros: Long,
    maxDimension: Int = MAX_VIDEO_POSTER_DIMENSION,
): Bitmap? = synchronized(videoFrameDecoderLock) {
    var retriever: MediaMetadataRetriever? = null
    try {
        val active = MediaMetadataRetriever().also { retriever = it }
        active.setDataSource(source.absolutePath)
        val width = active.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: return@synchronized null
        val height = active.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: return@synchronized null
        val size = videoFrameDecodeSize(width, height, maxDimension) ?: return@synchronized null
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            active.getScaledFrameAtTime(
                timeMicros.coerceAtLeast(0L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                size.width,
                size.height,
            )
        } else {
            if (width != size.width || height != size.height) return@synchronized null
            active.getFrameAtTime(timeMicros.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        // Some vendor implementations ignore the requested dimensions. Never hand their oversized
        // bitmap to Compose, which can otherwise crash while uploading it to the render thread.
        if (bitmap != null && maxOf(bitmap.width, bitmap.height) > minOf(maxDimension, MAX_VIDEO_POSTER_DIMENSION)) {
            bitmap.recycle()
            null
        } else {
            bitmap
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // A failed optional thumbnail must not take down the conversation or trim editor.
        null
    } finally {
        runCatching { retriever?.release() }
    }
}
