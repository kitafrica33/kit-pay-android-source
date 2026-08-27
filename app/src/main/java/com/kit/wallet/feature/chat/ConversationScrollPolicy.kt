package com.kit.wallet.feature.chat

import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind

/** What a changed conversation projection is allowed to do to the reader's viewport. */
internal enum class ConversationScrollAction {
    /** First non-empty projection for this conversation: open at its newest row. */
    JUMP_TO_NEWEST,

    /** The reader is already at the foot, or just sent an ordinary message: follow it smoothly. */
    FOLLOW_NEWEST,

    /** Keep the visible keyed row and its offset exactly where they are. */
    KEEP_POSITION,
}

internal data class ConversationScrollDecision(
    val action: ConversationScrollAction,
    val unseenMessages: Int = 0,
)

/**
 * Whether a payment-card hydration may restore the bottom anchor after its height changes.
 *
 * Hydration is not a new message, so it gets no unread count. It may only correct layout for a
 * reader who was already positioned at the newest rows and is not actively dragging the list.
 */
internal fun shouldRepinAfterGroupPaymentHydration(
    conversationPositioned: Boolean,
    nearBottom: Boolean,
    scrollInProgress: Boolean,
): Boolean = conversationPositioned && nearBottom && !scrollInProgress

/**
 * Decides how a new immutable message projection affects the list.
 *
 * Group-payment rows can arrive only after a server payment and an encrypted announcement have
 * both settled. That delayed insertion must never pull somebody away from older messages they are
 * reading merely because this account authored the payment. It follows naturally when the reader
 * is already at the bottom, and otherwise leaves a new-message affordance like an incoming row.
 */
internal fun conversationScrollDecision(
    previousMessageIds: Set<String>?,
    messages: List<Message>,
    nearBottom: Boolean,
): ConversationScrollDecision {
    if (messages.isEmpty()) return ConversationScrollDecision(ConversationScrollAction.KEEP_POSITION)
    if (previousMessageIds == null) {
        return ConversationScrollDecision(ConversationScrollAction.JUMP_TO_NEWEST)
    }

    val added = messages.filterNot { it.id in previousMessageIds }
    if (added.isEmpty()) {
        return ConversationScrollDecision(ConversationScrollAction.KEEP_POSITION)
    }
    if (nearBottom) {
        return ConversationScrollDecision(ConversationScrollAction.FOLLOW_NEWEST)
    }

    val newest = messages.last()
    val newestWasAdded = newest.id !in previousMessageIds
    val ordinaryOutgoingMessage = newestWasAdded && newest.fromMe && !newest.kind.isGroupPaymentRow
    return if (ordinaryOutgoingMessage) {
        ConversationScrollDecision(ConversationScrollAction.FOLLOW_NEWEST)
    } else {
        ConversationScrollDecision(
            action = ConversationScrollAction.KEEP_POSITION,
            unseenMessages = added.size,
        )
    }
}

private val MessageKind.isGroupPaymentRow: Boolean
    get() = this == MessageKind.GROUP_PAYMENT || this == MessageKind.GROUP_PAYMENT_EVENT
