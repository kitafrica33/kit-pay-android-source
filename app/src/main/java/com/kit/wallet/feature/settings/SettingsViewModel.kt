package com.kit.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ProfileEmailChallenge
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.auth.AccountDeletionPreflight
import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.normalizeProfileName
import com.kit.wallet.data.auth.normalizeProfileTag as canonicalProfileTag
import com.kit.wallet.data.auth.profileIdentityValidationError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileEditorUiState(
    val saving: Boolean = false,
    val error: String? = null,
    val uploadingAvatar: Boolean = false,
)

data class ProfileEmailUiState(
    val email: String = "",
    val challenge: ProfileEmailChallenge? = null,
    val resendNotBeforeEpochMillis: Long? = null,
    val requesting: Boolean = false,
    val verifying: Boolean = false,
    val error: String? = null,
)

data class AccountDeletionUiState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val preflight: AccountDeletionPreflight? = null,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepo: UserRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
) : ViewModel() {
    val profile = userRepo.profile

    private val mutableEditorState = MutableStateFlow(ProfileEditorUiState())
    val editorState = mutableEditorState.asStateFlow()

    private val mutableEmailState = MutableStateFlow(ProfileEmailUiState())
    val emailState = mutableEmailState.asStateFlow()

    private val mutableDeletionState = MutableStateFlow(AccountDeletionUiState())
    val deletionState = mutableDeletionState.asStateFlow()

    init {
        refreshProfile()
    }

    /**
     * Re-reads the profile from the server.
     *
     * Cached profile data keeps this screen useful offline; a failed refresh must not replace it
     * or prevent a later explicit edit. Called again whenever the editor resumes, because identity
     * verification can hand the account a legal name while the editor sits behind it on the stack,
     * and the screen would otherwise still be asking for a name the account already has.
     */
    fun refreshProfile() {
        viewModelScope.launch {
            runCatching { userRepo.refreshProfile() }
        }
    }

    fun saveProfile(name: String, tag: String, onSaved: () -> Unit) {
        if (mutableEditorState.value.let { it.saving || it.uploadingAvatar }) return
        val normalizedName = normalizeProfileName(name)
        val normalizedTag = normalizeProfileTag(tag)
        // A verified legal name makes both of these optional, exactly as the API has it. Validate
        // against the same fact the server will, or the screen refuses a save the API would take.
        profileValidationError(normalizedName, normalizedTag, profile.value.legalName)?.let { error ->
            mutableEditorState.value = mutableEditorState.value.copy(error = error)
            return
        }

        viewModelScope.launch {
            mutableEditorState.value = mutableEditorState.value.copy(saving = true, error = null)
            runCatching { userRepo.updateProfile(normalizedName, normalizedTag) }
                .onSuccess {
                    mutableEditorState.value = mutableEditorState.value.copy(
                        saving = false,
                        error = null,
                    )
                    onSaved()
                }
                .onFailure { error ->
                    mutableEditorState.value = mutableEditorState.value.copy(
                        saving = false,
                        error = error.message ?: "Could not update your profile",
                    )
                }
        }
    }

    fun clearProfileError() {
        mutableEditorState.value = mutableEditorState.value.copy(error = null)
    }

    /** Uploads an already-transcoded JPEG profile photo through the moderated media pipeline. */
    fun attachAvatar(jpegBytes: ByteArray) {
        if (mutableEditorState.value.let { it.saving || it.uploadingAvatar }) return
        viewModelScope.launch {
            mutableEditorState.value = mutableEditorState.value.copy(
                uploadingAvatar = true,
                error = null,
            )
            runCatching { userRepo.attachAvatar(jpegBytes) }
                .onSuccess {
                    mutableEditorState.value = mutableEditorState.value.copy(
                        uploadingAvatar = false,
                    )
                }
                .onFailure { error ->
                    mutableEditorState.value = mutableEditorState.value.copy(
                        uploadingAvatar = false,
                        error = error.message ?: "The profile photo could not be updated",
                    )
                }
        }
    }

    fun reportAvatarSelectionError(message: String) {
        mutableEditorState.value = mutableEditorState.value.copy(error = message)
    }

    fun beginEmailFlow(currentEmail: String?) {
        mutableEmailState.value = ProfileEmailUiState(email = currentEmail.orEmpty())
    }

    fun requestEmailAttachment(email: String) {
        val current = mutableEmailState.value
        if (current.requesting || current.verifying) return
        val normalized = email.trim().lowercase()
        if (!normalized.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            mutableEmailState.value = current.copy(
                email = normalized,
                error = "Enter a valid email address.",
            )
            return
        }

        val currentChallenge = current.challenge?.takeIf {
            current.email.equals(normalized, ignoreCase = true) && it.isValidAt(clock.instant())
        }
        val resendAllowed = isProfileEmailResendAllowed(
            current.resendNotBeforeEpochMillis,
            clock.millis(),
        )
        if (currentChallenge != null && !resendAllowed) {
            mutableEmailState.value = current.copy(
                error = "Wait for the resend cooldown before requesting another code.",
            )
            return
        }

        viewModelScope.launch {
            val pending = current.copy(
                email = normalized,
                challenge = currentChallenge,
                resendNotBeforeEpochMillis = current.resendNotBeforeEpochMillis
                    .takeIf { currentChallenge != null },
                requesting = true,
                error = null,
            )
            mutableEmailState.value = pending
            runCatching { userRepo.requestEmailAttachment(normalized) }
                .onSuccess { challenge ->
                    mutableEmailState.value = ProfileEmailUiState(
                        email = normalized,
                        challenge = challenge,
                        resendNotBeforeEpochMillis = profileEmailResendDeadline(
                            nowEpochMillis = clock.millis(),
                            resendAfterSeconds = challenge.resendAfterSeconds,
                        ),
                    )
                }
                .onFailure { error ->
                    val stillValidChallenge = pending.challenge?.takeIf {
                        it.isValidAt(clock.instant())
                    }
                    mutableEmailState.value = pending.copy(
                        challenge = stillValidChallenge,
                        // The server may have accepted a resend even if its response was lost.
                        // Keep the old proof usable, but apply a fresh local backoff before retry.
                        resendNotBeforeEpochMillis = stillValidChallenge?.let {
                            profileEmailResendDeadline(
                                nowEpochMillis = clock.millis(),
                                resendAfterSeconds = it.resendAfterSeconds,
                            )
                        },
                        requesting = false,
                        error = error.message ?: "Could not send an email verification code",
                    )
                }
        }
    }

    fun verifyEmailAttachment(code: String, onVerified: () -> Unit) {
        if (mutableEmailState.value.requesting || mutableEmailState.value.verifying) return
        val challenge = mutableEmailState.value.challenge ?: return
        val normalizedCode = code.filter(Char::isDigit)
        if (normalizedCode.length != 6) {
            mutableEmailState.value = mutableEmailState.value.copy(
                error = "Enter the complete 6-digit code.",
            )
            return
        }

        viewModelScope.launch {
            mutableEmailState.value = mutableEmailState.value.copy(
                verifying = true,
                error = null,
            )
            runCatching { userRepo.verifyEmailAttachment(challenge.id, normalizedCode) }
                .onSuccess {
                    mutableEmailState.value = ProfileEmailUiState()
                    onVerified()
                }
                .onFailure { error ->
                    mutableEmailState.value = mutableEmailState.value.copy(
                        verifying = false,
                        error = error.message ?: "Could not verify that email address",
                    )
                }
        }
    }

    fun dismissEmailFlow() {
        if (!mutableEmailState.value.requesting && !mutableEmailState.value.verifying) {
            mutableEmailState.value = ProfileEmailUiState()
        }
    }

    fun beginAccountDeletion() {
        if (mutableDeletionState.value.loading || mutableDeletionState.value.submitting) return
        viewModelScope.launch {
            mutableDeletionState.value = AccountDeletionUiState(loading = true)
            runCatching { authRepository.accountDeletionPreflight() }
                .onSuccess { preflight ->
                    mutableDeletionState.value = AccountDeletionUiState(preflight = preflight)
                }
                .onFailure { error ->
                    mutableDeletionState.value = AccountDeletionUiState(
                        error = error.message ?: "Could not load account deletion information",
                    )
                }
        }
    }

    fun requestAccountDeletion(confirmation: String, paymentPin: String) {
        val current = mutableDeletionState.value
        val preflight = current.preflight ?: return
        if (current.loading || current.submitting) return
        if (confirmation != preflight.confirmationText) {
            mutableDeletionState.value = current.copy(
                error = "Type ${preflight.confirmationText} exactly to continue.",
            )
            return
        }
        if (!paymentPin.matches(Regex("^[0-9]{4}$"))) {
            mutableDeletionState.value = current.copy(error = "Enter your four-digit wallet PIN.")
            return
        }

        viewModelScope.launch {
            mutableDeletionState.value = current.copy(submitting = true, error = null)
            runCatching {
                authRepository.requestAccountDeletion(preflight, confirmation, paymentPin)
            }.onFailure { error ->
                mutableDeletionState.value = current.copy(
                    submitting = false,
                    error = error.message ?: "Could not submit the account deletion request",
                )
            }
        }
    }

    fun dismissAccountDeletion() {
        if (!mutableDeletionState.value.submitting) {
            mutableDeletionState.value = AccountDeletionUiState()
        }
    }
}

