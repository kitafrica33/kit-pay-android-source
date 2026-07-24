package com.kit.wallet.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.ui.model.Contact
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    userRepo: UserRepository,
    walletRepo: WalletRepository,
    contactRepo: ContactRepository,
) : ViewModel() {

    val profile = userRepo.profile
    val balanceMinor = walletRepo.balanceMinor

    val recentTransactions = walletRepo.transactions
        .map { it.take(5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites = contactRepo.contacts
        // Every displayed favorite must be able to reach the preselected Send amount screen.
        // Invite-only or wallet-less contacts remain available in the full recipient picker.
        .map(::sendableHomeFavorites)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

internal fun sendableHomeFavorites(contacts: List<Contact>): List<Contact> = contacts.filter {
    it.favorite && it.id.isNotBlank() && it.isKitUser && !it.receivingWalletId.isNullOrBlank()
}
