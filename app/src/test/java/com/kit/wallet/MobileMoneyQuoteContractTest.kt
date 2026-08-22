package com.kit.wallet

import com.kit.wallet.data.remote.CreateMobileMoneyQuoteRequest
import com.kit.wallet.data.remote.CreateQuotedMobileMoneyOperationRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MobileMoneyQuoteDto
import com.kit.wallet.data.remote.MobileMoneyQuoteStepUpDto
import com.kit.wallet.data.repository.validateMobileMoneyQuote
import java.time.Instant
import kotlin.reflect.full.declaredMemberFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.POST

class MobileMoneyQuoteContractTest {
    @Test
    fun `quote routes and payloads match the current mobile money contract`() {
        val methods = KitWalletApi::class.declaredMemberFunctions.associateBy { it.name }
        assertEquals(
            "api/kit-wallet/v1/mobile-money/collection-quotes",
            methods.getValue("createMobileMoneyCollectionQuote").annotations.filterIsInstance<POST>().single().value,
        )
        assertEquals(
            "api/kit-wallet/v1/mobile-money/payout-quotes",
            methods.getValue("createMobileMoneyPayoutQuote").annotations.filterIsInstance<POST>().single().value,
        )
        assertEquals("sender_absorbs", CreateMobileMoneyQuoteRequest("w", "a", "1000", "sender_absorbs").feeMode)
        assertEquals("q", CreateQuotedMobileMoneyOperationRequest("q").quoteId)
    }

    @Test
    fun `quote validation rejects altered or expired quote bindings`() {
        val quote = quote()
        validateMobileMoneyQuote(
            quote, "payout", "wallet", "account", "1000", "sender_absorbs", "UGX",
            0, Instant.parse("2026-08-22T12:00:00Z"),
        )
        assertTrue(runCatching {
            validateMobileMoneyQuote(
                quote.copy(recipientAmount = "999"), "payout", "wallet", "account", "1000",
                "sender_absorbs", "UGX", 0, Instant.parse("2026-08-22T12:00:00Z"),
            )
        }.isFailure)
        assertTrue(runCatching {
            validateMobileMoneyQuote(
                quote.copy(currency = CurrencyDto("UGX", "2")), "payout", "wallet", "account",
                "1000", "sender_absorbs", "UGX", 0,
                Instant.parse("2026-08-22T12:00:00Z"),
            )
        }.isFailure)
        assertTrue(runCatching {
            validateMobileMoneyQuote(
                quote, "payout", "wallet", "account", "1000", "sender_absorbs", "UGX",
                0, Instant.parse("2026-08-23T12:00:00Z"),
            )
        }.isFailure)
    }

    private fun quote() = MobileMoneyQuoteDto(
        id = "quote",
        action = "payout",
        feeMode = "sender_absorbs",
        walletId = "wallet",
        accountId = "account",
        network = "MTN",
        currency = CurrencyDto("UGX", "0"),
        recipientAmount = "1000",
        customerDebit = "1050",
        processingFee = "50",
        providerFee = "40",
        kitFee = "10",
        providerFeeCap = "40",
        maximumProviderTotal = "1040",
        kitDebit = "0",
        scheduleVersion = "v1",
        scheduleVerified = true,
        expiresAt = "2026-08-22T13:00:00Z",
        stepUp = MobileMoneyQuoteStepUpDto(
            purpose = "mobile_money_payout",
            intent = mapOf(
                "action" to "payout", "quote_id" to "quote", "wallet_id" to "wallet",
                "mobile_money_account_id" to "account", "network" to "MTN",
                "fee_mode" to "sender_absorbs", "recipient_amount" to "1000",
                "processing_fee" to "50", "provider_fee" to "40", "kit_fee" to "10",
                "provider_fee_cap" to "40", "maximum_provider_total" to "1040",
                "customer_debit" to "1050", "kit_debit" to "0",
                "schedule_version" to "v1", "currency" to "UGX",
            ),
        ),
    )
}
