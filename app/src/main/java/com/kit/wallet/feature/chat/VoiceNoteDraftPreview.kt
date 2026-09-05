package com.kit.wallet.feature.chat

import android.content.Context
import android.media.MediaPlayer
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionTokens
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    private val ownsCurrentSession: () -> Boolean,
    private val onFinished: () -> Unit,
) {
    private var player: MediaPlayer? = null
    private var queue: List<File> = emptyList()
    private var index = 0

    val isPlaying: Boolean get() = player != null

    fun play(files: List<File>) {
        stopPlayer()
        if (!ownsCurrentSession()) {
            stop()
            onFinished()
            return
        }
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
        if (!ownsCurrentSession()) {
            stop()
            onFinished()
            return
        }
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
                if (player !== created) return@setOnCompletionListener
                index += 1
                stopPlayer()
                playNext()
            }
            created.setOnErrorListener { _, _, _ ->
                if (player === created) {
                    stop()
                    onFinished()
                }
                true
            }
            created.prepare()
            check(ownsCurrentSession()) { "The recording's session changed" }
            created.start()
            check(ownsCurrentSession()) { "The recording's session changed" }
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
        val active = player
        player = null
        active?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            runCatching { active.stop() }
            runCatching { active.release() }
        }
    }
}

/**
 * Keeps a voice-note draft alive across ordinary UI interruptions.
 *
 * The composer is a composable: navigation, recomposition, and configuration changes all
 * destroy it, and a draft owned by the composable would die with it. The recorder instead
 * lives here, keyed by authenticated session and conversation, bound to the application context,
 * and holding only
 * cache files and counters — so rotating the phone, glancing at another chat, or letting
 * the app background costs the user nothing they said. An empty recorder leaves this registry
 * on disposal; retirement of its authenticated session always removes it. Session retirement
 * deletes the files and revokes old recorder references, including pending permission callbacks.
 */
internal object VoiceNoteDrafts {
    private data class Key(val owner: SessionFence, val conversationId: String)
    private val recorders = mutableMapOf<Key, VoiceNoteRecorder>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessions: StateFlow<SessionTokens?>? = null
    private var bindingJob: Job? = null

    /** The application binds this once, so cleanup does not depend on a conversation being open. */
    fun bindToSession(sessions: StateFlow<SessionTokens?>) {
        bindingJob?.cancel()
        synchronized(recorders) {
            recorders.values.forEach(VoiceNoteRecorder::invalidate)
            recorders.clear()
            this.sessions = sessions
        }
        bindingJob = scope.launch {
            sessions.collect { synchronized(recorders) { retireObsoleteOwners() } }
        }
    }

    private fun owns(owner: SessionFence?): Boolean =
        owner != null && sessions?.value?.fence() == owner

    private fun retireObsoleteOwners() {
        val current = sessions?.value?.fence()
        val obsolete = recorders.filterKeys { it.owner != current }
        obsolete.forEach { (key, recorder) ->
            recorders.remove(key)
            recorder.invalidate()
        }
    }

    /** The one recorder for this conversation, created on first use. */
    fun recorder(conversationId: String, owner: SessionFence?, context: Context): VoiceNoteRecorder =
        synchronized(recorders) {
            // A successor can arrive before the flow collector runs. Fence lookup synchronously
            // too, so it cannot see an obsolete draft even for a shared group conversation ID.
            retireObsoleteOwners()
            if (!owns(owner)) {
                return@synchronized VoiceNoteRecorder(context.applicationContext) { false }
                    .also(VoiceNoteRecorder::invalidate)
            }
            recorders.getOrPut(Key(requireNotNull(owner), conversationId)) {
                VoiceNoteRecorder(context.applicationContext) { owns(owner) }
            }
        }

    /** Disposal may retire only its own recorder, never a successor's for the same conversation. */
    fun release(conversationId: String, owner: SessionFence?, recorder: VoiceNoteRecorder) {
        synchronized(recorders) {
            if (owner != null) {
                val key = Key(owner, conversationId)
                if (recorders[key] === recorder) recorders.remove(key)
            }
            recorder.invalidate()
        }
    }
}
