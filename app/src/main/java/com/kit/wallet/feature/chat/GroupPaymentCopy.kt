package com.kit.wallet.feature.chat

import com.kit.wallet.data.messaging.GroupPaymentAudience
import com.kit.wallet.data.messaging.GroupPaymentSplitMode
import com.kit.wallet.data.messaging.KitGroupPaymentAction
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.ui.model.GroupPaymentShareStatus
import com.kit.wallet.ui.model.GroupPaymentSummary
import com.kit.wallet.ui.model.Money

/**
 * Turning a group payment into the one or two lines a member actually reads in the chat.
 *
 * Two rules run through all of it. Nobody is told an amount the server did not disclose to them,
 * so a custom split announces itself without a figure and the recipient learns their own share
 * from the card. And nothing here claims more than it can know: an outcome line describes the
 * action of the member who authored it and no one else's.
 *
 * Word for word the same copy as iOS `GroupPaymentCopy`, so a group with members on both platforms
 * reads one conversation rather than two.
 */
internal object GroupPaymentCopy {
    /** How many names to spell out before falling back to counting the rest. */
    const val MAX_NAMED_RECIPIENTS = 3

    /** "Ama", "Ama and Ben", "Ama, Ben and Cara", "Ama, Ben and 4 others". */
    fun nameList(names: List<String>, totalCount: Int? = null): String? {
        val named = names.map(String::trim).filter(String::isNotEmpty)
        if (named.isEmpty()) return null
        val total = maxOf(totalCount ?: named.size, named.size)

        if (named.size >= total && total <= MAX_NAMED_RECIPIENTS) {
            return when (named.size) {
                1 -> named[0]
                2 -> "${named[0]} and ${named[1]}"
                else -> named.dropLast(1).joinToString(", ") + " and " + named.last()
            }
        }

        val shown = named.take(MAX_NAMED_RECIPIENTS)
        val remaining = total - shown.size
        if (remaining <= 0) return shown.joinToString(", ")
        val others = if (remaining == 1) "1 other" else "$remaining others"
        return shown.joinToString(", ") + " and " + others
    }

    /**
     * The announcement line: who paid whom, and how much when the group is allowed to know.
     *
     * [totalOverride] is a total the server disclosed to *this* viewer that the descriptor is not
     * allowed to carry — in practice the sender's own view of a custom split.
     */
    fun announcement(
        descriptor: KitGroupPaymentMessage,
        senderName: String,
        isViewerSender: Boolean,
        recipientNames: List<String>,
        totalOverride: Long? = null,
    ): String {
        val who = if (isViewerSender) "You" else senderName
        val amount = disclosedTotal(descriptor, totalOverride)

        val audience = when (descriptor.audience) {
            GroupPaymentAudience.ALL -> "everyone"
            GroupPaymentAudience.SELECTED, null ->
                nameList(recipientNames, descriptor.recipientCount)
                    ?: memberCount(descriptor.recipientCount)
        }

        // A custom split with no figure to show. "Payments", plural and unquantified, is the most
        // this viewer is allowed to be told.
        if (amount == null) return "$who sent payments to $audience"
        return "$who sent $amount to $audience"
    }

    /**
     * The per-member line under the announcement of an even split, so a recipient can see what is
     * coming to them before the card has loaded. Absent when the pot was never disclosed.
     */
    fun evenShareSubtitle(descriptor: KitGroupPaymentMessage): String? {
        if (descriptor.splitMode != GroupPaymentSplitMode.EVEN) return null
        val shareMinor = descriptor.evenShareMinor ?: return null
        val code = descriptor.currencyCode ?: return null
        val scale = descriptor.currencyScale ?: return null
        val count = descriptor.recipientCount ?: return null
        if (count <= 1) return null
        val share = Money.format(shareMinor, code, scale)
        // The odd minor unit has to land somewhere, so an inexact division is described as "about"
        // rather than quoting a figure one member will not receive.
        return if (descriptor.dividesEvenly) "$share each" else "About $share each"
    }

    /**
     * The small centred line an outcome posts into the thread. Deliberately only ever about the
     * member who authored it.
     */
    fun outcome(
        action: KitGroupPaymentAction,
        actorName: String,
        isViewerActor: Boolean,
    ): String? {
        val who = if (isViewerActor) "You" else actorName
        return when (action) {
            KitGroupPaymentAction.ACCEPTED ->
                if (isViewerActor) "You took your share" else "$who took their share"
            KitGroupPaymentAction.REJECTED ->
                if (isViewerActor) "You declined your share" else "$who declined their share"
            KitGroupPaymentAction.RETURNED ->
                if (isViewerActor) {
                    "You returned the unclaimed shares"
                } else {
                    "$who returned the unclaimed shares"
                }
            KitGroupPaymentAction.SENT -> null
        }
    }

    /**
     * Progress for the sender: counts only, so it means the same thing to a member who was never
     * shown the amounts.
     */
    fun progress(payment: GroupPaymentSummary): String {
        val total = maxOf(payment.recipientCount, payment.resolvedCount)
        if (total <= 0) return "No shares"
        if (payment.pendingCount == 0) {
            return if (payment.returnedCount == 0) {
                "All $total shares taken"
            } else {
                "${payment.acceptedCount} of $total taken, ${payment.returnedCount} returned"
            }
        }
        return "${payment.acceptedCount} of $total taken, ${payment.pendingCount} waiting"
    }

    /** What a recipient's own card says about where their share stands. */
    fun shareStatus(status: GroupPaymentShareStatus): String = when (status) {
        GroupPaymentShareStatus.PENDING -> "Waiting for you"
        GroupPaymentShareStatus.ACCEPTED -> "In your wallet"
        GroupPaymentShareStatus.REJECTED -> "You declined this"
        GroupPaymentShareStatus.REVERSED -> "Returned to the sender"
        GroupPaymentShareStatus.EXPIRED -> "Expired and returned"
    }

    /** Where a recipient's line in the sender's list has got to. */
    fun recipientStatus(status: GroupPaymentShareStatus): String = when (status) {
        GroupPaymentShareStatus.PENDING -> "Waiting"
        GroupPaymentShareStatus.ACCEPTED -> "Taken"
        GroupPaymentShareStatus.REJECTED -> "Declined"
        GroupPaymentShareStatus.REVERSED -> "Returned"
        GroupPaymentShareStatus.EXPIRED -> "Expired"
    }

    /**
     * Said on the card itself, because it is the whole reason there is no accept button in the
     * transfers inbox for this money.
     */
    const val GROUP_ONLY_CLAIM_NOTE = "Money sent to a group is claimed here, in the group."

    private fun disclosedTotal(
        descriptor: KitGroupPaymentMessage,
        totalOverride: Long?,
    ): String? {
        val code = descriptor.currencyCode ?: return null
        val scale = descriptor.currencyScale ?: return null
        val minor = totalOverride ?: descriptor.totalAmountMinor ?: return null
        return Money.format(minor, code, scale)
    }

    private fun memberCount(count: Int?): String {
        if (count == null || count <= 0) return "the group"
        return if (count == 1) "1 member" else "$count members"
    }
}
