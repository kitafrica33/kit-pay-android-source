package com.kit.wallet.ui.model

/**
 * Where one member's share of a group payment has got to. Deliberately the same vocabulary as a
 * one-to-one held transfer, because underneath it is one.
 */
enum class GroupPaymentShareStatus(val wire: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    REVERSED("reversed"),
    EXPIRED("expired"),
    ;

    val isSettled: Boolean get() = this != PENDING

    /** Whether the money went back to the sender rather than staying with the member. */
    val returnedFunds: Boolean get() = this == REJECTED || this == REVERSED || this == EXPIRED

    companion object {
        fun fromWire(value: String?): GroupPaymentShareStatus? =
            entries.firstOrNull { it.wire == value }
    }
}

/** What a group-payment timeline entry records. Mirrors the encrypted descriptor's action. */
enum class GroupPaymentEventKind { ANNOUNCED, ACCEPTED, REJECTED, RETURNED }

/** One recipient's line in the sender's list. */
data class GroupPaymentRecipient(
    val userId: String?,
    val name: String?,
    val status: GroupPaymentShareStatus,
    /** Withheld unless the viewer is the sender, the split was even, or this is the viewer's own. */
    val amountMinor: Long? = null,
)

/** This account's own share, and what it may do about it. */
data class GroupPaymentShare(
    val amountMinor: Long,
    val status: GroupPaymentShareStatus,
    val claimId: String? = null,
    val canAccept: Boolean = false,
    val canReject: Boolean = false,
)

/**
 * One group payment as the backend renders it *for the caller*.
 *
 * The scoping is the server's, not the app's: a recipient of an unevenly-split payment is sent no
 * total and no amount for anybody but themselves. The app never has the other members' amounts to
 * leak in the first place.
 */
data class GroupPaymentSummary(
    val id: String,
    val conversationId: String? = null,
    val splitMode: String,
    val audience: String,
    val currencyCode: String = "UGX",
    val currencyScale: Int = Money.SCALE,
    val recipientCount: Int = 0,
    /** Absent for a recipient of a custom split: the size of everybody else's share is not theirs. */
    val totalAmountMinor: Long? = null,
    val note: String? = null,
    val senderUserId: String? = null,
    val senderName: String? = null,
    val settled: Boolean = false,
    val pendingCount: Int = 0,
    val acceptedCount: Int = 0,
    val returnedCount: Int = 0,
    val yourShare: GroupPaymentShare? = null,
    val canReverseUnclaimed: Boolean = false,
    val recipients: List<GroupPaymentRecipient> = emptyList(),
    val expiresAtEpochMillis: Long = 0,
) {
    /**
     * Every recipient's share as a fraction of what has been decided, for the sender's progress
     * line. Counts, not amounts, so it means the same thing to a member who cannot see the pot.
     */
    val resolvedCount: Int get() = acceptedCount + returnedCount
}
