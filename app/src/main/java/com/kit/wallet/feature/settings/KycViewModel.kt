package com.kit.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.KycRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class KycViewModel @Inject constructor(
    private val kyc: KycRepository,
) : ViewModel() {
    val status = kyc.status

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    private val mutableLaunchUrl = MutableStateFlow<String?>(null)
    val launchUrl = mutableLaunchUrl.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        runCommand { kyc.refresh() }
    }

    fun startVerification(consent: Boolean) {
        if (!consent) {
            mutableLaunchUrl.value = null
            mutableError.value = "Consent is required before identity verification can start"
            return
        }
        runCommand {
            mutableLaunchUrl.value = kyc.startVerification(consent)
        }
    }

    fun continueVerification() {
        mutableLaunchUrl.value = status.value?.verificationUrl
    }

    fun consumeLaunchUrl() {
        mutableLaunchUrl.value = null
    }

    fun launchFailed() {
        mutableLaunchUrl.value = null
        mutableError.value = "No secure browser is available to open Didit verification"
    }

    private fun runCommand(command: suspend () -> Unit) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            runCatching { command() }
                .onFailure {
                    mutableError.value = it.message
                        ?.takeIf(String::isNotBlank)
                        ?: "Identity verification is temporarily unavailable"
                }
            mutableBusy.value = false
        }
    }
}
