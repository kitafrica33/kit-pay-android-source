package com.kit.wallet.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/** An already-validated sample-copy window for a video upload representation. */
internal data class MediaVideoRemuxPlan(
    val startMicros: Long,
    val endMicros: Long,
    val keepAudio: Boolean,
)

/**
 * Generation-loss-free MP4 remuxing shared by the editor and durable media worker.
 *
 * The source is never changed. Work is written to the caller's unpublished scratch file, and a
 * failed or interrupted remux removes that scratch so no partial clip can reach encryption.
 */
internal object MediaVideoRemuxer {
    private const val DEFAULT_SAMPLE_BUFFER_BYTES = 1 shl 20

    fun remux(source: File, destination: File, plan: MediaVideoRemuxPlan): Boolean {
        if (plan.endMicros <= plan.startMicros) return false
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(source.absolutePath)
            val sourceFormats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val selection = canonicalMp4TrackSelection(
                sourceFormats.map { it.getString(MediaFormat.KEY_MIME) },
                keepAudio = plan.keepAudio,
            ) ?: return false
            val videoTrack = selection.videoTrack
            val audioTrack = selection.audioTrack

            val created = MediaMuxer(
                destination.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = created
            val videoFormat = sourceFormats[videoTrack]
            val audioFormat = audioTrack?.let(sourceFormats::get)
            val muxerVideoTrack = created.addTrack(videoFormat)
            val muxerAudioTrack = audioFormat?.let(created::addTrack)
            sourceRotationDegrees(source)
                ?.takeIf { it == 90 || it == 180 || it == 270 }
                ?.let(created::setOrientationHint)
            created.start()

            val buffer = ByteBuffer.allocate(sampleBufferBytes(videoFormat, audioFormat))
            var video = copyTrack(
                extractor = extractor,
                muxer = created,
                sourceTrack = videoTrack,
                muxerTrack = muxerVideoTrack,
                buffer = buffer,
                plan = plan,
                rebaseMicros = null,
                seekMode = MediaExtractor.SEEK_TO_NEXT_SYNC,
            )
            if (video.samplesWritten == 0) {
                video = copyTrack(
                    extractor = extractor,
                    muxer = created,
                    sourceTrack = videoTrack,
                    muxerTrack = muxerVideoTrack,
                    buffer = buffer,
                    plan = plan,
                    rebaseMicros = null,
                    seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                )
            }
            check(video.samplesWritten > 0) { "The trim window holds no video samples" }
            if (audioTrack != null && muxerAudioTrack != null) {
                copyTrack(
                    extractor = extractor,
                    muxer = created,
                    sourceTrack = audioTrack,
                    muxerTrack = muxerAudioTrack,
                    buffer = buffer,
                    plan = plan,
                    rebaseMicros = video.baseMicros,
                    seekMode = MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                    dropBeforeMicros = video.baseMicros,
                )
            }
            created.stop()
            destination.isFile && destination.length() > 0L
        } catch (_: Exception) {
            destination.delete()
            false
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }

    private data class TrackCopy(val samplesWritten: Int, val baseMicros: Long)

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        sourceTrack: Int,
        muxerTrack: Int,
        buffer: ByteBuffer,
        plan: MediaVideoRemuxPlan,
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
                if (sampleTime < 0L || sampleTime > plan.endMicros) break
                if (dropBeforeMicros != null && sampleTime < dropBeforeMicros) {
                    if (!extractor.advance()) break
                    continue
                }
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (base == null) base = sampleTime
                val flags = if (
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                ) {
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
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
