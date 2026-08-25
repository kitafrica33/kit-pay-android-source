package com.kit.wallet.feature.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.ArrowDownward
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
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
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
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.MAX_IMAGE_PLAINTEXT_BYTES
import com.kit.wallet.data.messaging.readBoundedMedia
import com.kit.wallet.data.repository.AbuseReportContext
import com.kit.wallet.data.repository.AbuseReportSelectionPolicy
import com.kit.wallet.data.repository.AbuseReportTarget
import com.kit.wallet.feature.auth.PaymentApproval
import com.kit.wallet.feature.auth.rememberBiometricApprovalAvailable
import com.kit.wallet.feature.funding.TopUpSheet
import com.kit.wallet.feature.funding.TopUpViewModel
import com.kit.wallet.feature.chat.camera.CameraPull
import com.kit.wallet.feature.chat.camera.KitChatCameraFlow
import com.kit.wallet.ui.components.GroupedAmountTransformation
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.kitNameAccent
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
import com.kit.wallet.ui.model.TopUpRequirement
import com.kit.wallet.ui.model.acceptsReactions
import com.kit.wallet.ui.theme.KitGreen300
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
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

private data class PendingAbuseReport(
    val target: AbuseReportTarget,
    val reportedName: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chatId: String,
    claimableTransfersEnabled: Boolean,
    abuseReportingEnabled: Boolean = false,
    onBack: () -> Unit,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    /** The group's own screen — participants, roles, and the way out of it. */
    onOpenGroup: (String) -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
    topUp: TopUpViewModel = hiltViewModel(),
    abuseReports: AbuseReportViewModel = hiltViewModel(),
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
    val transferClaims by viewModel.transferClaims.collectAsStateWithLifecycle()
    val groupMembers by viewModel.groupMembers.collectAsStateWithLifecycle()
    val refusedForFunds by viewModel.topUpRequired.collectAsStateWithLifecycle()
    val topUpRequirement by topUp.requirement.collectAsStateWithLifecycle()
    val abuseReportState by abuseReports.state.collectAsStateWithLifecycle()
    LaunchedEffect(refusedForFunds) {
        val shortfall = refusedForFunds ?: return@LaunchedEffect
        topUp.start(shortfall)
        viewModel.clearTopUpRequired()
    }
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
    // The in-app CameraX flow replaced the platform capture intents: tap for a photo, hold for
    // a video, edit, then send. CameraX itself still requires CAMERA to be granted before it
    // binds; RECORD_AUDIO is requested alongside so held recordings carry sound, and a refusal
    // only mutes them.
    var cameraFlowOpen by remember { mutableStateOf(false) }
    fun capturePermissionGranted(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    val capturePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // The camera may not have been part of this request (already granted); audio alone
        // being refused only mutes recordings.
        val cameraGranted = grants[android.Manifest.permission.CAMERA]
            ?: capturePermissionGranted(android.Manifest.permission.CAMERA)
        if (cameraGranted) {
            cameraFlowOpen = true
        } else {
            viewModel.reportMediaSelectionError("Camera access is needed to capture media.")
        }
    }
    fun openCameraFlow() {
        val missing = buildList {
            if (!capturePermissionGranted(android.Manifest.permission.CAMERA)) {
                add(android.Manifest.permission.CAMERA)
            }
            if (!capturePermissionGranted(android.Manifest.permission.RECORD_AUDIO)) {
                add(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missing.isEmpty()) {
            cameraFlowOpen = true
        } else {
            capturePermissions.launch(missing.toTypedArray())
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
    // Readiness is never a reason to withhold a conversation. The transcript comes from this
    // device's own encrypted store and is readable long before — and independently of — the
    // session that would let a new message be sent; a blip during key revalidation or roster
    // resync leaves the open chat exactly where it was. What readiness gates is the composer,
    // and the composer says so itself.
    if (currentChat == null) {
        SecureConversationLoading(onBack)
        return
    }
    val reportContext = remember(currentChat, viewModel.currentAccountId, groupMembers) {
        AbuseReportContext.create(viewModel.currentAccountId, currentChat, groupMembers)
    }
    val accountReportTarget = remember(currentChat, reportContext) {
        reportContext?.let { AbuseReportSelectionPolicy.accountTarget(it, currentChat) }
    }
    var pendingAbuseReport by remember(currentChat.id) {
        mutableStateOf<PendingAbuseReport?>(null)
    }
    val reportableMessageIds = remember(messages, reportContext, abuseReportingEnabled) {
        val safeContext = reportContext
        if (!abuseReportingEnabled || safeContext == null) {
            emptySet()
        } else {
            messages.mapNotNull { message ->
                AbuseReportSelectionPolicy.messageTarget(message, safeContext)?.messageId
            }.toSet()
        }
    }
    pendingAbuseReport?.let { pending ->
        reportContext?.let { safeContext ->
            AbuseReportDialog(
                reportedName = pending.reportedName,
                context = safeContext,
                target = pending.target,
                messages = messages,
                reportingAvailable = abuseReportingEnabled,
                state = abuseReportState,
                onSubmit = { request ->
                    abuseReports.submit(request, reportingAvailable = abuseReportingEnabled)
                },
                onDismiss = {
                    if (!abuseReportState.submitting) {
                        pendingAbuseReport = null
                        abuseReports.clearPresentation()
                    }
                },
            )
        }
    }
    var galleryMessageId by remember { mutableStateOf<String?>(null) }
    galleryMessageId?.let { openedId ->
        ConversationMediaGallery(
            chatName = currentChat.name,
            mediaMessages = messages.filter { it.kind in GALLERY_MEDIA_KINDS },
            initialMessageId = openedId,
            mediaBytes = mediaBytes,
            mediaLoading = mediaLoading,
            mediaErrors = mediaErrors,
            onLoad = viewModel::openMedia,
            onRetry = viewModel::retryMedia,
            onDismiss = { galleryMessageId = null },
            reactionsEnabled = messagingAvailable,
            onToggleReaction = { message, emoji ->
                viewModel.toggleReaction(message.id, emoji)
            },
            reportableMessageIds = reportableMessageIds,
            onReportMessage = { message ->
                reportContext?.let { safeContext ->
                    AbuseReportSelectionPolicy.messageTarget(message, safeContext)?.let {
                        galleryMessageId = null
                        pendingAbuseReport = PendingAbuseReport(
                            target = it,
                            reportedName = message.senderName?.takeIf(String::isNotBlank)
                                ?: currentChat.name.takeUnless { currentChat.isGroup }
                                ?: "this account",
                        )
                    }
                }
            },
        )
    }
    if (cameraFlowOpen) {
        KitChatCameraFlow(
            maxTransferBytes = viewModel.captureByteLimit(),
            onDismiss = { cameraFlowOpen = false },
            onSendMedia = { bytes, mediaType, caption ->
                viewModel.sendMedia(bytes, mediaType, caption)
            },
            onError = viewModel::reportMediaSelectionError,
        )
    }
    ConversationContent(
        chat = currentChat,
        messages = messages,
        onBack = onBack,
        onVoiceCall = onVoiceCall,
        onVideoCall = onVideoCall,
        onOpenGroup = { onOpenGroup(currentChat.id) },
        sending = sending,
        retryingMessageId = retryingMessageId,
        error = error,
        onClearError = viewModel::clearError,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        reactionsEnabled = messagingAvailable,
        sendEnabled = messagingAvailable,
        onToggleReaction = { message, emoji ->
            viewModel.toggleReaction(message.id, emoji)
        },
        abuseReportingAvailable = abuseReportingEnabled && accountReportTarget != null,
        reportableMessageIds = reportableMessageIds,
        onReportAccount = {
            accountReportTarget?.let {
                pendingAbuseReport = PendingAbuseReport(it, currentChat.name)
            }
        },
        onReportMessage = { message ->
            reportContext?.let { safeContext ->
                AbuseReportSelectionPolicy.messageTarget(message, safeContext)?.let {
                    pendingAbuseReport = PendingAbuseReport(
                        target = it,
                        reportedName = message.senderName?.takeIf(String::isNotBlank)
                            ?: currentChat.name.takeUnless { currentChat.isGroup }
                            ?: "this account",
                    )
                }
            }
        },
        onSendPaymentRequest = viewModel::sendPaymentRequest,
        onPayRequest = viewModel::payPaymentRequest,
        shortfallForPaymentRequest = viewModel::shortfallForPaymentRequest,
        topUpRequirement = topUpRequirement,
        onTopUpNeeded = topUp::start,
        topUpSheet = { onFunded ->
            TopUpSheet(
                viewModel = topUp,
                onDismiss = topUp::dismiss,
                onFunded = {
                    topUp.dismiss()
                    onFunded()
                },
            )
        },
        biometricsAvailable = rememberBiometricApprovalAvailable(),
        onDeclineRequest = { message -> viewModel.declinePaymentRequest(message) },
        onCancelRequest = { message -> viewModel.cancelPaymentRequest(message) },
        claimableTransfersEnabled = claimableTransfersEnabled,
        currentAccountId = viewModel.currentAccountId,
        transferClaims = transferClaims,
        onAcceptTransfer = { message ->
            viewModel.acceptTransfer(message, claimableTransfersEnabled)
        },
        onRejectTransfer = { message, reason ->
            viewModel.rejectTransfer(message, reason, claimableTransfersEnabled)
        },
        onReverseTransfer = { message, reason, pin ->
            viewModel.reverseTransfer(message, reason, pin, claimableTransfersEnabled)
        },
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
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) openCameraFlow()
        },
        onAttachVideoNote = {
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) openCameraFlow()
        },
        onOpenCamera = {
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) openCameraFlow()
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
        onOpenViewer = { message -> galleryMessageId = message.id },
        restoredDraft = restoredDraft,
        onRestoredDraftConsumed = viewModel::consumeRestoredDraft,
        onPersistDraft = viewModel::persistDraft,
        onComposerChanged = viewModel::onComposerChanged,
        schedulingEnabled = viewModel.schedulingAvailable && messagingAvailable,
        onScheduleSend = viewModel::scheduleSend,
        onSchedulePaymentRequest = viewModel::schedulePaymentRequest,
        onSendScheduledNow = viewModel::sendScheduledNow,
        onRescheduleSend = viewModel::rescheduleSend,
        onCancelScheduledSend = viewModel::cancelScheduledSend,
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

private const val COMPOSER_DRAFT_PERSIST_DELAY_MILLIS = 600L

/** In-memory plaintext plus a monotonic edit fence for delayed durable-send callbacks. */
internal data class ConversationComposerState(
    val text: String = "",
    val generation: Long = 0L,
    /**
     * The [generation] already handed to the send path and not yet resolved, if any.
     *
     * This is what stops a double-tap from sending the same line twice. The composer deliberately
     * keeps its text until the send is *durably committed* — a send that fails before that point
     * must not lose what the user typed — which leaves a window where Send is still armed over
     * content that is already on its way. Two taps in that window used to produce two messages,
     * because the repository correctly treats every call as a distinct message and has no way to
     * tell a fat-fingered second tap from a deliberate second "ok".
     *
     * Keying the fence to the generation rather than to the text is what preserves that
     * distinction: retyping re-arms Send, so genuinely sending the same words twice still works.
     */
    val submittedGeneration: Long? = null,
) {
    fun edited(value: String): ConversationComposerState = copy(
        text = value,
        generation = Math.incrementExact(generation),
        submittedGeneration = null,
    )

    /**
     * The state to hold while this exact content is in flight, or null if it already is — in
     * which case there is nothing to send and the tap is dropped.
     */
    fun submitted(): ConversationComposerState? =
        if (text.isBlank() || submittedGeneration == generation) {
            null
        } else {
            copy(submittedGeneration = generation)
        }

    /**
     * Re-arms Send after an attempt that ended without committing.
     *
     * A send that fails before the durable boundary always surfaces an error and always keeps the
     * composer's text, so that error is precisely the moment the outstanding submission is known
     * to be over and the next tap is a real retry rather than a duplicate.
     */
    fun releasedForRetry(): ConversationComposerState =
        if (submittedGeneration == null) this else copy(submittedGeneration = null)

    fun clearIfUnchanged(submitted: ConversationComposerState): ConversationComposerState =
        if (generation == submitted.generation && text == submitted.text) edited("") else this
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationContent(
    chat: ChatPreview,
    messages: List<Message>,
    onBack: () -> Unit,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    onOpenGroup: () -> Unit = {},
    sending: Boolean,
    retryingMessageId: String?,
    error: String?,
    onClearError: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
    onRetry: (Message, () -> Unit) -> Unit,
    onSendPaymentRequest: (Long, String?, () -> Unit) -> Unit = { _, _, done -> done() },
    onPayRequest: (Message, String, () -> Unit) -> Unit = { _, _, done -> done() },
    shortfallForPaymentRequest: (Message) -> TopUpRequirement? = { null },
    topUpRequirement: TopUpRequirement? = null,
    onTopUpNeeded: (TopUpRequirement) -> Unit = {},
    topUpSheet: (@Composable (onFunded: () -> Unit) -> Unit)? = null,
    biometricsAvailable: Boolean = false,
    onDeclineRequest: (Message) -> Unit = {},
    onCancelRequest: (Message) -> Unit = {},
    claimableTransfersEnabled: Boolean = false,
    currentAccountId: String? = null,
    transferClaims: Map<String, TransferClaim> = emptyMap(),
    onAcceptTransfer: (Message) -> Unit = {},
    onRejectTransfer: (Message, String?) -> Unit = { _, _ -> },
    onReverseTransfer: (Message, String?, String) -> Unit = { _, _, _ -> },
    onAttachLibrary: () -> Unit = {},
    onAttachCamera: () -> Unit = {},
    onAttachVideoNote: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onSendVoiceNote: (ByteArray) -> Unit = {},
    mediaEnabled: Boolean = false,
    mediaBytes: Map<String, ByteArray> = emptyMap(),
    mediaLoading: Set<String> = emptySet(),
    mediaErrors: Map<String, String> = emptyMap(),
    onOpenMedia: (Message) -> Unit = {},
    onRetryMedia: (Message) -> Unit = {},
    onOpenViewer: (Message) -> Unit = {},
    reactionsEnabled: Boolean = false,
    onToggleReaction: (Message, String) -> Unit = { _, _ -> },
    abuseReportingAvailable: Boolean = false,
    reportableMessageIds: Set<String> = emptySet(),
    onReportAccount: () -> Unit = {},
    onReportMessage: (Message) -> Unit = {},
    /**
     * Whether the secure session can carry a message right now.
     *
     * Defaults to [reactionsEnabled] because both answer the same question — can this device
     * exchange? — and a preview or test that sets one has always meant the other.
     */
    sendEnabled: Boolean = reactionsEnabled,
    restoredDraft: String? = null,
    onRestoredDraftConsumed: () -> Unit = {},
    onPersistDraft: (String) -> Unit = {},
    onComposerChanged: (String) -> Unit = {},
    /** Whether this build can hold a message or a request back until a chosen time. */
    schedulingEnabled: Boolean = false,
    onScheduleSend: (String, Long, () -> Unit) -> Unit = { _, _, done -> done() },
    onSchedulePaymentRequest: (Long, String?, Long, () -> Unit) -> Unit = { _, _, _, done -> done() },
    onSendScheduledNow: (Message) -> Unit = {},
    onRescheduleSend: (Message, Long) -> Unit = { _, _ -> },
    onCancelScheduledSend: (Message) -> Unit = {},
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
    LaunchedEffect(error) {
        // The only way a send ends without releasing the composer is by failing before it was
        // durably committed, and that always reports an error. Re-arming Send here is therefore
        // exactly as permissive as it should be: retry after a failure, never a duplicate.
        if (error != null) composerState = composerState.releasedForRetry()
    }
    LaunchedEffect(Unit) {
        // Deliberately undebounced here, and deliberately not folded into the draft collector
        // above: `collectLatest` only lets *settled* text through, which would never refresh the
        // peer's bubble while someone is actually typing. The 300 ms debounce, the 4 s throttle
        // and stop-on-conversation-switch all live in KitTypingSignaller.
        snapshotFlow { composerState.text }.drop(1).collect(onComposerChanged)
    }
    var showRequestDialog by remember { mutableStateOf(false) }
    var payTarget by remember { mutableStateOf<Message?>(null) }
    // What the schedule picker is currently being opened for. One state, three callers: the
    // composer, the request dialog, and "Edit schedule" on an entry already in the queue.
    var scheduleTarget by remember { mutableStateOf<ScheduleTarget?>(null) }
    val retryableMessageIds = remember(messages) {
        retryableOutgoingMessageIds(messages)
    }
    // The conversation's own record of how each payment ended. Request cards stop offering Pay
    // once it carries their settlement, and a transfer card falls back to it whenever the wallet
    // API has not (or cannot) supply the live claim.
    val outcomes = remember(messages) { paymentOutcomes(messages) }
    val claimsByReference = remember(transferClaims) {
        transferClaims.mapKeys { (id, _) -> id.lowercase() }
    }
    // Sending money back is the one payment action that owes the other side an explanation, so
    // Reject and Reverse route through a prompt that collects one.
    var reasonTarget by remember { mutableStateOf<TransferReasonPrompt?>(null) }
    var conversationMenuOpen by remember(chat.id) { mutableStateOf(false) }

    // Keep the newest message visible, just like WhatsApp: jump to the bottom the first time the
    // thread loads, then follow new messages only while the reader is already near the bottom.
    // A reader who scrolled up to older history is never yanked; a chip offers the way back.
    val listState = rememberLazyListState()
    val conversationRows = remember(messages) { groupConversationRows(messages) }
    var renderedMessageCount by remember { mutableStateOf(0) }
    var pendingNewMessages by remember { mutableStateOf(0) }
    val coroutineScopeForScroll = rememberCoroutineScope()
    // The list header adds two leading items (date + encryption notice) and a trailing spacer,
    // so the newest message sits just above the final index.
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearBottom) {
        if (nearBottom) pendingNewMessages = 0
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        val bottomIndex = conversationRows.size + 2
        val lastFromMe = messages.lastOrNull()?.fromMe == true
        when {
            renderedMessageCount == 0 -> listState.scrollToItem(bottomIndex)
            messages.size > renderedMessageCount && (nearBottom || lastFromMe) ->
                listState.animateScrollToItem(bottomIndex)
            messages.size > renderedMessageCount ->
                pendingNewMessages += messages.size - renderedMessageCount
        }
        renderedMessageCount = messages.size
    }
    // A bubble below the fold is not an indicator. Follow it into view, but only for a reader who
    // is already at the bottom — the same rule a new message gets, for the same reason.
    LaunchedEffect(chat.typing) {
        if (chat.typing && nearBottom) {
            listState.animateScrollToItem(conversationRows.size + 3)
        }
    }

    if (showRequestDialog) {
        PaymentRequestDialog(
            sending = sending,
            schedulingEnabled = schedulingEnabled,
            onDismiss = { showRequestDialog = false },
            onRequest = { amountMinor, note ->
                onSendPaymentRequest(amountMinor, note) { showRequestDialog = false }
            },
            onRequestLater = { amountMinor, note ->
                scheduleTarget = ScheduleTarget.Request(amountMinor, note)
            },
        )
    }
    scheduleTarget?.let { target ->
        // Frozen for the life of the dialog: the presets and the validation must not shift under
        // somebody who is mid-decision, and the queue re-checks the real clock on confirm anyway.
        val openedAt = remember(target) { System.currentTimeMillis() }
        ScheduleSendDialog(
            heading = when (target) {
                ScheduleTarget.Composer -> "Send later"
                is ScheduleTarget.Request -> "Request later"
                is ScheduleTarget.Existing -> "Change the send time"
            },
            confirmLabel = if (target is ScheduleTarget.Existing) "Reschedule" else "Schedule",
            nowEpochMillis = openedAt,
            initialEpochMillis = (target as? ScheduleTarget.Existing)
                ?.message
                ?.scheduledAtEpochMillis,
            onDismiss = { scheduleTarget = null },
            onSchedule = { atEpochMillis ->
                when (target) {
                    // The same one-shot fence a tap on Send goes through, so holding Send and then
                    // tapping it cannot queue the message and send it as well.
                    ScheduleTarget.Composer -> composerState.submitted()?.let { submitted ->
                        composerState = submitted
                        onScheduleSend(submitted.text, atEpochMillis) {
                            composerState = composerState.clearIfUnchanged(submitted)
                        }
                    }
                    is ScheduleTarget.Request -> onSchedulePaymentRequest(
                        target.amountMinor,
                        target.note,
                        atEpochMillis,
                    ) { showRequestDialog = false }
                    is ScheduleTarget.Existing -> onRescheduleSend(target.message, atEpochMillis)
                }
                scheduleTarget = null
            },
        )
    }
    if (topUpRequirement != null && topUpSheet != null) {
        topUpSheet { /* The retained payTarget reopens approval when the sheet closes. */ }
    }
    payTarget?.takeIf { topUpRequirement == null }?.let { target ->
        PaymentApprovalDialog(
            amountText = Money.format(
                abs(target.amountMinor),
                target.paymentCurrencyCode,
                target.paymentCurrencyScale,
            ),
            sending = sending,
            error = error,
            biometricsAvailable = biometricsAvailable,
            onDismiss = { payTarget = null },
            onConfirm = { pin ->
                onPayRequest(target, pin) { payTarget = null }
            },
        )
    }
    reasonTarget?.let { prompt ->
        TransferReasonDialog(
            prompt = prompt,
            sending = sending,
            error = error,
            biometricsAvailable = biometricsAvailable,
            onDismiss = { reasonTarget = null },
            onConfirm = { reason, pin ->
                if (prompt.reverse) {
                    onReverseTransfer(prompt.message, reason, pin)
                } else {
                    onRejectTransfer(prompt.message, reason)
                }
                reasonTarget = null
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // A group's header is the way into the group itself, the way a person's
                        // header would be the way to their profile. A direct chat has nowhere
                        // else to go, so it stays inert rather than offering a dead tap target.
                        modifier = if (chat.isGroup) {
                            Modifier.clickable(onClick = onOpenGroup)
                        } else {
                            Modifier
                        },
                    ) {
                        KitAvatar(chat.name, size = 40.dp, online = chat.online, avatarUrl = chat.avatarUrl)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(chat.name, style = MaterialTheme.typography.titleMedium)
                            // Typing outranks online. Somebody typing is necessarily present, and
                            // showing the weaker of two true statements wastes the one line here.
                            // In a group the same line says who, because "typing…" under a group
                            // name does not tell anybody enough to be worth the space.
                            when {
                                chat.typing -> Text(
                                    groupTypingLabel(chat.typingNames) ?: "typing…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                chat.isGroup -> Text(
                                    "Tap for group info",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                chat.online -> Text(
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
                    if (abuseReportingAvailable && !chat.isGroup) {
                        Box {
                            IconButton(onClick = { conversationMenuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = conversationMenuOpen,
                                onDismissRequest = { conversationMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Report account") },
                                    onClick = {
                                        conversationMenuOpen = false
                                        onReportAccount()
                                    },
                                )
                            }
                        }
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
                        // Null means this exact composer content is already on its way, which is
                        // what a second tap before the durable commit looks like.
                        composerState.submitted()?.let { submitted ->
                            composerState = submitted
                            onSend(submitted.text) {
                                composerState = composerState.clearIfUnchanged(submitted)
                            }
                        }
                    },
                    submissionInFlight = composerState.submittedGeneration != null,
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
                    sendEnabled = sendEnabled,
                    schedulingEnabled = schedulingEnabled,
                    onScheduleSend = {
                        if (error != null) onClearError()
                        if (composerState.text.isNotBlank()) {
                            scheduleTarget = ScheduleTarget.Composer
                        }
                    },
                )
            }
        },
    ) { padding ->
        // Pull-beyond-latest: dragging up past the newest message reveals a camera panel behind
        // the list, and releasing past the threshold opens the in-app camera (the TikTok feel).
        val density = LocalDensity.current
        val cameraPullMaxPx = with(density) { CAMERA_PULL_MAX_REVEAL.toPx() }
        val cameraPullThresholdPx = with(density) { CAMERA_PULL_OPEN_THRESHOLD.toPx() }
        var cameraRevealPx by remember { mutableFloatStateOf(0f) }
        // The camera opens on release and only on release. Fling callbacks are not a reliable
        // "the finger left the screen" signal, so the gesture end is observed directly below.
        var cameraPullPointerDown by remember { mutableStateOf(false) }
        val currentOnOpenCamera by rememberUpdatedState(onOpenCamera)
        val cameraPullConnection = remember(mediaEnabled, cameraPullMaxPx, cameraPullThresholdPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!mediaEnabled || source != NestedScrollSource.UserInput) return Offset.Zero
                    val result = CameraPull.collapse(cameraRevealPx, available.y)
                    cameraRevealPx = result.revealPx
                    return Offset(0f, result.consumedY)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!mediaEnabled || source != NestedScrollSource.UserInput) return Offset.Zero
                    val next = CameraPull.pull(cameraRevealPx, available.y, cameraPullMaxPx)
                    val used = next - cameraRevealPx
                    cameraRevealPx = next
                    return Offset(0f, -used)
                }

                // Deliberately does not open the camera. This can be dispatched while the finger is
                // still down, which is the whole bug: the panel says "release to open the camera"
                // and then the camera would appear mid-drag. The release handler below owns it.
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                    Velocity.Zero
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(cameraPullConnection)
                // Observes the gesture without consuming it, so the list still scrolls normally.
                // Watching pointers directly is what makes "release" mean release: it fires once
                // the last finger lifts, whether or not the list happened to fling afterwards.
                .pointerInput(mediaEnabled) {
                    if (!mediaEnabled) return@pointerInput
                    while (true) {
                        awaitPointerEventScope {
                            var event = awaitPointerEvent(PointerEventPass.Initial)
                            while (event.changes.none { it.pressed }) {
                                event = awaitPointerEvent(PointerEventPass.Initial)
                            }
                            cameraPullPointerDown = true
                            while (event.changes.any { it.pressed }) {
                                event = awaitPointerEvent(PointerEventPass.Initial)
                            }
                            cameraPullPointerDown = false
                        }
                        if (cameraRevealPx > 0f) {
                            val open = CameraPull.shouldOpenOnRelease(
                                revealPx = cameraRevealPx,
                                thresholdPx = cameraPullThresholdPx,
                                pointerDown = cameraPullPointerDown,
                            )
                            // Settle the panel first either way, so the reveal never stays stuck
                            // open when the release produced no fling to collapse it.
                            animate(cameraRevealPx, 0f) { value, _ -> cameraRevealPx = value }
                            if (open) currentOnOpenCamera()
                        }
                    }
                },
        ) {
        if (cameraRevealPx > 0f) {
            CameraPeekPanel(
                pastThreshold = CameraPull.shouldOpen(cameraRevealPx, cameraPullThresholdPx),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { cameraRevealPx.toDp() }),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, -cameraRevealPx.roundToInt()) }
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
            items(
                count = conversationRows.size,
                // Stable keys keep scroll anchoring and item state correct when the projection
                // republishes the whole list (which happens on every sync commit).
                key = { index -> conversationRows[index].key },
            ) { i ->
                val group = conversationRows[i] as? ConversationRow.ImageGroup
                if (group != null) {
                    ImageGroupBubble(
                        messages = group.messages,
                        fromMe = group.messages.first().fromMe,
                        mediaBytes = mediaBytes,
                        mediaLoading = mediaLoading,
                        mediaErrors = mediaErrors,
                        onOpenMedia = onOpenMedia,
                        onOpenViewer = onOpenViewer,
                        reactable = reactionsEnabled,
                        onToggleReaction = onToggleReaction,
                        reportableMessageIds = reportableMessageIds,
                        onReportMessage = onReportMessage,
                    )
                    return@items
                }
                val message = (conversationRows[i] as ConversationRow.Single).message
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
                } else if (message.kind == MessageKind.PAYMENT_EVENT) {
                    // Outcomes are the conversation talking about itself, not either person
                    // talking. They read like the encryption notice: centred, unattributed.
                    TimelineNotice(paymentEventSummary(message, chat.name))
                } else if (message.kind == MessageKind.SYSTEM) {
                    // A membership change is the group talking about itself, so it reads exactly
                    // the same way — the copy is already resolved and actor-free.
                    TimelineNotice(message.text)
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
                        onOpenViewer = { onOpenViewer(message) },
                        reactable = reactionsEnabled && message.acceptsReactions,
                        onToggleReaction = { emoji -> onToggleReaction(message, emoji) },
                        reportable = message.id in reportableMessageIds,
                        onReport = { onReportMessage(message) },
                        outcome = outcomes[message.id],
                        claim = TransferClaimResolutionPolicy.forPresentation(
                            message = message,
                            claim = message.paymentReferenceId
                                ?.lowercase()
                                ?.let(claimsByReference::get),
                            binding = TransferClaimPartyBinding.create(
                                currentUserId = currentAccountId,
                                peerUserId = chat.peerUserId,
                            ),
                            capabilityEnabled = claimableTransfersEnabled,
                        ),
                        onPayRequest = {
                            payTarget = message
                            shortfallForPaymentRequest(message)?.let(onTopUpNeeded)
                        },
                        onDeclineRequest = { onDeclineRequest(message) },
                        onCancelRequest = { onCancelRequest(message) },
                        onAcceptTransfer = { onAcceptTransfer(message) },
                        onRejectTransfer = {
                            reasonTarget = TransferReasonPrompt(message, reverse = false)
                        },
                        onReverseTransfer = {
                            reasonTarget = TransferReasonPrompt(message, reverse = true)
                        },
                        onSendScheduledNow = { onSendScheduledNow(message) },
                        onEditSchedule = { scheduleTarget = ScheduleTarget.Existing(message) },
                        onCancelSchedule = { onCancelScheduledSend(message) },
                    )
                }
            }
            // Below the last message and above the trailing spacer, so it occupies the place the
            // message being typed will occupy. Keyed separately from the projection so appearing
            // and disappearing never disturbs a real row's scroll anchoring.
            if (chat.typing) {
                item(key = "typing-indicator") {
                    // A direct chat has exactly one possible typist and the header already names
                    // them, so the label stays null rather than repeating it. A group's bubble
                    // carries the names, because the reader's eye is down here at the composer and
                    // "somebody in this group" is not worth the row.
                    TypingBubble(label = groupTypingLabel(chat.typingNames))
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (pendingNewMessages > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clickable {
                        val target = conversationRows.size + 2
                        pendingNewMessages = 0
                        coroutineScopeForScroll.launch {
                            listState.animateScrollToItem(target)
                        }
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (pendingNewMessages == 1) {
                            "1 new message"
                        } else {
                            "$pendingNewMessages new messages"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
        }
        }
    }
}

/**
 * The incoming-side "…" bubble, shaped and coloured exactly like a received message so it reads as
 * the next message arriving rather than as a notice about the conversation.
 *
 * It is never a [Message]: nothing here has an id, an order or a place in the encrypted store, and
 * the list must not be able to scroll back to it. The three dots fade on a staggered loop — an
 * opacity animation rather than a moving one, so it stays quiet next to real content.
 */
@Composable
internal fun TypingBubble(label: String?) {
    val colors = KitTheme.colors
    val transition = rememberInfiniteTransition(label = "typing")

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        label?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
            )
        }
        Surface(
            color = colors.chatBubbleOther,
            contentColor = colors.onChatBubbleOther,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 6.dp,
                bottomEnd = 18.dp,
            ),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(TYPING_DOTS) { index ->
                    val alpha by transition.animateFloat(
                        initialValue = TYPING_DOT_MIN_ALPHA,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = TYPING_DOT_CYCLE_MILLIS,
                                // Each dot enters a third of a cycle after the one before it, which
                                // is what makes the row read as a wave instead of a flash.
                                delayMillis = index * (TYPING_DOT_CYCLE_MILLIS / TYPING_DOTS),
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "typing-dot-$index",
                    )
                    if (index > 0) Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(7.dp)
                            .graphicsLayer { this.alpha = alpha }
                            .background(colors.onChatBubbleOther, CircleShape),
                    )
                }
            }
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
    onOpenViewer: () -> Unit = {},
    /** False while secure messaging is unavailable, so the palette is never offered offline. */
    reactable: Boolean = false,
    onToggleReaction: (String) -> Unit = {},
    reportable: Boolean = false,
    onReport: () -> Unit = {},
    /** How this conversation recorded the payment ending, when it did. */
    outcome: PaymentOutcome? = null,
    /** The wallet API's live view of a held transfer; overrides [outcome] when present. */
    claim: TransferClaim? = null,
    onPayRequest: () -> Unit = {},
    onDeclineRequest: () -> Unit = {},
    onCancelRequest: () -> Unit = {},
    onAcceptTransfer: () -> Unit = {},
    onRejectTransfer: () -> Unit = {},
    onReverseTransfer: () -> Unit = {},
    /** Long-press actions for an entry still waiting in the send-later queue. */
    onSendScheduledNow: () -> Unit = {},
    onEditSchedule: () -> Unit = {},
    onCancelSchedule: () -> Unit = {},
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
        MessageKind.PAYMENT,
        MessageKind.PAYMENT_REQUEST,
        MessageKind.PAYMENT_TRANSFER,
        MessageKind.PAYMENT_EVENT,
        MessageKind.CALL,
        MessageKind.SYSTEM,
        -> null
    }
    var actionMenuOpen by remember(msg.id) { mutableStateOf(false) }
    var pickerOpen by remember(msg.id) { mutableStateOf(false) }
    var reactorsOpen by remember(msg.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val myReactions = msg.reactions.filter { it.fromMe }.mapTo(mutableSetOf()) { it.emoji }

    if (pickerOpen) {
        ReactionPickerDialog(
            selected = myReactions,
            onPick = { emoji ->
                pickerOpen = false
                onToggleReaction(emoji)
            },
            onDismiss = { pickerOpen = false },
        )
    }
    if (reactorsOpen && msg.reactions.isNotEmpty()) {
        ReactionReactorsDialog(
            reactions = msg.reactions,
            onDismiss = { reactorsOpen = false },
        )
    }

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(if (msg.fromMe) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(bottom = if (msg.reactions.isEmpty()) 8.dp else 18.dp)
                .widthIn(max = 300.dp),
        ) {
            DropdownMenu(
                expanded = actionMenuOpen,
                onDismissRequest = { actionMenuOpen = false },
            ) {
                if (msg.isScheduledEntry) {
                    DropdownMenuItem(
                        text = { Text("Send now") },
                        onClick = {
                            actionMenuOpen = false
                            onSendScheduledNow()
                        },
                    )
                    // An entry whose outcome is unknown must not be re-timed: rescheduling it
                    // would present a decision about a possible duplicate as a change of plan.
                    if (msg.state == DeliveryState.SCHEDULED) {
                        DropdownMenuItem(
                            text = { Text("Edit schedule") },
                            onClick = {
                                actionMenuOpen = false
                                onEditSchedule()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (msg.state == DeliveryState.UNCONFIRMED) "Discard" else "Cancel send",
                            )
                        },
                        onClick = {
                            actionMenuOpen = false
                            onCancelSchedule()
                        },
                    )
                }
                if (reactable) {
                    QuickReactionPalette(
                        selected = myReactions,
                        onPick = { emoji ->
                            actionMenuOpen = false
                            onToggleReaction(emoji)
                        },
                        onMore = {
                            actionMenuOpen = false
                            pickerOpen = true
                        },
                    )
                }
                if (copyableText != null) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            actionMenuOpen = false
                            clipboard.setText(AnnotatedString(copyableText))
                        },
                    )
                }
                if (reportable) {
                    DropdownMenuItem(
                        text = { Text("Report message") },
                        onClick = {
                            actionMenuOpen = false
                            onReport()
                        },
                    )
                }
            }
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                shadowElevation = 1.dp,
                // An outline is the whole visual difference between "waiting here" and "gone": the
                // bubble keeps its colour and its place, and reads as not yet solid.
                border = if (msg.isScheduledEntry) {
                    BorderStroke(
                        1.dp,
                        if (msg.state == DeliveryState.UNCONFIRMED) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        } else {
                            contentColor.copy(alpha = 0.35f)
                        },
                    )
                } else {
                    null
                },
                modifier = if (
                    copyableText != null || reactable || reportable || msg.isScheduledEntry
                ) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { actionMenuOpen = true },
                    )
                } else {
                    Modifier
                },
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    // Only a group names its senders, and only for other people's bubbles: the
                    // side of the screen already says which ones are mine. The accent matches
                    // this person's avatar, so the same author reads the same way down a thread.
                    if (!msg.fromMe) {
                        msg.senderName?.takeIf(String::isNotBlank)?.let { author ->
                            Text(
                                author,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = kitNameAccent(author),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
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
                            outcome = outcome,
                            payEnabled = !operationInFlight && outcome == null,
                            onPay = onPayRequest,
                            onDecline = onDeclineRequest,
                            onCancel = onCancelRequest,
                        )
                        MessageKind.PAYMENT_TRANSFER -> PaymentTransferChatCard(
                            msg = msg,
                            claim = claim,
                            outcome = outcome,
                            actionsEnabled = !operationInFlight,
                            onAccept = onAcceptTransfer,
                            onReject = onRejectTransfer,
                            onReverse = onReverseTransfer,
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
                            onOpenViewer = onOpenViewer,
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
                            onOpenViewer = onOpenViewer,
                        )
                        // PAYMENT_EVENT and SYSTEM never reach a bubble — the list renders them
                        // centred — and CALL is handled by CallLogBubble before this point.
                        MessageKind.TEXT,
                        MessageKind.PAYMENT_EVENT,
                        MessageKind.SYSTEM,
                        MessageKind.CALL,
                        -> Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (msg.isScheduledEntry) {
                            // A scheduled entry has no receipt to report and never will until it
                            // is actually sent. What it owes the reader is the time it will go.
                            val unconfirmed = msg.state == DeliveryState.UNCONFIRMED
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (unconfirmed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    contentColor.copy(alpha = 0.75f)
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (unconfirmed) {
                                    "Not confirmed · send or discard"
                                } else {
                                    "Scheduled · ${formatScheduledFor(
                                        msg.scheduledAtEpochMillis,
                                        rememberNowEpochMillis(),
                                    )}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (unconfirmed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    contentColor.copy(alpha = 0.75f)
                                },
                            )
                            return@Row
                        }
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
            MessageReactionChips(
                reactions = msg.reactions,
                onToggle = onToggleReaction,
                onShowReactors = { reactorsOpen = true },
                modifier = Modifier
                    .align(if (msg.fromMe) Alignment.End else Alignment.Start)
                    .padding(horizontal = 10.dp),
            )
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
    DeliveryState.SCHEDULED -> "Scheduled"
    DeliveryState.UNCONFIRMED -> "Not confirmed"
}

