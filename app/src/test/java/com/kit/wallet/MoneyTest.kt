package com.kit.wallet

import com.kit.wallet.ui.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `formats whole UGX amounts with grouping and no decimals`() {
        assertEquals("UGX 1,284,500", Money.format(128_450_000))
        assertEquals("UGX 25,000", Money.format(2_500_000))
        assertEquals("UGX 0", Money.format(0))
    }

    @Test
    fun `negative amounts use minus sign`() {
        assertEquals("−UGX 85,000", Money.format(-8_500_000))
    }

    @Test
    fun `signed formatting adds plus for money in`() {
        assertEquals("+UGX 25,000", Money.format(2_500_000, signed = true))
        assertEquals("−UGX 5,000", Money.format(-500_000, signed = true))
    }

    @Test
    fun `cents render only when non-zero`() {
        assertEquals("UGX 1.05", Money.format(105))
        assertEquals("UGX 10,000.99", Money.format(1_000_099))
    }

    @Test
    fun `parses money exactly without floating point rounding`() {
        assertEquals(29L, Money.parseMinor("0.29"))
        assertEquals(128_450_000L, Money.parseMinor("1284500"))
        assertNull(Money.parseMinor("1.005"))
        assertNull(Money.parseMinor("not money"))
    }

    @Test
    fun `formats the minimum long value safely`() {
        assertEquals("−UGX 92,233,720,368,547,758.08", Money.format(Long.MIN_VALUE))
    }
}
