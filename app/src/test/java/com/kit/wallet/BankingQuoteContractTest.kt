package com.kit.wallet

import com.kit.wallet.data.remote.BankingOutboundQuoteDto
import com.kit.wallet.data.remote.BankingQuoteBankDto
import com.kit.wallet.data.remote.BankingQuoteStepUpDto
import com.kit.wallet.data.remote.CreateBankingOutboundQuoteRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.validateBankingOutboundQuote
import com.kit.wallet.ui.model.BankOperationKind
import java.time.Instant
import kotlin.reflect.full.declaredMemberFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.POST

class BankingQuoteContractTest {
    @Test
    fun `outbound quote routes match the current bank contract`() {
        val methods = KitWalletApi::class.declaredMemberFunctions.associateBy { it.name }
        assertEquals(
            "api/kit-wallet/v1/banking/withdrawal-quotes",
            methods.getValue("createBankWithdrawalQuote").annotations.filterIsInstance<POST>().single().value,
        )
        assertEquals(
            "api/kit-wallet/v1/banking/transfer-quotes",
            methods.getValue("createBankTransferQuote").annotations.filterIsInstance<POST>().single().value,
        )
        assertEquals(
            "sender_absorbs",
            CreateBankingOutboundQuoteRequest("wallet", "beneficiary", "20000", "sender_absorbs").feeMode,
        )
    }

    @Test
    fun `outbound quote must preserve amounts expiry and exact step-up binding`() {
        val quote = quote()
        validateBankingOutboundQuote(
            quote, BankOperationKind.TRANSFER, "wallet", "beneficiary", "bank", "20000",
            "sender_absorbs", "UGX", 0, Instant.parse("2026-08-22T12:00:00Z"),
        )
        assertTrue(runCatching {
            validateBankingOutboundQuote(
                quote.copy(customerDebit = "21001"), BankOperationKind.TRANSFER,
                "wallet", "beneficiary", "bank", "20000", "sender_absorbs", "UGX", 0,
                Instant.parse("2026-08-22T12:00:00Z"),
            )
        }.isFailure)
        assertTrue(runCatching {
            validateBankingOutboundQuote(
                quote.copy(currency = CurrencyDto("UGX", "2")), BankOperationKind.TRANSFER,
                "wallet", "beneficiary", "bank", "20000", "sender_absorbs", "UGX", 0,
                Instant.parse("2026-08-22T12:00:00Z"),
            )
        }.isFailure)
        assertTrue(runCatching {
            validateBankingOutboundQuote(
                quote.copy(stepUp = quote.stepUp.copy(intent = quote.stepUp.intent + ("amount" to "20000"))),
                BankOperationKind.TRANSFER, "wallet", "beneficiary", "bank", "20000",
                "sender_absorbs", "UGX", 0, Instant.parse("2026-08-22T12:00:00Z"),
            )
        }.isFailure)
    }

    private fun quote(): BankingOutboundQuoteDto {
        val values = mapOf(
            "action" to "transfer", "operation_type" to "bank_transfer", "quote_id" to "quote",
            "wallet_id" to "wallet", "beneficiary_id" to "beneficiary", "bank_id" to "bank",
            "bank_code" to "BANK", "fee_mode" to "sender_absorbs",
            "recipient_amount" to "20000", "processing_fee" to "1000",
            "provider_fee" to "800", "kit_fee" to "200", "provider_fee_cap" to "800",
            "maximum_provider_total" to "20800", "customer_debit" to "21000",
            "kit_debit" to "0", "schedule_version" to "v1", "currency" to "UGX",
        )
        return BankingOutboundQuoteDto(
            "quote", "transfer", "bank_transfer", "sender_absorbs", "wallet", "beneficiary",
            BankingQuoteBankDto("bank", "BANK", "Bank"), "20000", "1000", "800", "200",
            "800", "20800", "21000", "0", "v1", true, CurrencyDto("UGX", "0"),
            "2026-08-22T13:00:00Z", BankingQuoteStepUpDto("bank_transfer", values),
        )
    }
}
