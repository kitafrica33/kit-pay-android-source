package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.feature.chat.ConversationViewModel
import com.kit.wallet.feature.chat.retryableOutgoingMessageIds
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    fun `received sent and permanently failed bubbles cannot enter retry send path`() = runTest {
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
        viewModel.retry(
            Message(
                "expired-media",
                "photo expired",
                "12:00",
                fromMe = true,
                state = DeliveryState.FAILED,
                kind = MessageKind.IMAGE,
                mediaDescriptor = "dead authenticated descriptor",
            ),
        )

        assertTrue(repository.sent.isEmpty())
        assertTrue(repository.retried.isEmpty())
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

    @Test
    fun `foreground sync runs every two seconds and stops immediately when hidden`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)

        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.syncRequests)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, repository.syncRequests)

        viewModel.setConversationVisible(false)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(2, repository.syncRequests)
    }

    @Test
    fun `visible conversation marks only unread inbound projection once`() = runTest {
        val repository = FakeChatRepository().apply {
            messages.value = listOf(
                Message(
                    id = "inbound-message",
                    text = "hello",
                    time = "12:00",
                    fromMe = false,
                    state = DeliveryState.DELIVERED,
                ),
            )
        }
        val viewModel = viewModel(repository)

        runCurrent()
        assertEquals(0, repository.readRequests)
        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)

        viewModel.setConversationVisible(false)
        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)
        viewModel.setConversationVisible(false)
    }

    @Test
    fun `failed visible read receipt retries on cadence and stops after local success`() = runTest {
        val repository = FakeChatRepository(readFailures = 1).apply {
            messages.value = listOf(
                Message(
                    id = "inbound-message",
                    text = "retry receipt",
                    time = "12:00",
                    fromMe = false,
                    state = DeliveryState.DELIVERED,
                ),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)
        assertEquals(DeliveryState.DELIVERED, repository.messages.value.single().state)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, repository.readRequests)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, repository.readRequests)
        assertEquals(DeliveryState.READ, repository.messages.value.single().state)

        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(2, repository.readRequests)
        viewModel.setConversationVisible(false)
    }

    @Test
    fun `hiding conversation cancels an in-flight read publication`() = runTest {
        val releaseRead = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(readBlockUntil = releaseRead).apply {
            messages.value = listOf(
                Message(
                    id = "inbound-message",
                    text = "do not publish off-screen",
                    time = "12:00",
                    fromMe = false,
                    state = DeliveryState.DELIVERED,
                ),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.setConversationVisible(true)
        runCurrent()
        assertEquals(1, repository.readRequests)

        viewModel.setConversationVisible(false)
        runCurrent()

        assertEquals(1, repository.readCancellations)
        assertEquals(DeliveryState.DELIVERED, repository.messages.value.single().state)
        releaseRead.complete(Unit)
        runCurrent()
        assertEquals(DeliveryState.DELIVERED, repository.messages.value.single().state)
    }

    @Test
    fun `decrypted media cache evicts and zeroizes its oldest entry`() = runTest {
        val first = byteArrayOf(1, 2, 3)
        val repository = FakeChatRepository(
            media = (1..5).associate { index ->
                "descriptor-$index" to if (index == 1) first else byteArrayOf(index.toByte())
            },
        )
        val viewModel = viewModel(repository)

        (1..5).forEach { index ->
            viewModel.openMedia(
                Message(
                    id = "message-$index",
                    text = "Photo",
                    time = "12:00",
                    fromMe = false,
                    mediaDescriptor = "descriptor-$index",
                ),
            )
        }

        assertEquals(setOf("message-2", "message-3", "message-4", "message-5"), viewModel.mediaBytes.value.keys)
        assertTrue(first.all { it == 0.toByte() })
    }

    @Test
    fun `multi photo receive is serialized and remains within the cache byte budget`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val first = ByteArray(6 * 1024 * 1024) { 1 }
        val repository = FakeChatRepository(
            mediaBlockUntil = releaseFirst,
            media = (1..5).associate { index ->
                "descriptor-$index" to if (index == 1) first else ByteArray(6 * 1024 * 1024) {
                    index.toByte()
                }
            },
        )
        val viewModel = viewModel(repository)

        (1..5).forEach { index ->
            viewModel.openMedia(
                Message(
                    id = "message-$index",
                    text = "Photo",
                    time = "12:00",
                    fromMe = false,
                    mediaDescriptor = "descriptor-$index",
                ),
            )
        }
        runCurrent()

        assertEquals(1, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(5, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)
        assertEquals(
            setOf("message-2", "message-3", "message-4", "message-5"),
            viewModel.mediaBytes.value.keys,
        )
        assertTrue(first.all { it == 0.toByte() })
    }

    @Test
    fun `media receive remains serialized across separate conversation view models`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(
            mediaBlockUntil = releaseFirst,
            media = mapOf(
                "descriptor-1" to byteArrayOf(1),
                "descriptor-2" to byteArrayOf(2),
            ),
        )
        val firstViewModel = viewModel(repository)
        val secondViewModel = viewModel(repository)

        firstViewModel.openMedia(
            Message("message-1", "Photo", "12:00", false, mediaDescriptor = "descriptor-1"),
        )
        runCurrent()
        secondViewModel.openMedia(
            Message("message-2", "Photo", "12:00", false, mediaDescriptor = "descriptor-2"),
        )
        runCurrent()

        assertEquals(1, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, repository.mediaOpenRequests)
        assertEquals(1, repository.maximumConcurrentMediaOpens)
    }

    @Test
    fun `cancelled media-open handoff zeroizes plaintext returned after cancellation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val plaintext = byteArrayOf(21, 22, 23, 24)
        val repository = FakeChatRepository(
            mediaBlockUntil = release,
            media = mapOf("descriptor" to plaintext),
        )
        val viewModel = viewModel(repository)

        viewModel.openMedia(
            Message(
                id = "message",
                text = "Photo",
                time = "12:00",
                fromMe = false,
                mediaDescriptor = "descriptor",
            ),
        )
        runCurrent()
        assertEquals(1, repository.mediaOpenRequests)

        viewModel.viewModelScope.cancel()
        release.complete(Unit)
        runCurrent()

        assertTrue(viewModel.mediaBytes.value.isEmpty())
        assertTrue(plaintext.all { it == 0.toByte() })
    }

    @Test
    fun `completed media send zeroizes picker plaintext`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        val bytes = byteArrayOf(1, 2, 3, 4)

        viewModel.sendImage(bytes, "image/jpeg")

        assertEquals(1, repository.sentImages.size)
        assertEquals(CHAT_ID, repository.sentImages.single().first)
        assertEquals("image/jpeg", repository.sentImages.single().second)
        assertTrue(repository.sentImages.single().third.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `rejected media send zeroizes picker plaintext`() = runTest {
        val repository = FakeChatRepository(initiallyReady = false)
        val viewModel = viewModel(repository)
        val bytes = byteArrayOf(5, 6, 7, 8)

        viewModel.sendImage(bytes, "image/jpeg")

        assertTrue(repository.sentImages.isEmpty())
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `media send into a cleared scope still zeroizes picker plaintext`() = runTest {
        val repository = FakeChatRepository()
        val viewModel = viewModel(repository)
        viewModel.viewModelScope.cancel()
        val bytes = byteArrayOf(9, 10, 11, 12)

        viewModel.sendImage(bytes, "image/jpeg")
        runCurrent()

        assertTrue(repository.sentImages.isEmpty())
        assertTrue(bytes.all { it == 0.toByte() })
    }

    private fun viewModel(repository: ChatRepository) = ConversationViewModel(
        chatRepo = repository,
        walletRepo = FakeWalletRepository(),
        savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
    )

    /** In-chat payments are exercised separately; these tests only need a compile-safe wallet. */
    private class FakeWalletRepository : WalletRepository {
        override val balanceMinor: StateFlow<Long> = MutableStateFlow(0L)
        override val transactions: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        override fun transaction(id: String): Transaction? = null
        override suspend fun send(
            recipient: Contact,
            amountMinor: Long,
            note: String?,
            paymentPin: String,
        ): Transaction = error("Unused in conversation tests")
        override suspend fun request(from: Contact, amountMinor: Long, note: String?) =
            error("Unused in conversation tests")
        override suspend fun payBill(
            provider: BillProvider,
            account: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Unused in conversation tests")
        override suspend fun buyAirtime(
            productId: String,
            phone: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Unused in conversation tests")
    }

    private class FakeChatRepository(
        private val failure: Exception? = null,
        private val blockUntil: CompletableDeferred<Unit>? = null,
        private var readFailures: Int = 0,
        private val readBlockUntil: CompletableDeferred<Unit>? = null,
        initiallyLoaded: Boolean = true,
        initiallyReady: Boolean = true,
        private val mediaBlockUntil: CompletableDeferred<Unit>? = null,
        private val media: Map<String, ByteArray> = emptyMap(),
    ) : ChatRepository {
        private val preview = ChatPreview(CHAT_ID, "Grace", "", "")
        override val readiness: StateFlow<Boolean> = MutableStateFlow(initiallyReady)
        private val mutableChats = MutableStateFlow(if (initiallyLoaded) listOf(preview) else emptyList())
        override val chats: StateFlow<List<ChatPreview>> = mutableChats
        val messages = MutableStateFlow<List<Message>>(emptyList())
        val sent = mutableListOf<Pair<String, String>>()
        val retried = mutableListOf<Triple<String, String, String>>()
        val sentImages = mutableListOf<Triple<String, String, ByteArray>>()
        var syncRequests = 0
        var readRequests = 0
        var readCancellations = 0
        var mediaOpenRequests = 0
        var maximumConcurrentMediaOpens = 0
        private var concurrentMediaOpens = 0

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

        override suspend fun sendImageMessage(
            chatId: String,
            bytes: ByteArray,
            mediaType: String,
            caption: String?,
        ) {
            sentImages += Triple(chatId, mediaType, bytes.copyOf())
        }

        override suspend fun synchronizeConversation(chatId: String) {
            assertEquals(CHAT_ID, chatId)
            syncRequests++
        }

        override suspend fun markConversationRead(chatId: String) {
            assertEquals(CHAT_ID, chatId)
            readRequests++
            if (readFailures > 0) {
                readFailures--
                throw IllegalStateException("temporary read-receipt failure")
            }
            try {
                readBlockUntil?.await()
            } catch (cancelled: CancellationException) {
                readCancellations++
                throw cancelled
            }
            messages.value = messages.value.map { message ->
                if (!message.fromMe && message.state == DeliveryState.DELIVERED) {
                    message.copy(state = DeliveryState.READ)
                } else {
                    message
                }
            }
        }

        override suspend fun openImageMessage(chatId: String, mediaDescriptor: String): ByteArray {
            mediaOpenRequests++
            concurrentMediaOpens++
            maximumConcurrentMediaOpens = maxOf(
                maximumConcurrentMediaOpens,
                concurrentMediaOpens,
            )
            return try {
                mediaBlockUntil?.await()
                checkNotNull(media[mediaDescriptor])
            } finally {
                concurrentMediaOpens--
            }
        }
    }

    private companion object {
        const val CHAT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
