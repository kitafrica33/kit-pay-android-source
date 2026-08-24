package com.kit.wallet.feature.chat

import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import kotlin.math.abs

/**
 * Presentation-layer grouping of the conversation timeline: consecutive caption-less photos
 * from the same sender within a short window render as one media grid (like every modern
 * messenger) instead of a long vertical list. Grouping never mutates or reorders the
 * underlying messages — each grid tile still maps to its own message for the gallery,
 * receipts and retries.
 */
internal sealed interface ConversationRow {
    val key: String

    data class Single(val message: Message) : ConversationRow {
        override val key: String get() = message.id
    }

    data class ImageGroup(val messages: List<Message>) : ConversationRow {
        override val key: String get() = "group:${messages.first().id}"
    }
}

internal const val IMAGE_GROUP_WINDOW_MILLIS = 90_000L
internal const val IMAGE_GROUP_MAX_SIZE = 12

internal fun groupConversationRows(messages: List<Message>): List<ConversationRow> {
    val rows = mutableListOf<ConversationRow>()
    var pending = mutableListOf<Message>()

    fun flush() {
        when {
            pending.isEmpty() -> Unit
            pending.size == 1 -> rows += ConversationRow.Single(pending.single())
            else -> rows += ConversationRow.ImageGroup(pending.toList())
        }
        pending = mutableListOf()
    }

    for (message in messages) {
        val groupable = message.kind == MessageKind.IMAGE &&
            // A caption is part of one specific photo; captioned photos stay full-size.
            (message.text.isBlank() || message.text == "Photo" || message.text == "📷 Photo")
        if (!groupable) {
            flush()
            rows += ConversationRow.Single(message)
            continue
        }
        val previous = pending.lastOrNull()
        val joins = previous != null &&
            previous.fromMe == message.fromMe &&
            pending.size < IMAGE_GROUP_MAX_SIZE &&
            abs(message.sortEpochMillis - previous.sortEpochMillis) <= IMAGE_GROUP_WINDOW_MILLIS
        if (previous != null && !joins) flush()
        pending += message
    }
    flush()
    return rows
}
