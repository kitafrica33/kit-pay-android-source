package com.kit.wallet.feature.mobilemoney

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.isKitInsufficientFundsError
import com.kit.wallet.data.repository.BeneficiaryContactDirectory
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.MobileMoneyRepository
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.data.repository.ProfilePhotoDirectory
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.ui.model.BeneficiaryIdentity
import com.kit.wallet.ui.model.MobileMoneyAccount
import com.kit.wallet.ui.model.TopUp
import com.kit.wallet.ui.model.TopUpRequirement
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MobileMoneyViewModel @Inject constructor(
    private val mobileMoney: MobileMoneyRepository,
    private val wallet: WalletRepository,
    private val walletSync: WalletSyncRepository,
    contacts: ContactRepository,
    beneficiaryContacts: BeneficiaryContactDirectory,
    profilePhotos: ProfilePhotoDirectory,
) : ViewModel() {
    val networks = mobileMoney.networks

    /**
     * The saved accounts, each carrying the face to draw beside it when this device can establish
     * one honestly. [BeneficiaryIdentity] holds the rules; the short version is that the masked
     * number the server returns is never matched against the address book, because several real
     * numbers fit one mask and the wrong face beside a payment destination is worse than none.
     */
    val accounts: StateFlow<List<MobileMoneyAccount>> = combine(
        mobileMoney.accounts,
        contacts.contacts,
        beneficiaryContacts.snapshots,
        profilePhotos.snapshots,
    ) { saved, addressBook, linkSnapshot, photoSnapshot ->
        val links = beneficiaryContacts.currentLinks(linkSnapshot)
        val knownPhotos = profilePhotos.currentPhotos(photoSnapshot)
        saved.map { account ->
            account.copy(
                avatarUrl = BeneficiaryIdentity.avatarUrlFor(
                    kitUserId = account.kitUserId,
                    serverAvatarUrl = account.avatarUrl,
                    savedPhoneIdentity = links[account.id],
                    contacts = addressBook,
                    knownPhotos = knownPhotos,
                    phoneIdentityOf = beneficiaryContacts::identityForPhone,
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val operations = mobileMoney.operations
    val verification = mobileMoney.verification

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableQuote = MutableStateFlow<FinancialOperationQuote?>(null)
    val quote = mutableQuote.asStateFlow()

    /**
     * A cash-out this wallet cannot pay for, raised once and then cleared by the screen.
     *
     * Only cash-outs can come up short: a cash-in is money arriving. The quote is deliberately left
     * standing, so that once the wallet has been topped up the same figures are still on screen
     * waiting to be approved.
     */
    private val mutableTopUpRequired = MutableStateFlow<TopUpRequirement?>(null)
    val topUpRequired = mutableTopUpRequired.asStateFlow()

    fun clearTopUpRequired() {
        mutableTopUpRequired.value = null
    }

    init {
        refresh()
    }

    fun refresh() {
        runCommand { mobileMoney.refresh() }
    }

    fun addAccount(
        networkCode: String,
        phoneNumber: String,
        label: String,
        kind: String,
        onDone: () -> Unit,
    ) {
        runCommand(onDone) {
            mobileMoney.verifyAndSaveAccount(networkCode, phoneNumber, label, kind)
        }
    }

    fun preview(
        action: String,
        accountId: String,
        amountMinor: Long,
        feeMode: String,
    ) {
        runCommand {
            val quote = mobileMoney.previewOperation(action, accountId, amountMinor, feeMode)
            mutableQuote.value = quote
            // Raised on the quote rather than on the amount, because the fee mode decides how much
            // actually leaves the wallet, and nobody should be asked for a PIN to authorize a
            // payment the balance was never going to cover.
            mutableTopUpRequired.value = shortfallFor(quote)
        }
    }

    fun submit(paymentPin: String, onDone: () -> Unit) {
        val quote = mutableQuote.value ?: return
        runCommand {
            try {
                mobileMoney.submitOperation(quote, paymentPin)
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
                // Deliberately not calling `onDone`: the sheet stays open on the quote it just
                // failed to submit, ready to be approved again once the wallet is topped up.
                return@runCommand
            }
            mutableQuote.value = null
            onDone()
        }
    }

    /** How far the wallet falls short of a cash-out, or null when it covers it. */
    private fun shortfallFor(
        quote: FinancialOperationQuote,
        balanceMinor: Long = wallet.balanceMinor.value,
    ): TopUpRequirement? {
        if (quote.operationType != "payout") return null
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
                mutableError.value = error.message
                    ?.takeIf(String::isNotBlank)
                    ?: "The mobile money request could not be completed"
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