internal fun normalizeProfileTag(value: String): String =
    canonicalProfileTag(value)

internal fun profileValidationError(
    name: String,
    tag: String,
    legalName: String? = null,
): String? = profileIdentityValidationError(name, tag, legalName)

internal fun profileEmailResendDeadline(
    nowEpochMillis: Long,
    resendAfterSeconds: Long?,
): Long = nowEpochMillis +
    (resendAfterSeconds ?: DEFAULT_EMAIL_RESEND_COOLDOWN_SECONDS)
        .coerceIn(0L, MAX_EMAIL_RESEND_COOLDOWN_SECONDS) * 1_000L

internal fun profileEmailResendSecondsRemaining(
    notBeforeEpochMillis: Long?,
    nowEpochMillis: Long,
): Long {
    val deadline = notBeforeEpochMillis ?: return 0L
    val remainingMillis = deadline - nowEpochMillis
    return if (remainingMillis <= 0L) 0L else (remainingMillis + 999L) / 1_000L
}

internal fun isProfileEmailResendAllowed(
    notBeforeEpochMillis: Long?,
    nowEpochMillis: Long,
): Boolean = notBeforeEpochMillis != null &&
    profileEmailResendSecondsRemaining(notBeforeEpochMillis, nowEpochMillis) == 0L

internal fun ProfileEmailChallenge.isValidAt(now: Instant): Boolean {
    val expiry = expiresAt?.takeIf(String::isNotBlank) ?: return true
    return runCatching { Instant.parse(expiry).isAfter(now) }.getOrDefault(false)
}

private const val DEFAULT_EMAIL_RESEND_COOLDOWN_SECONDS = 60L
private const val MAX_EMAIL_RESEND_COOLDOWN_SECONDS = 3_600L
