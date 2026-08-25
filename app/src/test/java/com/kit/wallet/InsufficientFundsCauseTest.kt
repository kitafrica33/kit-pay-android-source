package com.kit.wallet

import com.kit.wallet.data.remote.KIT_INSUFFICIENT_FUNDS_CODE
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.isKitInsufficientFundsError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsufficientFundsCauseTest {
    @Test
    fun `classification walks past an outer api wrapper`() {
        val failure = KitWalletApiException(
            code = "PAYMENT_FAILED",
            message = "Payment failed",
            cause = IllegalStateException(
                "repository wrapper",
                KitWalletApiException(KIT_INSUFFICIENT_FUNDS_CODE, "Not enough money"),
            ),
        )

        assertTrue(failure.isKitInsufficientFundsError())
    }

    @Test
    fun `unrelated api causes remain unrelated`() {
        val failure = IllegalStateException(
            "repository wrapper",
            KitWalletApiException("WALLET_RESTRICTED", "Wallet restricted"),
        )

        assertFalse(failure.isKitInsufficientFundsError())
    }
}
