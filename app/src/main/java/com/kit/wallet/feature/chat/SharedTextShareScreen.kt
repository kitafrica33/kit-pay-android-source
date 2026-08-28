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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
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
import com.kit.wallet.data.messaging.ImmediateSendIntent
import com.kit.wallet.data.messaging.SecureMediaAlbumSource
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.security.SecureScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SharedTextSendState(
    val requestToken: String? = null,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val pinnedConversationId: String? = null,
    val durablyQueuedComponents: Int = 0,
    val error: String? = null,
)

internal enum class SharedTextSendStart {
    STARTED,
    ALREADY_SENDING,
    REJECTED,
}

internal sealed interface SharedRecipient {
    val stableId: String
    val name: String
    val avatarUrl: String?

    data class Conversation(val chat: ChatPreview) : SharedRecipient {
        override val stableId: String = "conversation:${chat.id}"
        override val name: String = chat.name
        override val avatarUrl: String? = chat.avatarUrl
    }

    data class Person(
        val contact: Contact,
        /** Existing local thread, when this person has one outside the five recent rows. */
        val existingChatId: String?,
    ) : SharedRecipient {
        override val stableId: String = "contact:${contact.id}"
        override val name: String = contact.name
        override val avatarUrl: String? = contact.avatarUrl
    }
}

internal data class SharedRecipientSections(
    val recent: List<SharedRecipient.Conversation>,
    val contacts: List<SharedRecipient.Person>,
    val otherGroups: List<SharedRecipient.Conversation>,
) {
    val all: List<SharedRecipient> get() = recent + contacts + otherGroups
}

/** A restored partial batch must find its pinned chat even when that chat is older than Recents. */
internal fun pinnedShareRecipient(
    chats: List<ChatPreview>,
    conversationId: String,
    groupMessagingEnabled: Boolean = true,
): SharedRecipient.Conversation? = chats.firstOrNull { chat ->
    SharedInboxPolicy.canonicalConversationId(chat.id) == conversationId &&
        (!chat.isGroup || groupMessagingEnabled)
}?.let { SharedRecipient.Conversation(it) }

/**
 * Five conversations preserve repository recency order and mix people with groups. Everybody else
 * comes from the eligible Kit Pay contact directory alphabetically; an older direct thread is
 * reused instead of creating a duplicate. Groups have no contact row, so older ones get a final
 * alphabetical section and remain reachable.
 */
internal fun shareRecipientSections(
    chats: List<ChatPreview>,
    contacts: List<Contact>,
    query: String = "",
    groupMessagingEnabled: Boolean = true,
): SharedRecipientSections {
    val normalizedQuery = query.trim()
    fun matches(value: String): Boolean =
        normalizedQuery.isEmpty() || value.contains(normalizedQuery, ignoreCase = true)

    val eligibleChats = chats.filter { !it.isGroup || groupMessagingEnabled }
    val recentChats = eligibleChats.take(MAXIMUM_RECENT_SHARE_CHATS)
    val recent = recentChats
        .filter { matches(it.name) }
        .map { SharedRecipient.Conversation(it) }
    val directChatsByPeer = eligibleChats.asSequence()
        .filterNot(ChatPreview::isGroup)
        .mapNotNull { chat -> chat.peerUserId?.let { peerId -> peerId to chat.id } }
        .distinctBy { (peerId, _) -> peerId }
        .toMap()
    val recentDirectPeers = recent.asSequence()
        .map(SharedRecipient.Conversation::chat)
        .filterNot(ChatPreview::isGroup)
        .mapNotNull(ChatPreview::peerUserId)
        .toSet()
    val otherContacts = contacts.asSequence()
        .filter { it.isKitUser && it.id.isNotBlank() && it.id !in recentDirectPeers }
        .filter {
            matches(it.name) || matches(it.phone) ||
                it.registeredName?.let(::matches) == true
        }
        .distinctBy(Contact::id)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Contact::name).thenBy(Contact::id))
        .map { contact ->
            SharedRecipient.Person(
                contact = contact,
                existingChatId = directChatsByPeer[contact.id],
            )
        }
        .toList()
    val otherGroups = eligibleChats.drop(MAXIMUM_RECENT_SHARE_CHATS)
        .asSequence()
        .filter(ChatPreview::isGroup)
        .filter { matches(it.name) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ChatPreview::name).thenBy(ChatPreview::id))
        .map { SharedRecipient.Conversation(it) }
        .toList()
    return SharedRecipientSections(recent, otherContacts, otherGroups)
}

