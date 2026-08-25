package com.kit.wallet.data.repository

import kotlinx.coroutines.flow.StateFlow

data class KycDocument(
    val type: String,
    val issuingCountry: String?,
    val status: String,
    val reasonCodes: List<String>,
)

data class KycStatus(
    /** The status the server wants acted on — the device check when one is outstanding. */
    val status: String,
    /**
     * The account's identity standing on its own, or null on a server that does not report it.
     *
     * Kept separate from [status] because they answer different questions, and conflating them is
     * what used to send an already-verified user back through identity verification on a device
     * that had simply not yet proved itself.
     */
    val accountStatus: String?,
    /** Whether this device still has an identity check outstanding. */
    val deviceCheckRequired: Boolean,
    val caseReference: String?,
    val caseStatus: String?,
    val decisionCode: String?,
    val provider: String?,
    val providerStatus: String?,
    val verificationUrl: String?,
    val documents: List<KycDocument>,
) {
    /** What the account itself has proven, which is the answer to "is this person verified?". */
    val accountState: KycVerificationState
        get() = kycVerificationStateOf(accountStatus ?: status)

    /**
     * What still has to happen on *this* device before it can act.
     *
     * Falls back to the account's own standing when the server reports no device check at all, so
     * a build talking to an older server behaves exactly as it did before.
     */
    val deviceState: KycVerificationState
        get() = when {
            !deviceCheckRequired && accountStatus != null -> accountState
            else -> kycVerificationStateOf(status)
        }

    /**
     * Whether a live provider session is waiting for the user to finish it.
     *
     * The server only publishes a URL while the session is genuinely resumable, so its presence —
     * not a status string — is the reliable signal that "continue" is the right offer.
     */
    val resumable: Boolean get() = verificationUrl != null
}

interface KycRepository {
    val status: StateFlow<KycStatus?>

    suspend fun refresh(): KycStatus

    /** Records the user's explicit consent server-side and returns a backend-issued Didit URL. */
    suspend fun startVerification(consent: Boolean): String
}
