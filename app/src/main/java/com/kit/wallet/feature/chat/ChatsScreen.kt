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
import androidx.compose.material.icons.rounded.Call
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
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import com.kit.wallet.data.notifications.ActiveCallPresence
import com.kit.wallet.data.repository.MessageSearchHit
import com.kit.wallet.feature.calls.formatCallDuration
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme

@Composable
fun ChatsScreen(
    onChat: (String) -> Unit,
    onNewChat: () -> Unit,
    /** The group builder: pick who is in it, name it, and it opens as a conversation. */
    onNewGroup: () -> Unit = {},
    /** Returns to the call already in progress; the row never starts a new one. */
    onReturnToActiveCall: (String) -> Unit = {},
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val messagingAvailable by viewModel.messagingAvailable.collectAsStateWithLifecycle()
    val historyAvailable by viewModel.historyAvailable.collectAsStateWithLifecycle()
    val contacts by viewModel.searchableContacts.collectAsStateWithLifecycle()
    val activeCallPresence by viewModel.activeCallPresence.collectAsStateWithLifecycle()
    ChatsContent(
        allChats = chats,
        messagingAvailable = messagingAvailable,
        historyAvailable = historyAvailable,
        onChat = onChat,
        onNewChat = onNewChat,
        onNewGroup = onNewGroup,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onMarkRead = viewModel::markRead,
        searchableContacts = contacts,
        searchMessages = viewModel::searchMessages,
        onSearchedContact = { contact -> viewModel.openDirectConversation(contact, onChat) },
        activeCallPresence = activeCallPresence,
        onReturnToActiveCall = onReturnToActiveCall,
    )
}