private const val MAXIMUM_RECENT_SHARE_CHATS = 5

@HiltViewModel
internal class SharedTextShareViewModel @Inject constructor(
    private val sharedInbox: SharedInboxAccess,
    private val chatRepository: ChatRepository,
    contactRepository: ContactRepository,
    private val sessions: SessionStore,
) : ViewModel() {
    /**
     * A send is accepted as soon as this account's encrypted local history/outbox is open. The
     * repository publishes the bubble locally, then encrypts, queues and sends in the background.
     * Requiring a live transport here would make Android's share sheet fail exactly when the user
     * most needs the offline-first outbox.
     */
    val localOutboxAvailable = chatRepository.localHistoryReady
    val chats = chatRepository.chats
    val contacts = contactRepository.contacts

    private val mutableSendState = MutableStateFlow(SharedTextSendState())
    val sendState = mutableSendState.asStateFlow()
    private var sendJob: Job? = null

    fun begin(requestToken: String, pinnedConversationId: String? = null) {
        if (mutableSendState.value.requestToken == requestToken) return
        sendJob?.cancel()
        sendJob = null
        mutableSendState.value = SharedTextSendState(
            requestToken = requestToken,
            pinnedConversationId = pinnedConversationId,
        )
    }

    fun send(
        requestToken: String,
        recipient: SharedRecipient,
        batch: SharedInboxBatch,
        groupMessagingEnabled: Boolean = true,
        onFinished: () -> Unit,
    ): SharedTextSendStart {
        if (mutableSendState.value.requestToken != requestToken) {
            begin(requestToken, batch.pinnedConversationId)
        }
        if (mutableSendState.value.sending) return SharedTextSendStart.ALREADY_SENDING
        if (mutableSendState.value.sent) return SharedTextSendStart.REJECTED

        if (batch.text?.let(SharedInboxPolicy::allowsUserAuthoredText) == false) {
            mutableSendState.value = mutableSendState.value.copy(
                error = "Messages cannot start with one of Kit Pay's reserved prefixes",
            )
            return SharedTextSendStart.REJECTED
        }

        val recipientExists = when (recipient) {
            is SharedRecipient.Conversation ->
                chats.value.any {
                    it.id == recipient.chat.id && (!it.isGroup || groupMessagingEnabled)
                }
            is SharedRecipient.Person ->
                contacts.value.any { it.id == recipient.contact.id && it.isKitUser }
        }
        val owner = sessions.current()?.fence()
        if (
            !localOutboxAvailable.value ||
            !recipientExists ||
            !batch.isDeliverable ||
            owner == null ||
            !batch.owner.matches(owner)
        ) {
            mutableSendState.value = mutableSendState.value.copy(
                error = "Choose an available secure conversation.",
            )
            return SharedTextSendStart.REJECTED
        }

        mutableSendState.value = mutableSendState.value.copy(sending = true, error = null)
        sendJob = viewModelScope.launch {
            try {
                check(sessions.current()?.fence() == owner) {
                    "Your Kit Pay session changed. Share this item again."
                }
                // Resolve every file before pinning or mutating the outbox. A missing item must
                // reject the whole review rather than queue the readable prefix of the batch.
                val prepared = withContext(Dispatchers.IO) {
                    batch.items.map { item -> item to sharedInbox.source(batch, item) }
                }
                val chatId = when (recipient) {
                    is SharedRecipient.Conversation -> recipient.chat.id
                    is SharedRecipient.Person -> {
                        val currentContact = checkNotNull(
                            contacts.value.firstOrNull {
                                it.id == recipient.contact.id && it.isKitUser
                            },
                        ) { "That Kit Pay contact is no longer available" }
                        recipient.existingChatId
                            ?.takeIf { id ->
                                chats.value.any {
                                    it.id == id && !it.isGroup &&
                                        it.peerUserId == currentContact.id
                                }
                            }
                            ?: chatRepository.openDirectConversation(currentContact).also {
                                if (sessions.current()?.fence() != owner) {
                                    throw SessionInvalidatedException()
                                }
                            }
                    }
                }
                val canonicalChatId = checkNotNull(
                    SharedInboxPolicy.canonicalConversationId(chatId),
                ) { "That secure conversation is no longer available" }
                check(
                    batch.pinnedConversationId == null ||
                        batch.pinnedConversationId == canonicalChatId,
                ) { "This share is already assigned to another conversation" }
                // The delivery shape is decided once, here, and pinned with the destination. A
                // batch already pinned keeps its recorded shape whatever the capability reads
                // now — re-deciding after a process death would queue the same content again
                // under different component identities.
                val requestedAlbumDelivery =
                    prepared.size >= 2 && chatRepository.mediaAlbumsAvailable.value
                val pinnedBatch = withContext(Dispatchers.IO) {
                    sharedInbox.pinDestination(batch, canonicalChatId, requestedAlbumDelivery)
                }
                mutableSendState.value = mutableSendState.value.copy(
                    pinnedConversationId = canonicalChatId,
                )

                // This is the only send point. Reaching it requires both the capability gate and
                // an explicit tap on the review screen's Send securely button.
                if (pinnedBatch.albumDelivery == true && prepared.size >= 2) {
                    // Album shape: every file and the shared text together as one message under
                    // one stable identity. The text rides as the album's caption — never as a
                    // separate send — so caption and attachments can neither split nor reorder.
                    // A caption the wire cannot carry fails the whole review visibly, with the
                    // batch retained; nothing is truncated or sent piecemeal instead.
                    chatRepository.sendIdempotentMediaAlbumMessageForOwner(
                        owner = owner,
                        chatId = canonicalChatId,
                        attachments = prepared.map { (item, source) ->
                            SecureMediaAlbumSource(source, item.mediaType)
                        },
                        clientMessageId = SharedInboxPolicy.deliveryMessageId(
                            pinnedBatch.id,
                            canonicalChatId,
                            SharedInboxPolicy.ALBUM_COMPONENT,
                        ),
                        caption = pinnedBatch.text?.takeIf(String::isNotBlank),
                    )
                    mutableSendState.value = mutableSendState.value.copy(
                        durablyQueuedComponents = mutableSendState.value.durablyQueuedComponents + 1,
                    )
                } else {
                    // Per-item shape. A single file with words that fit the KITMEDIA1 caption
                    // sends as one captioned media message — the same one-message reading the
                    // album shape gives — instead of a file bubble chased by a text bubble.
                    // The decision derives only from the pinned batch and a wire constant, so a
                    // restarted send re-decides identically, and the caption joins the queued
                    // send's idempotent identity, so a replay can never re-shape it. Words too
                    // long for a caption keep the classic two-message shape, files first and
                    // text last so the words read as the thing said about the attachments.
                    val foldedCaption = pinnedBatch.text
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.takeIf { prepared.size == 1 }
                        ?.takeIf {
                            it.toByteArray(Charsets.UTF_8).size <=
                                ImmediateSendIntent.MAX_CAPTION_UTF8_BYTES
                        }
                    for ((item, source) in prepared) {
                        chatRepository.sendIdempotentMediaMessageForOwner(
                            owner = owner,
                            chatId = canonicalChatId,
                            source = source,
                            mediaType = item.mediaType,
                            clientMessageId = SharedInboxPolicy.deliveryMessageId(
                                pinnedBatch.id,
                                canonicalChatId,
                                item.id,
                            ),
                            caption = foldedCaption,
                        )
                        mutableSendState.value = mutableSendState.value.copy(
                            durablyQueuedComponents =
                                mutableSendState.value.durablyQueuedComponents + 1,
                        )
                    }
                    if (foldedCaption == null) {
                        pinnedBatch.text?.takeIf(String::isNotBlank)?.let { text ->
                            chatRepository.sendIdempotentMessageForOwner(
                                owner = owner,
                                chatId = canonicalChatId,
                                text = text,
                                clientMessageId = SharedInboxPolicy.deliveryMessageId(
                                    pinnedBatch.id,
                                    canonicalChatId,
                                    SharedInboxPolicy.TEXT_COMPONENT,
                                ),
                            )
                            mutableSendState.value = mutableSendState.value.copy(
                                durablyQueuedComponents =
                                    mutableSendState.value.durablyQueuedComponents + 1,
                            )
                        }
                    }
                }
                discard(pinnedBatch)
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

    /** Staged plaintext outlives nothing: it goes as soon as it has been sent or given up on. */
    fun discard(batch: SharedInboxBatch) {
        sharedInbox.discard(batch)
    }
}

/** Full-screen, explicit recipient-and-content review for an Android share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedTextShareDialog(
    request: IncomingTextShareRequest,
    batch: SharedInboxBatch,
    groupMessagingEnabled: Boolean,
    onDismiss: () -> Unit,
    onDeferred: () -> Unit,
    onSent: () -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    viewModel: SharedTextShareViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val localOutboxAvailable by viewModel.localOutboxAvailable.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    var query by remember(request.token) { mutableStateOf("") }
    var selectedRecipientId by remember(request.token) { mutableStateOf<String?>(null) }
    val unpinnedRecipientSections = remember(chats, contacts, query, groupMessagingEnabled) {
        shareRecipientSections(chats, contacts, query, groupMessagingEnabled)
    }
    val effectivePinnedConversationId =
        sendState.pinnedConversationId ?: batch.pinnedConversationId
    val recipientSections = remember(
        chats,
        unpinnedRecipientSections,
        effectivePinnedConversationId,
        groupMessagingEnabled,
    ) {
        effectivePinnedConversationId?.let { conversationId ->
            SharedRecipientSections(
                recent = listOfNotNull(
                    pinnedShareRecipient(chats, conversationId, groupMessagingEnabled),
                ),
                contacts = emptyList(),
                otherGroups = emptyList(),
            )
        } ?: unpinnedRecipientSections
    }
    val selectedRecipient = recipientSections.all
        .firstOrNull { it.stableId == selectedRecipientId }

    LaunchedEffect(request.token) {
        viewModel.begin(request.token, batch.pinnedConversationId)
    }
    LaunchedEffect(effectivePinnedConversationId, recipientSections) {
        if (effectivePinnedConversationId != null) {
            selectedRecipientId = recipientSections.all.firstOrNull()?.stableId
        }
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

    val dismissShare = {
        if (effectivePinnedConversationId == null) onDismiss() else onDeferred()
    }

    SecureScreen()
    Dialog(
        onDismissRequest = { if (!sendState.sending) dismissShare() },
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
                                onClick = dismissShare,
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
                                if (effectivePinnedConversationId != null) {
                                    Text(
                                        "The remaining items stay saved for this chat. You can retry now or after reopening Kit Pay.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                            }
                            KitGreenButton(
                                text = "Send securely",
                                icon = Icons.AutoMirrored.Rounded.Send,
                                loading = sendState.requestToken == request.token && sendState.sending,
                                enabled = localOutboxAvailable &&
                                    selectedRecipient != null &&
                                    sendState.requestToken == request.token &&
                                    !sendState.sent,
                                onClick = {
                                    selectedRecipient?.let { recipient ->
                                        // Mark the request busy before starting the suspend call so
                                        // a simultaneous external share cannot hide this send.
                                        onSendingChanged(true)
                                        val start = viewModel.send(
                                            requestToken = request.token,
                                            recipient = recipient,
                                            batch = batch,
                                            groupMessagingEnabled = groupMessagingEnabled,
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
                            Column {
                                Text(
                                    "Nothing is sent until you choose a secure chat and confirm below.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    SharedInboxPolicy.summary(
                                        itemCount = batch.items.size,
                                        hasText = !batch.text.isNullOrEmpty(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            if (effectivePinnedConversationId == null) {
                                "Choose a person or group"
                            } else {
                                "Finish sharing to this chat"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    if (effectivePinnedConversationId == null) {
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                placeholder = { Text("Search people and groups") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.extraLarge,
                            )
                        }
                    }
                    if (recipientSections.all.isEmpty()) {
                        item {
                            Text(
                                if (!localOutboxAvailable) {
                                    "Opening your encrypted conversations…"
                                } else if (chats.isEmpty() && contacts.none(Contact::isKitUser)) {
                                    "No Kit Pay conversations or contacts are available yet."
                                } else if (effectivePinnedConversationId != null) {
                                    "That selected conversation is no longer available. The remaining items are still saved."
                                } else {
                                    "No people or groups match your search."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (recipientSections.recent.isNotEmpty()) {
                        item {
                            RecipientSectionTitle(if (query.isBlank()) "Recent" else "Conversations")
                        }
                        items(recipientSections.recent, key = { it.stableId }) { recipient ->
                            RecipientRow(
                                recipient = recipient,
                                selected = recipient.stableId == selectedRecipientId,
                                enabled = effectivePinnedConversationId == null,
                                onClick = { selectedRecipientId = recipient.stableId },
                            )
                        }
                    }
                    if (recipientSections.contacts.isNotEmpty()) {
                        item { RecipientSectionTitle("Contacts") }
                        items(recipientSections.contacts, key = { it.stableId }) { recipient ->
                            RecipientRow(
                                recipient = recipient,
                                selected = recipient.stableId == selectedRecipientId,
                                enabled = effectivePinnedConversationId == null,
                                onClick = { selectedRecipientId = recipient.stableId },
                            )
                        }
                    }
                    if (recipientSections.otherGroups.isNotEmpty()) {
                        item { RecipientSectionTitle("More groups") }
                        items(recipientSections.otherGroups, key = { it.stableId }) { recipient ->
                            RecipientRow(
                                recipient = recipient,
                                selected = recipient.stableId == selectedRecipientId,
                                enabled = effectivePinnedConversationId == null,
                                onClick = { selectedRecipientId = recipient.stableId },
                            )
                        }
                    }
                    if (selectedRecipient != null) {
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
                                recipient = selectedRecipient,
                                batch = batch,
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
private fun RecipientSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun RecipientRow(
    recipient: SharedRecipient,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val chat = (recipient as? SharedRecipient.Conversation)?.chat
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitAvatar(
            recipient.name,
            size = 44.dp,
            online = chat?.online == true,
            avatarUrl = recipient.avatarUrl,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                recipient.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    chat?.isGroup == true -> "Group"
                    recipient is SharedRecipient.Person -> "Kit Pay contact"
                    else -> "Direct conversation"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
    }
}

@Composable
private fun SharePreview(
    recipient: SharedRecipient,
    batch: SharedInboxBatch,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitAvatar(recipient.name, size = 36.dp, avatarUrl = recipient.avatarUrl)
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
            batch.items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        attachmentIcon(item.mediaType),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatAttachmentSize(item.byteCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            batch.text?.takeIf(String::isNotEmpty)?.let { body ->
                if (batch.items.isNotEmpty()) Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun attachmentIcon(mediaType: String) = when {
    mediaType.startsWith("image/") -> Icons.Rounded.Image
    mediaType.startsWith("video/") -> Icons.Rounded.Videocam
    mediaType.startsWith("audio/") -> Icons.Rounded.Mic
    else -> Icons.Rounded.Description
}

private fun formatAttachmentSize(byteCount: Int): String = when {
    byteCount >= 1_024 * 1_024 -> "%.1f MB".format(byteCount / (1_024.0 * 1_024.0))
    byteCount >= 1_024 -> "${byteCount / 1_024} KB"
    else -> "$byteCount bytes"
}
