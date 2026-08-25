package com.kit.wallet.ui.model

/**
 * What a payment is short by, and what it would take to cover it.
 *
 * Held as a value rather than recomputed at each screen so the number the user is told, the number
 * the top-up is quoted for and the number the wait waits for are provably the same one. A payment
 * that has been re-quoted, or a balance that moved while the sheet was open, produces a new
 * requirement rather than quietly changing this one underneath the person reading it.
 */
data class TopUpRequirement(
    /** The total debit the payment needs, fees included. */
    val requiredMinor: Long,
    /** The available balance at the moment the shortfall was established. */
    val balanceMinor: Long,
    /** How much the balance falls short. Always positive. */
    val shortfallMinor: Long,
    /**
     * What to move into the wallet: [shortfallMinor] rounded up to the next whole currency unit.
     *
     * Rounded up rather than to nearest, because rounding a shortfall down leaves the payment still
     * unaffordable after a successful top-up — the one outcome this flow exists to prevent. Whole
     * units are also what the collection endpoints accept.
     */
    val topUpMinor: Long,
    val currencyCode: String,
    val currencyScale: Int,
) {
    /** Whether a wallet holding this much would now cover the payment. */
    fun coveredBy(balanceMinor: Long): Boolean = balanceMinor >= requiredMinor
}

object TopUp {
    /**
     * The shortfall for a payment, or null when the wallet already covers it.
     *
     * [requiredMinor] is the customer debit — the amount plus whatever fees the quote adds — not
     * the amount the recipient receives. Those differ on every channel that charges, and comparing
     * the wrong one against the balance would let a payment through that the server then refuses.
     */
    fun requirementFor(
        requiredMinor: Long,
        balanceMinor: Long,
        currencyCode: String = Money.SYMBOL,
        currencyScale: Int = Money.SCALE,
    ): TopUpRequirement? {
        if (requiredMinor <= 0) return null
        val shortfall = requiredMinor - balanceMinor
        if (shortfall <= 0) return null
        return TopUpRequirement(
            requiredMinor = requiredMinor,
            balanceMinor = balanceMinor,
            shortfallMinor = shortfall,
            topUpMinor = roundUpToWholeUnit(shortfall, currencyScale),
            currencyCode = currencyCode,
            currencyScale = currencyScale,
        )
    }

    /**
     * Rounds minor units up to the next whole currency unit — UGX 1,203.40 becomes UGX 1,204.
     *
     * An amount already on a whole unit is left alone rather than pushed to the next one, so
     * topping up a round shortfall does not silently ask for a unit more than it needs.
     */
    fun roundUpToWholeUnit(amountMinor: Long, scale: Int): Long {
        require(scale in 0..18) { "Unsupported currency scale" }
        if (amountMinor <= 0) return 0
        var unit = 1L
        repeat(scale) { unit *= 10 }
        val remainder = amountMinor % unit
        if (remainder == 0L) return amountMinor
        // A shortfall this close to the end of the range cannot be topped up anyway; returning it
        // unrounded keeps this total rather than throwing on the way to telling somebody why their
        // payment will not go through.
        return runCatching { Math.addExact(amountMinor, unit - remainder) }.getOrDefault(amountMinor)
    }
}
