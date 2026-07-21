package com.kit.wallet

import com.kit.wallet.data.mapper.DecimalMoney
import java.lang.ArithmeticException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DecimalMoneyTest {
    @Test
    fun `converts exact scale-2 decimal strings to minor units`() {
        assertEquals(128_450_000L, DecimalMoney.toMinor("1284500.00", 2))
        assertEquals(105L, DecimalMoney.toMinor("1.05", 2))
        assertEquals(-250L, DecimalMoney.toMinor("-2.50", 2))
    }

    @Test
    fun `rejects precision that cannot be represented without rounding`() {
        assertThrows(ArithmeticException::class.java) {
            DecimalMoney.toMinor("1.005", 2)
        }
    }

    @Test
    fun `rejects values outside Long range`() {
        assertThrows(ArithmeticException::class.java) {
            DecimalMoney.toMinor("999999999999999999999.00", 2)
        }
    }
}
