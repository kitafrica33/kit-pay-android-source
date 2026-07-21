package com.kit.wallet.feature.mobilemoney

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.MobileMoneyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MobileMoneyViewModel @Inject constructor(
    private val mobileMoney: MobileMoneyRepository,
) : ViewModel() {
    val networks = mobileMoney.networks
    val accounts = mobileMoney.accounts
    val operations = mobileMoney.operations
    val verification = mobileMoney.verification

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        runCommand { mobileMoney.refresh() }
    }

    fun addAccount(
        networkCode: String,
        phoneNumber: String,
        label: String,
        kind: String,
        onDone: () -> Unit,
    ) {
        runCommand(onDone) {
            mobileMoney.verifyAndSaveAccount(networkCode, phoneNumber, label, kind)
        }
    }

    fun operate(
        action: String,
        accountId: String,
        amountMinor: Long,
        paymentPin: String,
        onDone: () -> Unit,
    ) {
        runCommand(onDone) {
            mobileMoney.createOperation(action, accountId, amountMinor, paymentPin)
        }
    }

    fun clearError() {
        mutableError.value = null
    }

    private fun runCommand(onDone: () -> Unit = {}, command: suspend () -> Unit) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            runCatching { command() }
                .onSuccess { onDone() }
                .onFailure {
                    mutableError.value = it.message
                        ?.takeIf(String::isNotBlank)
                        ?: "The mobile money request could not be completed"
                }
            mutableBusy.value = false
        }
    }
}
