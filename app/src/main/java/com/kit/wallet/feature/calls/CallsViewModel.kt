package com.kit.wallet.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class CallsViewModel @Inject constructor(
    callRepo: CallRepository,
) : ViewModel() {
    val calls = callRepo.calls

    init {
        viewModelScope.launch { runCatching { callRepo.refresh() } }
    }
}
