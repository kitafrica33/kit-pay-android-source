package com.kit.wallet.feature.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.BankingRepository
import com.kit.wallet.ui.model.BankOperationKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BankViewModel @Inject constructor(
    private val banking: BankingRepository,
) : ViewModel() {

    val beneficiaries = banking.beneficiaries
    val bankTransfers = banking.operations
    val banks = banking.banks

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init {
        viewModelScope.launch { runCatching { banking.refresh() } }
    }

    fun addAccount(
        bankId: String,
        accountNumber: String,
        label: String,
        kind: String,
        onDone: () -> Unit,
    ) {
        runCommand(onDone) { banking.addBeneficiary(bankId, accountNumber, label, kind) }
    }

    fun operate(
        operation: BankOperationKind,
        beneficiaryId: String,
        amountMinor: Long,
        paymentPin: String,
        onDone: () -> Unit,
    ) {
        runCommand(onDone) {
            banking.createOperation(operation.apiType, beneficiaryId, amountMinor, paymentPin)
        }
    }

    fun clearError() {
        mutableError.value = null
    }

    private fun runCommand(onDone: () -> Unit, command: suspend () -> Unit) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            runCatching { command() }
                .onSuccess { onDone() }
                .onFailure { mutableError.value = it.message ?: "The bank request could not be completed" }
            mutableBusy.value = false
        }
    }
}
