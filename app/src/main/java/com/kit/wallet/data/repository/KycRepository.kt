package com.kit.wallet.data.repository

import kotlinx.coroutines.flow.StateFlow

data class KycDocument(
    val type: String,
    val issuingCountry: String?,
    val status: String,
    val reasonCodes: List<String>,
)

data class KycStatus(
    val status: String,
    val caseReference: String?,
    val decisionCode: String?,
    val provider: String?,
    val providerStatus: String?,
    val verificationUrl: String?,
    val documents: List<KycDocument>,
)

interface KycRepository {
    val status: StateFlow<KycStatus?>

    suspend fun refresh(): KycStatus

    /** Records the user's explicit consent server-side and returns a backend-issued Didit URL. */
    suspend fun startVerification(consent: Boolean): String
}
