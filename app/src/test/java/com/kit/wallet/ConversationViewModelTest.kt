package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.feature.chat.ConversationViewModel
import com.kit.wallet.feature.chat.retryableOutgoingMessageIds
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `successful send trims text and clears the draft only through success callback`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("  hello securely  ") { clearRequests++ }

        assertEquals(listOf(CHAT_ID to "hello securely"), repository.sent)
        assertEquals(1, clearRequests)
        assertFalse(viewModel.sending.value)
        assertEquals(null, viewModel.error.value)
    }

    @Test
    fun `failed send retains draft by withholding success callback and exposes error`() = runTest {
        val repository = FakeChatRepository(failure = IllegalStateException("Ciphertext was not accepted"))
        val viewModel = viewModel(repository)
        var clearRequests = 0

        viewModel.send("keep this draft") { clearRequests++ }

        assertEquals(0, clearRequests)
        assertEquals("Ciphertext was not accepted", viewModel.error.value)
        assertFalse(viewModel.sending.value)

        viewModel.clearError()
        assertEquals(null, viewModel.error.value)
    }

    @Test
    fun `second tap cannot start a concurrent duplicate send`() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(blockUntil = release)
        val viewModel = viewModel(repository)

        viewModel.send("one")
        assertTrue(viewModel.sending.value)
        viewModel.send("two")
        assertEquals(listOf(CHAT_ID to "one"), repository.sent)

        release.complete(Unit)
        assertFalse(viewModel.sending.value)
        assertEquals(listOf(CHAT_ID to "one"), repository.sent)
    }

    @Test
    fun `pending authenticated outgoing bubble can be retried explicitly`() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(blockUntil = release)
        val viewModel = viewModel(repository)
        val pending = Message(
            id = "client-message-id",
            text = "retry this ciphertext",
            time = "12:00",
            fromMe = true,
            state = DeliveryState.SENDING,
        )
        var retried = false

        viewModel.retry(pending) { retried = true }

        assertTrue(repository.sent.isEmpty())
        assertEquals(
            listOf(Triple(CHAT_ID, pending.id, pending.text)),
            repository.retried,
        )
        assertEquals(pending.id, viewModel.retryingMessageId.value)
        assertTrue(viewModel.sending.value)

        release.complete(Unit)
        assertTrue(retried)
        assertEquals(null, viewModel.retryingMessageId.value)
        assertFalse(viewModel.sending.value)
    }

    @Test
    fun `received and already sent bubbles cannot enter retry send path`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)

        viewModel.retry(
            Message("received", "hello", "12:00", fromMe = false),
        )
        viewModel.retry(
            Message(
                "sent",
                "hello",
                "12:00",
                fromMe = true,
                state = DeliveryState.SENT,
            ),
        )

        assertTrue(repository.sent.isEmpty())
        assertFalse(viewModel.sending.value)
    }

    @Test
    fun `stale roster bubble stops offering retry after its fresh copy exists`() {
        val stale = Message(
            "stale",
            "same authenticated text",
            "12:00",
            fromMe = true,
            state = DeliveryState.RETRY_REQUIRED,
        )
        val replacement = stale.copy(id = "replacement", state = DeliveryState.SENDING)

        assertEquals(
            setOf(replacement.id),
            retryableOutgoingMessageIds(listOf(stale, replacement)),
        )
        assertTrue(
            retryableOutgoingMessageIds(
                listOf(stale, replacement.copy(state = DeliveryState.SENT)),
            ).isEmpty(),
        )
    }

    @Test
    fun `deep linked conversation appears when its authenticated projection loads later`() = runTest {
        val repository = FakeChatRepository(initiallyLoaded = false)
        val viewModel = viewModel(repository)

        assertEquals(null, viewModel.chat.value)

        repository.publishChat()

        assertEquals(CHAT_ID, viewModel.chat.value?.id)
        assertTrue(viewModel.messages === repository.messages)
    }

    private fun viewModel(repository: ChatRepository) = ConversationViewModel(
        chatRepo = repository,
        savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
    )

    private class FakeChatRepository(
        private val failure: Exception? = null,
        private val blockUntil: CompletableDeferred<Unit>? = null,
        initiallyLoaded: Boolean = true,
    ) : ChatRepository {
        private val preview = ChatPreview(CHAT_ID, "Grace", "", "")
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        private val mutableChats = MutableStateFlow(if (initiallyLoaded) listOf(preview) else emptyList())
        override val chats: StateFlow<List<ChatPreview>> = mutableChats
        val messages = MutableStateFlow<List<Message>>(emptyList())
        val sent = mutableListOf<Pair<String, String>>()
        val retried = mutableListOf<Triple<String, String, String>>()

        fun publishChat() {
            mutableChats.value = listOf(preview)
        }

        override fun chat(chatId: String): ChatPreview? =
            mutableChats.value.singleOrNull { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> = messages

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(chatId: String, text: String) {
            sent += chatId to text
            failure?.let { throw it }
            blockUntil?.await()
        }

        override suspend fun retryMessage(
            chatId: String,
            clientMessageId: String,
            text: String,
        ) {
            retried += Triple(chatId, clientMessageId, text)
            failure?.let { throw it }
            blockUntil?.await()
        }
    }

    private companion object {
        const val CHAT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
