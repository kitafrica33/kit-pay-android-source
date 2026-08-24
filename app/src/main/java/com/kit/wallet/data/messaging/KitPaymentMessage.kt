package com.kit.wallet.data.messaging

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * What a payment descriptor says happened.
 *
 * Modelled as an enum rather than a string so that every `when` over it is exhaustive: adding a
 * future action turns the places that must handle it into compiler errors instead of silently
 * rendering a blank bubble.
 */
internal enum class KitPaymentAction(val wire: String) {
    /** Asks the peer to pay a payment request. */
    REQUEST("request"),

    /** Records a completed payment against a payment request. */
    PAID("paid"),

    /** The peer turned the request down. */
    DECLINED("declined"),

    /** The requester withdrew their own request. */
    CANCELLED("cancelled"),

    /** A Kit → Kit transfer waiting for the recipient to accept or reject it. */
    TRANSFER("transfer"),

    /** A Kit → Kit transfer that settled on the spot, with nothing to accept. */
    SENT("sent"),

    /** The recipient took a held transfer. From here it is final. */
    ACCEPTED("accepted"),

    /** The recipient turned a held transfer down and the money went back. */
    REJECTED("rejected"),

    /** The sender took a held transfer back before it was accepted. */
    REVERSED("reversed"),

    /** Nobody acted before the claim window closed, so the money went back on its own. */
    EXPIRED("expired"),
    ;

    /** True when this action refers to a transfer claim rather than a payment request. */
    val isTransferEvent: Boolean
        get() = this != REQUEST && this != PAID && this != DECLINED && this != CANCELLED

    /** True when the money ended up back with the sender. */
    val returnedFunds: Boolean
        get() = this == REJECTED || this == REVERSED || this == EXPIRED

    /**
     * True when seeing this action means balances have already changed, so the wallet is stale.
     * Asking for money and turning that ask down move nothing; everything else does.
     */
    val movesMoney: Boolean
        get() = when (this) {
            REQUEST, DECLINED, CANCELLED -> false
            PAID, TRANSFER, SENT, ACCEPTED, REJECTED, REVERSED, EXPIRED -> true
        }

    companion object {
        fun fromWire(value: String): KitPaymentAction? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * End-to-end encrypted in-chat payment descriptor carried as the authenticated message text.
 *
 * A payment chat message's Signal-encrypted text is `KITPAY1:` followed by URL-encoded
 * key=value pairs in a fixed order. The referenced payment request or transfer is created (and
 * settled) through the authenticated payments API; this descriptor only lets both conversation
 * members render and act on it inside the chat. Amounts are integer minor units, and the
 * server never sees this descriptor in plaintext.
 */
internal data class KitPaymentMessage(
    val action: KitPaymentAction,
    /**
     * Backend identifier both sides can act on: a payment-request id for request actions, a
     * transfer-claim id for transfer actions.
     */
    val referenceId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyScale: Int,
    val note: String?,
    /**
     * Why a payment came back, in the words of whoever sent it back. Carried in the descriptor so
     * the conversation records the reason even when the wallet API is unreachable.
     */
    val reason: String? = null,
) {
    /** Fixed field order keeps encoding deterministic, so retry text equality holds. */
    fun encode(): String = buildString {
        append(PREFIX)
        append("v=1")
        append("&a=").append(action.wire)
        append("&id=").append(referenceId.urlEncode())
        append("&amt=").append(amountMinor)
        append("&cur=").append(currencyCode.urlEncode())
        append("&sc=").append(currencyScale)
        note?.takeIf(String::isNotBlank)?.let { append("&note=").append(it.urlEncode()) }
        reason?.takeIf(String::isNotBlank)?.let { append("&rsn=").append(it.urlEncode()) }
    }

    val isRequest: Boolean get() = action == KitPaymentAction.REQUEST

    companion object {
        const val PREFIX = "KITPAY1:"
        private const val MAX_DESCRIPTOR_LENGTH = 1_024
        const val MAX_NOTE_LENGTH = 140
        const val MAX_REASON_LENGTH = 140
        private const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val CURRENCY_CODE = Regex("^[A-Z]{3}$")

        fun isPaymentText(text: String): Boolean = text.startsWith(PREFIX)

        /** Strict parse; returns null for anything that is not a well-formed v1 payment descriptor. */
        fun parse(text: String): KitPaymentMessage? {
            if (!text.startsWith(PREFIX) || text.length > MAX_DESCRIPTOR_LENGTH) return null
            val fields = mutableMapOf<String, String>()
            for (pair in text.substring(PREFIX.length).split('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) return null
                val key = pair.substring(0, separator)
                val value = pair.substring(separator + 1).urlDecode() ?: return null
                if (fields.put(key, value) != null) return null
            }
            if (fields["v"] != "1") return null
            val action = fields["a"]?.let(KitPaymentAction::fromWire) ?: return null
            val referenceId = fields["id"]?.lowercase() ?: return null
            val amountMinor = fields["amt"]?.toLongOrNull() ?: return null
            val currencyCode = fields["cur"] ?: return null
            val currencyScale = fields["sc"]?.toIntOrNull() ?: return null
            val note = fields["note"]
            val reason = fields["rsn"]
            if (!CANONICAL_UUID.matches(referenceId)) return null
            if (amountMinor !in 1..MAX_AMOUNT_MINOR) return null
            if (!CURRENCY_CODE.matches(currencyCode)) return null
            if (currencyScale !in 0..6) return null
            if (note != null && (note.isBlank() || note.length > MAX_NOTE_LENGTH)) return null
            if (reason != null && (reason.isBlank() || reason.length > MAX_REASON_LENGTH)) return null
            val parsed = KitPaymentMessage(
                action = action,
                referenceId = referenceId,
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                currencyScale = currencyScale,
                note = note,
                reason = reason,
            )
            // The authenticated descriptor has one canonical representation. Reject unknown or
            // reordered fields, alternate escaping and noncanonical numbers so a future parser
            // cannot assign a second meaning to already-authenticated content.
            return parsed.takeIf { it.encode() == text }
        }

        private fun String.urlEncode(): String =
            URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun String.urlDecode(): String? =
            runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrNull()
    }
}
