package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatPaymentRequest
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.feature.chat.ConversationViewModel
import com.kit.wallet.feature.chat.MessageSoundPlayer
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.Transaction
import java.io.IOException
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPaymentRequestRetryTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `share failure reuses the confirmed payment request on retry`() = runTest {
        val chats = FakeChatRepository(listOf(SendBehavior.FAIL, SendBehavior.SUCCEED))
        val wallet = RecordingWalletRepository()
        val viewModel = viewModel(chats, wallet)

        viewModel.sendPaymentRequest(15_000, "  for lunch  ")
        viewModel.sendPaymentRequest(15_000, "for lunch")

        assertEquals(1, wallet.createdRequests.size)
        assertEquals(2, chats.sentDescriptors.size)
        val requestIds = chats.sentDescriptors.map {
            checkNotNull(KitPaymentMessage.parse(it)).referenceId
        }
        assertEquals(listOf(requestId(1), requestId(1)), requestIds)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `changed details create a fresh payment request`() = runTest {
        val chats = FakeChatRepository(listOf(SendBehavior.FAIL, SendBehavior.SUCCEED))
        val wallet = RecordingWalletRepository()
        val viewModel = viewModel(chats, wallet)

        viewModel.sendPaymentRequest(15_000, "for lunch")
        viewModel.sendPaymentRequest(20_000, "for lunch")

        assertEquals(2, wallet.createdRequests.size)
        val lastRequestId = chats.sentDescriptors.last().let {
            checkNotNull(KitPaymentMessage.parse(it)).referenceId
        }
        assertEquals(requestId(2), lastRequestId)
    }

    @Test
    fun `durably committed share closes silently and never reuses its request`() = runTest {
        val chats = FakeChatRepository(listOf(SendBehavior.COMMIT_THEN_FAIL, SendBehavior.SUCCEED))
        val wallet = RecordingWalletRepository()
        val viewModel = viewModel(chats, wallet)
        var closes = 0

        viewModel.sendPaymentRequest(15_000, null) { closes++ }
        assertEquals(1, closes)
        assertNull(viewModel.error.value)

        // The first card is committed and owns replay; an identical follow-up is a new request.
        viewModel.sendPaymentRequest(15_000, null) { closes++ }
        assertEquals(2, closes)
        assertEquals(2, wallet.createdRequests.size)
    }

    private fun viewModel(
        chats: ChatRepository,
        wallet: WalletRepository,
    ) = ConversationViewModel(
        chatRepo = chats,
        walletRepo = wallet,
        walletSync = NoOpTestWalletSync,
        callRepo = NoCallsRepository,
        messageSounds = SilentMessageSoundPlayer,
        realtime = InertConversationSignals,
        typingSignaller = RecordingTypingSignals(),
        savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
    )

    private enum class SendBehavior { SUCCEED, FAIL, COMMIT_THEN_FAIL }

    private class FakeChatRepository(
        private val behaviors: List<SendBehavior>,
    ) : ChatRepository {
        private val preview = ChatPreview(CHAT_ID, "Grace", "", "", peerUserId = PEER_USER_ID)
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(listOf(preview))
        val sentDescriptors = mutableListOf<String>()

        override fun chat(chatId: String): ChatPreview? = preview.takeIf { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            val behavior = behaviors.getOrNull(sentDescriptors.size) ?: SendBehavior.SUCCEED
            sentDescriptors += text
            when (behavior) {
                SendBehavior.SUCCEED -> onDurablyCommitted("client-${sentDescriptors.size}")
                SendBehavior.FAIL -> throw IOException("offline before commit")
                SendBehavior.COMMIT_THEN_FAIL -> {
                    onDurablyCommitted("client-${sentDescriptors.size}")
                    throw IOException("offline after commit")
                }
            }
        }
    }

    private class RecordingWalletRepository : WalletRepository {
        override val balanceMinor: StateFlow<Long> = MutableStateFlow(0L)
        override val transactions: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        val createdRequests = mutableListOf<ChatPaymentRequest>()

        override fun transaction(id: String): Transaction? = null

        override suspend fun createChatPaymentRequest(
            peerUserId: String,
            amountMinor: Long,
            note: String?,
            idempotencyKey: String?,
        ): ChatPaymentRequest {
            check(peerUserId == PEER_USER_ID)
            val created = ChatPaymentRequest(
                id = requestId(createdRequests.size + 1),
                amountMinor = amountMinor,
                currencyCode = "UGX",
                currencyScale = 2,
                note = note,
            )
            createdRequests += created
            return created
        }

        override suspend fun send(
            recipient: Contact,
            amountMinor: Long,
            note: String?,
            paymentPin: String,
        ): Transaction = error("Unused")

        override suspend fun request(from: Contact, amountMinor: Long, note: String?) =
            error("Unused")

        override suspend fun payBill(
            provider: BillProvider,
            account: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Unused")

        override suspend fun buyAirtime(
            productId: String,
            phone: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Unused")
    }

    private object NoCallsRepository : CallRepository {
        override val calls: StateFlow<List<CallEntry>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
    }

    private object SilentMessageSoundPlayer : MessageSoundPlayer {
        override fun playSent() = Unit
        override fun playReceived() = Unit
        override fun playPaymentReceived() = Unit
    }

    private companion object {
        const val CHAT_ID = "conversation-1"
        const val PEER_USER_ID = "11111111-1111-4111-8111-111111111111"

        /** Canonical lowercase UUIDs, as required by the strict descriptor parser. */
        fun requestId(sequence: Int) =
            "00000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}"
    }
}
