package com.kit.wallet.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.R
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitGreen300
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chatId: String,
    onBack: () -> Unit,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messagingAvailable by viewModel.messagingAvailable.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val retryingMessageId by viewModel.retryingMessageId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val currentChat = chat
    if (!messagingAvailable) {
        SecureMessagingUnavailable(onBack)
        return
    }
    if (currentChat == null) {
        SecureConversationLoading(onBack)
        return
    }
    ConversationContent(
        chat = currentChat,
        messages = messages,
        onBack = onBack,
        onVoiceCall = onVoiceCall,
        onVideoCall = onVideoCall,
        sending = sending,
        retryingMessageId = retryingMessageId,
        error = error,
        onClearError = viewModel::clearError,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecureConversationLoading(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure conversation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Opening secure conversation…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Waiting for this authenticated conversation to finish loading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecureMessagingUnavailable(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure messaging") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Security,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text("Secure messaging is not ready", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Keep Kit Pay online while this device finishes secure setup. Kit Pay will never transmit message text without end-to-end encryption.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationContent(
    chat: ChatPreview,
    messages: List<Message>,
    onBack: () -> Unit,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    sending: Boolean,
    retryingMessageId: String?,
    error: String?,
    onClearError: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
    onRetry: (Message, () -> Unit) -> Unit,
) {
    // Message plaintext must not enter the Activity saved-instance-state bundle. A rotation or
    // process death deliberately discards this in-memory draft until an encrypted draft store exists.
    var draft by remember { mutableStateOf("") }
    val retryableMessageIds = remember(messages) {
        retryableOutgoingMessageIds(messages)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KitAvatar(chat.name, size = 40.dp, online = chat.online)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(chat.name, style = MaterialTheme.typography.titleMedium)
                            if (chat.online) {
                                Text(
                                    "online",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KitTheme.colors.success,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = chat.peerUserId != null,
                        onClick = { chat.peerUserId?.let(onVideoCall) },
                    ) {
                        Icon(Icons.Rounded.Videocam, contentDescription = "Video call")
                    }
                    IconButton(
                        enabled = chat.peerUserId != null,
                        onClick = { chat.peerUserId?.let(onVoiceCall) },
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = "Voice call")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Composer(
                    draft = draft,
                    sending = sending,
                    onDraft = {
                        draft = it
                        if (error != null) onClearError()
                    },
                    onSend = { onSend(draft) { draft = "" } },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(vertical = 10.dp),
                    ) {
                        Text(
                            "Today",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "Messages are protected with end-to-end encryption",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            items(messages.size) { i ->
                val message = messages[i]
                MessageBubble(
                    msg = message,
                    operationInFlight = sending,
                    retrying = retryingMessageId == message.id,
                    retryEnabled = message.id in retryableMessageIds,
                    onRetry = {
                        onRetry(message) {
                            if (draft.trim() == message.text) draft = ""
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: Message,
    operationInFlight: Boolean,
    retrying: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    val colors = KitTheme.colors
    val bubbleColor = if (msg.fromMe) colors.chatBubbleMe else colors.chatBubbleOther
    val contentColor = if (msg.fromMe) colors.onChatBubbleMe else colors.onChatBubbleOther
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (msg.fromMe) 18.dp else 6.dp,
        bottomEnd = if (msg.fromMe) 6.dp else 18.dp,
    )

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(if (msg.fromMe) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(bottom = if (msg.reactions.isEmpty()) 8.dp else 18.dp)
                .widthIn(max = 300.dp),
        ) {
            Surface(color = bubbleColor, contentColor = contentColor, shape = shape, shadowElevation = 1.dp) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    msg.replyToText?.let { reply ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Text(
                                reply,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    when (msg.kind) {
                        MessageKind.PAYMENT -> PaymentChatCard(msg)
                        MessageKind.VOICE_NOTE -> VoiceNoteRow(msg)
                        else -> Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (
                            msg.fromMe &&
                            msg.state in setOf(DeliveryState.SENDING, DeliveryState.RETRY_REQUIRED)
                        ) {
                            Text(
                                when {
                                    retrying -> "Retrying…"
                                    msg.state == DeliveryState.SENDING && retryEnabled ->
                                        "Pending · Retry"
                                    msg.state == DeliveryState.SENDING -> "Pending"
                                    retryEnabled -> "Not sent · Retry"
                                    else -> "Not sent"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = if (retryEnabled) {
                                    Modifier.clickable(
                                        enabled = !operationInFlight,
                                        onClick = onRetry,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            msg.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.65f),
                        )
                        if (msg.fromMe) {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                if (msg.state == DeliveryState.RETRY_REQUIRED) {
                                    Icons.Rounded.ErrorOutline
                                } else {
                                    Icons.Rounded.DoneAll
                                },
                                contentDescription = when (msg.state) {
                                    DeliveryState.READ -> "Read"
                                    DeliveryState.DELIVERED -> "Delivered"
                                    DeliveryState.SENDING -> if (retryEnabled) {
                                        "Pending delivery; retry available"
                                    } else {
                                        "Pending delivery"
                                    }
                                    DeliveryState.RETRY_REQUIRED -> if (retryEnabled) {
                                        "Not sent; retry required"
                                    } else {
                                        "Not sent"
                                    }
                                    else -> "Sent"
                                },
                                modifier = Modifier.size(14.dp),
                                tint = when (msg.state) {
                                    DeliveryState.READ -> KitTheme.colors.success
                                    DeliveryState.RETRY_REQUIRED -> MaterialTheme.colorScheme.error
                                    else -> contentColor.copy(alpha = 0.5f)
                                },
                            )
                        }
                    }
                }
            }
            if (msg.reactions.isNotEmpty()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .align(if (msg.fromMe) Alignment.End else Alignment.Start)
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        msg.reactions.joinToString(" "),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * A stale roster retry remains visible for audit, but must stop being actionable after a newer
 * outgoing copy of the same authenticated text exists. Otherwise tapping the old bubble after a
 * successful fresh encryption would send a duplicate. The newest unresolved copy stays retryable.
 */
internal fun retryableOutgoingMessageIds(messages: List<Message>): Set<String> = buildSet {
    messages.forEachIndexed { index, message ->
        if (
            message.fromMe &&
            message.state in setOf(DeliveryState.SENDING, DeliveryState.RETRY_REQUIRED) &&
            messages.subList(index + 1, messages.size).none { newer ->
                newer.fromMe && newer.text == message.text
            }
        ) {
            add(message.id)
        }
    }
}

@Composable
private fun PaymentChatCard(msg: Message) {
    val colors = KitTheme.colors
    Column(
        Modifier
            .background(
                Brush.linearGradient(listOf(colors.balanceCardStart, colors.balanceCardEnd)),
                MaterialTheme.shapes.medium,
            )
            .padding(14.dp)
            .widthIn(min = 210.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(Color.White.copy(alpha = 0.14f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_kit_mark_white),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (msg.amountMinor < 0) "Payment sent" else "Payment received",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Text(
                    Money.format(abs(msg.amountMinor)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Completed",
                tint = KitGreen300,
            )
        }
    }
}

@Composable
private fun VoiceNoteRow(msg: Message) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Voice note playback unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "0:%02d".format(msg.durationSec % 60),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = draft,
                    onValueChange = onDraft,
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 4,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(50.dp)
                .background(MaterialTheme.colorScheme.secondary, CircleShape)
                .clickable(enabled = draft.isNotBlank() && !sending, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onSecondary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = if (draft.isNotBlank()) 1f else 0.45f,
                    ),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationPreview() {
    KitWalletTheme {
        ConversationContent(
            chat = DemoData.chats.first(),
            messages = DemoData.conversation,
            onBack = {},
            onVoiceCall = {},
            onVideoCall = {},
            sending = false,
            retryingMessageId = null,
            error = null,
            onClearError = {},
            onSend = { _, onSent -> onSent() },
            onRetry = { _, onRetried -> onRetried() },
        )
    }
}
