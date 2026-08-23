package com.kit.wallet.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    ChatsContent(
        allChats = chats,
        messagingAvailable = messagingAvailable,
        onChat = onChat,
        onNewChat = onNewChat,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onMarkRead = viewModel::markRead,
    )
}

@Composable
private fun ChatsContent(
    allChats: List<ChatPreview>,
    messagingAvailable: Boolean,
    onChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onSetPinned: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    onSetMuted: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    onMarkRead: (Collection<String>) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    // Multi-select is a transient gesture mode; it deliberately resets on process death.
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()

    val chats = allChats.filter { c ->
        val q = query.isBlank() || c.name.contains(query, true) || c.lastMessage.contains(query, true)
        val f = when (filter) {
            "Unread" -> c.unread > 0
            "Pinned" -> c.pinned
            else -> true
        }
        q && f
    }
    val selectedChats = allChats.filter { it.id in selected }

    Scaffold(
        floatingActionButton = {
            if (messagingAvailable && !selecting) {
                FloatingActionButton(
                    onClick = onNewChat,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Icon(Icons.Rounded.AddComment, contentDescription = "New chat")
                }
            }
        },
        bottomBar = {
            if (selecting) {
                ChatSelectionBar(
                    count = selected.size,
                    anyUnread = selectedChats.any { it.unread > 0 },
                    allPinned = selectedChats.isNotEmpty() && selectedChats.all { it.pinned },
                    allMuted = selectedChats.isNotEmpty() && selectedChats.all { it.muted },
                    onMarkRead = {
                        onMarkRead(selected)
                        selected = emptySet()
                    },
                    onPin = { pinned ->
                        onSetPinned(selected, pinned)
                        selected = emptySet()
                    },
                    onMute = { muted ->
                        onSetMuted(selected, muted)
                        selected = emptySet()
                    },
                    onCancel = { selected = emptySet() },
                )
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
                        if (selecting) "${selected.size} selected" else "Chats",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (selecting) {
                        TextButton(onClick = { selected = emptySet() }) { Text("Cancel") }
                    }
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
                    listOf("All", "Unread", "Pinned").forEach { f ->
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
            if (messagingAvailable && chats.isEmpty()) {
                item {
                    EmptyChatsState(
                        noChatsAtAll = allChats.isEmpty(),
                        filter = filter,
                        searching = query.isNotBlank(),
                        onNewChat = onNewChat,
                    )
                }
            }
            items(chats.size) { i ->
                val chat = chats[i]
                ChatRow(
                    chat = chat,
                    selecting = selecting,
                    selected = chat.id in selected,
                    onClick = {
                        if (selecting) {
                            selected = if (chat.id in selected) selected - chat.id else selected + chat.id
                        } else {
                            onChat(chat.id)
                        }
                    },
                    onSelect = { if (!selecting) selected = setOf(chat.id) },
                    onPin = { onSetPinned(listOf(chat.id), !chat.pinned) },
                    onMute = { onSetMuted(listOf(chat.id), !chat.muted) },
                    onMarkRead = { onMarkRead(listOf(chat.id)) },
                )
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

/** Friendly first-run/empty content so the tab never renders as a bare search box. */
@Composable
private fun EmptyChatsState(
    noChatsAtAll: Boolean,
    filter: String,
    searching: Boolean,
    onNewChat: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AddComment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                noChatsAtAll -> "No chats yet"
                searching -> "No chats found"
                filter == "Unread" -> "You're all caught up"
                filter == "Groups" -> "No group chats yet"
                else -> "No chats found"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                noChatsAtAll ->
                    "Start an end-to-end encrypted chat with your Kit Pay contacts."
                searching -> "Try a different name or message."
                filter == "Unread" -> "New messages will appear here."
                filter == "Groups" -> "Group conversations will appear here."
                else -> "Try a different name or message."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (noChatsAtAll) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onNewChat) { Text("Start a chat") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatPreview,
    onClick: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    onPin: () -> Unit = {},
    onMute: () -> Unit = {},
    onMarkRead: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (chat.pinned) "Unpin" else "Pin") },
                leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                onClick = {
                    menuOpen = false
                    onPin()
                },
            )
            if (chat.unread > 0) {
                DropdownMenuItem(
                    text = { Text("Mark as read") },
                    leadingIcon = { Icon(Icons.Rounded.DoneAll, null) },
                    onClick = {
                        menuOpen = false
                        onMarkRead()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (chat.muted) "Unmute" else "Mute") },
                leadingIcon = { Icon(Icons.Rounded.NotificationsOff, null) },
                onClick = {
                    menuOpen = false
                    onMute()
                },
            )
            DropdownMenuItem(
                text = { Text("Select") },
                leadingIcon = { Icon(Icons.Rounded.CheckCircle, null) },
                onClick = {
                    menuOpen = false
                    onSelect()
                },
            )
        }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (!selecting) menuOpen = true },
            )
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        KitAvatar(chat.name, size = 52.dp, online = chat.online, avatarUrl = chat.avatarUrl)
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
}

/** Bulk actions for the selected conversations, floating above the navigation bar. */
@Composable
private fun ChatSelectionBar(
    count: Int,
    anyUnread: Boolean,
    allPinned: Boolean,
    allMuted: Boolean,
    onMarkRead: () -> Unit,
    onPin: (Boolean) -> Unit,
    onMute: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onMarkRead, enabled = anyUnread && count > 0) { Text("Read") }
            TextButton(onClick = { onPin(!allPinned) }, enabled = count > 0) {
                Text(if (allPinned) "Unpin" else "Pin")
            }
            TextButton(onClick = { onMute(!allMuted) }, enabled = count > 0) {
                Text(if (allMuted) "Unmute" else "Mute")
            }
            TextButton(onClick = onCancel) { Text("Done") }
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
