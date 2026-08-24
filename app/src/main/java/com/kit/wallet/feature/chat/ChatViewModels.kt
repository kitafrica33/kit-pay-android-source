package com.kit.wallet.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.messaging.KitChatMediaLimits
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.MessagingRichMediaCapability
import com.kit.wallet.data.remote.KIT_NETWORK_UNAVAILABLE_MESSAGE
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatPaymentRequest
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.canonicalTransferClaimReason
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-wide receive gate: navigation must not overlap bounded download/decrypt buffer sets. */
private val secureMediaOpenMutex = Mutex()

internal data class ConversationSoundDecision(
    val playReceived: Boolean = false,
    val playPaymentReceived: Boolean = false,
    val playSent: Boolean = false,
)

/**
 * Tracks the visible-conversation sound baseline across messaging epoch recovery. Projections
 * published while readiness is closed establish a silent baseline; only later deltas may sound.
 */
internal class ConversationSoundBaseline {
    private var knownIncomingIds: Set<String>? = null
    private var previousOutgoingStates: Map<String, DeliveryState>? = null

    fun observe(
        messagingReady: Boolean,
        projected: List<Message>,
    ): ConversationSoundDecision {
        val incoming = projected.filter { !it.fromMe }
        val incomingIds = incoming.mapTo(mutableSetOf()) { it.id }
        val outgoingStates = projected.asSequence()
            .filter { it.fromMe }
            .associate { it.id to it.state }
        val previousIncoming = knownIncomingIds
        val previousOutgoing = previousOutgoingStates

        // Readiness loss clears repository projections before the replacement epoch publishes its
        // baseline. Preserve the last non-empty identity set across that gap; StateFlow may
        // conflate the intermediate restored-while-not-ready emission with the ready transition.
        if (!messagingReady && projected.isEmpty()) return ConversationSoundDecision()

        val arrived = if (messagingReady && previousIncoming != null) {
            incoming.filter { it.id !in previousIncoming }
        } else {
            emptyList()
        }
        val reachedFirstTick = messagingReady && previousOutgoing != null &&
            outgoingStates.any { (id, state) ->
                state == DeliveryState.SENT && previousOutgoing[id] == DeliveryState.SENDING
            }

        knownIncomingIds = incomingIds
        previousOutgoingStates = outgoingStates
        val paymentArrived = arrived.any { it.kind == MessageKind.PAYMENT }
        return ConversationSoundDecision(
            playReceived = arrived.isNotEmpty() && !paymentArrived,
            playPaymentReceived = paymentArrived,
            playSent = reachedFirstTick,
        )
    }
}

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    contactRepo: ContactRepository,
) : ViewModel() {
    val messagingAvailable = chatRepo.readiness
    val chats = chatRepo.chats

    /** Kit contacts for the global-search Contacts section, like the iOS search sheet. */
    val searchableContacts = contactRepo.contacts
        .map { contacts -> contacts.filter { it.isKitUser && it.id.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local-only search across decrypted text projections. */
    fun searchMessages(query: String) = chatRepo.searchMessages(query)

    /** Opens (or creates) the direct conversation for a searched contact. */
    fun openDirectConversation(contact: Contact, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { chatRepo.openDirectConversation(contact) }
                .onSuccess(onOpened)
        }
    }

    /** Total unread messages across every conversation, for the navigation badge. */
    val totalUnread: StateFlow<Int> = chatRepo.chats
        .map { previews -> previews.sumOf { preview -> preview.unread } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            chatRepo.chats.value.sumOf { preview -> preview.unread },
        )

    /** Viewer-local pin; applies to every selected conversation. */
    fun setPinned(chatIds: Collection<String>, pinned: Boolean) {
        viewModelScope.launch {
            chatIds.forEach { chatRepo.setChatPinned(it, pinned) }
        }
    }

    fun setMuted(chatIds: Collection<String>, muted: Boolean) {
        viewModelScope.launch {
            chatIds.forEach { chatRepo.setChatMuted(it, muted) }
        }
    }

    /** Publishes read receipts for every selected conversation with unread messages. */
    fun markRead(chatIds: Collection<String>) {
        viewModelScope.launch {
            chatIds.forEach { chatId ->
                runCatching { chatRepo.markConversationRead(chatId) }
            }
        }
    }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val walletRepo: WalletRepository,
    private val callRepo: CallRepository,
    private val messageSounds: MessageSoundPlayer,
    savedStateHandle: SavedStateHandle,
    internal val richMediaCapability: MessagingRichMediaCapability? = null,
) : ViewModel() {

    /** Current authenticated public ID; used only to bind server claims to this direct chat. */
    val currentAccountId: String?
        get() = walletRepo.currentAccountId

    private val chatId: String = savedStateHandle.get<String>("chatId")
        ?.trim()
        .orEmpty()

    private val callTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    val messagingAvailable = chatRepo.readiness
    val chat: StateFlow<ChatPreview?> = chatRepo.chats
        .map { chats -> chats.singleOrNull { it.id == chatId } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            chatId.takeIf(String::isNotBlank)?.let(chatRepo::chat),
        )

    /** Raw encrypted messages for this conversation, before call-log entries are interleaved. */
    private val conversationMessages: StateFlow<List<Message>> = chatId.takeIf(String::isNotBlank)
        ?.let(chatRepo::conversation)
        ?: MutableStateFlow<List<Message>>(emptyList()).asStateFlow()

    /** Messages plus this conversation's call-log entries, ordered together like a WhatsApp thread. */
    val messages: StateFlow<List<Message>> = if (chatId.isBlank()) {
        MutableStateFlow<List<Message>>(emptyList()).asStateFlow()
    } else {
        combine(conversationMessages, callRepo.calls, chat) { msgs, calls, currentChat ->
            mergeCallLog(msgs, calls, currentChat)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, conversationMessages.value)
    }

    private fun mergeCallLog(
        messages: List<Message>,
        calls: List<CallEntry>,
        currentChat: ChatPreview?,
    ): List<Message> {
        val peerUserId = currentChat?.peerUserId
        val relevant = calls.filter { call ->
            call.conversationId == chatId ||
                (peerUserId != null && call.participantUserIds.contains(peerUserId))
        }
        if (relevant.isEmpty()) return messages
        val callMessages = relevant.map { call ->
            Message(
                id = "call:${call.id}",
                text = "",
                time = if (call.startedAtEpochMillis > 0) {
                    callTimeFormatter.format(Instant.ofEpochMilli(call.startedAtEpochMillis))
                } else {
                    call.time
                },
                fromMe = call.direction == CallDirection.OUTGOING,
                kind = MessageKind.CALL,
                sortEpochMillis = call.startedAtEpochMillis,
                callDirection = call.direction,
                callVideo = call.video,
                callDurationSeconds = call.durationSeconds,
            )
        }
        // sortedBy is stable, so messages keep their authenticated relative order and calls slot in
        // by start time.
        return (messages + callMessages).sortedBy { it.sortEpochMillis }
    }

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()

    private val mutableRetryingMessageId = MutableStateFlow<String?>(null)
    val retryingMessageId = mutableRetryingMessageId.asStateFlow()

    private val mutableMediaBytes = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val mediaBytes = mutableMediaBytes.asStateFlow()
    private val mediaCache = LinkedHashMap<String, ByteArray>()
    private var mediaCacheByteCount = 0

    private val mutableMediaLoading = MutableStateFlow<Set<String>>(emptySet())
    val mediaLoading = mutableMediaLoading.asStateFlow()

    private val mutableMediaErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val mediaErrors = mutableMediaErrors.asStateFlow()

    private val mutableConversationVisible = MutableStateFlow(false)
    private var foregroundSyncJob: Job? = null

    /**
     * Live state of the held Kit → Kit transfers this account is a party to, keyed by claim id.
     *
     * The chat message records that a transfer happened; this records what has become of it. A
     * card built from here is never stale because a follow-up message was lost.
     */
    private val mutableTransferClaims = MutableStateFlow<Map<String, TransferClaim>>(emptyMap())
    val transferClaims = mutableTransferClaims.asStateFlow()

    /** Expiries this session has already written into the conversation, to avoid a second line. */
    private val announcedExpiries = mutableSetOf<String>()

    // Encrypted composer draft restored once per conversation entry; consumed by the screen.
    private val mutableRestoredDraft = MutableStateFlow<String?>(null)
    val restoredDraft = mutableRestoredDraft.asStateFlow()

    init {
        if (chatId.isNotBlank()) {
            viewModelScope.launch {
                mutableRestoredDraft.value =
                    runCatching { chatRepo.composerDraft(chatId) }.getOrNull()
            }
            viewModelScope.launch {
                combine(
                    mutableConversationVisible,
                    messagingAvailable,
                    conversationMessages,
                ) { visible, ready, projected ->
                    visible && ready && projected.any {
                        !it.fromMe && it.state == DeliveryState.DELIVERED
                    }
                }.collectLatest { shouldMarkRead ->
                    if (shouldMarkRead) attemptMarkConversationRead()
                }
            }
            // In-conversation sounds while the chat is open:
            //  - a new incoming message plays the coin tone for a completed payment, else the
            //    knock tone;
            //  - an outgoing message plays the water-drop tone when it reaches its first tick.
            viewModelScope.launch {
                val soundBaseline = ConversationSoundBaseline()
                combine(messagingAvailable, conversationMessages) { ready, projected ->
                    ready to projected
                }.collect { (ready, projected) ->
                    val visible = mutableConversationVisible.value
                    val decision = soundBaseline.observe(ready, projected)
                    if (visible) {
                        when {
                            decision.playPaymentReceived -> messageSounds.playPaymentReceived()
                            decision.playReceived -> messageSounds.playReceived()
                        }
                        if (decision.playSent) messageSounds.playSent()
                    }
                }
            }
        }
    }

    /** Starts one cancellable, sequential sync loop only while this conversation is visible. */
    fun setConversationVisible(visible: Boolean) {
        mutableConversationVisible.value = visible
        if (!visible || chatId.isBlank()) {
            foregroundSyncJob?.cancel()
            foregroundSyncJob = null
            return
        }
        // Refresh the call log so recent calls appear inline in the conversation.
        viewModelScope.launch { runCatching { callRepo.refresh() } }
        viewModelScope.launch { refreshTransferClaims() }
        if (foregroundSyncJob?.isActive == true) return
        foregroundSyncJob = viewModelScope.launch {
            var firstIteration = true
            while (true) {
                if (messagingAvailable.value) {
                    try {
                        chatRepo.synchronizeConversation(chatId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // FCM and WorkManager remain recovery paths. A visible conversation makes
                        // another bounded foreground attempt on the next cadence.
                    }
                }
                // Projection changes attempt immediately through the collector above. If that
                // POST fails without changing local state, retry it on the next foreground tick.
                if (!firstIteration) attemptMarkConversationRead()
                // Held transfers settle from the other side, and expire with nobody acting at
                // all. There is no push for either, so an open conversation re-reads them on
                // the same cadence it re-reads messages.
                if (!firstIteration) refreshTransferClaims()
                firstIteration = false
                delay(FOREGROUND_SYNC_INTERVAL_MILLIS)
            }
        }
    }

    fun clearError() {
        mutableError.value = null
    }

    fun reportMediaSelectionError(message: String) {
        mutableError.value = message
    }

    /** The screen seeded its composer from the restored draft (or chose not to). */
    fun consumeRestoredDraft() {
        mutableRestoredDraft.value = null
    }

    /** Best-effort encrypted draft persistence; blank text clears the stored draft. */
    fun persistDraft(text: String) {
        if (chatId.isBlank()) return
        viewModelScope.launch { chatRepo.saveComposerDraft(chatId, text) }
    }

    private suspend fun attemptMarkConversationRead() {
        if (
            !mutableConversationVisible.value ||
            !messagingAvailable.value ||
            conversationMessages.value.none { !it.fromMe && it.state == DeliveryState.DELIVERED }
        ) return
        try {
            chatRepo.markConversationRead(chatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Durable unread state is the retry signal. A projection/readiness emission or the
            // two-second visible-conversation cadence will try the receipt again.
        }
    }

    fun send(text: String, onSent: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val normalized = text.trim()
        if (!messagingAvailable.value || normalized.isBlank()) return
        if (!KitPaymentMessage.allowsUserAuthoredText(normalized)) {
            mutableError.value = "Messages cannot start with Kit Pay's reserved payment prefix"
            return
        }
        viewModelScope.launch {
            val composerReleased = AtomicBoolean(false)
            fun releaseComposer() {
                if (composerReleased.compareAndSet(false, true)) {
                    onSent()
                    // The message is durably owned by the outbox; its draft copy is obsolete.
                    viewModelScope.launch { chatRepo.clearComposerDraft(selectedChat.id) }
                }
            }
            val failure = try {
                chatRepo.sendMessage(selectedChat.id, normalized) {
                    releaseComposer()
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            val durablyHandled = failure == null || composerReleased.get()

            // A successful return is a final fallback for implementations whose network round-trip
            // completed before they notified the durable boundary.
            if (durablyHandled) releaseComposer()

            failure?.let { error ->
                val connectivity = error.isKitConnectivityError()
                // Stay silent like WhatsApp only when this exact operation committed an encrypted
                // retry. Otherwise keep the composer and show address-free connectivity copy.
                if (!connectivity || !durablyHandled) {
                    mutableError.value = if (connectivity) {
                        KIT_NETWORK_UNAVAILABLE_MESSAGE
                    } else {
                        error.message ?: "Secure messaging is temporarily unavailable"
                    }
                }
            }
        }
    }

    fun retry(message: Message, onRetried: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        if (
            !message.fromMe ||
            message.state !in setOf(DeliveryState.SENDING, DeliveryState.RETRY_REQUIRED)
        ) return
        // A media message retries its authenticated descriptor, not its display caption.
        val normalized = (message.mediaDescriptor ?: message.text).trim()
        if (!messagingAvailable.value || normalized.isBlank() || mutableSending.value) return
        launchSend(
            selectedChatId = selectedChat.id,
            normalizedText = normalized,
            retryingMessageId = message.id,
            trustedPaymentEvent = KitPaymentMessage.parse(normalized) != null,
            onSuccess = onRetried,
        )
    }

    /** A server-confirmed request whose encrypted card has not been durably shared yet. */
    private data class UnsharedPaymentRequest(
        val chatId: String,
        val peerUserId: String,
        val amountMinor: Long,
        val note: String?,
        val request: ChatPaymentRequest,
    )

    private var unsharedPaymentRequest: UnsharedPaymentRequest? = null

    /**
     * Creates an idempotent, non-debit backend payment request addressed to the chat peer, then
     * shares it into the conversation as an end-to-end encrypted payment-request descriptor.
     */
    fun sendPaymentRequest(amountMinor: Long, note: String?, onSent: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val peerUserId = selectedChat.peerUserId
        if (!messagingAvailable.value || mutableSending.value) return
        if (amountMinor <= 0) {
            mutableError.value = "Enter an amount to request"
            return
        }
        if (peerUserId == null) {
            mutableError.value = "This conversation is not linked to a Kit Pay account"
            return
        }
        val normalizedNote = note?.trim()?.takeIf(String::isNotBlank)
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            var durablyShared = false
            try {
                // A request the server already confirmed for these exact details is reused, so a
                // create-success/share-failure retry never mints a second financial request. The
                // request UUID stays the stable identity of the eventual card, matching iOS.
                val retained = unsharedPaymentRequest?.takeIf {
                    it.chatId == selectedChat.id && it.peerUserId == peerUserId &&
                        it.amountMinor == amountMinor && it.note == normalizedNote
                }?.request
                val created = retained
                    ?: walletRepo.createChatPaymentRequest(peerUserId, amountMinor, normalizedNote)
                        .also { confirmed ->
                            unsharedPaymentRequest = UnsharedPaymentRequest(
                                chatId = selectedChat.id,
                                peerUserId = peerUserId,
                                amountMinor = amountMinor,
                                note = normalizedNote,
                                request = confirmed,
                            )
                        }
                val descriptor = KitPaymentMessage(
                    action = KitPaymentAction.REQUEST,
                    referenceId = created.id,
                    amountMinor = created.amountMinor,
                    currencyCode = created.currencyCode,
                    currencyScale = created.currencyScale,
                    note = created.note?.takeIf(String::isNotBlank),
                ).encode()
                chatRepo.sendPaymentEvent(selectedChat.id, descriptor) { durablyShared = true }
                unsharedPaymentRequest = null
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (durablyShared) {
                    // The encrypted card is committed to the outbox, appears in the conversation
                    // as a pending bubble, and owns replay from here. Close the composer like a
                    // WhatsApp offline send; surfacing an error here would invite a duplicate.
                    unsharedPaymentRequest = null
                    onSent()
                } else {
                    mutableError.value = error.message
                        ?: "The payment request could not be sent"
                }
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Pays a request received in this conversation, then confirms it in-chat once debited. */
    fun payPaymentRequest(message: Message, paymentPin: String, onPaid: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse) ?: return
        if (
            !messagingAvailable.value || mutableSending.value ||
            message.fromMe || !descriptor.isRequest
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                walletRepo.payChatPaymentRequest(
                    requestId = descriptor.referenceId,
                    amountMinor = descriptor.amountMinor,
                    paymentPin = paymentPin,
                )
                // The paid confirmation is best-effort: the debit already completed above.
                runCatching {
                    chatRepo.sendPaymentEvent(
                        selectedChat.id,
                        descriptor.copy(action = KitPaymentAction.PAID).encode(),
                    )
                }
                onPaid()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "The payment could not be completed"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /**
     * Turns down a payment request received in this conversation.
     *
     * Nothing is called on the server, because a request holds no money and the backend has no
     * payee-side decline — it only lets the requester withdraw. What a decline changes is the
     * conversation, so that is exactly and only what this writes.
     */
    fun declinePaymentRequest(message: Message, onDone: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse) ?: return
        if (
            !messagingAvailable.value || mutableSending.value ||
            message.fromMe || !descriptor.isRequest
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                chatRepo.sendPaymentEvent(
                    selectedChat.id,
                    descriptor.copy(action = KitPaymentAction.DECLINED).encode(),
                )
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The request could not be declined"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Withdraws a payment request this account sent, and records the withdrawal in-chat. */
    fun cancelPaymentRequest(message: Message, onDone: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse) ?: return
        if (
            !messagingAvailable.value || mutableSending.value ||
            !message.fromMe || !descriptor.isRequest
        ) return
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                walletRepo.cancelChatPaymentRequest(descriptor.referenceId)
                // The request is already withdrawn server-side; saying so in chat is best-effort.
                runCatching {
                    chatRepo.sendPaymentEvent(
                        selectedChat.id,
                        descriptor.copy(action = KitPaymentAction.CANCELLED).encode(),
                    )
                }
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "The request could not be cancelled"
            } finally {
                mutableSending.value = false
            }
        }
    }

    /** Takes a held transfer. From here the payment is final and cannot be reversed. */
    fun acceptTransfer(
        message: Message,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit = {},
    ) = settleTransfer(
        message = message,
        action = TransferClaimResolutionAction.ACCEPT,
        outcome = KitPaymentAction.ACCEPTED,
        expectedStatus = TransferClaimStatus.ACCEPTED,
        reason = null,
        claimableTransfersEnabled = claimableTransfersEnabled,
        onDone = onDone,
    ) { claimId ->
        walletRepo.acceptTransferClaim(claimId)
    }

    /** Sends a held transfer back, recording the recipient's reason in the conversation. */
    fun rejectTransfer(
        message: Message,
        reason: String?,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val canonicalReason = canonicalTransferClaimReason(reason)
        settleTransfer(
            message = message,
            action = TransferClaimResolutionAction.REJECT,
            outcome = KitPaymentAction.REJECTED,
            expectedStatus = TransferClaimStatus.REJECTED,
            reason = canonicalReason,
            claimableTransfersEnabled = claimableTransfersEnabled,
            onDone = onDone,
        ) { claimId ->
            walletRepo.rejectTransferClaim(claimId, canonicalReason)
        }
    }

    /** Takes back a transfer only after a claim-bound biometric-or-PIN approval. */
    fun reverseTransfer(
        message: Message,
        reason: String?,
        paymentPin: String,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit = {},
    ) {
        val canonicalReason = canonicalTransferClaimReason(reason)
        settleTransfer(
            message = message,
            action = TransferClaimResolutionAction.REVERSE,
            outcome = KitPaymentAction.REVERSED,
            expectedStatus = TransferClaimStatus.REVERSED,
            reason = canonicalReason,
            claimableTransfersEnabled = claimableTransfersEnabled,
            onDone = onDone,
        ) { claimId ->
            walletRepo.reverseTransferClaim(claimId, canonicalReason, paymentPin)
        }
    }

    private fun settleTransfer(
        message: Message,
        action: TransferClaimResolutionAction,
        outcome: KitPaymentAction,
        expectedStatus: TransferClaimStatus,
        reason: String?,
        claimableTransfersEnabled: Boolean,
        onDone: () -> Unit,
        settle: suspend (String) -> TransferClaim,
    ) {
        val selectedChat = chat.value ?: return
        val claimId = message.paymentReferenceId?.takeIf(String::isNotBlank) ?: return
        val binding = TransferClaimPartyBinding.create(
            currentUserId = walletRepo.currentAccountId,
            peerUserId = selectedChat.peerUserId,
        )
        if (!claimableTransfersEnabled || binding == null) {
            mutableError.value = "Transfer decisions are not available right now"
            return
        }
        if (mutableSending.value) return
        // Claim the in-flight marker before the first suspension so two fast taps cannot race.
        mutableSending.value = true
        mutableError.value = null
        viewModelScope.launch {
            var settlementAttempted = false
            try {
                check(walletRepo.refreshClaimableTransfersCapability()) {
                    "Transfer decisions are not available right now"
                }
                // A failed or malformed fresh read never falls back to the polled claim map.
                val authoritative = walletRepo.transferClaim(claimId)
                mutableTransferClaims.value += authoritative.id to authoritative
                check(
                    TransferClaimResolutionPolicy.allows(
                        action,
                        message,
                        authoritative,
                        binding,
                    ),
                ) {
                    "This payment is not pending or does not belong to this conversation"
                }
                check(walletRepo.currentAccountId.equals(binding.currentUserId, ignoreCase = true)) {
                    "The signed-in account changed while approving this payment"
                }
                settlementAttempted = true
                val settled = settle(authoritative.id)
                check(settled.id.equals(authoritative.id, ignoreCase = true)) {
                    "The server returned a different transfer"
                }
                check(settled.status == expectedStatus) {
                    "The server did not confirm this transfer update"
                }
                mutableTransferClaims.value += settled.id to settled
                postTransferEvent(selectedChat.id, settled, outcome, reason)
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "This transfer could not be updated"
                if (settlementAttempted) {
                    // A race can settle between the preflight GET and POST. Re-read this exact
                    // claim; never replace the fresh result with a cached or broad-list fallback.
                    runCatching { walletRepo.transferClaim(claimId) }
                        .getOrNull()
                        ?.let { refreshed ->
                            mutableTransferClaims.value += refreshed.id to refreshed
                        }
                }
            } finally {
                mutableSending.value = false
            }
        }
    }

    /**
     * Writes the outcome of a held transfer into the conversation, reason and all.
     *
     * Best-effort on purpose: the money has already moved, and the card's own state comes from
     * the wallet API. A failure here costs the written record of why, not the truth of what
     * happened — so it must not be reported as a failed settlement.
     */
    private suspend fun postTransferEvent(
        chatId: String,
        claim: TransferClaim,
        outcome: KitPaymentAction,
        reason: String?,
    ) {
        val descriptor = KitPaymentMessage(
            action = outcome,
            referenceId = claim.id,
            amountMinor = claim.amountMinor,
            currencyCode = claim.currencyCode,
            currencyScale = claim.currencyScale,
            // The original note already sits on the transfer card above; repeating it here would
            // only crowd out the one thing this line exists to say.
            note = null,
            reason = reason?.trim()
                ?.takeIf(String::isNotBlank)
                ?.take(KitPaymentMessage.MAX_REASON_LENGTH),
        ).encode()
        if (KitPaymentMessage.parse(descriptor) == null) return
        try {
            chatRepo.sendPaymentEvent(chatId, descriptor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Swallowed on purpose; see the note above.
        }
    }

    private suspend fun refreshTransferClaims() {
        val claims = try {
            walletRepo.transferClaims()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep whatever was last known. A card falls back to the conversation's own record
            // rather than losing its state because one poll failed.
            return
        }
        mutableTransferClaims.value = claims.associateBy(TransferClaim::id)
        recordExpiredTransfers(claims)
    }

    /**
     * Writes the closing line for transfers that timed out.
     *
     * The seven-day auto-return happens on the server with nobody watching, so neither party is
     * told. The sender's app posts it, chosen because exactly one side must and the sender's own
     * outgoing card identifies them without any further identity plumbing.
     */
    private suspend fun recordExpiredTransfers(claims: List<TransferClaim>) {
        val expired = claims.filter { it.status == TransferClaimStatus.EXPIRED }
        if (expired.isEmpty() || chatId.isBlank() || !messagingAvailable.value) return
        val projected = conversationMessages.value
        val sentFromHere = projected
            .filter { it.fromMe && it.kind == MessageKind.PAYMENT_TRANSFER }
            .mapNotNullTo(mutableSetOf()) { it.paymentReferenceId?.lowercase() }
        val alreadyWritten = projected
            .filter { it.paymentEvent == PaymentEventKind.EXPIRED }
            .mapNotNullTo(mutableSetOf()) { it.paymentReferenceId?.lowercase() }
        for (claim in expired) {
            val reference = claim.id.lowercase()
            if (reference !in sentFromHere || reference in alreadyWritten) continue
            // The projection lags the send by a round trip; this keeps one poll from writing the
            // same line twice while the first is still in flight.
            if (!announcedExpiries.add(reference)) continue
            postTransferEvent(chatId, claim, KitPaymentAction.EXPIRED, claim.reason)
        }
    }

    fun sendImage(bytes: ByteArray, mediaType: String, onSent: () -> Unit = {}) =
        sendMedia(bytes, mediaType, caption = null, onSent = onSent)

    /**
     * Recording/encoding cap for the in-app camera: the compiled policy clamped to what this
     * service currently accepts, so the camera improves for free when the service limit rises.
     */
    fun captureByteLimit(): Long =
        richMediaCapability?.maximumSendableBytes()
            ?: KitChatMediaLimits.MAX_TRANSFER_BYTES.toLong()

    /** Sends any kit-media-v1 attachment (photo, voice note, video or document) end-to-end. */
    fun sendMedia(
        bytes: ByteArray,
        mediaType: String,
        caption: String? = null,
        onSent: () -> Unit = {},
    ) {
        val selectedChat = chat.value
        if (
            selectedChat == null ||
            !messagingAvailable.value ||
            bytes.isEmpty() ||
            mutableSending.value
        ) {
            // Dropping a capture without a word would present a fake success (the camera has
            // already closed); say why the attachment was not queued.
            if (bytes.isNotEmpty() && selectedChat != null) {
                mutableError.value = if (mutableSending.value) {
                    "Wait for the current attachment to finish sending, then try again"
                } else {
                    "Secure messaging is temporarily unavailable"
                }
            }
            bytes.fill(0)
            return
        }
        val sendJob = viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                richMediaCapability?.requireSendable(mediaType.trim().lowercase(), bytes.size.toLong())
                chatRepo.sendImageMessage(selectedChat.id, bytes, mediaType, caption)
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "The secure attachment could not be sent"
            } finally {
                bytes.fill(0)
                mutableSending.value = false
            }
        }
        // A launch into an already-cleared ViewModel can be cancelled before its body (and
        // finally block) starts. Completion is the final ownership boundary for picker plaintext.
        sendJob.invokeOnCompletion { bytes.fill(0) }
    }

    /** Downloads and decrypts a media message once; results and failures are keyed by message. */
    fun openMedia(message: Message) {
        val selectedChat = chat.value ?: return
        val descriptor = message.mediaDescriptor ?: return
        if (!messagingAvailable.value) return
        if (
            mutableMediaBytes.value.containsKey(message.id) ||
            message.id in mutableMediaLoading.value ||
            mutableMediaErrors.value.containsKey(message.id)
        ) return
        mutableMediaLoading.value = mutableMediaLoading.value + message.id
        viewModelScope.launch {
            var opened: ByteArray? = null
            try {
                // One authenticated blob may transiently occupy ciphertext, plaintext and decode
                // buffers. Serialize receive work. The non-cancellable handoff ensures a returned
                // plaintext array is assigned before cancellation can discard its only owner.
                secureMediaOpenMutex.withLock {
                    withContext(NonCancellable) {
                        opened = chatRepo.openImageMessage(selectedChat.id, descriptor)
                    }
                }
                coroutineContext.ensureActive()
                cacheMedia(message.id, checkNotNull(opened))
                opened = null // Ownership moved into the bounded cache.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableMediaErrors.value = mutableMediaErrors.value +
                    (message.id to (error.message ?: "The secure photo could not be opened"))
            } finally {
                opened?.fill(0)
                mutableMediaLoading.value = mutableMediaLoading.value - message.id
            }
        }
    }

    fun retryMedia(message: Message) {
        discardMedia(message.id)
        mutableMediaErrors.value = mutableMediaErrors.value - message.id
        openMedia(message)
    }

    private fun cacheMedia(messageId: String, bytes: ByteArray) {
        val erased = mutableListOf<ByteArray>()
        val previous = mediaCache.remove(messageId)
        if (previous != null) {
            mediaCacheByteCount -= previous.size
            erased += previous
        }
        while (
            mediaCache.isNotEmpty() &&
            (mediaCache.size >= MAX_MEDIA_CACHE_ENTRIES ||
                mediaCacheByteCount + bytes.size > MAX_MEDIA_CACHE_BYTES)
        ) {
            val oldest = mediaCache.entries.first()
            mediaCache.remove(oldest.key)
            mediaCacheByteCount -= oldest.value.size
            erased += oldest.value
        }
        mediaCache[messageId] = bytes
        mediaCacheByteCount += bytes.size
        mutableMediaBytes.value = mediaCache.toMap()
        erased.forEach { it.fill(0) }
    }

    private fun discardMedia(messageId: String) {
        val removed = mediaCache.remove(messageId) ?: return
        mediaCacheByteCount -= removed.size
        mutableMediaBytes.value = mediaCache.toMap()
        removed.fill(0)
    }

    override fun onCleared() {
        foregroundSyncJob?.cancel()
        mutableMediaBytes.value = emptyMap()
        mediaCache.values.forEach { it.fill(0) }
        mediaCache.clear()
        mediaCacheByteCount = 0
        super.onCleared()
    }

    private fun launchSend(
        selectedChatId: String,
        normalizedText: String,
        retryingMessageId: String?,
        trustedPaymentEvent: Boolean,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            mutableSending.value = true
            mutableRetryingMessageId.value = retryingMessageId
            mutableError.value = null
            try {
                if (retryingMessageId == null) {
                    chatRepo.sendMessage(selectedChatId, normalizedText)
                } else {
                    if (trustedPaymentEvent) {
                        chatRepo.retryPaymentEvent(
                            chatId = selectedChatId,
                            clientMessageId = retryingMessageId,
                            descriptor = normalizedText,
                        )
                    } else {
                        chatRepo.retryMessage(
                            chatId = selectedChatId,
                            clientMessageId = retryingMessageId,
                            text = normalizedText,
                        )
                    }
                }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "Secure messaging is temporarily unavailable"
            } finally {
                mutableRetryingMessageId.value = null
                mutableSending.value = false
            }
        }
    }

    private companion object {
        const val FOREGROUND_SYNC_INTERVAL_MILLIS = 2_000L
        const val MAX_MEDIA_CACHE_ENTRIES = 4
        const val MAX_MEDIA_CACHE_BYTES = 24 * 1024 * 1024
    }
}
