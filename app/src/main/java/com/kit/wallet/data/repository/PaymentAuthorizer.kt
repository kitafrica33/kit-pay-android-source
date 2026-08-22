package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateStepUpChallengeRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.VerifyStepUpChallengeRequest
import com.kit.wallet.data.remote.VerifyBiometricStepUpRequest
import com.kit.wallet.data.auth.BiometricPaymentApprover
import com.kit.wallet.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentAuthorizer @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore? = null,
    private val biometricApprovals: BiometricPaymentApprover? = null,
) {
    suspend fun authorize(
        purpose: String,
        intent: Map<String, Any?>,
        paymentPin: String,
    ): String {
        val challenge = apiCalls.execute {
            api.createStepUpChallenge(CreateStepUpChallengeRequest(purpose, intent))
        }
        val methods = challenge.methods.orEmpty()
        val session = sessions?.current()
        if ("biometric_signature" in methods && session?.accountId != null &&
            biometricApprovals?.availableFor(session.accountId) == true
        ) {
            val signature = biometricApprovals.sign(
                session.accountId,
                challenge.signingPayload,
                "Confirm this Kit Pay payment",
            )
            check(sessions.current()?.fence() == session.fence()) {
                "The signed-in account changed during approval"
            }
            return apiCalls.execute {
                api.verifyBiometricStepUpChallenge(
                    challenge.id,
                    VerifyBiometricStepUpRequest(challenge.nonce, signature),
                )
            }.also {
                check(it.method.equals("biometric_signature", ignoreCase = true)) {
                    "The server did not confirm biometric approval"
                }
            }.stepUpToken
        }
        check("pin" in methods) { "Wallet PIN authorization is not enabled" }
        require(paymentPin.matches(Regex("^[0-9]{4}$"))) {
            "Enter the four-digit wallet PIN"
        }
        return apiCalls.execute {
            api.verifyStepUpChallenge(challenge.id, VerifyStepUpChallengeRequest(paymentPin))
        }.stepUpToken
    }
}
