package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateStepUpChallengeRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.VerifyStepUpChallengeRequest
import com.kit.wallet.data.remote.VerifyBiometricStepUpRequest
import com.kit.wallet.data.auth.BiometricPaymentApprover
import com.kit.wallet.data.session.SessionStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class PaymentAuthorizer @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore? = null,
    private val biometricApprovals: BiometricPaymentApprover? = null,
    private val moshi: Moshi = DEFAULT_MOSHI,
) {
    suspend fun authorize(
        purpose: String,
        intent: Map<String, Any?>,
        paymentPin: String,
        biometricReason: String = "Confirm this Kit Pay payment",
    ): String {
        val challenge = apiCalls.execute {
            api.createStepUpChallenge(stepUpChallengeBody(purpose, intent))
        }
        val methods = challenge.methods.orEmpty()
        val session = sessions?.current()
        if (paymentPin.isEmpty() && "biometric_signature" in methods && session?.accountId != null &&
            biometricApprovals?.availableFor(session.accountId) == true
        ) {
            val signature = biometricApprovals.sign(
                session.accountId,
                challenge.signingPayload,
                biometricReason,
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

    /**
     * Challenge intent hashing distinguishes an explicit JSON null from an omitted member.
     * Serialize nulls for this one request only so, for example, a reversal without a reason is
     * hashed as `{reason:null}` by both Android and the server without changing any other API
     * request's JSON behaviour.
     */
    private fun stepUpChallengeBody(
        purpose: String,
        intent: Map<String, Any?>,
    ): RequestBody = moshi.adapter(CreateStepUpChallengeRequest::class.java)
        .serializeNulls()
        .toJson(CreateStepUpChallengeRequest(purpose, intent))
        .toRequestBody(JSON_MEDIA_TYPE)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        val DEFAULT_MOSHI: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }
}
