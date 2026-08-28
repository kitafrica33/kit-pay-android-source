package com.kit.wallet.data.notifications

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * A held-transfer push exactly as the wallet backend signs it off, or nothing at all.
 *
 * The payload also carries its own `deep_link`, which is deliberately never read: a push is the
 * least authenticated input this app accepts, so the only navigation it may cause is the one the
 * client reconstructs from the validated claim id — and even that route re-fetches the claim and
 * re-validates every party before any screen more specific than wallet history opens.
 *
 * Every field here is validated to the shape the backend contract promises. One malformed field —
 * an id that is not a UUID, a tag naming a different claim, an expiry that does not parse —
 * rejects the whole alert rather than salvaging the rest, because a payload that breaks its
 * contract anywhere cannot be trusted to mean anything.
 */
data class PaymentClaimAlert(
    /** One of the exact `wallet.transfer_claim.*` types; nothing else parses. */
    val type: String,
    /** The held transfer, as a canonical lowercase UUID. */
    val claimId: String,
    /** The server's own id for this push, as a canonical lowercase UUID. */
    val notificationId: String,
    /**
     * The server's coalescing tag, required to equal `wallet-transfer-claim:<canonical claim
     * UUID>` exactly. Reused as the platform notification tag so a reminder replaces the original
     * alert for its claim instead of stacking a duplicate.
     */
    val notificationTag: String,
    /** The group conversation this share belongs to, when the backend linked one. */
    val conversationId: String? = null,
    /** The group payment this share hangs off, when the backend linked one. */
    val groupPaymentId: String? = null,
    /** When a pending claim lapses, already verified to parse as ISO-8601. */
    val expiresAtEpochMillis: Long? = null,
    /** Server-composed alert text, delivered data-only from Android 0.2.32 on. */
    val title: String? = null,
    val body: String? = null,
) {

    fun claimLink(): PaymentClaimLink =
        PaymentClaimLink(claimId, conversationId, groupPaymentId)

    companion object {
        const val OPEN_ACTION = "open_transfer_claim"
        const val NOTIFICATION_TAG_PREFIX = "wallet-transfer-claim:"

        /** Group hints ride as Activity extras so the data URI stays the exact claim link. */
        const val EXTRA_CONVERSATION_HINT = "claim_conversation_id"
        const val EXTRA_GROUP_PAYMENT_HINT = "claim_group_payment_id"

        val SUPPORTED_TYPES = setOf(
            "wallet.transfer_claim.opened",
            "wallet.transfer_claim.reminder",
            "wallet.transfer_claim.accepted",
            "wallet.transfer_claim.rejected",
            "wallet.transfer_claim.reversed",
            "wallet.transfer_claim.expired",
        )

        fun fromData(data: Map<String, String>): PaymentClaimAlert? {
            // Contract fields compare exactly, untrimmed — same as the iOS response policy. A
            // payload that pads its own action or tag already failed to be the backend's.
            val type = data["type"]?.takeIf(SUPPORTED_TYPES::contains) ?: return null
            if (data["action"] != OPEN_ACTION) return null
            val claimId = canonicalUuid(data["claim_id"]) ?: return null
            val notificationId = canonicalUuid(data["notification_id"]) ?: return null
            val notificationTag = data["notification_tag"] ?: return null
            if (notificationTag != NOTIFICATION_TAG_PREFIX + claimId) return null
            val conversationId = data["conversation_id"]?.let {
                canonicalUuid(it) ?: return null
            }
            val groupPaymentId = data["group_payment_id"]?.let {
                canonicalUuid(it) ?: return null
            }
            val expiresAtEpochMillis = data["expires_at"]?.let { raw ->
                runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                    ?: return null
            }
            return PaymentClaimAlert(
                type = type,
                claimId = claimId,
                notificationId = notificationId,
                notificationTag = notificationTag,
                conversationId = conversationId,
                groupPaymentId = groupPaymentId,
                expiresAtEpochMillis = expiresAtEpochMillis,
                title = sanitizedAlertText(data["notification_title"], MAX_TITLE_LENGTH),
                body = sanitizedAlertText(data["notification_body"], MAX_BODY_LENGTH),
            )
        }

        /**
         * Mirrors iOS `MessageNotificationContract.canonicalUUID`: the value must parse as a
         * UUID **and** its canonical rendering must equal the full, untrimmed input (case
         * aside). Case variants of the canonical form pass; whitespace padding, short digit
         * groups and every other parser leniency reject — so two different strings can never
         * quietly name the same claim.
         */
        internal fun canonicalUuid(raw: String?): String? {
            if (raw == null) return null
            val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
            return parsed.toString().takeIf { it.equals(raw, ignoreCase = true) }
        }

        private fun sanitizedAlertText(raw: String?, maxLength: Int): String? = raw
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(maxLength)
            ?.takeIf(String::isNotEmpty)

        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_BODY_LENGTH = 300
    }
}

