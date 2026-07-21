package com.kit.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.DeviceDto
import com.kit.wallet.data.remote.KitWalletApi
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
    val error: String? = null,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SecurityUiState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            runCatching { apiCalls.execute { api.bootstrap() } }
                .onSuccess { bootstrap ->
                    mutableState.value = SecurityUiState(
                        loading = false,
                        paymentPinSet = bootstrap.user.paymentPinSet == true,
                        mfaEnabled = bootstrap.user.mfaEnabled == true,
                        devices = bootstrap.devices,
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