/** True for a thread entry that is still waiting in the send-later queue. */
internal val Message.isScheduledEntry: Boolean
    get() = state == DeliveryState.SCHEDULED || state == DeliveryState.UNCONFIRMED

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
    outcome: PaymentOutcome?,
    payEnabled: Boolean,
    onPay: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = KitTheme.colors
    val settled = outcome != null
    // A queued request has no server-side request behind it — deliberately, so nothing can be paid,
    // declined or chased before it is sent. There is therefore nothing here to act on yet.
    val queued = msg.isScheduledEntry
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
                        queued -> "Payment request • Scheduled"
                        else -> when (outcome?.event) {
                            PaymentEventKind.PAID -> "Payment request • Paid"
                            PaymentEventKind.DECLINED -> "Payment request • Declined"
                            PaymentEventKind.CANCELLED -> "Payment request • Cancelled"
                            // Anything else against this reference still ends the request; say so
                            // plainly rather than inventing a label for it.
                            null -> if (msg.fromMe) "Payment request sent" else "Payment request"
                            else -> "Payment request • Closed"
                        }
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
        if (!settled && !queued) {
            Spacer(Modifier.height(10.dp))
            if (msg.fromMe) {
                // The requester's only move is to withdraw what they asked for.
                TransferCardAction(
                    label = "Cancel request",
                    enabled = payEnabled,
                    primary = false,
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferCardAction(
                        label = "Pay ${Money.format(
                            abs(msg.amountMinor),
                            msg.paymentCurrencyCode,
                            msg.paymentCurrencyScale,
                        )}",
                        enabled = payEnabled,
                        primary = true,
                        onClick = onPay,
                        modifier = Modifier.weight(1f),
                    )
                    TransferCardAction(
                        label = "Decline",
                        enabled = payEnabled,
                        primary = false,
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * A Kit → Kit transfer that is being held for the recipient.
 *
 * The recipient sees Accept and Reject; the sender sees Reverse for as long as the money is still
 * unclaimed. Which actions exist is the server's decision, carried on the claim — the card never
 * infers them, so a stale screen cannot offer to settle money that is already settled.
 */
@Composable
private fun PaymentTransferChatCard(
    msg: Message,
    claim: TransferClaim?,
    outcome: PaymentOutcome?,
    actionsEnabled: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onReverse: () -> Unit,
) {
    val colors = KitTheme.colors
    val status = claim?.status ?: outcome?.event?.toTransferClaimStatus()
    val settledReason = claim?.reason ?: outcome?.reason
    val amountMinor = claim?.amountMinor ?: abs(msg.amountMinor)
    val currencyCode = claim?.currencyCode ?: msg.paymentCurrencyCode
    val currencyScale = claim?.currencyScale ?: msg.paymentCurrencyScale
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
                    transferCardLabel(status, msg.fromMe),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Text(
                    Money.format(amountMinor, currencyCode, currencyScale),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            if (status == TransferClaimStatus.ACCEPTED) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Accepted",
                    tint = KitGreen300,
                )
            }
        }
        (claim?.note ?: msg.paymentNote)?.takeIf(String::isNotBlank)?.let { note ->
            Spacer(Modifier.height(6.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        settledReason?.takeIf(String::isNotBlank)?.let { reason ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Reason: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        if (status == TransferClaimStatus.PENDING) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (msg.fromMe) {
                    "Waiting for this to be accepted. You can take it back until then."
                } else {
                    "This money is yours once you accept it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        if (claim?.canAccept == true || claim?.canReject == true) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (claim.canAccept) {
                    TransferCardAction(
                        label = "Accept",
                        enabled = actionsEnabled,
                        primary = true,
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (claim.canReject) {
                    TransferCardAction(
                        label = "Reject",
                        enabled = actionsEnabled,
                        primary = false,
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (claim?.canReverse == true) {
            Spacer(Modifier.height(10.dp))
            TransferCardAction(
                label = "Reverse",
                enabled = actionsEnabled,
                primary = false,
                onClick = onReverse,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TransferCardAction(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(
                if (primary) KitGreen300 else Color.White.copy(alpha = 0.16f),
                MaterialTheme.shapes.small,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color(0xFF0B2B1A) else Color.White,
        )
    }
}

private fun PaymentEventKind.toTransferClaimStatus(): TransferClaimStatus? = when (this) {
    PaymentEventKind.ACCEPTED -> TransferClaimStatus.ACCEPTED
    PaymentEventKind.REJECTED -> TransferClaimStatus.REJECTED
    PaymentEventKind.REVERSED -> TransferClaimStatus.REVERSED
    PaymentEventKind.EXPIRED -> TransferClaimStatus.EXPIRED
    PaymentEventKind.REQUESTED,
    PaymentEventKind.PAID,
    PaymentEventKind.DECLINED,
    PaymentEventKind.CANCELLED,
    PaymentEventKind.TRANSFER,
    PaymentEventKind.SENT,
    -> null
}

private fun transferCardLabel(status: TransferClaimStatus?, fromMe: Boolean): String =
    when (status) {
        TransferClaimStatus.ACCEPTED -> if (fromMe) "Payment sent • Accepted" else "Payment accepted"
        TransferClaimStatus.REJECTED -> "Payment returned • Rejected"
        TransferClaimStatus.REVERSED -> "Payment returned • Reversed"
        TransferClaimStatus.EXPIRED -> "Payment returned • Not accepted in time"
        // Both the pending case and the unknown case: the card is still about money in flight,
        // and its buttons come from the claim, so saying less here costs nothing.
        TransferClaimStatus.PENDING, null ->
            if (fromMe) "Payment sent • Waiting" else "Payment received • Accept to keep"
    }

/**
 * A line the conversation writes about itself: a settled payment, or a membership change.
 *
 * Centred and unattributed on purpose. For money, this is the conversation recording what both
 * people can see happened to it, and — when it went back — in whose words and why. For a group,
 * it is the group recording who is in it, which belongs to no single member.
 */
@Composable
private fun TimelineNotice(summary: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Which held transfer is being sent back, and by which side. */
private data class TransferReasonPrompt(
    val message: Message,
    /** True when the sender is taking their own transfer back, false when the recipient rejects. */
    val reverse: Boolean,
)

/**
 * Collects why a held transfer is going back.
 *
 * Optional, because a payment must never be trapped behind a text field — but asked for every
 * time, because the reason is what the other side reads in the conversation afterwards.
 */
@Composable
private fun TransferReasonDialog(
    prompt: TransferReasonPrompt,
    sending: Boolean,
    error: String?,
    biometricsAvailable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?, String) -> Unit,
) {
    var reason by remember(prompt.message.id, prompt.reverse) { mutableStateOf("") }
    val amountText = Money.format(
        abs(prompt.message.amountMinor),
        prompt.message.paymentCurrencyCode,
        prompt.message.paymentCurrencyScale,
    )
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(if (prompt.reverse) "Reverse $amountText" else "Reject $amountText") },
        text = {
            Column {
                Text(
                    if (prompt.reverse) {
                        "The money goes back to your wallet straight away. Your reason is shown " +
                            "in this chat so they know why."
                    } else {
                        "The money goes back to them straight away. Your reason is shown in this " +
                            "chat so they know why."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(KitPaymentMessage.MAX_REASON_LENGTH) },
                    enabled = !sending,
                    label = { Text("Reason (optional)") },
                    singleLine = true,
                )
                // Reversing takes money out of someone's hands, so it is approved like any other
                // payment. Rejecting only sends back what was never accepted, and needs no approval.
                if (prompt.reverse) {
                    Spacer(Modifier.height(16.dp))
                    PaymentApproval(
                        actionLabel = "Reverse $amountText",
                        biometricsAvailable = biometricsAvailable,
                        busy = sending,
                        error = error,
                        onApprove = { pin -> onConfirm(reason.trim().ifBlank { null }, pin) },
                        pinSubtitle = "Authorizes returning $amountText to your wallet.",
                    )
                }
            }
        },
        confirmButton = {
            if (!prompt.reverse) {
                TextButton(
                    enabled = !sending,
                    onClick = { onConfirm(reason.trim().ifBlank { null }, "") },
                ) {
                    Text(if (sending) "Sending…" else "Reject")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Collects the amount and optional note for an in-chat payment request. */
/** What the schedule picker was opened for, and everything needed to act on its answer. */
private sealed interface ScheduleTarget {
    /** Whatever is in the composer right now. */
    data object Composer : ScheduleTarget

    /** An amount and note already filled in on the request dialog, not yet sent to the server. */
    data class Request(val amountMinor: Long, val note: String?) : ScheduleTarget

    /** An entry already in the queue, being moved to a different time. */
    data class Existing(val message: Message) : ScheduleTarget
}

@Composable
private fun PaymentRequestDialog(
    sending: Boolean,
    onDismiss: () -> Unit,
    onRequest: (Long, String?) -> Unit,
    schedulingEnabled: Boolean = false,
    onRequestLater: (Long, String?) -> Unit = { _, _ -> },
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
                    // Digits and a single point are the whole alphabet of an amount, and keeping
                    // the state to that alphabet is what lets the grouping stay presentational.
                    onValueChange = { value ->
                        amountText = value.filter { it.isDigit() || it == '.' }
                    },
                    enabled = !sending,
                    label = { Text("Amount (${Money.SYMBOL})") },
                    visualTransformation = GroupedAmountTransformation,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
                if (schedulingEnabled) {
                    TextButton(
                        enabled = !sending && (amountMinor ?: 0L) > 0L,
                        onClick = {
                            onRequestLater(checkNotNull(amountMinor), note.trim().ifBlank { null })
                        },
                    ) { Text("Later") }
                }
            }
        },
    )
}

/**
 * Approves an in-chat payment.
 *
 * The approval itself is [PaymentApproval], the same surface every other payment in the app uses,
 * so a request paid from a chat is approved exactly the way one sent from Send Money is: biometrics
 * when this device has them, the full-screen wallet PIN when it does not.
 */
@Composable
private fun PaymentApprovalDialog(
    amountText: String,
    sending: Boolean,
    error: String?,
    biometricsAvailable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Pay $amountText") },
        text = {
            Column {
                Text(
                    "Money moves only once you approve it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PaymentApproval(
                    actionLabel = "Pay $amountText",
                    biometricsAvailable = biometricsAvailable,
                    busy = sending,
                    error = error,
                    onApprove = onConfirm,
                    pinSubtitle = "Authorizes $amountText from your wallet.",
                )
            }
        },
        confirmButton = {},
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

/** The camera panel peeking from behind the conversation while the pull gesture is held. */
@Composable
private fun CameraPeekPanel(pastThreshold: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xFF10151B)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.PhotoCamera,
                contentDescription = null,
                tint = if (pastThreshold) KitGreen300 else Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (pastThreshold) "Release to open the camera" else "Keep pulling for the camera",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private val CAMERA_PULL_MAX_REVEAL = 160.dp
private val CAMERA_PULL_OPEN_THRESHOLD = 110.dp

private const val TYPING_DOTS = 3
private const val TYPING_DOT_CYCLE_MILLIS = 600
private const val TYPING_DOT_MIN_ALPHA = 0.25f

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
    onOpenViewer: () -> Unit = {},
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
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenViewer),
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    /**
     * Whether a message can actually leave this device right now.
     *
     * This is the *only* place secure-messaging readiness is allowed to change what the user
     * sees: typing and drafting stay open (the draft is already encrypted at rest), and just the
     * outbound actions rest until the session is ready. There is no plaintext path behind this
     * flag — it mirrors the same gate the repository enforces, so a stale composition cannot
     * send either.
     */
    sendEnabled: Boolean = true,
    /** Whether the current composer content has already been handed to the send path. */
    submissionInFlight: Boolean = false,
    /** Whether holding Send may offer to hold the message back until a chosen time. */
    schedulingEnabled: Boolean = false,
    onScheduleSend: () -> Unit = {},
) {
    val context = LocalContext.current
    val recorder = remember { VoiceNoteRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var sendMenuOpen by remember { mutableStateOf(false) }
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
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (!sendEnabled) {
            // The whole of the readiness story, told where it is actually relevant and nowhere
            // else. It needs no button: the session retries on its own and this line goes away
            // with it.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Preparing secure messaging…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
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
                                IconButton(
                                    onClick = { attachMenuOpen = true },
                                    enabled = sendEnabled,
                                ) {
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
                        IconButton(onClick = onRequestPayment, enabled = sendEnabled) {
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
            // One dimmed circle covers all three states: the outbound action is present and in the
            // same place whether or not the session is ready, so nothing jumps when it becomes so.
            val actionAlpha = if (sendEnabled) 1f else 0.38f
            if (recording) {
                Box(
                    Modifier
                        .size(50.dp)
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = actionAlpha),
                            CircleShape,
                        )
                        .clickable(enabled = sendEnabled) {
                            val finished = recorder.finish()
                            recording = false
                            if (finished != null) onSendVoiceNote(finished.bytes) else onVoiceNoteTooShort()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send voice note",
                        tint = MaterialTheme.colorScheme.onSecondary.copy(alpha = actionAlpha),
                    )
                }
            } else if (draft.isBlank() && mediaEnabled) {
                Box(
                    Modifier
                        .size(50.dp)
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = actionAlpha),
                            CircleShape,
                        )
                        .clickable(enabled = sendEnabled) {
                            recordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = if (sendEnabled) {
                            "Record a voice note"
                        } else {
                            "Record a voice note, available once secure messaging is ready"
                        },
                        tint = MaterialTheme.colorScheme.onSecondary.copy(alpha = actionAlpha),
                    )
                }
            } else {
                val sendArmed = draft.isNotBlank() && sendEnabled && !submissionInFlight
                Box {
                    // Anchored to the send circle itself, so "Send now / Send later" opens where
                    // the thumb already is — the same gesture WhatsApp and Gmail put it behind.
                    DropdownMenu(
                        expanded = sendMenuOpen,
                        onDismissRequest = { sendMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Send now") },
                            onClick = {
                                sendMenuOpen = false
                                onSend()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Send later") },
                            onClick = {
                                sendMenuOpen = false
                                onScheduleSend()
                            },
                        )
                    }
                    Box(
                        Modifier
                            .size(50.dp)
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = actionAlpha),
                                CircleShape,
                            )
                            .combinedClickable(
                                // The state fence in ConversationComposerState is the real defence;
                                // this makes the second tap visibly inert rather than silently so.
                                enabled = sendArmed,
                                onClick = onSend,
                                onLongClick = {
                                    if (schedulingEnabled) sendMenuOpen = true
                                },
                                onLongClickLabel = "Send now or send later",
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = when {
                                !sendEnabled -> "Send, available once secure messaging is ready"
                                schedulingEnabled -> "Send. Hold to send later"
                                else -> "Send"
                            },
                            tint = MaterialTheme.colorScheme.onSecondary.copy(
                                alpha = if (draft.isNotBlank()) actionAlpha else 0.45f * actionAlpha,
                            ),
                        )
                    }
                }
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
