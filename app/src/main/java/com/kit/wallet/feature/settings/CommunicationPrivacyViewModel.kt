package com.kit.wallet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.BlockedCommunicationUser
import com.kit.wallet.data.repository.CommunicationPreferenceChanges
import com.kit.wallet.data.repository.CommunicationPreferences
import com.kit.wallet.data.repository.CommunicationPrivacyRepository
import com.kit.wallet.data.contacts.ContactDiscoveryConsent
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.canonicalPublicUserId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class CommunicationPreferenceField {
    PHONE_DISCOVERABILITY,
    DIRECT_MESSAGE_REQUESTS,
    INCOMING_CALLS,
    MESSAGING_PRESENCE,
}

internal data class CommunicationPrivacyUiState(
    val preferences: CommunicationPreferences = CommunicationPreferences.DEFAULT_OFF,
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val savingField: CommunicationPreferenceField? = null,
    val blockedUsers: List<BlockedCommunicationUser> = emptyList(),
    val blocksLoaded: Boolean = false,
    val blocksLoading: Boolean = true,
    val blockMutationUserId: String? = null,
    val error: String? = null,
)

@HiltViewModel
class CommunicationPrivacyViewModel @Inject constructor(
    private val repository: CommunicationPrivacyRepository,
    contactRepository: ContactRepository,
    private val discovery: ContactDiscoveryConsent? = null,
) : ViewModel() {
    internal val contacts = contactRepository.contacts
    private val contactRepository = contactRepository

    /**
     * Whether this phone may upload its address book so people can find this account from their
     * contacts. Device-local and instant: unlike the server-backed flags there is nothing to
     * negotiate, and switching it off stops the next upload immediately.
     */
    internal val shareDeviceContacts: StateFlow<Boolean> =
        discovery?.shareDeviceContacts ?: MutableStateFlow(false).asStateFlow()

    internal val contactDiscoveryAvailable: StateFlow<Boolean> =
        discovery?.available ?: MutableStateFlow(false).asStateFlow()

    private var contactDiscoverySyncJob: Job? = null

    fun setShareDeviceContacts(allowed: Boolean) {
        val consent = discovery
        if (consent == null) {
            mutableState.value = mutableState.value.copy(
                error = "Contact discovery is unavailable until this account is known.",
            )
            return
        }
        if (!allowed) {
            val stored = consent.setShareDeviceContacts(false)
            // Store the closed gate before cancellation. Even if an in-flight HTTP cancellation is
            // delayed by the network stack, the repository rejects its next chunk/publication.
            contactDiscoverySyncJob?.cancel()
            contactDiscoverySyncJob = null
            if (!stored) {
                mutableState.value = mutableState.value.copy(
                    error = "Contact discovery could not be disabled for this account.",
                )
            }
            return
        }
        if (!consent.setShareDeviceContacts(true)) {
            mutableState.value = mutableState.value.copy(
                error = "Contact discovery stays off until this account can record your choice.",
            )
            return
        }
        contactDiscoverySyncJob?.cancel()
        contactDiscoverySyncJob = viewModelScope.launch {
            try {
                contactRepository.syncDeviceContacts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    error = error.privacyMessage("Could not synchronize device contacts"),
                )
            }
        }
    }

    private val mutableState = MutableStateFlow(CommunicationPrivacyUiState())
    internal val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true || mutableState.value.savingField != null) return
        refreshJob = viewModelScope.launch {
            val before = mutableState.value
            mutableState.value = before.copy(
                loading = true,
                blocksLoading = true,
                error = null,
            )

            val preferences = resultOf { repository.preferences() }
            val blocks = resultOf { repository.blockedUsers() }
            val errors = listOfNotNull(
                preferences.exceptionOrNull()?.privacyMessage(
                    "Could not load communication privacy settings",
                ),
                blocks.exceptionOrNull()?.privacyMessage("Could not load blocked accounts"),
            )
            val latest = mutableState.value
            mutableState.value = latest.copy(
                preferences = preferences.getOrNull() ?: before.preferences,
                loaded = preferences.isSuccess || before.loaded,
                loading = false,
                blockedUsers = blocks.getOrNull() ?: before.blockedUsers,
                blocksLoaded = blocks.isSuccess || before.blocksLoaded,
                blocksLoading = false,
                error = errors.takeIf(List<String>::isNotEmpty)?.joinToString(" "),
            )
        }
    }

    fun refreshBlockManagement() {
        if (mutableState.value.blocksLoading || mutableState.value.blockMutationUserId != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(blocksLoading = true, error = null)
            val contactsResult = resultOf { contactRepository.refresh() }
            val blocksResult = resultOf { repository.blockedUsers() }
            val latest = mutableState.value
            mutableState.value = latest.copy(
                blockedUsers = blocksResult.getOrNull() ?: latest.blockedUsers,
                blocksLoaded = blocksResult.isSuccess || latest.blocksLoaded,
                blocksLoading = false,
                error = listOfNotNull(
                    contactsResult.exceptionOrNull()?.privacyMessage("Could not refresh contacts"),
                    blocksResult.exceptionOrNull()?.privacyMessage("Could not load blocked accounts"),
                ).takeIf(List<String>::isNotEmpty)?.joinToString(" "),
            )
        }
    }

    fun setPhoneDiscoverable(enabled: Boolean) = updatePreference(
        field = CommunicationPreferenceField.PHONE_DISCOVERABILITY,
        changes = CommunicationPreferenceChanges(phoneDiscoverable = enabled),
    )

    fun setIncomingCallsEnabled(enabled: Boolean) = updatePreference(
        field = CommunicationPreferenceField.INCOMING_CALLS,
        changes = CommunicationPreferenceChanges(incomingCallsEnabled = enabled),
    )

    /**
     * Turning this off is reciprocal by construction: the server leaves an opted-out account off
     * the conversation's presence channel entirely, so it neither emits presence and typing nor
     * receives them. The copy in Settings has to say both halves, because the second one is a cost
     * the user is agreeing to rather than a protection they are gaining.
     */
    fun setMessagingPresenceVisible(enabled: Boolean) = updatePreference(
        field = CommunicationPreferenceField.MESSAGING_PRESENCE,
        changes = CommunicationPreferenceChanges(messagingPresenceVisible = enabled),
    )

    fun setDirectMessageRequestsEnabled(enabled: Boolean, messagingUsable: Boolean) {
        if (!messagingUsable) {
            mutableState.value = mutableState.value.copy(
                error = "Direct message requests stay off until secure messaging is available.",
            )
            return
        }
        updatePreference(
            field = CommunicationPreferenceField.DIRECT_MESSAGE_REQUESTS,
            changes = CommunicationPreferenceChanges(directMessageRequestsEnabled = enabled),
        )
    }

    fun block(userId: String) {
        val canonicalId = validateUserId(userId) ?: return
        if (mutableState.value.blockMutationUserId != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                blockMutationUserId = canonicalId,
                error = null,
            )
            try {
                val block = repository.block(canonicalId)
                val blocks = (mutableState.value.blockedUsers + block)
                    .distinctBy(BlockedCommunicationUser::userId)
                    .sortedBy(BlockedCommunicationUser::userId)
                mutableState.value = mutableState.value.copy(
                    blockedUsers = blocks,
                    blocksLoaded = true,
                    blockMutationUserId = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    blockMutationUserId = null,
                    error = error.privacyMessage("Could not block that account"),
                )
            }
        }
    }

    fun unblock(userId: String) {
        val canonicalId = validateUserId(userId) ?: return
        if (mutableState.value.blockMutationUserId != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                blockMutationUserId = canonicalId,
                error = null,
            )
            try {
                repository.unblock(canonicalId)
                mutableState.value = mutableState.value.copy(
                    blockedUsers = mutableState.value.blockedUsers.filterNot {
                        it.userId == canonicalId
                    },
                    blocksLoaded = true,
                    blockMutationUserId = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    blockMutationUserId = null,
                    error = error.privacyMessage("Could not unblock that account"),
                )
            }
        }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private fun updatePreference(
        field: CommunicationPreferenceField,
        changes: CommunicationPreferenceChanges,
    ) {
        val current = mutableState.value
        if (!current.loaded || current.loading || current.savingField != null) {
            if (!current.loaded && !current.loading) refresh()
            return
        }

        viewModelScope.launch {
            mutableState.value = current.copy(savingField = field, error = null)
            try {
                val updated = repository.updatePreferences(current.preferences.version, changes)
                mutableState.value = mutableState.value.copy(
                    preferences = updated,
                    loaded = true,
                    savingField = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error.isPreferenceVersionConflict()) {
                    refreshAfterConflict(error)
                } else {
                    mutableState.value = mutableState.value.copy(
                        savingField = null,
                        error = error.privacyMessage("Could not update communication settings"),
                    )
                }
            }
        }
    }

    private suspend fun refreshAfterConflict(conflict: Exception) {
        val refreshed = resultOf { repository.preferences() }
        val latest = mutableState.value
        mutableState.value = latest.copy(
            preferences = refreshed.getOrNull() ?: CommunicationPreferences.DEFAULT_OFF,
            loaded = refreshed.isSuccess,
            loading = false,
            savingField = null,
            error = if (refreshed.isSuccess) {
                "Settings changed on another device. The latest choices are shown; review and try again."
            } else {
                refreshed.exceptionOrNull()?.privacyMessage(
                    "Settings changed on another device, but the latest choices could not be loaded",
                ) ?: conflict.privacyMessage("Communication settings changed on another device")
            },
        )
    }

    private fun validateUserId(userId: String): String? = try {
        canonicalPublicUserId(userId)
    } catch (_: IllegalArgumentException) {
        mutableState.value = mutableState.value.copy(error = "Choose a valid Kit Pay contact.")
        null
    }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

private fun Throwable.isPreferenceVersionConflict(): Boolean =
    this is KitWalletApiException &&
        (code == "COMMUNICATION_PREFERENCES_VERSION_CONFLICT" || statusCode == 409)

private fun Throwable.privacyMessage(fallback: String): String =
    message?.trim()?.takeIf(String::isNotBlank) ?: fallback
