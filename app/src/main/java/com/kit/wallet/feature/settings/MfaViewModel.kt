package com.kit.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.MfaCodeRequest
import com.kit.wallet.data.remote.TotpEnrollmentDto
import com.kit.wallet.data.auth.normalizeMfaFactorCode
import com.kit.wallet.data.auth.normalizeSixDigitCode
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MfaUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val enrollment: TotpEnrollmentDto? = null,
    val recoveryCodes: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class MfaViewModel @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MfaUiState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = launch {
        val bootstrap = apiCalls.execute { api.bootstrap() }
        mutableState.value = mutableState.value.copy(
            enabled = bootstrap.user.mfaEnabled == true,
            enrollment = null,
            recoveryCodes = emptyList(),
        )
    }

    fun enroll() = launch {
        val enrollment = apiCalls.execute { api.enrollTotp() }
        mutableState.value = mutableState.value.copy(
            enrollment = enrollment,
            recoveryCodes = emptyList(),
            message = "Add Kit Pay to your authenticator, then enter its current code.",
        )
    }

    fun confirm(code: String) {
        val normalizedCode = normalizeSixDigitCode(code)
        if (normalizedCode == null) {
            mutableState.value = mutableState.value.copy(error = "Enter the 6-digit authenticator code.")
            return
        }
        launch {
            val result = apiCalls.execute { api.confirmTotp(MfaCodeRequest(normalizedCode)) }
            check(result.enabled == true) { "Two-step verification was not enabled" }
            val recoveryCodes = requireReturnedRecoveryCodes(result.recoveryCodes)
            mutableState.value = mutableState.value.copy(
                enabled = true,
                enrollment = null,
                recoveryCodes = recoveryCodes,
                message = "Two-step verification is on. Save these recovery codes now.",
            )
        }
    }

    fun regenerateRecoveryCodes(code: String) {
        val normalizedCode = normalizeMfaFactorCode(code)
        if (normalizedCode == null) {
            mutableState.value = mutableState.value.copy(
                error = "Enter a current authenticator code or a complete recovery code.",
            )
            return
        }
        launch {
            val result = apiCalls.execute {
                api.regenerateRecoveryCodes(MfaCodeRequest(normalizedCode))
            }
            val recoveryCodes = requireReturnedRecoveryCodes(result.recoveryCodes)
            mutableState.value = mutableState.value.copy(
                recoveryCodes = recoveryCodes,
                message = "New recovery codes created. Your previous codes no longer work.",
            )
        }
    }

    fun disable(code: String) {
        val normalizedCode = normalizeMfaFactorCode(code)
        if (normalizedCode == null) {
            mutableState.value = mutableState.value.copy(
                error = "Enter a current authenticator code or a complete recovery code.",
            )
            return
        }
        launch {
            val result = apiCalls.execute { api.disableTotp(MfaCodeRequest(normalizedCode)) }
            check(result.enabled == false) { "Two-step verification was not disabled" }
            mutableState.value = mutableState.value.copy(
                enabled = false,
                enrollment = null,
                recoveryCodes = emptyList(),
                message = "Two-step verification is off.",
            )
        }
    }

    fun clearRecoveryCodes() {
        mutableState.value = mutableState.value.copy(recoveryCodes = emptyList())
    }

    fun clearFeedback() {
        mutableState.value = mutableState.value.copy(error = null, message = null)
    }

    private fun launch(block: suspend () -> Unit) {
        if (mutableState.value.loading && mutableState.value != MfaUiState()) return
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

    private fun Throwable.userMessage(): String = when (this) {
        is KitWalletApiException -> message
        is IOException -> "Kit Pay could not connect. Check your internet and try again."
        else -> message?.takeIf(String::isNotBlank) ?: "The security change could not be completed."
    }
}

internal fun requireReturnedRecoveryCodes(codes: List<String>?): List<String> {
    val normalized = codes.orEmpty().map(String::trim).filter(String::isNotEmpty)
    check(normalized.isNotEmpty()) {
        "The security response did not include recovery codes. Refresh and try again."
    }
    return normalized
}
