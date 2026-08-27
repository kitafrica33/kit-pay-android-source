package com.kit.wallet.feature.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.os.Build
import android.system.Os
import com.kit.wallet.data.messaging.chatMediaFileExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlin.math.max

/** The note the player is currently on, with everything the floating bar needs to name it. */
internal data class VoiceNotePlayingNote(
    val id: String,
    val context: VoiceNotePlaybackContext,
)

/** Everything the bubble, the floating bar and the notification each draw from. */
internal data class VoiceNotePlaybackState(
    val playing: VoiceNotePlayingNote? = null,
    val isPaused: Boolean = false,
    val progress: Float = 0f,
    val durationMillis: Long = 0,
    /**
     * Whether the bubble that owns the playing note is on screen. The floating bar is the fallback
     * control for when it is not.
     */
    val isSourceOnScreen: Boolean = false,
) {
    fun isCurrent(messageId: String): Boolean = playing?.id == messageId

    val positionMillis: Long
        get() = VoiceNoteSeekPolicy.timeForFraction(progress, durationMillis)
}

/**
 * One-at-a-time voice-note playback with observable progress, seeking, and a life of its own.
 *
 * Plays the opened attachment through a descriptor this process holds open, so nothing is ever
 * read from a world-readable path and the note keeps playing even if the media cache evicts its
 * entry mid-listen. The player deliberately outlives
 * the bubble that started it: the note keeps playing when the thread is scrolled past it, when the
 * chat is left, and when Kit Pay is put in the background — the same expectation a call sets.
 * [VoiceNotePlaybackService] holds the foreground notification for exactly that window, and the
 * floating bar ([VoiceNoteMiniBarPolicy]) is the on-screen control whenever the note's own bubble is
 * not visible.
 *
 * Rule for rule the same as iOS `VoiceNotePlayer`.
 */
internal object VoiceNotePlayer {
    private val mutableState = MutableStateFlow(VoiceNotePlaybackState())
    val state: StateFlow<VoiceNotePlaybackState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var source: FileInputStream? = null
    private var progressJob: Job? = null
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * An interruption — a call, another app taking the output — leaves the note paused rather than
     * silently "playing" into a stream it no longer owns.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> pause()
        }
    }

    // MARK: Transport

    /**
     * Plays [file] for [messageId], or pauses and resumes it when it is already the note in hand.
     * Any other note stops first — a thread only ever speaks with one voice.
     */
    fun toggle(
        context: Context,
        messageId: String,
        file: File,
        playbackContext: VoiceNotePlaybackContext,
    ) {
        appContext = context.applicationContext
        if (mutableState.value.isCurrent(messageId) && player != null) {
            if (mutableState.value.isPaused) resume() else pause()
            return
        }
        stop()
        // A live call owns the audio route; a voice note must never take it away.
        if (isCallInProgress()) return
        if (!requestAudioFocus()) return
        val opened = try {
            FileInputStream(file)
        } catch (error: Exception) {
            abandonAudioFocus()
            throw IllegalStateException("This voice note could not be played", error)
        }
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(playbackAttributes())
            created.setDataSource(opened.fd)
            created.prepare()
            created.setOnCompletionListener { stop() }
            created.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            created.start()
        } catch (error: Exception) {
            runCatching { created.release() }
            runCatching { opened.close() }
            abandonAudioFocus()
            throw IllegalStateException("This voice note could not be played", error)
        }
        player = created
        source = opened
        mutableState.value = VoiceNotePlaybackState(
            playing = VoiceNotePlayingNote(messageId, playbackContext),
            isPaused = false,
            progress = 0f,
            durationMillis = max(0, created.duration.toLong()),
            // Playback always begins from a control the user just touched.
            isSourceOnScreen = true,
        )
        startProgressUpdates()
        VoiceNotePlaybackService.refresh(appContext, mutableState.value)
    }

    fun pause() {
        val active = player ?: return
        if (!runCatching { active.isPlaying }.getOrDefault(false)) return
        runCatching { active.pause() }
        progressJob?.cancel()
        progressJob = null
        mutableState.value = mutableState.value.copy(isPaused = true)
        VoiceNotePlaybackService.refresh(appContext, mutableState.value)
    }

    fun resume() {
        val active = player ?: return
        if (mutableState.value.playing == null) return
        if (isCallInProgress()) return
        if (!requestAudioFocus()) return
        runCatching { active.start() }
        mutableState.value = mutableState.value.copy(isPaused = false)
        startProgressUpdates()
        VoiceNotePlaybackService.refresh(appContext, mutableState.value)
    }

    fun toggleCurrent() {
        if (mutableState.value.playing == null) return
        if (mutableState.value.isPaused) resume() else pause()
    }

    /**
     * Positions playback at [fraction] of the note. Used by both a tap inside the waveform and a
     * slide along it; scrubbing past either end simply rests at that end.
     */
    fun seekToFraction(fraction: Float) {
        val active = player ?: return
        val duration = mutableState.value.durationMillis
        if (mutableState.value.playing == null) return
        val target = VoiceNoteSeekPolicy.timeForFraction(fraction, duration)
        runCatching { active.seekTo(target.toInt()) }
        mutableState.value = mutableState.value.copy(
            progress = VoiceNoteSeekPolicy.fractionForTime(target, duration),
        )
        VoiceNotePlaybackService.refresh(appContext, mutableState.value)
    }

    /** Nudges playback by [deltaMillis], for the notification's skip controls. */
    fun seekBy(deltaMillis: Long) {
        val current = mutableState.value
        if (current.playing == null || current.durationMillis <= 0) return
        seekToFraction(
            VoiceNoteSeekPolicy.fractionForTime(
                current.positionMillis + deltaMillis,
                current.durationMillis,
            ),
        )
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        player?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        player = null
        runCatching { source?.close() }
        source = null
        abandonAudioFocus()
        mutableState.value = VoiceNotePlaybackState()
        VoiceNotePlaybackService.refresh(appContext, mutableState.value)
    }

    // MARK: Source visibility

    /**
     * Reported by the bubble that owns a note as it enters and leaves the screen. A stale report
     * from another row can never move the bar, because only the playing note's own source is heard.
     */
    fun noteSourceVisibility(visible: Boolean, messageId: String) {
        val current = mutableState.value
        if (!current.isCurrent(messageId) || current.isSourceOnScreen == visible) return
        mutableState.value = current.copy(isSourceOnScreen = visible)
    }

    // MARK: Progress

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val active = player ?: return@launch
                // A call that starts mid-note takes the route; end the note rather than let it
                // fight the call for the earpiece.
                if (isCallInProgress()) {
                    stop()
                    return@launch
                }
                val duration = mutableState.value.durationMillis
                if (duration > 0) {
                    val position = runCatching { active.currentPosition.toLong() }.getOrDefault(0L)
                    mutableState.value = mutableState.value.copy(
                        progress = VoiceNoteSeekPolicy.fractionForTime(position, duration),
                    )
                }
                delay(120)
            }
        }
    }

    // MARK: Audio focus

    private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun audioManager(): AudioManager? {
        audioManager?.let { return it }
        val resolved = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = resolved
        return resolved
    }

    private fun isCallInProgress(): Boolean {
        val mode = audioManager()?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager() ?: return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
    }

}

