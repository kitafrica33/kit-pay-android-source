package com.kit.wallet

import com.kit.wallet.feature.home.shouldPromptForIdentityVerification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KycPromptTest {
    @Test
    fun `unverified users can discover identity verification before server activation`() {
        assertTrue(shouldPromptForIdentityVerification("unverified"))
        assertTrue(shouldPromptForIdentityVerification("in_review"))
        assertFalse(shouldPromptForIdentityVerification("approved"))
        assertFalse(shouldPromptForIdentityVerification("VERIFIED"))
        assertFalse(shouldPromptForIdentityVerification("KYC verified"))
    }
}
