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
    fun `primary UGX formatting groups amounts and trims only trailing fractional zeros`() {
        assertEquals("UGX 1,856.84", Money.format(185_684))
        assertEquals("UGX 1,768.8", Money.format(176_880))
        assertEquals("UGX 1,000", Money.format(100_000))
    }

    @Test
    fun `parses money exactly without floating point rounding`() {
        assertEquals(29L, Money.parseMinor("0.29"))
        assertEquals(128_450_000L, Money.parseMinor("1284500"))
        assertNull(Money.parseMinor("1.005"))
        assertNull(Money.parseMinor("not money"))
    }

    @Test
    fun `parses zero-scale currencies as whole units`() {
        assertEquals(1_000L, Money.parseMinor("1000", scale = 0))
        assertNull(Money.parseMinor("1000.50", scale = 0))
    }

    @Test
    fun `formats the minimum long value safely`() {
        assertEquals("−UGX 92,233,720,368,547,758.08", Money.format(Long.MIN_VALUE))
    }

    @Test fun `formats currency using its authoritative scale`() {
        assertEquals("UGX 1,000", Money.format(1_000, "UGX", 0))
        assertEquals("+USD 10.5", Money.format(1_050, "usd", 2, signed = true))
    }

    @Test
    fun `currency-aware formatting groups whole units like the wallet balance`() {
        assertEquals("UGX 120,000", Money.format(12_000_000, "UGX", 2))
        assertEquals("UGX 1,284,500", Money.format(128_450_000, "UGX", 2))
        assertEquals(Money.format(2_500_000), Money.format(2_500_000, "UGX", 2))
        assertEquals("−KES 1,234.56", Money.format(-123_456, "KES", 2))
        assertEquals("JPY 500", Money.format(500, "JPY", 0))
        assertEquals("BHD 1,000.001", Money.format(1_000_001, "BHD", 3))
    }

    @Test
    fun `typed amounts are grouped for display without altering the entry`() {
        assertEquals("25,000", Money.groupTypedAmount("25000"))
        assertEquals("1,284,500", Money.groupTypedAmount("1284500"))
        assertEquals("100", Money.groupTypedAmount("100"))
        assertEquals("", Money.groupTypedAmount(""))
        // A half-typed decimal keeps every character the keypad produced.
        assertEquals("25,000.", Money.groupTypedAmount("25000."))
        assertEquals("25,000.5", Money.groupTypedAmount("25000.5"))
        assertEquals(".5", Money.groupTypedAmount(".5"))
        // Anything that is not a plain digit run is echoed untouched rather than mangled.
        assertEquals("1e5", Money.groupTypedAmount("1e5"))
        assertEquals("-100", Money.groupTypedAmount("-100"))
    }

    @Test
    fun `grouping a typed amount never changes what it parses to`() {
        listOf("0", "7", "25000", "1284500", "0.29", "1000.05").forEach { typed ->
            assertEquals(
                Money.parseMinor(typed),
                Money.parseMinor(Money.groupTypedAmount(typed).replace(",", "")),
            )
        }
    }

    @Test
    fun `currency-aware formatting survives the minimum long value`() {
        assertEquals(
            "−UGX 92,233,720,368,547,758.08",
            Money.format(Long.MIN_VALUE, "UGX", 2),
        )
    }
}
