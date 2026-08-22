package com.kit.wallet

import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.PaymentRequestDto
import com.kit.wallet.data.repository.requirePayablePaymentRequest
import com.kit.wallet.data.repository.validateCreatedPaymentRequest
import com.kit.wallet.data.repository.validatePaidPaymentRequest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaymentRequestValidationTest {
    @Test fun `accepts an exactly matching pending create response`() {
        validateCreatedPaymentRequest(
            created = created(),
            destinationWalletId = WALLET_ID,
            requestedFromUserId = PEER_ID.uppercase(),
            amount = "150.00",
            currencyCode = "ugx",
            currencyScale = 2,
        )
    }

    @Test fun `rejects create responses that mutate the submitted request`() {
        val mismatches = listOf(
            created(id = "not-a-uuid"),
            created(type = "payment_link"),
            created(status = "paid"),
            created(destinationWalletId = "other-wallet"),
            created(requestedFromUserId = "22222222-2222-4222-8222-222222222222"),
            created(requestedFromUserId = null),
            created(amount = "150.01"),
            created(currency = CurrencyDto("KES", "2")),
            created(currency = CurrencyDto("UGX", "0")),
        )
        mismatches.forEach { response ->
            assertThrows(IllegalStateException::class.java) {
                validateCreatedPaymentRequest(
                    created = response,
                    destinationWalletId = WALLET_ID,
                    requestedFromUserId = PEER_ID,
                    amount = "150.00",
                    currencyCode = "UGX",
                    currencyScale = 2,
                )
            }
        }
    }

    @Test fun `accepts a settled pay response with its transaction`() {
        validatePaidPaymentRequest(
            paid = created(status = "paid", walletTransactionId = "tx-1"),
            requestId = REQUEST_ID.uppercase(),
        )
    }

    @Test fun `rejects pay responses without settlement evidence`() {
        val mismatches = listOf(
            created(status = "paid", walletTransactionId = "tx-1", id = "33333333-3333-4333-8333-333333333333"),
            created(status = "pending", walletTransactionId = "tx-1"),
            created(status = "paid", walletTransactionId = null),
            created(status = "paid", walletTransactionId = " "),
            created(status = "paid", walletTransactionId = "tx-1", type = "payment_link"),
        )
        mismatches.forEach { response ->
            assertThrows(IllegalStateException::class.java) {
                validatePaidPaymentRequest(paid = response, requestId = REQUEST_ID)
            }
        }
    }

    @Test fun `allows paying only a matching pending unexpired request`() {
        requirePayablePaymentRequest(
            records = listOf(created(), created(id = "other")),
            requestId = REQUEST_ID.uppercase(),
            amountMinor = 15_000,
            currencyCode = "ugx",
            currencyScale = 2,
            now = NOW,
        )
    }

    @Test fun `refuses settled stale mutated or missing requests with clear reasons`() {
        val refusals = mapOf(
            listOf(created(status = "paid")) to "already paid",
            listOf(created(status = "cancelled")) to "cancelled",
            listOf(created(status = "expired")) to "expired",
            listOf(created(expiresAt = "2026-08-22T11:59:59Z")) to "expired",
            listOf(created(amount = "150.01")) to "no longer matches",
            listOf(created(currency = CurrencyDto("KES", "2"))) to "no longer matches",
            listOf(created(id = "33333333-3333-4333-8333-333333333333")) to "no longer available",
            emptyList<PaymentRequestDto>() to "no longer available",
        )
        refusals.forEach { (records, expectedReason) ->
            val refusal = assertThrows(IllegalStateException::class.java) {
                requirePayablePaymentRequest(
                    records = records,
                    requestId = REQUEST_ID,
                    amountMinor = 15_000,
                    currencyCode = "UGX",
                    currencyScale = 2,
                    now = NOW,
                )
            }
            assertEquals(
                "refusal for $records",
                true,
                refusal.message.orEmpty().contains(expectedReason),
            )
        }
    }

    private fun created(
        id: String = REQUEST_ID,
        type: String = "payment_request",
        status: String = "pending",
        destinationWalletId: String = WALLET_ID,
        requestedFromUserId: String? = PEER_ID,
        amount: String = "150.00",
        currency: CurrencyDto = CurrencyDto("UGX", "2"),
        walletTransactionId: String? = null,
        expiresAt: String? = null,
    ) = PaymentRequestDto(
        id = id,
        type = type,
        status = status,
        destinationWalletId = destinationWalletId,
        requestedFromUserId = requestedFromUserId,
        amount = amount,
        currency = currency,
        walletTransactionId = walletTransactionId,
        expiresAt = expiresAt,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-22T12:00:00Z")
        const val REQUEST_ID = "019f8c6f-cc57-720c-9a55-000000000001"
        const val WALLET_ID = "wallet-1"
        const val PEER_ID = "11111111-1111-4111-8111-111111111111"
    }
}
