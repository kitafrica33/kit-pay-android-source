package com.kit.wallet

import com.kit.wallet.ui.model.MobileMoneyOperation
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomerMovementPresentationTest {
    @Test
    fun `bank row prefers aggregate customer debit over nominal transfer amount`() {
        val transaction = transaction(
            amountMinor = -20_000,
            customerDebitMinor = 21_000,
        )

        assertEquals(21_000L, transaction.customerVisibleAmountMinor)
    }

    @Test
    fun `incoming bank row prefers the amount actually added`() {
        val transaction = transaction(
            amountMinor = 10_000,
            recipientAmountMinor = 9_400,
        )

        assertEquals(9_400L, transaction.customerVisibleAmountMinor)
    }

    @Test
    fun `wallet transaction uses its already verified public signed amount`() {
        assertEquals(-20_000L, transaction(amountMinor = -20_000).customerVisibleAmountMinor)
    }

    @Test
    fun `mobile money rows use wallet credit and customer debit aggregates`() {
        val collection = mobileOperation(
            action = "collection",
            amountMinor = 10_000,
            netAmountMinor = 9_400,
        )
        val payout = mobileOperation(
            action = "payout",
            amountMinor = 20_000,
            customerDebitMinor = 21_000,
        )

        assertEquals(9_400L, collection.customerVisibleAmountMinor)
        assertEquals(21_000L, payout.customerVisibleAmountMinor)
    }

    @Test
    fun `mobile money rows never fall back to nominal provider amount`() {
        assertNull(mobileOperation(action = "collection", amountMinor = 10_000).customerVisibleAmountMinor)
        assertNull(mobileOperation(action = "payout", amountMinor = 20_000).customerVisibleAmountMinor)
    }

    private fun transaction(
        amountMinor: Long,
        recipientAmountMinor: Long? = null,
        customerDebitMinor: Long? = null,
    ) = Transaction(
        id = "transaction",
        counterparty = "Customer counterparty",
        note = null,
        amountMinor = amountMinor,
        time = "Now",
        dateGroup = "Today",
        type = if (amountMinor < 0) TxType.BANK_OUT else TxType.BANK_IN,
        reference = "CUSTOMER-REF",
        recipientAmountMinor = recipientAmountMinor,
        customerDebitMinor = customerDebitMinor,
    )

    private fun mobileOperation(
        action: String,
        amountMinor: Long,
        netAmountMinor: Long? = null,
        customerDebitMinor: Long? = null,
    ) = MobileMoneyOperation(
        id = "operation-$action",
        reference = "MOBILE-REF",
        action = action,
        accountId = "account",
        networkCode = "MTN",
        networkName = "MTN Mobile Money",
        amountMinor = amountMinor,
        currencyCode = "UGX",
        currencyScale = 0,
        status = "completed",
        submissionStage = null,
        createdAt = null,
        failureMessage = null,
        netAmountMinor = netAmountMinor,
        customerDebitMinor = customerDebitMinor,
    )
}
