package com.kit.wallet.feature.support

import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.remote.isKitInsufficientFundsError
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.support.SupportCategory
import com.kit.wallet.data.support.SupportDraft
import com.kit.wallet.data.support.SupportDraftOutcome
import com.kit.wallet.data.support.SupportMessage
import com.kit.wallet.data.support.SupportPaymentReceipt
import com.kit.wallet.data.support.SupportRepository
import com.kit.wallet.data.support.SupportTicket
import com.kit.wallet.data.support.isDefinitiveSupportRejection
import com.kit.wallet.ui.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val GENERIC_SUPPORT_ERROR = "Support isn't reachable right now. Try again."

private fun supportErrorMessage(error: Exception): String = when {
    error.isKitConnectivityError() ->
        "You're offline. Kit Pay will keep what you wrote and send it when you're back."
    error is KitWalletApiException -> error.message ?: GENERIC_SUPPORT_ERROR
    else -> error.message ?: GENERIC_SUPPORT_ERROR
}

// --- Support hub -------------------------------------------------------------

data class SupportHubUiState(
    val loading: Boolean = true,
    val tickets: List<SupportTicket> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** Non-null when the list could not be (re)loaded; cached rows stay visible. */
    val error: String? = null,
)

@HiltViewModel
class SupportHubViewModel @Inject constructor(
    private val support: SupportRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SupportHubUiState())
    val state: StateFlow<SupportHubUiState> = mutableState.asStateFlow()

    /** Open-ticket drafts still waiting to reach the server, shown above the list. */
    val queuedDrafts: StateFlow<List<SupportDraft>> = support.openTicketDrafts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    /** Flushes queued drafts first so a reconnect turns them into listed tickets. */
    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            runCatching { support.flushOutbox() }
            try {
                val page = support.tickets()
                mutableState.value = SupportHubUiState(
                    loading = false,
                    tickets = page.tickets,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = supportErrorMessage(error),
                )
            }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        val cursor = current.nextCursor
        if (!current.hasMore || cursor == null || current.loadingMore) return
        viewModelScope.launch {
            mutableState.value = current.copy(loadingMore = true)
            try {
                val page = support.tickets(cursor = cursor)
                mutableState.value = mutableState.value.copy(
                    tickets = mutableState.value.tickets + page.tickets,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                    loadingMore = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    loadingMore = false,
                    error = supportErrorMessage(error),
                )
            }
        }
    }

    fun discardDraft(clientMessageId: String) {
        viewModelScope.launch { support.discardDraft(clientMessageId) }
    }
}

// --- New ticket ---------------------------------------------------------------

sealed interface SupportCategoriesState {
    data object Loading : SupportCategoriesState
    data class Loaded(val categories: List<SupportCategory>) : SupportCategoriesState
    data class Failed(val message: String) : SupportCategoriesState
}

data class NewSupportTicketUiState(
    val submitting: Boolean = false,
    /** The server refused this exact content; shown inline, content stays editable. */
    val error: String? = null,
)

/** Where an interactive submit ended up; the screen navigates on it. */
sealed interface NewTicketResult {
    data class Opened(val ticket: SupportTicket) : NewTicketResult
    /** Durably queued; it will open when connectivity returns. */
    data object Queued : NewTicketResult
}

