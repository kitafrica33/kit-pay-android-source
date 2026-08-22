package com.kit.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.DeviceDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.auth.BiometricSigningKey
import com.kit.wallet.data.remote.EnrollBiometricKeyRequest
import com.kit.wallet.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SecurityUiState(
    val loading: Boolean = true,
    val paymentPinSet: Boolean = false,
    val mfaEnabled: Boolean = false,
    val devices: List<DeviceDto> = emptyList(),
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val configuringBiometrics: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val biometricKey: BiometricSigningKey,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SecurityUiState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            runCatching {
                val bootstrap = apiCalls.execute { api.bootstrap() }
                val capabilities = apiCalls.execute { api.capabilities() }
                val assurance = if (capabilities.authentication?.get("biometric_tokens") == true) {
                    apiCalls.execute { api.sessionAssurance() }.sessionAssurance
                } else null
                Triple(bootstrap, capabilities, assurance)
            }.onSuccess { (bootstrap, capabilities, assurance) ->
                    val accountId = sessions.current()?.accountId
                    val biometricAvailable = capabilities.authentication
                        ?.get("biometric_tokens") == true && biometricKey.isAvailable()
                    mutableState.value = SecurityUiState(
                        loading = false,
                        paymentPinSet = bootstrap.user.paymentPinSet == true,
                        mfaEnabled = bootstrap.user.mfaEnabled == true,
                        devices = bootstrap.devices,
                        biometricAvailable = biometricAvailable,
                        biometricEnabled = biometricAvailable && accountId != null &&
                            biometricKey.hasKey(accountId) && assurance?.loginUnlock?.methods
                            ?.any { it.equals("biometric_signature", ignoreCase = true) } == true,
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        error = it.message ?: "Could not load security settings",
                    )
                }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        val expected = sessions.snapshot()
        val accountId = expected.fence?.accountId ?: return
        if (!mutableState.value.biometricAvailable || mutableState.value.configuringBiometrics) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(configuringBiometrics = true, error = null)
            var serverEnrolled = false
            runCatching {
                if (enabled) {
                    val pem = biometricKey.publicKeyPem(accountId)
                    val result = apiCalls.execute {
                        api.enrollBiometricKey(
                            EnrollBiometricKeyRequest(
                                pem,
                                mapOf(
                                    "platform" to "android",
                                    "key_storage" to "android_keystore",
                                    "access_control" to "biometric_strong",
                                ),
                            ),
                        )
                    }
                    serverEnrolled = true
                    check(result.algorithm.equals("ES256", ignoreCase = true)) {
                        "The biometric key was not registered"
                    }
                } else {
                    val result = apiCalls.execute { api.removeBiometricKey() }
                    check(result.removed == true) { "The biometric key was not removed" }
                }
                check(sessions.snapshot() == expected) { "The signed-in account changed" }
                if (!enabled) biometricKey.remove(accountId)
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                if (enabled) {
                    if (serverEnrolled && sessions.snapshot() == expected) {
                        runCatching { apiCalls.execute { api.removeBiometricKey() } }
                    }
                    biometricKey.remove(accountId)
                }
                mutableState.value = mutableState.value.copy(
                    configuringBiometrics = false,
                    error = error.message ?: "Could not update biometric unlock",
                )
            }
        }
    }

    fun revoke(device: DeviceDto) {
        if (!canRevokeDevice(device)) return
        viewModelScope.launch {
            runCatching { apiCalls.execute { api.revokeDevice(device.id) } }
                .onSuccess { refresh() }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        error = it.message ?: "Could not sign out that device",
                    )
                }
        }
    }
}

/** Only an explicit server assertion that this is another device permits remote revocation. */
internal fun canRevokeDevice(device: DeviceDto): Boolean = device.isCurrent == false
