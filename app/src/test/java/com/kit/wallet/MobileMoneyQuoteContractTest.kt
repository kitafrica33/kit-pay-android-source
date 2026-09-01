package com.kit.wallet

import com.kit.wallet.data.remote.CreateMobileMoneyQuoteRequest
import com.kit.wallet.data.remote.CreateQuotedMobileMoneyOperationRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MobileMoneyOperationDto
import com.kit.wallet.data.remote.MobileMoneyQuoteDto
import com.kit.wallet.data.remote.MobileMoneyQuoteStepUpDto
import com.kit.wallet.data.repository.validateMobileMoneyQuote
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.POST

class MobileMoneyQuoteContractTest {
    private val now = Instant.parse("2026-08-22T12:00:00Z")

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
    fun `aggregate-only payout quote decodes and validates`() {
        val json = """{
            "id":"quote","action":"payout","fee_mode":"sender_absorbs",
            "wallet_id":"wallet","account_id":"account","network":"MTN",
            "currency":{"code":"UGX","scale":"0"},"recipient_amount":"1000",
            "customer_debit":"1050","processing_fee":"50","total_fees":"50",
            "pricing_scope":"customer_totals","schedule_verified":true,
            "expires_at":"2026-08-22T13:00:00Z",
            "step_up":{"purpose":"mobile_money_payout","intent":${payoutIntentJson()}}
        }"""
        val quote = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(MobileMoneyQuoteDto::class.java)
            .fromJson(json)

        assertNotNull(quote)
        validatePayout(requireNotNull(quote))
    }

