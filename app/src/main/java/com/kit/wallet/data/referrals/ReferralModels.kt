package com.kit.wallet.data.referrals

/**
 * Domain projection of the referral surface. Everything here is rendered
 * verbatim from the server: amounts are pre-formatted decimal strings, the
 * program terms are the active policy's own numbers, and each referral's
 * status is the server's coarse public status. The client never computes
 * qualification, progress, or payout amounts (docs/support-client.md R1–R4).
 */

enum class ReferralRewardStatus {
    PENDING,
    QUALIFIED,
    PAID,
    EXPIRED,
    NOT_ELIGIBLE,
    REVERSED,

    /** A status this build does not know; rendered from [ReferralEntry.rawStatus] neutrally. */
    UNKNOWN,
}

/** Server-formatted money: display [amount] with [currencyCode], no arithmetic. */
data class ReferralAmount(
    val amount: String,
    val currencyCode: String,
)

data class ReferralProgramTerms(
    val reward: ReferralAmount,
    val qualifyingBalance: ReferralAmount,
    val qualifyingBusinessDays: Int,
    val windowDays: Int,
)

data class ReferralShareCode(
    val code: String,
    /** Canonical share link, copied and shared exactly as the server minted it. */
    val shareUrl: String,
)

data class ReferralEntry(
    val id: String,
    /** Display name of the referred customer — the only detail the server exposes about them. */
    val referredName: String?,
    val status: ReferralRewardStatus,
    /** The server's own status word, for rendering statuses this build doesn't know. */
    val rawStatus: String,
    val reward: ReferralAmount,
    val attributedAt: String,
    val paidAt: String?,
)

data class ReferralTotals(
    val total: Int,
    val pending: Int,
    val qualified: Int,
    val paid: Int,
    val expired: Int,
    val notEligible: Int,
    val reversed: Int,
)

data class ReferralOverview(
    /** Null whenever no referral policy version is currently active. */
    val program: ReferralProgramTerms?,
    /** Null until the account first requests a code. */
    val code: ReferralShareCode?,
    val referrals: List<ReferralEntry>,
    val totals: ReferralTotals,
)
