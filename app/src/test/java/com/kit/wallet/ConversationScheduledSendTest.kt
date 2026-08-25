package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.ScheduledSend
import com.kit.wallet.data.messaging.ScheduledSendDispatcher
import com.kit.wallet.data.messaging.ScheduledSendGateway
import com.kit.wallet.data.messaging.ScheduledSendKind
import com.kit.wallet.data.messaging.ScheduledSendState
import com.kit.wallet.data.messaging.ScheduledSendStore
import com.kit.wallet.data.session.SessionFence
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
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.acceptsReactions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class ConversationScheduledSendTest {
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val sessions = MutableTestSessionStore(testSession(OWNER_ID))
    private val disk = TestSecureMessagingStateStore()
    private val store = ScheduledSendStore(disk, sessions)
    private val gateway = QueueGateway()
    private val dispatcher = ScheduledSendDispatcher(store, gateway, clock)
    private val chats = SchedulingChatRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `a scheduled message joins the thread and clears the composer draft`() = runTest {
        val viewModel = viewModel()

        viewModel.scheduleSend("bring the receipt", NOW.toEpochMilli() + 3_600_000L)

        val queued = store.items.value.single()
        assertEquals(ScheduledSendKind.TEXT, queued.kind)
        assertEquals("bring the receipt", queued.text)
        assertEquals(CHAT_ID, queued.conversationId)
        assertEquals(listOf(CHAT_ID), chats.clearedDrafts)

        val bubble = viewModel.messages.value.single()
        assertEquals(DeliveryState.SCHEDULED, bubble.state)
        assertEquals(MessageKind.TEXT, bubble.kind)
        assertEquals("bring the receipt", bubble.text)
        assertTrue(bubble.fromMe)
        assertEquals(NOW.toEpochMilli() + 3_600_000L, bubble.scheduledAtEpochMillis)
        assertNull(viewModel.error.value)
    }

    @Test fun `a scheduled entry always sits at the foot of the thread`() = runTest {
        chats.publish(
            listOf(
                Message(
                    id = "sent-1",
                    text = "morning",
                    time = "08:00",
                    fromMe = true,
                    sortEpochMillis = NOW.toEpochMilli(),
                ),
            ),
        )
        val viewModel = viewModel()

        // Due tomorrow, yet it belongs after everything that has already happened.
        viewModel.scheduleSend("see you then", NOW.toEpochMilli() + 86_400_000L)

        assertEquals(
            listOf("sent-1", SCHEDULED_PREFIX + store.items.value.single().id),
            viewModel.messages.value.map { it.id },
        )
    }

    @Test fun `a scheduled entry cannot be reacted to`() {
        val scheduled = Message(
            id = "x",
            text = "later",
            time = "",
            fromMe = true,
            state = DeliveryState.SCHEDULED,
        )

        assertFalse(scheduled.acceptsReactions)
        assertFalse(scheduled.copy(state = DeliveryState.UNCONFIRMED).acceptsReactions)
    }

    @Test fun `a reserved prefix is refused before anything is stored`() = runTest {
        val viewModel = viewModel()

        viewModel.scheduleSend(
            KitPaymentMessage.PREFIX + "v=1",
            NOW.toEpochMilli() + 3_600_000L,
        )

        assertEquals(
            "Messages cannot start with one of Kit Pay's reserved prefixes",
            viewModel.error.value,
        )
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `a time the queue would refuse is refused in the same words`() = runTest {
        val viewModel = viewModel()

        viewModel.scheduleSend("too soon", NOW.toEpochMilli() + 1_000L)

        assertEquals("Pick a time at least a minute from now.", viewModel.error.value)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `a conversation from the previous login cannot queue plaintext for its successor`() =
        runTest {
            val viewModel = viewModel()
            sessions.save(testSession(OWNER_B))

            viewModel.scheduleSend("belongs to A", NOW.toEpochMilli() + 3_600_000L)

            val successorStore = ScheduledSendStore(disk, sessions)
            successorStore.load()
            assertEquals(emptyList<ScheduledSend>(), successorStore.items.value)
            assertEquals(emptyList<ScheduledSend>(), store.items.value)
            assertTrue(viewModel.error.value?.contains("session changed", ignoreCase = true) == true)

            val successorMessage = ScheduledSend(
                id = QUEUE_ID,
                conversationId = CHAT_ID,
                kind = ScheduledSendKind.TEXT,
                scheduledAtEpochMillis = NOW.toEpochMilli() + 3_600_000L,
                createdAtEpochMillis = NOW.toEpochMilli(),
                text = "belongs to B",
            )
            store.put(successorMessage)

            assertEquals(listOf(successorMessage), store.items.value)
            assertTrue(viewModel.messages.value.none { it.text == "belongs to B" })
        }

    @Test fun `an over-long message is refused`() = runTest {
        val viewModel = viewModel()

        viewModel.scheduleSend(
            "x".repeat(ScheduledSend.MAX_TEXT_LENGTH + 1),
            NOW.toEpochMilli() + 3_600_000L,
        )

        assertEquals("That message is too long to schedule", viewModel.error.value)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `a scheduled request creates nothing on the server until it is sent`() = runTest {
        val viewModel = viewModel()

        viewModel.schedulePaymentRequest(250_000, " rent ", NOW.toEpochMilli() + 3_600_000L)

        val queued = store.items.value.single()
        assertEquals(ScheduledSendKind.PAYMENT_REQUEST, queued.kind)
        assertEquals(250_000L, queued.amountMinor)
        assertEquals("rent", queued.note)
        assertEquals(emptyList<String>(), gateway.requests)

        val bubble = viewModel.messages.value.single()
        assertEquals(MessageKind.PAYMENT_REQUEST, bubble.kind)
        assertEquals(DeliveryState.SCHEDULED, bubble.state)
        assertEquals(250_000L, bubble.amountMinor)
        assertEquals("rent", bubble.paymentNote)
    }

    @Test fun `a request without an amount or a linked peer is refused`() = runTest {
        val viewModel = viewModel()

        viewModel.schedulePaymentRequest(0, null, NOW.toEpochMilli() + 3_600_000L)
        assertEquals("Enter an amount to request", viewModel.error.value)

        chats.publishChat(ChatPreview(CHAT_ID, "Grace", "", "", peerUserId = null))
        viewModel.schedulePaymentRequest(1_000, null, NOW.toEpochMilli() + 3_600_000L)
        assertEquals("This conversation is not linked to a Kit Pay account", viewModel.error.value)

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `send now hands a scheduled entry over immediately`() = runTest {
        val viewModel = viewModel()
        viewModel.scheduleSend("on my way", NOW.toEpochMilli() + 3_600_000L)
        val bubble = viewModel.messages.value.single()

        viewModel.sendScheduledNow(bubble)

        assertEquals(listOf("on my way"), gateway.texts)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertEquals(emptyList<Message>(), viewModel.messages.value)
    }

    @Test fun `rescheduling keeps the entry's identity and resets its backoff`() = runTest {
        val viewModel = viewModel()
        viewModel.scheduleSend("later then", NOW.toEpochMilli() + 3_600_000L)
        store.compareAndSet(
            store.items.value.single(),
            store.items.value.single().copy(attempts = 3, lastAttemptAtEpochMillis = 1L),
        )
        val bubble = viewModel.messages.value.single()
        val id = store.items.value.single().id

        viewModel.rescheduleSend(bubble, NOW.toEpochMilli() + 7_200_000L)

        val moved = store.items.value.single()
        assertEquals(id, moved.id)
        assertEquals(NOW.toEpochMilli() + 7_200_000L, moved.scheduledAtEpochMillis)
        assertEquals(0, moved.attempts)
        assertEquals(0L, moved.lastAttemptAtEpochMillis)
    }

    @Test fun `cancelling discards the entry without sending anything`() = runTest {
        val viewModel = viewModel()
        viewModel.scheduleSend("never mind", NOW.toEpochMilli() + 3_600_000L)
        val bubble = viewModel.messages.value.single()

        viewModel.cancelScheduledSend(bubble)

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertEquals(emptyList<String>(), gateway.texts)
        assertEquals(emptyList<Message>(), viewModel.messages.value)
    }

    @Test fun `an entry being sent right now is neither re-timed nor discarded`() = runTest {
        val viewModel = viewModel()
        viewModel.scheduleSend("mid-flight", NOW.toEpochMilli() + 3_600_000L)
        val bubble = viewModel.messages.value.single()
        store.compareAndSet(
            store.items.value.single(),
            store.items.value.single().copy(
                state = ScheduledSendState.SENDING,
                claimedAtEpochMillis = NOW.toEpochMilli(),
            ),
        )

        viewModel.rescheduleSend(bubble, NOW.toEpochMilli() + 7_200_000L)
        assertEquals("That message is being sent right now", viewModel.error.value)

        viewModel.clearError()
        viewModel.cancelScheduledSend(bubble)
        assertEquals("That message is being sent right now", viewModel.error.value)

        val untouched = store.items.value.single()
        assertEquals(ScheduledSendState.SENDING, untouched.state)
        assertEquals(NOW.toEpochMilli() + 3_600_000L, untouched.scheduledAtEpochMillis)
    }

    @Test fun `an unconfirmed entry is shown as one and can still be sent by hand`() = runTest {
        store.put(
            ScheduledSend(
                id = QUEUE_ID,
                conversationId = CHAT_ID,
                kind = ScheduledSendKind.TEXT,
                scheduledAtEpochMillis = NOW.toEpochMilli(),
                createdAtEpochMillis = NOW.toEpochMilli() - 60_000L,
                state = ScheduledSendState.UNCONFIRMED,
                text = "did this go?",
            ),
        )
        val viewModel = viewModel()

        val bubble = viewModel.messages.value.single()
        assertEquals(DeliveryState.UNCONFIRMED, bubble.state)
        assertEquals(SCHEDULED_PREFIX + QUEUE_ID, bubble.id)

        viewModel.sendScheduledNow(bubble)

        assertEquals(listOf("did this go?"), gateway.texts)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `scheduled actions ignore an ordinary message`() = runTest {
        val viewModel = viewModel()
        viewModel.scheduleSend("still here", NOW.toEpochMilli() + 3_600_000L)
        val ordinary = Message(id = "server-1", text = "hello", time = "08:00", fromMe = true)

        viewModel.sendScheduledNow(ordinary)
        viewModel.cancelScheduledSend(ordinary)
        viewModel.rescheduleSend(ordinary, NOW.toEpochMilli() + 7_200_000L)

        assertEquals(1, store.items.value.size)
        assertEquals(emptyList<String>(), gateway.texts)
    }

    @Test fun `scheduling is unavailable without a queue`() = runTest {
        val viewModel = ConversationViewModel(
            chatRepo = chats,
            walletRepo = UnusedWalletRepository,
            walletSync = NoOpTestWalletSync,
            callRepo = NoCallsRepository,
            messageSounds = SilentMessageSoundPlayer,
            realtime = InertConversationSignals,
            typingSignaller = RecordingTypingSignals(),
            savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
            clock = clock,
        )

        assertFalse(viewModel.schedulingAvailable)
        viewModel.scheduleSend("nowhere to put this", NOW.toEpochMilli() + 3_600_000L)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    private fun viewModel() = ConversationViewModel(
        chatRepo = chats,
        walletRepo = UnusedWalletRepository,
        walletSync = NoOpTestWalletSync,
        callRepo = NoCallsRepository,
        messageSounds = SilentMessageSoundPlayer,
        realtime = InertConversationSignals,
        typingSignaller = RecordingTypingSignals(),
        savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
        scheduledSends = store,
        scheduledDispatcher = dispatcher,
        clock = clock,
    ).also { assertTrue(it.schedulingAvailable) }

    private class QueueGateway : ScheduledSendGateway {
        override fun readyFor(owner: SessionFence): Boolean = true
        val texts = mutableListOf<String>()
        val requests = mutableListOf<String>()

        override suspend fun sendText(
            owner: SessionFence,
            conversationId: String,
            text: String,
            onDurablyCommitted: () -> Unit,
        ) {
            texts += text
            onDurablyCommitted()
        }

        override suspend fun sendPaymentRequest(
            owner: SessionFence,
            conversationId: String,
            idempotencyKey: String,
            amountMinor: Long,
            note: String?,
            onDurablyCommitted: () -> Unit,
        ) {
            requests += idempotencyKey
            onDurablyCommitted()
        }
    }

    private class SchedulingChatRepository : ChatRepository {
        private val previews = MutableStateFlow(
            listOf(ChatPreview(CHAT_ID, "Grace", "", "", peerUserId = PEER_USER_ID)),
        )
        private val thread = MutableStateFlow<List<Message>>(emptyList())
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = previews
        val clearedDrafts = mutableListOf<String>()

        fun publish(messages: List<Message>) {
            thread.value = messages
        }

        fun publishChat(updated: ChatPreview) {
            previews.value = listOf(updated)
        }

        override fun chat(chatId: String): ChatPreview? =
            previews.value.singleOrNull { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> = thread

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) = error("Scheduled sends go through the gateway")

        override suspend fun composerDraft(chatId: String): String? = null

        override suspend fun saveComposerDraft(chatId: String, text: String) = Unit

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
        const val OWNER_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff00a1"
        const val OWNER_B = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff00b2"
        const val CHAT_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0009"
        const val PEER_USER_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff00aa"
        const val QUEUE_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0001"
        const val SCHEDULED_PREFIX = "scheduled:"
        val NOW: Instant = Instant.parse("2026-08-25T09:00:00Z")
    }
}
