package com.kit.wallet.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.PaymentPinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PaymentPinViewModel @Inject constructor(
    private val repository: PaymentPinRepository,
) : ViewModel() {
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun set(pin: String, currentPin: String? = null, onDone: () -> Unit) {
        if (mutableLoading.value) return
        viewModelScope.launch {
            mutableLoading.value = true
            mutableError.value = null
            runCatching { repository.set(pin, currentPin) }
                .onSuccess { onDone() }
                .onFailure { mutableError.value = it.message ?: "Could not set the wallet PIN" }
            mutableLoading.value = false
        }
    }

    fun showError(message: String) {
        mutableError.value = message
    }
}
