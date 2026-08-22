package com.kit.wallet

import com.kit.wallet.data.remote.BankingOperationDto
import com.kit.wallet.data.remote.MobileMoneyOperationDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FinancialOperationDetailsContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test fun `bank operation retains authoritative outbound pricing`() {
        val operation = moshi.adapter(BankingOperationDto::class.java).fromJson(
            """{"id":"op","reference":"ref","type":"bank_transfer","direction":"debit","status":"completed","bank_id":"bank","beneficiary_id":"beneficiary","wallet_id":"wallet","amount":"20000","currency":{"code":"UGX","scale":"0"},"outbound_quote_id":"quote","outbound_pricing":{"fee_mode":"sender_absorbs","recipient_amount":"20000","processing_fee":"1000","provider_fee":"800","kit_fee":"200","provider_fee_cap":"800","maximum_provider_total":"20800","customer_debit":"21000","kit_debit":"0","schedule_version":"v1","actual_provider_fee":"750","actual_provider_total":"20750"}}""",
        )

        assertNotNull(operation)
        assertEquals("1000", operation?.outboundPricing?.processingFee)
        assertEquals("21000", operation?.outboundPricing?.customerDebit)
        assertEquals("750", operation?.outboundPricing?.actualProviderFee)
    }

    @Test fun `mobile money operation retains collection fee settlement`() {
        val operation = moshi.adapter(MobileMoneyOperationDto::class.java).fromJson(
            """{"id":"op","reference":"ref","type":"mobile_money_collection","mobile_money_type":"collection","direction":"credit","status":"completed","bank_id":"bank","beneficiary_id":"account","wallet_id":"wallet","amount":"10000","fee_mode":"inclusive","requested_amount":"10000","provider_fee":"500","provider_fee_estimated":true,"platform_fee":"100","rounding_adjustment":"0","total_fees":"600","net_amount":"9400","currency":{"code":"UGX","scale":"0"},"network":{"id":"network","code":"MTN","name":"MTN Mobile Money","currency":{"code":"UGX","scale":"0"}}}""",
        )

        assertNotNull(operation)
        assertEquals("600", operation?.totalFees)
        assertEquals("9400", operation?.netAmount)
        assertEquals(true, operation?.providerFeeEstimated)
    }
}
