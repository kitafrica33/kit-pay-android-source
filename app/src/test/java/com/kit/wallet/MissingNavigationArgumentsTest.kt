package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.repository.BillsRepository
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.feature.bills.BillPayViewModel
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
class MissingNavigationArgumentsTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `missing bill provider route fails closed without crashing or refreshing`() = runTest {
        val bills = FakeBillsRepository()

        val viewModel = BillPayViewModel(
            billsRepo = bills,
            wallet = UnusedWalletRepository,
            savedStateHandle = SavedStateHandle(),
        )

        assertNull(viewModel.provider.value)
        assertTrue(viewModel.error.value.orEmpty().contains("link is invalid"))
        assertEquals(0, bills.refreshCalls)
    }

    @Test
    fun `unknown bill provider stops loading after refresh`() = runTest {
        val bills = FakeBillsRepository()

        val viewModel = BillPayViewModel(
            billsRepo = bills,
            wallet = UnusedWalletRepository,
            savedStateHandle = SavedStateHandle(mapOf("providerId" to "missing")),
        )

        assertNull(viewModel.provider.value)
        assertEquals(1, bills.refreshCalls)
        assertTrue(viewModel.error.value.orEmpty().contains("no longer available"))
    }

    @Test
    fun `missing conversation route fails closed without asking repository for null identity`() {
        val chats = FakeChatRepository()

        val viewModel = ConversationViewModel(
            chatRepo = chats,
            walletRepo = UnusedWalletRepository,
            callRepo = UnusedCallRepository,
            messageSounds = UnusedMessageSoundPlayer,
            savedStateHandle = SavedStateHandle(),
        )

        assertNull(viewModel.chat.value)
        assertTrue(viewModel.messages.value.isEmpty())
        assertEquals(0, chats.chatLookups)
    }

    private class FakeBillsRepository : BillsRepository {
        override val providers: StateFlow<List<BillProvider>> = MutableStateFlow(emptyList())
        override val airtimeProducts: StateFlow<List<BillProvider>> = MutableStateFlow(emptyList())
        var refreshCalls = 0

        override fun provider(id: String): BillProvider? = providers.value.firstOrNull { it.id == id }

        override fun airtimeProduct(id: String): BillProvider? = null

        override suspend fun refresh() {
            refreshCalls += 1
        }
    }

    private class FakeChatRepository : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(emptyList())
        var chatLookups = 0

        override fun chat(chatId: String): ChatPreview? {
            chatLookups += 1
            return null
        }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(chatId: String, text: String) = Unit
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
        ): Transaction = error("Not used")

        override suspend fun request(from: Contact, amountMinor: Long, note: String?) = Unit

        override suspend fun payBill(
            provider: BillProvider,
            account: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Not used")

        override suspend fun buyAirtime(
            productId: String,
            phone: String,
            amountMinor: Long,
            paymentPin: String,
        ): Transaction = error("Not used")
    }

    private object UnusedCallRepository : CallRepository {
        override val calls: StateFlow<List<CallEntry>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
    }

    private object UnusedMessageSoundPlayer : MessageSoundPlayer {
        override fun playSent() = Unit
        override fun playReceived() = Unit
        override fun playPaymentReceived() = Unit
    }
}
