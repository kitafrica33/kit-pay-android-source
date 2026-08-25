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
    fun parseMinor(value: String, scale: Int = SCALE): Long? = runCatching {
        require(scale in 0..18)
        BigDecimal(value.trim())
            .setScale(scale, RoundingMode.UNNECESSARY)
            .movePointRight(scale)
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
        val grouped = groupUnits(units.toString())
        val fraction = when {
            cents == 0 -> ""
            cents % 10 == 0 -> (cents / 10).toString()
            cents < 10 -> "0$cents"
            else -> cents.toString()
        }
        val body = if (fraction.isEmpty()) grouped else "$grouped.$fraction"
        return if (withSymbol) "$sign$SYMBOL $body" else "$sign$body"
    }

    /**
     * Thousands separators for a run of whole units, and the single place the app decides what
     * grouping looks like.
     *
     * Every amount the user reads — a balance, a receipt, a chat payment, a half-typed keypad
     * entry — comes through here, so none of them can drift apart into a differently punctuated
     * house style.
     */
    fun groupUnits(units: String): String =
        units.reversed().chunked(3).joinToString(",").reversed()

    /**
     * Groups a half-typed amount for display without changing what was typed: only the digits
     * ahead of the decimal point gain separators, and everything from the point onwards is
     * echoed back verbatim so a keypad entry mid-decimal still reads as the user left it.
     * Anything that is not a plain digit string is returned untouched.
     */
    fun groupTypedAmount(text: String): String {
        val point = text.indexOf('.')
        val units = if (point < 0) text else text.substring(0, point)
        val rest = if (point < 0) "" else text.substring(point)
        if (!units.all(Char::isDigit)) return text
        return groupUnits(units) + rest
    }

    fun format(
        amountMinor: Long,
        currencyCode: String,
        scale: Int,
        signed: Boolean = false,
    ): String {
        require(scale in 0..18)
        val sign = when {
            signed && amountMinor > 0 -> "+"
            amountMinor < 0 -> "−"
            else -> ""
        }
        val amount = BigDecimal(BigInteger.valueOf(amountMinor).abs(), scale)
            .stripTrailingZeros().toPlainString()
        // Group the whole units the same way the single-currency formatter does, so a payment
        // card and the wallet balance never disagree about how UGX 120,000 is written.
        val units = amount.substringBefore('.')
        val fraction = amount.substringAfter('.', "")
        val grouped = groupUnits(units)
        val body = if (fraction.isEmpty()) grouped else "$grouped.$fraction"
        return "$sign${currencyCode.uppercase()} $body"
    }
}
