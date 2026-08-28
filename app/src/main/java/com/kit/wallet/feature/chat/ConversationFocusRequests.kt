package com.kit.wallet.feature.chat

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One request to land a conversation on a particular message — the floating voice-note bar's
 * body tap, which opens the owning thread and scrolls to the exact bubble that is speaking.
 *
 * Carried beside navigation rather than inside the route, because the thread may already be the
 * top entry (single-top navigation does not re-deliver arguments to it) and the jump can only
 * happen once that thread has its rows. Only the conversation named here may consume it; any
 * other screen seeing it leaves it alone.
 */
internal data class ConversationFocusRequest(
    val conversationId: String,
    val messageId: String,
    /** Distinguishes repeat requests for the same message, so every tap jumps again. */
    val token: Long,
)

/** Publish-then-consume hand-off of the single most recent focus request. */
internal object ConversationFocusRequests {
    private val tokens = AtomicLong(0L)
    private val mutableCurrent = MutableStateFlow<ConversationFocusRequest?>(null)

    val current: StateFlow<ConversationFocusRequest?> = mutableCurrent.asStateFlow()

    /**
     * Publishes a request, replacing any unconsumed one — the newest tap wins. Blank ids
     * identify nothing, so they publish nothing rather than a request no screen could honor.
     */
    fun request(conversationId: String, messageId: String): Boolean {
        val conversation = conversationId.trim()
        val message = messageId.trim()
        if (conversation.isEmpty() || message.isEmpty()) return false
        mutableCurrent.value =
            ConversationFocusRequest(conversation, message, tokens.incrementAndGet())
        return true
    }

    /** Consumes exactly [request]; a newer request published meanwhile is left for its owner. */
    fun consume(request: ConversationFocusRequest) {
        mutableCurrent.compareAndSet(request, null)
    }

    @VisibleForTesting
    fun reset() {
        mutableCurrent.value = null
    }
}

/** What a conversation screen does with the current focus request, if anything. */
internal sealed interface ConversationFocusAction {
    /** This screen owns the request and the message is at row [rowIndex]: jump and highlight. */
    data class Jump(val rowIndex: Int) : ConversationFocusAction

    /** This screen owns the request but its loaded thread has no such message: consume quietly. */
    data object Drop : ConversationFocusAction

    /** This screen owns the request but has no rows yet: leave it for the load to satisfy. */
    data object Wait : ConversationFocusAction

    /** No request, or someone else's conversation: never touch it. */
    data object Ignore : ConversationFocusAction
}

/**
 * Pure decision for [ConversationFocusRequests.current] as seen by the thread showing
 * [conversationId], whose rows render the message-id groups in [rowMessageIds] (row order).
 *
 * Ownership is an exact conversation-id match — the same comparison the thread's own call-log
 * merge uses — and anything unowned is [ConversationFocusAction.Ignore], so a request can never
 * scroll a conversation it was not aimed at.
 */
internal fun conversationFocusAction(
    request: ConversationFocusRequest?,
    conversationId: String,
    rowMessageIds: List<List<String>>,
): ConversationFocusAction {
    if (request == null) return ConversationFocusAction.Ignore
    if (conversationId.isBlank() || request.conversationId != conversationId) {
        return ConversationFocusAction.Ignore
    }
    if (rowMessageIds.isEmpty()) return ConversationFocusAction.Wait
    val rowIndex = rowMessageIds.indexOfFirst { ids -> request.messageId in ids }
    return if (rowIndex >= 0) {
        ConversationFocusAction.Jump(rowIndex)
    } else {
        ConversationFocusAction.Drop
    }
}
