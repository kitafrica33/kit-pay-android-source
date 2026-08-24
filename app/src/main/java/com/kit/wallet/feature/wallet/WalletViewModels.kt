package com.kit.wallet.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.SentTransfer
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Transaction
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SendMoneyViewModel @Inject constructor(
    private val wallet: WalletRepository,
    private val chats: ChatRepository,
    contactRepo: ContactRepository,
) : ViewModel() {

    val contacts = contactRepo.contacts
    val balanceMinor = wallet.balanceMinor

    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _lastSent = MutableStateFlow<Transaction?>(null)
    val lastSent = _lastSent.asStateFlow()

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
                val sent = wallet.sendToContact(recipient, amountMinor, note, paymentPin)
                _lastSent.value = sent.transaction
                announceInChat(recipient, sent)
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message ?: "The transfer could not be completed"
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Puts the payment into the conversation with the person who received it, so money sent from
     * the wallet still reads as part of the chat rather than vanishing into a separate history.
     *
     * Deliberately best-effort: the debit has already happened and the receipt is already in the
     * wallet. A messaging failure here must never surface as a failed payment — the recipient
     * still sees the transfer in their wallet, and a held transfer's card is rebuilt from the
     * wallet API whenever the conversation is opened.
     */
    private suspend fun announceInChat(recipient: Contact, sent: SentTransfer) {
        if (!recipient.isKitUser) return
        val claim = sent.claim
        val descriptor = KitPaymentMessage(
            action = if (claim == null) KitPaymentAction.SENT else KitPaymentAction.TRANSFER,
            referenceId = claim?.id ?: sent.transaction.id,
            amountMinor = abs(sent.transaction.amountMinor),
            currencyCode = sent.transaction.currencyCode,
            currencyScale = sent.transaction.currencyScale,
            note = sent.transaction.note
                ?.takeIf(String::isNotBlank)
                ?.take(KitPaymentMessage.MAX_NOTE_LENGTH),
        ).encode()
        // Post nothing rather than something neither side can read back: a descriptor that fails
        // its own canonical round trip would render as raw text in every client that receives it.
        if (KitPaymentMessage.parse(descriptor) == null) return
        try {
            chats.sendPaymentEvent(chats.openDirectConversation(recipient), descriptor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Swallowed on purpose; see the note above.
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
}

sealed interface TransactionDetailUiState {
    data object Loading : TransactionDetailUiState
    data object NotFound : TransactionDetailUiState
    data class Ready(val transaction: Transaction) : TransactionDetailUiState
}

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    wallet: WalletRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val txId: String = savedStateHandle.get<String>("txId").orEmpty()

    val uiState = wallet.transactions
        .map { transactions ->
            transactions.firstOrNull { it.id == txId }
                ?.let { TransactionDetailUiState.Ready(it) }
                ?: TransactionDetailUiState.NotFound
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TransactionDetailUiState.Loading,
        )
}

@HiltViewModel
class ReceiveViewModel @Inject constructor(userRepo: UserRepository) : ViewModel() {
    val profile = userRepo.profile
}
