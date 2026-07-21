package com.kit.wallet.data.auth

import com.kit.wallet.data.remote.DeviceDto
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionSnapshot
import kotlinx.coroutines.flow.StateFlow

enum class AuthChallengeKind { PHONE_OTP, TWO_FACTOR }

data class PhoneAuthCapabilities(
    val serverPhoneOtp: Boolean,
)

data class PendingAuthChallenge(
    val id: String,
    val kind: AuthChallengeKind,
    val method: String? = null,
    val destination: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val resendAfterSeconds: Long? = null,
    /** Binds a multi-step challenge to the local authentication epoch that created it. */
    val expectedSession: SessionSnapshot? = null,
)

data class AuthenticatedUser(
    val id: String,
    val name: String,
    val email: String?,
    val phone: String?,
    val tag: String?,
    val paymentPinSet: Boolean,
    val profileSetupRequired: Boolean,
)

data class RegistrationResult(
    val email: String,
    val destination: String,
    val expiresAt: String,
)

data class AccountDeletionPreflight(
    val purpose: String,
    val intent: Map<String, Any?>,
    val confirmationText: String,
    val publicUrl: String,
    val deletedCategories: List<String>,
    val retainedCategories: List<String>,
    val closureRequirements: List<String>,
)

sealed interface AuthOutcome {
    data class Authenticated(val user: AuthenticatedUser) : AuthOutcome
    data class ChallengeRequired(val challenge: PendingAuthChallenge) : AuthOutcome
}

enum class RemoteRevocationState {
    CONFIRMED,
    ALREADY_REVOKED,
    UNCONFIRMED,
}

data class LogoutResult(
    val localSessionCleared: Boolean,
    val remoteRevocation: RemoteRevocationState,
    val retryRecommended: Boolean,
    val warning: String? = null,
)

interface AuthRepository {
    val signedIn: StateFlow<Boolean>
    val profileSetupState: StateFlow<ProfileSetupState>

    suspend fun loginWithEmail(email: String, password: String): AuthOutcome

    suspend fun registerWithEmail(
        name: String,
        tag: String,
        email: String,
        password: String,
        passwordConfirmation: String,
    ): RegistrationResult

    suspend fun verifyEmail(token: String)

    suspend fun resendEmailVerification(email: String): String

    suspend fun forgotPassword(email: String): String

    suspend fun resetPassword(token: String, password: String, passwordConfirmation: String)

    suspend fun requestPhoneOtp(phone: String): PendingAuthChallenge

    suspend fun phoneAuthCapabilities(): PhoneAuthCapabilities

    suspend fun verifyPhoneOtp(
        challenge: PendingAuthChallenge,
        phone: String,
        code: String,
    ): AuthOutcome

    suspend fun verifyTwoFactor(
        challenge: PendingAuthChallenge,
        code: String,
    ): AuthOutcome

    suspend fun refreshSession(): AuthOutcome.Authenticated

    suspend fun logout(allDevices: Boolean = false): LogoutResult

    suspend fun accountDeletionPreflight(): AccountDeletionPreflight

    suspend fun requestAccountDeletion(
        preflight: AccountDeletionPreflight,
        confirmation: String,
        paymentPin: String,
    )
}

interface DeviceIdentityProvider {
    fun registration(): DeviceRegistrationDto
}
