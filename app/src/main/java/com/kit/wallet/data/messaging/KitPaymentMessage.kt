package com.kit.wallet.data.messaging

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * End-to-end encrypted in-chat payment descriptor carried as the authenticated message text.
 *
 * A payment chat message's Signal-encrypted text is `KITPAY1:` followed by URL-encoded
 * key=value pairs in a fixed order. The referenced payment request is created (and paid)
 * through the authenticated payments API; this descriptor only lets both conversation
 * members render and act on it inside the chat. Amounts are integer minor units, and the
 * server never sees this descriptor in plaintext.
 */
internal data class KitPaymentMessage(
    /** `request` asks the peer to pay; `paid` records a completed payment for a request. */
    val action: String,
    /** Backend payment-request identifier both sides can act on. */
    val paymentRequestId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyScale: Int,
    val note: String?,
) {
    /** Fixed field order keeps encoding deterministic, so retry text equality holds. */
    fun encode(): String = buildString {
        append(PREFIX)
        append("v=1")
        append("&a=").append(action)
        append("&id=").append(paymentRequestId.urlEncode())
        append("&amt=").append(amountMinor)
        append("&cur=").append(currencyCode.urlEncode())
        append("&sc=").append(currencyScale)
        note?.takeIf(String::isNotBlank)?.let { append("&note=").append(it.urlEncode()) }
    }

    val isRequest: Boolean get() = action == ACTION_REQUEST

    companion object {
        const val PREFIX = "KITPAY1:"
        const val ACTION_REQUEST = "request"
        const val ACTION_PAID = "paid"
        private const val MAX_DESCRIPTOR_LENGTH = 1_024
        private const val MAX_NOTE_LENGTH = 140
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
            val action = fields["a"] ?: return null
            val paymentRequestId = fields["id"]?.lowercase() ?: return null
            val amountMinor = fields["amt"]?.toLongOrNull() ?: return null
            val currencyCode = fields["cur"] ?: return null
            val currencyScale = fields["sc"]?.toIntOrNull() ?: return null
            val note = fields["note"]
            if (action !in setOf(ACTION_REQUEST, ACTION_PAID)) return null
            if (!CANONICAL_UUID.matches(paymentRequestId)) return null
            if (amountMinor !in 1..MAX_AMOUNT_MINOR) return null
            if (!CURRENCY_CODE.matches(currencyCode)) return null
            if (currencyScale !in 0..6) return null
            if (note != null && (note.isBlank() || note.length > MAX_NOTE_LENGTH)) return null
            val parsed = KitPaymentMessage(
                action = action,
                paymentRequestId = paymentRequestId,
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                currencyScale = currencyScale,
                note = note,
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