/**
 * The locally reconstructed way into a claim — the only navigation a claim push can cause.
 *
 * The exact form, `kitwallet://payment/claim?claim_id=<uuid>`, is what the notification carries
 * (the same form iOS checks against); group hints travel beside it and reappear as query
 * parameters only on the in-process canonical route. Wherever a hint occurs it must be a
 * canonical UUID — a link with a malformed hint is rejected whole, not partially honored.
 */
data class PaymentClaimLink(
    val claimId: String,
    val conversationId: String? = null,
    val groupPaymentId: String? = null,
) {

    /** The iOS-identical claim link: nothing but the claim id. */
    fun exactDeepLinkUri(): String =
        "kitwallet://payment/claim?claim_id=" + claimId.urlEncode()

    /** The in-process canonical route, carrying any validated group hints along. */
    fun deepLinkUri(): String = buildString {
        append(exactDeepLinkUri())
        conversationId?.let { append("&conversation_id=").append(it.urlEncode()) }
        groupPaymentId?.let { append("&group_payment_id=").append(it.urlEncode()) }
    }

    /**
     * Attaches hints recovered from Activity extras, keeping any the link already carries.
     * Returns null — rejecting the whole link — when a supplied hint is not a canonical UUID
     * or contradicts one the link already names, because the only writer of these extras
     * validated them against this very link and any divergence means tampering.
     */
    fun withExtraHints(conversationId: String?, groupPaymentId: String?): PaymentClaimLink? {
        val extraConversation = conversationId?.let {
            PaymentClaimAlert.canonicalUuid(it) ?: return null
        }
        val extraGroupPayment = groupPaymentId?.let {
            PaymentClaimAlert.canonicalUuid(it) ?: return null
        }
        if (this.conversationId != null && extraConversation != null &&
            extraConversation != this.conversationId
        ) {
            return null
        }
        if (this.groupPaymentId != null && extraGroupPayment != null &&
            extraGroupPayment != this.groupPaymentId
        ) {
            return null
        }
        return copy(
            conversationId = this.conversationId ?: extraConversation,
            groupPaymentId = this.groupPaymentId ?: extraGroupPayment,
        )
    }

    companion object {
        private val QUERY_KEYS = setOf("claim_id", "conversation_id", "group_payment_id")

        /**
         * Parses [raw], accepting only the exact scheme, host and path, a valid claim id, and
         * optionally the two group hints. A claim link names a claim and nothing else, so any
         * user info, port or fragment, any query segment that is not exactly one known
         * `key=value` pair, and any repeated key rejects the link whole. iOS never opens a
         * payload URL at all; this parser must not be the more forgiving of the two.
         */
        fun fromDeepLink(raw: String): PaymentClaimLink? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme != "kitwallet" || uri.host != "payment" || uri.path != "/claim") {
                return null
            }
            if (uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
            val pairs = uri.rawQuery.orEmpty()
                .split('&')
                .map { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return null
                    // Keys match literally, never percent-decoded: this app writes them bare,
                    // so an encoded spelling of a known key is someone else's link.
                    val key = part.substring(0, separator)
                    if (key !in QUERY_KEYS) return null
                    val value = part.substring(separator + 1).urlDecode() ?: return null
                    key to value
                }
            if (pairs.map { it.first }.let { it.size != it.distinct().size }) return null
            val query = pairs.toMap()
            val claimId = PaymentClaimAlert.canonicalUuid(query["claim_id"]) ?: return null
            val conversationId = query["conversation_id"]?.let {
                PaymentClaimAlert.canonicalUuid(it) ?: return null
            }
            val groupPaymentId = query["group_payment_id"]?.let {
                PaymentClaimAlert.canonicalUuid(it) ?: return null
            }
            return PaymentClaimLink(claimId, conversationId, groupPaymentId)
        }
    }
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.urlDecode(): String? =
    runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrNull()
