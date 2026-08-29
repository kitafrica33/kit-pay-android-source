package com.kit.wallet.feature.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.isKitInsufficientFundsError
import com.kit.wallet.data.repository.BillsRepository
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.TopUp
import com.kit.wallet.ui.model.TopUpRequirement
import com.kit.wallet.ui.model.TxStatus
import kotlinx.coroutines.CancellationException
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
    private val walletSync: WalletSyncRepository,
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

    private val _quote = MutableStateFlow<FinancialOperationQuote?>(null)
    val quote = _quote.asStateFlow()

    private val _topUpRequired = MutableStateFlow<TopUpRequirement?>(null)
    val topUpRequired = _topUpRequired.asStateFlow()

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

    /** Fetches the authoritative amount/fee/total for review; nothing is debited yet. */
    fun review(account: String, amountMinor: Long) {
        if (_paying.value) return
        viewModelScope.launch {
            _paying.value = true
            _error.value = null
            runCatching {
                val selectedProvider = requireNotNull(provider.value) {
                    "The selected bill provider is no longer available"
                }
                wallet.previewBill(selectedProvider, account, amountMinor)
            }
                .onSuccess {
                    _quote.value = it
                    _topUpRequired.value = shortfallFor(it)
                }
                .onFailure { _error.value = it.message ?: "The bill quote could not be prepared" }
            _paying.value = false
        }
    }

    /** Editing any detail invalidates the reviewed quote so a stale fee is never approved. */
    fun invalidateQuote() {
        _quote.value = null
        _topUpRequired.value = null
    }

    fun clearTopUpRequired() {
        _topUpRequired.value = null
    }

    fun pay(paymentPin: String, onDone: () -> Unit) {
        if (_paying.value) return
        val reviewed = _quote.value ?: return
        viewModelScope.launch {
            _paying.value = true
            _error.value = null
            try {
                try {
                    val operation = wallet.submitProviderOperation(reviewed, paymentPin)
                    _quote.value = null
                    if (operation.status == TxStatus.FAILED) {
                        _error.value = "The bill payment failed. Your held balance has been released."
                    } else {
                        onDone()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure.isKitInsufficientFundsError()) {
                        // A server refusal is newer than the cached wallet row. Refresh before
                        // deriving the shortfall and retain the reviewed quote for the retry.
                        try {
                            val refreshed = walletSync.refresh()
                            val shortfall = shortfallFor(
                                reviewed,
                                refreshed.selectedAvailableBalanceMinor
                                    ?: wallet.balanceMinor.value,
                            )
                            if (shortfall != null) {
                                _topUpRequired.value = shortfall
                            } else {
                                _error.value = failure.message
                                    ?: "The bill payment could not be completed"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (refreshFailure: Exception) {
                            _error.value = refreshFailure.message
                                ?: "Your wallet balance could not be refreshed"
                        }
                    } else {
                        _quote.value = null
                        _error.value = failure.message ?: "The bill payment could not be completed"
                    }
                }
            } finally {
                _paying.value = false
            }
        }
    }

    private fun shortfallFor(
        quote: FinancialOperationQuote,
        balanceMinor: Long = wallet.balanceMinor.value,
    ): TopUpRequirement? =
        TopUp.requirementFor(
            requiredMinor = quote.customerDebitMinor,
            balanceMinor = balanceMinor,
            currencyCode = quote.currencyCode,
            currencyScale = quote.currencyScale,
        )
}

@HiltViewModel
class AirtimeViewModel @Inject constructor(
    private val wallet: WalletRepository,
    private val walletSync: WalletSyncRepository,
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

    private val _quote = MutableStateFlow<FinancialOperationQuote?>(null)
    val quote = _quote.asStateFlow()

    private val _topUpRequired = MutableStateFlow<TopUpRequirement?>(null)
    val topUpRequired = _topUpRequired.asStateFlow()

    /** Fetches the authoritative amount/fee/total for review; nothing is debited yet. */
    fun review(productId: String, phone: String, amountMinor: Long) {
        if (_buying.value) return
        viewModelScope.launch {
            _buying.value = true
            _error.value = null
            runCatching { wallet.previewAirtime(productId, phone, amountMinor) }
                .onSuccess {
                    _quote.value = it
                    _topUpRequired.value = shortfallFor(it)
                }
                .onFailure { _error.value = it.message ?: "The airtime quote could not be prepared" }
            _buying.value = false
        }
    }

    /** Editing any detail invalidates the reviewed quote so a stale fee is never approved. */
    fun invalidateQuote() {
        _quote.value = null
        _topUpRequired.value = null
    }

    fun clearTopUpRequired() {
        _topUpRequired.value = null
    }

    fun buy(paymentPin: String, onDone: () -> Unit) {
        if (_buying.value) return
        val reviewed = _quote.value ?: return
        viewModelScope.launch {
            _buying.value = true
            _error.value = null
            try {
                try {
                    val operation = wallet.submitProviderOperation(reviewed, paymentPin)
                    _quote.value = null
                    if (operation.status == TxStatus.FAILED) {
                        _error.value = "The airtime purchase failed. Your held balance has been released."
                    } else {
                        onDone()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure.isKitInsufficientFundsError()) {
                        try {
                            val refreshed = walletSync.refresh()
                            val shortfall = shortfallFor(
                                reviewed,
                                refreshed.selectedAvailableBalanceMinor
                                    ?: wallet.balanceMinor.value,
                            )
                            if (shortfall != null) {
                                _topUpRequired.value = shortfall
                            } else {
                                _error.value = failure.message
                                    ?: "The airtime purchase could not be completed"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (refreshFailure: Exception) {
                            _error.value = refreshFailure.message
                                ?: "Your wallet balance could not be refreshed"
                        }
                    } else {
                        _quote.value = null
                        _error.value = failure.message ?: "The airtime purchase could not be completed"
                    }
                }
            } finally {
                _buying.value = false
            }
        }
    }

    private fun shortfallFor(
        quote: FinancialOperationQuote,
        balanceMinor: Long = wallet.balanceMinor.value,
    ): TopUpRequirement? =
        TopUp.requirementFor(
            requiredMinor = quote.customerDebitMinor,
            balanceMinor = balanceMinor,
            currencyCode = quote.currencyCode,
            currencyScale = quote.currencyScale,
        )
}
