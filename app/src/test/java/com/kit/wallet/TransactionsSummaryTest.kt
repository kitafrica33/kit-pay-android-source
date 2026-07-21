package com.kit.wallet

import com.kit.wallet.feature.wallet.summarizeTransactionActivity
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionsSummaryTest {
    @Test
    fun `empty activity never creates zero weight segments`() {
        val summary = summarizeTransactionActivity(emptyList())

        assertEquals(0L, summary.moneyInMinor)
        assertEquals(0L, summary.moneyOutMinor)
        assertNull(summary.moneyInWeight)
        assertNull(summary.moneyOutWeight)
    }

    @Test
    fun `one sided activity only creates its positive segment`() {
        val incoming = summarizeTransactionActivity(listOf(transaction(5_000)))
        val outgoing = summarizeTransactionActivity(listOf(transaction(-7_500)))

        assertEquals(1f, incoming.moneyInWeight)
        assertNull(incoming.moneyOutWeight)
        assertNull(outgoing.moneyInWeight)
        assertEquals(1f, outgoing.moneyOutWeight)
    }

    @Test
    fun `mixed activity produces finite positive weights`() {
        val summary = summarizeTransactionActivity(
            listOf(transaction(2_500), transaction(-7_500)),
        )

        assertEquals(0.25f, summary.moneyInWeight)
        assertEquals(0.75f, summary.moneyOutWeight)
        assertTrue(summary.moneyInWeight?.isFinite() == true)
        assertTrue(summary.moneyOutWeight?.isFinite() == true)
    }

    private fun transaction(amountMinor: Long) = Transaction(
        id = amountMinor.toString(),
        counterparty = "Test",
        note = null,
        amountMinor = amountMinor,
        time = "Now",
        dateGroup = "Today",
        type = if (amountMinor >= 0) TxType.RECEIVE else TxType.SEND,
        reference = "TEST",
    )
}
