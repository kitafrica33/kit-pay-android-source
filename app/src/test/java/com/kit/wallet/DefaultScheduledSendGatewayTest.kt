package com.kit.wallet

import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.repository.ChatPaymentRequest
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.DefaultScheduledSendGateway
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultScheduledSendGatewayTest {
    @Test
    fun `scheduled text carries its exact owner into the encrypted send boundary`() = runTest {
        val sessions = MutableTestSessionStore(ownerSession())
        val owner = checkNotNull(sessions.current()).fence()
        val chats = RecordingChatRepository()
        val gateway = DefaultScheduledSendGateway(chats, RecordingWalletRepository(), sessions)
        var commits = 0

        gateway.sendText(owner, CONVERSATION_ID, "send this later") { commits++ }

        assertEquals(
            listOf(OwnedTextSend(owner, CONVERSATION_ID, "send this later")),
            chats.textSends,
        )
        assertEquals(1, commits)
    }

    @Test
    fun `scheduled payment request carries one owner through creation and encrypted sharing`() =
        runTest {
            val sessions = MutableTestSessionStore(ownerSession())
            val owner = checkNotNull(sessions.current()).fence()
            val chats = RecordingChatRepository()
            val wallet = RecordingWalletRepository()
            val gateway = DefaultScheduledSendGateway(chats, wallet, sessions)
            var commits = 0

            gateway.sendPaymentRequest(
                owner = owner,
                conversationId = CONVERSATION_ID,
                idempotencyKey = SCHEDULED_SEND_ID,
                amountMinor = AMOUNT_MINOR,
                note = NOTE,
            ) { commits++ }

            assertEquals(
                listOf(
                    OwnedPaymentRequest(
                        owner = owner,
                        peerUserId = PEER_USER_ID,
                        amountMinor = AMOUNT_MINOR,
                        note = NOTE,
                        idempotencyKey = "android-scheduled-request-$SCHEDULED_SEND_ID",
                    ),
                ),
                wallet.ownerRequests,
            )
            val shared = chats.paymentSends.single()
            assertEquals(owner, shared.owner)
            assertEquals(CONVERSATION_ID, shared.conversationId)
            assertEquals(1, commits)

            val descriptor = checkNotNull(KitPaymentMessage.parse(shared.descriptor))
            assertEquals(KitPaymentAction.REQUEST, descriptor.action)
            assertEquals(PAYMENT_REQUEST_ID, descriptor.referenceId)
            assertEquals(AMOUNT_MINOR, descriptor.amountMinor)
            assertEquals("UGX", descriptor.currencyCode)
            assertEquals(2, descriptor.currencyScale)
            assertEquals(NOTE, descriptor.note)
        }

    @Test
    fun `replacement login after request creation cannot receive the encrypted payment event`() =
        runTest {
            val sessions = MutableTestSessionStore(ownerSession())
            val owner = checkNotNull(sessions.current()).fence()
            val chats = RecordingChatRepository()
            val wallet = RecordingWalletRepository(
                afterOwnerRequest = { sessions.save(replacementSession()) },
            )
            val gateway = DefaultScheduledSendGateway(chats, wallet, sessions)
            var commits = 0

            val failure = runCatching {
                gateway.sendPaymentRequest(
                    owner = owner,
                    conversationId = CONVERSATION_ID,
                    idempotencyKey = SCHEDULED_SEND_ID,
                    amountMinor = AMOUNT_MINOR,
                    note = NOTE,
                ) { commits++ }
            }.exceptionOrNull()

            assertTrue(failure is SessionInvalidatedException)
            assertEquals(owner, wallet.ownerRequests.single().owner)
            assertTrue(chats.paymentSends.isEmpty())
            assertEquals(0, commits)
        }

    private data class OwnedTextSend(
        val owner: SessionFence,
        val conversationId: String,
        val text: String,
    )

    private data class OwnedPaymentSend(
        val owner: SessionFence,
        val conversationId: String,
        val descriptor: String,
    )

    private data class OwnedPaymentRequest(
        val owner: SessionFence,
        val peerUserId: String,
        val amountMinor: Long,
        val note: String?,
        val idempotencyKey: String,
    )

    private class RecordingChatRepository : ChatRepository {
        private val preview = ChatPreview(
            id = CONVERSATION_ID,
            name = "Grace",
            lastMessage = "",
            time = "",
            peerUserId = PEER_USER_ID,
        )

        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(listOf(preview))
        val textSends = mutableListOf<OwnedTextSend>()
        val paymentSends = mutableListOf<OwnedPaymentSend>()

        override fun chat(chatId: String): ChatPreview? = preview.takeIf { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = error("Unused")

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ): Unit = error("Scheduled text must use the owner-aware send boundary")

        override suspend fun sendMessageForOwner(
            owner: SessionFence,
            chatId: String,
            text: String,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            textSends += OwnedTextSend(owner, chatId, text)
            onDurablyCommitted("text-client-id")
        }

        override suspend fun sendPaymentEventForOwner(
            owner: SessionFence,
            chatId: String,
            descriptor: String,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) {
            paymentSends += OwnedPaymentSend(owner, chatId, descriptor)
            onDurablyCommitted("payment-client-id")
        }
    }

    private class RecordingWalletRepository(
        private val afterOwnerRequest: suspend () -> Unit = {},
    ) : WalletRepository {
        override val balanceMinor: StateFlow<Long> = MutableStateFlow(0L)
        override val transactions: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        val ownerRequests = mutableListOf<OwnedPaymentRequest>()

        override fun transaction(id: String): Transaction? = null

        override suspend fun createChatPaymentRequestForOwner(
            owner: SessionFence,
            peerUserId: String,
            amountMinor: Long,
            note: String?,
            idempotencyKey: String,
        ): ChatPaymentRequest {
            ownerRequests += OwnedPaymentRequest(
                owner = owner,
                peerUserId = peerUserId,
                amountMinor = amountMinor,
                note = note,
                idempotencyKey = idempotencyKey,
            )
            afterOwnerRequest()
            return ChatPaymentRequest(
                id = PAYMENT_REQUEST_ID,
                amountMinor = amountMinor,
                currencyCode = "UGX",
                currencyScale = 2,
                note = note,
            )
        }

        override suspend fun send(
            recipient: Contact,
            amountMinor: Long,
            note: String?,
            paymentPin: String,
        ): Transaction = error("Unused")

        override suspend fun request(from: Contact, amountMinor: Long, note: String?): Unit =
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

    private companion object {
        const val OWNER_ACCOUNT_ID = "11111111-1111-4111-8111-111111111111"
        const val REPLACEMENT_ACCOUNT_ID = "22222222-2222-4222-8222-222222222222"
        const val PEER_USER_ID = "33333333-3333-4333-8333-333333333333"
        const val CONVERSATION_ID = "44444444-4444-4444-8444-444444444444"
        const val SCHEDULED_SEND_ID = "55555555-5555-4555-8555-555555555555"
        const val PAYMENT_REQUEST_ID = "66666666-6666-4666-8666-666666666666"
        const val AMOUNT_MINOR = 12_500L
        const val NOTE = "rent"

        fun ownerSession() = testSession(
            accountId = OWNER_ACCOUNT_ID,
            sessionId = "owner-session",
            cacheScopeId = "owner-scope",
        )

        fun replacementSession() = testSession(
            accountId = REPLACEMENT_ACCOUNT_ID,
            sessionId = "replacement-session",
            cacheScopeId = "replacement-scope",
        )
    }
}
