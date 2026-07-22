package com.kit.wallet.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

@HiltViewModel
class ChatsViewModel @Inject constructor(chatRepo: ChatRepository) : ViewModel() {
    val messagingAvailable = chatRepo.readiness
    val chats = chatRepo.chats
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val walletRepo: WalletRepository,
    private val callRepo: CallRepository,
    private val messageSounds: MessageSoundPlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

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

    init {
        if (chatId.isNotBlank()) {
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
                var knownIncomingIds: Set<String>? = null
                var previousOutgoingStates: Map<String, DeliveryState>? = null
                conversationMessages.collect { projected ->
                    val visible = mutableConversationVisible.value

                    val incoming = projected.filter { !it.fromMe }
                    val previousIncoming = knownIncomingIds
                    if (previousIncoming != null && visible) {
                        val arrived = incoming.filter { it.id !in previousIncoming }
                        if (arrived.any { it.kind == MessageKind.PAYMENT }) {
                            messageSounds.playPaymentReceived()
                        } else if (arrived.isNotEmpty()) {
                            messageSounds.playReceived()
                        }
                    }
                    knownIncomingIds = incoming.mapTo(mutableSetOf()) { it.id }

                    val outgoingStates = projected.asSequence()
                        .filter { it.fromMe }
                        .associate { it.id to it.state }
                    val previousOutgoing = previousOutgoingStates
                    if (previousOutgoing != null && visible) {
                        val reachedFirstTick = outgoingStates.any { (id, state) ->
                            state == DeliveryState.SENT && previousOutgoing[id] == DeliveryState.SENDING
                        }
                        if (reachedFirstTick) messageSounds.playSent()
                    }
                    previousOutgoingStates = outgoingStates
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
        // Clear the composer immediately. The message is committed to the encrypted outbox and
        // appears in the thread with a clock tick right away — no spinner and no waiting on the
        // network round-trip. The "sent" sound plays when it reaches its first tick (see below).
        // Concurrent sends are serialized inside the runtime.
        onSent()
        viewModelScope.launch {
            try {
                chatRepo.sendMessage(selectedChat.id, normalized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "Secure messaging is temporarily unavailable"
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
            onSuccess = onRetried,
        )
    }

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
        viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                val created = walletRepo.createChatPaymentRequest(peerUserId, amountMinor, note)
                val descriptor = KitPaymentMessage(
                    action = KitPaymentMessage.ACTION_REQUEST,
                    paymentRequestId = created.id,
                    amountMinor = created.amountMinor,
                    currencyCode = created.currencyCode,
                    currencyScale = created.currencyScale,
                    note = created.note?.takeIf(String::isNotBlank),
                ).encode()
                chatRepo.sendMessage(selectedChat.id, descriptor)
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "The payment request could not be sent"
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
                    requestId = descriptor.paymentRequestId,
                    amountMinor = descriptor.amountMinor,
                    paymentPin = paymentPin,
                )
                // The paid confirmation is best-effort: the debit already completed above.
                runCatching {
                    chatRepo.sendMessage(
                        selectedChat.id,
                        descriptor.copy(action = KitPaymentMessage.ACTION_PAID).encode(),
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

    fun sendImage(bytes: ByteArray, mediaType: String, onSent: () -> Unit = {}) {
        val selectedChat = chat.value
        if (
            selectedChat == null ||
            !messagingAvailable.value ||
            bytes.isEmpty() ||
            mutableSending.value
        ) {
            bytes.fill(0)
            return
        }
        val sendJob = viewModelScope.launch {
            mutableSending.value = true
            mutableError.value = null
            try {
                chatRepo.sendImageMessage(selectedChat.id, bytes, mediaType)
                onSent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message
                    ?: "The secure photo could not be sent"
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
        mediaCache.remove(messageId)?.let { previous ->
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
                    chatRepo.retryMessage(
                        chatId = selectedChatId,
                        clientMessageId = retryingMessageId,
                        text = normalizedText,
                    )
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
