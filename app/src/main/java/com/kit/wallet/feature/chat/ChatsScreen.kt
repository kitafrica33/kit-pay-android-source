package com.kit.wallet.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme

@Composable
fun ChatsScreen(
    onChat: (String) -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val messagingAvailable by viewModel.messagingAvailable.collectAsStateWithLifecycle()
    ChatsContent(chats, messagingAvailable, onChat, onNewChat)
}

@Composable
private fun ChatsContent(
    allChats: List<ChatPreview>,
    messagingAvailable: Boolean,
    onChat: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }

    val chats = allChats.filter { c ->
        val q = query.isBlank() || c.name.contains(query, true) || c.lastMessage.contains(query, true)
        val f = when (filter) {
            "Unread" -> c.unread > 0
            "Groups" -> c.isGroup
            else -> true
        }
        q && f
    }

    Scaffold(
        floatingActionButton = {
            if (messagingAvailable) {
                FloatingActionButton(
                    onClick = onNewChat,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Icon(Icons.Rounded.AddComment, contentDescription = "New chat")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Chats",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    placeholder = { Text("Search chats and messages") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    listOf("All", "Unread", "Groups").forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            if (!messagingAvailable) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                "Secure messaging is not ready",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Keep Kit Pay online while this device finishes secure setup. Message text is never sent without end-to-end encryption.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(chats.size) { i ->
                ChatRow(chat = chats[i], onClick = { onChat(chats[i].id) })
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatPreview, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitAvatar(chat.name, size = 52.dp, online = chat.online)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.lastFromMe) {
                    Icon(
                        when (chat.lastState) {
                            DeliveryState.SENDING -> Icons.Rounded.Schedule
                            DeliveryState.SENT -> Icons.Rounded.Done
                            DeliveryState.RETRY_REQUIRED,
                            DeliveryState.FAILED,
                            -> Icons.Rounded.ErrorOutline
                            else -> Icons.Rounded.DoneAll
                        },
                        // The preview text immediately below announces this same delivery state.
                        contentDescription = null,
                        modifier = Modifier
                            .size(15.dp)
                            .padding(end = 2.dp),
                        tint = when (chat.lastState) {
                            DeliveryState.READ -> KitTheme.colors.readReceipt
                            DeliveryState.RETRY_REQUIRED,
                            DeliveryState.FAILED,
                            -> MaterialTheme.colorScheme.error
                            DeliveryState.DELIVERED -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
                Text(
                    when {
                        chat.typing -> "typing…"
                        chat.lastState == DeliveryState.RETRY_REQUIRED ->
                            "Not sent · ${chat.lastMessage}"
                        chat.lastState == DeliveryState.FAILED ->
                            "Photo expired · Send again"
                        chat.lastFromMe ->
                            "${outgoingDeliveryLabel(chat.lastState)} · ${chat.lastMessage}"
                        else -> chat.lastMessage
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chat.typing) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                chat.time,
                style = MaterialTheme.typography.labelSmall,
                color = if (chat.unread > 0) KitTheme.colors.success
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (chat.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (chat.muted) {
                    Icon(
                        Icons.Rounded.NotificationsOff,
                        contentDescription = "Muted",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                if (chat.pinned) {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                if (chat.unread > 0) {
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .background(MaterialTheme.colorScheme.secondary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            chat.unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.padding(horizontal = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatsPreview() {
    KitWalletTheme {
        ChatsContent(
            allChats = DemoData.chats,
            messagingAvailable = true,
            onChat = {},
            onNewChat = {},
        )
    }
}
