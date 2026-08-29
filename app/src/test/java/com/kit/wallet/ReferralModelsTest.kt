package com.kit.wallet

import com.kit.wallet.data.referrals.ReferralRewardStatus
import com.kit.wallet.data.referrals.referralStatusFrom
import com.kit.wallet.data.referrals.toDomain
import com.kit.wallet.data.remote.ReferralOverviewDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The referral surface renders only what the server said: statuses map 1:1
 * onto the contract words with everything else neutral-unknown, and amounts,
 * links, and policy numbers pass through verbatim with no client arithmetic.
 */
class ReferralModelsTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun `status words map exactly and unknown words degrade neutrally`() {
        assertEquals(ReferralRewardStatus.PENDING, referralStatusFrom("pending"))
        assertEquals(ReferralRewardStatus.QUALIFIED, referralStatusFrom("qualified"))
        assertEquals(ReferralRewardStatus.PAID, referralStatusFrom("paid"))
        assertEquals(ReferralRewardStatus.EXPIRED, referralStatusFrom("expired"))
        assertEquals(ReferralRewardStatus.NOT_ELIGIBLE, referralStatusFrom("not_eligible"))
        assertEquals(ReferralRewardStatus.REVERSED, referralStatusFrom("reversed"))
        // A future server word must not be guessed at — especially not as PAID.
        assertEquals(ReferralRewardStatus.UNKNOWN, referralStatusFrom("superseded"))
        assertEquals(ReferralRewardStatus.UNKNOWN, referralStatusFrom("PAID"))
    }

    @Test
    fun `the overview binds the wire names and passes server values through verbatim`() {
        val dto = moshi.adapter(ReferralOverviewDto::class.java).fromJson(
            """
            {
              "program": {
                "reward": {"amount": "500.00", "currency": {"code": "NGN", "scale": "2"}},
                "qualifying_balance": {"amount": "1000.00",
                                        "currency": {"code": "NGN", "scale": "2"}},
                "qualifying_business_days": 3,
                "window_days": 30
              },
              "code": {"code": "KIT-ADA-7", "share_url": "https://kit.example/r/KIT-ADA-7"},
              "referrals": [
                {
                  "id": "ref-1",
                  "referred_name": "B. Okoro",
                  "status": "qualified",
                  "reward": {"amount": "500.00", "currency": {"code": "NGN", "scale": "2"}},
                  "attributed_at": "2026-08-01T09:00:00Z",
                  "paid_at": null
                },
                {
                  "id": "ref-2",
                  "status": "superseded",
                  "reward": {"amount": "500.00", "currency": {"code": "NGN", "scale": "2"}},
                  "attributed_at": "2026-08-02T09:00:00Z"
                }
              ],
              "totals": {"total": 2, "pending": 0, "qualified": 1, "paid": 0,
                          "expired": 0, "not_eligible": 0, "reversed": 0}
            }
            """.trimIndent(),
        )!!

        val overview = dto.toDomain()

        assertEquals("500.00", overview.program!!.reward.amount)
        assertEquals("NGN", overview.program!!.reward.currencyCode)
        assertEquals("1000.00", overview.program!!.qualifyingBalance.amount)
        assertEquals(3, overview.program!!.qualifyingBusinessDays)
        assertEquals(30, overview.program!!.windowDays)
        assertEquals("KIT-ADA-7", overview.code!!.code)
        assertEquals("https://kit.example/r/KIT-ADA-7", overview.code!!.shareUrl)
        assertEquals(2, overview.referrals.size)
        assertEquals("B. Okoro", overview.referrals[0].referredName)
        assertEquals(ReferralRewardStatus.QUALIFIED, overview.referrals[0].status)
        // Anonymous entries and unknown statuses stay renderable, verbatim.
        assertNull(overview.referrals[1].referredName)
        assertEquals(ReferralRewardStatus.UNKNOWN, overview.referrals[1].status)
        assertEquals("superseded", overview.referrals[1].rawStatus)
        assertEquals(2, overview.totals.total)
        assertEquals(1, overview.totals.qualified)
    }

    @Test
    fun `no active program and no minted code are ordinary states, not errors`() {
        val dto = moshi.adapter(ReferralOverviewDto::class.java).fromJson(
            """
            {
              "program": null,
              "code": null,
              "referrals": [],
              "totals": {"total": 0, "pending": 0, "qualified": 0, "paid": 0,
                          "expired": 0, "not_eligible": 0, "reversed": 0}
            }
            """.trimIndent(),
        )!!

        val overview = dto.toDomain()

        assertNull(overview.program)
        assertNull(overview.code)
        assertEquals(0, overview.referrals.size)
    }
}
