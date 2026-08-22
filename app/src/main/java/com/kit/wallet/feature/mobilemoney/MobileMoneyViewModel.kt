package com.kit.wallet.feature.mobilemoney

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.MobileMoneyRepository
import com.kit.wallet.data.repository.FinancialOperationQuote
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    private val mutableQuote = MutableStateFlow<FinancialOperationQuote?>(null)
    val quote = mutableQuote.asStateFlow()

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

    fun preview(
        action: String,
        accountId: String,
        amountMinor: Long,
        feeMode: String,
    ) {
        runCommand {
            mutableQuote.value = mobileMoney.previewOperation(action, accountId, amountMinor, feeMode)
        }
    }

    fun submit(paymentPin: String, onDone: () -> Unit) {
        val quote = mutableQuote.value ?: return
        runCommand(onDone) {
            mobileMoney.submitOperation(quote, paymentPin)
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
                mutableError.value = error.message
                    ?.takeIf(String::isNotBlank)
                    ?: "The mobile money request could not be completed"
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
