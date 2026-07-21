package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateStepUpChallengeRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.VerifyStepUpChallengeRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentAuthorizer @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
) {
    suspend fun authorize(
        purpose: String,
        intent: Map<String, Any?>,
        paymentPin: String,
    ): String {
        require(paymentPin.matches(Regex("^[0-9]{4}$"))) { "Enter the four-digit wallet PIN" }
        val challenge = apiCalls.execute {
            api.createStepUpChallenge(CreateStepUpChallengeRequest(purpose, intent))
        }
        check("pin" in challenge.methods.orEmpty()) { "Wallet PIN authorization is not enabled" }
        return apiCalls.execute {
            api.verifyStepUpChallenge(challenge.id, VerifyStepUpChallengeRequest(paymentPin))
        }.stepUpToken
    }
}