@Composable
internal fun ChatsContent(
    allChats: List<ChatPreview>,
    messagingAvailable: Boolean,
    /** Whether the local encrypted store has been read; the list is real once this is true. */
    historyAvailable: Boolean = messagingAvailable,
    onChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit = {},
    onSetPinned: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    onSetMuted: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    onMarkRead: (Collection<String>) -> Unit = {},
    searchableContacts: List<Contact> = emptyList(),
    searchMessages: (String) -> List<MessageSearchHit> = { emptyList() },
    onSearchedContact: (Contact) -> Unit = {},
    /** This account's live call, so its owning row can show it and return to it. */
    activeCallPresence: ActiveCallPresence? = null,
    onReturnToActiveCall: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    // Multi-select is a transient gesture mode; it deliberately resets on process death.
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    val searching = query.isNotBlank()

    // Debounced local search over decrypted projections, matching iOS's 220 ms search sheet.
    var messageHits by remember { mutableStateOf(emptyList<MessageSearchHit>()) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            messageHits = emptyList()
        } else {
            delay(220)
            messageHits = searchMessages(query)
        }
    }
    val contactHits = if (searching) {
        searchableContacts.filter { it.name.contains(query, true) || it.phone.contains(query) }
            .take(6)
    } else {
        emptyList()
    }

    val chats = allChats.filter { c ->
        val q = query.isBlank() || c.name.contains(query, true) || c.lastMessage.contains(query, true)
        val f = when (filter) {
            "Unread" -> c.unread > 0
            "Pinned" -> c.pinned
            "Groups" -> c.isGroup
            else -> true
        }
        q && f
    }
    val selectedChats = allChats.filter { it.id in selected }

    Scaffold(
        floatingActionButton = {
            if (historyAvailable && !selecting) {
                // Starting a chat is the one thing here that genuinely needs a live session, so
                // it is the one thing that dims — in place, keeping its position on screen,
                // rather than vanishing and shifting the layout when the session arrives.
                FloatingActionButton(
                    onClick = { if (messagingAvailable) onNewChat() },
                    containerColor = MaterialTheme.colorScheme.secondary.copy(
                        alpha = if (messagingAvailable) 1f else 0.38f,
                    ),
                    contentColor = MaterialTheme.colorScheme.onSecondary.copy(
                        alpha = if (messagingAvailable) 1f else 0.38f,
                    ),
                ) {
                    Icon(
                        Icons.Rounded.AddComment,
                        contentDescription = if (messagingAvailable) {
                            "New chat"
                        } else {
                            "New chat, available once secure messaging is ready"
                        },
                    )
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
                    listOf("All", "Unread", "Pinned", "Groups").forEach { f ->
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
                // One quiet line, not a page. The chats below it are already readable; the only
                // thing still preparing is the ability to send, and it says so where that
                // matters — in each conversation's composer. It disappears on its own.
                item { PreparingSecureMessagingNote() }
            }
            if (historyAvailable && chats.isEmpty()) {
                item {
                    EmptyChatsState(
                        noChatsAtAll = allChats.isEmpty(),
                        filter = filter,
                        searching = query.isNotBlank(),
                        onNewChat = onNewChat,
                        onNewGroup = onNewGroup,
                    )
                }
            }
            if (!historyAvailable && allChats.isEmpty()) {
                item { OpeningChatsPlaceholders() }
            }
            if (searching && contactHits.isNotEmpty()) {
                item { SearchSectionHeader("Contacts") }
                items(contactHits.size) { i ->
                    val contact = contactHits[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSearchedContact(contact) }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KitAvatar(contact.name, size = 40.dp, avatarUrl = contact.avatarUrl)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                contact.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                contact.phone,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (searching && chats.isNotEmpty()) {
                item { SearchSectionHeader("Chats") }
            }
            items(chats.size) { i ->
                val chat = chats[i]
                // The live call marks only the row it strictly belongs to — the server's
                // conversation linkage or a direct chat's peer on the call roster — never a
                // lookalike, so this row can never reopen somebody else's call.
                val liveCall = activeCallPresence?.takeIf {
                    it.matchesChat(chatId = chat.id, isGroup = chat.isGroup, peerUserId = chat.peerUserId)
                }
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
                    liveCall = liveCall,
                    onReturnToCall = { liveCall?.let { onReturnToActiveCall(it.callId) } },
                )
            }
            if (searching && messageHits.isNotEmpty()) {
                item { SearchSectionHeader("Messages") }
                items(messageHits.size) { i ->
                    val hit = messageHits[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChat(hit.chat.id) }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KitAvatar(
                            hit.chat.name,
                            size = 40.dp,
                            avatarUrl = hit.chat.avatarUrl,
                            isGroup = hit.chat.isGroup,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                hit.chat.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                hit.message.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            hit.message.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * One line of status, sized like a caption and never like a page.
 *
 * It reports the one thing that is actually still preparing — the ability to *send* — while the
 * chats underneath it stay readable. It withdraws itself the moment the session reaches ready,
 * with no dismiss affordance to teach and nothing to retry, because the retry is automatic.
 */
@Composable
private fun PreparingSecureMessagingNote() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Preparing secure messaging…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Placeholder rows for the first read of the local store.
 *
 * Shown only when there is genuinely nothing to draw yet, so a device that has chats sees its
 * chats and a device that is still opening the store sees the shape of the list rather than an
 * empty state it would have to take back a moment later.
 */
@Composable
private fun OpeningChatsPlaceholders() {
    Column(Modifier.fillMaxWidth()) {
        repeat(6) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaceholderBlock(Modifier.size(48.dp), CircleShape)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // Widths vary a little so the column reads as a list of names rather than a
                    // loading bar stack.
                    PlaceholderBlock(
                        Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.42f else 0.55f)
                            .height(13.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    PlaceholderBlock(
                        Modifier
                            .fillMaxWidth(if (index % 3 == 0) 0.78f else 0.64f)
                            .height(11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderBlock(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small,
) {
    Box(
        modifier.background(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            shape,
        ),
    )
}

/** Friendly first-run/empty content so the tab never renders as a bare search box. */
@Composable
private fun EmptyChatsState(
    noChatsAtAll: Boolean,
    filter: String,
    searching: Boolean,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit = {},
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
            // A filter that is on describes the emptiness better than the device's overall state
            // does: under Groups, "No chats yet" would read as though groups were not a thing.
            when {
                searching -> "No chats found"
                filter == "Unread" -> "You're all caught up"
                filter == "Groups" -> "No group chats yet"
                noChatsAtAll -> "No chats yet"
                else -> "No chats found"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                searching -> "Try a different name or message."
                filter == "Unread" -> "New messages will appear here."
                filter == "Groups" ->
                    "Start an end-to-end encrypted group with your Kit Pay contacts."
                noChatsAtAll ->
                    "Start an end-to-end encrypted chat with your Kit Pay contacts."
                else -> "Try a different name or message."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        when {
            // Under the Groups filter the way forward is a group, not another direct chat —
            // even on a device with no chats at all.
            filter == "Groups" && !searching -> {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onNewGroup) { Text("New group") }
            }
            noChatsAtAll -> {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onNewChat) { Text("Start a chat") }
            }
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
    /** The live call this row's conversation strictly owns, or null for the normal preview. */
    liveCall: ActiveCallPresence? = null,
    onReturnToCall: () -> Unit = {},
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
        KitAvatar(
            chat.name,
            size = 52.dp,
            online = chat.online,
            avatarUrl = chat.avatarUrl,
            isGroup = chat.isGroup,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (liveCall != null) {
                // The ongoing call takes the preview line: live colour, the call's own kind, and
                // a duration ticking from the authoritative answer anchor (no anchor, no timer).
                // Its own tap target returns to the call; the rest of the row still opens the chat.
                val liveSeconds = rememberLiveCallSeconds(liveCall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onReturnToCall),
                ) {
                    Icon(
                        if (liveCall.video) Icons.Rounded.Videocam else Icons.Rounded.Call,
                        contentDescription = "Return to call",
                        modifier = Modifier
                            .size(15.dp)
                            .padding(end = 2.dp),
                        tint = KitTheme.colors.success,
                    )
                    Text(
                        buildString {
                            append(if (liveCall.video) "Video call" else "Voice call")
                            if (liveCall.anchor != null) {
                                append(" · ")
                                append(formatCallDuration(liveSeconds))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = KitTheme.colors.success,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
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
                        // A group says who, since its name is on the row above and "typing…"
                        // under it would not tell anybody which of thirty people it is.
                        chat.typing -> groupTypingLabel(chat.typingNames) ?: "typing…"
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
