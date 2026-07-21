package com.kit.wallet.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.ui.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepo: ContactRepository,
    private val chatRepo: ChatRepository,
) : ViewModel() {
    val contacts = contactRepo.contacts

    private val mutableSyncing = MutableStateFlow(false)
    val syncing = mutableSyncing.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    private val mutableOpeningContactId = MutableStateFlow<String?>(null)
    val openingContactId = mutableOpeningContactId.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = run { launchSync(deviceContacts = false) }

    fun syncDeviceContacts() = run { launchSync(deviceContacts = true) }

    fun clearError() {
        mutableError.value = null
    }

    fun openDirectConversation(contact: Contact, onOpened: (String) -> Unit) {
        if (mutableOpeningContactId.value != null) return
        if (!contact.isKitUser) {
            mutableError.value = "Invite this contact to Kit Pay before starting a secure chat."
            return
        }
        if (!chatRepo.readiness.value) {
            mutableError.value = "Secure messaging is not ready on this device yet."
            return
        }
        viewModelScope.launch {
            mutableOpeningContactId.value = contact.id
            mutableError.value = null
            try {
                onOpened(chatRepo.openDirectConversation(contact))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "Unable to start a secure conversation"
            } finally {
                mutableOpeningContactId.value = null
            }
        }
    }

    private fun launchSync(deviceContacts: Boolean) {
        if (mutableSyncing.value) return
        viewModelScope.launch {
            mutableSyncing.value = true
            mutableError.value = null
            try {
                if (deviceContacts) contactRepo.syncDeviceContacts() else contactRepo.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "Unable to load contacts"
            } finally {
                mutableSyncing.value = false
            }
        }
    }
}
