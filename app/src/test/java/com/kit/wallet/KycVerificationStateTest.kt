package com.kit.wallet

import com.kit.wallet.data.repository.KycStatus
import com.kit.wallet.data.repository.KycVerificationState
import com.kit.wallet.data.repository.kycVerificationStateOf
import com.kit.wallet.feature.home.identityPromptFor
import com.kit.wallet.feature.settings.identityVerificationPresentation
import com.kit.wallet.feature.settings.reviewReason
import com.kit.wallet.feature.settings.verificationSummaryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KycVerificationStateTest {
    @Test
    fun `every vocabulary the server speaks is understood`() {
        // The account's own words.
        listOf("verified", "unverified", "pending").forEach {
            assertTrue("account status $it", kycVerificationStateOf(it) != KycVerificationState.UNKNOWN)
        }
        // The per-device check's words, which are not the account's.
        listOf("not_required", "required", "pending", "review", "failed", "verified").forEach {
            assertTrue("device status $it", kycVerificationStateOf(it) != KycVerificationState.UNKNOWN)
        }
        // The compliance case's words, which are neither.
        listOf(
            "draft", "pending", "in_review", "needs_information", "approved", "rejected",
        ).forEach {
            assertTrue("case status $it", kycVerificationStateOf(it) != KycVerificationState.UNKNOWN)
        }
    }

    @Test
    fun `the device check's word for review is not mistaken for a fresh start`() {
        // This exact value is what a verified account under manual review was served, and reading
        // it as "nothing submitted" is what put the user through four duplicate sessions.
        assertEquals(KycVerificationState.IN_REVIEW, kycVerificationStateOf("review"))
        assertEquals(KycVerificationState.IN_REVIEW, kycVerificationStateOf("in_review"))
        assertEquals(KycVerificationState.IN_REVIEW, kycVerificationStateOf("In Review"))
    }

    @Test
    fun `case and whitespace never change the answer`() {
        listOf("verified", " verified ", "VERIFIED", "Verified\n").forEach {
            assertEquals(KycVerificationState.VERIFIED, kycVerificationStateOf(it))
        }
        assertEquals(KycVerificationState.VERIFIED, kycVerificationStateOf("KYC verified"))
        assertEquals(KycVerificationState.IN_REVIEW, kycVerificationStateOf("KYC pending"))
        assertEquals(KycVerificationState.NOT_STARTED, kycVerificationStateOf("KYC not started"))
        assertEquals(KycVerificationState.ACTION_NEEDED, kycVerificationStateOf("KYC needs attention"))
    }

    @Test
    fun `an unrecognised word is never read as unverified`() {
        listOf("quantum_reviewed", "", "   ", null).forEach {
            assertEquals(KycVerificationState.UNKNOWN, kycVerificationStateOf(it))
        }
        // And nothing downstream turns that into an accusation or an invitation to start over.
        assertNull(identityPromptFor("something new the server invented"))
        assertFalse(identityVerificationPresentation("something new").verified)
        assertFalse(
            identityVerificationPresentation("something new").title.contains("Verify your identity"),
        )
    }

    @Test
    fun `a verified account under a device check is told which is which`() {
        // Exactly what the server returns for a verified account on a device that has not yet
        // proved itself: the top-level status is the device's, not the account's.
        val status = kycStatus(status = "review", accountStatus = "verified", deviceCheckRequired = true)
        assertEquals(KycVerificationState.VERIFIED, status.accountState)
        assertEquals(KycVerificationState.IN_REVIEW, status.deviceState)
        assertEquals("Verified • confirming this device", verificationSummaryLabel(status))
    }

    @Test
    fun `a verified account with nothing outstanding reads as simply verified`() {
        val status = kycStatus(status = "verified", accountStatus = "verified")
        assertEquals(KycVerificationState.VERIFIED, status.accountState)
        assertEquals(KycVerificationState.VERIFIED, status.deviceState)
        assertEquals("Verified", verificationSummaryLabel(status))
        assertNull(identityPromptFor("KYC verified"))
    }

    @Test
    fun `a server that reports no account status behaves as it always did`() {
        val status = kycStatus(status = "approved", accountStatus = null)
        assertEquals(KycVerificationState.VERIFIED, status.accountState)
        assertEquals(KycVerificationState.VERIFIED, status.deviceState)
    }

    @Test
    fun `a check in review invites patience rather than a second submission`() {
        val prompt = requireNotNull(identityPromptFor("KYC pending"))
        assertEquals("Verification in review", prompt.title)
        assertFalse(prompt.detail.contains("Didit"))
        assertEquals(
            "Didit verification in review",
            identityVerificationPresentation("KYC pending").title,
        )
    }

    @Test
    fun `a reviewer's decision code is explained or withheld, never shown raw`() {
        assertEquals(
            "The name on your document needs a manual check against your Kit Pay profile.",
            reviewReason("DIDIT_IDENTITY_NAME_REVIEW_REQUIRED"),
        )
        assertNull(reviewReason("SOME_INTERNAL_CODE"))
        assertNull(reviewReason(null))
    }

    private fun kycStatus(
        status: String,
        accountStatus: String? = null,
        deviceCheckRequired: Boolean = false,
    ) = KycStatus(
        status = status,
        accountStatus = accountStatus,
        deviceCheckRequired = deviceCheckRequired,
        caseReference = null,
        caseStatus = null,
        decisionCode = null,
        provider = "didit",
        providerStatus = null,
        verificationUrl = null,
        documents = emptyList(),
    )
}
