package com.kit.wallet.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.AuthChallengeKind
import com.kit.wallet.data.auth.AuthOutcome
import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.PendingAuthChallenge
import com.kit.wallet.data.auth.normalizeMfaFactorCode
import com.kit.wallet.data.auth.normalizeSixDigitCode
import com.kit.wallet.data.remote.KitWalletApiException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    val pendingChallenge: PendingAuthChallenge? = null,
    val pendingPhone: String? = null,
    val resendNotBeforeEpochMillis: Long? = null,
)

/** Owns the short-lived sign-in challenge without placing secrets in navigation routes. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val clock: Clock,
) : ViewModel() {

    val signedIn: StateFlow<Boolean> = authRepository.signedIn
    val profileSetupRequired: StateFlow<Boolean> = authRepository.profileSetupState
        .map { it.requiresSetup }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            authRepository.profileSetupState.value.requiresSetup,
        )

    private val _uiState = MutableStateFlow(AuthUiState())
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
        launchAuth {
            val capabilities = authRepository.phoneAuthCapabilities()
            check(capabilities.serverPhoneOtp) {
                "Phone sign-in is temporarily unavailable. Use email instead."
            }
            val challenge = authRepository.requestPhoneOtp(phone)
            _uiState.value = AuthUiState(
                pendingChallenge = challenge,
                pendingPhone = phone,
                resendNotBeforeEpochMillis = challenge.resendDeadline(clock.millis()),
            )
            onChallengeReady()
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
        launchAuth {
            handleOutcome(
                authRepository.loginWithEmail(email.trim(), password),
                pendingPhone = null,
                onChallengeReady = onChallengeReady,
                onAuthenticated = onAuthenticated,
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

        launchAuth {
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
            )
        }
    }

    fun resendPhoneOtp() {
        val state = _uiState.value
        val phone = state.pendingPhone ?: return
        val challenge = state.pendingChallenge ?: return
        if (challenge.kind != AuthChallengeKind.PHONE_OTP) return
        if (!isResendCooldownElapsed(state.resendNotBeforeEpochMillis, clock.millis())) return
        launchAuth {
            when (challenge.kind) {
                AuthChallengeKind.PHONE_OTP -> {
                    val renewed = authRepository.requestPhoneOtp(phone)
                    _uiState.value = AuthUiState(
                        pendingChallenge = renewed,
                        pendingPhone = phone,
                        resendNotBeforeEpochMillis = renewed.resendDeadline(clock.millis()),
                    )
                }
                AuthChallengeKind.TWO_FACTOR -> Unit
            }
        }
    }

    fun clearPendingChallenge() {
        _uiState.value = AuthUiState()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun logoutCurrentDevice() {
        launchAuth { authRepository.logout(allDevices = false) }
    }

    private suspend fun handleOutcome(
        outcome: AuthOutcome,
        pendingPhone: String?,
        onChallengeReady: () -> Unit,
        onAuthenticated: (
            needsPaymentPinSetup: Boolean,
            needsProfileSetup: Boolean,
        ) -> Unit,
    ) {
        when (outcome) {
            is AuthOutcome.Authenticated -> {
                _uiState.value = AuthUiState()
                onAuthenticated(
                    !outcome.user.paymentPinSet,
                    outcome.user.profileSetupRequired,
                )
            }
            is AuthOutcome.ChallengeRequired -> {
                _uiState.value = AuthUiState(
                    pendingChallenge = outcome.challenge,
                    pendingPhone = pendingPhone,
                    resendNotBeforeEpochMillis = outcome.challenge.resendDeadline(clock.millis()),
                )
                onChallengeReady()
            }
        }
    }

    private fun launchAuth(block: suspend () -> Unit) {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
            } finally {
                if (_uiState.value.loading) {
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
}

private fun PendingAuthChallenge.resendDeadline(nowEpochMillis: Long): Long? = when (kind) {
    AuthChallengeKind.PHONE_OTP -> phoneResendDeadline(
        nowEpochMillis = nowEpochMillis,
        resendAfterSeconds = resendAfterSeconds,
    )
    AuthChallengeKind.TWO_FACTOR -> resendAfterSeconds
        ?.coerceIn(0L, MAX_RESEND_COOLDOWN_SECONDS)
        ?.let { seconds -> nowEpochMillis + seconds * 1_000L }
}

/**
 * A missing phone cooldown is not permission to hammer the SMS endpoint. The production contract
 * normally returns 60 seconds, but old/null responses must remain conservative as well.
 */
internal fun phoneResendDeadline(
    nowEpochMillis: Long,
    resendAfterSeconds: Long?,
): Long = nowEpochMillis +
    (resendAfterSeconds ?: DEFAULT_PHONE_RESEND_COOLDOWN_SECONDS)
        .coerceIn(0L, MAX_RESEND_COOLDOWN_SECONDS) * 1_000L

internal fun isResendCooldownElapsed(
    notBeforeEpochMillis: Long?,
    nowEpochMillis: Long,
): Boolean = notBeforeEpochMillis != null &&
    resendSecondsRemaining(notBeforeEpochMillis, nowEpochMillis) == 0L

internal fun resendSecondsRemaining(notBeforeEpochMillis: Long?, nowEpochMillis: Long): Long {
    val remainingMillis = (notBeforeEpochMillis ?: nowEpochMillis) - nowEpochMillis
    return if (remainingMillis <= 0L) 0L else (remainingMillis + 999L) / 1_000L
}

private const val DEFAULT_PHONE_RESEND_COOLDOWN_SECONDS = 60L
private const val MAX_RESEND_COOLDOWN_SECONDS = 3_600L
