package com.kit.wallet.feature.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.BankingRepository
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.ui.model.BankOperationKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

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
    private val mutableQuote = MutableStateFlow<FinancialOperationQuote?>(null)
    val quote = mutableQuote.asStateFlow()

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

    fun preview(
        operation: BankOperationKind,
        beneficiaryId: String,
        amountMinor: Long,
        feeMode: String,
    ) {
        runCommand {
            mutableQuote.value = banking.previewOperation(
                operation.apiType, beneficiaryId, amountMinor, feeMode,
            )
        }
    }

    fun submit(paymentPin: String, onDone: () -> Unit) {
        val quote = mutableQuote.value ?: return
        runCommand(onDone) {
            banking.submitOperation(quote, paymentPin)
            mutableQuote.value = null
        }
    }

    fun clearQuote() { mutableQuote.value = null }

    fun clearError() {
        mutableError.value = null
    }

    private fun runCommand(onDone: () -> Unit = {}, command: suspend () -> Unit) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            try {
                command()
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The bank request could not be completed"
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
