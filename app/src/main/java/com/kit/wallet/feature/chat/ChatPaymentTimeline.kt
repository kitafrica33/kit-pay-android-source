package com.kit.wallet.feature.chat

import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.PaymentEventKind
import kotlin.math.abs

/**
 * How a conversation reads its own payment history.
 *
 * The wallet API is the authority on a held transfer's state, but it is not always reachable and
 * it does not page back forever. The conversation itself carries a durable second record: the
 * settlement events both sides post. Folding those events gives every payment card a state to
 * render offline, and gives a settled card a reason to show.
 */

/** True when this event ends a payment's life — nothing further can happen to it. */
internal val PaymentEventKind.isTerminal: Boolean
    get() = when (this) {
        PaymentEventKind.REQUESTED, PaymentEventKind.TRANSFER -> false
        // A transfer that settled on the spot is already over the moment it appears.
        PaymentEventKind.SENT -> true
        PaymentEventKind.PAID,
        PaymentEventKind.DECLINED,
        PaymentEventKind.CANCELLED,
        PaymentEventKind.ACCEPTED,
        PaymentEventKind.REJECTED,
        PaymentEventKind.REVERSED,
        PaymentEventKind.EXPIRED,
        -> true
    }

/** The reason recorded against a payment, and the event that carried it. */
internal data class PaymentOutcome(
    val event: PaymentEventKind,
    val reason: String?,
)

/**
 * Folds a conversation's payment messages into the last outcome recorded per reference.
 *
 * Last-wins rather than first-wins: the authenticated ordering is the conversation's own, and a
 * later event is by definition the more recent word on the same money. References are lowercased
 * because a descriptor's id is canonicalised on parse but a card may be keyed from elsewhere.
 */
internal fun paymentOutcomes(messages: List<Message>): Map<String, PaymentOutcome> {
    val outcomes = LinkedHashMap<String, PaymentOutcome>()
    for (message in messages) {
        val event = message.paymentEvent ?: continue
        if (!event.isTerminal) continue
        val reference = message.paymentReferenceId?.lowercase()?.takeIf(String::isNotBlank)
            ?: continue
        outcomes[reference] = PaymentOutcome(event, message.paymentReason)
    }
    return outcomes
}

/**
 * The line a settled payment leaves in the conversation.
 *
 * Money that went back always says why, when a reason was given — that is the whole point of the
 * line. [peerName] is the other side of a direct conversation; it falls back to a neutral phrasing
 * so a missing name can never read as the wrong person having acted.
 */
internal fun paymentEventSummary(message: Message, peerName: String?): String {
    val amount = Money.format(
        abs(message.amountMinor),
        message.paymentCurrencyCode,
        message.paymentCurrencyScale,
    )
    val actor = when {
        message.fromMe -> "You"
        else -> message.senderName?.takeIf(String::isNotBlank)
            ?: peerName?.takeIf(String::isNotBlank)
            ?: "They"
    }
    val headline = when (message.paymentEvent) {
        PaymentEventKind.ACCEPTED -> "$actor accepted $amount"
        PaymentEventKind.REJECTED -> "$actor returned $amount"
        PaymentEventKind.REVERSED -> "$actor reversed $amount"
        PaymentEventKind.EXPIRED -> "$amount was returned — not accepted in time"
        PaymentEventKind.DECLINED -> "$actor declined a request for $amount"
        PaymentEventKind.CANCELLED -> "$actor cancelled a request for $amount"
        // These are cards, not lines, and never reach this function. Say something true rather
        // than nothing if a future descriptor ever routes one here.
        PaymentEventKind.REQUESTED,
        PaymentEventKind.PAID,
        PaymentEventKind.TRANSFER,
        PaymentEventKind.SENT,
        null,
        -> "Payment of $amount"
    }
    val reason = message.paymentReason?.trim()?.takeIf(String::isNotBlank)
    return if (reason == null) headline else "$headline · $reason"
}
