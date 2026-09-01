package com.kit.wallet

import com.kit.wallet.data.mapper.isCustomerVisibleWalletTransactionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerTransactionVisibilityTest {
    @Test
    fun `reviewed customer movements remain visible`() {
        listOf(
            "airtime",
            "bank_deposit",
            "bank_reversal",
            "bank_transfer",
            "bank_withdrawal",
            "bill_payment",
            "internal_transfer",
            "internal_transfer_reversal",
            "merchant_escrow_release",
            "merchant_payment",
            "merchant_refund",
            "provider_reversal",
            "referral_reward",
            "referral_reward_reversal",
        ).forEach { type ->
            assertTrue(type, type.isCustomerVisibleWalletTransactionType())
        }
    }

    @Test
    fun `institutional and future ledger movements fail closed`() {
        listOf(
            "bank_collection_commission",
            "bank_collection_rounding_adjustment",
            "bank_funding_received",
            "bank_outbound_commission",
            "bank_outbound_commission_reversal",
            "bank_outbound_provider_fee",
            "bank_outbound_provider_fee_reversal",
            "provider_float_credit",
            "future_margin_reconciliation",
        ).forEach { type ->
            assertFalse(type, type.isCustomerVisibleWalletTransactionType())
        }
    }
}
