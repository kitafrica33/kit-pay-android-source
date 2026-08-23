package com.kit.wallet.feature.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.BuildConfig
import com.kit.wallet.R
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.MAX_IMAGE_PLAINTEXT_BYTES
import com.kit.wallet.data.messaging.readBoundedMedia
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitGreen300
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chatId: String,
    onBack: () -> Unit,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setConversationVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.setConversationVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setConversationVisible(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setConversationVisible(false)
        }
    }
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messagingAvailable by viewModel.messagingAvailable.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val retryingMessageId by viewModel.retryingMessageId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val mediaBytes by viewModel.mediaBytes.collectAsStateWithLifecycle()
    val mediaLoading by viewModel.mediaLoading.collectAsStateWithLifecycle()
    val mediaErrors by viewModel.mediaErrors.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.restoredDraft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Every picker/capture result funnels through one bounded reader that hands plaintext
    // ownership to the ViewModel send job (which erases it on completion or failure).
    fun sendPickedMedia(read: suspend () -> Triple<ByteArray, String, String?>) {
        coroutineScope.launch {
            var selectedBytes: ByteArray? = null
            try {
                var mediaType = "application/octet-stream"
                var caption: String? = null
                withContext(Dispatchers.IO + NonCancellable) {
                    val (bytes, type, name) = read()
                    selectedBytes = bytes
                    mediaType = type
                    caption = name
                }
                coroutineContext.ensureActive()
                val owned = checkNotNull(selectedBytes)
                viewModel.sendMedia(owned, mediaType, caption)
                selectedBytes = null // Ownership moved to the ViewModel send job.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewModel.reportMediaSelectionError(
                    error.message ?: "The selected file could not be opened",
                )
            } finally {
                selectedBytes?.fill(0)
            }
        }
    }

    val pickLibraryMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            sendPickedMedia {
                val resolvedType = context.contentResolver.getType(uri).orEmpty().lowercase()
                if (resolvedType.startsWith("video/")) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        it.readBoundedMedia(MAX_IMAGE_PLAINTEXT_BYTES)
                    } ?: error("The selected video could not be opened")
                    val mediaType = KitMediaMessage.normalizeMediaType(resolvedType) ?: "video/mp4"
                    Triple(bytes, mediaType, null)
                } else {
                    val bytes = transcodeChatImage(context.contentResolver, uri)
                        ?: error("The selected photo could not be prepared")
                    Triple(bytes, "image/jpeg", null)
                }
            }
        }
    }
    var captureTarget by remember { mutableStateOf<android.net.Uri?>(null) }
    var captureFile by remember { mutableStateOf<java.io.File?>(null) }
    fun newCaptureTarget(extension: String): android.net.Uri {
        val directory = java.io.File(context.cacheDir, "chat-capture").apply { mkdirs() }
        val file = java.io.File(directory, "capture-${java.util.UUID.randomUUID()}.$extension")
        captureFile = file
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.chatmedia",
            file,
        ).also { captureTarget = it }
    }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = captureTarget
        val file = captureFile
        captureTarget = null
        captureFile = null
        if (saved && target != null) {
            sendPickedMedia {
                try {
                    val bytes = transcodeChatImage(context.contentResolver, target)
                        ?: error("The captured photo could not be prepared")
                    Triple(bytes, "image/jpeg", null)
                } finally {
                    file?.delete()
                }
            }
        } else {
            file?.delete()
        }
    }
    val captureVideoNote = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { saved ->
        val target = captureTarget
        val file = captureFile
        captureTarget = null
        captureFile = null
        if (saved && target != null) {
            sendPickedMedia {
                try {
                    val bytes = context.contentResolver.openInputStream(target)?.use {
                        it.readBoundedMedia(MAX_IMAGE_PLAINTEXT_BYTES)
                    } ?: error("The recorded video could not be opened")
                    Triple(bytes, "video/mp4", null)
                } finally {
                    file?.delete()
                }
            }
        } else {
            file?.delete()
        }
    }
    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            sendPickedMedia {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    it.readBoundedMedia(MAX_IMAGE_PLAINTEXT_BYTES)
                } ?: error("The selected document could not be opened")
                val mediaType = KitMediaMessage.normalizeMediaType(
                    context.contentResolver.getType(uri).orEmpty(),
                ) ?: "application/octet-stream"
                // The wire descriptor has no filename field; the caption carries it (iOS parity).
                val displayName = context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                Triple(bytes, mediaType, displayName?.take(120))
            }
        }
    }
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
        onSendPaymentRequest = viewModel::sendPaymentRequest,
        onPayRequest = viewModel::payPaymentRequest,
        // Dormant-feature guard: the composer hides the affordances, and these keep even a
        // stale composition from opening a picker while the release profile is text-only.
        onAttachLibrary = {
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) {
                pickLibraryMedia.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                    ),
                )
            }
        },
        onAttachCamera = {
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) takePhoto.launch(newCaptureTarget("jpg"))
        },
        onAttachVideoNote = {
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) {
                captureVideoNote.launch(newCaptureTarget("mp4"))
            }
        },
        onAttachDocument = {
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) {
                pickDocument.launch(CHAT_DOCUMENT_MIME_TYPES)
            }
        },
        onSendVoiceNote = { bytes ->
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) {
                viewModel.sendMedia(bytes, VoiceNoteRecorder.Recording.MEDIA_TYPE)
            } else {
                bytes.fill(0)
            }
        },
        mediaEnabled = BuildConfig.MEDIA_MESSAGING_ENABLED,
        mediaBytes = mediaBytes,
        mediaLoading = mediaLoading,
        mediaErrors = mediaErrors,
        onOpenMedia = viewModel::openMedia,
        onRetryMedia = viewModel::retryMedia,
        restoredDraft = restoredDraft,
        onRestoredDraftConsumed = viewModel::consumeRestoredDraft,
        onPersistDraft = viewModel::persistDraft,
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

