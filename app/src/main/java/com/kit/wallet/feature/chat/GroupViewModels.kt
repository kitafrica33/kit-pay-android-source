package com.kit.wallet.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.MAX_GROUP_MEMBERS
import com.kit.wallet.data.remote.isValidMessagingGroupTitle
import com.kit.wallet.data.remote.truncateMessagingGroupTitle
import com.kit.wallet.data.remote.KIT_NETWORK_UNAVAILABLE_MESSAGE
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many other people a group can hold besides this account. */
internal const val MAX_OTHER_GROUP_MEMBERS = MAX_GROUP_MEMBERS - 1

/**
 * Builds one group: who is in it, what it is called, and the one network call that creates it.
 *
 * Selection is held as a set of account IDs rather than of [Contact]s, so a contact list that
 * refreshes underneath the picker never duplicates or drops somebody already chosen.
 */
@HiltViewModel
class NewGroupViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val contactRepo: ContactRepository,
) : ViewModel() {
    val messagingAvailable = chatRepo.readiness

    /** Only Kit Pay members can be in a group: everybody in it has to be able to decrypt it. */
    private val kitContacts: StateFlow<List<Contact>> = contactRepo.contacts
        .map { contacts ->
            contacts.filter { it.isKitUser && it.id.isNotBlank() }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Contact::name))
        }
        // Eager, like everything the selection is made of: [create] reads these by value, so they
        // have to hold the real answer whether or not a screen happens to be collecting them.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    private val mutableSelectedIds = MutableStateFlow<Set<String>>(emptySet())

    private val mutableTitle = MutableStateFlow("")
    val title: StateFlow<String> = mutableTitle.asStateFlow()

    private val mutableCreating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = mutableCreating.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    /** The contacts on offer, filtered by the search box. Selected people always stay listed. */
    val contacts: StateFlow<List<Contact>> =
        combine(kitContacts, mutableQuery, mutableSelectedIds) { all, query, selected ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                all
            } else {
                all.filter { contact ->
                    contact.id in selected ||
                        contact.name.contains(trimmed, ignoreCase = true) ||
                        contact.phone.contains(trimmed)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Who is in the group so far, in the order the list reads rather than the order tapped. */
    val selected: StateFlow<List<Contact>> =
        combine(kitContacts, mutableSelectedIds) { all, ids ->
            all.filter { it.id in ids }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val canCreate: StateFlow<Boolean> =
        combine(selected, mutableTitle, mutableCreating) { people, title, creating ->
            people.isNotEmpty() && isValidMessagingGroupTitle(title.trim()) && !creating
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // A group can only be built out of contacts this device knows are on Kit Pay, and the
        // picker is often the first screen to need that list after a cold start.
        viewModelScope.launch { runCatching { contactRepo.refresh() } }
    }

    fun setQuery(value: String) {
        mutableQuery.value = value
    }

    fun setTitle(value: String) {
        // Trimming is left to the send: a trailing space while typing a second word is not an error.
        mutableTitle.value = truncateMessagingGroupTitle(value)
    }

    fun toggle(contact: Contact) {
        if (!contact.isKitUser || contact.id.isBlank()) return
        val current = mutableSelectedIds.value
        when {
            contact.id in current -> mutableSelectedIds.value = current - contact.id
            current.size >= MAX_OTHER_GROUP_MEMBERS ->
                mutableError.value =
                    "A group holds $MAX_GROUP_MEMBERS people, including you."
            else -> mutableSelectedIds.value = current + contact.id
        }
    }

    fun clearError() {
        mutableError.value = null
    }

    /** Creates the group and hands its conversation ID back for navigation. */
    fun create(onCreated: (String) -> Unit) {
        if (mutableCreating.value) return
        val members = selected.value
        val name = mutableTitle.value.trim()
        if (members.isEmpty() || name.isEmpty()) return
        mutableCreating.value = true
        viewModelScope.launch {
            try {
                onCreated(chatRepo.createGroupConversation(name, members))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = groupFailureMessage(error, "That group could not be created")
            } finally {
                mutableCreating.value = false
            }
        }
    }
}

/**
 * One group's participants and the membership actions this account is allowed to take on them.
 *
 * Every action re-publishes from the server's answer rather than editing the list locally, so a
 * refused change never appears to have worked; see [ChatRepository.groupMembers].
 */
@HiltViewModel
class GroupProfileViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val contactRepo: ContactRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val chatId: String = savedStateHandle.get<String>("chatId")?.trim().orEmpty()

    val messagingAvailable = chatRepo.readiness

    val chat: StateFlow<ChatPreview?> = chatRepo.chats
        .map { chats -> chats.singleOrNull { it.id == chatId } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            chatId.takeIf(String::isNotBlank)?.let(chatRepo::chat),
        )

    val members: StateFlow<List<ChatMember>> = if (chatId.isBlank()) {
        MutableStateFlow<List<ChatMember>>(emptyList()).asStateFlow()
    } else {
        chatRepo.groupMembers(chatId)
    }

    /** This account's own row, which is what decides every action offered on the others. */
    val viewer: StateFlow<ChatMember?> = members
        .map { list -> list.firstOrNull(ChatMember::isSelf) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, members.value.firstOrNull(ChatMember::isSelf))

    /** Kit contacts who are not in the group yet, for the add-participants screen. */
    val addableContacts: StateFlow<List<Contact>> =
        combine(contactRepo.contacts, members) { contacts, roster ->
            val present = roster.mapTo(mutableSetOf()) { it.userId.lowercase() }
            contacts.filter { it.isKitUser && it.id.isNotBlank() && it.id.lowercase() !in present }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Contact::name))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    /** [addableContacts] narrowed by the add screen's search box. */
    val addableResults: StateFlow<List<Contact>> =
        combine(addableContacts, mutableQuery) { contacts, query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                contacts
            } else {
                contacts.filter {
                    it.name.contains(trimmed, ignoreCase = true) || it.phone.contains(trimmed)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        viewModelScope.launch { runCatching { contactRepo.refresh() } }
    }

    fun setQuery(value: String) {
        mutableQuery.value = value
    }

    fun clearError() {
        mutableError.value = null
    }

    fun addMember(contact: Contact, onAdded: () -> Unit = {}) {
        mutate("${contact.name} could not be added", onAdded) {
            chatRepo.addGroupMember(chatId, contact)
        }
    }

    fun setRole(member: ChatMember, role: ChatMemberRole) {
        mutate("${member.name}'s role could not be changed") {
            chatRepo.setGroupMemberRole(chatId, member.userId, role)
        }
    }

    fun removeMember(member: ChatMember) {
        mutate("${member.name} could not be removed") {
            chatRepo.removeGroupMember(chatId, member.userId)
        }
    }

    /** Leaves the group; [onLeft] runs only once the server has taken this account out of it. */
    fun leave(onLeft: () -> Unit) {
        mutate("You could not be taken out of this group", onLeft) {
            chatRepo.leaveGroupConversation(chatId)
        }
    }

    private fun mutate(
        failureMessage: String,
        onDone: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (chatId.isBlank() || mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                block()
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = groupFailureMessage(error, failureMessage)
            } finally {
                mutableBusy.value = false
            }
        }
    }
}

/**
 * Turns a failed membership call into something worth reading.
 *
 * A dropped connection is described as one — the server's own words for a refusal are kept,
 * because they say which rule was broken, and a generic line would leave somebody re-tapping.
 */
internal fun groupFailureMessage(error: Throwable, fallback: String): String = when {
    error.isKitConnectivityError() -> KIT_NETWORK_UNAVAILABLE_MESSAGE
    else -> error.message?.takeIf(String::isNotBlank) ?: fallback
}
