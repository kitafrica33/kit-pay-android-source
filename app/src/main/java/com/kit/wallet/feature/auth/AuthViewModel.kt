package com.kit.wallet.feature.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.AuthChallengeKind
import com.kit.wallet.data.auth.AuthOutcome
import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.PendingAuthChallenge
import com.kit.wallet.data.auth.normalizeMfaFactorCode
import com.kit.wallet.data.auth.normalizeSixDigitCode
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val pendingChallenge: PendingAuthChallenge? = null,
    val pendingPhone: String? = null,
    val resendNotBeforeElapsedRealtimeMillis: Long? = null,
    val challengeExpiresAtElapsedRealtimeMillis: Long? = null,
)

private enum class AuthOperationKind {
    PHONE_REQUEST,
    PHONE_RESEND,
    VERIFICATION,
    EMAIL_LOGIN,
    LOGOUT,
}

private data class ActiveAuthOperation(
    val generation: Long,
    val kind: AuthOperationKind,
    val challengeId: String?,
    var job: Job? = null,
)

/** Owns the short-lived sign-in challenge without placing secrets in navigation routes. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val elapsedRealtimeClock: ElapsedRealtimeClock,
    private val bootSessionIdProvider: BootSessionIdProvider,
    private val savedStateHandle: SavedStateHandle,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private var authGeneration = 0L
    private var activeOperation: ActiveAuthOperation? = null

    val signedIn: StateFlow<Boolean> = authRepository.signedIn
    val profileSetupRequired: StateFlow<Boolean> = authRepository.profileSetupState
        .map { it.requiresSetup }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            authRepository.profileSetupState.value.requiresSetup,
        )

    private val _uiState = MutableStateFlow(restorePendingPhoneChallenge())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun requestPhoneOtp(
        rawPhone: String,
        onChallengeReady: () -> Unit,
    ) {
        val phone = runCatching { normalizeUgandanPhone(rawPhone) }
            .getOrElse {
                _uiState.value = _uiState.value.copy(error = it.message)
                return
        }
        launchAuth(AuthOperationKind.PHONE_REQUEST) { generation ->
            val capabilities = authRepository.phoneAuthCapabilities()
            if (!isCurrentGeneration(generation)) return@launchAuth
            check(capabilities.serverPhoneOtp) {
                "Phone sign-in is temporarily unavailable. Use email instead."
            }
            val challenge = authRepository.requestPhoneOtp(phone)
            if (publishPendingChallenge(
                challenge = challenge,
                pendingPhone = phone,
                notice = PHONE_OTP_STABLE_CODE_NOTICE,
                expectedGeneration = generation,
            )) {
                onChallengeReady()
            }
        }
    }

    fun loginWithEmail(
        email: String,
        password: String,
        onChallengeReady: () -> Unit,
        onAuthenticated: (
            needsPaymentPinSetup: Boolean,
            needsProfileSetup: Boolean,
        ) -> Unit,
    ) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter your email and password.")
            return
        }
        launchAuth(AuthOperationKind.EMAIL_LOGIN) { generation ->
            handleOutcome(
                authRepository.loginWithEmail(email.trim(), password),
                pendingPhone = null,
                onChallengeReady = onChallengeReady,
                onAuthenticated = onAuthenticated,
                expectedGeneration = generation,
            )
        }
    }

    fun verifyCode(
        code: String,
        onAuthenticated: (needsPaymentPinSetup: Boolean, needsProfileSetup: Boolean) -> Unit,
    ) {
        val state = _uiState.value
        val challenge = state.pendingChallenge ?: run {
            _uiState.value = state.copy(error = "This verification request expired. Start again.")
            return
        }
        if (!state.hasUsableChallenge(elapsedRealtimeClock.millis())) {
            clearPendingChallenge(
                message = "This verification request expired. Start again.",
            )
            return
        }
        val authenticatorChallenge = challenge.kind == AuthChallengeKind.TWO_FACTOR &&
            challenge.method.equals("totp", ignoreCase = true)
        val normalizedCode = when {
            authenticatorChallenge -> normalizeMfaFactorCode(code)
            else -> normalizeSixDigitCode(code)
        }
        if (normalizedCode === null) {
            _uiState.value = state.copy(
                error = if (authenticatorChallenge) {
                    "Enter a 6-digit authenticator code or a complete recovery code."
                } else {
                    "Enter the complete 6-digit verification code."
                },
            )
            return
        }

        val pendingPhone = state.pendingPhone?.takeIf(String::isNotBlank)
        if (challenge.kind == AuthChallengeKind.PHONE_OTP && pendingPhone == null) {
            _uiState.value = state.copy(
                error = "This phone verification request expired. Start again.",
            )
            return
        }

        launchAuth(
            kind = AuthOperationKind.VERIFICATION,
            challengeId = challenge.id,
        ) { generation ->
            val outcome = when (challenge.kind) {
                AuthChallengeKind.PHONE_OTP -> authRepository.verifyPhoneOtp(
                    challenge = challenge,
                    phone = pendingPhone.orEmpty(),
                    code = normalizedCode,
                )
                AuthChallengeKind.TWO_FACTOR -> authRepository.verifyTwoFactor(
                    challenge,
                    normalizedCode,
                )
            }
            handleOutcome(
                outcome = outcome,
                pendingPhone = pendingPhone,
                onChallengeReady = {},
                onAuthenticated = onAuthenticated,
                expectedGeneration = generation,
            )
        }
    }

    fun resendPhoneOtp() {
        val state = _uiState.value
        val phone = state.pendingPhone ?: return
        val challenge = state.pendingChallenge ?: return
        if (challenge.kind != AuthChallengeKind.PHONE_OTP) return
        val nowElapsedRealtimeMillis = elapsedRealtimeClock.millis()
        if (!state.hasUsableChallenge(nowElapsedRealtimeMillis)) {
            clearPendingChallenge(
                message = "This verification request expired. Start again.",
            )
            return
        }
        if (!isResendCooldownElapsed(
                state.resendNotBeforeElapsedRealtimeMillis,
                nowElapsedRealtimeMillis,
            )
        ) {
            return
        }
        launchAuth(
            kind = AuthOperationKind.PHONE_RESEND,
            challengeId = challenge.id,
        ) { generation ->
            if (!isCurrentGeneration(generation)) return@launchAuth
            // A resend can be accepted by the server before its response reaches this process.
            // Persist that uncertainty before network I/O so process restoration can never revive
            // the old challenge after an unobserved server-side replacement.
            markPhoneResendInFlight(challenge.id)
            when (challenge.kind) {
                AuthChallengeKind.PHONE_OTP -> {
                    val renewed = authRepository.requestPhoneOtp(phone)
                    if (renewed.id != challenge.id ||
                        renewed.kind != AuthChallengeKind.PHONE_OTP
                    ) {
                        // The stable-resend contract was not honored. Do not silently turn this
                        // operation into a new sign-in attempt or let an entered code cross IDs.
                        clearPendingChallenge(
                            message = PHONE_OTP_RESEND_UNCONFIRMED_MESSAGE,
                            expectedGeneration = generation,
                            cancelActiveOperation = false,
                        )
                        return@launchAuth
                    }
                    publishPendingChallenge(
                        challenge = renewed,
                        pendingPhone = phone,
                        notice = PHONE_OTP_RESENT_SAME_CHALLENGE_NOTICE,
                        expectedGeneration = generation,
                    )
                }
                AuthChallengeKind.TWO_FACTOR -> Unit
            }
        }
    }

    fun clearPendingChallenge() {
        clearPendingChallenge(message = null)
    }

    /**
     * Removes an expired/missing OTP route without allowing a delayed callback from an older
     * composition to discard a challenge that a resend has just replaced.
     */
    fun clearUnavailableChallenge(routeChallengeId: String?): Boolean {
        val state = _uiState.value
        val challenge = state.pendingChallenge
        if (challenge?.id != routeChallengeId) return false
        if (challenge != null && state.hasUsableChallenge(elapsedRealtimeClock.millis())) {
            return false
        }
        val operation = activeOperation
        if (operation != null &&
            (operation.kind == AuthOperationKind.PHONE_RESEND ||
                operation.kind == AuthOperationKind.VERIFICATION) &&
            operation.challengeId == routeChallengeId &&
            operation.generation == authGeneration &&
            state.loading
        ) {
            // These operations were admitted while the challenge was valid. The server response
            // owns the result even when network latency carries it across the local deadline; in
            // particular, do not open a replacement-login race with a side-effecting verification.
            return false
        }
        clearPendingChallenge(
            message = state.error ?: "This verification request expired. Start again.",
        )
        return true
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun logoutCurrentDevice() {
        launchAuth(AuthOperationKind.LOGOUT) { _ ->
            authRepository.logout(allDevices = false)
        }
    }

    private suspend fun handleOutcome(
        outcome: AuthOutcome,
        pendingPhone: String?,
        onChallengeReady: () -> Unit,
        onAuthenticated: (
            needsPaymentPinSetup: Boolean,
            needsProfileSetup: Boolean,
        ) -> Unit,
        expectedGeneration: Long,
    ) {
        if (!isCurrentGeneration(expectedGeneration)) return
        when (outcome) {
            is AuthOutcome.Authenticated -> {
                if (clearPendingChallenge(
                        message = null,
                        expectedGeneration = expectedGeneration,
                        cancelActiveOperation = false,
                    )
                ) {
                    onAuthenticated(
                        !outcome.user.paymentPinSet,
                        outcome.user.profileSetupRequired,
                    )
                }
            }
            is AuthOutcome.ChallengeRequired -> {
                if (publishPendingChallenge(
                        outcome.challenge,
                        pendingPhone,
                        expectedGeneration = expectedGeneration,
                    )
                ) {
                    onChallengeReady()
                }
            }
        }
    }

    private fun launchAuth(
        kind: AuthOperationKind,
        challengeId: String? = null,
        block: suspend (generation: Long) -> Unit,
    ) {
        if (_uiState.value.loading) return
        val generation = nextAuthGeneration()
        val operation = ActiveAuthOperation(
            generation = generation,
            kind = kind,
            challengeId = challengeId,
        )
        activeOperation = operation
        // Reserve the request synchronously. Setting loading inside the launched coroutine leaves
        // a frame-sized window where two rapid taps can enqueue two OTP requests before either
        // coroutine begins.
        _uiState.value = _uiState.value.copy(loading = true, error = null, notice = null)
        operation.job = viewModelScope.launch {
            try {
                block(generation)
            } catch (cancelled: CancellationException) {
                if (kind == AuthOperationKind.PHONE_RESEND &&
                    isCurrentGeneration(generation)
                ) {
                    clearPendingChallenge(
                        message = PHONE_OTP_RESEND_UNCONFIRMED_MESSAGE,
                        expectedGeneration = generation,
                        cancelActiveOperation = false,
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                if (!isCurrentGeneration(generation)) return@launch
                if (kind == AuthOperationKind.PHONE_RESEND) {
                    // A transport failure cannot prove whether the server accepted and renewed
                    // the resend. Retaining the old ID could pair it with a newly delivered code.
                    clearPendingChallenge(
                        message = PHONE_OTP_RESEND_UNCONFIRMED_MESSAGE,
                        expectedGeneration = generation,
                        cancelActiveOperation = false,
                    )
                } else if (error.isTerminalChallengeFailure()) {
                    clearPendingChallenge(
                        message = error.userMessage(),
                        expectedGeneration = generation,
                        cancelActiveOperation = false,
                    )
                } else if ((kind == AuthOperationKind.PHONE_RESEND ||
                        kind == AuthOperationKind.VERIFICATION) &&
                    !_uiState.value.hasUsableChallenge(elapsedRealtimeClock.millis())
                ) {
                    clearPendingChallenge(
                        message = error.userMessage(),
                        expectedGeneration = generation,
                        cancelActiveOperation = false,
                    )
                } else {
                    finishActiveOperation(generation)
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = error.userMessage(),
                    )
                }
            } finally {
                if (isCurrentGeneration(generation) && _uiState.value.loading) {
                    finishActiveOperation(generation)
                    _uiState.value = _uiState.value.copy(loading = false)
                }
            }
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is KitWalletApiException -> message
        is IOException -> "Kit Pay could not connect. Check your internet and try again."
        is HttpException -> "Kit Pay could not complete sign-in (HTTP ${code()})."
        else -> message?.takeIf { it.isNotBlank() }
            ?: "Kit Pay could not complete sign-in. Try again."
    }

    private fun normalizeUgandanPhone(raw: String): String {
        var digits = raw.filter(Char::isDigit)
        digits = when {
            digits.startsWith("256") -> digits
            digits.startsWith("0") -> "256${digits.drop(1)}"
            digits.length == 9 -> "256$digits"
            else -> digits
        }
        require(digits.matches(Regex("^256[0-9]{9}$"))) {
            "Enter a valid Ugandan mobile number."
        }
        return "+$digits"
    }

    private fun publishPendingChallenge(
        challenge: PendingAuthChallenge,
        pendingPhone: String?,
        notice: String? = null,
        expectedGeneration: Long? = null,
    ): Boolean {
        if (expectedGeneration != null && !isCurrentGeneration(expectedGeneration)) return false
        val nowElapsedRealtimeMillis = elapsedRealtimeClock.millis()
        val expiryDeadline = challenge.expiryDeadline(nowElapsedRealtimeMillis)
        check(expiryDeadline != null && expiryDeadline > nowElapsedRealtimeMillis) {
            "This verification request has already expired. Start again."
        }
        val resendDeadline = challenge.resendDeadline(nowElapsedRealtimeMillis)
        if (expectedGeneration != null && !isCurrentGeneration(expectedGeneration)) return false
        expectedGeneration?.let(::finishActiveOperation)
        nextAuthGeneration()
        val state = AuthUiState(
            pendingChallenge = challenge,
            pendingPhone = pendingPhone,
            resendNotBeforeElapsedRealtimeMillis = resendDeadline,
            challengeExpiresAtElapsedRealtimeMillis = expiryDeadline,
            notice = notice,
        )
        _uiState.value = state
        if (challenge.kind == AuthChallengeKind.PHONE_OTP && pendingPhone != null) {
            persistPendingPhoneChallenge(
                challenge = challenge,
                phone = pendingPhone,
                resendDeadline = resendDeadline,
                expiryDeadline = expiryDeadline,
                capturedElapsedRealtimeMillis = nowElapsedRealtimeMillis,
            )
        } else {
            clearPersistedPhoneChallenge()
        }
        return true
    }

    private fun clearPendingChallenge(
        message: String?,
        expectedGeneration: Long? = null,
        cancelActiveOperation: Boolean = true,
    ): Boolean {
        if (expectedGeneration != null && !isCurrentGeneration(expectedGeneration)) return false
        if (cancelActiveOperation) {
            activeOperation?.job?.cancel()
            activeOperation = null
        } else {
            expectedGeneration?.let(::finishActiveOperation)
        }
        nextAuthGeneration()
        clearPersistedPhoneChallenge()
        _uiState.value = AuthUiState(error = message)
        return true
    }

    private fun nextAuthGeneration(): Long {
        authGeneration = Math.incrementExact(authGeneration)
        return authGeneration
    }

    private fun isCurrentGeneration(expected: Long): Boolean = authGeneration == expected

    private fun finishActiveOperation(generation: Long) {
        if (activeOperation?.generation == generation) activeOperation = null
    }

    /**
     * SavedStateHandle is backed by Android's saved-instance-state bundle. Store only primitive,
     * non-secret challenge metadata; the entered OTP is intentionally never part of this model.
     * SessionSnapshot is documented as non-secret, but its revision is process-local, so only its
     * fence is persisted and rebound to the new process's current revision during restoration.
     */
    private fun persistPendingPhoneChallenge(
        challenge: PendingAuthChallenge,
        phone: String,
        resendDeadline: Long?,
        expiryDeadline: Long,
        capturedElapsedRealtimeMillis: Long,
    ) {
        if (!isCanonicalUgandanPhone(phone) ||
            capturedElapsedRealtimeMillis < 0L ||
            expiryDeadline <= capturedElapsedRealtimeMillis
        ) {
            clearPersistedPhoneChallenge()
            return
        }
        val bootId = bootSessionIdProvider.currentBootId() ?: run {
            clearPersistedPhoneChallenge()
            return
        }
        val expected = challenge.expectedSession ?: sessionStore.snapshot()

        // Write the challenge ID last. A process death between primitive writes therefore leaves
        // no apparently complete record, and restoration fails closed.
        clearPersistedPhoneChallenge()
        savedStateHandle[STATE_PHONE] = phone
        savedStateHandle[STATE_METHOD] = challenge.method
        savedStateHandle[STATE_DESTINATION] = challenge.destination
        savedStateHandle[STATE_CAPTURED_ELAPSED_REALTIME_MILLIS] = capturedElapsedRealtimeMillis
        savedStateHandle[STATE_EXPIRY_ELAPSED_REALTIME_MILLIS] = expiryDeadline
        savedStateHandle[STATE_RESEND_ELAPSED_REALTIME_MILLIS] = resendDeadline
        savedStateHandle[STATE_BOOT_ID] = bootId
        savedStateHandle[STATE_EXPECTED_FENCE_PRESENT] = expected.fence != null
        expected.fence?.let { fence ->
            savedStateHandle[STATE_EXPECTED_SESSION_ID] = fence.sessionId
            savedStateHandle[STATE_EXPECTED_CACHE_SCOPE_ID] = fence.cacheScopeId
            savedStateHandle[STATE_EXPECTED_ACCOUNT_ID] = fence.accountId
        }
        savedStateHandle[STATE_CHALLENGE_ID] = challenge.id
    }

    private fun markPhoneResendInFlight(challengeId: String) {
        if (challengeId.isBlank()) {
            clearPersistedPhoneChallenge()
            return
        }
        savedStateHandle[STATE_RESEND_IN_FLIGHT_CHALLENGE_ID] = challengeId
    }

    private fun restorePendingPhoneChallenge(): AuthUiState {
        // A process may die after the server accepts a resend but before Android receives its
        // response. The old saved challenge is then ambiguous and must never be restored.
        if (savedStateHandle.get<String>(STATE_RESEND_IN_FLIGHT_CHALLENGE_ID) != null) {
            return discardInvalidRestoredChallenge(PHONE_OTP_RESEND_UNCONFIRMED_MESSAGE)
        }
        val challengeId = savedStateHandle.get<String>(STATE_CHALLENGE_ID)
            ?.takeIf(String::isNotBlank)
            ?: run {
                if (savedStateHandle.keys().any { it.startsWith(STATE_PREFIX) }) {
                    clearPersistedPhoneChallenge()
                }
                return AuthUiState()
            }
        val phone = savedStateHandle.get<String>(STATE_PHONE)
            ?.takeIf(::isCanonicalUgandanPhone)
            ?: return discardInvalidRestoredChallenge()
        val capturedElapsedRealtimeMillis =
            savedStateHandle.get<Long>(STATE_CAPTURED_ELAPSED_REALTIME_MILLIS)
            ?: return discardInvalidRestoredChallenge()
        val expiryElapsedRealtimeMillis =
            savedStateHandle.get<Long>(STATE_EXPIRY_ELAPSED_REALTIME_MILLIS)
            ?: return discardInvalidRestoredChallenge()
        val persistedBootId = savedStateHandle.get<Long>(STATE_BOOT_ID)
            ?: return discardInvalidRestoredChallenge()
        val currentBootId = bootSessionIdProvider.currentBootId()
            ?: return discardInvalidRestoredChallenge()
        if (persistedBootId != currentBootId) return discardInvalidRestoredChallenge()
        val nowElapsedRealtimeMillis = elapsedRealtimeClock.millis()
        if (capturedElapsedRealtimeMillis < 0L ||
            nowElapsedRealtimeMillis < capturedElapsedRealtimeMillis ||
            expiryElapsedRealtimeMillis <= nowElapsedRealtimeMillis ||
            expiryElapsedRealtimeMillis <= capturedElapsedRealtimeMillis
        ) {
            return discardInvalidRestoredChallenge()
        }
        val fencePresent = savedStateHandle.get<Boolean>(STATE_EXPECTED_FENCE_PRESENT)
            ?: return discardInvalidRestoredChallenge()
        val persistedFence = if (fencePresent) {
            val sessionId = savedStateHandle.get<String>(STATE_EXPECTED_SESSION_ID)
                ?.takeIf(String::isNotBlank)
                ?: return discardInvalidRestoredChallenge()
            val cacheScopeId = savedStateHandle.get<String>(STATE_EXPECTED_CACHE_SCOPE_ID)
                ?.takeIf(String::isNotBlank)
                ?: return discardInvalidRestoredChallenge()
            SessionFence(
                sessionId = sessionId,
                cacheScopeId = cacheScopeId,
                accountId = savedStateHandle[STATE_EXPECTED_ACCOUNT_ID],
            )
        } else {
            null
        }
        val currentSession = sessionStore.snapshot()
        if (currentSession.fence != persistedFence) return discardInvalidRestoredChallenge()

        val challenge = PendingAuthChallenge(
            id = challengeId,
            kind = AuthChallengeKind.PHONE_OTP,
            method = savedStateHandle[STATE_METHOD],
            destination = savedStateHandle.get<String>(STATE_DESTINATION) ?: phone,
            expiresAfterMillis = expiryElapsedRealtimeMillis - nowElapsedRealtimeMillis,
            expectedSession = SessionSnapshot(
                revision = currentSession.revision,
                fence = currentSession.fence,
            ),
        )
        return AuthUiState(
            pendingChallenge = challenge,
            pendingPhone = phone,
            resendNotBeforeElapsedRealtimeMillis =
                savedStateHandle[STATE_RESEND_ELAPSED_REALTIME_MILLIS],
            challengeExpiresAtElapsedRealtimeMillis = expiryElapsedRealtimeMillis,
            notice = PHONE_OTP_RESTORED_NOTICE,
        )
    }

    private fun discardInvalidRestoredChallenge(message: String? = null): AuthUiState {
        clearPersistedPhoneChallenge()
        return AuthUiState(error = message)
    }

    private fun clearPersistedPhoneChallenge() {
        // Remove the completeness marker first, then the remaining primitive metadata.
        savedStateHandle.remove<String>(STATE_CHALLENGE_ID)
        savedStateHandle.remove<String>(STATE_PHONE)
        savedStateHandle.remove<String>(STATE_METHOD)
        savedStateHandle.remove<String>(STATE_DESTINATION)
        savedStateHandle.remove<Long>(STATE_CAPTURED_ELAPSED_REALTIME_MILLIS)
        savedStateHandle.remove<Long>(STATE_EXPIRY_ELAPSED_REALTIME_MILLIS)
        savedStateHandle.remove<Long>(STATE_RESEND_ELAPSED_REALTIME_MILLIS)
        savedStateHandle.remove<Long>(STATE_BOOT_ID)
        savedStateHandle.remove<Long>(LEGACY_STATE_EXPIRES_AT_SECONDS)
        savedStateHandle.remove<Long>(LEGACY_STATE_RESEND_NOT_BEFORE_MILLIS)
        savedStateHandle.remove<Boolean>(STATE_EXPECTED_FENCE_PRESENT)
        savedStateHandle.remove<String>(STATE_EXPECTED_SESSION_ID)
        savedStateHandle.remove<String>(STATE_EXPECTED_CACHE_SCOPE_ID)
        savedStateHandle.remove<String>(STATE_EXPECTED_ACCOUNT_ID)
        savedStateHandle.remove<String>(STATE_RESEND_IN_FLIGHT_CHALLENGE_ID)
    }
}

private fun Throwable.isTerminalChallengeFailure(): Boolean =
    this is KitWalletApiException && code in TERMINAL_CHALLENGE_ERROR_CODES

private fun AuthUiState.hasUsableChallenge(nowElapsedRealtimeMillis: Long): Boolean =
    pendingChallenge?.id?.isNotBlank() == true &&
        challengeExpiresAtElapsedRealtimeMillis?.let { it > nowElapsedRealtimeMillis } == true

private fun PendingAuthChallenge.expiryDeadline(nowElapsedRealtimeMillis: Long): Long? =
    expiresAfterMillis
        ?.takeIf { it > 0L }
        ?.let { lifetime ->
            runCatching { Math.addExact(nowElapsedRealtimeMillis, lifetime) }.getOrNull()
        }

private fun isCanonicalUgandanPhone(phone: String): Boolean =
    phone.matches(Regex("^\\+256[0-9]{9}$"))

private fun PendingAuthChallenge.resendDeadline(
    nowElapsedRealtimeMillis: Long,
): Long? = when (kind) {
    AuthChallengeKind.PHONE_OTP -> phoneResendDeadline(
        nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
        resendAfterSeconds = resendAfterSeconds,
    )
    AuthChallengeKind.TWO_FACTOR -> resendAfterSeconds
        ?.coerceIn(0L, MAX_RESEND_COOLDOWN_SECONDS)
        ?.let { seconds -> nowElapsedRealtimeMillis + seconds * 1_000L }
}

/**
 * A missing phone cooldown is not permission to hammer the SMS endpoint. The production contract
 * normally returns 60 seconds, but old/null responses must remain conservative as well.
 */
internal fun phoneResendDeadline(
    nowElapsedRealtimeMillis: Long,
    resendAfterSeconds: Long?,
): Long = nowElapsedRealtimeMillis +
    (resendAfterSeconds ?: DEFAULT_PHONE_RESEND_COOLDOWN_SECONDS)
        .coerceIn(0L, MAX_RESEND_COOLDOWN_SECONDS) * 1_000L

internal fun isResendCooldownElapsed(
    notBeforeElapsedRealtimeMillis: Long?,
    nowElapsedRealtimeMillis: Long,
): Boolean = notBeforeElapsedRealtimeMillis != null &&
    resendSecondsRemaining(notBeforeElapsedRealtimeMillis, nowElapsedRealtimeMillis) == 0L

internal fun resendSecondsRemaining(
    notBeforeElapsedRealtimeMillis: Long?,
    nowElapsedRealtimeMillis: Long,
): Long {
    val remainingMillis = (notBeforeElapsedRealtimeMillis ?: nowElapsedRealtimeMillis) -
        nowElapsedRealtimeMillis
    return if (remainingMillis <= 0L) 0L else (remainingMillis + 999L) / 1_000L
}

private const val DEFAULT_PHONE_RESEND_COOLDOWN_SECONDS = 60L
private const val MAX_RESEND_COOLDOWN_SECONDS = 3_600L
private const val PHONE_OTP_STABLE_CODE_NOTICE =
    "A verification code was sent. It remains valid until this request expires."
private const val PHONE_OTP_RESTORED_NOTICE =
    "Your active verification request was restored. Enter the code already sent to you."
private const val PHONE_OTP_RESENT_SAME_CHALLENGE_NOTICE =
    "The same code was sent again. Earlier SMS messages for this sign-in still work."
private const val PHONE_OTP_RESEND_UNCONFIRMED_MESSAGE =
    "We couldn't confirm the same verification request. Start again and use the latest message."

private val TERMINAL_CHALLENGE_ERROR_CODES = setOf(
    "CHALLENGE_EXPIRED",
    "CHALLENGE_ALREADY_USED",
    "CHALLENGE_LOCKED",
)

private const val STATE_CHALLENGE_ID = "auth.phone_otp.challenge_id"
private const val STATE_PREFIX = "auth.phone_otp."
private const val STATE_PHONE = "auth.phone_otp.phone"
private const val STATE_METHOD = "auth.phone_otp.method"
private const val STATE_DESTINATION = "auth.phone_otp.destination"
private const val STATE_CAPTURED_ELAPSED_REALTIME_MILLIS =
    "auth.phone_otp.captured_elapsed_realtime_millis"
private const val STATE_EXPIRY_ELAPSED_REALTIME_MILLIS =
    "auth.phone_otp.expiry_elapsed_realtime_millis"
private const val STATE_RESEND_ELAPSED_REALTIME_MILLIS =
    "auth.phone_otp.resend_elapsed_realtime_millis"
private const val STATE_BOOT_ID = "auth.phone_otp.boot_id"
private const val STATE_EXPECTED_FENCE_PRESENT = "auth.phone_otp.expected_fence_present"
private const val STATE_EXPECTED_SESSION_ID = "auth.phone_otp.expected_session_id"
private const val STATE_EXPECTED_CACHE_SCOPE_ID = "auth.phone_otp.expected_cache_scope_id"
private const val STATE_EXPECTED_ACCOUNT_ID = "auth.phone_otp.expected_account_id"
private const val STATE_RESEND_IN_FLIGHT_CHALLENGE_ID =
    "auth.phone_otp.resend_in_flight_challenge_id"
private const val LEGACY_STATE_EXPIRES_AT_SECONDS = "auth.phone_otp.expires_at_seconds"
private const val LEGACY_STATE_RESEND_NOT_BEFORE_MILLIS =
    "auth.phone_otp.resend_not_before_millis"
