package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.remote.MAX_GROUP_MEMBERS
import com.kit.wallet.data.remote.MAX_GROUP_TITLE_LENGTH
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.feature.chat.GroupProfileViewModel
import com.kit.wallet.feature.chat.NewGroupViewModel
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class GroupViewModelsTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a group is created from the chosen people under a Unicode normalized name`() = runTest {
        val chats = FakeChatRepository()
        val viewModel = NewGroupViewModel(chats, FakeContactRepository(directory(3)))

        viewModel.toggle(contact(0))
        viewModel.toggle(contact(2))
        viewModel.setTitle("\u0085Site team\u0085")
        var opened: String? = null
        viewModel.create { opened = it }

        assertEquals(CONVERSATION_ID, opened)
        assertEquals("Site team", chats.createdTitle)
        assertEquals(listOf(contact(0).id, contact(2).id), chats.createdMembers.map(Contact::id))
        assertNull(viewModel.error.value)
    }

    @Test
    fun `tapping somebody twice takes them back out`() = runTest {
        val viewModel = NewGroupViewModel(FakeChatRepository(), FakeContactRepository(directory(2)))

        viewModel.toggle(contact(1))
        viewModel.toggle(contact(1))

        assertEquals(emptyList<Contact>(), viewModel.selected.value)
        assertFalse(viewModel.canCreate.value)
    }

    @Test
    fun `the group ceiling counts this account and is explained rather than silently ignored`() =
        runTest {
            val everybody = directory(MAX_GROUP_MEMBERS + 4)
            val viewModel = NewGroupViewModel(FakeChatRepository(), FakeContactRepository(everybody))

            everybody.forEach(viewModel::toggle)

            // One seat is this account's own, so the picker stops one short of the ceiling.
            assertEquals(MAX_GROUP_MEMBERS - 1, viewModel.selected.value.size)
            assertTrue(viewModel.error.value.orEmpty().contains("including you"))
        }

    @Test
    fun `a name longer than the server accepts is cut rather than refused on send`() = runTest {
        val viewModel = NewGroupViewModel(FakeChatRepository(), FakeContactRepository(directory(1)))

        viewModel.setTitle("n".repeat(MAX_GROUP_TITLE_LENGTH * 2))

        assertEquals(MAX_GROUP_TITLE_LENGTH, viewModel.title.value.length)
    }

    @Test
    fun `group name truncation counts Unicode scalars and UTF-8 bytes`() = runTest {
        val viewModel = NewGroupViewModel(FakeChatRepository(), FakeContactRepository(directory(1)))

        viewModel.setTitle("😀".repeat(64))

        assertEquals(30, viewModel.title.value.codePointCount(0, viewModel.title.value.length))
        assertEquals(120, viewModel.title.value.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `typing a space then another word preserves the intended group name`() = runTest {
        val viewModel = NewGroupViewModel(FakeChatRepository(), FakeContactRepository(directory(1)))

        viewModel.setTitle("Weekend ")
        assertEquals("Weekend ", viewModel.title.value)

        viewModel.setTitle("Weekend trip")
        assertEquals("Weekend trip", viewModel.title.value)
    }

    @Test
    fun `padded exact-bound title stays editable and creates the full canonical core`() = runTest {
        val chats = FakeChatRepository()
        val viewModel = NewGroupViewModel(chats, FakeContactRepository(directory(1)))
        val rawTitle = "\u0085${"a".repeat(MAX_GROUP_TITLE_LENGTH)}\u3000"

        viewModel.toggle(contact(0))
        viewModel.setTitle(rawTitle)
        assertEquals(rawTitle, viewModel.title.value)

        viewModel.create { }
        assertEquals("a".repeat(MAX_GROUP_TITLE_LENGTH), chats.createdTitle)
    }

    @Test
    fun `padded over-bound core is truncated without spending its budget on padding`() = runTest {
        val viewModel = NewGroupViewModel(FakeChatRepository(), FakeContactRepository(directory(1)))

        viewModel.setTitle("\u0085${"é".repeat(61)}\u3000")

        assertEquals("é".repeat(60), viewModel.title.value)
        assertEquals(60, viewModel.title.value.codePointCount(0, viewModel.title.value.length))
        assertEquals(120, viewModel.title.value.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `Unicode whitespace-only title remains non-creatable`() = runTest {
        val chats = FakeChatRepository()
        val viewModel = NewGroupViewModel(chats, FakeContactRepository(directory(1)))

        viewModel.toggle(contact(0))
        viewModel.setTitle("\u0085\u3000")

        assertFalse(viewModel.canCreate.value)
        viewModel.create { }
        assertNull(chats.createdTitle)
    }

    @Test
    fun `malformed UTF-16 is removed before it can become creatable`() = runTest {
        val viewModel = NewGroupViewModel(FakeChatRepository(), FakeContactRepository(directory(1)))

        viewModel.setTitle("Valid\uD800tail")

        assertEquals("Valid", viewModel.title.value)
    }

    @Test
    fun `an unnamed or empty group cannot be created`() = runTest {
        val chats = FakeChatRepository()
        val viewModel = NewGroupViewModel(chats, FakeContactRepository(directory(2)))

        viewModel.setTitle("Site team")
        viewModel.create { }
        assertNull(chats.createdTitle)

        viewModel.toggle(contact(0))
        viewModel.setTitle("   ")
        viewModel.create { }
        assertNull(chats.createdTitle)
    }

    @Test
    fun `a refused creation keeps the picker open and says why`() = runTest {
        val chats = FakeChatRepository(failWith = IllegalStateException("That group is full"))
        val viewModel = NewGroupViewModel(chats, FakeContactRepository(directory(1)))

        viewModel.toggle(contact(0))
        viewModel.setTitle("Site team")
        var opened: String? = null
        viewModel.create { opened = it }

        assertNull(opened)
        assertEquals("That group is full", viewModel.error.value)
        assertFalse(viewModel.creating.value)
    }

    @Test
    fun `the participant list offers the viewer's own role and nothing on their own row`() =
        runTest {
            val roster = listOf(
                ChatMember("me", "You", ChatMemberRole.OWNER, isSelf = true),
                ChatMember("brian", "Brian", ChatMemberRole.MEMBER),
            )
            val chats = FakeChatRepository(members = roster)
            val viewModel = GroupProfileViewModel(
                chats,
                FakeContactRepository(directory(2)),
                SavedStateHandle(mapOf("chatId" to GROUP_ID)),
            )

            assertEquals(ChatMemberRole.OWNER, viewModel.viewer.value?.role)
            assertEquals(roster, viewModel.members.value)
        }

    @Test
    fun `adding somebody already in the group is never offered`() = runTest {
        val roster = listOf(
            ChatMember("me", "You", ChatMemberRole.OWNER, isSelf = true),
            ChatMember(contact(0).id, contact(0).name, ChatMemberRole.MEMBER),
        )
        val viewModel = GroupProfileViewModel(
            FakeChatRepository(members = roster),
            FakeContactRepository(directory(3)),
            SavedStateHandle(mapOf("chatId" to GROUP_ID)),
        )

        val addable = viewModel.addableContacts.value.map(Contact::id)
        assertFalse(contact(0).id in addable)
        assertTrue(contact(1).id in addable)
    }

    @Test
    fun `a membership change that is refused is reported instead of appearing to work`() = runTest {
        val chats = FakeChatRepository(
            members = listOf(ChatMember("me", "You", ChatMemberRole.ADMIN, isSelf = true)),
            failWith = IllegalStateException("Only an owner can do that"),
        )
        val viewModel = GroupProfileViewModel(
            chats,
            FakeContactRepository(directory(1)),
            SavedStateHandle(mapOf("chatId" to GROUP_ID)),
        )

        viewModel.removeMember(ChatMember("brian", "Brian"))

        assertEquals("Only an owner can do that", viewModel.error.value)
        assertFalse(viewModel.busy.value)
    }

    @Test
    fun `leaving only navigates once the server has taken this account out`() = runTest {
        val chats = FakeChatRepository(
            members = listOf(ChatMember("me", "You", ChatMemberRole.MEMBER, isSelf = true)),
        )
        val viewModel = GroupProfileViewModel(
            chats,
            FakeContactRepository(directory(1)),
            SavedStateHandle(mapOf("chatId" to GROUP_ID)),
        )
        var left = false

        viewModel.leave { left = true }

        assertTrue(left)
        assertEquals(listOf(GROUP_ID), chats.leftGroups)
    }

    @Test
    fun `a missing conversation route touches no membership endpoint`() = runTest {
        val chats = FakeChatRepository()
        val viewModel = GroupProfileViewModel(
            chats,
            FakeContactRepository(directory(1)),
            SavedStateHandle(),
        )
        var left = false

        viewModel.leave { left = true }
        viewModel.removeMember(ChatMember("brian", "Brian"))
        viewModel.addMember(contact(0))

        assertFalse(left)
        assertEquals(emptyList<String>(), chats.leftGroups)
        assertEquals(emptyList<Contact>(), chats.addedMembers)
        assertEquals(emptyList<ChatMember>(), viewModel.members.value)
    }

    private class FakeContactRepository(initial: List<Contact>) : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(initial).asStateFlow()
        override suspend fun refresh() = Unit
        override suspend fun syncDeviceContacts() = Unit
    }

    private class FakeChatRepository(
        members: List<ChatMember> = emptyList(),
        private val failWith: Exception? = null,
    ) : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(
            listOf(ChatPreview(GROUP_ID, "Site team", "hi", "10:00", isGroup = true)),
        )
        private val roster = MutableStateFlow(members)
        var createdTitle: String? = null
        var createdMembers: List<Contact> = emptyList()
        val addedMembers = mutableListOf<Contact>()
        val leftGroups = mutableListOf<String>()

        override fun chat(chatId: String): ChatPreview? = chats.value.firstOrNull { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = CONVERSATION_ID

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) = Unit

        override suspend fun createGroupConversation(
            title: String,
            contacts: List<Contact>,
        ): String {
            failWith?.let { throw it }
            createdTitle = title
            createdMembers = contacts
            return CONVERSATION_ID
        }

        override fun groupMembers(chatId: String): StateFlow<List<ChatMember>> = roster.asStateFlow()

        override suspend fun addGroupMember(chatId: String, contact: Contact) {
            failWith?.let { throw it }
            addedMembers += contact
        }

        override suspend fun removeGroupMember(chatId: String, userId: String) {
            failWith?.let { throw it }
            roster.value = roster.value.filterNot { it.userId == userId }
        }

        override suspend fun leaveGroupConversation(chatId: String) {
            failWith?.let { throw it }
            leftGroups += chatId
        }
    }

    private companion object {
        const val CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
        const val GROUP_ID = "44444444-4444-4444-8444-444444444444"

        fun contact(index: Int) = Contact(
            id = "contact-$index",
            name = "Person $index",
            phone = "+25670000%04d".format(index),
            isKitUser = true,
        )

        fun directory(size: Int) = (0 until size).map(::contact)
    }
}