private const val COMPOSER_DRAFT_PERSIST_DELAY_MILLIS = 600L

/** In-memory plaintext plus a monotonic edit fence for delayed durable-send callbacks. */
internal data class ConversationComposerState(
    val text: String = "",
    val generation: Long = 0L,
) {
    fun edited(value: String): ConversationComposerState = copy(
        text = value,
        generation = Math.incrementExact(generation),
    )

    fun clearIfUnchanged(submitted: ConversationComposerState): ConversationComposerState =
        if (generation == submitted.generation && text == submitted.text) edited("") else this
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
    onSendPaymentRequest: (Long, String?, () -> Unit) -> Unit = { _, _, done -> done() },
    onPayRequest: (Message, String, () -> Unit) -> Unit = { _, _, done -> done() },
    onAttachLibrary: () -> Unit = {},
    onAttachCamera: () -> Unit = {},
    onAttachVideoNote: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onSendVoiceNote: (ByteArray) -> Unit = {},
    mediaEnabled: Boolean = false,
    mediaBytes: Map<String, ByteArray> = emptyMap(),
    mediaLoading: Set<String> = emptySet(),
    mediaErrors: Map<String, String> = emptyMap(),
    onOpenMedia: (Message) -> Unit = {},
    onRetryMedia: (Message) -> Unit = {},
    restoredDraft: String? = null,
    onRestoredDraftConsumed: () -> Unit = {},
    onPersistDraft: (String) -> Unit = {},
) {
    // Message plaintext must not enter the Activity saved-instance-state bundle. Instead, the
    // composer is continuously mirrored into the hardware-encrypted messaging draft store, which
    // restores it across rotation and process death and is erased with the messaging state.
    var composerState by remember { mutableStateOf(ConversationComposerState()) }
    LaunchedEffect(restoredDraft) {
        val restored = restoredDraft ?: return@LaunchedEffect
        if (restored.isNotBlank() && composerState.text.isEmpty()) {
            composerState = composerState.edited(restored)
        }
        onRestoredDraftConsumed()
    }
    LaunchedEffect(Unit) {
        snapshotFlow { composerState.text }
            .drop(1)
            .collectLatest { text ->
                // collectLatest gives debounce semantics: rapid keystrokes cancel the pending
                // write and only the settled composer text reaches the encrypted store.
                delay(COMPOSER_DRAFT_PERSIST_DELAY_MILLIS)
                onPersistDraft(text)
            }
    }
    var showRequestDialog by remember { mutableStateOf(false) }
    var payTarget by remember { mutableStateOf<Message?>(null) }
    val retryableMessageIds = remember(messages) {
        retryableOutgoingMessageIds(messages)
    }
    // Request cards stop offering Pay once this thread carries a paid confirmation for them.
    val settledRequestIds = remember(messages) {
        messages.mapNotNullTo(mutableSetOf()) { message ->
            message.paymentRequestId
                ?.lowercase()
                ?.takeIf { message.kind == MessageKind.PAYMENT }
        }
    }

    // Keep the newest message visible, just like WhatsApp: jump to the bottom the first time the
    // thread loads, then smoothly follow every message that is sent or arrives afterwards.
    val listState = rememberLazyListState()
    var renderedMessageCount by remember { mutableStateOf(0) }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) {
            renderedMessageCount = 0
            return@LaunchedEffect
        }
        // The list header adds two leading items (date + encryption notice) and a trailing spacer,
        // so the newest message sits just above the final index.
        val bottomIndex = messages.size + 2
        if (renderedMessageCount == 0) {
            listState.scrollToItem(bottomIndex)
        } else if (messages.size > renderedMessageCount) {
            listState.animateScrollToItem(bottomIndex)
        }
        renderedMessageCount = messages.size
    }

    if (showRequestDialog) {
        PaymentRequestDialog(
            sending = sending,
            onDismiss = { showRequestDialog = false },
            onRequest = { amountMinor, note ->
                onSendPaymentRequest(amountMinor, note) { showRequestDialog = false }
            },
        )
    }
    payTarget?.let { target ->
        PaymentPinDialog(
            amountText = Money.format(
                abs(target.amountMinor),
                target.paymentCurrencyCode,
                target.paymentCurrencyScale,
            ),
            sending = sending,
            onDismiss = { payTarget = null },
            onConfirm = { pin ->
                onPayRequest(target, pin) { payTarget = null }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KitAvatar(chat.name, size = 40.dp, online = chat.online, avatarUrl = chat.avatarUrl)
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
                    draft = composerState.text,
                    onDraft = {
                        composerState = composerState.edited(it)
                        if (error != null) onClearError()
                    },
                    onSend = {
                        val submitted = composerState
                        onSend(submitted.text) {
                            composerState = composerState.clearIfUnchanged(submitted)
                        }
                    },
                    onAttachLibrary = onAttachLibrary,
                    onAttachCamera = onAttachCamera,
                    onAttachVideoNote = onAttachVideoNote,
                    onAttachDocument = onAttachDocument,
                    onSendVoiceNote = onSendVoiceNote,
                    mediaEnabled = mediaEnabled,
                    onRequestPayment = {
                        if (error != null) onClearError()
                        showRequestDialog = true
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
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
                if (message.kind == MessageKind.CALL) {
                    CallLogBubble(
                        msg = message,
                        onCall = {
                            message.callDirection?.let {
                                chat.peerUserId?.let { peer ->
                                    if (message.callVideo) onVideoCall(peer) else onVoiceCall(peer)
                                }
                            }
                        },
                    )
                } else {
                    MessageBubble(
                        msg = message,
                        operationInFlight = sending,
                        retrying = retryingMessageId == message.id,
                        retryEnabled = message.id in retryableMessageIds,
                        onRetry = {
                            val submitted = composerState
                            onRetry(message) {
                                if (submitted.text.trim() == message.text) {
                                    composerState = composerState.clearIfUnchanged(submitted)
                                }
                            }
                        },
                        mediaBytes = mediaBytes[message.id],
                        mediaLoading = message.id in mediaLoading,
                        mediaError = mediaErrors[message.id],
                        onOpenMedia = { onOpenMedia(message) },
                        onRetryMedia = { onRetryMedia(message) },
                        requestSettled = message.paymentRequestId?.lowercase() in settledRequestIds,
                        onPayRequest = { payTarget = message },
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    msg: Message,
    operationInFlight: Boolean,
    retrying: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    mediaBytes: ByteArray? = null,
    mediaLoading: Boolean = false,
    mediaError: String? = null,
    onOpenMedia: () -> Unit = {},
    onRetryMedia: () -> Unit = {},
    requestSettled: Boolean = false,
    onPayRequest: () -> Unit = {},
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

    // Copyable plaintext exists only for ordinary text and media captions; payment cards,
    // call logs and raw descriptors are deliberately not exposed to the clipboard.
    val copyableText = when (msg.kind) {
        MessageKind.TEXT -> msg.text
        MessageKind.IMAGE, MessageKind.VIDEO, MessageKind.VOICE_NOTE, MessageKind.DOCUMENT ->
            msg.text.takeIf {
                it.isNotBlank() && it !in setOf("Photo", "Voice note", "Video", "Document")
            }
        else -> null
    }
    var copyMenuOpen by remember(msg.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(if (msg.fromMe) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(bottom = if (msg.reactions.isEmpty()) 8.dp else 18.dp)
                .widthIn(max = 300.dp),
        ) {
            DropdownMenu(expanded = copyMenuOpen, onDismissRequest = { copyMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        copyMenuOpen = false
                        copyableText?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                )
            }
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                shadowElevation = 1.dp,
                modifier = if (copyableText != null) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { copyMenuOpen = true },
                    )
                } else {
                    Modifier
                },
            ) {
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
                        MessageKind.PAYMENT_REQUEST -> PaymentRequestChatCard(
                            msg = msg,
                            settled = requestSettled,
                            payEnabled = !operationInFlight && !requestSettled,
                            onPay = onPayRequest,
                        )
                        MessageKind.VOICE_NOTE -> SecureVoiceNoteContent(
                            msg = msg,
                            mediaBytes = mediaBytes,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                        )
                        MessageKind.VIDEO -> SecureVideoContent(
                            msg = msg,
                            mediaBytes = mediaBytes,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                        )
                        MessageKind.DOCUMENT -> SecureDocumentContent(
                            msg = msg,
                            mediaBytes = mediaBytes,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                        )
                        MessageKind.IMAGE -> SecureImageContent(
                            msg = msg,
                            mediaBytes = mediaBytes,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                        )
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
                            (retrying || msg.state in setOf(
                                DeliveryState.RETRY_REQUIRED,
                                DeliveryState.FAILED,
                            ))
                        ) {
                            Text(
                                when {
                                    retrying -> "Retrying…"
                                    retryEnabled -> "Not sent · Retry"
                                    msg.state == DeliveryState.FAILED ->
                                        "Photo expired · Send again"
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
                        if (
                            msg.fromMe &&
                            msg.state in setOf(
                                DeliveryState.SENDING,
                                DeliveryState.SENT,
                                DeliveryState.DELIVERED,
                                DeliveryState.READ,
                            )
                        ) {
                            Text(
                                outgoingDeliveryLabel(msg.state),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (msg.state == DeliveryState.READ) {
                                    KitTheme.colors.readReceipt
                                } else {
                                    contentColor.copy(alpha = 0.65f)
                                },
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            msg.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.65f),
                        )
                        if (
                            msg.fromMe &&
                            msg.state !in setOf(
                                DeliveryState.RETRY_REQUIRED,
                                DeliveryState.FAILED,
                            )
                        ) {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                // Clock while sending, one tick when sent, two ticks once
                                // delivered, and two blue ticks once the peer has read it.
                                when (msg.state) {
                                    DeliveryState.SENDING -> Icons.Rounded.Schedule
                                    DeliveryState.SENT -> Icons.Rounded.Done
                                    else -> Icons.Rounded.DoneAll
                                },
                                // The adjacent visible status text already owns accessibility
                                // semantics; announcing the decorative tick repeats every receipt.
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = when (msg.state) {
                                    DeliveryState.READ -> KitTheme.colors.readReceipt
                                    DeliveryState.DELIVERED -> contentColor.copy(alpha = 0.75f)
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

internal fun outgoingDeliveryLabel(state: DeliveryState): String = when (state) {
    DeliveryState.SENDING -> "Pending"
    DeliveryState.SENT -> "Sent"
    DeliveryState.DELIVERED -> "Delivered"
    DeliveryState.READ -> "Read"
    DeliveryState.RETRY_REQUIRED -> "Not sent"
    DeliveryState.FAILED -> "Photo expired"
}

/**
 * A stale roster retry remains visible for audit, but must stop being actionable after a newer
 * outgoing copy of the same authenticated text exists. Otherwise tapping the old bubble after a
 * successful fresh encryption would send a duplicate. The newest unresolved copy stays retryable.
 */
internal fun retryableOutgoingMessageIds(messages: List<Message>): Set<String> = buildSet {
    messages.forEachIndexed { index, message ->
        // Media messages compare their authenticated descriptor, not their shared display caption.
        val messageContent = message.mediaDescriptor ?: message.text
        if (
            message.fromMe &&
            message.state in setOf(DeliveryState.SENDING, DeliveryState.RETRY_REQUIRED) &&
            messages.subList(index + 1, messages.size).none { newer ->
                newer.fromMe && (newer.mediaDescriptor ?: newer.text) == messageContent
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
                    Money.format(abs(msg.amountMinor), msg.paymentCurrencyCode, msg.paymentCurrencyScale),
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

/**
 * A payment request shared inside the conversation. The requester sees a summary; the payer sees
 * a Pay action that opens the wallet-PIN confirmation before any debit happens.
 */
@Composable
private fun PaymentRequestChatCard(
    msg: Message,
    settled: Boolean,
    payEnabled: Boolean,
    onPay: () -> Unit,
) {
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
                    when {
                        settled -> "Payment request • Paid"
                        msg.fromMe -> "Payment request sent"
                        else -> "Payment request"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Text(
                    Money.format(abs(msg.amountMinor), msg.paymentCurrencyCode, msg.paymentCurrencyScale),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        msg.paymentNote?.takeIf(String::isNotBlank)?.let { note ->
            Spacer(Modifier.height(6.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        if (!msg.fromMe && !settled) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(KitGreen300, MaterialTheme.shapes.small)
                    .clickable(enabled = payEnabled, onClick = onPay)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Pay ${Money.format(abs(msg.amountMinor), msg.paymentCurrencyCode, msg.paymentCurrencyScale)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0B2B1A),
                )
            }
        }
    }
}

/** Collects the amount and optional note for an in-chat payment request. */
@Composable
private fun PaymentRequestDialog(
    sending: Boolean,
    onDismiss: () -> Unit,
    onRequest: (Long, String?) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amountText)
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Request a payment") },
        text = {
            Column {
                Text(
                    "The request is shared securely in this chat. Money moves only when they approve it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    enabled = !sending,
                    label = { Text("Amount (${Money.SYMBOL})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(140) },
                    enabled = !sending,
                    label = { Text("Note (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && (amountMinor ?: 0L) > 0L,
                onClick = { onRequest(checkNotNull(amountMinor), note.trim().ifBlank { null }) },
            ) { Text(if (sending) "Sending…" else "Request") }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Approves an in-chat payment with biometrics (when advertised) or the four-digit wallet PIN. */
@Composable
private fun PaymentPinDialog(
    amountText: String,
    sending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Pay $amountText") },
        text = {
            Column {
                Text(
                    "Approve this payment with your wallet PIN, or leave it blank to use biometrics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter(Char::isDigit).take(4) },
                    enabled = !sending,
                    label = { Text("Wallet PIN (optional with biometrics)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && (pin.isEmpty() || pin.length == 4),
                onClick = { onConfirm(pin) },
            ) { Text(if (sending) "Paying…" else "Pay") }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A call-log entry shown inline in the conversation, like a WhatsApp call bubble: a directional
 * icon, the call type (and "Missed" when it went unanswered), the time and connected duration, and
 * a tap target to call the person back.
 */
@Composable
private fun CallLogBubble(msg: Message, onCall: () -> Unit) {
    val colors = KitTheme.colors
    val missed = msg.callDirection == CallDirection.MISSED
    val contentColor = if (msg.fromMe) colors.onChatBubbleMe else colors.onChatBubbleOther
    val (directionIcon, directionTint) = when (msg.callDirection) {
        CallDirection.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade to colors.success
        CallDirection.MISSED -> Icons.AutoMirrored.Rounded.CallMissed to MaterialTheme.colorScheme.error
        else -> Icons.AutoMirrored.Rounded.CallReceived to colors.success
    }
    val title = buildString {
        if (missed) append("Missed ")
        append(if (msg.callVideo) "video call" else "voice call")
    }.replaceFirstChar { it.uppercase() }
    val subtitle = if (msg.callDurationSeconds > 0) {
        "${msg.time} · ${formatCallDuration(msg.callDurationSeconds)}"
    } else {
        msg.time
    }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            color = if (msg.fromMe) colors.chatBubbleMe else colors.chatBubbleOther,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 1.dp,
            modifier = Modifier
                .align(if (msg.fromMe) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(vertical = 4.dp)
                .clickable(onClick = onCall),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    directionIcon,
                    contentDescription = null,
                    tint = directionTint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (missed) MaterialTheme.colorScheme.error else contentColor,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(18.dp))
                Icon(
                    if (msg.callVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                    contentDescription = "Call back",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun formatCallDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

/**
 * An end-to-end encrypted photo bubble. A user tap starts its serialized ciphertext download;
 * decrypted bytes render entirely in memory and nothing is written to disk in plaintext.
 */
@Composable
private fun SecureImageContent(
    msg: Message,
    mediaBytes: ByteArray?,
    mediaLoading: Boolean,
    mediaError: String?,
    onOpenMedia: () -> Unit,
    onRetryMedia: () -> Unit,
) {
    var bitmap by remember(mediaBytes) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var decodeFailed by remember(mediaBytes) { mutableStateOf(false) }
    var decoding by remember(mediaBytes) { mutableStateOf(mediaBytes != null) }
    LaunchedEffect(mediaBytes) {
        bitmap = null
        decodeFailed = false
        decoding = mediaBytes != null
        if (mediaBytes != null) {
            bitmap = withOwnedSecureMediaSnapshot(mediaBytes) { ownedBytes ->
                withContext(Dispatchers.Default) {
                    secureImageDecodeMutex.withLock {
                        decodeBoundedSecureImage(ownedBytes)
                    }
                }
            }
            decodeFailed = bitmap == null
            decoding = false
        }
    }
    val displayError = mediaError ?: if (decodeFailed) {
        "The secure photo could not be decoded safely"
    } else {
        null
    }
    val renderedBitmap = bitmap
    Column {
        when {
            renderedBitmap != null -> Image(
                bitmap = renderedBitmap,
                contentDescription = "Encrypted photo",
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
            displayError != null -> Column {
                Text(
                    displayError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Tap to retry",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onRetryMedia),
                )
            }
            mediaLoading || decoding -> Box(
                Modifier
                    .size(width = 220.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            mediaBytes == null -> Box(
                Modifier
                    .size(width = 220.dp, height = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .clickable(onClick = onOpenMedia),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Tap to load secure photo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Text(
                "The secure photo is unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (msg.text.isNotBlank() && msg.text != "Photo" && msg.text != "📷 Photo") {
            Text(
                msg.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Pins an ownership-safe plaintext snapshot for a decoder and erases it on success, failure or
 * cancellation. The cache may therefore evict/zero its own array without mutating in-flight input.
 */
internal suspend fun <T> withOwnedSecureMediaSnapshot(
    cachedBytes: ByteArray,
    block: suspend (ByteArray) -> T,
): T {
    val owned = cachedBytes.copyOf()
    return try {
        block(owned)
    } finally {
        owned.fill(0)
    }
}

internal fun decodeBoundedSecureImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_IMAGE_DIMENSION ||
            height > MAX_SOURCE_IMAGE_DIMENSION
        ) {
            null
        } else {
            var sampleSize = 1
            while (
                width / sampleSize > MAX_RENDERED_IMAGE_DIMENSION ||
                height / sampleSize > MAX_RENDERED_IMAGE_DIMENSION ||
                (width.toLong() / sampleSize) * (height.toLong() / sampleSize) >
                MAX_RENDERED_IMAGE_PIXELS
            ) {
                sampleSize = Math.multiplyExact(sampleSize, 2)
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    } catch (_: RuntimeException) {
        // Malformed or unsupported decoder input is rendered through the existing retry/error UI.
        null
    }

/** SAF document mime filter mirroring the kit-media-v1 document set (plus the octet fallback). */
private val CHAT_DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/zip",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain",
    "text/csv",
    "application/octet-stream",
)

private const val MAX_SOURCE_IMAGE_DIMENSION = 32_768
private const val MAX_RENDERED_IMAGE_DIMENSION = 4_096
private const val MAX_RENDERED_IMAGE_PIXELS = 4_000_000L
private val secureImageDecodeMutex = Mutex()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttachLibrary: () -> Unit = {},
    onAttachCamera: () -> Unit = {},
    onAttachVideoNote: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onSendVoiceNote: (ByteArray) -> Unit = {},
    onVoiceNoteTooShort: () -> Unit = {},
    mediaEnabled: Boolean = false,
    onRequestPayment: () -> Unit = {},
) {
    val context = LocalContext.current
    val recorder = remember { VoiceNoteRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var recordingElapsedMillis by remember { mutableStateOf(0L) }
    var recordingLevel by remember { mutableStateOf(0f) }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching { recorder.start() }
                .onSuccess { recording = true }
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            recordingElapsedMillis = recorder.elapsedMillis()
            recordingLevel = recorder.level()
            if (recordingElapsedMillis >=
                com.kit.wallet.data.messaging.KitChatMediaLimits.VOICE_NOTE_MAX_DURATION_MILLIS
            ) {
                recorder.finish()?.let { onSendVoiceNote(it.bytes) }
                recording = false
            }
            delay(80)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
        }
    }
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
            if (recording) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        recorder.cancel()
                        recording = false
                    }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Discard recording",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatVoiceNoteTime(recordingElapsedMillis),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(10.dp))
                    RecorderLevelWave(
                        level = recordingLevel,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The composer is never disabled while a send is in flight: text goes into
                    // the thread instantly and the user keeps typing without waiting.
                    TextField(
                        value = draft,
                        onValueChange = onDraft,
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
                    if (mediaEnabled) {
                        Box {
                            IconButton(onClick = { attachMenuOpen = true }) {
                                Icon(
                                    Icons.Rounded.AttachFile,
                                    contentDescription = "Attachments",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = attachMenuOpen,
                                onDismissRequest = { attachMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Photo & video library") },
                                    leadingIcon = { Icon(Icons.Rounded.Photo, null) },
                                    onClick = {
                                        attachMenuOpen = false
                                        onAttachLibrary()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Camera") },
                                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                                    onClick = {
                                        attachMenuOpen = false
                                        onAttachCamera()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Video note") },
                                    leadingIcon = { Icon(Icons.Rounded.Videocam, null) },
                                    onClick = {
                                        attachMenuOpen = false
                                        onAttachVideoNote()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Document") },
                                    leadingIcon = { Icon(Icons.Rounded.Description, null) },
                                    onClick = {
                                        attachMenuOpen = false
                                        onAttachDocument()
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onRequestPayment) {
                        Icon(
                            Icons.Rounded.Payments,
                            contentDescription = "Request a payment",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (recording) {
            Box(
                Modifier
                    .size(50.dp)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    .clickable {
                        val finished = recorder.finish()
                        recording = false
                        if (finished != null) onSendVoiceNote(finished.bytes) else onVoiceNoteTooShort()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Send voice note",
                    tint = MaterialTheme.colorScheme.onSecondary,
                )
            }
        } else if (draft.isBlank() && mediaEnabled) {
            Box(
                Modifier
                    .size(50.dp)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    .clickable {
                        recordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = "Record a voice note",
                    tint = MaterialTheme.colorScheme.onSecondary,
                )
            }
        } else {
            Box(
                Modifier
                    .size(50.dp)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    .clickable(enabled = draft.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
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

/** Scrolling live-input wave for the recording bar, sized like the iOS `RecorderLevelWave`. */
@Composable
private fun RecorderLevelWave(level: Float, modifier: Modifier = Modifier) {
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level) {
        history.add(level)
        if (history.size > 28) history.removeAt(0)
    }
    val accent = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f)
    Canvas(modifier.height(24.dp)) {
        val barWidth = 2.6.dp.toPx()
        val spacing = 2.4.dp.toPx()
        history.takeLast(28).forEachIndexed { index, value ->
            val barHeight = 4.dp.toPx() + value * 18.dp.toPx()
            drawRoundRect(
                color = accent,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = index * (barWidth + spacing),
                    y = (size.height - barHeight) / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
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
