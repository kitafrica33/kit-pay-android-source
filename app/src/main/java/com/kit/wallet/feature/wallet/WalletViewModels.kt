package com.kit.wallet.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SendMoneyViewModel @Inject constructor(
    private val wallet: WalletRepository,
    contactRepo: ContactRepository,
) : ViewModel() {

    val contacts = contactRepo.contacts
    val balanceMinor = wallet.balanceMinor

    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _lastSent = MutableStateFlow<Transaction?>(null)
    val lastSent = _lastSent.asStateFlow()

    fun send(
        recipient: Contact,
        amountMinor: Long,
        note: String?,
        paymentPin: String,
        onSent: () -> Unit,
    ) {
        if (_sending.value) return
        viewModelScope.launch {
            _sending.value = true
            _error.value = null
            runCatching { wallet.send(recipient, amountMinor, note, paymentPin) }
                .onSuccess {
                    _lastSent.value = it
                    onSent()
                }
                .onFailure { _error.value = it.message ?: "The transfer could not be completed" }
            _sending.value = false
        }
    }
}

@HiltViewModel
class RequestMoneyViewModel @Inject constructor(
    private val wallet: WalletRepository,
    contactRepo: ContactRepository,
) : ViewModel() {

    val contacts = contactRepo.contacts

    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun request(from: Contact, amountMinor: Long, note: String?, onDone: () -> Unit) {
        if (_sending.value) return
        viewModelScope.launch {
            _sending.value = true
            _error.value = null
            runCatching { wallet.request(from, amountMinor, note) }
                .onSuccess { onDone() }
                .onFailure { _error.value = it.message ?: "The request could not be sent" }
            _sending.value = false
        }
    }
}

@HiltViewModel
class TransactionsViewModel @Inject constructor(wallet: WalletRepository) : ViewModel() {
    val transactions = wallet.transactions
}

sealed interface TransactionDetailUiState {
    data object Loading : TransactionDetailUiState
    data object NotFound : TransactionDetailUiState
    data class Ready(val transaction: Transaction) : TransactionDetailUiState
}

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    wallet: WalletRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val txId: String = savedStateHandle.get<String>("txId").orEmpty()

    val uiState = wallet.transactions
        .map { transactions ->
            transactions.firstOrNull { it.id == txId }
                ?.let { TransactionDetailUiState.Ready(it) }
                ?: TransactionDetailUiState.NotFound
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TransactionDetailUiState.Loading,
        )
}

@HiltViewModel
class ReceiveViewModel @Inject constructor(userRepo: UserRepository) : ViewModel() {
    val profile = userRepo.profile
}
