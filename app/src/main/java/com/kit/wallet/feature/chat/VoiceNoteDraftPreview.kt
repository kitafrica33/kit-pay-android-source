package com.kit.wallet.feature.chat

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Local listen-back for a paused voice-note draft.
 *
 * The draft is a row of finalized segment files, so playback is a tiny sequencer: play one
 * file, and when it completes play the next, until the row runs out. Everything stays on
 * this device — the player reads the plaintext cache files in place and never copies,
 * encrypts, or uploads anything. Stopping, or any playback failure, simply hands the
 * composer back its paused draft.
 */
internal class VoiceNoteDraftPreviewPlayer(
    private val onFinished: () -> Unit,
) {
    private var player: MediaPlayer? = null
    private var queue: List<File> = emptyList()
    private var index = 0

    val isPlaying: Boolean get() = player != null

    fun play(files: List<File>) {
        stopPlayer()
        queue = files
        index = 0
        playNext()
    }

    fun stop() {
        stopPlayer()
        queue = emptyList()
        index = 0
    }

    private fun playNext() {
        val file = queue.getOrNull(index)
        if (file == null) {
            stop()
            onFinished()
            return
        }
        val created = MediaPlayer()
        val started = runCatching {
            created.setDataSource(file.absolutePath)
            created.setOnCompletionListener {
                index += 1
                stopPlayer()
                playNext()
            }
            created.prepare()
            created.start()
        }.isSuccess
        if (started) {
            player = created
        } else {
            runCatching { created.release() }
            stop()
            onFinished()
        }
    }

    private fun stopPlayer() {
        player?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        player = null
    }
}

/**
 * Keeps a voice-note draft alive across ordinary UI interruptions.
 *
 * The composer is a composable: navigation, recomposition, and configuration changes all
 * destroy it, and a draft owned by the composable would die with it. The recorder instead
 * lives here, keyed by conversation, bound to the application context, and holding only
 * cache files and counters — so rotating the phone, glancing at another chat, or letting
 * the app background costs the user nothing they said. A draft leaves this registry in
 * exactly two ways: it is sent, or it is explicitly discarded. Process death is the one
 * interruption that still loses it, which is what makes these files safe to hold.
 */
internal object VoiceNoteDrafts {
    private val recorders = mutableMapOf<String, VoiceNoteRecorder>()

    /** The one recorder for this conversation, created on first use. */
    fun recorder(conversationId: String, context: Context): VoiceNoteRecorder =
        synchronized(recorders) {
            recorders.getOrPut(conversationId) {
                VoiceNoteRecorder(context.applicationContext)
            }
        }

    /** Called after send or discard, when the draft no longer exists to preserve. */
    fun release(conversationId: String) {
        synchronized(recorders) { recorders.remove(conversationId) }
    }
}
