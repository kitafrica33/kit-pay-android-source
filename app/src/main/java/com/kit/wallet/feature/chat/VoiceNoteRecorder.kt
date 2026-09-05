package com.kit.wallet.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.kit.wallet.data.messaging.KitChatMediaLimits
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.session.SessionInvalidatedException
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Tap-to-start voice-note recorder matching the iOS `VoiceNoteRecorder` contract: AAC in an
 * MPEG-4 container (`audio/mp4`), 24 kHz mono at 32 kbps, at least one second and at most
 * thirty minutes.
 *
 * A draft is captured as a row of *segments*. An MPEG-4 file is only playable once its
 * metadata is finalized at stop, so pausing stops the current recorder outright — turning
 * what exists so far into a finished, listenable file — and resuming opens the next
 * segment rather than reopening the old one. Send stitches the row back into the single
 * `audio/mp4` the wire expects. The temp files live in the app cache, plaintext, and only
 * until they are read back at Send or explicitly discarded; nothing is encrypted or
 * uploaded before then.
 */
internal class VoiceNoteRecorder(
    private val context: Context,
    private val ownerIsCurrent: () -> Boolean,
) {
    /**
     * Ownership of finalized recording segments transferred out of the recorder.
     *
     * [source] defers the only potentially expensive step (joining paused segments) until the
     * repository has already committed the permanent local message identity. The stream is
     * file-backed in every case, including a 30-minute note, and [release] is idempotent so UI
     * cancellation cannot race cleanup against the repository's final read.
     */
    class Recording internal constructor(
        private val files: List<File>,
        val durationMillis: Long,
    ) {
        private val released = AtomicBoolean(false)
        private val assemblyLock = Any()
        private var assembled: File? = files.singleOrNull()

        val source: SecureMediaSource = SecureMediaSource(
            declaredByteCount = files.sumOf(File::length),
            durationMillis = durationMillis,
            localPlaybackFile = files.singleOrNull()?.takeIf { it.isFile && it.length() > 0L },
        ) {
            assembledFile().inputStream()
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(assemblyLock) {
                assembled?.takeUnless { it in files }?.delete()
                assembled = null
                files.forEach(File::delete)
            }
        }

        private fun assembledFile(): File = synchronized(assemblyLock) {
            check(!released.get()) { "The voice-note source is no longer available" }
            assembled?.takeIf { it.isFile && it.length() > 0L }?.let { return@synchronized it }
            VoiceNoteSegmentAssembler.assembleFile(files)?.also { assembled = it }
                ?: error("The voice note could not be prepared")
        }

        companion object {
            const val MEDIA_TYPE = "audio/mp4"
        }
    }

    private val segments = mutableListOf<File>()
    private var finalizedMillis: Long = 0
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMillis: Long = 0
    private var revoked = false
    private val invalidationListeners = mutableSetOf<() -> Unit>()

    fun ownsCurrentSession(): Boolean = !revoked && ownerIsCurrent()

    val isRecording: Boolean get() = ownsCurrentSession() && recorder != null

    /** Whether any capture exists at all — an active segment or finalized ones. */
    val hasDraft: Boolean get() = ownsCurrentSession() && (recorder != null || segments.isNotEmpty())

    /** Whether at least one finalized, individually playable segment exists. */
    val hasPlayableSegments: Boolean get() = ownsCurrentSession() && segments.isNotEmpty()

    /**
     * Total captured audio: every finalized segment plus the live one. Measured on the
     * monotonic clock, so a wall-clock step mid-recording cannot stretch or shrink it.
     */
    fun elapsedMillis(): Long = if (ownsCurrentSession()) finalizedMillis + activeMillis() else 0L

    /** Live input level in 0..1 for the recording wave; 0 when idle or paused. */
    fun level(): Float {
        if (!ownsCurrentSession()) return 0f
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return min(1f, amplitude / 12_000f)
    }

    fun start() {
        requireCurrentOwner()
        check(!hasDraft) { "A voice note is already being recorded" }
        beginSegment()
    }

    /**
     * Finalizes the active segment into a playable file and stops the microphone. A
     * near-empty segment that MPEG-4 cannot finalize is silently dropped — its handful of
     * milliseconds is not audio the user could miss. Idempotent when already paused.
     */
    fun pause() {
        if (!ownsCurrentSession()) {
            invalidate()
            return
        }
        val active = recorder ?: return
        val file = output
        val duration = activeMillis()
        recorder = null
        output = null
        // stop() throws if nothing was captured yet; that segment is dropped either way.
        val finalized = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }
        if (!ownsCurrentSession()) {
            file?.delete()
            invalidate()
            return
        }
        if (finalized && file != null && file.isFile && file.length() > 0) {
            segments += file
            finalizedMillis += duration
        } else {
            file?.delete()
        }
    }

    /** Opens the next segment of a paused draft. */
    fun resume() {
        requireCurrentOwner()
        check(recorder == null) { "A voice note is already being recorded" }
        beginSegment()
    }

    /**
     * The finalized segments, in capture order, for local listen-back while paused. The
     * files remain owned by this recorder: play them in place, never move or delete them.
     */
    fun previewFiles(): List<File> = if (ownsCurrentSession()) segments.toList() else emptyList()

    /** Retiring a session also stops any composed listen-back player immediately. */
    fun observeInvalidation(listener: () -> Unit): () -> Unit {
        if (revoked) listener() else invalidationListeners += listener
        return { invalidationListeners -= listener }
    }

    /** A retired recorder can never be revived by a late permission/result callback. */
    fun invalidate() {
        if (revoked) return
        revoked = true
        cancel()
        invalidationListeners.toList().forEach { runCatching(it) }
        invalidationListeners.clear()
    }

    /**
     * Stops and transfers the finalized files without reading them into heap. The caller owns the
     * returned [Recording] until its local-first send completes and must invoke `release()` then.
     */
    fun finish(): Recording? {
        if (!ownsCurrentSession()) {
            invalidate()
            return null
        }
        pause()
        if (!ownsCurrentSession()) {
            invalidate()
            return null
        }
        val files = segments.toList()
        val duration = finalizedMillis
        segments.clear()
        finalizedMillis = 0
        if (
            files.isEmpty() ||
            duration < KitChatMediaLimits.VOICE_NOTE_MIN_DURATION_MILLIS ||
            !KitChatMediaLimits.fits(files.sumOf(File::length))
        ) {
            files.forEach { it.delete() }
            return null
        }
        return Recording(files, min(duration, KitChatMediaLimits.VOICE_NOTE_MAX_DURATION_MILLIS))
    }

    /** The explicit discard: everything captured so far is deleted, live or finalized. */
    fun cancel() {
        val active = recorder
        recorder = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        output?.delete()
        output = null
        segments.forEach { it.delete() }
        segments.clear()
        finalizedMillis = 0
    }

    private fun activeMillis(): Long =
        if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAtMillis

    private fun requireCurrentOwner() {
        if (!ownsCurrentSession()) {
            invalidate()
            throw SessionInvalidatedException()
        }
    }

    private fun beginSegment() {
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
            // Each segment may only spend what the earlier ones have left of the cap.
            created.setMaxDuration(
                (KitChatMediaLimits.VOICE_NOTE_MAX_DURATION_MILLIS - finalizedMillis)
                    .coerceAtLeast(1_000)
                    .toInt(),
            )
            created.setOutputFile(file.absolutePath)
            created.prepare()
            requireCurrentOwner()
            created.start()
            requireCurrentOwner()
        } catch (error: Exception) {
            runCatching { created.release() }
            file.delete()
            throw IllegalStateException("Recording could not start. Check the microphone.", error)
        }
        recorder = created
        output = file
        startedAtMillis = SystemClock.elapsedRealtime()
    }
}
