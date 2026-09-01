package com.kit.wallet

import com.kit.wallet.feature.mobilemoney.mobileMoneyOperationStatusLabel
import com.kit.wallet.ui.model.MobileMoneyOperation
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileMoneySettlementPresentationTest {
    @Test fun `successful payout reads paid while a successful collection reads completed`() {
        assertEquals("Paid", mobileMoneyOperationStatusLabel(operation("payout", "succeeded")))
        assertEquals("Paid", mobileMoneyOperationStatusLabel(operation("PAYOUT", "COMPLETED")))
        assertEquals("Completed", mobileMoneyOperationStatusLabel(operation("collection", "succeeded")))
    }

    @Test fun `nonterminal and unsuccessful operation labels remain exact`() {
        assertEquals("Processing", mobileMoneyOperationStatusLabel(operation("payout", "processing")))
        assertEquals("Failed", mobileMoneyOperationStatusLabel(operation("payout", "failed")))
        assertEquals("Reversed", mobileMoneyOperationStatusLabel(operation("payout", "reversed")))
        assertEquals("Cancelled", mobileMoneyOperationStatusLabel(operation("payout", "cancelled")))
        assertEquals("Cancelled", mobileMoneyOperationStatusLabel(operation("payout", "canceled")))
    }

    private fun operation(action: String, status: String) = MobileMoneyOperation(
        id = "operation",
        reference = "KIT-REFERENCE",
        action = action,
        accountId = "account",
        networkCode = "MTN",
        networkName = "MTN Mobile Money",
        amountMinor = 5_000,
        currencyCode = "UGX",
        currencyScale = 0,
        status = status,
        submissionStage = null,
        createdAt = null,
        failureMessage = null,
    )
}
