package com.kit.wallet

import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.GroupPaymentRequestsProtocolDto
import com.kit.wallet.data.remote.PaymentProtocolsDto
import com.kit.wallet.data.remote.PaymentProtocolsDtoAdapter
import com.kit.wallet.data.remote.ProtocolsDto
import com.kit.wallet.data.remote.ScheduledChatPaymentsProtocolDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentsProtocolDto
import com.kit.wallet.data.remote.groupPaymentRequestsAvailable
import com.kit.wallet.data.remote.scheduledChatPaymentsAvailable
import com.kit.wallet.data.remote.scheduledGroupPaymentsAvailable
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSchedulingCapabilityTest {
    @Test
    fun `all additive payment gates require their exact Android 46 handshake`() {
        val capabilities = CapabilitiesDto(
            currency = CurrencyDto("UGX", "0"),
            features = mapOf(
                "wallets" to true,
                "internal_transfers" to true,
                "claimable_transfers" to true,
                "group_payments" to true,
                "group_payment_requests_v1" to true,
                "scheduled_payments" to true,
                "scheduled_chat_payments_v1" to true,
                "scheduled_group_payments_v1" to true,
            ),
            protocols = ProtocolsDto(
                payments = PaymentProtocolsDto(
                    groupPaymentRequests = GroupPaymentRequestsProtocolDto(
                        "v1", true, true, 10_000, "0.2.35", 46,
                    ),
                    scheduledChatPayments = ScheduledChatPaymentsProtocolDto(
                        "v1", true, "0.2.35", 46,
                    ),
                    scheduledGroupPayments = ScheduledGroupPaymentsProtocolDto(
                        "v1", true, "0.2.35", 46, 60, 31_536_000,
                    ),
                ),
            ),
        )

        assertTrue(capabilities.groupPaymentRequestsAvailable())
        assertTrue(capabilities.scheduledChatPaymentsAvailable())
        assertTrue(capabilities.scheduledGroupPaymentsAvailable())
        assertFalse(
            capabilities.copy(
                protocols = capabilities.protocols?.copy(
                    payments = capabilities.protocols.payments?.copy(
                        groupPaymentRequests = capabilities.protocols.payments
                            ?.groupPaymentRequests?.copy(minimumAndroidVersionCode = 45),
                    ),
                ),
            ).groupPaymentRequestsAvailable(),
        )
    }

    @Test
    fun `malformed payment handshake fails closed without breaking capabilities`() {
        val moshi = Moshi.Builder()
            .add(PaymentProtocolsDtoAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
        val decoded = requireNotNull(
            moshi.adapter(CapabilitiesDto::class.java).fromJson(
                """{"currency":{"code":"UGX","scale":"0"},"features":{"wallets":true,"internal_transfers":true,"group_payment_requests_v1":true},"protocols":{"payments":{"group_payment_requests":{"version":"v1","ready":"yes","partial_contributions":true,"progress_basis_points_max":10000,"minimum_android_version":"0.2.35","minimum_android_version_code":46}}}}""",
            ),
        )

        assertFalse(decoded.groupPaymentRequestsAvailable())
        assertFalse(decoded.scheduledChatPaymentsAvailable())
        assertFalse(decoded.scheduledGroupPaymentsAvailable())
    }
}
