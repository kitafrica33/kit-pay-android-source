package com.kit.wallet

import com.kit.wallet.data.local.BeneficiaryContactDao
import com.kit.wallet.data.local.BeneficiaryContactEntity
import com.kit.wallet.data.local.ProfilePhotoDao
import com.kit.wallet.data.local.ProfilePhotoEntity
import com.kit.wallet.data.remote.KIT_INSUFFICIENT_FUNDS_CODE
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.BankingRepository
import com.kit.wallet.data.repository.BeneficiaryContactDirectory
import com.kit.wallet.data.repository.BeneficiaryPhoneIdentity
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.data.repository.MobileMoneyRepository
import com.kit.wallet.data.repository.ProfilePhotoDirectory
import com.kit.wallet.data.repository.WalletCurrency
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.WalletSyncResult
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.feature.funding.TopUpStage
import com.kit.wallet.feature.funding.TopUpViewModel
import com.kit.wallet.feature.bank.BankViewModel
import com.kit.wallet.feature.mobilemoney.MobileMoneyViewModel
import com.kit.wallet.ui.model.BankOperationKind
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.MobileMoneyAccount
import com.kit.wallet.ui.model.MobileMoneyNetwork
import com.kit.wallet.ui.model.MobileMoneyOperation
import com.kit.wallet.ui.model.MobileMoneyVerificationState
import com.kit.wallet.ui.model.TopUp
import com.kit.wallet.ui.model.Transaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopUpViewModelTest {
    @Before
    fun setUp() = Unit

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `dismiss fences a late wait completion from the next top up`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wallet = FakeWalletRepository(0)
        val gate = CompletableDeferred<Unit>()
        val sync = CancellationInsensitiveWalletSync(gate) { wallet.balance.value = 90_000 }
        val mobile = FakeMobileMoneyRepository()
        val viewModel = viewModel(wallet, sync, mobile, backgroundScope)
        runCurrent()

        beginWaiting(viewModel)
        runCurrent()
        assertSame(TopUpStage.Waiting, viewModel.stage.value)
        assertEquals(true, viewModel.busy.value)

        viewModel.dismiss()
        val replacement = checkNotNull(
            TopUp.requirementFor(120_000, 10_000, "UGX", 2),
        )
        viewModel.start(replacement)
        gate.complete(Unit)
        runCurrent()

        assertEquals(replacement, viewModel.requirement.value)
        assertSame(TopUpStage.ChooseSource, viewModel.stage.value)
        assertFalse(viewModel.busy.value)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `keep waiting still observes failure of the submitted operation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wallet = FakeWalletRepository(0)
        val mobile = FakeMobileMoneyRepository()
        val viewModel = viewModel(wallet, RecordingTestWalletSync(), mobile, backgroundScope)
        runCurrent()

        beginWaiting(viewModel)
        runCurrent()
        advanceTimeBy(150_001)
        runCurrent()
        assertSame(TopUpStage.StillMoving, viewModel.stage.value)

        mobile.operationsState.value = listOf(
            mobileOperation(
                id = FakeMobileMoneyRepository.OPERATION_ID,
                status = "failed",
                failureMessage = "Provider declined the collection",
            ),
        )
        viewModel.keepWaiting()
        runCurrent()

        assertSame(TopUpStage.ChooseSource, viewModel.stage.value)
        assertEquals("Provider declined the collection", viewModel.error.value)
        assertFalse(viewModel.busy.value)
    }

    @Test
    fun `new account is selected without relying on shared StateFlow propagation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wallet = FakeWalletRepository(0)
        val mobile = FakeMobileMoneyRepository(
            initialAccounts = listOf(mobileAccount("existing")),
        )
        val viewModel = viewModel(wallet, RecordingTestWalletSync(), mobile, backgroundScope)
        runCurrent()
        viewModel.start(requirement())
        runCurrent()

        // Deliberately never collect `sources`: its WhileSubscribed projection remains empty.
        assertEquals(emptyList<Any>(), viewModel.sources.value)
        viewModel.addMobileMoneySource("MTN_UG", "+256700000002", "Second phone")
        runCurrent()

        assertEquals(FakeMobileMoneyRepository.ADDED_ID, viewModel.selectedSourceId.value)
        assertFalse(viewModel.busy.value)
    }

    @Test
    fun `funded is published only after an authoritative wallet refresh`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wallet = FakeWalletRepository(0)
        val sync = RecordingTestWalletSync(authoritativeBalanceMinor = 50_000)
        val viewModel = viewModel(wallet, sync, FakeMobileMoneyRepository(), backgroundScope)
        runCurrent()

        beginWaiting(viewModel)
        runCurrent()

        assertEquals(1, sync.refreshCalls)
        assertSame(TopUpStage.Funded, viewModel.stage.value)
        assertFalse(viewModel.busy.value)
    }

    @Test
    fun `mobile money server rejection uses refreshed authoritative balance`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wallet = FakeWalletRepository(100_000)
        val sync = RecordingTestWalletSync(authoritativeBalanceMinor = 10_000)
        val mobile = FakeMobileMoneyRepository(submissionFailure = insufficientFunds())
        val viewModel = MobileMoneyViewModel(
            mobileMoney = mobile,
            wallet = wallet,
            walletSync = sync,
            contacts = EmptyContactRepository,
            beneficiaryContacts = BeneficiaryContactDirectory(
                EmptyBeneficiaryContactDao,
                MutableTestSessionStore(testSession("top-up")),
                EmptyBeneficiaryPhoneIdentity,
                backgroundScope,
            ),
            profilePhotos = ProfilePhotoDirectory(
                EmptyProfilePhotoDao,
                MutableTestSessionStore(testSession("top-up")),
                backgroundScope,
            ),
        )
        runCurrent()

        viewModel.preview("payout", FakeMobileMoneyRepository.DEFAULT_ID, 50_000, "sender_absorbs")
        runCurrent()
        viewModel.submit("") {}
        runCurrent()

        assertEquals(1, sync.refreshCalls)
        assertEquals(40_000L, viewModel.topUpRequired.value?.shortfallMinor)
        assertNull(viewModel.error.value)
    }

    @Test
    fun `bank server rejection uses refreshed authoritative balance`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wallet = FakeWalletRepository(100_000)
        val sync = RecordingTestWalletSync(authoritativeBalanceMinor = 10_000)
        val banking = FakeBankingRepository(insufficientFunds())
        val viewModel = BankViewModel(
            banking = banking,
            wallet = wallet,
            walletSync = sync,
            profilePhotos = ProfilePhotoDirectory(
                EmptyProfilePhotoDao,
                MutableTestSessionStore(testSession("top-up")),
                backgroundScope,
            ),
        )
        runCurrent()

        viewModel.preview(BankOperationKind.WITHDRAWAL, "bank-account-1", 50_000, "sender_absorbs")
        runCurrent()
        viewModel.submit("") {}
        runCurrent()

        assertEquals(1, sync.refreshCalls)
        assertEquals(40_000L, viewModel.topUpRequired.value?.shortfallMinor)
        assertNull(viewModel.error.value)
    }

    private fun TestScope.beginWaiting(viewModel: TopUpViewModel) {
        viewModel.start(requirement())
        viewModel.select(FakeMobileMoneyRepository.DEFAULT_ID)
        viewModel.review()
        runCurrent()
        assertSame(TopUpStage.Review, viewModel.stage.value)
        viewModel.confirm("")
    }

    private fun requirement() = checkNotNull(
        TopUp.requirementFor(50_000, 0, "UGX", 2),
    )

    private fun viewModel(
        wallet: FakeWalletRepository,
        sync: WalletSyncRepository,
        mobile: FakeMobileMoneyRepository,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = TopUpViewModel(
        mobileMoney = mobile,
        banking = EmptyBankingRepository,
        wallet = wallet,
        walletSync = sync,
        contacts = EmptyContactRepository,
        beneficiaryContacts = BeneficiaryContactDirectory(
            EmptyBeneficiaryContactDao,
            MutableTestSessionStore(testSession("top-up")),
            EmptyBeneficiaryPhoneIdentity,
            scope,
        ),
        profilePhotos = ProfilePhotoDirectory(
            EmptyProfilePhotoDao,
            MutableTestSessionStore(testSession("top-up")),
            scope,
        ),
    )

    private class CancellationInsensitiveWalletSync(
        private val gate: CompletableDeferred<Unit>,
        private val afterGate: () -> Unit,
    ) : WalletSyncRepository {
        override suspend fun refresh(): WalletSyncResult {
            withContext(NonCancellable) { gate.await() }
            afterGate()
            return WalletSyncResult(1, 0, false)
        }

        override suspend fun clearCachedUserData(ownerScopeId: String?) = Unit
    }

    private class FakeWalletRepository(initialBalance: Long) : WalletRepository {
        val balance = MutableStateFlow(initialBalance)
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
        ): Transaction = error("Unused")
        override suspend fun request(from: Contact, amountMinor: Long, note: String?) = Unit
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

    private class FakeMobileMoneyRepository(
        initialAccounts: List<MobileMoneyAccount> = listOf(mobileAccount(DEFAULT_ID)),
        private val submissionFailure: Exception? = null,
    ) : MobileMoneyRepository {
        override val networks: StateFlow<List<MobileMoneyNetwork>> = MutableStateFlow(emptyList())
        val accountsState = MutableStateFlow(initialAccounts)
        override val accounts: StateFlow<List<MobileMoneyAccount>> = accountsState
        val operationsState = MutableStateFlow<List<MobileMoneyOperation>>(emptyList())
        override val operations: StateFlow<List<MobileMoneyOperation>> = operationsState
        override val verification: StateFlow<MobileMoneyVerificationState?> = MutableStateFlow(null)
        override suspend fun refresh() = Unit
        override suspend fun verifyAndSaveAccount(
            networkCode: String,
            phoneNumber: String,
            label: String,
            kind: String,
        ) {
            accountsState.value += mobileAccount(ADDED_ID, label)
        }
        override suspend fun createOperation(
            action: String,
            accountId: String,
            amountMinor: Long,
            paymentPin: String,
            feeMode: String,
        ) = Unit
        override suspend fun previewOperation(
            action: String,
            accountId: String,
            amountMinor: Long,
            feeMode: String,
        ): FinancialOperationQuote = quote(action, accountId, amountMinor, feeMode)
        override suspend fun submitOperation(
            quote: FinancialOperationQuote,
            paymentPin: String,
        ): String {
            submissionFailure?.let { throw it }
            return OPERATION_ID
        }

        companion object {
            const val DEFAULT_ID = "mobile-existing"
            const val ADDED_ID = "mobile-added"
            const val OPERATION_ID = "operation-1"
        }
    }

    private class FakeBankingRepository(
        private val submissionFailure: Exception,
    ) : BankingRepository {
        override val banks: StateFlow<List<BankInstitution>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        override val operations: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
        override suspend fun addBeneficiary(
            bankId: String,
            accountNumber: String,
            label: String,
            kind: String,
        ) = Unit
        override suspend fun createOperation(
            type: String,
            beneficiaryId: String,
            amountMinor: Long,
            paymentPin: String,
            feeMode: String,
        ) = Unit
        override suspend fun previewOperation(
            type: String,
            beneficiaryId: String,
            amountMinor: Long,
            feeMode: String,
        ): FinancialOperationQuote = FinancialOperationQuote(
            quoteId = "bank-quote",
            operationType = type,
            destinationId = beneficiaryId,
            amountMinor = amountMinor,
            recipientAmountMinor = amountMinor,
            feesMinor = 0,
            customerDebitMinor = amountMinor,
            currencyCode = "UGX",
            currencyScale = 2,
            feeMode = feeMode,
            expiresAt = null,
            feesKnown = true,
            authorizationPurpose = "bank_withdrawal",
            authorizationIntent = emptyMap(),
            sessionFence = SessionFence("session", "scope", null),
        )
        override suspend fun submitOperation(
            quote: FinancialOperationQuote,
            paymentPin: String,
        ): String = throw submissionFailure
    }

    private object EmptyBankingRepository : BankingRepository {
        override val banks: StateFlow<List<BankInstitution>> = MutableStateFlow(emptyList())
        override val beneficiaries: StateFlow<List<Beneficiary>> = MutableStateFlow(emptyList())
        override val operations: StateFlow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
        override suspend fun addBeneficiary(
            bankId: String,
            accountNumber: String,
            label: String,
            kind: String,
        ) = Unit
        override suspend fun createOperation(
            type: String,
            beneficiaryId: String,
            amountMinor: Long,
            paymentPin: String,
            feeMode: String,
        ) = Unit
        override suspend fun previewOperation(
            type: String,
            beneficiaryId: String,
            amountMinor: Long,
            feeMode: String,
        ): FinancialOperationQuote = error("Unused")
        override suspend fun submitOperation(
            quote: FinancialOperationQuote,
            paymentPin: String,
        ): String = error("Unused")
    }

    private object EmptyContactRepository : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(emptyList())
        override suspend fun refresh() = Unit
        override suspend fun syncDeviceContacts() = Unit
    }

    private object EmptyBeneficiaryContactDao : BeneficiaryContactDao {
        override fun observeForOwner(
            ownerScopeId: String,
        ): Flow<List<BeneficiaryContactEntity>> = MutableStateFlow(emptyList())
        override suspend fun put(links: List<BeneficiaryContactEntity>) = Unit
        override suspend fun forget(ownerScopeId: String, beneficiaryIds: List<String>) = Unit
        override suspend fun clear() = Unit
    }

    private object EmptyProfilePhotoDao : ProfilePhotoDao {
        override fun observeForOwner(
            ownerScopeId: String,
        ): Flow<List<ProfilePhotoEntity>> = MutableStateFlow(emptyList())
        override suspend fun put(photos: List<ProfilePhotoEntity>) = Unit
        override suspend fun forget(ownerScopeId: String, userIds: List<String>) = Unit
        override suspend fun clear() = Unit
    }

    private object EmptyBeneficiaryPhoneIdentity : BeneficiaryPhoneIdentity {
        override fun digest(phoneNumber: String?): String? = null
    }

    private companion object {
        fun mobileAccount(id: String, label: String = "My phone") = MobileMoneyAccount(
            id = id,
            kind = "own",
            label = label,
            networkCode = "MTN_UG",
            networkName = "MTN",
            accountName = "Test User",
            phoneNumberMasked = "+256•••001",
            currencyCode = "UGX",
            currencyScale = 2,
            status = "active",
        )

        fun mobileOperation(id: String, status: String, failureMessage: String?) =
            MobileMoneyOperation(
                id = id,
                reference = "ref-1",
                action = "collection",
                accountId = FakeMobileMoneyRepository.DEFAULT_ID,
                networkCode = "MTN_UG",
                networkName = "MTN",
                amountMinor = 50_000,
                currencyCode = "UGX",
                currencyScale = 2,
                status = status,
                submissionStage = null,
                createdAt = null,
                failureMessage = failureMessage,
            )

        fun quote(
            operationType: String,
            accountId: String,
            amountMinor: Long,
            feeMode: String,
        ) =
            FinancialOperationQuote(
                quoteId = "quote-1",
                operationType = operationType,
                destinationId = accountId,
                amountMinor = amountMinor,
                recipientAmountMinor = amountMinor,
                feesMinor = 0,
                customerDebitMinor = amountMinor,
                currencyCode = "UGX",
                currencyScale = 2,
                feeMode = feeMode,
                expiresAt = null,
                feesKnown = true,
                authorizationPurpose = "mobile_money_collection",
                authorizationIntent = emptyMap(),
                sessionFence = SessionFence("session", "scope", null),
            )

        fun insufficientFunds() = KitWalletApiException(
            KIT_INSUFFICIENT_FUNDS_CODE,
            "Not enough money",
        )
    }
}
