package com.kit.wallet.data.auth

import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.messaging.AccountMessageHistoryRetention
import com.kit.wallet.data.messaging.AccountMessageArchivePurgeNotDurableException
import com.kit.wallet.data.messaging.NoOpAccountMessageHistoryRetention
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.remote.AuthChallengeDto
import com.kit.wallet.data.remote.AuthResultDto
import com.kit.wallet.data.remote.AuthTokenRefresher
import com.kit.wallet.data.remote.AuthSessionRefreshCoordinator
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
import com.kit.wallet.data.remote.ResetMessagingEnrollmentDto
import com.kit.wallet.data.remote.ResetMessagingEnrollmentRequest
import com.kit.wallet.data.remote.ResetPasswordRequest
import com.kit.wallet.data.remote.SecureMessagingEnrollmentRecoveryApi
import com.kit.wallet.data.remote.SessionRefreshResult
import com.kit.wallet.data.remote.isDefinitiveSessionRejection
import com.kit.wallet.data.remote.TwoFactorVerifyRequest
import com.kit.wallet.data.remote.UserDto
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
import com.kit.wallet.data.session.SecureMessagingResetProofFence
import com.kit.wallet.di.ApplicationScope
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Singleton
class RemoteAuthRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val messagingEnrollmentRecovery: SecureMessagingEnrollmentRecoveryApi,
    private val tokenRefresher: AuthTokenRefresher,
    private val refreshCoordinator: AuthSessionRefreshCoordinator = AuthSessionRefreshCoordinator(),
    private val sessions: SessionStore,
    private val deviceIdentity: DeviceIdentityProvider,
    private val walletSync: WalletSyncRepository,
    private val walletRefreshTrigger: WalletRefreshTrigger,
    private val pushTokens: PushTokenCoordinator,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val messageHistory: AccountMessageHistoryRetention =
        NoOpAccountMessageHistoryRetention,
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
        val response = apiCalls.executeWithMeta {
            api.loginWithEmail(
                EmailLoginRequest(
                    email = email.trim(),
                    password = password,
                    device = deviceIdentity.registration(),
                ),
            )
        }
        currentCoroutineContext().ensureActive()
        return response.data.toOutcome(expected, response.meta?.serverTime)
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
        val response = apiCalls.executeWithMeta { api.requestPhoneOtp(
            PhoneOtpRequest(phone = phone.trim(), device = deviceIdentity.registration()),
        ) }
        return requireNotNull(response.data.challenge) { "OTP response omitted challenge" }
            .toPending(
                destination = phone.trim(),
                expectedSession = expected,
                serverTime = response.meta?.serverTime,
            )
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
        val response = apiCalls.executeWithMeta {
            api.verifyPhoneOtp(
                PhoneOtpVerifyRequest(
                    challengeId = challenge.id,
                    phone = phone.trim(),
                    code = code,
                    device = deviceIdentity.registration(),
                ),
            )
        }
        currentCoroutineContext().ensureActive()
        return response.data.toOutcome(expected, response.meta?.serverTime)
    }

    override suspend fun verifyTwoFactor(
        challenge: PendingAuthChallenge,
        code: String,
    ): AuthOutcome {
        val expected = challenge.requireUnchangedSession()
        val response = apiCalls.executeWithMeta {
            api.verifyTwoFactor(
                TwoFactorVerifyRequest(challengeId = challenge.id, code = code),
            )
        }
        currentCoroutineContext().ensureActive()
        return response.data.toOutcome(expected, response.meta?.serverTime)
    }

    override suspend fun refreshSession(): AuthOutcome.Authenticated {
        return refreshCoordinator.serialized {
            val current = requireNotNull(sessions.current()) { "No session to refresh" }
            when (val refresh = tokenRefresher.refresh(current)) {
                is SessionRefreshResult.Refreshed -> {
                    val authenticatedUser = requireNotNull(refresh.user) {
                        "Successful session refresh omitted user"
                    }
                    if (!sessions.adoptRefreshedCredentialsIfCurrent(
                            current,
                            refresh.tokens,
                        )
                    ) {
                        throw SessionInvalidatedException()
                    }
                    walletRefreshTrigger.refreshNow()
                    AuthOutcome.Authenticated(
                        authenticatedUser.toAuthenticatedUser(
                            refresh.tokens.profileSetupState,
                        ),
                    )
                }
                is SessionRefreshResult.Rejected -> {
                    clearLocalUserDataIfCredentialsCurrent(
                        current,
                        preserveMessagingHistory = true,
                    )
                    throw refresh.error
                }
            }
        }
    }

    override suspend fun logout(allDevices: Boolean): LogoutResult {
        val target = sessions.current()?.fence()
            ?: return LogoutResult(
                localSessionCleared = true,
                remoteRevocation = RemoteRevocationState.ALREADY_REVOKED,
                retryRecommended = false,
            )
        var historyCleanupFailed = false
        if (allDevices) {
            // Persist both independent crash fences before remote revocation. A restart can then
            // remove credentials/Signal state and retry the exact-generation archive purge even
            // if this process dies during the network request.
            messageHistory.scheduleAccountErasure(target)
            if (!sessions.prepareClearIfCurrent(target)) {
                throw SessionInvalidatedException()
            }
        } else {
            // Write-through normally keeps this current. This early pass migrates messages
            // created before archive support and surfaces archive failures before revocation.
            // A second, state-exclusive pass is coupled to local erasure below.
            try {
                messageHistory.snapshotActiveHistory(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Do not erase the only remaining projection after a failed final snapshot. The
                // authenticated session stays active so the user can retry logout without loss.
                throw error
            }
        }
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
            try {
                nonCancellablePreservingFailure {
                    clearLocalUserData(
                        target,
                        preserveMessagingHistory = !allDevices,
                        purgeMessagingHistory = allDevices,
                        onHistoryCleanupFailure = { historyCleanupFailed = true },
                    )
                }
            } catch (cleanupFailure: Throwable) {
                cancelled.addSuppressed(cleanupFailure)
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

        val localCleared = nonCancellablePreservingFailure {
            clearLocalUserData(
                target,
                preserveMessagingHistory = !allDevices,
                purgeMessagingHistory = allDevices,
                onHistoryCleanupFailure = { historyCleanupFailed = true },
            )
        }
        if (historyCleanupFailed) {
            warning = listOfNotNull(
                warning,
                "Signed out, but encrypted local message-history cleanup must be retried.",
            ).joinToString(" ")
        }
        return LogoutResult(
            localSessionCleared = localCleared,
            remoteRevocation = remoteRevocation,
            retryRecommended = remoteRevocation == RemoteRevocationState.UNCONFIRMED ||
                historyCleanupFailed,
            warning = warning,
        )
    }

    override suspend fun recoverMissingSecureMessagingEnrollment(
        expectedSession: SessionFence,
        activationFence: SecureMessagingSessionFence,
        target: SecureMessagingEnrollmentResetTarget,
    ) {
        var targetFence = sessions.current()?.fence() ?: throw SessionInvalidatedException()
        if (targetFence != expectedSession) throw SessionInvalidatedException()
        var targetSession = sessions.withCurrentSession(targetFence) { it }
        var accessRefreshAttempted = false
        val pendingReset = SecureMessagingResetProofFence(
            serverDeviceId = target.serverDeviceId,
            previousEnrollmentEpoch = target.enrollmentEpoch,
            previousRegistrationId = target.registrationId,
            previousIdentityKeySha256 = target.identityKeySha256,
            previousBundleVersion = target.bundleVersion,
        )
        if (!sessions.recordMessagingResetPendingIfCurrent(targetFence, pendingReset)) {
            throw SessionInvalidatedException()
        }

        // This client has explicit snapshotted credentials and no mutable-session interceptor or
        // authenticator. An obsolete S1 request can therefore never be replayed as S2.
        while (true) {
            try {
                val proof = apiCalls.execute {
                    messagingEnrollmentRecovery.reset(
                        authorization = "Bearer ${targetSession.accessToken}",
                        sessionId = targetSession.sessionId,
                        request = ResetMessagingEnrollmentRequest(
                            expectedEnrollmentEpoch = target.enrollmentEpoch,
                            expectedRegistrationId = target.registrationId,
                            expectedIdentityKeySha256 = target.identityKeySha256,
                            expectedBundleVersion = target.bundleVersion,
                        ),
                    )
                }
                val resetProof = requireEnrollmentResetProof(proof, target)
                if (sessions.current()?.fence() != targetFence) throw SessionInvalidatedException()
                if (!sessions.recordMessagingResetProofIfCurrent(targetFence, resetProof)) {
                    throw SessionInvalidatedException()
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: KitWalletApiException) {
                if (error.statusCode == 409 &&
                    error.code == "MESSAGING_ENROLLMENT_RESET_STALE"
                ) {
                    requireFreshAuthentication(targetFence, activationFence)
                }
                if (error.statusCode == 401 && !accessRefreshAttempted) {
                    accessRefreshAttempted = true
                    targetSession = refreshCoordinator.serialized {
                        val latest = sessions.withCurrentSession(targetFence) { it }
                        if (latest.accessToken != targetSession.accessToken ||
                            latest.refreshToken != targetSession.refreshToken
                        ) {
                            return@serialized latest
                        }
                        when (val refresh = tokenRefresher.refresh(latest)) {
                            is SessionRefreshResult.Refreshed -> {
                                check(refresh.tokens.sessionId == latest.sessionId) {
                                    "Session refresh changed secure-messaging recovery epoch"
                                }
                                if (!sessions.adoptRefreshedCredentialsIfCurrent(
                                        latest,
                                        refresh.tokens,
                                    )
                                ) {
                                    throw SessionInvalidatedException()
                                }
                                refresh.tokens
                            }
                            is SessionRefreshResult.Rejected -> {
                                requireFreshAuthentication(targetFence, activationFence)
                            }
                        }
                    }
                    targetFence = targetSession.fence()
                    continue
                }
                if (error.isDefinitiveSessionRejection()) {
                    requireFreshAuthentication(targetFence, activationFence)
                }
                throw error
            }
        }
    }

    override suspend fun requireFreshAuthenticationForSecureMessagingRecovery(
        expectedSession: SessionFence,
        activationFence: SecureMessagingSessionFence,
    ) {
        val target = sessions.current()?.fence() ?: throw SessionInvalidatedException()
        if (target != expectedSession) throw SessionInvalidatedException()
        clearLocalUserData(
            target = target,
            preserveMessagingHistory = true,
            recoveryActivationFence = activationFence,
        )
    }

    private suspend fun requireFreshAuthentication(
        target: SessionFence,
        activationFence: SecureMessagingSessionFence,
    ): Nothing {
        clearLocalUserData(
            target = target,
            preserveMessagingHistory = true,
            recoveryActivationFence = activationFence,
        )
        throw SessionInvalidatedException()
    }

    private fun requireEnrollmentResetProof(
        proof: ResetMessagingEnrollmentDto,
        target: SecureMessagingEnrollmentResetTarget,
    ): SecureMessagingResetProofFence {
        check(proof.deviceId == target.serverDeviceId) {
            "Messaging enrollment reset proof belongs to another device"
        }
        check(proof.previousEnrollmentEpoch == target.enrollmentEpoch) {
            "Messaging enrollment reset proof has another previous epoch"
        }
        check(proof.enrollmentEpoch == target.enrollmentEpoch + 1L) {
            "Messaging enrollment reset proof did not advance exactly one epoch"
        }
        check(proof.enrolled == false) {
            "Messaging enrollment reset proof left the device enrolled"
        }
        checkNotNull(proof.resetApplied) {
            "Messaging enrollment reset proof omitted idempotency state"
        }
        return SecureMessagingResetProofFence(
            serverDeviceId = target.serverDeviceId,
            previousEnrollmentEpoch = target.enrollmentEpoch,
            resultingEnrollmentEpoch = checkNotNull(proof.enrollmentEpoch),
            previousRegistrationId = target.registrationId,
            previousIdentityKeySha256 = target.identityKeySha256,
            previousBundleVersion = target.bundleVersion,
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
        nonCancellablePreservingFailure {
            messageHistory.scheduleAccountErasure(target)
            if (!sessions.prepareClearIfCurrent(target)) {
                throw SessionInvalidatedException()
            }
            var historyFailure: Exception? = null
            val sessionFailure = runCatching {
                clearLocalUserData(
                    target = target,
                    purgeMessagingHistory = true,
                    onHistoryCleanupFailure = { historyFailure = it },
                )
            }.exceptionOrNull()
            val retainedHistoryFailure = historyFailure
            if (retainedHistoryFailure != null) {
                sessionFailure?.let(retainedHistoryFailure::addSuppressed)
                throw retainedHistoryFailure
            }
            sessionFailure?.let { throw it }
        }
    }

    private suspend fun <T> nonCancellablePreservingFailure(
        block: suspend () -> T,
    ): T {
        val outcome = withContext(NonCancellable) {
            runCatching { block() }
        }
        return outcome.getOrThrow()
    }

    private suspend fun clearLocalUserData(
        target: SessionFence,
        preserveMessagingHistory: Boolean = false,
        purgeMessagingHistory: Boolean = false,
        recoveryActivationFence: SecureMessagingSessionFence? = null,
        onHistoryCleanupFailure: (Exception) -> Unit = {},
    ): Boolean {
        require(!preserveMessagingHistory || !purgeMessagingHistory) {
            "Message history cannot be preserved and purged by the same session clear"
        }
        var sessionFailure: Exception? = null
        var targetCleared = false
        try {
            when {
                preserveMessagingHistory -> {
                    if (recoveryActivationFence == null) {
                        sessions.clearIfCurrentAfterFinalMessagingSnapshot(
                            expected = target,
                            allowPermanentlyUnavailableSnapshot = true,
                        ) {
                            messageHistory.snapshotActiveHistory(target)
                        }
                    } else {
                        sessions.clearIfCurrentForSecureMessagingRecovery(
                            expected = target,
                            activationFence = recoveryActivationFence,
                            allowPermanentlyUnavailableSnapshot = true,
                        ) {
                            messageHistory.snapshotActiveHistory(target)
                        }
                    }
                }
                purgeMessagingHistory -> {
                    sessions.clearIfCurrentAfterFinalMessagingSnapshot(target) {
                        try {
                            messageHistory.eraseScheduledAccount(target)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: AccountMessageArchivePurgeNotDurableException) {
                            throw error
                        } catch (error: Exception) {
                            // scheduleAccountErasure already committed the exact-generation
                            // marker. Continue session/state erasure and retry this purge later.
                            onHistoryCleanupFailure(error)
                        }
                    }
                }
                else -> sessions.clearIfCurrent(target)
            }
            targetCleared = sessions.current()?.fence() != target
        } catch (error: Exception) {
            // Keystore-backed session clear removes the credential in a finally block. Preserve
            // the erasure error, but never let it skip unencrypted profile/wallet cache cleanup.
            sessionFailure = error
            targetCleared = sessions.current()?.fence() != target
        }

        if (targetCleared) {
            try {
                // This is owner-conditional, so a newer account may claim/populate its cache while
                // the remote logout is finishing without having its projections erased here.
                walletSync.clearCachedUserData(target.cacheScopeId)
            } catch (cacheFailure: Exception) {
                sessionFailure?.addSuppressed(cacheFailure)
                if (sessionFailure == null) throw cacheFailure
            }
        }

        sessionFailure?.let { throw it }
        return targetCleared
    }

    private suspend fun clearLocalUserDataIfCredentialsCurrent(
        expected: SessionTokens,
        preserveMessagingHistory: Boolean = false,
    ): Boolean {
        var sessionFailure: Exception? = null
        var targetCleared = false
        try {
            targetCleared = if (preserveMessagingHistory) {
                sessions.clearIfCredentialsCurrentAfterFinalMessagingSnapshot(
                    expected = expected,
                    allowPermanentlyUnavailableSnapshot = true,
                ) {
                    messageHistory.snapshotActiveHistory(expected.fence())
                }
            } else {
                sessions.clearIfCredentialsCurrent(expected)
            }
        } catch (error: Exception) {
            sessionFailure = error
            targetCleared = sessions.current() == null
        }

        if (!targetCleared) {
            sessionFailure?.let { throw it }
            return false
        }

        try {
            walletSync.clearCachedUserData(expected.cacheScopeId)
        } catch (cacheFailure: Exception) {
            sessionFailure?.addSuppressed(cacheFailure)
            if (sessionFailure == null) throw cacheFailure
        }

        sessionFailure?.let { throw it }
        return true
    }

    private suspend fun AuthResultDto.toOutcome(
        expected: SessionSnapshot,
        serverTime: String?,
        allowAccountReplacement: Boolean = true,
    ): AuthOutcome {
        currentCoroutineContext().ensureActive()
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
                messagingResetProof = if (sessionDto.sessionId == previousFence?.sessionId) {
                    sessions.current()?.messagingResetProof
                } else {
                    null
                },
            )
            currentCoroutineContext().ensureActive()
            val sessionAdopted = sessions.replaceIfUnchangedAfterFinalMessagingSnapshot(
                expected = expected,
                tokens = tokens,
                finalMessagingSnapshot = { fence ->
                    messageHistory.snapshotActiveHistory(fence)
                    currentCoroutineContext().ensureActive()
                },
            )
            if (!sessionAdopted) {
                throw SessionInvalidatedException()
            }
            walletRefreshTrigger.refreshNow()
            return AuthOutcome.Authenticated(
                authenticatedUser.toAuthenticatedUser(setupState, profileName),
            )
        }

        return AuthOutcome.ChallengeRequired(
            requireNotNull(challenge) { "Authentication response omitted session and challenge" }
                .toPending(expectedSession = expected, serverTime = serverTime),
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
        serverTime: String?,
    ): PendingAuthChallenge {
        val expiry = expiresAt.toInstantOrNull()
        val serverNow = serverTime.toInstantOrNull()
        return PendingAuthChallenge(
            id = id,
            kind = when (type.lowercase()) {
                "otp", "phone_otp" -> AuthChallengeKind.PHONE_OTP
                "two_factor" -> AuthChallengeKind.TWO_FACTOR
                else -> error("The backend returned an unsupported authentication challenge")
            },
            method = method,
            destination = destination ?: this.destination,
            expiresAtEpochSeconds = expiry?.epochSecond,
            expiresAfterMillis = challengeLifetimeMillis(expiry, serverNow),
            resendAfterSeconds = resendAfterSeconds.toCooldownSecondsOrNull(),
            expectedSession = expectedSession,
        )
    }

    private fun String?.toEpochSecondsOrNull(): Long? = this?.let {
        runCatching { Instant.parse(it).epochSecond }.getOrNull()
    }

    private fun String?.toInstantOrNull(): Instant? = this?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }

    private fun Double?.toCooldownSecondsOrNull(): Long? = this
        ?.takeIf { it.isFinite() }
        ?.let { ceil(it).toLong().coerceAtLeast(0L) }

    private fun UserDto.toAuthenticatedUser(
        setupState: ProfileSetupState,
        normalizedName: String = profileNameOrPlaceholder(name),
    ) = AuthenticatedUser(
        id = id,
        name = normalizedName,
        email = email,
        phone = phone,
        tag = tag,
        paymentPinSet = paymentPinSet == true,
        profileSetupRequired = setupState.requiresSetup,
    )

    private fun String?.orFallback(fallback: String): String =
        this?.trim()?.takeIf(String::isNotBlank) ?: fallback
}

internal fun challengeLifetimeMillis(expiresAt: Instant?, serverTime: Instant?): Long? {
    if (expiresAt == null || serverTime == null) return null
    return runCatching { Duration.between(serverTime, expiresAt).toMillis() }
        .getOrNull()
        ?.takeIf { it > 0L }
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
