package com.kit.wallet

import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.feature.wallet.RequestMoneyViewModel
import com.kit.wallet.feature.wallet.canReceiveKitPaymentRequest
import com.kit.wallet.feature.wallet.canReceiveKitTransfer
import com.kit.wallet.feature.home.sendableHomeFavorites
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Contact
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletInteractionPolicyTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `transfer preselection requires a real Kit identity and receiving wallet`() {
        assertTrue(contact().canReceiveKitTransfer())
        assertFalse(contact(id = "").canReceiveKitTransfer())
        assertFalse(contact(isKitUser = false).canReceiveKitTransfer())
        assertFalse(contact(receivingWalletId = null).canReceiveKitTransfer())
        assertFalse(contact(receivingWalletId = " ").canReceiveKitTransfer())
    }

    @Test
    fun `home only presents favorites that can be preselected for a transfer`() {
        val eligible = contact().copy(favorite = true)
        val inviteOnly = contact(isKitUser = false).copy(favorite = true)
        val walletLess = contact(receivingWalletId = null).copy(
            id = "kit-user-2",
            favorite = true,
        )

        assertEquals(listOf(eligible), sendableHomeFavorites(listOf(eligible, inviteOnly, walletLess)))
    }

    @Test
    fun `payment request eligibility requires a visible Kit user id but not their wallet id`() {
        assertTrue(contact(receivingWalletId = null).canReceiveKitPaymentRequest())
        assertFalse(contact(id = "").canReceiveKitPaymentRequest())
        assertFalse(contact(isKitUser = false).canReceiveKitPaymentRequest())
    }

    @Test
    fun `request view model rejects an invalid contact without reaching the wallet`() = runTest {
        val wallet = RecordingWalletRepository()
        val viewModel = RequestMoneyViewModel(
            wallet = wallet,
            contactRepo = FakeContactRepository(),
        )

        viewModel.request(
            from = contact(isKitUser = false),
            amountMinor = 2_500,
            note = null,
            onDone = {},
        )

        assertEquals("Choose a valid Kit Pay contact", viewModel.error.value)
        assertEquals(emptyList<Contact>(), wallet.requests)
    }

    @Test
    fun `request view model accepts a valid visible contact and positive amount`() = runTest {
        val valid = contact(receivingWalletId = null)
        val wallet = RecordingWalletRepository()
        val viewModel = RequestMoneyViewModel(
            wallet = wallet,
            contactRepo = FakeContactRepository(listOf(valid)),
        )
        var done = false

        viewModel.request(valid, 2_500, "Lunch") { done = true }

        assertEquals(listOf(valid), wallet.requests)
        assertTrue(done)
        assertEquals(null, viewModel.error.value)
    }

    private fun contact(
        id: String = "kit-user-1",
        isKitUser: Boolean = true,
        receivingWalletId: String? = "wallet-1",
    ) = Contact(
        id = id,
        name = "Flora Namisi",
        phone = "0761146015",
        isKitUser = isKitUser,
        receivingWalletId = receivingWalletId,
    )

    private class FakeContactRepository(initial: List<Contact> = emptyList()) : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(initial)
        override suspend fun refresh() = Unit
        override suspend fun syncDeviceContacts() = Unit
    }

    private class RecordingWalletRepository : WalletRepository {
        override val balanceMinor: StateFlow<Long> = MutableStateFlow(0L)
        override val transactions: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        val requests = mutableListOf<Contact>()

        override fun transaction(id: String): Transaction? = null

        override suspend fun send(
            recipient: Contact,
            amountMinor: Long,
            note: String?,
            paymentPin: String,
        ): Transaction = error("Not used")

        override suspend fun request(from: Contact, amountMinor: Long, note: String?) {
            requests += from
        }

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
}
