package com.kit.wallet.feature.auth

/**
 * What the sign-in screen may offer beyond the phone number itself.
 *
 * Phone OTP is the only way an account is created: entering a number signs a returning
 * user in and enrols a new one, so the screen never offers "create an account" — not
 * even when a stale capability snapshot still advertises the retired email registration.
 * Email remains strictly a secondary sign-in for accounts that already have a password,
 * and its recovery affordances follow the `email_recovery` capability alone.
 *
 * Pure Kotlin, so the contract is pinned by unit tests rather than by screenshots.
 */
internal object AccountAccessPolicy {
    /** The affordances shown under the email sign-in form. */
    data class EmailAccessAffordances(
        /** "Forgot password?" — starts the emailed reset flow. */
        val forgotPassword: Boolean,
        /**
         * "Have a verification token?" — completes an emailed verification that was
         * already issued, so nobody holding one is stranded by the retired flow.
         */
        val verificationTokenEntry: Boolean,
    )

    fun emailAffordances(emailRecoveryAvailable: Boolean): EmailAccessAffordances =
        EmailAccessAffordances(
            forgotPassword = emailRecoveryAvailable,
            verificationTokenEntry = emailRecoveryAvailable,
        )

    /**
     * Whether any surface may offer account creation. Constant on purpose: the answer
     * does not depend on what the server advertises, because the retired flow must not
     * come back through a stale or hostile capability response.
     */
    @Suppress("UNUSED_PARAMETER")
    fun offersAccountCreation(emailRegistrationAdvertised: Boolean): Boolean = false
}
