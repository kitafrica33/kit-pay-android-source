package com.kit.wallet.data.repository

import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production-safe messaging boundary used until a reviewed Signal-compatible
 * client is integrated. It never downloads ciphertext for plaintext display,
 * persists drafts, or sends unencrypted message bodies to the backend.
 */
@Singleton
class EncryptedMessagingUnavailableRepository @Inject constructor() : ChatRepository {
    override val readiness: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    private val emptyChats = MutableStateFlow<List<ChatPreview>>(emptyList())
    override val chats: StateFlow<List<ChatPreview>> = emptyChats.asStateFlow()

    private val emptyConversation = MutableStateFlow<List<Message>>(emptyList()).asStateFlow()

    override fun chat(chatId: String): ChatPreview? = null

    override fun conversation(chatId: String): StateFlow<List<Message>> = emptyConversation

    override suspend fun openDirectConversation(contact: Contact): Nothing =
        error("Secure messaging is unavailable until end-to-end encryption is provisioned")

    override suspend fun sendMessage(
        chatId: String,
        text: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit,
    ): Nothing =
        error("Secure messaging is unavailable until end-to-end encryption is provisioned")

    override suspend fun retryMessage(
        chatId: String,
        clientMessageId: String,
        text: String,
    ): Nothing = error(
        "Secure messaging is unavailable until end-to-end encryption is provisioned",
    )
}
