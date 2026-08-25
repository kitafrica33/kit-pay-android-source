package com.kit.wallet.data.realtime

import com.kit.wallet.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The outbound half of typing: keystrokes in, at most one request every four
 * seconds out.
 *
 * All of the coalescing lives here rather than in the ViewModel, and that is a
 * correctness decision, not a tidiness one. A rapid conversation switch is the
 * case that breaks a naive implementation: leaving A for B while a debounce is
 * pending would otherwise post a `start` for A after the user is already looking
 * at B, leaving a bubble in a conversation nobody is typing in until it expires.
 * Switching therefore stops A first, synchronously, before B is even considered.
 *
 * Parameters, and why:
 *
 * - **300 ms debounce** before the first `start`. Kills a single stray keypress,
 *   and blunts the "began composing" timing signal available to the peer.
 * - **4 s throttle** while typing continues. Caps a 60 wpm typist at 15 requests a
 *   minute per conversation; the receiver's 6 s expiry is 1.5× that, so one lost
 *   request cannot flicker the bubble.
 * - **Explicit `stop`** when the composer empties, when the conversation leaves the
 *   foreground, and — the important one — the instant an outbound message is
 *   durably committed, *before* its network POST, so the bubble can never outlive
 *   the message that ends it.
 *
 * Every request is fire-and-forget. A failure is dropped without a retry, because
 * a retry queue on the path of a keystroke is a worse outcome than a bubble that
 * expires on its own six seconds later.
 */
/**
 * The three composer events the outbound half needs, and nothing else.
 *
 * [KitTypingSignaller.arm] and [KitTypingSignaller.disarm] are deliberately absent:
 * whether we are willing to signal at all is the coordinator's call, decided from
 * the advertisement and a live socket, and a screen must not be able to override it.
 */
internal interface KitTypingSignals {
    /** Called on every composer change, including the one that empties it. */
    fun onComposerChanged(conversationId: String, text: String)

    /**
     * The message is durably in the outbox. Called **before** the network POST: the
     * peer must never see "typing…" attached to a message that has already arrived.
     */
    fun onMessageCommitted(conversationId: String)

    /** The conversation left the foreground, or its channel was given up. */
    fun onConversationClosed(conversationId: String)
}

@Singleton
internal class KitTypingSignaller @Inject constructor(
    private val api: KitRealtimeAuthApi,
    private val clock: KitRealtimeClock,
    @ApplicationScope private val scope: CoroutineScope,
) : KitTypingSignals {
    private val lock = Any()

    private var conversationId: String? = null
    private var debounce: Job? = null
    private var announced: Boolean = false
    private var lastStartMillis: Long = 0L

    /**
     * Whether signalling is worth doing at all: the server advertised typing *and*
     * we have a live socket. Without one we would not be rendering the peer's
     * bubble either, so the requests would buy nothing.
     */
    @Volatile
    private var armed: Boolean = false

    @Volatile
    private var socketId: String? = null

    fun arm(socketId: String) {
        this.socketId = socketId
        armed = true
    }

    /** The socket went away. Nothing to announce and nobody listening. */
    fun disarm() {
        armed = false
        socketId = null
        synchronized(lock) { reset() }
    }

    override fun onComposerChanged(conversationId: String, text: String) {
        synchronized(lock) {
            if (this.conversationId != conversationId) {
                // Stop the conversation being left before anything else happens, so
                // a pending debounce for it can never fire against the new one.
                stopAnnounced()
                this.conversationId = conversationId
            }

            if (!armed) return
            if (text.isBlank()) {
                stopAnnounced()
                return
            }

            val now = clock.elapsedMillis()

            if (announced) {
                if (now - lastStartMillis < THROTTLE_MILLIS) return
                lastStartMillis = now
                post(conversationId, TypingRequest.Start)
                return
            }

            if (debounce != null) return
            debounce = scope.launch {
                delay(DEBOUNCE_MILLIS)
                synchronized(lock) {
                    debounce = null
                    if (!armed || this@KitTypingSignaller.conversationId != conversationId) {
                        return@launch
                    }
                    announced = true
                    lastStartMillis = clock.elapsedMillis()
                    post(conversationId, TypingRequest.Start)
                }
            }
        }
    }

    override fun onMessageCommitted(conversationId: String) {
        synchronized(lock) {
            if (this.conversationId != conversationId) return
            stopAnnounced()
        }
    }

    override fun onConversationClosed(conversationId: String) {
        synchronized(lock) {
            if (this.conversationId != conversationId) return
            stopAnnounced()
            this.conversationId = null
        }
    }

    /** Cancels any pending debounce and sends `stop` only if a `start` went out. */
    private fun stopAnnounced() {
        debounce?.cancel()
        debounce = null
        lastStartMillis = 0L
        if (!announced) return
        announced = false
        conversationId?.let { post(it, TypingRequest.Stop) }
    }

    private fun reset() {
        debounce?.cancel()
        debounce = null
        announced = false
        lastStartMillis = 0L
    }

    private fun post(conversationId: String, body: TypingRequest) {
        val socket = socketId
        scope.launch {
            runCatching { api.typing(conversationId, socket, body) }
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS: Long = 300L
        const val THROTTLE_MILLIS: Long = 4_000L
    }
}
