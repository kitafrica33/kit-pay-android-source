package com.kit.wallet.feature.chat

import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    /**
     * Every message drawn in this row, in the order it appears.
     *
     * A row is the unit the list can actually scroll to, so answering "which row holds message X"
     * — what jumping to a quoted message needs — has to go through the grid as well as the bubble.
     */
    val messages: List<Message>

    data class Single(val message: Message) : ConversationRow {
        override val key: String get() = message.id
        override val messages: List<Message> get() = listOf(message)
    }

    data class ImageGroup(override val messages: List<Message>) : ConversationRow {
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
            // In a group two people's photos can arrive back to back, and both are "not from me".
            // A grid carries one author label, so a second author starts a second grid. A direct
            // chat never sets a sender name, which leaves its grouping exactly as it was.
            previous.senderName == message.senderName &&
            pending.size < IMAGE_GROUP_MAX_SIZE &&
            abs(message.sortEpochMillis - previous.sortEpochMillis) <= IMAGE_GROUP_WINDOW_MILLIS
        if (previous != null && !joins) flush()
        pending += message
    }
    flush()
    return rows
}

/**
 * Which bubbles in a group thread carry their sender's name.
 *
 * A name is a heading for a run, not a label on every line: it appears on the first message of a
 * stretch by one member and stays off until somebody else speaks, a card or call interrupts, or
 * the day changes. Outgoing bubbles are never labelled — they are on the other side of the thread
 * and the reader knows who wrote them — but they do end somebody else's run, so the next incoming
 * message gets its heading back.
 *
 * The same rule as iOS `ConversationSenderRunPolicy`, with the date read from each entry's own
 * timestamp because an Android thread draws no date separators to hang it on.
 */
internal fun senderNamedMessageIds(
    messages: List<Message>,
    isGroup: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): Set<String> {
    if (!isGroup) return emptySet()

    val named = mutableSetOf<String>()
    var runSender: String? = null
    var runDay: LocalDate? = null

    for (message in messages) {
        val day = message.sortEpochMillis
            .takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        if (day != null && day != runDay) {
            runSender = null
            runDay = day
        }
        // A full-width card, a call row or a line the conversation speaks itself is a visual
        // break; whoever speaks next is introduced again.
        if (message.kind.interruptsSenderRun) {
            runSender = null
            continue
        }
        if (message.fromMe) {
            runSender = SELF_RUN_SENDER
            continue
        }
        val sender = message.senderUserId?.lowercase()
            ?: message.senderName
            ?: continue
        if (sender != runSender) {
            named += message.id
            runSender = sender
        }
    }
    return named
}

/** Sentinel for "this account is mid-run", which no user ID can collide with. */
private const val SELF_RUN_SENDER = "self"

private val MessageKind.interruptsSenderRun: Boolean
    get() = when (this) {
        MessageKind.TEXT,
        MessageKind.VOICE_NOTE,
        MessageKind.IMAGE,
        MessageKind.VIDEO,
        MessageKind.DOCUMENT,
        MessageKind.MEDIA_ALBUM,
        MessageKind.UNSUPPORTED_ATTACHMENT,
        -> false
        MessageKind.PAYMENT,
        MessageKind.PAYMENT_REQUEST,
        MessageKind.PAYMENT_TRANSFER,
        MessageKind.PAYMENT_EVENT,
        MessageKind.GROUP_PAYMENT,
        MessageKind.GROUP_PAYMENT_EVENT,
        MessageKind.GROUP_PAYMENT_REQUEST,
        MessageKind.GROUP_PAYMENT_REQUEST_EVENT,
        MessageKind.SCHEDULED_PAYMENT_EVENT,
        MessageKind.SCHEDULED_GROUP_PAYMENT_EVENT,
        MessageKind.CALL,
        MessageKind.SYSTEM,
        -> true
    }
