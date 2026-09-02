package com.kit.wallet.data.messaging

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** What a group-payment descriptor says happened. */
internal enum class KitGroupPaymentAction(val wire: String) {
    /** The announcement the sender posts: who was paid, and how much when that is not private. */
    SENT("sent"),

    /** A member took their own share. */
    ACCEPTED("accepted"),

    /** A member turned their own share down. */
    REJECTED("rejected"),

    /** The sender pulled back whatever nobody had claimed. */
    RETURNED("returned"),
    ;

    companion object {
        fun fromWire(value: String): KitGroupPaymentAction? = entries.firstOrNull { it.wire == value }
    }
}

/** One pot divided across the recipients, or an amount the sender wrote for each member. */
internal enum class GroupPaymentSplitMode(val wire: String) {
    EVEN("even"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromWire(value: String?): GroupPaymentSplitMode? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Everybody in the group at the time of sending, or a chosen few. */
internal enum class GroupPaymentAudience(val wire: String) {
    ALL("all"),
    SELECTED("selected"),
    ;

    companion object {
        fun fromWire(value: String?): GroupPaymentAudience? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * Canonical group-payment descriptor carried inside the end-to-end encrypted message body.
 *
 * One ciphertext reaches every member, so this can only ever carry what the whole group is allowed
 * to see. That is the reason the total is present for an even split and absent for a custom one:
 * with an even split the pot is share × members and hiding it would be theatre, while with a custom
 * split the amounts are between the sender and each recipient. Each member reads their own share
 * from `GET /group-payments/{id}`, never from here.
 *
 * Its fixed field order and strict re-encoding match the iOS `KITGRP1` wire contract byte for byte.
 */
internal data class KitGroupPaymentMessage(
    val action: KitGroupPaymentAction,
    val groupPaymentId: String,
    val splitMode: GroupPaymentSplitMode? = null,
    val audience: GroupPaymentAudience? = null,
    val recipientCount: Int? = null,
    val currencyCode: String? = null,
    val currencyScale: Int? = null,
    /** Present only on a `sent` announcement of an even split. */
    val totalAmountMinor: Long? = null,
    val note: String? = null,
    /**
     * Public ids of the members who were paid, lowercased, in the server's order. Empty when the
     * roster did not fit or the payment went to everybody.
     */
    val recipientUserIds: List<String> = emptyList(),
) {
    /** Fixed field order keeps encoding deterministic, so retry text equality holds. */
    fun encode(): String = buildString {
        append(PREFIX)
        append("v=1")
        append("&a=").append(action.wire)
        append("&id=").append(groupPaymentId.urlEncode())
        splitMode?.let { append("&sp=").append(it.wire) }
        audience?.let { append("&au=").append(it.wire) }
        recipientCount?.let { append("&n=").append(it) }
        currencyCode?.let { append("&cur=").append(it.urlEncode()) }
        currencyScale?.let { append("&sc=").append(it) }
        totalAmountMinor?.let { append("&amt=").append(it) }
        note?.let { append("&note=").append(it.urlEncode()) }
        if (recipientUserIds.isNotEmpty()) {
            append("&rid=").append(recipientUserIds.joinToString(",").urlEncode())
        }
    }

    /** Stable identity for the creator's one announcement of this server-owned payment. */
    fun announcementMessageId(): String {
        require(action == KitGroupPaymentAction.SENT) {
            "Only a sent group payment has an announcement identity"
        }
        return deterministicUuid("kit-group-payment-event-v1|${groupPaymentId.lowercase()}|sent")
    }

    /**
     * What one member gets from an evenly-split pot, before the remainder is dealt. Shown only as
     * "about", because the odd minor unit goes to one member and not the others.
     */
    val evenShareMinor: Long?
        get() {
            if (splitMode != GroupPaymentSplitMode.EVEN) return null
            val total = totalAmountMinor ?: return null
            val count = recipientCount?.takeIf { it > 0 } ?: return null
            return total / count
        }

    /**
     * Whether the announcement in the thread and the payment the server holds agree on every field
     * the announcement states.
     *
     * A descriptor is authenticated, so it proves its author wrote it — not that what it says about
     * money is true. Where the two disagree the card must show a warning rather than buttons: the
     * thread cannot vouch for it, and offering "Take my share" over a claim nobody verified is how
     * a member gets talked into settling something else.
     */
    fun matchesAuthoritativePayment(
        payment: com.kit.wallet.ui.model.GroupPaymentSummary,
    ): Boolean {
        if (payment.id.lowercase() != groupPaymentId) return false
        if (payment.splitMode != splitMode?.wire) return false
        if (payment.audience != audience?.wire) return false
        if (payment.recipientCount != recipientCount) return false
        val code = currencyCode ?: return false
        val scale = currencyScale ?: return false
        if (payment.currencyCode != code || payment.currencyScale != scale) return false
        if (splitMode == GroupPaymentSplitMode.EVEN &&
            payment.totalAmountMinor != totalAmountMinor
        ) {
            return false
        }
        return true
    }

    val dividesEvenly: Boolean
        get() {
            val total = totalAmountMinor ?: return false
            val count = recipientCount?.takeIf { it > 0 } ?: return false
            return total % count == 0L
        }

    companion object {
        const val PREFIX = "KITGRP1:"

        /**
         * Larger than `KITPAY1` because the announcement may name who was paid. A send to more
         * recipients than fit simply omits the roster and the app resolves names from the API.
         */
        private const val MAX_DESCRIPTOR_LENGTH = 4_096
        const val MAX_NOTE_LENGTH = 280
        const val MAX_RECIPIENT_COUNT = 50

        /**
         * Beyond this the roster is dropped from the descriptor rather than truncated: a partial
         * list would read as the whole list, and "sent to Ama and Ben" when six were paid is a lie.
         */
        const val MAX_INLINE_RECIPIENTS = 24
        private const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val CURRENCY_CODE = Regex("^[A-Z]{3}$")

        fun isGroupPaymentText(text: String): Boolean = text.startsWith(PREFIX)

        /**
         * User-authored text cannot enter the group-payment wire namespace, even hidden behind
         * whitespace a composer or a notification reply is about to trim.
         */
        fun beginsWithReservedPrefix(text: String): Boolean =
            text.dropWhile(Char::isWhitespace).startsWith(PREFIX)

        /**
         * Builds a descriptor, or null when the combination is one the wire does not allow.
         *
         * Every rule here exists to stop a descriptor saying more than its author is entitled to
         * say: an outcome carries no amounts at all, and a custom split carries no total.
         */
        fun create(
            action: KitGroupPaymentAction,
            groupPaymentId: String,
            splitMode: GroupPaymentSplitMode? = null,
            audience: GroupPaymentAudience? = null,
            recipientCount: Int? = null,
            currencyCode: String? = null,
            currencyScale: Int? = null,
            totalAmountMinor: Long? = null,
            note: String? = null,
            recipientUserIds: List<String> = emptyList(),
        ): KitGroupPaymentMessage? {
            val id = groupPaymentId.lowercase()
            if (!CANONICAL_UUID.matches(id)) return null
            val normalizedNote = note?.takeIf { it.isNotBlank() }
            val recipients = recipientUserIds.map(String::lowercase)

            when (action) {
                KitGroupPaymentAction.SENT -> {
                    if (splitMode == null || audience == null) return null
                    if (recipientCount == null || recipientCount !in 1..MAX_RECIPIENT_COUNT) return null
                    if (currencyCode == null || !CURRENCY_CODE.matches(currencyCode)) return null
                    if (currencyScale == null || currencyScale !in 0..6) return null
                    if ((normalizedNote?.length ?: 0) > MAX_NOTE_LENGTH) return null
                    if (recipients.size > MAX_INLINE_RECIPIENTS) return null
                    if (!recipients.all(CANONICAL_UUID::matches)) return null
                    if (recipients.toSet().size != recipients.size) return null
                    if (recipients.isNotEmpty() && recipients.size != recipientCount) return null
                    // The total travels with an even split and only with an even split. A custom
                    // split that carried its total would hand every member the sum of amounts they
                    // were never shown, one subtraction away from someone else's share.
                    when (splitMode) {
                        GroupPaymentSplitMode.EVEN ->
                            if (totalAmountMinor == null || totalAmountMinor !in 1..MAX_AMOUNT_MINOR) {
                                return null
                            }
                        GroupPaymentSplitMode.CUSTOM ->
                            if (totalAmountMinor != null || audience != GroupPaymentAudience.SELECTED) {
                                return null
                            }
                    }
                }
                KitGroupPaymentAction.ACCEPTED,
                KitGroupPaymentAction.REJECTED,
                KitGroupPaymentAction.RETURNED,
                -> {
                    // An outcome is who did what, and nothing else: an amount here would republish
                    // a share the group was never told.
                    if (splitMode != null || audience != null || recipientCount != null) return null
                    if (currencyCode != null || currencyScale != null) return null
                    if (totalAmountMinor != null || normalizedNote != null) return null
                    if (recipients.isNotEmpty()) return null
                }
            }

            val descriptor = KitGroupPaymentMessage(
                action = action,
                groupPaymentId = id,
                splitMode = splitMode,
                audience = audience,
                recipientCount = recipientCount,
                currencyCode = currencyCode,
                currencyScale = currencyScale,
                totalAmountMinor = totalAmountMinor,
                note = normalizedNote,
                recipientUserIds = recipients,
            )
            return descriptor.takeIf { it.encode().length <= MAX_DESCRIPTOR_LENGTH }
        }

        /** The announcement for a payment the server has just confirmed. */
        fun announcing(
            payment: com.kit.wallet.ui.model.GroupPaymentSummary,
            recipientUserIds: List<String>,
        ): KitGroupPaymentMessage? {
            val splitMode = GroupPaymentSplitMode.fromWire(payment.splitMode) ?: return null
            val audience = GroupPaymentAudience.fromWire(payment.audience) ?: return null
            val totalMinor = when (splitMode) {
                GroupPaymentSplitMode.EVEN -> payment.totalAmountMinor ?: return null
                GroupPaymentSplitMode.CUSTOM -> null
            }
            // A roster is stated in full or not at all. A partial list reads as the whole list, and
            // "sent to Ama and Ben" when six were paid is a lie the group cannot check.
            val roster = recipientUserIds.takeIf {
                it.size <= MAX_INLINE_RECIPIENTS && it.size == payment.recipientCount
            }.orEmpty()
            return create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = payment.id.lowercase(),
                splitMode = splitMode,
                audience = audience,
                recipientCount = payment.recipientCount,
                currencyCode = payment.currencyCode,
                currencyScale = payment.currencyScale,
                totalAmountMinor = totalMinor,
                note = payment.note,
                recipientUserIds = roster,
            )
        }

        /** An outcome event authored by whoever produced it. */
        fun outcome(action: KitGroupPaymentAction, groupPaymentId: String): KitGroupPaymentMessage? {
            if (action == KitGroupPaymentAction.SENT) return null
            return create(action = action, groupPaymentId = groupPaymentId)
        }

        /**
         * The local message id for an outcome chip this member posts.
         *
         * Derived from the payment, the outcome and the author, so a retry — or the same action
         * taken twice on two devices — converges on one chip instead of announcing "Ama took their
         * share" again. The timeline drops repeats anyway; this stops them being sent at all.
         *
         * The first 16 bytes of the SHA-256 of the same `|`-joined namespace iOS hashes, so both
         * platforms mint the identical id for the identical act.
         */
        fun outcomeMessageId(
            groupPaymentId: String,
            action: KitGroupPaymentAction,
            actorUserId: String,
        ): String {
            val namespace = listOf(
                "group-payment-outcome",
                groupPaymentId.lowercase(),
                action.wire,
                actorUserId.lowercase(),
            ).joinToString("|")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(namespace.toByteArray(StandardCharsets.UTF_8))
            val hex = digest.take(16).joinToString("") { "%02x".format(it) }
            return listOf(
                hex.substring(0, 8),
                hex.substring(8, 12),
                hex.substring(12, 16),
                hex.substring(16, 20),
                hex.substring(20, 32),
            ).joinToString("-")
        }

        /** Strict parse; returns null for anything that is not a well-formed v1 descriptor. */
        fun parse(text: String): KitGroupPaymentMessage? {
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
            val action = fields["a"]?.let(KitGroupPaymentAction::fromWire) ?: return null
            val groupPaymentId = fields["id"]?.lowercase() ?: return null
            val roster = fields["rid"]?.split(",") ?: emptyList()
            val parsed = create(
                action = action,
                groupPaymentId = groupPaymentId,
                splitMode = GroupPaymentSplitMode.fromWire(fields["sp"]),
                audience = GroupPaymentAudience.fromWire(fields["au"]),
                recipientCount = fields["n"]?.toIntOrNull(),
                currencyCode = fields["cur"],
                currencyScale = fields["sc"]?.toIntOrNull(),
                totalAmountMinor = fields["amt"]?.toLongOrNull(),
                note = fields["note"],
                recipientUserIds = roster,
            ) ?: return null
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
