package com.kit.wallet.feature.chat.camera

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

internal const val MIN_CLIP_MILLIS = 500L

private const val DEFAULT_SAMPLE_BUFFER_BYTES = 1 shl 20

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
 * Stream-copies compressed samples from a recorded clip into a trimmed MP4 without
 * decoding, so trimming and muting stay fast and generation-loss free. The window opens
 * at the first sync frame AT OR AFTER the requested start, so frames the sender trimmed
 * away are never sent — the clip may start up to one GOP later than requested, never
 * earlier. Only when the window holds no sync frame at all (a sub-GOP window at the very
 * end of the clip) does it fall back to the previous sync frame.
 */
internal object ChatVideoTranscoder {

    fun trim(source: File, destination: File, plan: VideoTrimPlan): Boolean {
        if (plan.durationMicros <= 0L) return false
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(source.absolutePath)
            val videoTrack = trackIndex(extractor, "video/") ?: return false
            val audioTrack = if (plan.keepAudio) trackIndex(extractor, "audio/") else null

            val created = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = created
            val videoFormat = extractor.getTrackFormat(videoTrack)
            val audioFormat = audioTrack?.let(extractor::getTrackFormat)
            val muxerVideoTrack = created.addTrack(videoFormat)
            val muxerAudioTrack = audioFormat?.let(created::addTrack)
            sourceRotationDegrees(source)
                ?.takeIf { it == 90 || it == 180 || it == 270 }
                ?.let(created::setOrientationHint)
            created.start()

            val buffer = ByteBuffer.allocate(sampleBufferBytes(videoFormat, audioFormat))
            var video = copyTrack(
                extractor, created, videoTrack, muxerVideoTrack, buffer, plan,
                rebaseMicros = null,
                seekMode = MediaExtractor.SEEK_TO_NEXT_SYNC,
            )
            if (video.samplesWritten == 0) {
                video = copyTrack(
                    extractor, created, videoTrack, muxerVideoTrack, buffer, plan,
                    rebaseMicros = null,
                    seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                )
            }
            check(video.samplesWritten > 0) { "The trim window holds no video samples" }
            if (audioTrack != null && muxerAudioTrack != null) {
                // Audio frames are all sync frames; drop the ones before the video base so no
                // sound from ahead of the chosen start leaks into the clip.
                copyTrack(
                    extractor, created, audioTrack, muxerAudioTrack, buffer, plan,
                    rebaseMicros = video.baseMicros,
                    seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                    dropBeforeMicros = video.baseMicros,
                )
            }
            created.stop()
            true
        } catch (_: Exception) {
            destination.delete()
            false
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }

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
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            retriever.setDataSource(source.absolutePath)
            val decoded = retriever.getFrameAtTime(atMillis * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            frame = decoded
            val longest = max(decoded.width, decoded.height)
            val poster = if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                Bitmap.createScaledBitmap(
                    decoded,
                    max(1, (decoded.width * scale).toInt()),
                    max(1, (decoded.height * scale).toInt()),
                    true,
                ).also { scaled = it }
            } else {
                decoded
            }
            val output = ByteArrayOutputStream()
            if (!poster.compress(Bitmap.CompressFormat.JPEG, 85, output)) return null
            output.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { scaled?.recycle() }
            runCatching { frame?.recycle() }
        }
    }

    private class TrackCopy(val samplesWritten: Int, val baseMicros: Long)

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        sourceTrack: Int,
        muxerTrack: Int,
        buffer: ByteBuffer,
        plan: VideoTrimPlan,
        rebaseMicros: Long?,
        seekMode: Int,
        dropBeforeMicros: Long? = null,
    ): TrackCopy {
        extractor.selectTrack(sourceTrack)
        val info = MediaCodec.BufferInfo()
        var base = rebaseMicros
        var written = 0
        try {
            extractor.seekTo(plan.startMicros, seekMode)
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > plan.endMicros) break
                if (dropBeforeMicros != null && sampleTime < dropBeforeMicros) {
                    if (!extractor.advance()) break
                    continue
                }
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (base == null) base = sampleTime
                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, (sampleTime - base).coerceAtLeast(0L), flags)
                muxer.writeSampleData(muxerTrack, buffer, info)
                written++
                if (!extractor.advance()) break
            }
        } finally {
            runCatching { extractor.unselectTrack(sourceTrack) }
        }
        return TrackCopy(written, base ?: plan.startMicros)
    }

    private fun trackIndex(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return index
        }
        return null
    }

    private fun sampleBufferBytes(vararg formats: MediaFormat?): Int {
        var largest = 0
        for (format in formats) {
            if (format != null && format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                largest = max(largest, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
        return if (largest > 0) largest else DEFAULT_SAMPLE_BUFFER_BYTES
    }

    private fun sourceRotationDegrees(source: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
