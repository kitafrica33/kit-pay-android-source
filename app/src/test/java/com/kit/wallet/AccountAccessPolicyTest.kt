package com.kit.wallet

import com.kit.wallet.feature.auth.AccountAccessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in screen's affordance contract. Phone OTP is the only way an account is
 * created; email is strictly a secondary sign-in for accounts that already hold a
 * password, with its recovery affordances following the `email_recovery` capability.
 */
class AccountAccessPolicyTest {
    @Test
    fun `account creation is never offered, whatever the server advertises`() {
        // The retired email-registration flow must not come back through a stale or
        // hostile capability response: the answer is a constant, not a lookup.
        assertFalse(AccountAccessPolicy.offersAccountCreation(emailRegistrationAdvertised = true))
        assertFalse(AccountAccessPolicy.offersAccountCreation(emailRegistrationAdvertised = false))
    }

    @Test
    fun `recovery affordances follow the email recovery capability alone`() {
        val available = AccountAccessPolicy.emailAffordances(emailRecoveryAvailable = true)

        assertTrue(available.forgotPassword)
        // An already-issued verification token must still have somewhere to be completed.
        assertTrue(available.verificationTokenEntry)

        val withdrawn = AccountAccessPolicy.emailAffordances(emailRecoveryAvailable = false)

        assertFalse(withdrawn.forgotPassword)
        assertFalse(withdrawn.verificationTokenEntry)
    }
}
