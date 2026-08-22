package com.kit.wallet

import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.ProviderOperationDto
import com.kit.wallet.data.remote.ProviderQuoteDto
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.data.repository.validateProviderOperationResponse
import com.kit.wallet.data.repository.validateProviderQuote
import com.kit.wallet.data.session.SessionTokens
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderQuoteValidationTest {
    @Test fun `accepts a consistent unexpired quote for the requested product`() {
        validateProviderQuote(
            quote = quote(),
            productId = PRODUCT_ID,
            serviceType = "bill",
            amount = "150.00",
            currencyCode = "ugx",
            currencyScale = 2,
            now = NOW,
        )
    }

    @Test fun `rejects quotes that mutate or contradict the request`() {
        val mismatches = listOf(
            quote(productId = "other-product"),
            quote(serviceType = "airtime"),
            quote(amount = "150.01"),
            quote(fee = "-1.00"),
            quote(total = "151.00"),
            quote(currency = CurrencyDto("KES", "2")),
            quote(currency = CurrencyDto("UGX", "0")),
            quote(expiresAt = "2026-08-22T11:59:59Z"),
            quote(expiresAt = "not-a-time"),
        )
        mismatches.forEach { response ->
            assertThrows(IllegalStateException::class.java) {
                validateProviderQuote(
                    quote = response,
                    productId = PRODUCT_ID,
                    serviceType = "bill",
                    amount = "150.00",
                    currencyCode = "UGX",
                    currencyScale = 2,
                    now = NOW,
                )
            }
        }
    }

    @Test fun `accepts an operation that exactly matches the approved quote`() {
        validateProviderOperationResponse(
            operation = operation(),
            quote = approvedQuote(),
            walletId = WALLET_ID,
            clientReference = CLIENT_REFERENCE,
        )
    }

    @Test fun `rejects operations that differ from the approved quote`() {
        val mismatches = listOf(
            operation(type = "airtime_purchase"),
            operation(walletId = "other-wallet"),
            operation(productId = "other-product"),
            operation(clientReference = "other-reference"),
            operation(amount = "150.01"),
            operation(fee = "0.00"),
            operation(total = "160.00"),
            operation(currency = CurrencyDto("KES", "2")),
        )
        mismatches.forEach { response ->
            assertThrows(IllegalStateException::class.java) {
                validateProviderOperationResponse(
                    operation = response,
                    quote = approvedQuote(),
                    walletId = WALLET_ID,
                    clientReference = CLIENT_REFERENCE,
                )
            }
        }
    }

    private fun quote(
        productId: String = PRODUCT_ID,
        serviceType: String = "bill",
        amount: String = "150.00",
        fee: String = "3.50",
        total: String = "153.50",
        currency: CurrencyDto = CurrencyDto("UGX", "2"),
        expiresAt: String = "2026-08-22T12:10:00Z",
    ) = ProviderQuoteDto(
        id = "quote-1",
        productId = productId,
        providerCode = "umeme",
        serviceType = serviceType,
        accountDisplay = "MOTOKA JOHN",
        amount = amount,
        fee = fee,
        total = total,
        currency = currency,
        expiresAt = expiresAt,
    )

    private fun approvedQuote() = FinancialOperationQuote(
        quoteId = "quote-1",
        operationType = "bill_payment",
        destinationId = "meter-1",
        amountMinor = 15_000,
        recipientAmountMinor = 15_000,
        feesMinor = 350,
        customerDebitMinor = 15_350,
        currencyCode = "UGX",
        currencyScale = 2,
        feeMode = "sender_absorbs",
        expiresAt = "2026-08-22T12:10:00Z",
        feesKnown = true,
        authorizationPurpose = "bill_payment",
        authorizationIntent = linkedMapOf(
            "quote_id" to "quote-1",
            "wallet_id" to WALLET_ID,
            "client_reference" to CLIENT_REFERENCE,
        ),
        sessionFence = SessionTokens("access", "refresh", "session").fence(),
        destinationName = "Umeme",
        accountDisplay = "MOTOKA JOHN",
        productId = PRODUCT_ID,
    )

    private fun operation(
        type: String = "bill_payment",
        walletId: String = WALLET_ID,
        productId: String = PRODUCT_ID,
        clientReference: String? = CLIENT_REFERENCE,
        amount: String = "150.00",
        fee: String = "3.50",
        total: String = "153.50",
        currency: CurrencyDto = CurrencyDto("UGX", "2"),
    ) = ProviderOperationDto(
        id = "operation-1",
        type = type,
        status = "processing",
        walletId = walletId,
        providerCode = "umeme",
        productId = productId,
        productName = "Umeme",
        accountDisplay = "MOTOKA JOHN",
        amount = amount,
        fee = fee,
        total = total,
        currency = currency,
        clientReference = clientReference,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-22T12:00:00Z")
        const val PRODUCT_ID = "product-umeme"
        const val WALLET_ID = "wallet-1"
        const val CLIENT_REFERENCE = "android-provider-test"
    }
}
