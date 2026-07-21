package com.kit.wallet

import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.BlockedCommunicationUser
import com.kit.wallet.data.repository.CommunicationPreferenceChanges
import com.kit.wallet.data.repository.CommunicationPreferences
import com.kit.wallet.data.repository.CommunicationPrivacyRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.feature.settings.CommunicationPrivacyViewModel
import com.kit.wallet.ui.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationPrivacyViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial failure presents default off choices and disables mutation`() = runTest {
        val repository = FakePrivacyRepository().apply {
            preferencesError = IllegalStateException("Malformed privacy response")
        }
        val viewModel = CommunicationPrivacyViewModel(repository, FakeContactRepository())

        assertFalse(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.preferences.phoneDiscoverable)
        assertFalse(viewModel.state.value.preferences.directMessageRequestsEnabled)
        assertFalse(viewModel.state.value.preferences.incomingCallsEnabled)

        viewModel.setPhoneDiscoverable(true)

        assertTrue(repository.updates.isEmpty())
    }

    @Test
    fun `preference mutations use current version and future messaging stays unavailable`() = runTest {
        val repository = FakePrivacyRepository()
        val viewModel = CommunicationPrivacyViewModel(repository, FakeContactRepository())

        viewModel.setPhoneDiscoverable(true)

        assertEquals(1L, repository.updates.single().first)
        assertEquals(true, repository.updates.single().second.phoneDiscoverable)
        assertEquals(2L, viewModel.state.value.preferences.version)
        assertTrue(viewModel.state.value.preferences.phoneDiscoverable)

        viewModel.setDirectMessageRequestsEnabled(enabled = true, messagingUsable = false)

        assertEquals(1, repository.updates.size)
        assertTrue(viewModel.state.value.error.orEmpty().contains("stay off"))
    }

    @Test
    fun `version conflict refreshes authoritative choices before another update`() = runTest {
        val repository = FakePrivacyRepository().apply { conflictOnNextUpdate = true }
        val viewModel = CommunicationPrivacyViewModel(repository, FakeContactRepository())

        viewModel.setIncomingCallsEnabled(true)

        assertEquals(2, repository.preferenceReads)
        assertEquals(7L, viewModel.state.value.preferences.version)
        assertTrue(viewModel.state.value.preferences.phoneDiscoverable)
        assertFalse(viewModel.state.value.preferences.incomingCallsEnabled)
        assertNull(viewModel.state.value.savingField)
        assertTrue(viewModel.state.value.error.orEmpty().contains("another device"))
    }

    @Test
    fun `ordinary update error is not mistaken for a version conflict`() = runTest {
        val repository = FakePrivacyRepository().apply {
            updateError = KitWalletApiException(
                code = "COMMUNICATION_TARGET_UNAVAILABLE",
                message = "The setting could not be changed.",
                statusCode = 422,
            )
        }
        val viewModel = CommunicationPrivacyViewModel(repository, FakeContactRepository())

        viewModel.setIncomingCallsEnabled(true)

        assertEquals(1, repository.preferenceReads)
        assertEquals(1L, viewModel.state.value.preferences.version)
        assertTrue(viewModel.state.value.error.orEmpty().contains("could not be changed"))
        assertNull(viewModel.state.value.savingField)
    }

    @Test
    fun `failed conflict refresh disables controls and returns presentation to off`() = runTest {
        val repository = FakePrivacyRepository().apply {
            conflictOnNextUpdate = true
            failConflictRefresh = true
        }
        val viewModel = CommunicationPrivacyViewModel(repository, FakeContactRepository())

        viewModel.setPhoneDiscoverable(true)

        assertFalse(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.preferences.phoneDiscoverable)
        assertFalse(viewModel.state.value.preferences.directMessageRequestsEnabled)
        assertFalse(viewModel.state.value.preferences.incomingCallsEnabled)
        assertTrue(
            viewModel.state.value.error.orEmpty().contains("latest choices", ignoreCase = true),
        )
    }

    @Test
    fun `block management accepts Kit contacts and updates only after confirmation`() = runTest {
        val contacts = FakeContactRepository(
            listOf(Contact(USER_ID, "Grace", "+256700000001", isKitUser = true)),
        )
        val repository = FakePrivacyRepository()
        val viewModel = CommunicationPrivacyViewModel(repository, contacts)

        viewModel.block(USER_ID)

        assertEquals(listOf(USER_ID), viewModel.state.value.blockedUsers.map { it.userId })
        assertEquals(listOf(USER_ID), repository.blockCalls)

        viewModel.unblock(USER_ID)

        assertTrue(viewModel.state.value.blockedUsers.isEmpty())
        assertEquals(listOf(USER_ID), repository.unblockCalls)

        viewModel.block("not-a-user")
        assertEquals(1, repository.blockCalls.size)
        assertTrue(viewModel.state.value.error.orEmpty().contains("valid Kit Pay contact"))
    }

    @Test
    fun `server block remains removable after contact projection disappears`() = runTest {
        val repository = FakePrivacyRepository().apply { seedBlock(USER_ID) }
        val viewModel = CommunicationPrivacyViewModel(
            repository,
            FakeContactRepository(initial = emptyList()),
        )

        assertEquals(listOf(USER_ID), viewModel.state.value.blockedUsers.map { it.userId })
        assertTrue(viewModel.contacts.value.isEmpty())

        viewModel.unblock(USER_ID)

        assertTrue(viewModel.state.value.blockedUsers.isEmpty())
        assertEquals(listOf(USER_ID), repository.unblockCalls)
    }

    private class FakePrivacyRepository : CommunicationPrivacyRepository {
        var current = CommunicationPreferences(
            version = 1,
            phoneDiscoverable = false,
            directMessageRequestsEnabled = false,
            incomingCallsEnabled = false,
            updatedAt = null,
        )
        var preferencesError: Exception? = null
        var conflictOnNextUpdate = false
        var failConflictRefresh = false
        var updateError: Exception? = null
        var preferenceReads = 0
        val updates = mutableListOf<Pair<Long, CommunicationPreferenceChanges>>()
        val blockCalls = mutableListOf<String>()
        val unblockCalls = mutableListOf<String>()
        private val blocks = mutableListOf<BlockedCommunicationUser>()

        override suspend fun preferences(): CommunicationPreferences {
            preferenceReads++
            preferencesError?.let { throw it }
            return current
        }

        override suspend fun updatePreferences(
            expectedVersion: Long,
            changes: CommunicationPreferenceChanges,
        ): CommunicationPreferences {
            updates += expectedVersion to changes
            updateError?.let { throw it }
            if (conflictOnNextUpdate) {
                conflictOnNextUpdate = false
                current = CommunicationPreferences(
                    version = 7,
                    phoneDiscoverable = true,
                    directMessageRequestsEnabled = false,
                    incomingCallsEnabled = false,
                    updatedAt = null,
                )
                if (failConflictRefresh) {
                    preferencesError = IllegalStateException("Latest choices unavailable")
                }
                throw KitWalletApiException(
                    code = "COMMUNICATION_PREFERENCES_VERSION_CONFLICT",
                    message = "Preferences changed on another session.",
                    statusCode = 409,
                )
            }
            current = current.copy(
                version = current.version + 1,
                phoneDiscoverable = changes.phoneDiscoverable ?: current.phoneDiscoverable,
                directMessageRequestsEnabled = changes.directMessageRequestsEnabled
                    ?: current.directMessageRequestsEnabled,
                incomingCallsEnabled = changes.incomingCallsEnabled
                    ?: current.incomingCallsEnabled,
            )
            return current
        }

        override suspend fun blockedUsers(): List<BlockedCommunicationUser> = blocks.toList()

        override suspend fun block(userId: String): BlockedCommunicationUser {
            blockCalls += userId
            return BlockedCommunicationUser(userId, null).also { blocks += it }
        }

        override suspend fun unblock(userId: String) {
            unblockCalls += userId
            blocks.removeAll { it.userId == userId }
        }

        fun seedBlock(userId: String) {
            blocks += BlockedCommunicationUser(userId, null)
        }
    }

    private class FakeContactRepository(
        initial: List<Contact> = emptyList(),
    ) : ContactRepository {
        private val mutableContacts = MutableStateFlow(initial)
        override val contacts: StateFlow<List<Contact>> = mutableContacts
        override suspend fun refresh() = Unit
        override suspend fun syncDeviceContacts() = Unit
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
    }
}
