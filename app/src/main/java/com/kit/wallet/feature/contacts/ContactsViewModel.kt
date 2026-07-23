package com.kit.wallet.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.ui.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
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

    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()

    /** Kit Pay members found for an `@kittag` query; empty for device-contact queries. */
    private val mutableTagResults = MutableStateFlow<List<Contact>>(emptyList())
    val tagResults = mutableTagResults.asStateFlow()

    private val mutableTagSearching = MutableStateFlow(false)
    val tagSearching = mutableTagSearching.asStateFlow()

    init {
        refresh()
        observeTagQueries()
    }

    fun refresh() = run { launchSync(deviceContacts = false) }

    fun syncDeviceContacts() = run { launchSync(deviceContacts = true) }

    fun clearError() {
        mutableError.value = null
    }

    /**
     * A query starting with `@` searches the Kit Pay member directory by kit tag; anything else
     * filters the device/synced contact list locally.
     */
    fun setQuery(value: String) {
        mutableQuery.value = value
        if (!value.trim().startsWith("@")) {
            mutableTagResults.value = emptyList()
            mutableTagSearching.value = false
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeTagQueries() {
        viewModelScope.launch {
            mutableQuery.debounce(TAG_SEARCH_DEBOUNCE_MILLIS).collectLatest { raw ->
                val value = raw.trim()
                if (!value.startsWith("@") || value.removePrefix("@").trim().length < 2) {
                    mutableTagResults.value = emptyList()
                    return@collectLatest
                }
                mutableTagSearching.value = true
                try {
                    mutableTagResults.value = contactRepo.searchByKitTag(value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Directory search is best-effort; the local list stays usable either way.
                    mutableTagResults.value = emptyList()
                } finally {
                    mutableTagSearching.value = false
                }
            }
        }
    }

    fun openDirectConversation(contact: Contact, onOpened: (String) -> Unit) {
        if (mutableOpeningContactId.value != null) return
        if (!chatRepo.readiness.value) {
            mutableError.value = "Secure messaging is not ready on this device yet."
            return
        }
        viewModelScope.launch {
            mutableOpeningContactId.value = contact.id
            mutableError.value = null
            try {
                val resolved = contactRepo.resolveForMessaging(contact)
                if (resolved == null) {
                    mutableError.value =
                        "Invite this contact to Kit Pay before starting a secure chat."
                    return@launch
                }
                onOpened(chatRepo.openDirectConversation(resolved))
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
                // Offline is not an error worth showing: the cached and on-device contacts stay on
                // screen and the list refreshes automatically once the network returns.
                if (!error.isKitConnectivityError()) {
                    mutableError.value = error.message ?: "Unable to load contacts"
                }
            } finally {
                mutableSyncing.value = false
            }
        }
    }

    private companion object {
        const val TAG_SEARCH_DEBOUNCE_MILLIS = 350L
    }
}
