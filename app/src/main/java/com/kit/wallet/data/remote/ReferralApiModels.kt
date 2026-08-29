package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ---------------------------------------------------------------------------
// Referral contract DTOs — bound 1:1 to the backend referral OpenAPI
// (`/referrals`, `/referrals/code`). Amounts are server-formatted decimal
// strings and are rendered verbatim; the client performs no arithmetic and
// derives no qualification or payout state (docs/support-client.md R1–R4).
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = false)
data class ReferralMoneyDto(
    val amount: String,
    val currency: ReferralCurrencyDto,
)

@JsonClass(generateAdapter = false)
data class ReferralCurrencyDto(
    val code: String,
    /** Minor-unit scale the amount was expanded with, serialized as a string. */
    val scale: String,
)

@JsonClass(generateAdapter = false)
data class ReferralProgramTermsDto(
    val reward: ReferralMoneyDto,
    @Json(name = "qualifying_balance") val qualifyingBalance: ReferralMoneyDto,
    @Json(name = "qualifying_business_days") val qualifyingBusinessDays: Int,
    @Json(name = "window_days") val windowDays: Int,
)

@JsonClass(generateAdapter = false)
data class ReferralShareCodeDto(
    val code: String,
    @Json(name = "share_url") val shareUrl: String,
)

@JsonClass(generateAdapter = false)
data class ReferralListItemDto(
    val id: String,
    @Json(name = "referred_name") val referredName: String? = null,
    val status: String,
    val reward: ReferralMoneyDto,
    @Json(name = "attributed_at") val attributedAt: String,
    @Json(name = "paid_at") val paidAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class ReferralTotalsDto(
    val total: Int,
    val pending: Int,
    val qualified: Int,
    val paid: Int,
    val expired: Int,
    @Json(name = "not_eligible") val notEligible: Int,
    val reversed: Int,
)

@JsonClass(generateAdapter = false)
data class ReferralOverviewDto(
    /** Null whenever no policy version is currently active. */
    val program: ReferralProgramTermsDto?,
    /** Null until the caller first requests a code. */
    val code: ReferralShareCodeDto?,
    val referrals: List<ReferralListItemDto>,
    val totals: ReferralTotalsDto,
)

@JsonClass(generateAdapter = false)
data class ReferralCodeResultDto(
    val code: ReferralShareCodeDto,
)
