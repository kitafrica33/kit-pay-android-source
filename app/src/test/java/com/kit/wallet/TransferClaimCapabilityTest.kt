package com.kit.wallet

import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.remote.claimableTransfersAvailable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferClaimCapabilityTest {
    @Test
    fun `claim actions require all three exact capability flags`() {
        val enabled = capabilities(
            mapOf(
                KitFeature.WALLETS to true,
                KitFeature.INTERNAL_TRANSFERS to true,
                KitFeature.CLAIMABLE_TRANSFERS to true,
            ),
        )

        assertTrue(enabled.claimableTransfersAvailable())
        for (missing in listOf(
            KitFeature.WALLETS,
            KitFeature.INTERNAL_TRANSFERS,
            KitFeature.CLAIMABLE_TRANSFERS,
        )) {
            assertFalse(
                enabled.copy(features = enabled.features.orEmpty() - missing)
                    .claimableTransfersAvailable(),
            )
            assertFalse(
                enabled.copy(features = enabled.features.orEmpty() + (missing to false))
                    .claimableTransfersAvailable(),
            )
            assertFalse(
                enabled.copy(features = enabled.features.orEmpty() + (missing to null))
                    .claimableTransfersAvailable(),
            )
        }
    }

    @Test
    fun `missing feature map fails closed`() {
        assertFalse(capabilities(null).claimableTransfersAvailable())
    }

    private fun capabilities(features: Map<String, Boolean?>?) = CapabilitiesDto(
        currency = CurrencyDto("UGX", "2"),
        features = features,
    )
}
