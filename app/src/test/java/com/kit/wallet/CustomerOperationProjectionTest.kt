package com.kit.wallet

import com.kit.wallet.data.remote.BankingOperationDto
import com.kit.wallet.data.remote.BankingOutboundPricingDto
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.MobileMoneyNetworkDto
import com.kit.wallet.data.remote.MobileMoneyOperationDto
import com.kit.wallet.data.repository.hasVerifiedCustomerActivityProjection
import com.kit.wallet.data.repository.customerSafeMobileMoneyOperationFailure
import com.kit.wallet.data.repository.customerSafeMobileMoneyVerificationFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerOperationProjectionTest {
    @Test
    fun `mobile money failures never expose persisted provider diagnostics`() {
        val sensitive = "Commission margin failed in the settlement wallet ledger."

        assertEquals(
            "This payment could not be completed. Check your balance before trying again.",
            customerSafeMobileMoneyOperationFailure(sensitive, "payout", "failed"),
        )
        assertEquals(
            "This deposit could not be completed. No money was added to your wallet.",
            customerSafeMobileMoneyOperationFailure(sensitive, "collection", "failed"),
        )
        assertEquals(
            "We could not confirm this transaction yet. Check again before retrying.",
            customerSafeMobileMoneyOperationFailure(sensitive, "payout", "unknown"),
        )
        assertEquals(
            "This transaction needs attention. Contact support with the reference.",
            customerSafeMobileMoneyOperationFailure(sensitive, "collection", "processing"),
        )
        assertEquals(
            "We could not verify these account details. Review them and try again.",
            customerSafeMobileMoneyVerificationFailure(sensitive),
        )
    }

    @Test
    fun `bank activity accepts only reconciled customer-scoped outbound totals`() {
        assertTrue(bankOperation().hasVerifiedCustomerActivityProjection())
        assertFalse(bankOperation(outboundPricing = null).hasVerifiedCustomerActivityProjection())
        assertFalse(
            bankOperation(
                outboundPricing = pricing(pricingScope = null),
            ).hasVerifiedCustomerActivityProjection(),
        )
        assertFalse(
            bankOperation(
                outboundPricing = pricing(customerDebit = "200.00"),
            ).hasVerifiedCustomerActivityProjection(),
        )
        assertFalse(bankOperation(type = "deposit", direction = "inbound").hasVerifiedCustomerActivityProjection())
    }

    @Test
    fun `mobile money collection requires an exact customer total projection`() {
        assertTrue(mobileOperationCollection().hasVerifiedCustomerActivityProjection())
        assertFalse(
            mobileOperationCollection(pricingScope = null)
                .hasVerifiedCustomerActivityProjection(),
        )
        assertFalse(
            mobileOperationCollection(netAmount = null)
                .hasVerifiedCustomerActivityProjection(),
        )
        assertFalse(
            mobileOperationCollection(amount = "101.00")
                .hasVerifiedCustomerActivityProjection(),
        )
    }

    @Test
    fun `mobile money payout rejects missing mismatched or internal pricing`() {
        assertTrue(mobileOperationPayout().hasVerifiedCustomerActivityProjection())
        assertFalse(
            mobileOperationPayout(outboundPricing = null)
                .hasVerifiedCustomerActivityProjection(),
        )
        assertFalse(
            mobileOperationPayout(
                outboundPricing = pricing(pricingScope = "institutional_ledger"),
            ).hasVerifiedCustomerActivityProjection(),
        )
        assertFalse(
            mobileOperationPayout(
                outboundPricing = pricing(totalFees = "11.00"),
            ).hasVerifiedCustomerActivityProjection(),
        )
        assertFalse(
            mobileOperationPayout(totalFees = "11.00", pricingScope = "customer_totals")
                .hasVerifiedCustomerActivityProjection(),
        )
    }

    private fun bankOperation(
        type: String = "bank_transfer",
        direction: String = "outbound",
        outboundPricing: BankingOutboundPricingDto? = pricing(),
    ) = BankingOperationDto(
        id = "bank-operation",
        reference = "BANK-REF",
        type = type,
        direction = direction,
        status = "completed",
        bankId = "bank",
        beneficiaryId = "beneficiary",
        walletId = "wallet",
        amount = "100.00",
        outboundPricing = outboundPricing,
        feeMode = "sender_absorbs",
        currency = CURRENCY,
    )

    private fun mobileOperationCollection(
        amount: String = "100.00",
        totalFees: String? = "4.00",
        netAmount: String? = "96.00",
        pricingScope: String? = "customer_totals",
    ) = MobileMoneyOperationDto(
        id = "collection",
        reference = "MM-IN",
        type = "deposit",
        direction = "inbound",
        status = "completed",
        bankId = "mobile-bank",
        beneficiaryId = "mobile-account",
        walletId = "wallet",
        amount = amount,
        feeMode = "inclusive",
        totalFees = totalFees,
        netAmount = netAmount,
        pricingScope = pricingScope,
        currency = CURRENCY,
        mobileMoneyType = "collection",
        network = NETWORK,
    )

    private fun mobileOperationPayout(
        outboundPricing: BankingOutboundPricingDto? = pricing(),
        totalFees: String? = null,
        pricingScope: String? = null,
    ) = MobileMoneyOperationDto(
        id = "payout",
        reference = "MM-OUT",
        type = "withdrawal",
        direction = "outbound",
        status = "completed",
        bankId = "mobile-bank",
        beneficiaryId = "mobile-account",
        walletId = "wallet",
        amount = "100.00",
        outboundPricing = outboundPricing,
        feeMode = "sender_absorbs",
        totalFees = totalFees,
        pricingScope = pricingScope,
        currency = CURRENCY,
        mobileMoneyType = "payout",
        network = NETWORK,
    )

    private fun pricing(
        totalFees: String = "10.00",
        customerDebit: String = "110.00",
        pricingScope: String? = "customer_totals",
    ) = BankingOutboundPricingDto(
        feeMode = "sender_absorbs",
        recipientAmount = "100.00",
        processingFee = "10.00",
        customerDebit = customerDebit,
        totalFees = totalFees,
        pricingScope = pricingScope,
    )

    private companion object {
        val CURRENCY = CurrencyDto("UGX", "2")
        val NETWORK = MobileMoneyNetworkDto(
            id = "network",
            code = "MTN",
            name = "MTN Mobile Money",
            currency = CURRENCY,
        )
    }
}
