package com.kit.wallet

import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.feature.chat.SharedTextSendStart
import com.kit.wallet.feature.chat.SharedTextShareViewModel
import com.kit.wallet.feature.chat.directTextShareRecipients
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedTextShareViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `recipient choices contain direct conversations and never groups`() {
        val direct = chat(id = "direct", name = "Grace")
        val group = chat(id = "group", name = "Grace's group", isGroup = true)

        assertEquals(listOf(direct), directTextShareRecipients(listOf(group, direct), "grace"))
        assertTrue(directTextShareRecipients(listOf(group)).isEmpty())
    }

    @Test
    fun `send rejects a group conversation without invoking repository`() = runTest {
        val repository = FakeChatRepository(listOf(chat("group", "Family", isGroup = true)))
        val viewModel = SharedTextShareViewModel(repository)
        viewModel.begin("request")

        val result = viewModel.send("request", "group", "Private text") {}

        assertEquals(SharedTextSendStart.REJECTED, result)
        assertTrue(repository.sentMessages.isEmpty())
        assertTrue(viewModel.sendState.value.error.orEmpty().contains("direct"))
    }

    @Test
    fun `send accepts a current direct conversation`() = runTest {
        val repository = FakeChatRepository(listOf(chat("direct", "Grace")))
        val viewModel = SharedTextShareViewModel(repository)
        viewModel.begin("request")
        var finished = false

        val result = viewModel.send("request", "direct", "Hello", onFinished = { finished = true })

        assertEquals(SharedTextSendStart.STARTED, result)
        assertEquals(listOf("direct" to "Hello"), repository.sentMessages)
        assertTrue(viewModel.sendState.value.sent)
        assertTrue(finished)
    }

    private fun chat(id: String, name: String, isGroup: Boolean = false) = ChatPreview(
        id = id,
        name = name,
        lastMessage = "",
        time = "",
        isGroup = isGroup,
    )

    private class FakeChatRepository(initialChats: List<ChatPreview>) : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(initialChats)
        val sentMessages = mutableListOf<Pair<String, String>>()

        override fun chat(chatId: String): ChatPreview? = chats.value.firstOrNull { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(chatId: String, text: String) {
            sentMessages += chatId to text
        }
    }
}
