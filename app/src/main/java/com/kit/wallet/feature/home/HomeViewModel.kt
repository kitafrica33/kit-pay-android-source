package com.kit.wallet.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.ui.model.Contact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    userRepo: UserRepository,
    walletRepo: WalletRepository,
    contactRepo: ContactRepository,
    private val walletSync: WalletSyncRepository,
) : ViewModel() {

    val profile = userRepo.profile
    val balanceMinor = walletRepo.balanceMinor

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    val recentTransactions = walletRepo.transactions
        .map { it.take(5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites = contactRepo.contacts
        // Every displayed favorite must be able to reach the preselected Send amount screen.
        // Invite-only or wallet-less contacts remain available in the full recipient picker.
        .map(::sendableHomeFavorites)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var visiblePollJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                walletSync.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Offline or transient server errors keep showing the durable cached balance.
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * The backend has no wallet push or realtime channel, so an incoming credit is only visible
     * on the next poll. While the home screen is on screen we poll quietly so received money
     * appears without a manual gesture; the loop stops as soon as the screen leaves composition.
     */
    fun setHomeVisible(visible: Boolean) {
        if (!visible) {
            visiblePollJob?.cancel()
            visiblePollJob = null
            return
        }
        if (visiblePollJob?.isActive == true) return
        visiblePollJob = viewModelScope.launch {
            while (true) {
                delay(VISIBLE_POLL_INTERVAL_MILLIS)
                try {
                    walletSync.refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retry on the next tick; the cached balance stays authoritative offline.
                }
            }
        }
    }

    private companion object {
        const val VISIBLE_POLL_INTERVAL_MILLIS = 20_000L
    }
}

internal fun sendableHomeFavorites(contacts: List<Contact>): List<Contact> = contacts.filter {
    it.favorite && it.id.isNotBlank() && it.isKitUser && !it.receivingWalletId.isNullOrBlank()
}
