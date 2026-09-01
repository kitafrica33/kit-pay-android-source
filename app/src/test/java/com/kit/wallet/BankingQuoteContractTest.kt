package com.kit.wallet

import com.kit.wallet.data.remote.BankingOperationDto
import com.kit.wallet.data.remote.BankingOutboundPricingDto
import com.kit.wallet.data.remote.BankingOutboundQuoteDto
import com.kit.wallet.data.remote.BankingQuoteBankDto
import com.kit.wallet.data.remote.BankingQuoteStepUpDto
import com.kit.wallet.data.remote.CreateBankingOutboundQuoteRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.validateBankingOutboundQuote
import com.kit.wallet.ui.model.BankOperationKind
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

class BankingQuoteContractTest {
    private val now = Instant.parse("2026-08-22T12:00:00Z")

    @Test
    fun `outbound quote routes match the current bank contract`() {
        val methods = KitWalletApi::class.declaredMemberFunctions.associateBy { it.name }
        assertEquals(
            "api/kit-wallet/v1/banking/operations/{operationId}",
            methods.getValue("bankingOperation").annotations.filterIsInstance<GET>().single().value,
        )
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
    fun `aggregate-only quote decodes and validates without institutional fields`() {
        val json = """{
            "id":"quote","action":"transfer","operation_type":"bank_transfer",
            "fee_mode":"sender_absorbs","wallet_id":"wallet","beneficiary_id":"beneficiary",
            "bank":{"id":"bank","code":"BANK","name":"Bank"},
            "recipient_amount":"20000","processing_fee":"1000","total_fees":"1000",
            "pricing_scope":"customer_totals","customer_debit":"21000",
            "schedule_verified":true,"currency":{"code":"UGX","scale":"0"},
            "expires_at":"2026-08-22T13:00:00Z",
            "step_up":{"purpose":"bank_transfer","intent":${publicIntentJson()}}
        }"""
        val quote = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(BankingOutboundQuoteDto::class.java)
            .fromJson(json)

        assertNotNull(quote)
        validate(requireNotNull(quote))
    }

    @Test
    fun `legacy step-up aliases remain accepted without becoming DTO properties`() {
        validate(quote(totalFees = null, pricingScope = null, includeLegacyIntent = true))

        val forbidden = setOf(
            "providerFee", "kitFee", "providerFeeCap", "maximumProviderTotal", "kitDebit",
            "actualProviderFee", "actualProviderTotal", "platformFee", "roundingAdjustment",
            "providerFeeEstimated", "scheduleVersion",
        )
        listOf(
            BankingOutboundQuoteDto::class,
            BankingOutboundPricingDto::class,
            BankingOperationDto::class,
        ).forEach { type ->
            assertTrue(type.declaredMemberProperties.map { it.name }.toSet().intersect(forbidden).isEmpty())
        }
    }

    @Test
    fun `outbound quote rejects contradictory totals unsafe scope and unknown intent keys`() {
        val quote = quote()
        validate(quote)

        assertTrue(runCatching { validate(quote.copy(totalFees = "999")) }.isFailure)
        assertTrue(runCatching { validate(quote.copy(pricingScope = "institutional_split")) }.isFailure)
        assertTrue(
            runCatching {
                validate(
                    quote.copy(
                        stepUp = quote.stepUp.copy(
                            intent = quote.stepUp.intent + ("actual_provider_fee" to "750"),
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(runCatching { validate(quote.copy(customerDebit = "21001")) }.isFailure)
        assertTrue(
            runCatching {
                validate(quote.copy(currency = CurrencyDto("UGX", "2")))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                validate(
                    quote.copy(
                        stepUp = quote.stepUp.copy(
                            intent = quote.stepUp.intent + ("amount" to "20000"),
                        ),
                    ),
                )
            }.isFailure,
        )
    }

    private fun validate(quote: BankingOutboundQuoteDto) {
        validateBankingOutboundQuote(
            quote,
            BankOperationKind.TRANSFER,
            "wallet",
            "beneficiary",
            "bank",
            "20000",
            "sender_absorbs",
            "UGX",
            0,
            now,
        )
    }

    private fun quote(
        totalFees: String? = "1000",
        pricingScope: String? = "customer_totals",
        includeLegacyIntent: Boolean = false,
    ): BankingOutboundQuoteDto {
        val intent = publicIntent().toMutableMap()
        if (includeLegacyIntent) {
            intent += mapOf(
                "provider_fee" to "1000",
                "kit_fee" to "0",
                "provider_fee_cap" to "1000",
                "maximum_provider_total" to "21000",
                "kit_debit" to "0",
                "schedule_version" to "v1",
            )
        }
        return BankingOutboundQuoteDto(
            id = "quote",
            action = "transfer",
            operationType = "bank_transfer",
            feeMode = "sender_absorbs",
            walletId = "wallet",
            beneficiaryId = "beneficiary",
            bank = BankingQuoteBankDto("bank", "BANK", "Bank"),
            recipientAmount = "20000",
            processingFee = "1000",
            customerDebit = "21000",
            scheduleVerified = true,
            currency = CurrencyDto("UGX", "0"),
            expiresAt = "2026-08-22T13:00:00Z",
            stepUp = BankingQuoteStepUpDto("bank_transfer", intent),
            totalFees = totalFees,
            pricingScope = pricingScope,
        )
    }

    private fun publicIntent(): Map<String, String> = mapOf(
        "action" to "transfer",
        "operation_type" to "bank_transfer",
        "quote_id" to "quote",
        "wallet_id" to "wallet",
        "beneficiary_id" to "beneficiary",
        "bank_id" to "bank",
        "bank_code" to "BANK",
        "fee_mode" to "sender_absorbs",
        "recipient_amount" to "20000",
        "processing_fee" to "1000",
        "customer_debit" to "21000",
        "currency" to "UGX",
    )

    private fun publicIntentJson(): String = publicIntent().entries.joinToString(
        prefix = "{",
        postfix = "}",
    ) { (key, value) -> "\"$key\":\"$value\"" }
}
