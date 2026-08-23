package com.kit.wallet.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.kit.wallet.data.messaging.KitChatMediaLimits
import java.io.File
import java.util.UUID
import kotlin.math.min

/**
 * Tap-to-start voice-note recorder matching the iOS `VoiceNoteRecorder` contract: AAC in an
 * MPEG-4 container (`audio/mp4`), 24 kHz mono at 32 kbps, at least one second and at most
 * thirty minutes. The temp file lives in the app cache only until the bytes are read back.
 */
internal class VoiceNoteRecorder(private val context: Context) {
    class Recording(val bytes: ByteArray, val durationMillis: Long) {
        companion object {
            const val MEDIA_TYPE = "audio/mp4"
        }
    }

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMillis: Long = 0

    val isRecording: Boolean get() = recorder != null

    fun elapsedMillis(): Long =
        if (recorder == null) 0 else System.currentTimeMillis() - startedAtMillis

    /** Live input level in 0..1 for the recording wave; 0 when idle. */
    fun level(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return min(1f, amplitude / 12_000f)
    }

    fun start() {
        check(recorder == null) { "A voice note is already being recorded" }
        val file = File(context.cacheDir, "kit-voice-${UUID.randomUUID()}.m4a")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioSamplingRate(24_000)
            created.setAudioChannels(1)
            created.setAudioEncodingBitRate(32_000)
            created.setMaxDuration(KitChatMediaLimits.VOICE_NOTE_MAX_DURATION_MILLIS.toInt())
            created.setOutputFile(file.absolutePath)
            created.prepare()
            created.start()
        } catch (error: Exception) {
            runCatching { created.release() }
            file.delete()
            throw IllegalStateException("Recording could not start. Check the microphone.", error)
        }
        recorder = created
        output = file
        startedAtMillis = System.currentTimeMillis()
    }

    /** Stops and returns the finished note, or null when shorter than the one-second minimum. */
    fun finish(): Recording? {
        val active = recorder ?: return null
        val file = output
        val duration = elapsedMillis()
        stopAndRelease(active)
        recorder = null
        output = null
        try {
            if (file == null || duration < KitChatMediaLimits.VOICE_NOTE_MIN_DURATION_MILLIS) {
                return null
            }
            val bytes = file.takeIf(File::isFile)?.readBytes() ?: return null
            if (!KitChatMediaLimits.fits(bytes.size.toLong())) return null
            return Recording(bytes, min(duration, KitChatMediaLimits.VOICE_NOTE_MAX_DURATION_MILLIS))
        } finally {
            file?.delete()
        }
    }

    fun cancel() {
        val active = recorder ?: return
        stopAndRelease(active)
        recorder = null
        output?.delete()
        output = null
    }

    private fun stopAndRelease(active: MediaRecorder) {
        // stop() throws if nothing was captured yet; the note is discarded either way.
        runCatching { active.stop() }
        runCatching { active.release() }
    }
}
