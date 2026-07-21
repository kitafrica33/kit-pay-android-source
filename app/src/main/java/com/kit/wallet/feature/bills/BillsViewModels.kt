package com.kit.wallet.feature.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.BillsRepository
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.ui.model.BillProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BillsViewModel @Inject constructor(private val billsRepo: BillsRepository) : ViewModel() {
    val providers = billsRepo.providers
    val airtimeProducts = billsRepo.airtimeProducts
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (mutableLoading.value) return
        viewModelScope.launch {
            mutableLoading.value = true
            mutableError.value = null
            runCatching { billsRepo.refresh() }
                .onFailure {
                    mutableError.value = it.message ?: "Bill providers are temporarily unavailable"
                }
            mutableLoading.value = false
        }
    }
}

@HiltViewModel
class BillPayViewModel @Inject constructor(
    private val billsRepo: BillsRepository,
    private val wallet: WalletRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val providerId: String = savedStateHandle.get<String>("providerId")
        ?.trim()
        .orEmpty()

    val provider = billsRepo.providers
        .map { providers -> providers.firstOrNull { it.id == providerId } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            providerId.takeIf(String::isNotBlank)?.let(billsRepo::provider),
        )

    private val _paying = MutableStateFlow(false)
    val paying = _paying.asStateFlow()

    private val _error = MutableStateFlow<String?>(
        if (providerId.isBlank()) {
            "This bill-payment link is invalid. Go back and choose a provider."
        } else {
            null
        },
    )
    val error = _error.asStateFlow()

    init {
        if (providerId.isNotBlank() && provider.value == null) {
            viewModelScope.launch {
                runCatching { billsRepo.refresh() }
                    .onSuccess {
                        if (billsRepo.provider(providerId) == null) {
                            _error.value = "The selected bill provider is no longer available"
                        }
                    }
                    .onFailure { _error.value = it.message ?: "The bill provider is unavailable" }
            }
        }
    }

    fun pay(account: String, amountMinor: Long, paymentPin: String, onDone: () -> Unit) {
        if (_paying.value) return
        viewModelScope.launch {
            _paying.value = true
            _error.value = null
            runCatching {
                val selectedProvider = requireNotNull(provider.value) {
                    "The selected bill provider is no longer available"
                }
                wallet.payBill(selectedProvider, account, amountMinor, paymentPin)
            }
                .onSuccess { onDone() }
                .onFailure { _error.value = it.message ?: "The bill payment could not be completed" }
            _paying.value = false
        }
    }
}

@HiltViewModel
class AirtimeViewModel @Inject constructor(
    private val wallet: WalletRepository,
    private val billsRepo: BillsRepository,
    userRepo: UserRepository,
) : ViewModel() {

    val ownPhone = userRepo.profile.value.phone
    val products = billsRepo.airtimeProducts

    private val _buying = MutableStateFlow(false)
    val buying = _buying.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        if (products.value.isEmpty()) {
            viewModelScope.launch {
                runCatching { billsRepo.refresh() }
                    .onFailure { _error.value = it.message ?: "Airtime is temporarily unavailable" }
            }
        }
    }

    fun buy(
        productId: String,
        phone: String,
        amountMinor: Long,
        paymentPin: String,
        onDone: () -> Unit,
    ) {
        if (_buying.value) return
        viewModelScope.launch {
            _buying.value = true
            _error.value = null
            runCatching { wallet.buyAirtime(productId, phone, amountMinor, paymentPin) }
                .onSuccess { onDone() }
                .onFailure { _error.value = it.message ?: "The airtime purchase could not be completed" }
            _buying.value = false
        }
    }
}
