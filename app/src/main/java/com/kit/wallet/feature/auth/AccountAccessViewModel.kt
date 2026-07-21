package com.kit.wallet.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.normalizeProfileTag
import com.kit.wallet.data.auth.profileIdentityValidationError
import com.kit.wallet.data.remote.KitWalletApiException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountAccessUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val email: String = "",
    val verificationDestination: String? = null,
    val identityToken: String = "",
)

@HiltViewModel
class AccountAccessViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountAccessUiState())
    val state = mutableState.asStateFlow()

    fun register(
        name: String,
        tag: String,
        email: String,
        password: String,
        confirmation: String,
        onRegistered: () -> Unit,
    ) {
        val validationError = validateRegistration(name, tag, email, password, confirmation)
        if (validationError != null) {
            mutableState.value = mutableState.value.copy(error = validationError, message = null)
            return
        }
        launch {
            val result = authRepository.registerWithEmail(
                name = name,
                tag = normalizeProfileTag(tag),
                email = email,
                password = password,
                passwordConfirmation = confirmation,
            )
            mutableState.value = mutableState.value.copy(
                email = result.email,
                verificationDestination = result.destination,
                message = "We sent a verification token to ${result.destination}.",
            )
            onRegistered()
        }
    }

    fun verifyEmail(token: String, onVerified: () -> Unit) {
        if (token.trim().length < MIN_TOKEN_LENGTH) {
            mutableState.value = mutableState.value.copy(
                error = "Paste the complete verification token from your email.",
                message = null,
            )
            return
        }
        launch {
            authRepository.verifyEmail(token)
            mutableState.value = mutableState.value.copy(
                identityToken = "",
                message = "Email verified. You can now sign in.",
            )
            onVerified()
        }
    }

    fun resendVerification() {
        val email = mutableState.value.email
        if (email.isBlank()) {
            mutableState.value = mutableState.value.copy(
                error = "Enter your email from the sign-in screen and try again.",
            )
            return
        }
        launch {
            val message = authRepository.resendEmailVerification(email)
            mutableState.value = mutableState.value.copy(message = message)
        }
    }

    fun forgotPassword(email: String, onRequested: () -> Unit) {
        if (!email.isValidEmail()) {
            mutableState.value = mutableState.value.copy(error = "Enter a valid email address.")
            return
        }
        launch {
            val message = authRepository.forgotPassword(email)
            mutableState.value = mutableState.value.copy(email = email.trim(), message = message)
            onRequested()
        }
    }

    fun resetPassword(
        token: String,
        password: String,
        confirmation: String,
        onReset: () -> Unit,
    ) {
        val error = when {
            token.trim().length < MIN_TOKEN_LENGTH -> "Paste the complete reset token from your email."
            !password.isStrongPassword() -> PASSWORD_REQUIREMENTS
            password != confirmation -> "The passwords do not match."
            else -> null
        }
        if (error != null) {
            mutableState.value = mutableState.value.copy(error = error, message = null)
            return
        }
        launch {
            authRepository.resetPassword(token, password, confirmation)
            mutableState.value = mutableState.value.copy(
                identityToken = "",
                message = "Password updated. Sign in with your new password.",
            )
            onReset()
        }
    }

    fun setIdentityToken(token: String) {
        mutableState.value = mutableState.value.copy(
            identityToken = token.trim(),
            error = null,
        )
    }

    fun setEmail(email: String) {
        mutableState.value = mutableState.value.copy(
            email = email.trim(),
            error = null,
        )
    }

    fun clearFeedback() {
        mutableState.value = mutableState.value.copy(error = null, message = null)
    }

    private fun validateRegistration(
        name: String,
        tag: String,
        email: String,
        password: String,
        confirmation: String,
    ): String? {
        profileIdentityValidationError(name, tag)?.let { return it }
        return when {
            !email.isValidEmail() -> "Enter a valid email address."
            !password.isStrongPassword() -> PASSWORD_REQUIREMENTS
            password != confirmation -> "The passwords do not match."
            else -> null
        }
    }

    private fun launch(block: suspend () -> Unit) {
        if (mutableState.value.loading) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null, message = null)
            try {
                block()
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(error = error.userMessage())
            } finally {
                mutableState.value = mutableState.value.copy(loading = false)
            }
        }
    }

    private fun String.isValidEmail() = trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))

    private fun String.isStrongPassword() = length >= 12 &&
        any(Char::isUpperCase) && any(Char::isLowerCase) && any(Char::isDigit)

    private fun Throwable.userMessage(): String = when (this) {
        is KitWalletApiException -> message
        is IOException -> "Kit Pay could not connect. Check your internet and try again."
        else -> message?.takeIf(String::isNotBlank) ?: "Kit Pay could not complete the request."
    }

    private companion object {
        const val MIN_TOKEN_LENGTH = 64
        const val PASSWORD_REQUIREMENTS =
            "Use at least 12 characters with uppercase, lowercase, and a number."
    }
}
