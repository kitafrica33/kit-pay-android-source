package com.kit.wallet.feature.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.BankingRepository
import com.kit.wallet.data.repository.BankDeposit
import com.kit.wallet.data.repository.BankDepositRepository
import com.kit.wallet.data.repository.BankFundingAccount
import com.kit.wallet.data.remote.isKitInsufficientFundsError
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.data.repository.ProfilePhotoDirectory
import com.kit.wallet.data.repository.UnavailableBankDepositRepository
import com.kit.wallet.data.repository.WalletCurrency
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.ui.model.BankOperationKind
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BeneficiaryIdentity
import com.kit.wallet.ui.model.TopUp
import com.kit.wallet.ui.model.TopUpRequirement
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@HiltViewModel
class BankViewModel @Inject constructor(
    private val banking: BankingRepository,
    private val wallet: WalletRepository,
    private val walletSync: WalletSyncRepository,
    profilePhotos: ProfilePhotoDirectory,
    private val bankDeposits: BankDepositRepository = UnavailableBankDepositRepository,
) : ViewModel() {
    private var settlementScreenActive = false

    /**
     * The saved bank accounts, each carrying a face when the server says the account belongs to a
     * Kit Pay member. A bank account number is nothing the device address book can be matched
     * against, so unlike a mobile money destination there is no local fallback here — see
     * [BeneficiaryIdentity].
     */
    val beneficiaries: StateFlow<List<Beneficiary>> = combine(
        banking.beneficiaries,
        profilePhotos.snapshots,
    ) { saved, photoSnapshot ->
        val knownPhotos = profilePhotos.currentPhotos(photoSnapshot)
        saved.map { beneficiary ->
            beneficiary.copy(
                avatarUrl = BeneficiaryIdentity.avatarUrlFor(
                    kitUserId = beneficiary.kitUserId,
                    serverAvatarUrl = beneficiary.avatarUrl,
                    knownPhotos = knownPhotos,
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bankTransfers = banking.operations
    val banks = banking.banks
    val fundingAccounts: StateFlow<List<BankFundingAccount>> = bankDeposits.fundingAccounts
    val deposits: StateFlow<List<BankDeposit>> = bankDeposits.deposits
    val walletCurrency: StateFlow<WalletCurrency> = wallet.walletCurrency

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableQuote = MutableStateFlow<FinancialOperationQuote?>(null)
    val quote = mutableQuote.asStateFlow()
    private var depositPoll: Job? = null

    /**
     * A withdrawal or transfer this wallet cannot pay for, raised once and cleared by the screen.
     *
     * A deposit is money arriving, so it can never be short. The quote is left standing, so the
     * same figures are waiting to be approved once the wallet has been topped up.
     */
    private val mutableTopUpRequired = MutableStateFlow<TopUpRequirement?>(null)
    val topUpRequired = mutableTopUpRequired.asStateFlow()

    fun clearTopUpRequired() {
        mutableTopUpRequired.value = null
    }

    init {
        viewModelScope.launch { runCatching { banking.refresh() } }
    }

    fun setSettlementScreenActive(active: Boolean) {
        if (settlementScreenActive == active) return
        settlementScreenActive = active
        banking.setSettlementScreenActive(active)
    }

    override fun onCleared() {
        if (settlementScreenActive) banking.setSettlementScreenActive(false)
        settlementScreenActive = false
        super.onCleared()
    }

    fun addAccount(
        bankId: String,
        accountNumber: String,
        label: String,
        kind: String,
        onDone: () -> Unit,
    ) {
        runCommand(onDone) { banking.addBeneficiary(bankId, accountNumber, label, kind) }
    }

    fun refreshDeposits(enabled: Boolean) {
        if (!enabled) return
        runCommand { bankDeposits.refresh() }
    }

    fun createDeposit(
        fundingAccountId: String,
        amountMinor: Long,
        note: String?,
        onDone: (BankDeposit) -> Unit,
    ) {
        runDepositCommand(onDone) {
            bankDeposits.create(fundingAccountId, amountMinor, note)
        }
    }

    fun uploadDepositProof(
        depositId: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        onDone: (BankDeposit) -> Unit,
    ) {
        runDepositCommand(onDone) {
            bankDeposits.attachProof(depositId, bytes, filename, mimeType)
        }
    }

    fun refreshDeposit(depositId: String, onDone: (BankDeposit) -> Unit = {}) {
        runDepositCommand(onDone) { bankDeposits.refreshDeposit(depositId) }
    }

    fun observeDeposit(depositId: String?) {
        depositPoll?.cancel()
        depositPoll = null
        if (depositId == null) return
        depositPoll = viewModelScope.launch {
            repeat(120) {
                delay(5_000)
                val current = deposits.value.firstOrNull { it.id == depositId } ?: return@launch
                if (current.terminal) return@launch
                runCatching { bankDeposits.refreshDeposit(depositId) }
            }
        }
    }

    fun preview(
        operation: BankOperationKind,
        beneficiaryId: String,
        amountMinor: Long,
        feeMode: String,
    ) {
        runCommand {
            val quote = banking.previewOperation(
                operation.apiType, beneficiaryId, amountMinor, feeMode,
            )
            mutableQuote.value = quote
            // Raised on the quote rather than the amount: the fee mode decides how much actually
            // leaves the wallet, and nobody should be asked to approve what it cannot cover.
            mutableTopUpRequired.value = shortfallFor(quote)
        }
    }

    fun submit(paymentPin: String, onDone: () -> Unit) {
        val quote = mutableQuote.value ?: return
        runCommand {
            try {
                banking.submitOperation(quote, paymentPin)
            } catch (error: Exception) {
                // The balance can move between quoting and charging, so the server's refusal is
                // the backstop. Rethrown when it is anything else, or when the wallet turns out to
                // cover it after all, so the message is not swallowed.
                val shortfall = if (error.isKitInsufficientFundsError()) {
                    val refreshed = walletSync.refresh()
                    shortfallFor(
                        quote,
                        refreshed.selectedAvailableBalanceMinor ?: wallet.balanceMinor.value,
                    )
                } else {
                    null
                }
                mutableTopUpRequired.value = shortfall ?: throw error
                // Deliberately not calling `onDone`: the sheet stays on the quote it failed to
                // submit, ready to be approved again once the wallet is topped up.
                return@runCommand
            }
            mutableQuote.value = null
            onDone()
        }
    }

    /** How far the wallet falls short of a payment out, or null when it covers it. */
    private fun shortfallFor(
        quote: FinancialOperationQuote,
        balanceMinor: Long = wallet.balanceMinor.value,
    ): TopUpRequirement? {
        if (quote.operationType == BankOperationKind.DEPOSIT.apiType) return null
        return TopUp.requirementFor(
            requiredMinor = quote.customerDebitMinor,
            balanceMinor = balanceMinor,
            currencyCode = quote.currencyCode,
            currencyScale = quote.currencyScale,
        )
    }

    fun clearQuote() { mutableQuote.value = null }

    fun clearError() {
        mutableError.value = null
    }

    private fun runCommand(onDone: () -> Unit = {}, command: suspend () -> Unit) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            try {
                command()
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The bank request could not be completed"
            } finally {
                mutableBusy.value = false
            }
        }
    }

    private fun runDepositCommand(
        onDone: (BankDeposit) -> Unit,
        command: suspend () -> BankDeposit,
    ) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            try {
                onDone(command())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The bank deposit could not be completed"
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
