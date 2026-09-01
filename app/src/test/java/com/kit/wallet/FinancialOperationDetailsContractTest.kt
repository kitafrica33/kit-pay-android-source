package com.kit.wallet

import com.kit.wallet.data.remote.BankingOperationDto
import com.kit.wallet.data.remote.BankingOutboundPricingDto
import com.kit.wallet.data.remote.MobileMoneyOperationDto
import com.kit.wallet.data.repository.customerFeeAmountForPublicContract
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.reflect.full.declaredMemberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialOperationDetailsContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun `bank operation retains only authoritative customer outbound pricing`() {
        val operation = moshi.adapter(BankingOperationDto::class.java).fromJson(
            """{"id":"op","reference":"ref","type":"bank_transfer","direction":"debit","status":"completed","bank_id":"bank","beneficiary_id":"beneficiary","wallet_id":"wallet","amount":"20000","currency":{"code":"UGX","scale":"0"},"outbound_quote_id":"quote","pricing_scope":"customer_totals","outbound_pricing":{"fee_mode":"sender_absorbs","recipient_amount":"20000","processing_fee":"1000","total_fees":"1000","pricing_scope":"customer_totals","customer_debit":"21000"}}""",
        )

        assertNotNull(operation)
        assertEquals("1000", operation?.outboundPricing?.processingFee)
        assertEquals("1000", operation?.outboundPricing?.totalFees)
        assertEquals("customer_totals", operation?.outboundPricing?.pricingScope)
        assertEquals("21000", operation?.outboundPricing?.customerDebit)
    }

    @Test
    fun `legacy operation fee aliases decode only as ignored compatibility data`() {
        val operation = moshi.adapter(BankingOperationDto::class.java).fromJson(
            """{"id":"op","reference":"ref","type":"bank_transfer","direction":"debit","status":"completed","bank_id":"bank","beneficiary_id":"beneficiary","wallet_id":"wallet","amount":"20000","currency":{"code":"UGX","scale":"0"},"outbound_quote_id":"quote","outbound_pricing":{"fee_mode":"sender_absorbs","recipient_amount":"20000","processing_fee":"1000","provider_fee":"800","kit_fee":"200","provider_fee_cap":"800","maximum_provider_total":"20800","customer_debit":"21000","kit_debit":"0","schedule_version":"v1","actual_provider_fee":"750","actual_provider_total":"20750"}}""",
        )

        assertNotNull(operation)
        assertEquals("1000", operation?.outboundPricing?.processingFee)
        assertEquals("21000", operation?.outboundPricing?.customerDebit)
        assertNull(operation?.outboundPricing?.totalFees)
        assertNull(operation?.outboundPricing?.pricingScope)
    }

    @Test
    fun `mobile money operation retains aggregate collection settlement`() {
        val operation = moshi.adapter(MobileMoneyOperationDto::class.java).fromJson(
            """{"id":"op","reference":"ref","type":"mobile_money_collection","mobile_money_type":"collection","direction":"credit","status":"completed","bank_id":"bank","beneficiary_id":"account","wallet_id":"wallet","amount":"10000","fee_mode":"inclusive","requested_amount":"10000","provider_fee":"600","provider_fee_estimated":true,"platform_fee":"0","rounding_adjustment":"0","total_fees":"600","net_amount":"9400","pricing_scope":"customer_totals","currency":{"code":"UGX","scale":"0"},"network":{"id":"network","code":"MTN","name":"MTN Mobile Money","currency":{"code":"UGX","scale":"0"}}}""",
        )

        assertNotNull(operation)
        assertEquals("600", operation?.totalFees)
        assertEquals("9400", operation?.netAmount)
        assertEquals("customer_totals", operation?.pricingScope)
    }

    @Test
    fun `customer fee resolver prefers total and rejects mismatches or unsafe scope`() {
        assertEquals(
            "1000.00",
            customerFeeAmountForPublicContract("1000.00", "1000", "customer_totals"),
        )
        assertEquals("1000", customerFeeAmountForPublicContract(null, "1000", null))
        assertTrue(
            runCatching {
                customerFeeAmountForPublicContract("1001", "1000", "customer_totals")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                customerFeeAmountForPublicContract("1000", "1000", "internal_breakdown")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                customerFeeAmountForPublicContract(null, "1000", "customer_totals")
            }.isFailure,
        )
    }

    @Test
    fun `operation DTOs have no institutional fee properties`() {
        val forbidden = setOf(
            "providerFee", "kitFee", "providerFeeCap", "maximumProviderTotal", "kitDebit",
            "actualProviderFee", "actualProviderTotal", "platformFee", "roundingAdjustment",
            "providerFeeEstimated", "scheduleVersion",
        )
        listOf(
            BankingOutboundPricingDto::class,
            BankingOperationDto::class,
            MobileMoneyOperationDto::class,
        ).forEach { type ->
            assertTrue(type.declaredMemberProperties.map { it.name }.toSet().intersect(forbidden).isEmpty())
        }
    }
}
