package com.kit.wallet

import com.kit.wallet.ui.model.TopUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money arithmetic behind the insufficient-balance top-up.
 *
 * Worth pinning down on its own: every figure the person is shown, the amount the top-up is quoted
 * for and the balance the app waits for all come out of here, and a shortfall rounded the wrong way
 * would leave a payment still unaffordable after a top-up that appeared to work.
 */
class TopUpRequirementTest {

    @Test
    fun `a wallet that covers the payment needs no top-up`() {
        assertNull(TopUp.requirementFor(requiredMinor = 500_000, balanceMinor = 500_000))
        assertNull(TopUp.requirementFor(requiredMinor = 500_000, balanceMinor = 900_000))
    }

    @Test
    fun `the shortfall is the difference between the debit and the balance`() {
        val requirement = TopUp.requirementFor(requiredMinor = 500_000, balanceMinor = 120_000)!!
        assertEquals(500_000, requirement.requiredMinor)
        assertEquals(120_000, requirement.balanceMinor)
        assertEquals(380_000, requirement.shortfallMinor)
    }

    @Test
    fun `an empty wallet is short by the whole payment`() {
        val requirement = TopUp.requirementFor(requiredMinor = 500_000, balanceMinor = 0)!!
        assertEquals(500_000, requirement.shortfallMinor)
        assertEquals(500_000, requirement.topUpMinor)
    }

    @Test
    fun `a part-unit shortfall is topped up to the next whole unit`() {
        // UGX 1,203.40 short — the top-up asks for UGX 1,204, never UGX 1,203.
        val requirement = TopUp.requirementFor(requiredMinor = 120_340, balanceMinor = 0)!!
        assertEquals(120_340, requirement.shortfallMinor)
        assertEquals(120_400, requirement.topUpMinor)
        assertTrue(requirement.topUpMinor > requirement.shortfallMinor)
    }

    @Test
    fun `a whole-unit shortfall is not pushed up another unit`() {
        val requirement = TopUp.requirementFor(requiredMinor = 120_300, balanceMinor = 20_300)!!
        assertEquals(100_000, requirement.shortfallMinor)
        assertEquals(100_000, requirement.topUpMinor)
    }

    @Test
    fun `topping up by the rounded amount always covers the payment`() {
        // The property the whole flow rests on: whatever the shortfall, the rounded top-up leaves
        // the wallet able to pay. Walked across a unit boundary a cent at a time.
        for (shortfall in 1L..250L) {
            val requirement = TopUp.requirementFor(requiredMinor = shortfall, balanceMinor = 0)!!
            assertTrue(
                "Topping up ${requirement.topUpMinor} must cover $shortfall",
                requirement.coveredBy(requirement.topUpMinor),
            )
        }
    }

    @Test
    fun `a balance below the payment does not cover it`() {
        val requirement = TopUp.requirementFor(requiredMinor = 500_000, balanceMinor = 100_000)!!
        assertFalse(requirement.coveredBy(499_999))
        assertTrue(requirement.coveredBy(500_000))
        assertTrue(requirement.coveredBy(600_000))
    }

    @Test
    fun `a payment of nothing is never a shortfall`() {
        assertNull(TopUp.requirementFor(requiredMinor = 0, balanceMinor = 0))
        assertNull(TopUp.requirementFor(requiredMinor = -100, balanceMinor = 0))
    }

    @Test
    fun `a currency without minor units rounds to itself`() {
        val requirement = TopUp.requirementFor(
            requiredMinor = 1_234,
            balanceMinor = 0,
            currencyCode = "JPY",
            currencyScale = 0,
        )!!
        assertEquals(1_234, requirement.topUpMinor)
        assertEquals("JPY", requirement.currencyCode)
        assertEquals(0, requirement.currencyScale)
    }

    @Test
    fun `a three-decimal currency rounds to its own whole unit`() {
        // KWD and friends: 1,000 minor units to the dinar, so 1,001 rounds to 2,000.
        assertEquals(2_000, TopUp.roundUpToWholeUnit(1_001, scale = 3))
        assertEquals(1_000, TopUp.roundUpToWholeUnit(1_000, scale = 3))
    }

    @Test
    fun `a shortfall at the end of the range is reported rather than overflowing`() {
        // Unpayable either way; the point is that it answers instead of throwing while explaining
        // why somebody's payment will not go through.
        assertEquals(Long.MAX_VALUE, TopUp.roundUpToWholeUnit(Long.MAX_VALUE, scale = 2))
    }

    @Test
    fun `rounding nothing is nothing`() {
        assertEquals(0, TopUp.roundUpToWholeUnit(0, scale = 2))
        assertEquals(0, TopUp.roundUpToWholeUnit(-5, scale = 2))
    }
}