    @Test
    fun `legacy payout aliases remain accepted but unknown keys fail closed`() {
        validatePayout(payoutQuote(totalFees = null, pricingScope = null, includeLegacyIntent = true))

        val quote = payoutQuote()
        assertTrue(
            runCatching {
                validatePayout(
                    quote.copy(
                        stepUp = quote.stepUp.copy(
                            intent = quote.stepUp.intent + ("actual_provider_fee" to "40"),
                        ),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun `payout validation rejects contradictory totals unsafe scope and altered bindings`() {
        val quote = payoutQuote()
        validatePayout(quote)

        assertTrue(runCatching { validatePayout(quote.copy(totalFees = "49")) }.isFailure)
        assertTrue(runCatching { validatePayout(quote.copy(pricingScope = "institutional_split")) }.isFailure)
        assertTrue(runCatching { validatePayout(quote.copy(recipientAmount = "999")) }.isFailure)
        assertTrue(
            runCatching {
                validatePayout(quote.copy(currency = CurrencyDto("UGX", "2")))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                validateMobileMoneyQuote(
                    quote,
                    "payout",
                    "wallet",
                    "account",
                    "1000",
                    "sender_absorbs",
                    "UGX",
                    0,
                    Instant.parse("2026-08-23T12:00:00Z"),
                )
            }.isFailure,
        )
    }

    @Test
    fun `collection accepts only its known compatibility aliases`() {
        val public = collectionIntent()
        val knownLegacy = mapOf(
            "provider_fee" to "50",
            "platform_fee" to "0",
            "rounding_adjustment" to "0",
        )
        val quote = collectionQuote(public + knownLegacy)
        validateCollection(quote)

        assertTrue(
            runCatching {
                validateCollection(
                    quote.copy(
                        stepUp = quote.stepUp.copy(
                            intent = quote.stepUp.intent + ("kit_fee" to "0"),
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                validateCollection(quote.copy(pricingScope = "provider_breakdown"))
            }.isFailure,
        )

        val wrongFeeTreatment = quote.copy(
            feeMode = "inclusive",
            stepUp = quote.stepUp.copy(
                intent = quote.stepUp.intent + ("fee_mode" to "inclusive"),
            ),
        )
        assertTrue(
            runCatching { validateCollection(wrongFeeTreatment, feeMode = "inclusive") }.isFailure,
        )

        val inclusive = wrongFeeTreatment.copy(
            providerAmount = "1000",
            walletCredit = "950",
            stepUp = wrongFeeTreatment.stepUp.copy(
                intent = wrongFeeTreatment.stepUp.intent + mapOf(
                    "provider_amount" to "1000",
                    "wallet_credit" to "950",
                ),
            ),
        )
        validateCollection(inclusive, feeMode = "inclusive")
    }

    @Test
    fun `mobile money DTOs expose no institutional fee components`() {
        val forbidden = setOf(
            "providerFee", "kitFee", "providerFeeCap", "maximumProviderTotal", "kitDebit",
            "actualProviderFee", "actualProviderTotal", "platformFee", "roundingAdjustment",
            "providerFeeEstimated", "scheduleVersion",
        )
        listOf(MobileMoneyQuoteDto::class, MobileMoneyOperationDto::class).forEach { type ->
            assertTrue(type.declaredMemberProperties.map { it.name }.toSet().intersect(forbidden).isEmpty())
        }
    }

    private fun validatePayout(quote: MobileMoneyQuoteDto) {
        validateMobileMoneyQuote(
            quote,
            "payout",
            "wallet",
            "account",
            "1000",
            "sender_absorbs",
            "UGX",
            0,
            now,
        )
    }

    private fun validateCollection(
        quote: MobileMoneyQuoteDto,
        feeMode: String = "gross_up",
    ) {
        validateMobileMoneyQuote(
            quote,
            "collection",
            "wallet",
            "account",
            "1000",
            feeMode,
            "UGX",
            0,
            now,
        )
    }

    private fun payoutQuote(
        totalFees: String? = "50",
        pricingScope: String? = "customer_totals",
        includeLegacyIntent: Boolean = false,
    ): MobileMoneyQuoteDto {
        val intent = payoutIntent().toMutableMap()
        if (includeLegacyIntent) {
            intent += mapOf(
                "provider_fee" to "50",
                "kit_fee" to "0",
                "provider_fee_cap" to "50",
                "maximum_provider_total" to "1050",
                "kit_debit" to "0",
                "schedule_version" to "v1",
            )
        }
        return MobileMoneyQuoteDto(
            id = "quote",
            action = "payout",
            feeMode = "sender_absorbs",
            walletId = "wallet",
            accountId = "account",
            network = "MTN",
            currency = CurrencyDto("UGX", "0"),
            recipientAmount = "1000",
            customerDebit = "1050",
            totalFees = totalFees,
            processingFee = "50",
            scheduleVerified = true,
            pricingScope = pricingScope,
            expiresAt = "2026-08-22T13:00:00Z",
            stepUp = MobileMoneyQuoteStepUpDto("mobile_money_payout", intent),
        )
    }

    private fun collectionQuote(intent: Map<String, String>): MobileMoneyQuoteDto =
        MobileMoneyQuoteDto(
            id = "quote",
            action = "collection",
            feeMode = "gross_up",
            walletId = "wallet",
            accountId = "account",
            network = "MTN",
            currency = CurrencyDto("UGX", "0"),
            requestedAmount = "1000",
            providerAmount = "1050",
            totalFees = "50",
            walletCredit = "1000",
            pricingScope = "customer_totals",
            expiresAt = "2026-08-22T13:00:00Z",
            stepUp = MobileMoneyQuoteStepUpDto("mobile_money_collection", intent),
        )

    private fun payoutIntent(): Map<String, String> = mapOf(
        "action" to "payout",
        "quote_id" to "quote",
        "wallet_id" to "wallet",
        "mobile_money_account_id" to "account",
        "network" to "MTN",
        "fee_mode" to "sender_absorbs",
        "recipient_amount" to "1000",
        "processing_fee" to "50",
        "customer_debit" to "1050",
        "currency" to "UGX",
    )

    private fun collectionIntent(): Map<String, String> = mapOf(
        "action" to "collection",
        "quote_id" to "quote",
        "wallet_id" to "wallet",
        "mobile_money_account_id" to "account",
        "network" to "MTN",
        "fee_mode" to "gross_up",
        "requested_amount" to "1000",
        "provider_amount" to "1050",
        "total_fees" to "50",
        "wallet_credit" to "1000",
        "currency" to "UGX",
    )

    private fun payoutIntentJson(): String = payoutIntent().entries.joinToString(
        prefix = "{",
        postfix = "}",
    ) { (key, value) -> "\"$key\":\"$value\"" }
}
