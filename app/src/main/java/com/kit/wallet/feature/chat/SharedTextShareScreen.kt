package com.kit.wallet.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.security.SecureScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SharedTextSendState(
    val requestToken: String? = null,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
)

internal enum class SharedTextSendStart {
    STARTED,
    ALREADY_SENDING,
    REJECTED,
}

internal fun directTextShareRecipients(
    chats: List<ChatPreview>,
    query: String = "",
): List<ChatPreview> {
    val normalizedQuery = query.trim()
    return chats.filter { chat ->
        !chat.isGroup && chat.name.contains(normalizedQuery, ignoreCase = true)
    }
}

@HiltViewModel
internal class SharedTextShareViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {
    val messagingAvailable = chatRepository.readiness
    val chats = chatRepository.chats

    private val mutableSendState = MutableStateFlow(SharedTextSendState())
    val sendState = mutableSendState.asStateFlow()
    private var sendJob: Job? = null

    fun begin(requestToken: String) {
        if (mutableSendState.value.requestToken == requestToken) return
        sendJob?.cancel()
        sendJob = null
        mutableSendState.value = SharedTextSendState(requestToken = requestToken)
    }

    fun send(
        requestToken: String,
        chatId: String,
        text: String,
        onFinished: () -> Unit,
    ): SharedTextSendStart {
        if (mutableSendState.value.requestToken != requestToken) begin(requestToken)
        if (mutableSendState.value.sending) return SharedTextSendStart.ALREADY_SENDING
        if (mutableSendState.value.sent) return SharedTextSendStart.REJECTED

        val directChatExists = chats.value.singleOrNull { it.id == chatId }?.isGroup == false
        if (!messagingAvailable.value || !directChatExists || text.isBlank()) {
            mutableSendState.value = mutableSendState.value.copy(
                error = "Choose an available direct secure conversation.",
            )
            return SharedTextSendStart.REJECTED
        }

        mutableSendState.value = mutableSendState.value.copy(sending = true, error = null)
        sendJob = viewModelScope.launch {
            try {
                // This is the only send point. Reaching it requires both the capability gate and
                // an explicit tap on the review screen's Send securely button.
                chatRepository.sendMessage(chatId, text)
                mutableSendState.value = mutableSendState.value.copy(
                    sending = false,
                    sent = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableSendState.value = mutableSendState.value.copy(
                    sending = false,
                    error = error.message ?: "The message could not be sent securely.",
                )
            } finally {
                // This direct callback cannot lose a fast true → false StateFlow transition.
                onFinished()
            }
        }
        return SharedTextSendStart.STARTED
    }

    fun cancel(requestToken: String) {
        val state = mutableSendState.value
        if (state.requestToken != requestToken || state.sent) return
        sendJob?.cancel()
        sendJob = null
        mutableSendState.value = state.copy(sending = false)
    }
}

/** Full-screen, explicit recipient-and-content review for an Android text share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedTextShareDialog(
    request: IncomingTextShareRequest,
    text: String,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    viewModel: SharedTextShareViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val messagingAvailable by viewModel.messagingAvailable.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    var query by remember(request.token) { mutableStateOf("") }
    var selectedChatId by remember(request.token) { mutableStateOf<String?>(null) }
    val directChats = remember(chats) { directTextShareRecipients(chats) }
    val matchingChats = remember(chats, query) {
        directTextShareRecipients(chats, query)
    }
    val selectedChat = directChats.firstOrNull { it.id == selectedChatId }

    LaunchedEffect(request.token) {
        viewModel.begin(request.token)
    }
    LaunchedEffect(request.token, sendState.requestToken, sendState.sent) {
        if (sendState.requestToken == request.token && sendState.sent) onSent()
    }
    DisposableEffect(request.token) {
        onDispose {
            viewModel.cancel(request.token)
            onSendingChanged(false)
        }
    }

    SecureScreen()
    Dialog(
        onDismissRequest = { if (!sendState.sending) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Share with Kit Pay") },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismiss,
                                enabled = !sendState.sending,
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel sharing")
                            }
                        },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 8.dp) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            if (sendState.requestToken == request.token && sendState.error != null) {
                                Text(
                                    sendState.error.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            KitGreenButton(
                                text = "Send securely",
                                icon = Icons.AutoMirrored.Rounded.Send,
                                loading = sendState.requestToken == request.token && sendState.sending,
                                enabled = messagingAvailable &&
                                    selectedChat != null &&
                                    sendState.requestToken == request.token &&
                                    !sendState.sent,
                                onClick = {
                                    selectedChat?.let {
                                        // Mark the request busy before starting the suspend call so
                                        // a simultaneous external share cannot hide this send.
                                        onSendingChanged(true)
                                        val start = viewModel.send(
                                            requestToken = request.token,
                                            chatId = it.id,
                                            text = text,
                                            onFinished = { onSendingChanged(false) },
                                        )
                                        if (start == SharedTextSendStart.REJECTED) {
                                            onSendingChanged(false)
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Nothing is sent until you choose a secure chat and confirm below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        Text(
                            "Choose recipient",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            placeholder = { Text("Search secure chats") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge,
                        )
                    }
                    if (matchingChats.isEmpty()) {
                        item {
                            Text(
                                if (directChats.isEmpty()) {
                                    "No direct secure conversations are available yet. Start a secure chat in Kit Pay, then share again."
                                } else {
                                    "No direct secure conversations match your search."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        items(matchingChats, key = ChatPreview::id) { chat ->
                            RecipientRow(
                                chat = chat,
                                selected = chat.id == selectedChatId,
                                onClick = { selectedChatId = chat.id },
                            )
                        }
                    }
                    if (selectedChat != null) {
                        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                        item {
                            Text(
                                "Review before sending",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                        item {
                            SharePreview(
                                recipient = selectedChat,
                                text = text,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RecipientRow(chat: ChatPreview, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitAvatar(chat.name, size = 44.dp, online = chat.online)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Direct secure conversation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SharePreview(recipient: ChatPreview, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitAvatar(recipient.name, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("To", style = MaterialTheme.typography.labelSmall)
                    Text(
                        recipient.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
