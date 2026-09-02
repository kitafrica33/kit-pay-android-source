package com.kit.wallet

import com.kit.wallet.data.remote.ContributeGroupPaymentRequest
import com.kit.wallet.data.remote.CreateGroupPaymentRecipient
import com.kit.wallet.data.remote.CreateGroupPaymentRequest
import com.kit.wallet.data.remote.CreatePaymentRequestDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.WalletTransferRequest
import com.kit.wallet.data.session.SessionFence
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Tag

class FinancialRecoveryApiContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun `recovery and exact read methods use final authenticated routes`() {
        assertRecoveryPost(
            "recoverTransfer",
            "api/kit-wallet/v1/wallets/{walletId}/transfers/recovery",
        )
        assertRecoveryPost(
            "recoverPaymentRequestCreation",
            "api/kit-wallet/v1/payments/requests/recovery",
        )
        assertRecoveryPost(
            "recoverGroupPayment",
            "api/kit-wallet/v1/conversations/{conversationId}/group-payments/recovery",
        )
        assertRecoveryPost(
            "recoverGroupPaymentRequestContribution",
            "api/kit-wallet/v1/group-payment-requests/{requestId}/contributions/recovery",
        )

        val exactRequest = apiMethod("paymentRequest")
        assertEquals(
            "api/kit-wallet/v1/payments/requests/{requestId}",
            checkNotNull(exactRequest.getAnnotation(GET::class.java)).value,
        )
        assertTrue(exactRequest.hasOwnerTag())
    }

    @Test
    fun `recovery bodies reproduce the original mutation fields without step up credentials`() {
        val transfer = json(
            WalletTransferRequest::class.java,
            WalletTransferRequest(WALLET_TWO, "25.00", "Lunch"),
        )
        assertTrue(transfer.contains("\"destination_wallet_id\":\"$WALLET_TWO\""))
        assertTrue(transfer.contains("\"amount\":\"25.00\""))
        assertTrue(transfer.contains("\"note\":\"Lunch\""))

        val paymentRequest = json(
            CreatePaymentRequestDto::class.java,
            CreatePaymentRequestDto(WALLET_ONE, USER_ONE, "25.00", "Lunch"),
        )
        assertTrue(paymentRequest.contains("\"destination_wallet_id\":\"$WALLET_ONE\""))
        assertTrue(paymentRequest.contains("\"requested_from_user_id\":\"$USER_ONE\""))

        val groupPayment = json(
            CreateGroupPaymentRequest::class.java,
            CreateGroupPaymentRequest(
                sourceWalletId = WALLET_ONE,
                splitMode = "custom",
                audience = "selected",
                note = "Dinner",
                recipients = listOf(CreateGroupPaymentRecipient(USER_ONE, "25.00")),
            ),
        )
        assertTrue(groupPayment.contains("\"source_wallet_id\":\"$WALLET_ONE\""))
        assertTrue(groupPayment.contains("\"split_mode\":\"custom\""))
        assertTrue(groupPayment.contains("\"recipients\":["))

        val contribution = json(
            ContributeGroupPaymentRequest::class.java,
            ContributeGroupPaymentRequest(WALLET_ONE, "25.00"),
        )
        assertTrue(contribution.contains("\"source_wallet_id\":\"$WALLET_ONE\""))
        assertTrue(contribution.contains("\"amount\":\"25.00\""))

        RECOVERY_METHODS.forEach { name ->
            val method = apiMethod(name)
            assertTrue(method.parameterAnnotations.flatten().any {
                it is Header && it.value == "Idempotency-Key"
            })
            assertTrue(method.parameterAnnotations.flatten().any { it is Body })
            assertTrue(method.hasOwnerTag())
            assertFalse(method.parameterAnnotations.flatten().any {
                it is Header && it.value == "X-Kit-Wallet-Step-Up"
            })
        }
    }

    private fun assertRecoveryPost(name: String, route: String) {
        val method = apiMethod(name)
        assertEquals(route, checkNotNull(method.getAnnotation(POST::class.java)).value)
        assertTrue(method.hasOwnerTag())
    }

    private fun apiMethod(name: String): Method = KitWalletApi::class.java.methods.single {
        it.name == name
    }

    private fun Method.hasOwnerTag(): Boolean = parameters.any { parameter ->
        parameter.type == SessionFence::class.java && parameter.isAnnotationPresent(Tag::class.java)
    }

    private fun <T> json(type: Class<T>, value: T): String = moshi.adapter(type).toJson(value)

    private companion object {
        val RECOVERY_METHODS = listOf(
            "recoverTransfer",
            "recoverPaymentRequestCreation",
            "recoverGroupPayment",
            "recoverGroupPaymentRequestContribution",
        )
        const val USER_ONE = "10000000-0000-4000-8000-000000000001"
        const val WALLET_ONE = "20000000-0000-4000-8000-000000000001"
        const val WALLET_TWO = "20000000-0000-4000-8000-000000000002"
    }
}
