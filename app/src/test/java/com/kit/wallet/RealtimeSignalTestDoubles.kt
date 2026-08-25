package com.kit.wallet

import com.kit.wallet.data.realtime.KitConversationSignals
import com.kit.wallet.data.realtime.KitTypingSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A conversation with no socket behind it.
 *
 * `foregroundSyncIntervalMillis` is `null` — the state the coordinator publishes
 * while it is `Live` — so a ViewModel under test syncs once and then waits, rather
 * than spinning a poll loop that would make every `advanceUntilIdle` hang.
 *
 * Realtime behaviour is covered where it lives, in `KitRealtimeStateMachineTest`
 * and the registry tests. These doubles exist so the chat tests can stay about
 * chat.
 */
internal object InertConversationSignals : KitConversationSignals {
    override val foregroundSyncIntervalMillis: StateFlow<Long?> = MutableStateFlow(null)

    override fun observeConversation(conversationId: String) = Unit

    override fun stopObservingConversation(conversationId: String) = Unit
}

/**
 * [InertConversationSignals] with the two things a chat test may need to drive: which
 * conversations were watched, and the fallback poll interval.
 *
 * `syncInterval` starts `null`, which is what the coordinator publishes while it is
 * `Live`. Setting it is how a test says "the socket is down".
 */
internal class RecordingConversationSignals : KitConversationSignals by InertConversationSignals {
    val observed = mutableListOf<String>()
    val released = mutableListOf<String>()
    val syncInterval = MutableStateFlow<Long?>(null)

    override val foregroundSyncIntervalMillis: StateFlow<Long?> get() = syncInterval

    override fun observeConversation(conversationId: String) {
        observed += conversationId
    }

    override fun stopObservingConversation(conversationId: String) {
        released += conversationId
    }
}

/**
 * Records the composer events a screen emits, so a test that cares about typing can
 * assert on them and one that does not can ignore them.
 */
internal class RecordingTypingSignals : KitTypingSignals {
    val events = mutableListOf<String>()

    override fun onComposerChanged(conversationId: String, text: String) {
        events += "changed:$conversationId:${text.length}"
    }

    override fun onMessageCommitted(conversationId: String) {
        events += "committed:$conversationId"
    }

    override fun onConversationClosed(conversationId: String) {
        events += "closed:$conversationId"
    }
}
