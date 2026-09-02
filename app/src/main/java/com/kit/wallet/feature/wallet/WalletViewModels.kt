package com.kit.wallet.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.WalletTransferChatReceiptCoordinator
import com.kit.wallet.data.remote.isKitInsufficientFundsError
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.SentTransfer
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.TopUp
import com.kit.wallet.ui.model.TopUpRequirement
import com.kit.wallet.ui.model.Transaction
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SendMoneyViewModel @Inject internal constructor(
    private val wallet: WalletRepository,
    private val walletSync: WalletSyncRepository,
    private val chats: ChatRepository,
    private val contactRepo: ContactRepository,
    private val transferChatReceipts: WalletTransferChatReceiptCoordinator? = null,
) : ViewModel() {

    val contacts = contactRepo.contacts
    val balanceMinor = wallet.balanceMinor
    val currency = wallet.walletCurrency

    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _lastSent = MutableStateFlow<Transaction?>(null)
    val lastSent = _lastSent.asStateFlow()

    /**
     * A shortfall the server itself reported, raised once and then cleared by the screen.
     *
     * The amount is checked before the payment is attempted too — see [shortfallFor] — but a
     * balance can move between the check and the charge, and the server is the one that decides.
     */
    private val _topUpRequired = MutableStateFlow<TopUpRequirement?>(null)
    val topUpRequired = _topUpRequired.asStateFlow()

    init {
        // The app-wide contact graph is warm-cached for offline use, but Send money is an
        // authoritative destination picker. Re-read it whenever this flow is opened so a newly
        // registered or relinked Kit account is not hidden behind an older session snapshot.
        // Failure deliberately leaves the cached list usable; the payment endpoint still owns
        // final recipient validation.
        viewModelScope.launch {
            try {
                contactRepo.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Cached recipients remain useful while offline.
            }
        }
    }

    /**
     * How far this wallet falls short of a transfer, or null when it covers it.
     *
     * A Kit Pay transfer carries no fee, so the amount being sent is the whole debit — unlike a
     * mobile money or bank payment, where the quote's customer debit is the figure that matters.
     */
    fun shortfallFor(
        amountMinor: Long,
        balanceMinor: Long = wallet.balanceMinor.value,
        currencyCode: String = wallet.walletCurrency.value.code,
        currencyScale: Int = wallet.walletCurrency.value.scale,
    ): TopUpRequirement? {
        return TopUp.requirementFor(
            requiredMinor = amountMinor,
            balanceMinor = balanceMinor,
            currencyCode = currencyCode,
            currencyScale = currencyScale,
        )
    }

    fun clearTopUpRequired() {
        _topUpRequired.value = null
    }

    fun send(
        recipient: Contact,
        amountMinor: Long,
        note: String?,
        paymentPin: String,
        onSent: () -> Unit,
    ) {
        if (_sending.value) return
        viewModelScope.launch {
            _sending.value = true
            _error.value = null
            try {
                val sent = if (transferChatReceipts == null) {
                    wallet.sendToContact(recipient, amountMinor, note, paymentPin).also {
                        announceInChat(recipient, it)
                    }
                } else {
                    transferChatReceipts.send(
                        recipient = recipient,
                        amountMinor = amountMinor,
                        note = note,
                        paymentPin = paymentPin,
                    )
                }
                _lastSent.value = sent.transaction
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val shortfall = if (error.isKitInsufficientFundsError()) {
                    // The server, not the cached balance, just established that this wallet is
                    // short. Re-read it before calculating what to offer; otherwise a stale-high
                    // cache turns a recoverable refusal into a dead-end error.
                    try {
                        val refreshed = walletSync.refresh()
                        shortfallFor(
                            amountMinor = amountMinor,
                            balanceMinor = refreshed.selectedAvailableBalanceMinor
                                ?: wallet.balanceMinor.value,
                            currencyCode = refreshed.selectedCurrencyCode
                                ?: wallet.walletCurrency.value.code,
                            currencyScale = refreshed.selectedCurrencyScale
                                ?: wallet.walletCurrency.value.scale,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (refreshFailure: Exception) {
                        _error.value = refreshFailure.message
                            ?.takeIf(String::isNotBlank)
                            ?: "Your wallet balance could not be refreshed"
                        null
                    }
                } else {
                    null
                }
                if (shortfall != null) {
                    // The top-up says what is wrong and offers the way out of it, so repeating it
                    // as a red line under the PIN field would only be the same news twice.
                    _topUpRequired.value = shortfall
                } else if (_error.value == null) {
                    _error.value = error.message ?: "The transfer could not be completed"
                }
            } finally {
                _sending.value = false
            }
        }
    }

    /** Compatibility fallback for isolated tests and repository-only embeddings without recovery. */
    private suspend fun announceInChat(recipient: Contact, sent: SentTransfer) {
        if (!recipient.isKitUser) return
        val claim = sent.claim
        val event = KitPaymentMessage(
            action = if (claim == null) KitPaymentAction.SENT else KitPaymentAction.TRANSFER,
            referenceId = claim?.id ?: sent.transaction.id,
            amountMinor = abs(sent.transaction.amountMinor),
            currencyCode = sent.transaction.currencyCode,
            currencyScale = sent.transaction.currencyScale,
            note = sent.transaction.note
                ?.takeIf(String::isNotBlank)
                ?.take(KitPaymentMessage.MAX_NOTE_LENGTH),
        )
        val descriptor = event.encode()
        if (KitPaymentMessage.parse(descriptor) == null) return
        try {
            chats.sendPaymentEvent(chats.openDirectConversation(recipient), descriptor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The production graph uses the durable coordinator. This fallback remains best effort.
        }
    }
}

@HiltViewModel
class RequestMoneyViewModel @Inject constructor(
    private val wallet: WalletRepository,
    contactRepo: ContactRepository,
) : ViewModel() {

    val contacts = contactRepo.contacts

    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun request(from: Contact, amountMinor: Long, note: String?, onDone: () -> Unit) {
        if (_sending.value) return
        if (!from.canReceiveKitPaymentRequest()) {
            _error.value = "Choose a valid Kit Pay contact"
            return
        }
        if (amountMinor <= 0) {
            _error.value = "Enter an amount greater than zero"
            return
        }
        viewModelScope.launch {
            _sending.value = true
            _error.value = null
            runCatching { wallet.request(from, amountMinor, note) }
                .onSuccess { onDone() }
                .onFailure { _error.value = it.message ?: "The request could not be sent" }
            _sending.value = false
        }
    }
}

@HiltViewModel
class TransactionsViewModel @Inject constructor(wallet: WalletRepository) : ViewModel() {
    val transactions = wallet.transactions
    val currency = wallet.walletCurrency
}

sealed interface TransactionDetailUiState {
    data object Loading : TransactionDetailUiState
    data object NotFound : TransactionDetailUiState
    data class Ready(val transaction: Transaction) : TransactionDetailUiState
}

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    wallet: WalletRepository,
    contacts: ContactRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val txId: String = savedStateHandle.get<String>("txId").orEmpty()

    val uiState = combine(
        wallet.transactions,
        contacts.contacts,
        wallet.walletCurrency,
    ) { transactions, directory, currency ->
            transactions.firstOrNull {
                it.id == txId && it.hasVerifiedCustomerPresentation(currency)
            }
                ?.withCounterpartyVerification(directory)
                ?.let { TransactionDetailUiState.Ready(it) }
                ?: TransactionDetailUiState.NotFound
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TransactionDetailUiState.Loading,
        )
}

/** Resolves counterparty presentation only by the backend's public user ID, never by its name. */
internal fun Transaction.withCounterpartyVerification(contacts: List<Contact>): Transaction {
    val userId = counterpartyUserId?.trim()?.takeIf(String::isNotEmpty) ?: return this
    val matched = contacts.firstOrNull { contact ->
        contact.isKitUser && contact.id.equals(userId, ignoreCase = true)
    } ?: return this
    val avatarUrl = matched.avatarUrl?.trim()?.takeIf(String::isNotEmpty)
        ?: counterpartyAvatarUrl
    val verification = matched.accountVerification ?: accountVerification
    return if (
        counterpartyAvatarUrl == avatarUrl &&
        accountVerification == verification
    ) {
        this
    } else {
        copy(
            counterpartyAvatarUrl = avatarUrl,
            accountVerification = verification,
        )
    }
}

@HiltViewModel
class ReceiveViewModel @Inject constructor(userRepo: UserRepository) : ViewModel() {
    val profile = userRepo.profile
}