/**
 * Deterministic 26-bar waveform seeded from the message UUID, byte-compatible with the iOS
 * `VoiceNoteWaveform` shape (`height = 6 + byte % 16` over a 22-point track).
 */
internal fun voiceNoteWaveformFractions(messageId: String): List<Float> {
    val seed = messageId.filter(Char::isLetterOrDigit).ifEmpty { "kitpay" }
    return List(26) { index ->
        val byte = seed[index % seed.length].code
        (6 + (byte % 16)) / 22f
    }
}

/**
 * Extracts a poster frame near the start of a decrypted video, like the iOS 0.1 s poster.
 *
 * The retriever reads the opened attachment where it already lies, so posting a thumbnail for a
 * 200 MB video costs one seek rather than a second copy of the video.
 */
internal fun videoPosterFrame(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.getFrameAtTime(100_000)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * Presents an opened attachment under a name a viewer can show, for a short-lived hand-off.
 *
 * The bytes are already decrypted in app-private storage; what an external viewer additionally
 * needs is a path this app's FileProvider is willing to grant and a filename worth reading. A
 * hard link gives both without duplicating the file, which at a 200 MB cap is the difference
 * between opening a document and running the phone out of space; a copy is the fallback for the
 * rare case the two directories are not on one filesystem.
 *
 * Because the link is its own reference to the data, the cache is free to evict its entry while
 * the viewer is still reading. The caller deletes this file when the viewer closes, mirroring iOS
 * temp-file hygiene.
 */
internal fun writeChatMediaTempFile(
    context: Context,
    source: File,
    mediaType: String,
    displayName: String?,
): File {
    val directory = File(context.cacheDir, "chat-media/${UUID.randomUUID()}")
    check(directory.mkdirs()) { "The media viewer could not prepare storage" }
    val sanitized = displayName
        ?.replace(Regex("[/\\\\:]"), "-")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(120)
        ?: "Kit-media.${chatMediaFileExtension(mediaType)}"
    val named = if (sanitized.contains('.')) {
        sanitized
    } else {
        "$sanitized.${chatMediaFileExtension(mediaType)}"
    }
    val file = File(directory, named)
    val linked = runCatching { Os.link(source.absolutePath, file.absolutePath) }.isSuccess
    if (!linked) {
        source.inputStream().use { input -> file.outputStream().use(input::copyTo) }
    }
    return file
}

internal fun deleteChatMediaTempFile(file: File) {
    runCatching {
        file.delete()
        file.parentFile?.takeIf { it.name.length == 36 }?.delete()
    }
}

internal fun formatVoiceNoteTime(fractionOrMillis: Long): String {
    val totalSeconds = max(0L, fractionOrMillis / 1_000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Plaintext scratch left behind when the process died with a viewer or a capture open.
 *
 * Both directories hold decrypted attachments the app wrote for something outside itself — a
 * document viewer, the media picker's transcoder, the camera's encoder — and both are cleaned up
 * by their owners in the normal case. The abnormal case is a kill, and at a 200 MB cap that leaves
 * real megabytes of plaintext sitting in the cache with nobody left who remembers them.
 *
 * This runs once per process, not once per Activity: a viewer in another app still holds a
 * content URI into `chat-media` across a configuration change, and pulling the file out from
 * under it would break an open document for no reason.
 */
internal object ChatMediaScratch {
    private val purged = java.util.concurrent.atomic.AtomicBoolean(false)

    internal val DIRECTORY_NAMES = listOf("chat-media", "chat-capture")

    fun purgeStaleOnce(context: Context) {
        if (!purged.compareAndSet(false, true)) return
        for (name in DIRECTORY_NAMES) {
            runCatching { File(context.cacheDir, name).deleteRecursively() }
        }
    }
}
