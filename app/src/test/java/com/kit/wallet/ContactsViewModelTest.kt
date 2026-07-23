package com.kit.wallet

import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.feature.contacts.ContactsViewModel
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `on-Kit selection navigates only after validated conversation creation returns`() = runTest {
        val chats = FakeChatRepository()
        val viewModel = ContactsViewModel(FakeContactRepository(), chats)
        var opened: String? = null

        viewModel.openDirectConversation(ON_KIT) { opened = it }

        assertEquals(CONVERSATION_ID, opened)
        assertEquals(listOf(ON_KIT), chats.openRequests)
        assertNull(viewModel.error.value)
        assertNull(viewModel.openingContactId.value)
    }

    @Test
    fun `non-Kit selection remains invite-only and never reaches messaging repository`() = runTest {
        val chats = FakeChatRepository()
        val viewModel = ContactsViewModel(FakeContactRepository(), chats)
        var navigated = false

        viewModel.openDirectConversation(NOT_ON_KIT) { navigated = true }

        assertTrue(!navigated)
        assertTrue(chats.openRequests.isEmpty())
        assertTrue(viewModel.error.value.orEmpty().contains("Invite"))
    }

    @Test
    fun `stale device contact is synchronized and resolved before opening chat`() = runTest {
        val chats = FakeChatRepository()
        val resolved = NOT_ON_KIT.copy(
            id = ON_KIT.id,
            name = "Flora saved locally",
            isKitUser = true,
            savedInDevice = true,
        )
        val contacts = FakeContactRepository(resolved)
        val viewModel = ContactsViewModel(contacts, chats)
        var opened: String? = null

        viewModel.openDirectConversation(NOT_ON_KIT) { opened = it }

        assertEquals(CONVERSATION_ID, opened)
        assertEquals(listOf(NOT_ON_KIT), contacts.resolveRequests)
        assertEquals(listOf(resolved), chats.openRequests)
    }

    private class FakeContactRepository(
        private val resolved: Contact? = null,
    ) : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(emptyList())
        val resolveRequests = mutableListOf<Contact>()
        override suspend fun refresh() = Unit
        override suspend fun syncDeviceContacts() = Unit
        override suspend fun resolveForMessaging(contact: Contact): Contact? {
            resolveRequests += contact
            return if (contact.isKitUser) contact else resolved
        }
    }

    private class FakeChatRepository : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(emptyList())
        val openRequests = mutableListOf<Contact>()

        override fun chat(chatId: String): ChatPreview? = null

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String {
            openRequests += contact
            return CONVERSATION_ID
        }

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) = Unit
    }

    private companion object {
        const val CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
        val ON_KIT = Contact(
            id = "22222222-2222-4222-8222-222222222222",
            name = "Grace",
            phone = "+256700000001",
            isKitUser = true,
        )
        val NOT_ON_KIT = Contact(
            id = "33333333-3333-4333-8333-333333333333",
            name = "Invitee",
            phone = "+256700000002",
            isKitUser = false,
        )
    }
}
