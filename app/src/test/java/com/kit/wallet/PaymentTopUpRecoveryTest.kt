package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.remote.KIT_INSUFFICIENT_FUNDS_CODE
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.BillsRepository
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletCurrency
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.feature.bills.AirtimeViewModel
import com.kit.wallet.feature.bills.BillPayViewModel
import com.kit.wallet.feature.chat.ConversationViewModel
import com.kit.wallet.feature.chat.MessageSoundPlayer
import com.kit.wallet.feature.wallet.SendMoneyViewModel
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.UserProfile
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
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentTopUpRecoveryTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening send money refreshes the authoritative recipient directory`() = runTest {
        val contacts = RecordingContacts()

        SendMoneyViewModel(
            RejectingWalletRepository(staleBalanceMinor = 100_000),
            authoritativeSync(),
            EmptyChats,
            contacts,
        )

        assertEquals(1, contacts.refreshCalls)
    }

    @Test
    fun `send rejection refreshes authoritative balance before offering top up`() = runTest {
        val wallet = RejectingWalletRepository(staleBalanceMinor = 100_000)
        val sync = authoritativeSync()
        val viewModel = SendMoneyViewModel(wallet, sync, EmptyChats, EmptyContacts)

        viewModel.send(contact(), 50_000, null, "", onSent = {})

        assertEquals(1, sync.refreshCalls)
        assertEquals(40_000L, viewModel.topUpRequired.value?.shortfallMinor)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `bill rejection retains quote and offers top up from refreshed balance`() = runTest {
        val wallet = RejectingWalletRepository(staleBalanceMinor = 100_000)
        val sync = authoritativeSync()
        val bills = FakeBillsRepository()
        val viewModel = BillPayViewModel(
            billsRepo = bills,
            wallet = wallet,
            walletSync = sync,
            savedStateHandle = SavedStateHandle(mapOf("providerId" to BILL.id)),
        )

        viewModel.review("meter-1", 50_000)
        val reviewed = viewModel.quote.value
        viewModel.pay("", onDone = {})

        assertEquals(1, sync.refreshCalls)
        assertEquals(40_000L, viewModel.topUpRequired.value?.shortfallMinor)
        assertSame(reviewed, viewModel.quote.value)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `airtime rejection retains quote and offers top up from refreshed balance`() = runTest {
        val wallet = RejectingWalletRepository(staleBalanceMinor = 100_000)
        val sync = authoritativeSync()
        val viewModel = AirtimeViewModel(wallet, sync, FakeBillsRepository(), FakeUserRepository)

        viewModel.review(AIRTIME.id, "+256700000001", 50_000)
        val reviewed = viewModel.quote.value
        viewModel.buy("", onDone = {})

        assertEquals(1, sync.refreshCalls)
        assertEquals(40_000L, viewModel.topUpRequired.value?.shortfallMinor)
        assertSame(reviewed, viewModel.quote.value)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `authoritative airtime failure stays on screen after released balance refresh`() = runTest {
        val wallet = RejectingWalletRepository(staleBalanceMinor = 100_000).apply {
            providerResult = Transaction(
                id = "airtime-failed",
                counterparty = "MTN Airtime",
                note = "+256 700 000 001",
                amountMinor = -50_000,
                time = "Just now",
                dateGroup = "Today",
                type = TxType.AIRTIME,
                status = TxStatus.FAILED,
                reference = "android-provider-reference",
            )
        }
        val viewModel = AirtimeViewModel(wallet, authoritativeSync(), FakeBillsRepository(), FakeUserRepository)
        var completed = false

        viewModel.review(AIRTIME.id, "+256700000001", 50_000)
        viewModel.buy("") { completed = true }

        assertEquals(false, completed)
        assertEquals(null, viewModel.quote.value)
        assertEquals(
            "The airtime purchase failed. Your held balance has been released.",
            viewModel.error.value,
        )
    }

    @Test
    fun `chat request rejection keeps card payable and offers common top up`() = runTest {
        val wallet = RejectingWalletRepository(staleBalanceMinor = 100_000)
        val sync = authoritativeSync()
        val viewModel = ConversationViewModel(
            chatRepo = PaymentChatRepository,
            walletRepo = wallet,
            walletSync = sync,
            callRepo = NoCalls,
            messageSounds = NoSounds,
            realtime = InertConversationSignals,
            typingSignaller = RecordingTypingSignals(),
            savedStateHandle = SavedStateHandle(mapOf("chatId" to CHAT_ID)),
        )

        viewModel.payPaymentRequest(requestMessage(), "")

        assertEquals(1, sync.refreshCalls)
        assertEquals(40_000L, viewModel.topUpRequired.value?.shortfallMinor)
        assertNull(viewModel.error.value)
    }

    private class RejectingWalletRepository(staleBalanceMinor: Long) : WalletRepository {
        var providerResult: Transaction? = null
        val balance = MutableStateFlow(staleBalanceMinor)
        override val balanceMinor: StateFlow<Long> = balance
        override val walletCurrency: StateFlow<WalletCurrency> =
            MutableStateFlow(WalletCurrency("UGX", 2))
        override val transactions: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())

        override fun transaction(id: String): Transaction? = null

        override suspend fun send(
            recipient: Contact,
            amountMinor: Long,
            note: String?,
            paymentPin: String,
        ): Transaction = throw wrappedInsufficientFunds()

        override suspend fun request(from: Contact, amountMinor: Long, note: String?) = Unit

        override suspend fun payChatPaymentRequest(
            requestId: String,
            amountMinor: Long,
            paymentPin: String,
        ) = throw wrappedInsufficientFunds()

        override suspend fun previewBill(
            provider: BillProvider,
            account: String,
            amountMinor: Long,
        ): FinancialOperationQuote = quote("bill_payment", account, provider.id, amountMinor)

        override suspend fun previewAirtime(
            productId: String,
            phone: String,
            amountMinor: Long,
        ): FinancialOperationQuote = quote("airtime_purchase", phone, productId, amountMinor)

        override suspend fun submitProviderOperation(
            quote: FinancialOperationQuote,
            paymentPin: String,
        ): Transaction = providerResult ?: throw wrappedInsufficientFunds()

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

    private class FakeBillsRepository : BillsRepository {
        override val providers: StateFlow<List<BillProvider>> = MutableStateFlow(listOf(BILL))
        override val airtimeProducts: StateFlow<List<BillProvider>> = MutableStateFlow(listOf(AIRTIME))
        override fun provider(id: String): BillProvider? = providers.value.firstOrNull { it.id == id }
        override fun airtimeProduct(id: String): BillProvider? =
            airtimeProducts.value.firstOrNull { it.id == id }
        override suspend fun refresh() = Unit
    }

    private object EmptyContacts : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
        override suspend fun syncDeviceContacts() = Unit
    }

    private class RecordingContacts : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(emptyList())
        var refreshCalls = 0

        override suspend fun refresh() {
            refreshCalls += 1
        }

        override suspend fun syncDeviceContacts() = Unit
    }

    private object EmptyChats : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(emptyList())
        override fun chat(chatId: String): ChatPreview? = null
        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())
        override suspend fun openDirectConversation(contact: Contact): String = error("Unused")
        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) = error("Unused")
    }

    private object PaymentChatRepository : ChatRepository {
        private val preview = ChatPreview(CHAT_ID, "Amina", "", "", peerUserId = PEER_ID)
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(listOf(preview))
        override fun chat(chatId: String): ChatPreview? = preview.takeIf { it.id == chatId }
        override fun conversation(chatId: String): StateFlow<List<Message>> = MutableStateFlow(emptyList())
        override suspend fun openDirectConversation(contact: Contact): String = error("Unused")
        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) = error("Unused")
    }

    private object FakeUserRepository : UserRepository {
        override val profile: StateFlow<UserProfile> = MutableStateFlow(
            UserProfile("Test User", "+256700000001", "test", "Verified"),
        )
        override suspend fun refreshProfile() = Unit
        override suspend fun updateProfile(name: String, tag: String) = Unit
        override suspend fun requestEmailAttachment(email: String) = error("Unused")
        override suspend fun verifyEmailAttachment(challengeId: String, code: String) = Unit
    }

    private object NoCalls : CallRepository {
        override val calls: StateFlow<List<CallEntry>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
    }

    private object NoSounds : MessageSoundPlayer {
        override fun playSent() = Unit
        override fun playReceived() = Unit
        override fun playPaymentReceived() = Unit
    }

    private fun requestMessage(): Message {
        val descriptor = KitPaymentMessage(
            action = KitPaymentAction.REQUEST,
            referenceId = REQUEST_ID,
            amountMinor = 50_000,
            currencyCode = "UGX",
            currencyScale = 2,
            note = null,
        )
        return Message(
            id = "message-1",
            text = "",
            time = "12:00",
            fromMe = false,
            kind = MessageKind.PAYMENT_REQUEST,
            mediaDescriptor = descriptor.encode(),
        )
    }

    private fun contact() = Contact(
        id = PEER_ID,
        name = "Amina",
        phone = "+256700000001",
        isKitUser = true,
        receivingWalletId = "wallet-2",
    )

    private companion object {
        const val CHAT_ID = "chat-1"
        const val PEER_ID = "11111111-1111-4111-8111-111111111111"
        const val REQUEST_ID = "22222222-2222-4222-8222-222222222222"
        val BILL = BillProvider("umeme", "Electricity", "Utilities", "Meter number")
        val AIRTIME = BillProvider("mtn-airtime", "MTN", "Airtime", "Phone number")

        fun wrappedInsufficientFunds(): Exception = IllegalStateException(
            "payment wrapper",
            KitWalletApiException(KIT_INSUFFICIENT_FUNDS_CODE, "Not enough money"),
        )

        fun authoritativeSync() = RecordingTestWalletSync(
            authoritativeBalanceMinor = 10_000,
            authoritativeCurrencyCode = "UGX",
            authoritativeCurrencyScale = 2,
        )

        fun quote(
            operationType: String,
            destinationId: String,
            productId: String,
            amountMinor: Long,
        ) = FinancialOperationQuote(
            quoteId = "quote-$operationType",
            operationType = operationType,
            destinationId = destinationId,
            amountMinor = amountMinor,
            recipientAmountMinor = amountMinor,
            feesMinor = 0,
            customerDebitMinor = amountMinor,
            currencyCode = "UGX",
            currencyScale = 2,
            feeMode = "sender_absorbs",
            expiresAt = null,
            feesKnown = true,
            authorizationPurpose = operationType,
            authorizationIntent = emptyMap(),
            sessionFence = SessionFence("session", "scope", null),
            productId = productId,
        )
    }
}
