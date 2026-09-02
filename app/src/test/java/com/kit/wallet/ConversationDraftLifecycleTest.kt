package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.repository.CallRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDraftLifecycleTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `restores the stored draft once and persists composer edits`() = runTest {
        val chats = DraftRecordingChatRepository(storedDraft = "half a thought")
        val viewModel = viewModel(chats)

        assertEquals("half a thought", viewModel.restoredDraft.value)
        viewModel.consumeRestoredDraft()
        assertNull(viewModel.restoredDraft.value)

        viewModel.persistDraft("half a thought, finished")
        assertEquals(listOf("half a thought, finished"), chats.savedDrafts)
    }

    @Test
    fun `restore waits for encrypted history and buffered writes keep only the latest edit`() =
        runTest {
            val chats = DraftRecordingChatRepository(
                storedDraft = "older device draft",
                initiallyHistoryReady = false,
            )
            val viewModel = viewModel(chats)

            assertEquals(0, chats.draftReads)
            viewModel.onComposerChanged("new")
            viewModel.persistDraft("new")
            viewModel.onComposerChanged("newest")
            viewModel.persistDraft("newest")
            runCurrent()

            assertEquals(emptyList<String>(), chats.savedDrafts)
            assertNull(viewModel.restoredDraft.value)

            chats.setHistoryReady()
            runCurrent()

            assertEquals(1, chats.draftReads)
            assertNull(viewModel.restoredDraft.value)
            assertEquals(listOf("newest"), chats.savedDrafts)
        }

    @Test
    fun `typing then clearing before history opens cannot resurrect an older draft`() = runTest {
        val chats = DraftRecordingChatRepository(
            storedDraft = "do not resurrect",
            initiallyHistoryReady = false,
        )
        val viewModel = viewModel(chats)

        viewModel.onComposerChanged("temporary")
        viewModel.onComposerChanged("")
        viewModel.persistDraft("")
        chats.setHistoryReady()
        runCurrent()

        assertNull(viewModel.restoredDraft.value)
        assertEquals(listOf(CHAT_ID), chats.clearedDrafts)
    }

    @Test
    fun `transient encrypted draft write failures retry without another edit`() = runTest {
        val chats = DraftRecordingChatRepository(
            storedDraft = null,
            draftWriteFailures = 2,
        )
        val viewModel = viewModel(chats)

        viewModel.persistDraft("survives a transient keystore failure")
        advanceUntilIdle()

        assertEquals(3, chats.draftSaveAttempts)
        assertEquals(listOf("survives a transient keystore failure"), chats.savedDrafts)
    }

    @Test
    fun `durably committed send clears the stored draft`() = runTest {
        val chats = DraftRecordingChatRepository(storedDraft = null)
        val viewModel = viewModel(chats)

        viewModel.send("sending now")

        assertEquals(1, chats.sentMessages.size)
        assertEquals(1, chats.clearedDrafts.size)
        assertEquals(CHAT_ID, chats.clearedDrafts.single())
    }

    @Test
    fun `failed pre-commit send keeps the stored draft`() = runTest {
        val chats = DraftRecordingChatRepository(storedDraft = null, failSends = true)
        val viewModel = viewModel(chats)

        viewModel.send("still typing this one")

        assertEquals(0, chats.clearedDrafts.size)
    }

    private fun viewModel(chats: ChatRepository) = ConversationViewModel(
        chatRepo = chats,
        walletRepo = UnusedWalletRepository,
        walletSync = NoOpTestWalletSync,
        callRepo = NoCallsRepository,
        messageSounds = SilentMessageSoundPlayer,
        realtime = InertConversationSignals,
        typingSignaller = RecordingTypingSignals(),
        savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
    )

    private class DraftRecordingChatRepository(
        private val storedDraft: String?,
        private val failSends: Boolean = false,
        initiallyHistoryReady: Boolean = true,
        private var draftWriteFailures: Int = 0,
    ) : ChatRepository {
        private val preview = ChatPreview(CHAT_ID, "Grace", "", "")
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        private val mutableHistoryReady = MutableStateFlow(initiallyHistoryReady)
        override val localHistoryReady: StateFlow<Boolean> = mutableHistoryReady
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(listOf(preview))
        val savedDrafts = mutableListOf<String>()
        val clearedDrafts = mutableListOf<String>()
        val sentMessages = mutableListOf<String>()
        var draftReads = 0
        var draftSaveAttempts = 0

        fun setHistoryReady() {
            mutableHistoryReady.value = true
        }

        override fun chat(chatId: String): ChatPreview? = preview.takeIf { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            if (failSends) throw java.io.IOException("offline before commit")
            sentMessages += text
            onDurablyCommitted("client-${sentMessages.size}")
        }

        override suspend fun composerDraft(chatId: String): String? {
            draftReads++
            return storedDraft
        }

        override suspend fun saveComposerDraft(chatId: String, text: String) {
            draftSaveAttempts++
            if (draftWriteFailures > 0) {
                draftWriteFailures--
                throw java.io.IOException("temporary encrypted-store failure")
            }
            savedDrafts += text
        }

        override suspend fun clearComposerDraft(chatId: String) {
            clearedDrafts += chatId
        }
    }

    private object UnusedWalletRepository : WalletRepository {
        override val balanceMinor: StateFlow<Long> = MutableStateFlow(0L)
        override val transactions: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        override fun transaction(id: String): Transaction? = null
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
        const val CHAT_ID = "conversation-draft-1"
    }
}
