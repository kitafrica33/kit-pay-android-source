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

private enum class PaymentOriginKind { REQUEST, TRANSFER }

private data class PaymentOutcomeKey(
    val reference: String,
    val origin: PaymentOriginKind,
    val originFromMe: Boolean,
)

/**
 * Indexes the last terminal receipt each concrete request or transfer card is allowed to trust.
 *
 * Signal authentication proves who wrote a message, not that its claim about a payment is true.
 * Direction therefore binds each receipt to the role allowed to produce it: the other party may
 * pay/decline a request or accept/reject a transfer; the original sender may cancel, reverse or
 * document server expiry. The result is keyed by opener message ID (not reference alone), so a
 * forged duplicate opener cannot change another card's direction binding.
 */
internal fun paymentOutcomes(messages: List<Message>): Map<String, PaymentOutcome> {
    val latestByRole = HashMap<PaymentOutcomeKey, PaymentOutcome>()
    for (message in messages) {
        val event = message.paymentEvent ?: continue
        if (!event.isTerminal) continue
        val reference = message.paymentReferenceId?.lowercase()?.takeIf(String::isNotBlank)
            ?: continue
        val key = when (event) {
            PaymentEventKind.PAID, PaymentEventKind.DECLINED -> PaymentOutcomeKey(
                reference,
                PaymentOriginKind.REQUEST,
                originFromMe = !message.fromMe,
            )
            PaymentEventKind.CANCELLED -> PaymentOutcomeKey(
                reference,
                PaymentOriginKind.REQUEST,
                originFromMe = message.fromMe,
            )
            PaymentEventKind.ACCEPTED, PaymentEventKind.REJECTED -> PaymentOutcomeKey(
                reference,
                PaymentOriginKind.TRANSFER,
                originFromMe = !message.fromMe,
            )
            PaymentEventKind.REVERSED, PaymentEventKind.EXPIRED -> PaymentOutcomeKey(
                reference,
                PaymentOriginKind.TRANSFER,
                originFromMe = message.fromMe,
            )
            else -> continue
        }
        latestByRole[key] = PaymentOutcome(event, message.paymentReason)
    }

    return buildMap {
        for (subject in messages) {
            val origin = when (subject.paymentEvent) {
                PaymentEventKind.REQUESTED -> PaymentOriginKind.REQUEST
                PaymentEventKind.TRANSFER -> PaymentOriginKind.TRANSFER
                else -> continue
            }
            val reference = subject.paymentReferenceId?.lowercase()?.takeIf(String::isNotBlank)
                ?: continue
            latestByRole[PaymentOutcomeKey(reference, origin, subject.fromMe)]?.let { outcome ->
                put(subject.id, outcome)
            }
        }
    }
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
