package com.kit.wallet.data.notifications

import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotification

/** Private notification copy derived only from locally authenticated secure-message state. */
internal data class SecureMessageNotificationPresentation(
    val sender: String,
    val preview: String,
)

/**
 * Converts authenticated plaintext into bounded notification copy.
 *
 * Opaque media/payment descriptors can contain attachment key material or internal identifiers,
 * so they are always rendered as human-readable summaries and are never copied verbatim into an
 * Android notification. Plain text is normalized to one line and bounded by Unicode code points.
 */
internal object SecureMessageNotificationPresentationFactory {
    fun create(
        notification: SecureMessagingIncomingNotification,
    ): SecureMessageNotificationPresentation = SecureMessageNotificationPresentation(
        sender = notification.senderName
            ?.normalizeNotificationText()
            ?.takeUnless(CANONICAL_UUID::matches)
            ?.truncateNotificationText(MAX_SENDER_CODE_POINTS)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_SENDER,
        preview = notification.authenticatedText.toNotificationPreview(),
    )

    private fun String.toNotificationPreview(): String {
        val media = KitMediaMessage.parse(this)
        if (media != null) {
            return listOfNotNull(
                PHOTO_LABEL,
                media.caption?.normalizeNotificationText()?.takeIf(String::isNotBlank),
            ).joinToString(DETAIL_SEPARATOR).truncateNotificationText(MAX_PREVIEW_CODE_POINTS)
        }
        // Fail closed if a future/malformed secure-media descriptor cannot be parsed. In
        // particular, never surface its embedded attachment key material in the notification.
        if (KitMediaMessage.isMediaText(this)) return PHOTO_LABEL

        val payment = KitPaymentMessage.parse(this)
        if (payment != null) {
            val label = if (payment.isRequest) PAYMENT_REQUEST_LABEL else PAYMENT_LABEL
            return listOfNotNull(
                label,
                payment.amountForNotification(),
                payment.note?.normalizeNotificationText()?.takeIf(String::isNotBlank),
            ).joinToString(DETAIL_SEPARATOR).truncateNotificationText(MAX_PREVIEW_CODE_POINTS)
        }
        // The same fail-closed rule keeps internal payment-request IDs out of the system UI.
        if (KitPaymentMessage.isPaymentText(this)) return PAYMENT_LABEL

        return normalizeNotificationText()
            .takeIf(String::isNotBlank)
            ?.truncateNotificationText(MAX_PREVIEW_CODE_POINTS)
            ?: EMPTY_MESSAGE_LABEL
    }

    private fun KitPaymentMessage.amountForNotification(): String {
        val digits = amountMinor.toString().padStart(currencyScale + 1, '0')
        val wholeEnd = digits.length - currencyScale
        val whole = digits.substring(0, wholeEnd)
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
        val fraction = if (currencyScale == 0) "" else ".${digits.substring(wholeEnd)}"
        return "$currencyCode $whole$fraction"
    }

    private fun String.normalizeNotificationText(): String {
        val normalized = StringBuilder(length)
        var pendingSeparator = false
        codePoints().forEachOrdered { codePoint ->
            if (
                Character.isWhitespace(codePoint) ||
                Character.isSpaceChar(codePoint) ||
                Character.isISOControl(codePoint) ||
                codePoint in BIDI_CONTROL_CODE_POINTS
            ) {
                pendingSeparator = normalized.isNotEmpty()
            } else {
                if (pendingSeparator) normalized.append(' ')
                normalized.appendCodePoint(codePoint)
                pendingSeparator = false
            }
        }
        return normalized.toString()
    }

    private fun String.truncateNotificationText(maxCodePoints: Int): String {
        if (codePointCount(0, length) <= maxCodePoints) return this
        val end = offsetByCodePoints(0, maxCodePoints - 1)
        return substring(0, end).trimEnd() + ELLIPSIS
    }

    private const val MAX_SENDER_CODE_POINTS = 64
    private const val MAX_PREVIEW_CODE_POINTS = 96
    private const val DEFAULT_SENDER = "Kit Pay contact"
    private const val EMPTY_MESSAGE_LABEL = "New secure message"
    private const val PHOTO_LABEL = "📷 Photo"
    private const val PAYMENT_REQUEST_LABEL = "💰 Payment request"
    private const val PAYMENT_LABEL = "💸 Payment"
    private const val DETAIL_SEPARATOR = " · "
    private const val ELLIPSIS = "…"
    private val CANONICAL_UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )
    private val BIDI_CONTROL_CODE_POINTS = setOf(
        0x061C,
        0x200E,
        0x200F,
        0x202A,
        0x202B,
        0x202C,
        0x202D,
        0x202E,
        0x2066,
        0x2067,
        0x2068,
        0x2069,
    )
}
