package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.feature.wallet.TransactionDetailUiState
import com.kit.wallet.feature.wallet.TransactionDetailViewModel
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxType
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unknown id never falls back to a different transaction`() = runTest {
        val other = transaction("other")
        val viewModel = viewModel(FakeWalletRepository(listOf(other)), "missing")

        assertEquals(TransactionDetailUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun `missing route id fails closed instead of crashing`() = runTest {
        val viewModel = TransactionDetailViewModel(
            wallet = FakeWalletRepository(listOf(transaction("other"))),
            savedStateHandle = SavedStateHandle(),
        )

        assertEquals(TransactionDetailUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun `transaction arriving after initial cache emission becomes visible`() = runTest {
        val repository = FakeWalletRepository(emptyList())
        val viewModel = viewModel(repository, "target")
        val target = transaction("target")

        assertEquals(TransactionDetailUiState.NotFound, viewModel.uiState.value)
        repository.publish(listOf(transaction("other"), target))

        assertEquals(TransactionDetailUiState.Ready(target), viewModel.uiState.value)
    }

    @Test
    fun `transaction removed from cache becomes unavailable`() = runTest {
        val target = transaction("target")
        val repository = FakeWalletRepository(listOf(target))
        val viewModel = viewModel(repository, target.id)

        assertEquals(TransactionDetailUiState.Ready(target), viewModel.uiState.value)
        repository.publish(emptyList())

        assertEquals(TransactionDetailUiState.NotFound, viewModel.uiState.value)
    }

    private fun viewModel(repository: WalletRepository, transactionId: String) =
        TransactionDetailViewModel(
            wallet = repository,
            savedStateHandle = SavedStateHandle(mapOf("txId" to transactionId)),
        )

    private fun transaction(id: String) = Transaction(
        id = id,
        counterparty = "Test user",
        note = null,
        amountMinor = -1_000,
        time = "Now",
        dateGroup = "Today",
        type = TxType.SEND,
        reference = "TEST-$id",
    )

    private class FakeWalletRepository(initial: List<Transaction>) : WalletRepository {
        private val mutableTransactions = MutableStateFlow(initial)

        override val balanceMinor: StateFlow<Long> = MutableStateFlow(0L)
        override val transactions: StateFlow<List<Transaction>> = mutableTransactions
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())

        fun publish(transactions: List<Transaction>) {
            mutableTransactions.value = transactions
        }

        override fun transaction(id: String): Transaction? =
            mutableTransactions.value.firstOrNull { it.id == id }

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
}