@HiltViewModel
class NewSupportTicketViewModel @Inject constructor(
    private val support: SupportRepository,
) : ViewModel() {
    private val mutableCategories =
        MutableStateFlow<SupportCategoriesState>(SupportCategoriesState.Loading)
    val categories: StateFlow<SupportCategoriesState> = mutableCategories.asStateFlow()

    private val mutableState = MutableStateFlow(NewSupportTicketUiState())
    val state: StateFlow<NewSupportTicketUiState> = mutableState.asStateFlow()

    init {
        loadCategories()
    }

    fun loadCategories() {
        viewModelScope.launch {
            mutableCategories.value = SupportCategoriesState.Loading
            try {
                mutableCategories.value = SupportCategoriesState.Loaded(support.categories())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableCategories.value =
                    SupportCategoriesState.Failed(supportErrorMessage(error))
            }
        }
    }

    /**
     * Queues the ticket durably, then tries to send it right away. Offline is
     * not a failure: the draft survives and [NewTicketResult.Queued] is
     * reported. Only a definitive server rejection surfaces as an error — the
     * content stays in the editor (and the rejected draft row is removed, so
     * the same words are not queued twice when the user edits and resubmits).
     */
    fun submit(
        categoryKey: String,
        subject: String,
        message: String,
        onResult: (NewTicketResult) -> Unit,
    ) {
        if (mutableState.value.submitting) return
        viewModelScope.launch {
            mutableState.value = NewSupportTicketUiState(submitting = true)
            try {
                val clientMessageId = support.enqueueOpenTicket(categoryKey, subject, message)
                when (val outcome = support.flushOutbox()[clientMessageId]) {
                    is SupportDraftOutcome.TicketOpened -> {
                        mutableState.value = NewSupportTicketUiState()
                        onResult(NewTicketResult.Opened(outcome.ticket))
                    }
                    is SupportDraftOutcome.Rejected -> {
                        support.discardDraft(clientMessageId)
                        mutableState.value = NewSupportTicketUiState(
                            error = "Support couldn't accept this ticket" +
                                (outcome.code?.let { " ($it)" } ?: "") +
                                ". Review it and try again.",
                        )
                    }
                    // Deferred, absent (flush stopped earlier), or an unexpected
                    // shape: the draft is safely queued either way.
                    else -> {
                        mutableState.value = NewSupportTicketUiState()
                        onResult(NewTicketResult.Queued)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                mutableState.value = NewSupportTicketUiState(
                    error = error.message ?: GENERIC_SUPPORT_ERROR,
                )
            } catch (error: Exception) {
                mutableState.value = NewSupportTicketUiState(
                    error = supportErrorMessage(error),
                )
            }
        }
    }
}

// --- Ticket thread -------------------------------------------------------------

data class SupportTicketUiState(
    val loading: Boolean = true,
    val ticket: SupportTicket? = null,
    /** Oldest-first, deduplicated by id, ordered by server position. */
    val messages: List<SupportMessage> = emptyList(),
    /** Load failed before anything rendered. */
    val error: String? = null,
    /** A close/escalate/send problem worth a transient notice. */
    val actionError: String? = null,
    val closing: Boolean = false,
    val escalating: Boolean = false,
)

/** One reviewed payment confirmation; its idempotency key lives exactly as long as it does. */
data class SupportPaymentReview(
    val amountMinor: Long,
    val amountDecimal: String,
    val note: String?,
    val sourceWalletId: String,
    val currencyCode: String,
    val currencyScale: Int,
    val idempotencyKey: String,
)

data class SupportPaymentUiState(
    val review: SupportPaymentReview? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val receipt: SupportPaymentReceipt? = null,
)

@HiltViewModel
class SupportTicketViewModel @Inject constructor(
    private val support: SupportRepository,
    private val wallet: WalletRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val ticketId: String = savedState.get<String>("ticketId").orEmpty()

    private val mutableState = MutableStateFlow(SupportTicketUiState())
    val state: StateFlow<SupportTicketUiState> = mutableState.asStateFlow()

    private val mutablePayment = MutableStateFlow(SupportPaymentUiState())
    val payment: StateFlow<SupportPaymentUiState> = mutablePayment.asStateFlow()

    /** Memory-only agent photo; never written to disk (server sends no-store). */
    private val mutableAgentPhoto = MutableStateFlow<ImageBitmap?>(null)
    val agentPhoto: StateFlow<ImageBitmap?> = mutableAgentPhoto.asStateFlow()
    private var photoForAlias: String? = null

    val drafts: StateFlow<List<SupportDraft>> = support.draftsForTicket(ticketId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val refreshMutex = Mutex()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                refreshMutex.withLock {
                    val detail = support.ticketDetail(ticketId)
                    var messages = detail.messages
                    // Detail pages forward from position 1; catch up to the newest
                    // in bounded steps so the poll below starts from the true end.
                    var more = detail.messagesHasMore
                    var after = detail.messagesNextAfterPosition
                    var guard = 0
                    while (more && after != null && guard < 30) {
                        val page = support.messagesAfter(ticketId, after)
                        if (page.messages.isEmpty()) break
                        messages = merge(messages, page.messages)
                        after = messages.last().position
                        more = page.messages.size >= 100
                        guard++
                    }
                    mutableState.value = SupportTicketUiState(
                        loading = false,
                        ticket = detail.ticket,
                        messages = messages,
                    )
                }
                flushDrafts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = if (mutableState.value.ticket == null) {
                        supportErrorMessage(error)
                    } else {
                        mutableState.value.error
                    },
                )
            }
            refreshAgentPhoto()
        }
    }

    /**
     * One poll tick: queued drafts first (so replies appear in order), then
     * anything new after the highest position seen. The refreshed ticket rides
     * on every page, which is how closure, agent assignment, and AI handoff
     * reach the screen. Failures are silent — the thread stays as it was.
     */
    fun poll() {
        viewModelScope.launch {
            if (mutableState.value.ticket == null) return@launch
            flushDrafts()
            try {
                refreshMutex.withLock {
                    val current = mutableState.value
                    val after = current.messages.lastOrNull()?.position ?: 0L
                    val page = support.messagesAfter(ticketId, after)
                    mutableState.value = current.copy(
                        ticket = page.ticket,
                        messages = merge(current.messages, page.messages),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            refreshAgentPhoto()
        }
    }

    /** Queues the reply durably and pushes the outbox; offline simply leaves it queued. */
    fun send(body: String, onQueued: () -> Unit) {
        viewModelScope.launch {
            try {
                support.enqueueMessage(ticketId, body)
                onQueued()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    actionError = error.message ?: GENERIC_SUPPORT_ERROR,
                )
                return@launch
            }
            flushDrafts()
            poll()
        }
    }

    fun retryDrafts() {
        viewModelScope.launch {
            flushDrafts()
            poll()
        }
    }

    fun discardDraft(clientMessageId: String) {
        viewModelScope.launch { support.discardDraft(clientMessageId) }
    }

    /** Closes now; any queued drafts were already resolved by the caller's explicit choice. */
    fun close(discardQueued: Boolean, onClosed: () -> Unit) {
        if (mutableState.value.closing) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(closing = true, actionError = null)
            try {
                if (discardQueued) support.discardDraftsForTicket(ticketId)
                val ticket = support.closeTicket(ticketId)
                mutableState.value = mutableState.value.copy(closing = false, ticket = ticket)
                onClosed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    closing = false,
                    actionError = supportErrorMessage(error),
                )
            }
        }
    }

    /** Asks for a human to take over from the AI assistant. */
    fun escalate() {
        if (mutableState.value.escalating) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(escalating = true, actionError = null)
            try {
                val ticket = support.escalateTicket(ticketId)
                mutableState.value = mutableState.value.copy(escalating = false, ticket = ticket)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    escalating = false,
                    actionError = supportErrorMessage(error),
                )
            }
        }
    }

    fun clearActionError() {
        mutableState.value = mutableState.value.copy(actionError = null)
    }

    // --- Payment ---------------------------------------------------------------

    /**
     * Parses and reviews a payment, minting the idempotency key that will
     * identify this confirmation for as long as it is on screen. Retries of the
     * same review reuse the key; a new review is a new key.
     */
    fun reviewPayment(amountText: String, noteText: String) {
        viewModelScope.launch {
            try {
                val source = wallet.spendingSource()
                val amountMinor = Money.parseMinor(amountText, source.currencyScale)
                if (amountMinor == null || amountMinor <= 0L) {
                    mutablePayment.value = SupportPaymentUiState(error = "Enter a valid amount")
                    return@launch
                }
                val note = noteText.trim().takeIf { it.isNotEmpty() }
                if ((note?.length ?: 0) > 280) {
                    mutablePayment.value =
                        SupportPaymentUiState(error = "The note must be at most 280 characters")
                    return@launch
                }
                mutablePayment.value = SupportPaymentUiState(
                    review = SupportPaymentReview(
                        amountMinor = amountMinor,
                        amountDecimal = DecimalMoney.fromMinor(amountMinor, source.currencyScale),
                        note = note,
                        sourceWalletId = source.walletId,
                        currencyCode = source.currencyCode,
                        currencyScale = source.currencyScale,
                        idempotencyKey = support.mintPaymentIdempotencyKey(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutablePayment.value = SupportPaymentUiState(
                    error = error.message ?: "Your wallet isn't ready for payments right now",
                )
            }
        }
    }

    fun confirmPayment(paymentPin: String) {
        val review = mutablePayment.value.review ?: return
        if (mutablePayment.value.busy) return
        viewModelScope.launch {
            mutablePayment.value = mutablePayment.value.copy(busy = true, error = null)
            try {
                val receipt = support.payTicket(
                    ticketId = ticketId,
                    sourceWalletId = review.sourceWalletId,
                    amount = review.amountDecimal,
                    note = review.note,
                    paymentPin = paymentPin,
                    idempotencyKey = review.idempotencyKey,
                )
                mutablePayment.value = SupportPaymentUiState(receipt = receipt)
                poll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when {
                    error.isKitInsufficientFundsError() ->
                        "Your wallet doesn't have enough for this payment"
                    else -> error.message ?: "This payment could not be completed"
                }
                val definitive =
                    error is KitWalletApiException && isDefinitiveSupportRejection(error)
                mutablePayment.value = if (definitive) {
                    // The server refused this exact intent; the confirmation and
                    // its key are dead. A fresh attempt starts a fresh review.
                    SupportPaymentUiState(error = message)
                } else {
                    // Possibly-committed or transient: keep the review so a retry
                    // replays the same idempotency key.
                    mutablePayment.value.copy(busy = false, error = message)
                }
            }
        }
    }

    fun dismissPayment() {
        mutablePayment.value = SupportPaymentUiState()
    }

    // --- Internals ---------------------------------------------------------------

    private suspend fun flushDrafts() {
        if (drafts.value.none { !it.failed }) return
        runCatching { support.flushOutbox() }
    }

    private fun merge(
        existing: List<SupportMessage>,
        incoming: List<SupportMessage>,
    ): List<SupportMessage> {
        if (incoming.isEmpty()) return existing
        val seen = existing.asSequence().map { it.id }.toHashSet()
        val merged = existing + incoming.filter { seen.add(it.id) }
        return merged.sortedBy { it.position }
    }

    private fun refreshAgentPhoto() {
        val ticket = mutableState.value.ticket ?: return
        val alias = ticket.agentAlias
        if (!ticket.agentHasAvatar || alias == null) {
            photoForAlias = null
            mutableAgentPhoto.value = null
            return
        }
        if (alias == photoForAlias) return
        photoForAlias = alias
        viewModelScope.launch {
            val bytes = support.agentAvatar(ticketId) ?: return@launch
            val decoded = withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            // Only publish if the hint still names the same agent.
            if (photoForAlias == alias) mutableAgentPhoto.value = decoded
        }
    }
}
