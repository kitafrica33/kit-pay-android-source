package com.kit.wallet

import com.kit.wallet.data.notifications.PaymentClaimLink
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.navigation.PaymentClaimNavigationTarget
import com.kit.wallet.navigation.PaymentClaimNavigationViewModel
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session races around claim-alert resolution: the account that tapped is the resolution's
 * security context, and any sign-out or account switch — during an await, or after a target is
 * already resolved — must suppress navigation rather than steer whoever is signed in next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentClaimNavigationViewModelTest {

    private val claimId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val otherClaimId = "b7f9d9a2-5a1e-4a5b-9d5c-2f9f4f4d1a10"
    private val groupId = "0e5a9c3d-7a4b-4a4e-8a2f-6d3b1c9e7f21"
    private val owner = "7a1b2c3d-4e5f-4671-8293-a4b5c6d7e8f9"
    private val sender = "5b0e7d7c-1f2a-4b3c-8d4e-9f0a1b2c3d4e"
    private val otherAccount = "1c2d3e4f-5a6b-4c8d-9e0f-a1b2c3d4e5f6"

    private val walletActivity = PaymentClaimNavigationTarget.WalletHistory(claimId)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun claim() = TransferClaim(
        id = claimId,
        transactionId = "tx-1",
        status = TransferClaimStatus.PENDING,
        amountMinor = 5_000,
        senderUserId = sender,
        recipientUserId = owner,
    )

    @Test
    fun `a tap with no canonical signed-in account resolves to nothing at all`() = runTest {
        for (accountId in listOf(null, "user-1", " $owner ")) {
            val viewModel = PaymentClaimNavigationViewModel(
                FakeWalletRepository(accountId),
                EmptyChats,
            )

            viewModel.open(PaymentClaimLink(claimId))

            assertNull(viewModel.target.value)
            assertFalse(viewModel.targetOwnerStillCurrent())
        }
    }

    @Test
    fun `an account switch during the claim refetch suppresses the stale completion`() = runTest {
        val wallet = FakeWalletRepository(owner)
        val viewModel = PaymentClaimNavigationViewModel(wallet, EmptyChats)
        // First land a real target, to prove suppression also clears stale state.
        wallet.capability = { false }
        viewModel.open(PaymentClaimLink(claimId))
        assertEquals(walletActivity, viewModel.target.value)
        assertTrue(viewModel.targetOwnerStillCurrent())

        val gate = CompletableDeferred<Unit>()
        wallet.capability = { true }
        wallet.claim = { gate.await(); claim() }
        viewModel.open(PaymentClaimLink(claimId))
        wallet.accountId = otherAccount
        gate.complete(Unit)

        assertNull(viewModel.target.value)
        assertFalse(viewModel.targetOwnerStillCurrent())
    }

    @Test
    fun `a sign-out during the capability check suppresses navigation entirely`() = runTest {
        val wallet = FakeWalletRepository(owner)
        val viewModel = PaymentClaimNavigationViewModel(wallet, EmptyChats)
        val gate = CompletableDeferred<Unit>()
        wallet.capability = { gate.await(); true }

        viewModel.open(PaymentClaimLink(claimId))
        wallet.accountId = null
        gate.complete(Unit)

        assertNull(viewModel.target.value)
        assertFalse(viewModel.targetOwnerStillCurrent())
    }

    @Test
    fun `a newer tap cancels the older resolution, whose late completion never lands`() = runTest {
        val wallet = FakeWalletRepository(owner)
        val viewModel = PaymentClaimNavigationViewModel(wallet, EmptyChats)
        val firstGate = CompletableDeferred<Unit>()
        var firstTapReachedFetch = false
        wallet.capability = { true }
        wallet.claim = { firstTapReachedFetch = true; firstGate.await(); error("unreachable") }

        viewModel.open(PaymentClaimLink(claimId))
        assertTrue(firstTapReachedFetch)

        // The newer tap resolves immediately to its own claim's wallet activity.
        wallet.claim = { error("offline") }
        viewModel.open(PaymentClaimLink(otherClaimId))
        val newerTarget = PaymentClaimNavigationTarget.WalletHistory(otherClaimId)
        assertEquals(newerTarget, viewModel.target.value)

        // Releasing the cancelled resolution changes nothing.
        firstGate.complete(Unit)
        assertEquals(newerTarget, viewModel.target.value)
        assertTrue(viewModel.targetOwnerStillCurrent())
    }

    @Test
    fun `a resolved target stops being navigable the moment its account is not current`() = runTest {
        val wallet = FakeWalletRepository(owner.uppercase())
        val viewModel = PaymentClaimNavigationViewModel(wallet, EmptyChats)
        wallet.capability = { false }
        viewModel.open(PaymentClaimLink(claimId))
        assertEquals(walletActivity, viewModel.target.value)

        // A case change of the same account is not a switch; the comparison is canonical.
        wallet.accountId = owner
        assertTrue(viewModel.targetOwnerStillCurrent())

        wallet.accountId = otherAccount
        assertFalse(viewModel.targetOwnerStillCurrent())
        wallet.accountId = null
        assertFalse(viewModel.targetOwnerStillCurrent())

        viewModel.consumed()
        wallet.accountId = owner
        assertNull(viewModel.target.value)
        assertFalse(viewModel.targetOwnerStillCurrent())
    }

    @Test
    fun `a fenced resolution still lands in the hinted group when everything checks out`() = runTest {
        val wallet = FakeWalletRepository(owner)
        wallet.capability = { true }
        wallet.claim = { claim() }
        val chats = GroupChats(
            group = ChatPreview(groupId, "Group", "", "10:00", isGroup = true),
            roster = listOf(
                ChatMember(userId = owner, name = "Me"),
                ChatMember(userId = sender, name = "Sender"),
            ),
        )
        val viewModel = PaymentClaimNavigationViewModel(wallet, chats)

        viewModel.open(PaymentClaimLink(claimId, conversationId = groupId))

        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = null),
            viewModel.target.value,
        )
        assertTrue(viewModel.targetOwnerStillCurrent())
    }

    private class FakeWalletRepository(var accountId: String?) : WalletRepository {
        var capability: suspend () -> Boolean = { false }
        var claim: suspend (String) -> TransferClaim = { error("No claim") }

        override val currentAccountId: String?
            get() = accountId

        override suspend fun refreshClaimableTransfersCapability(): Boolean = capability()

        override suspend fun transferClaim(claimId: String): TransferClaim = claim(claimId)

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

        override suspend fun request(from: Contact, amountMinor: Long, note: String?) =
            error("Not used")

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

    private class GroupChats(
        group: ChatPreview,
        private val roster: List<ChatMember>,
    ) : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(listOf(group))
        override fun chat(chatId: String): ChatPreview? =
            chats.value.firstOrNull { it.id == chatId }

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override fun groupMembers(chatId: String): StateFlow<List<ChatMember>> =
            MutableStateFlow(roster)

        override suspend fun openDirectConversation(contact: Contact): String = error("Unused")
        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) = error("Unused")
    }
}
