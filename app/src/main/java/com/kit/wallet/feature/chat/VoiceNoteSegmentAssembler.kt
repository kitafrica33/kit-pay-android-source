package com.kit.wallet.feature.chat

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Stitches the finalized segments of a paused-and-resumed voice note back into the single
 * `audio/mp4` file the wire contract expects, by copying AAC samples — never re-encoding,
 * so pausing costs the note no quality and the send no meaningful time.
 *
 * The unpaused note stays exactly what it always was: one segment short-circuits to its own
 * bytes without touching the muxer. Any failure returns null rather than half a file — a
 * voice note that cannot be assembled is not sent, and the caller tells the user.
 */
internal object VoiceNoteSegmentAssembler {
    fun assemble(segments: List<File>): ByteArray? {
        val single = segments.singleOrNull()
        if (single != null) return single.takeIf(File::isFile)?.readBytes()
        if (segments.isEmpty()) return null

        val joined = File.createTempFile("kit-voice-joined-", ".m4a", segments.first().parentFile)
        return try {
            runCatching {
                join(segments, into = joined)
                joined.readBytes()
            }.getOrNull()
        } finally {
            joined.delete()
        }
    }

    private fun join(segments: List<File>, into: File) {
        val muxer = MediaMuxer(into.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            var trackIndex = -1
            var started = false
            // Where the next segment's samples begin on the joined timeline.
            var timelineOffsetUs = 0L
            val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()

            for (segment in segments) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.absolutePath)
                    val audioTrack = (0 until extractor.trackCount).firstOrNull { track ->
                        extractor.getTrackFormat(track)
                            .getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    } ?: error("A voice-note segment carries no audio track")
                    extractor.selectTrack(audioTrack)
                    val format = extractor.getTrackFormat(audioTrack)
                    if (!started) {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        started = true
                    }

                    // One AAC frame is 1024 samples; spacing the next segment by exactly one
                    // frame keeps the joined timeline gapless without overlapping timestamps.
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val frameDurationUs = 1_000_000L * 1_024 / sampleRate
                    var lastSampleTimeUs = -frameDurationUs

                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.set(
                            0,
                            size,
                            timelineOffsetUs + extractor.sampleTime,
                            MediaCodec.BUFFER_FLAG_KEY_FRAME,
                        )
                        muxer.writeSampleData(trackIndex, buffer, info)
                        lastSampleTimeUs = extractor.sampleTime
                        extractor.advance()
                    }
                    timelineOffsetUs += lastSampleTimeUs + frameDurationUs
                } finally {
                    extractor.release()
                }
            }
            check(started) { "No voice-note segment carried audio" }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    private const val SAMPLE_BUFFER_BYTES = 256 * 1024
}
