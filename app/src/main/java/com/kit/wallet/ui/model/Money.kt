package com.kit.wallet.ui.model

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Money formatting. Currency: Ugandan shilling (UGX). Amounts are stored in
 * minor units (cents) for ledger compatibility, but UGX is displayed without
 * decimals in practice — cents only render when non-zero.
 */
object Money {
    const val SYMBOL = "UGX"
    const val SCALE = 2

    /**
     * Parses user-entered money without ever passing through binary floating point.
     * Values with more fractional digits than the currency supports are rejected,
     * as are values that cannot fit in the app's signed minor-unit representation.
     */
    fun parseMinor(value: String): Long? = runCatching {
        BigDecimal(value.trim())
            .setScale(SCALE, RoundingMode.UNNECESSARY)
            .movePointRight(SCALE)
            .longValueExact()
    }.getOrNull()

    fun format(amountMinor: Long, withSymbol: Boolean = true, signed: Boolean = false): String {
        val sign = when {
            signed && amountMinor > 0 -> "+"
            amountMinor < 0 -> "−" // minus sign
            else -> ""
        }
        val absolute = BigInteger.valueOf(amountMinor).abs()
        val units = absolute.divide(BigInteger.valueOf(100))
        val cents = absolute.mod(BigInteger.valueOf(100)).toInt()
        val grouped = units.toString().reversed().chunked(3).joinToString(",").reversed()
        val body = if (cents == 0) grouped else "$grouped.%02d".format(cents)
        return if (withSymbol) "$sign$SYMBOL $body" else "$sign$body"
    }
}
