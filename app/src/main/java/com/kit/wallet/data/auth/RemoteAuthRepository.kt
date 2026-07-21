package com.kit.wallet.data.auth

import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.remote.AuthChallengeDto
import com.kit.wallet.data.remote.AuthResultDto
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.EmailAddressRequest
import com.kit.wallet.data.remote.EmailLoginRequest
import com.kit.wallet.data.remote.EmailRegistrationRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.LogoutRequest
import com.kit.wallet.data.remote.PhoneOtpRequest
import com.kit.wallet.data.remote.PhoneOtpVerifyRequest
import com.kit.wallet.data.remote.RequestAccountDeletionDto
import com.kit.wallet.data.remote.RefreshSessionRequest
import com.kit.wallet.data.remote.ResetPasswordRequest
import com.kit.wallet.data.remote.TwoFactorVerifyRequest
import com.kit.wallet.data.remote.VerifyIdentityTokenRequest
import com.kit.wallet.data.repository.WalletRefreshTrigger
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.di.ApplicationScope
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Singleton
class RemoteAuthRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val deviceIdentity: DeviceIdentityProvider,
    private val walletSync: WalletSyncRepository,
    private val walletRefreshTrigger: WalletRefreshTrigger,
    private val pushTokens: PushTokenCoordinator,
    private val paymentAuthorizer: PaymentAuthorizer,
    @ApplicationScope applicationScope: CoroutineScope,
) : AuthRepository {

    override val signedIn: StateFlow<Boolean> = sessions.session
        .map { it != null }
        .stateIn(applicationScope, SharingStarted.Eagerly, sessions.current() != null)

    override val profileSetupState: StateFlow<ProfileSetupState> = sessions.session
        .map { it?.profileSetupState ?: ProfileSetupState.UNKNOWN }
        .stateIn(
            applicationScope,
            SharingStarted.Eagerly,
            sessions.current()?.profileSetupState ?: ProfileSetupState.UNKNOWN,
        )

    override suspend fun loginWithEmail(email: String, password: String): AuthOutcome {
        val expected = sessions.snapshot()
        return apiCalls.execute {
            api.loginWithEmail(
                EmailLoginRequest(
                    email = email.trim(),
                    password = password,
                    device = deviceIdentity.registration(),
                ),
            )
        }.toOutcome(expected)
    }

    override suspend fun registerWithEmail(
        name: String,
        tag: String,
        email: String,
        password: String,
        passwordConfirmation: String,
    ): RegistrationResult {
        val result = apiCalls.execute {
            api.registerWithEmail(
                EmailRegistrationRequest(
                    name = normalizeProfileName(name),
                    tag = normalizeProfileTag(tag),
                    email = email.trim(),
                    password = password,
                    passwordConfirmation = passwordConfirmation,
                    timezone = ZoneId.systemDefault().id,
                ),
            )
        }
        check(result.state == "verification_required") {
            "Registration did not return an email verification challenge"
        }
        return RegistrationResult(
            email = result.user.email ?: email.trim(),
            destination = result.challenge.destination,
            expiresAt = result.challenge.expiresAt,
        )
    }

    override suspend fun verifyEmail(token: String) {
        val result = apiCalls.execute {
            api.verifyEmail(VerifyIdentityTokenRequest(token.trim()))
        }
        check(result.verified == true) { "Email verification was not completed" }
    }

    override suspend fun resendEmailVerification(email: String): String = apiCalls.execute {
        api.resendEmailVerification(EmailAddressRequest(email.trim()))
    }.message.orFallback("If the account exists, a new verification email has been sent.")

    override suspend fun forgotPassword(email: String): String = apiCalls.execute {
        api.forgotPassword(EmailAddressRequest(email.trim()))
    }.message.orFallback("If the account exists, password reset instructions have been sent.")

    override suspend fun resetPassword(
        token: String,
        password: String,
        passwordConfirmation: String,
    ) {
        val result = apiCalls.execute {
            api.resetPassword(
                ResetPasswordRequest(
                    token = token.trim(),
                    password = password,
                    passwordConfirmation = passwordConfirmation,
                ),
            )
        }
        check(result.passwordReset == true) { "Password reset was not completed" }
    }

    override suspend fun requestPhoneOtp(phone: String): PendingAuthChallenge {
        val expected = sessions.snapshot()
        val result = apiCalls.execute { api.requestPhoneOtp(
            PhoneOtpRequest(phone = phone.trim(), device = deviceIdentity.registration()),
        ) }
        return requireNotNull(result.challenge) { "OTP response omitted challenge" }
            .toPending(destination = phone.trim(), expectedSession = expected)
    }

    override suspend fun phoneAuthCapabilities(): PhoneAuthCapabilities {
        val authentication = apiCalls.execute { api.capabilities() }.authentication.orEmpty()
        return PhoneAuthCapabilities(
            serverPhoneOtp = authentication["phone_otp"] == true,
        )
    }

    override suspend fun verifyPhoneOtp(
        challenge: PendingAuthChallenge,
        phone: String,
        code: String,
    ): AuthOutcome {
        val expected = challenge.requireUnchangedSession()
        return apiCalls.execute {
            api.verifyPhoneOtp(
                PhoneOtpVerifyRequest(
                    challengeId = challenge.id,
                    phone = phone.trim(),
                    code = code,
                    device = deviceIdentity.registration(),
                ),
            )
        }.toOutcome(expected)
    }

    override suspend fun verifyTwoFactor(
        challenge: PendingAuthChallenge,
        code: String,
    ): AuthOutcome {
        val expected = challenge.requireUnchangedSession()
        return apiCalls.execute {
            api.verifyTwoFactor(
                TwoFactorVerifyRequest(challengeId = challenge.id, code = code),
            )
        }.toOutcome(expected)
    }

    override suspend fun refreshSession(): AuthOutcome.Authenticated {
        val expected = sessions.snapshot()
        val current = requireNotNull(sessions.current()) { "No session to refresh" }
        if (expected.fence != current.fence()) throw SessionInvalidatedException()
        val outcome = apiCalls.execute { api.refresh(RefreshSessionRequest(current.refreshToken)) }
            .toOutcome(expected, allowAccountReplacement = false)
        return outcome as? AuthOutcome.Authenticated
            ?: error("Refresh unexpectedly required another authentication challenge")
    }

    override suspend fun logout(allDevices: Boolean): LogoutResult {
        val target = sessions.current()?.fence()
            ?: return LogoutResult(
                localSessionCleared = true,
                remoteRevocation = RemoteRevocationState.ALREADY_REVOKED,
                retryRecommended = false,
            )
        var remoteRevocation = RemoteRevocationState.CONFIRMED
        var warning: String? = null
        try {
            // The current device token must be removed while its Kit session can still authorize
            // the request. All-device logout then revokes the remaining sessions server-side.
            try {
                pushTokens.unregisterBeforeLogout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Session revocation is authoritative; a stale push token is ignored once the
                // session/device is revoked and can be cleaned by the backend independently.
            }
            apiCalls.execute { api.logout(LogoutRequest(allDevices)) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { clearLocalUserData(target) }
                    .exceptionOrNull()
                    ?.let(cancelled::addSuppressed)
            }
            throw cancelled
        } catch (error: KitWalletApiException) {
            if (error.statusCode == 401) {
                remoteRevocation = RemoteRevocationState.ALREADY_REVOKED
            } else {
                remoteRevocation = RemoteRevocationState.UNCONFIRMED
                warning = "Signed out on this device, but server revocation was not confirmed. " +
                    "Sign in and retry when connected."
            }
        } catch (_: Exception) {
            remoteRevocation = RemoteRevocationState.UNCONFIRMED
            warning = "Signed out on this device, but server revocation was not confirmed. " +
                "Sign in and retry when connected."
        }

        val localCleared = clearLocalUserData(target)
        return LogoutResult(
            localSessionCleared = localCleared,
            remoteRevocation = remoteRevocation,
            retryRecommended = remoteRevocation == RemoteRevocationState.UNCONFIRMED,
            warning = warning,
        )
    }

    override suspend fun accountDeletionPreflight(): AccountDeletionPreflight {
        val result = apiCalls.execute { api.accountDeletionPreflight() }
        check(result.state == "available" && result.canRequest == true) {
            "Account deletion is not available for this account"
        }
        check(result.stepUp.purpose == "account_deletion") {
            "The server returned an unsupported account deletion authorization"
        }
        check(result.confirmationText == "DELETE") {
            "The server returned an unsupported account deletion confirmation"
        }
        return AccountDeletionPreflight(
            purpose = result.stepUp.purpose,
            intent = result.stepUp.intent,
            confirmationText = result.confirmationText,
            publicUrl = result.notice.publicUrl,
            deletedCategories = result.notice.deletedCategories,
            retainedCategories = result.notice.retainedCategories,
            closureRequirements = result.closureRequirements.map { it.message },
        )
    }

    override suspend fun requestAccountDeletion(
        preflight: AccountDeletionPreflight,
        confirmation: String,
        paymentPin: String,
    ) {
        val target = requireNotNull(sessions.current()) {
            "Sign in again to request account deletion"
        }.fence()
        require(confirmation == preflight.confirmationText) {
            "Type ${preflight.confirmationText} to confirm account deletion"
        }
        val token = paymentAuthorizer.authorize(
            purpose = preflight.purpose,
            intent = preflight.intent,
            paymentPin = paymentPin,
        )
        val receipt = apiCalls.execute {
            api.requestAccountDeletion(
                stepUpToken = token,
                request = RequestAccountDeletionDto(confirmation),
            )
        }
        check(receipt.state == "accepted" && receipt.accountStatus == "deletion_pending") {
            "The account deletion request was not accepted"
        }
        clearLocalUserData(target)
    }

    private suspend fun clearLocalUserData(target: SessionFence): Boolean {
        var sessionFailure: Exception? = null
        var targetCleared = false
        try {
            sessions.clearIfCurrent(target)
            targetCleared = sessions.current()?.fence() != target
        } catch (error: Exception) {
            // Keystore-backed session clear removes the credential in a finally block. Preserve
            // the erasure error, but never let it skip unencrypted profile/wallet cache cleanup.
            sessionFailure = error
            targetCleared = sessions.current()?.fence() != target
        }

        try {
            // This is owner-conditional, so a newer account may claim/populate its cache while
            // the remote logout is finishing without having its projections erased here.
            walletSync.clearCachedUserData(target.cacheScopeId)
        } catch (cacheFailure: Exception) {
            sessionFailure?.addSuppressed(cacheFailure)
            if (sessionFailure == null) throw cacheFailure
        }

        sessionFailure?.let { throw it }
        return targetCleared
    }

    private suspend fun AuthResultDto.toOutcome(
        expected: SessionSnapshot,
        allowAccountReplacement: Boolean = true,
    ): AuthOutcome {
        val sessionDto = session
        if (sessionDto != null) {
            val authenticatedUser = requireNotNull(user) { "Authenticated response omitted user" }
            if (!allowAccountReplacement) {
                expected.fence?.accountId?.let { expectedAccount ->
                    check(authenticatedUser.id == expectedAccount) {
                        "The refreshed session belongs to another account"
                    }
                }
            }
            val profileName = profileNameOrPlaceholder(authenticatedUser.name)
            val setupState = if (
                authenticatedUser.profileSetupRequired == true ||
                requiresProfileSetup(authenticatedUser.name, authenticatedUser.tag)
            ) {
                ProfileSetupState.REQUIRED
            } else {
                ProfileSetupState.COMPLETED
            }
            val previousFence = expected.fence
            val preserveCacheScope = previousFence != null && (
                previousFence.accountId == authenticatedUser.id ||
                    (previousFence.accountId == null &&
                        previousFence.sessionId == sessionDto.sessionId)
            )
            val tokens = SessionTokens(
                accessToken = sessionDto.accessToken,
                refreshToken = sessionDto.refreshToken,
                sessionId = sessionDto.sessionId,
                accessTokenExpiresAtEpochSeconds = sessionDto.accessExpiresAt.toEpochSecondsOrNull(),
                accountId = authenticatedUser.id,
                cacheScopeId = if (preserveCacheScope) {
                    requireNotNull(previousFence).cacheScopeId
                } else {
                    "${authenticatedUser.id}:${sessionDto.sessionId}"
                },
                profileSetupState = setupState,
            )
            if (!sessions.saveIfUnchanged(expected, tokens)) throw SessionInvalidatedException()
            walletRefreshTrigger.refreshNow()
            return AuthOutcome.Authenticated(
                AuthenticatedUser(
                    id = authenticatedUser.id,
                    name = profileName,
                    email = authenticatedUser.email,
                    phone = authenticatedUser.phone,
                    tag = authenticatedUser.tag,
                    paymentPinSet = authenticatedUser.paymentPinSet == true,
                    profileSetupRequired = setupState.requiresSetup,
                ),
            )
        }

        return AuthOutcome.ChallengeRequired(
            requireNotNull(challenge) { "Authentication response omitted session and challenge" }
                .toPending(expectedSession = expected),
        )
    }

    private fun PendingAuthChallenge.requireUnchangedSession(): SessionSnapshot {
        val expected = expectedSession ?: sessions.snapshot()
        if (sessions.snapshot() != expected) throw SessionInvalidatedException()
        return expected
    }

    private fun AuthChallengeDto.toPending(
        destination: String? = null,
        expectedSession: SessionSnapshot,
    ) = PendingAuthChallenge(
        id = id,
        kind = when (type.lowercase()) {
            "otp", "phone_otp" -> AuthChallengeKind.PHONE_OTP
            "two_factor" -> AuthChallengeKind.TWO_FACTOR
            else -> error("The backend returned an unsupported authentication challenge")
        },
        method = method,
        destination = destination ?: this.destination,
        expiresAtEpochSeconds = expiresAt.toEpochSecondsOrNull(),
        resendAfterSeconds = resendAfterSeconds.toCooldownSecondsOrNull(),
        expectedSession = expectedSession,
    )

    private fun String?.toEpochSecondsOrNull(): Long? = this?.let {
        runCatching { Instant.parse(it).epochSecond }.getOrNull()
    }

    private fun Double?.toCooldownSecondsOrNull(): Long? = this
        ?.takeIf { it.isFinite() }
        ?.let { ceil(it).toLong().coerceAtLeast(0L) }

    private fun String?.orFallback(fallback: String): String =
        this?.trim()?.takeIf(String::isNotBlank) ?: fallback
}

internal fun requiresProfileSetup(name: String?, tag: String?): Boolean {
    return tag == null || profileIdentityValidationError(name.orEmpty(), tag) != null
}

internal fun isPlaceholderProfileName(value: String): Boolean =
    normalizeProfileName(value).let {
        it.isBlank() ||
            it.equals("Kit Pay User", ignoreCase = true) ||
            it.equals("Kit Wallet User", ignoreCase = true)
    }

internal fun isProvisionalProfileTag(value: String): Boolean =
    value.trim().removePrefix("@").lowercase().matches(Regex("^kit_[a-z0-9]{10}$"))
