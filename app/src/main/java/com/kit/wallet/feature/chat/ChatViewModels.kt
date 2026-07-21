package com.kit.wallet.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel @Inject constructor(chatRepo: ChatRepository) : ViewModel() {
    val messagingAvailable = chatRepo.readiness
    val chats = chatRepo.chats
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val chatId: String = savedStateHandle.get<String>("chatId")
        ?.trim()
        .orEmpty()

    val messagingAvailable = chatRepo.readiness
    val chat: StateFlow<ChatPreview?> = chatRepo.chats
        .map { chats -> chats.singleOrNull { it.id == chatId } }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            chatId.takeIf(String::isNotBlank)?.let(chatRepo::chat),
        )
    val messages: StateFlow<List<Message>> = chatId.takeIf(String::isNotBlank)
        ?.let(chatRepo::conversation)
        ?: MutableStateFlow<List<Message>>(emptyList()).asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    private val mutableSending = MutableStateFlow(false)
    val sending = mutableSending.asStateFlow()

    private val mutableRetryingMessageId = MutableStateFlow<String?>(null)
    val retryingMessageId = mutableRetryingMessageId.asStateFlow()

    init {
        if (chatId.isNotBlank()) {
            viewModelScope.launch {
                combine(messagingAvailable, messages) { ready, projected ->
                    ready && projected.any { !it.fromMe }
                }.collectLatest { shouldMarkRead ->
                    if (!shouldMarkRead) return@collectLatest
                    try {
                        chatRepo.markConversationRead(chatId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Reading remains available if a local marker cannot be persisted. A later
                        // projection/readiness emission retries without leaking a remote receipt.
                    }
                }
            }
        }
    }

    fun clearError() {
        mutableError.value = null
    }

    fun send(text: String, onSent: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        val normalized = text.trim()
        if (!messagingAvailable.value || normalized.isBlank() || mutableSending.value) return
        launchSend(
            selectedChatId = selectedChat.id,
            normalizedText = normalized,
            retryingMessageId = null,
            onSuccess = onSent,
        )
    }

    fun retry(message: Message, onRetried: () -> Unit = {}) {
        val selectedChat = chat.value ?: return
        if (
            !message.fromMe ||
            message.state !in setOf(DeliveryState.SENDING, DeliveryState.RETRY_REQUIRED)
        ) return
        val normalized = message.text.trim()
        if (!messagingAvailable.value || normalized.isBlank() || mutableSending.value) return
        launchSend(
            selectedChatId = selectedChat.id,
            normalizedText = normalized,
            retryingMessageId = message.id,
            onSuccess = onRetried,
        )
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
}
