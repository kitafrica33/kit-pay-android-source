package com.kit.wallet.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.notifications.ActiveCallStateHolder
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class CallActionsState(
    val contacts: List<Contact> = emptyList(),
    val selected: Set<String> = emptySet(),
    val groupPickerOpen: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class CallsViewModel @Inject constructor(
    callRepo: CallRepository,
    private val contacts: ContactRepository,
    private val sessions: SessionStore,
    private val activeCalls: ActiveCallStateHolder,
) : ViewModel() {
    val calls = callRepo.calls
    private val mutableActions = MutableStateFlow(CallActionsState())
    val actions = mutableActions.asStateFlow()
    private var owner: com.kit.wallet.data.session.SessionFence? = null

    init {
        viewModelScope.launch {
            sessions.session.map { it?.fence() }.distinctUntilChanged().collectLatest { next ->
                owner = next
                mutableActions.value = CallActionsState()
                if (next == null) return@collectLatest
                try {
                    callRepo.refresh()
                    contacts.refresh()
                    sessions.withCurrentSession(next) {
                        mutableActions.update { it.copy(contacts = selectableCallContacts(contacts.contacts.value, next.accountId)) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Existing authenticated contacts remain available without a device sync.
                }
            }
        }
    }

    fun openGroupPicker() {
        if (owner == null || owner != sessions.current()?.fence()) return
        mutableActions.update {
            it.copy(groupPickerOpen = true, selected = emptySet(), error = null,
                contacts = selectableCallContacts(contacts.contacts.value, owner?.accountId))
        }
    }

    fun closeGroupPicker() = mutableActions.update { it.copy(groupPickerOpen = false, selected = emptySet(), error = null) }

    fun toggleRecipient(id: String) {
        if (owner == null || owner != sessions.current()?.fence() || actions.value.contacts.none { it.id == id }) return
        mutableActions.update {
            when {
                id in it.selected -> it.copy(selected = it.selected - id, error = null)
                it.selected.size < ScheduledCallPolicy.MAX_RECIPIENTS -> it.copy(selected = it.selected + id, error = null)
                else -> it.copy(error = "Choose at most 20 contacts.")
            }
        }
    }

    fun groupTarget(): String? {
        if (owner == null || owner != sessions.current()?.fence()) return null
        if (activeCalls.activeCallId.value != null) {
            mutableActions.update { it.copy(error = "Finish your current call before starting another.") }
            return null
        }
        val ids = runCatching { ScheduledCallPolicy.recipients(actions.value.selected.toList(), owner?.accountId) }.getOrNull() ?: return null
        closeGroupPicker()
        return ids.joinToString(",")
    }
}
