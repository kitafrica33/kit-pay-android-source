package com.kit.wallet.data.referrals

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.ReferralListItemDto
import com.kit.wallet.data.remote.ReferralMoneyDto
import com.kit.wallet.data.remote.ReferralOverviewDto
import com.kit.wallet.data.remote.ReferralProgramTermsDto
import com.kit.wallet.data.remote.ReferralShareCodeDto
import com.kit.wallet.data.remote.ReferralTotalsDto
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authenticated referral client. Two calls, both server-authoritative and both
 * fenced to the preparing session; the surface stays dark unless the exact
 * `referrals` capability is advertised (gated in AppCapabilities, not here).
 */
@Singleton
class ReferralRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
) {
    suspend fun overview(): ReferralOverview {
        val fence = requireSession()
        return apiCalls.execute { api.referralOverview(fence) }.toDomain()
    }

    /**
     * The account's single active share code, minted server-side on first use.
     * Idempotent by contract: safe to call again after a lost response.
     */
    suspend fun ensureCode(): ReferralShareCode {
        val fence = requireSession()
        return apiCalls.execute { api.ensureReferralCode(fence) }.code.toDomain()
    }

    private fun requireSession() =
        sessions.current()?.fence() ?: throw SessionInvalidatedException()
}

private fun ReferralMoneyDto.toDomain(): ReferralAmount = ReferralAmount(
    amount = amount,
    currencyCode = currency.code,
)

private fun ReferralProgramTermsDto.toDomain(): ReferralProgramTerms = ReferralProgramTerms(
    reward = reward.toDomain(),
    qualifyingBalance = qualifyingBalance.toDomain(),
    qualifyingBusinessDays = qualifyingBusinessDays,
    windowDays = windowDays,
)

private fun ReferralShareCodeDto.toDomain(): ReferralShareCode = ReferralShareCode(
    code = code,
    shareUrl = shareUrl,
)

internal fun referralStatusFrom(raw: String): ReferralRewardStatus = when (raw) {
    "pending" -> ReferralRewardStatus.PENDING
    "qualified" -> ReferralRewardStatus.QUALIFIED
    "paid" -> ReferralRewardStatus.PAID
    "expired" -> ReferralRewardStatus.EXPIRED
    "not_eligible" -> ReferralRewardStatus.NOT_ELIGIBLE
    "reversed" -> ReferralRewardStatus.REVERSED
    else -> ReferralRewardStatus.UNKNOWN
}

private fun ReferralListItemDto.toDomain(): ReferralEntry = ReferralEntry(
    id = id,
    referredName = referredName,
    status = referralStatusFrom(status),
    rawStatus = status,
    reward = reward.toDomain(),
    attributedAt = attributedAt,
    paidAt = paidAt,
)

private fun ReferralTotalsDto.toDomain(): ReferralTotals = ReferralTotals(
    total = total,
    pending = pending,
    qualified = qualified,
    paid = paid,
    expired = expired,
    notEligible = notEligible,
    reversed = reversed,
)

internal fun ReferralOverviewDto.toDomain(): ReferralOverview = ReferralOverview(
    program = program?.toDomain(),
    code = code?.toDomain(),
    referrals = referrals.map { it.toDomain() },
    totals = totals.toDomain(),
)
