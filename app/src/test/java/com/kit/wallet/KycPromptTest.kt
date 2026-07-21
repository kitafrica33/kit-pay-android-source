package com.kit.wallet

import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.feature.home.shouldPromptForIdentityVerification
import com.kit.wallet.navigation.AppCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KycPromptTest {
    @Test
    fun `unverified users are prompted only when Didit KYC is enabled`() {
        val enabled = AppCapabilities(loaded = true, features = mapOf(KitFeature.KYC to true))
        val disabled = AppCapabilities(loaded = true, features = mapOf(KitFeature.KYC to false))

        assertTrue(shouldPromptForIdentityVerification(enabled, "unverified"))
        assertTrue(shouldPromptForIdentityVerification(enabled, "in_review"))
        assertFalse(shouldPromptForIdentityVerification(enabled, "approved"))
        assertFalse(shouldPromptForIdentityVerification(enabled, "VERIFIED"))
        assertFalse(shouldPromptForIdentityVerification(enabled, "KYC verified"))
        assertFalse(shouldPromptForIdentityVerification(disabled, "unverified"))
    }
}
