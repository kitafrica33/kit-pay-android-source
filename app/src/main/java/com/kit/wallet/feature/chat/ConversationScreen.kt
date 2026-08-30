package com.kit.wallet.feature.chat

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.kit.wallet.data.messaging.KitMediaMessageV2
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.KitScheduledGroupPaymentOutcomeAction
import com.kit.wallet.data.messaging.KitScheduledGroupPaymentOutcomeMessage
import com.kit.wallet.data.messaging.KitScheduledPaymentAction
import com.kit.wallet.data.messaging.KitScheduledPaymentMessage
import com.kit.wallet.data.messaging.SecureMediaAlbumSource
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.messaging.displayName
import com.kit.wallet.data.messaging.secureMediaSource
import com.kit.wallet.data.notifications.ActiveCallPresence
import com.kit.wallet.data.remote.GroupPaymentRequestContributionDto
import com.kit.wallet.data.remote.GroupPaymentRequestDto
import com.kit.wallet.data.remote.KitGroupPaymentRequestMessage
import com.kit.wallet.data.remote.ScheduledGroupPaymentDto
import com.kit.wallet.data.messaging.GroupPaymentAudience
import com.kit.wallet.data.messaging.GroupPaymentSplitMode
import com.kit.wallet.data.repository.GroupPaymentDraftPolicy
import com.kit.wallet.data.remote.ScheduledPaymentDto
import com.kit.wallet.data.repository.AbuseReportContext
import com.kit.wallet.data.repository.AbuseReportSelectionPolicy
import com.kit.wallet.data.repository.AbuseReportTarget
import com.kit.wallet.feature.auth.PaymentApproval
import com.kit.wallet.feature.auth.rememberBiometricApprovalAvailable
import com.kit.wallet.feature.calls.CallDurationAnchorPolicy
import com.kit.wallet.feature.funding.TopUpSheet
import com.kit.wallet.feature.funding.TopUpViewModel
import com.kit.wallet.feature.wallet.runFinancialAction
import com.kit.wallet.feature.chat.camera.CameraPull
import com.kit.wallet.feature.chat.camera.KitChatCameraFlow
import com.kit.wallet.feature.chat.camera.KitChatVideoEditorFlow
import com.kit.wallet.feature.chat.camera.LibraryVideoDraft
import com.kit.wallet.feature.chat.camera.stageLibraryVideoForEditing
import com.kit.wallet.ui.components.GroupedAmountTransformation
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.VerifiedAccountName
import com.kit.wallet.ui.components.kitNameAccent
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.GroupPaymentSummary
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.MessageMediaItem
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
import com.kit.wallet.ui.model.TopUpRequirement
import com.kit.wallet.ui.model.acceptsDeliveryInfo
import com.kit.wallet.ui.model.acceptsEdits
import com.kit.wallet.ui.model.acceptsReactions
import com.kit.wallet.ui.model.acceptsReplies
import com.kit.wallet.ui.model.copyablePlaintext
import com.kit.wallet.ui.model.presentableText
import com.kit.wallet.ui.model.replyPreviewLabel
import java.io.File
import java.util.UUID
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class PendingAbuseReport(
    val target: AbuseReportTarget,
    val reportedName: String,
)

/** A selection resolved far enough to send: how to open it, what it is, what to call it. */
private data class PickedMedia(
    val source: SecureMediaSource,
    val mediaType: String,
    val caption: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chatId: String,
    moneyMovementAllowed: Boolean,
    onVerifyIdentityRequired: () -> Unit,
    claimableTransfersEnabled: Boolean,
    /** Whether this account may send a payment into the group, and answer one it was sent. */
    groupPaymentsEnabled: Boolean = false,
    groupPaymentRequestsEnabled: Boolean = false,
    scheduledChatPaymentsEnabled: Boolean = false,
    scheduledGroupPaymentsEnabled: Boolean = false,
    abuseReportingEnabled: Boolean = false,
    onBack: () -> Unit,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    /** The group's own screen — participants, roles, and the way out of it. */
    onOpenGroup: (String) -> Unit = {},
    /**
     * Returns to the live call named by the id. Never starts, answers or ends anything — while
     * the app is in a call that belongs to this chat, every call affordance here reopens that
     * call instead of dialing a second one.
     */
    onReturnToActiveCall: (String) -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
    topUp: TopUpViewModel = hiltViewModel(),
    abuseReports: AbuseReportViewModel = hiltViewModel(),
) {
    LaunchedEffect(groupPaymentRequestsEnabled, viewModel) {
        viewModel.setGroupPaymentRequestsEnabled(groupPaymentRequestsEnabled)
    }
    LaunchedEffect(scheduledChatPaymentsEnabled, scheduledGroupPaymentsEnabled, viewModel) {
        viewModel.setServerSchedulingEnabled(
            direct = scheduledChatPaymentsEnabled,
            group = scheduledGroupPaymentsEnabled,
        )
    }
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
    val historyAvailable by viewModel.historyAvailable.collectAsStateWithLifecycle()
    val messageEditsAvailable by viewModel.messageEditsAvailable.collectAsStateWithLifecycle()
    val mediaAlbumsAvailable by viewModel.mediaAlbumsAvailable.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val retryingMessageId by viewModel.retryingMessageId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val mediaFiles by viewModel.mediaFiles.collectAsStateWithLifecycle()
    val mediaLoading by viewModel.mediaLoading.collectAsStateWithLifecycle()
    val mediaErrors by viewModel.mediaErrors.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.restoredDraft.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val editTarget by viewModel.editTarget.collectAsStateWithLifecycle()
    val messageInfo by viewModel.messageInfo.collectAsStateWithLifecycle()
    val transferClaims by viewModel.transferClaims.collectAsStateWithLifecycle()
    val groupPayments by viewModel.groupPayments.collectAsStateWithLifecycle()
    val groupPaymentRequests by viewModel.groupPaymentRequests.collectAsStateWithLifecycle()
    val groupPaymentRequestContributions by
        viewModel.groupPaymentRequestContributions.collectAsStateWithLifecycle()
    val serverScheduledDirect by viewModel.serverScheduledDirect.collectAsStateWithLifecycle()
    val serverScheduledGroup by viewModel.serverScheduledGroup.collectAsStateWithLifecycle()
    val serverSchedulesHaveMore by viewModel.serverSchedulesHaveMore.collectAsStateWithLifecycle()
    val serverScheduleApproval by viewModel.serverScheduleApproval.collectAsStateWithLifecycle()
    val groupMembers by viewModel.groupMembers.collectAsStateWithLifecycle()
    val refusedForFunds by viewModel.topUpRequired.collectAsStateWithLifecycle()
    val topUpRequirement by topUp.requirement.collectAsStateWithLifecycle()
    val abuseReportState by abuseReports.state.collectAsStateWithLifecycle()
    val activeCallPresence by viewModel.activeCallPresence.collectAsStateWithLifecycle()
    LaunchedEffect(refusedForFunds, moneyMovementAllowed) {
        val shortfall = refusedForFunds ?: return@LaunchedEffect
        if (moneyMovementAllowed) {
            topUp.start(shortfall)
        } else {
            onVerifyIdentityRequired()
        }
        viewModel.clearTopUpRequired()
    }
    LaunchedEffect(moneyMovementAllowed) {
        if (!moneyMovementAllowed) topUp.dismiss()
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Every picker/capture result funnels through one place that hands the ViewModel a way to
    // *open* the selection rather than the bytes of it, so the size of what someone attaches
    // stops being the size of what this process has to hold.
    fun sendPickedMedia(prepare: suspend () -> PickedMedia) {
        coroutineScope.launch {
            try {
                val picked = withContext(Dispatchers.IO + NonCancellable) { prepare() }
                coroutineContext.ensureActive()
                viewModel.sendMedia(picked.source, picked.mediaType, picked.caption)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewModel.reportMediaSelectionError(
                    error.message ?: "The selected file could not be opened",
                )
            }
        }
    }

    // A picked video opens the same trim/mute/caption editor the in-app camera uses, so a
    // library clip can be cut down before it is encrypted — the raw pick never goes straight
    // to the wire anymore. Photos keep their existing transcode-and-send path.
    var libraryVideoDraft by remember { mutableStateOf<LibraryVideoDraft?>(null) }
    fun handleSingleLibraryPick(uri: Uri) {
        val resolvedType = context.contentResolver.getType(uri).orEmpty().lowercase()
        if (resolvedType.startsWith("video/")) {
            coroutineScope.launch {
                try {
                    val staged = withContext(Dispatchers.IO) {
                        stageLibraryVideoForEditing(context, uri, resolvedType)
                    }
                    libraryVideoDraft = staged
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    viewModel.reportMediaSelectionError(
                        error.message ?: "The selected video could not be opened",
                    )
                }
            }
        } else {
            sendPickedMedia {
                // Photos are re-encoded first (orientation, size, stripped metadata), so this
                // one really is bytes in heap — a transcoded still, not a source file.
                val bytes = transcodeChatImage(context.contentResolver, uri)
                    ?: error("The selected photo could not be prepared")
                PickedMedia(SecureMediaSource.ofBytes(bytes), "image/jpeg")
            }
        }
    }
    val pickLibraryMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) handleSingleLibraryPick(uri)
    }
    // Multi-select variant, offered only while the account's album capability is proven. One
    // pick stays on the single-item paths (a lone video still opens the trim editor); several
    // picks become one album, so they arrive as one message rather than a burst of bubbles.
    val pickLibraryMediaAlbum = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(KitMediaMessageV2.MAX_ATTACHMENTS),
    ) { uris ->
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> handleSingleLibraryPick(uris.single())
            uris.any {
                context.contentResolver.getType(it).orEmpty().lowercase().startsWith("video/")
            } -> {
                // Albums skip the per-clip trim editor, and skipping it would put raw library
                // video on the wire — so a mixed pick sends nothing rather than something else.
                viewModel.reportMediaSelectionError(
                    "Videos are sent one at a time for now. Select photos only to send them together.",
                )
            }
            else -> coroutineScope.launch {
                try {
                    val attachments = withContext(Dispatchers.IO + NonCancellable) {
                        uris.map { uri ->
                            val bytes = transcodeChatImage(context.contentResolver, uri)
                                ?: error("A selected photo could not be prepared")
                            SecureMediaAlbumSource(SecureMediaSource.ofBytes(bytes), "image/jpeg")
                        }
                    }
                    coroutineContext.ensureActive()
                    viewModel.sendMediaAlbum(attachments)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    viewModel.reportMediaSelectionError(
                        error.message ?: "The selected photos could not be opened",
                    )
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
                PickedMedia(
                    source = context.contentResolver.secureMediaSource(uri),
                    mediaType = KitMediaMessage.normalizeMediaType(
                        context.contentResolver.getType(uri).orEmpty(),
                    ) ?: "application/octet-stream",
                    // The wire descriptor has no filename field; the caption carries it (iOS parity).
                    caption = context.contentResolver.displayName(uri),
                )
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
    messageInfo?.let { state ->
        MessageInfoScreen(
            state = state,
            onRetry = viewModel::retryMessageInfo,
            onDismiss = viewModel::closeMessageInfo,
        )
    }
    var galleryMessageId by remember { mutableStateOf<String?>(null) }
    galleryMessageId?.let { openedId ->
        ConversationMediaGallery(
            chatName = currentChat.name,
            mediaMessages = messages.filter { it.kind in GALLERY_MEDIA_KINDS },
            initialMessageId = openedId,
            mediaFiles = mediaFiles,
            mediaLoading = mediaLoading,
            mediaErrors = mediaErrors,
            onLoad = viewModel::openMedia,
            onRetry = viewModel::retryMedia,
            onDismiss = { galleryMessageId = null },
            reactionsEnabled = historyAvailable,
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
            onSendMedia = { encoded ->
                viewModel.sendMedia(
                    source = encoded.source,
                    mediaType = encoded.mediaType,
                    caption = encoded.caption,
                    onFinished = encoded.release,
                )
            },
            onError = viewModel::reportMediaSelectionError,
        )
    }
    libraryVideoDraft?.let { staged ->
        KitChatVideoEditorFlow(
            draft = staged,
            maxTransferBytes = viewModel.captureByteLimit(),
            onDismiss = { libraryVideoDraft = null },
            onSendMedia = { encoded ->
                viewModel.sendMedia(
                    source = encoded.source,
                    mediaType = encoded.mediaType,
                    caption = encoded.caption,
                    onFinished = encoded.release,
                )
            },
            onError = viewModel::reportMediaSelectionError,
        )
    }
    // Group-payment wire is believed only in the company of the announcement that vouches for it,
    // and never at all in a one-to-one thread. Everything that fails is dropped here rather than
    // reaching a bubble, because an unrenderable descriptor is not a message.
    val visibleMessages = remember(messages, currentChat.isGroup) {
        projectedGroupPaymentMessages(messages, currentChat.isGroup)
    }
    ConversationContent(
        chat = currentChat,
        messages = visibleMessages,
        onBack = onBack,
        onVoiceCall = onVoiceCall,
        onVideoCall = onVideoCall,
        onOpenGroup = { onOpenGroup(currentChat.id) },
        activeCallPresence = activeCallPresence,
        onReturnToActiveCall = onReturnToActiveCall,
        sending = sending,
        retryingMessageId = retryingMessageId,
        error = error,
        onClearError = viewModel::clearError,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        reactionsEnabled = historyAvailable,
        sendEnabled = historyAvailable,
        onToggleReaction = { message, emoji ->
            viewModel.toggleReaction(message.id, emoji)
        },
        replyTarget = replyTarget,
        onBeginReply = viewModel::beginReply,
        onCancelReply = viewModel::cancelReply,
        editTarget = editTarget,
        editsEnabled = messageEditsAvailable,
        onBeginEdit = viewModel::beginEdit,
        onCancelEdit = viewModel::cancelEdit,
        onOpenMessageInfo = viewModel::openMessageInfo,
        onSubmitEdit = viewModel::submitEdit,
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
        moneyMovementAllowed = moneyMovementAllowed,
        onVerifyIdentityRequired = onVerifyIdentityRequired,
        onSendPaymentRequest = { amount, note, done ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.sendPaymentRequest(amount, note, done)
            }
        },
        onPayRequest = { message, pin, done ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.payPaymentRequest(message, pin, done)
            }
        },
        shortfallForPaymentRequest = { message ->
            if (moneyMovementAllowed) viewModel.shortfallForPaymentRequest(message) else null
        },
        topUpRequirement = topUpRequirement,
        onTopUpNeeded = { requirement ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                topUp.start(requirement)
            }
        },
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
        onDeclineRequest = { message ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.declinePaymentRequest(message)
            }
        },
        onCancelRequest = { message ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.cancelPaymentRequest(message)
            }
        },
        claimableTransfersEnabled = claimableTransfersEnabled,
        currentAccountId = viewModel.currentAccountId,
        transferClaims = transferClaims,
        onAcceptTransfer = { message ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.acceptTransfer(message, claimableTransfersEnabled)
            }
        },
        onRejectTransfer = { message, reason ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.rejectTransfer(message, reason, claimableTransfersEnabled)
            }
        },
        onReverseTransfer = { message, reason, pin ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.reverseTransfer(message, reason, pin, claimableTransfersEnabled)
            }
        },
        // A group payment needs the roster for two different reasons: to name the members an
        // announcement only carries ids for, and to offer the composer somebody to pay.
        groupPaymentsEnabled = groupPaymentsEnabled && currentChat.isGroup,
        groupPayments = groupPayments,
        groupPaymentRequestsEnabled = groupPaymentRequestsEnabled && currentChat.isGroup,
        groupPaymentRequests = groupPaymentRequests,
        groupPaymentRequestContributions = groupPaymentRequestContributions,
        onCreateGroupPaymentRequest = { amount, note, done ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.createGroupPaymentRequest(amount, note, groupPaymentRequestsEnabled, done)
            }
        },
        onContributeToGroupPaymentRequest = { requestId, amount, pin, done ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.contributeToGroupPaymentRequest(
                    requestId, amount, pin, groupPaymentRequestsEnabled, done,
                )
            }
        },
        onCancelGroupPaymentRequest = { requestId ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.cancelGroupPaymentRequest(requestId, groupPaymentRequestsEnabled)
            }
        },
        serverScheduledDirect = serverScheduledDirect,
        serverScheduledGroup = serverScheduledGroup,
        serverSchedulesHaveMore = serverSchedulesHaveMore,
        serverScheduleApproval = serverScheduleApproval,
        onLoadMoreServerSchedules = {
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.loadMoreServerSchedules()
            }
        },
        onCancelServerSchedule = { id, group ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.cancelServerSchedule(id, group)
            }
        },
        onDismissServerScheduleApproval = viewModel::dismissServerScheduleApproval,
        serverPaymentsSchedulingEnabled = if (currentChat.isGroup) {
            scheduledGroupPaymentsEnabled
        } else {
            scheduledChatPaymentsEnabled
        },
        onPrepareServerSchedule = { amount, note, at, ready ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.prepareServerSchedule(
                    amount, note, at,
                    enabled = if (currentChat.isGroup) {
                        scheduledGroupPaymentsEnabled
                    } else {
                        scheduledChatPaymentsEnabled
                    },
                    onReady = ready,
                )
            }
        },
        onPrepareServerGroupSchedule = { split, audience, selected, total, custom, note, at, key, ready ->
            var prepared = false
            val ran = runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                prepared = viewModel.prepareServerGroupSchedule(
                    split,
                    audience,
                    selected,
                    total,
                    custom,
                    note,
                    at,
                    key,
                    enabled = scheduledGroupPaymentsEnabled,
                    onReady = ready,
                )
            }
            ran && prepared
        },
        onCreateServerSchedule = { pin, done ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.createServerSchedule(
                    pin,
                    enabled = if (currentChat.isGroup) {
                        scheduledGroupPaymentsEnabled
                    } else {
                        scheduledChatPaymentsEnabled
                    },
                    onDone = done,
                )
            }
        },
        groupMembers = groupMembers,
        onSendGroupPayment = {
                splitMode,
                audience,
                selected,
                total,
                custom,
                note,
                pin,
                idempotencyKey,
                onSent,
            ->
            var sent = false
            val ran = runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                sent = viewModel.sendGroupPayment(
                    splitMode = splitMode,
                    audience = audience,
                    selected = selected,
                    totalInput = total,
                    customAmounts = custom,
                    note = note,
                    paymentPin = pin,
                    idempotencyKey = idempotencyKey,
                    groupPaymentsEnabled = groupPaymentsEnabled,
                    onSent = onSent,
                )
            }
            ran && sent
        },
        onAcceptGroupShare = { message ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.acceptGroupPaymentShare(message, groupPaymentsEnabled)
            }
        },
        onRejectGroupShare = { message, reason ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.rejectGroupPaymentShare(message, reason, groupPaymentsEnabled)
            }
        },
        onReverseGroupPayment = { message, reason, pin ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.reverseUnclaimedGroupPayment(message, reason, pin, groupPaymentsEnabled)
            }
        },
        // Dormant-feature guard: the composer hides the affordances, and these keep even a
        // stale composition from opening a picker while the release profile is text-only.
        onAttachLibrary = {
            if (BuildConfig.MEDIA_MESSAGING_ENABLED) {
                val request = PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                )
                // Multi-select appears only while the server has proven the album capability;
                // the send path re-proves the whole gate anyway, this just avoids offering a
                // selection the account could never send as one message.
                if (mediaAlbumsAvailable) {
                    pickLibraryMediaAlbum.launch(request)
                } else {
                    pickLibraryMedia.launch(request)
                }
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
        mediaFiles = mediaFiles,
        mediaLoading = mediaLoading,
        mediaErrors = mediaErrors,
        onOpenMedia = viewModel::openMedia,
        onRetryMedia = viewModel::retryMedia,
        onOpenAlbumItem = viewModel::openAlbumItem,
        onRetryAlbumItem = viewModel::retryAlbumItem,
        onOpenViewer = { message -> galleryMessageId = message.id },
        restoredDraft = restoredDraft,
        onRestoredDraftConsumed = viewModel::consumeRestoredDraft,
        onPersistDraft = viewModel::persistDraft,
        onComposerChanged = viewModel::onComposerChanged,
        schedulingEnabled = viewModel.schedulingAvailable && historyAvailable,
        onScheduleSend = viewModel::scheduleSend,
        onSchedulePaymentRequest = { amount, note, at, done ->
            runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                viewModel.schedulePaymentRequest(amount, note, at, done)
            }
        },
        onSendScheduledNow = { message ->
            if (message.kind == MessageKind.PAYMENT_REQUEST) {
                runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                    viewModel.sendScheduledNow(message)
                }
            } else {
                viewModel.sendScheduledNow(message)
            }
        },
        onRescheduleSend = { message, at ->
            if (message.kind == MessageKind.PAYMENT_REQUEST) {
                runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                    viewModel.rescheduleSend(message, at)
                }
            } else {
                viewModel.rescheduleSend(message, at)
            }
        },
        onCancelScheduledSend = { message ->
            if (message.kind == MessageKind.PAYMENT_REQUEST) {
                runFinancialAction(moneyMovementAllowed, onVerifyIdentityRequired) {
                    viewModel.cancelScheduledSend(message)
                }
            } else {
                viewModel.cancelScheduledSend(message)
            }
        },
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

/**
 * The date heading and the encryption notice, which sit above the first message.
 *
 * They are list items like any other, so a row's index in the thread is not its index in the list.
 */
private const val CONVERSATION_LEADING_ITEMS = 2

/** How often the thread re-checks which of one's own messages are still inside the edit window. */
private const val EDIT_WINDOW_TICK_MILLIS = 15_000L

/** How long a focus-jumped message keeps its highlight tint before fading back to normal. */
private const val FOCUS_HIGHLIGHT_MILLIS = 2_200L

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
    /**
     * The call this app is connected to right now, if any. Drawn as this thread's live-call
     * banner — and rerouting every call affordance to a reopen — only when it strictly matches
     * this chat; a call belonging elsewhere renders nothing here.
     */
    activeCallPresence: ActiveCallPresence? = null,
    /** Returns to the live call named by the id; never starts, answers or ends anything. */
    onReturnToActiveCall: (String) -> Unit = {},
    sending: Boolean,
    retryingMessageId: String?,
    error: String?,
    onClearError: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
    onRetry: (Message, () -> Unit) -> Unit,
    /** Account-level KYC gate. Message, media, reaction and call actions remain independent. */
    moneyMovementAllowed: Boolean = true,
    onVerifyIdentityRequired: () -> Unit = {},
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
    /** Whether this thread can send a payment to its members, and answer one it was sent. */
    groupPaymentsEnabled: Boolean = false,
    /** The server's live view of every group payment this thread mentions, keyed by lower-case id. */
    groupPayments: Map<String, GroupPaymentSummary> = emptyMap(),
    groupPaymentRequestsEnabled: Boolean = false,
    groupPaymentRequests: Map<String, GroupPaymentRequestDto> = emptyMap(),
    groupPaymentRequestContributions: Map<String, GroupPaymentRequestContributionDto> = emptyMap(),
    onCreateGroupPaymentRequest: (Long, String?, () -> Unit) -> Unit = { _, _, done -> done() },
    onContributeToGroupPaymentRequest: (String, Long, String, () -> Unit) -> Unit =
        { _, _, _, done -> done() },
    onCancelGroupPaymentRequest: (String) -> Unit = {},
    serverScheduledDirect: List<ScheduledPaymentDto> = emptyList(),
    serverScheduledGroup: List<ScheduledGroupPaymentDto> = emptyList(),
    serverSchedulesHaveMore: Boolean = false,
    serverScheduleApproval: ServerSchedulePreview? = null,
    onLoadMoreServerSchedules: () -> Unit = {},
    onCancelServerSchedule: (String, Boolean) -> Unit = { _, _ -> },
    onDismissServerScheduleApproval: () -> Unit = {},
    serverPaymentsSchedulingEnabled: Boolean = false,
    onPrepareServerSchedule: (Long, String?, Long, (ServerSchedulePreview) -> Unit) -> Unit =
        { _, _, _, _ -> },
    onPrepareServerGroupSchedule: (
        GroupPaymentSplitMode,
        GroupPaymentAudience,
        List<GroupPaymentDraftPolicy.Member>,
        String,
        Map<String, String>,
        String?,
        Long,
        String,
        (ServerSchedulePreview) -> Unit,
    ) -> Boolean = { _, _, _, _, _, _, _, _, _ -> false },
    onCreateServerSchedule: (String, () -> Unit) -> Unit = { _, done -> done() },
    groupMembers: List<ChatMember> = emptyList(),
    onSendGroupPayment: GroupPaymentSendHandler = {
            _, _, _, _, _, _, _, _, done ->
        done()
        true
    },
    onAcceptGroupShare: (Message) -> Unit = {},
    onRejectGroupShare: (Message, String?) -> Unit = { _, _ -> },
    onReverseGroupPayment: (Message, String?, String) -> Unit = { _, _, _ -> },
    onAttachLibrary: () -> Unit = {},
    onAttachCamera: () -> Unit = {},
    onAttachVideoNote: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onSendVoiceNote: (ByteArray) -> Unit = {},
    mediaEnabled: Boolean = false,
    mediaFiles: Map<String, SecureMediaFile> = emptyMap(),
    mediaLoading: Set<String> = emptySet(),
    mediaErrors: Map<String, String> = emptyMap(),
    onOpenMedia: (Message) -> Unit = {},
    onRetryMedia: (Message) -> Unit = {},
    onOpenAlbumItem: (Message, MessageMediaItem) -> Unit = { _, _ -> },
    onRetryAlbumItem: (Message, MessageMediaItem) -> Unit = { _, _ -> },
    onOpenViewer: (Message) -> Unit = {},
    reactionsEnabled: Boolean = false,
    onToggleReaction: (Message, String) -> Unit = { _, _ -> },
    /** The message the composer is currently answering, when one was picked. */
    replyTarget: Message? = null,
    onBeginReply: (Message) -> Unit = {},
    onCancelReply: () -> Unit = {},
    /** The message whose wording the composer is currently rewriting, when one was picked. */
    editTarget: Message? = null,
    /**
     * Whether the authenticated account may correct a message at all.
     *
     * Fail closed by default and separate from [sendEnabled]: a device can be perfectly able to
     * exchange messages while this account's server capability for corrections is still off, and
     * offering an Edit item then would only produce a refusal.
     */
    editsEnabled: Boolean = false,
    onBeginEdit: (Message) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    /**
     * Asks what became of a message this account sent.
     *
     * Separate from every send-side action: it changes nothing, and it is the one long-press item
     * that is about a message already gone rather than about saying something new.
     */
    onOpenMessageInfo: (Message) -> Unit = {},
    onSubmitEdit: (String, () -> Unit) -> Unit = { _, _ -> },
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
    var editState by remember { mutableStateOf(ConversationComposerState()) }
    // Ticked rather than read once, so an "Edit" option leaves the menu when its fifteen minutes
    // run out instead of lingering until something else happens to redraw the thread.
    var editClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(EDIT_WINDOW_TICK_MILLIS)
            editClock = System.currentTimeMillis()
        }
    }
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
        if (error != null) {
            composerState = composerState.releasedForRetry()
            editState = editState.releasedForRetry()
        }
    }
    // A correction is written in its own state rather than in the composer's, so half a sentence
    // someone had already typed survives being interrupted by a second thought about an earlier
    // message — and so the draft store, which mirrors the composer, is never handed wording that
    // belongs to a message already sent.
    LaunchedEffect(editTarget?.id) {
        editState = ConversationComposerState()
            .edited(editTarget?.text.orEmpty())
    }
    LaunchedEffect(Unit) {
        // Deliberately undebounced here, and deliberately not folded into the draft collector
        // above: `collectLatest` only lets *settled* text through, which would never refresh the
        // peer's bubble while someone is actually typing. The 300 ms debounce, the 4 s throttle
        // and stop-on-conversation-switch all live in KitTypingSignaller.
        snapshotFlow { composerState.text }.drop(1).collect(onComposerChanged)
    }
    var showRequestDialog by remember { mutableStateOf(false) }
    var showServerScheduleAmountDialog by remember { mutableStateOf(false) }
    var showScheduledGroupComposer by remember(chat.id) { mutableStateOf(false) }
    var scheduledGroupAt by remember(chat.id) { mutableStateOf<Long?>(null) }
    var payTarget by remember { mutableStateOf<Message?>(null) }
    var groupRequestPayTarget by remember {
        mutableStateOf<GroupPaymentRequestContributionTarget?>(null)
    }
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
    var showGroupPaymentComposer by remember(chat.id) { mutableStateOf(false) }
    // Declining a share and returning the unclaimed ones both explain themselves, for the same
    // reason a one-to-one reversal does.
    var groupAnswerTarget by remember { mutableStateOf<GroupPaymentAnswerPrompt?>(null) }
    LaunchedEffect(moneyMovementAllowed) {
        if (!moneyMovementAllowed) {
            showRequestDialog = false
            showServerScheduleAmountDialog = false
            showScheduledGroupComposer = false
            scheduledGroupAt = null
            payTarget = null
            groupRequestPayTarget = null
            scheduleTarget = null
            reasonTarget = null
            showGroupPaymentComposer = false
            groupAnswerTarget = null
            onDismissServerScheduleApproval()
        }
    }
    // A group payment names its members by id; only the roster can turn one into a name.
    val memberNames = remember(groupMembers) {
        groupMembers.associateBy({ it.userId.lowercase() }, { it.name })
    }
    val displayGroupMemberName: (String) -> String = remember(memberNames) {
        { userId -> memberNames[userId.lowercase()] ?: "Kit Pay user" }
    }
    // A voice note outlives the bubble that started it, and the floating bar still has to say who
    // is speaking and where — so the thread hands over everything only it can resolve, now.
    val voiceNoteChatContext = remember(chat.id, chat.name, chat.isGroup, displayGroupMemberName) {
        VoiceNoteChatContext(
            conversationId = chat.id,
            conversationTitle = chat.name,
            displayName = { userId ->
                when {
                    userId.isBlank() -> "You"
                    chat.isGroup -> displayGroupMemberName(userId)
                    else -> chat.name
                }
            },
        )
    }
    var conversationMenuOpen by remember(chat.id) { mutableStateOf(false) }
    // The call this app is in belongs to this thread only on a strict authenticated match —
    // the server's conversation linkage, or a direct chat's peer on the call's roster. Anything
    // else fails closed to "no live call here", so no surface can reopen somebody else's call.
    val liveCall = activeCallPresence?.takeIf {
        it.matchesChat(chatId = chat.id, isGroup = chat.isGroup, peerUserId = chat.peerUserId)
    }

    // Keep the newest message visible, just like WhatsApp: jump to the bottom the first time the
    // thread loads, then follow new messages only while the reader is already near the bottom.
    // A reader who scrolled up to older history is never yanked; a chip offers the way back.
    val listState = key(chat.id) { rememberLazyListState() }
    var openingBottomAnchorActive by remember(chat.id) { mutableStateOf(true) }
    val userIsDragging by listState.interactionSource.collectIsDraggedAsState()
    val conversationRows = remember(messages) { groupConversationRows(messages) }
    val conversationRowKeys = remember(conversationRows) { conversationRows.map(ConversationRow::key) }
    val messageIds = remember(messages) { messages.map(Message::id) }
    val groupPaymentHydrationKey = remember(groupPayments) {
        // The live map is capped by the ViewModel. Keep the complete immutable values so any card
        // content change that can alter its measured height restarts the narrowly guarded effect.
        groupPayments.toSortedMap().values.toList()
    }
    // A group writes a member's name once per run, not once per bubble. Computed over the whole
    // thread because the answer for any one message depends on what came before it.
    val senderNamedIds = remember(messages, chat.isGroup) {
        senderNamedMessageIds(messages, chat.isGroup)
    }
    var renderedMessageIds by remember(chat.id) { mutableStateOf<Set<String>?>(null) }
    var pendingNewMessages by remember(chat.id) { mutableStateOf(0) }
    val coroutineScopeForScroll = rememberCoroutineScope()
    // Tapping a quote takes the thread to what it quotes. A target that is not in the loaded
    // history does nothing at all, rather than scrolling somewhere arbitrary and calling it the
    // message: the quote itself already says which words it is, which is the useful half.
    val jumpToMessage: (String) -> Unit = { targetId ->
        val row = conversationRows.indexOfFirst { candidate ->
            candidate.messages.any { it.id == targetId }
        }
        if (row >= 0) {
            coroutineScopeForScroll.launch {
                listState.animateScrollToItem(row + CONVERSATION_LEADING_ITEMS)
            }
        }
    }
    // The list header adds two leading items (date + encryption notice) and a trailing spacer,
    // so the newest message sits just above the final index.
    val nearBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearBottom) {
        if (nearBottom) pendingNewMessages = 0
    }
    // The voice-note bar's body tap lands this thread on the exact message that is speaking. The
    // request is consumed before scrolling so it runs once; while it is pending for this thread,
    // the opening jump-to-newest stands down and lets the focus jump own first positioning.
    val rowMessageIds = remember(conversationRows) {
        conversationRows.map { row -> row.messages.map(Message::id) }
    }
    val focusRequest by ConversationFocusRequests.current.collectAsStateWithLifecycle()
    val focusAction = conversationFocusAction(focusRequest, chat.id, rowMessageIds)
    val focusPending = focusAction is ConversationFocusAction.Jump ||
        focusAction is ConversationFocusAction.Wait
    LaunchedEffect(userIsDragging, focusPending) {
        if (userIsDragging || focusPending) openingBottomAnchorActive = false
    }
    val currentRowMessageIds by rememberUpdatedState(rowMessageIds)
    val currentMessageIds by rememberUpdatedState(messageIds)
    var focusedMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    // Keyed on the thread, not the request: consuming the request must not cancel the collector
    // mid-jump. rememberUpdatedState feeds snapshotFlow because it only reacts to State reads.
    LaunchedEffect(chat.id) {
        combine(
            ConversationFocusRequests.current,
            snapshotFlow { currentRowMessageIds },
        ) { request, rows -> request to rows }
            .collect { (request, rows) ->
                request ?: return@collect
                when (val action = conversationFocusAction(request, chat.id, rows)) {
                    is ConversationFocusAction.Jump -> {
                        // Claim first positioning before consuming, so the scroll effect never
                        // sees "unpositioned, nothing pending" and jumps to the other end.
                        val firstPosition = renderedMessageIds == null
                        if (firstPosition) renderedMessageIds = currentMessageIds.toSet()
                        ConversationFocusRequests.consume(request)
                        val targetIndex = action.rowIndex + CONVERSATION_LEADING_ITEMS
                        snapshotFlow { listState.layoutInfo.totalItemsCount }
                            .first { totalItems -> totalItems > targetIndex }
                        focusedMessageId = request.messageId
                        if (firstPosition) {
                            listState.scrollToItem(targetIndex)
                        } else {
                            listState.animateScrollToItem(targetIndex)
                        }
                    }
                    // Loaded thread, no such message: the quote-jump rule — do nothing rather
                    // than scroll somewhere arbitrary — but consume so it cannot fire later.
                    ConversationFocusAction.Drop -> ConversationFocusRequests.consume(request)
                    ConversationFocusAction.Wait, ConversationFocusAction.Ignore -> Unit
                }
            }
    }
    LaunchedEffect(focusedMessageId) {
        if (focusedMessageId != null) {
            delay(FOCUS_HIGHLIGHT_MILLIS)
            focusedMessageId = null
        }
    }
    LaunchedEffect(chat.id, conversationRowKeys, messageIds, focusPending) {
        if (messages.isEmpty()) {
            // Keep null for a brand-new conversation composition so its first hydrated rows take
            // the explicit jump path. A thread whose existing rows were removed is already
            // positioned and can treat its next row as an ordinary addition.
            if (renderedMessageIds != null) renderedMessageIds = emptySet()
            pendingNewMessages = 0
            return@LaunchedEffect
        }
        val bottomIndex = conversationRows.size + CONVERSATION_LEADING_ITEMS
        val decision =
            conversationScrollDecision(
                renderedMessageIds,
                messages,
                nearBottom,
                focusPending,
                openingBottomAnchorActive,
            )
        when (decision.action) {
            ConversationScrollAction.JUMP_TO_NEWEST,
            ConversationScrollAction.FOLLOW_NEWEST,
            -> {
                // A preloaded encrypted projection can be present before LazyColumn has measured a
                // single row. Wait for this exact row set to exist before positioning; if a newer
                // projection arrives first this effect is cancelled and restarted for that set.
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { totalItems -> totalItems > bottomIndex }
                if (decision.action == ConversationScrollAction.JUMP_TO_NEWEST) {
                    listState.scrollToItem(bottomIndex)
                } else {
                    listState.animateScrollToItem(bottomIndex)
                }
                pendingNewMessages = 0
            }
            ConversationScrollAction.KEEP_POSITION -> {
                pendingNewMessages += decision.unseenMessages
            }
        }
        // While a focus jump still owns first positioning, leave the thread unpositioned here so
        // the jump's own claim (or its Drop) decides what happens, not a half-taken snapshot.
        if (!(focusPending && renderedMessageIds == null)) {
            renderedMessageIds = messageIds.toSet()
        }
    }
    // Room history, server history and rich-card hydration can all arrive after the first frame.
    // Until the person actually drags (or explicit target navigation owns the viewport), keep the
    // normal conversation opening pinned to the real laid-out tail rather than a stale row count.
    LaunchedEffect(chat.id, openingBottomAnchorActive, focusPending) {
        if (!openingBottomAnchorActive || focusPending) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.sumOf { it.size }
        }.distinctUntilChanged().collect { (count, _) ->
            if (count > 0 && openingBottomAnchorActive && !focusPending) {
                listState.scrollToItem(count - 1)
            }
        }
    }
    LaunchedEffect(chat.id, groupPaymentHydrationKey) {
        if (
            !shouldRepinAfterGroupPaymentHydration(
                conversationPositioned = renderedMessageIds != null,
                nearBottom = nearBottom,
                scrollInProgress = listState.isScrollInProgress,
            )
        ) return@LaunchedEffect

        // Let the hydrated payment card take its final size, then ask again. A finger drag or a
        // move into older history during that frame wins and cancels the correction.
        withFrameNanos { }
        if (
            !shouldRepinAfterGroupPaymentHydration(
                conversationPositioned = renderedMessageIds != null,
                nearBottom = nearBottom,
                scrollInProgress = listState.isScrollInProgress,
            )
        ) return@LaunchedEffect
        // Read the live tail rather than the row count captured when hydration began. The payment
        // announcement may insert its own row during the frame above; a stale index would then
        // point at that card and race the normal new-message follow effect.
        val tailIndex = listState.layoutInfo.totalItemsCount - 1
        if (tailIndex >= 0) {
            listState.scrollToItem(tailIndex)
        }
    }
    // A bubble below the fold is not an indicator. Follow it into view, but only for a reader who
    // is already at the bottom — the same rule a new message gets, for the same reason.
    LaunchedEffect(chat.id, chat.typing) {
        if (chat.typing && nearBottom) {
            listState.animateScrollToItem(conversationRows.size + 3)
        }
    }

    if (showRequestDialog) {
        PaymentRequestDialog(
            sending = sending,
            title = if (chat.isGroup && groupPaymentRequestsEnabled) {
                "Create group payment request"
            } else {
                "Request a payment"
            },
            explanation = if (chat.isGroup && groupPaymentRequestsEnabled) {
                "Members can contribute partially until the full amount is collected."
            } else {
                "The request is shared securely in this chat. Money moves only when they approve it."
            },
            schedulingEnabled = schedulingEnabled && !(chat.isGroup && groupPaymentRequestsEnabled),
            onDismiss = { showRequestDialog = false },
            onRequest = { amountMinor, note ->
                if (chat.isGroup && groupPaymentRequestsEnabled) {
                    onCreateGroupPaymentRequest(amountMinor, note) { showRequestDialog = false }
                } else {
                    onSendPaymentRequest(amountMinor, note) { showRequestDialog = false }
                }
            },
            onRequestLater = { amountMinor, note ->
                scheduleTarget = ScheduleTarget.Request(amountMinor, note)
            },
        )
    }
    if (showServerScheduleAmountDialog) {
        PaymentRequestDialog(
            sending = sending,
            title = if (chat.isGroup) "Schedule a group payment" else "Schedule a payment",
            explanation = if (chat.isGroup) {
                "The member list and exact even split will be previewed and frozen when you approve."
            } else {
                "The payment is held by Kit and executes on the server at the selected time."
            },
            confirmLabel = "Choose time",
            onDismiss = { showServerScheduleAmountDialog = false },
            onRequest = { amount, note ->
                scheduleTarget = ScheduleTarget.ServerPayment(amount, note)
                showServerScheduleAmountDialog = false
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
                is ScheduleTarget.ServerPayment -> "Schedule payment"
                ScheduleTarget.ServerGroupPayment -> "Schedule group payment"
                is ScheduleTarget.Existing -> "Change the send time"
            },
            confirmLabel = if (target is ScheduleTarget.Existing) "Reschedule" else "Schedule",
            nowEpochMillis = openedAt,
            initialEpochMillis = (target as? ScheduleTarget.Existing)
                ?.message
                ?.scheduledAtEpochMillis,
            explanation = if (
                target is ScheduleTarget.ServerPayment || target == ScheduleTarget.ServerGroupPayment
            ) {
                "Kit schedules this payment on the server after you approve the exact amount and time."
            } else {
                "It stays on this device, encrypted, until it goes out."
            },
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
                    is ScheduleTarget.ServerPayment -> {
                        onPrepareServerSchedule(
                            target.amountMinor,
                            target.note,
                            atEpochMillis,
                        ) { }
                    }
                    ScheduleTarget.ServerGroupPayment -> {
                        scheduledGroupAt = atEpochMillis
                        showScheduledGroupComposer = true
                    }
                    is ScheduleTarget.Existing -> onRescheduleSend(target.message, atEpochMillis)
                }
                scheduleTarget = null
            },
        )
    }
    if (showScheduledGroupComposer && chat.isGroup && serverPaymentsSchedulingEnabled) {
        val scheduledAt = scheduledGroupAt
        if (scheduledAt != null) {
            GroupPaymentComposerDialog(
                members = groupMembers,
                currencyCode = groupPayments.values.firstOrNull()?.currencyCode ?: Money.SYMBOL,
                sending = sending,
                error = error,
                biometricsAvailable = biometricsAvailable,
                onDismiss = { showScheduledGroupComposer = false },
                onSend = { _, _, _, _, _, _, _, _, _ -> false },
                title = "Schedule a group payment",
                explanation = "Choose who is paid and how to split the amount. Kit freezes the " +
                    "reviewed recipients and sends it from the server at the selected time.",
                onReview = { split, audience, selected, total, custom, note, key, done ->
                    onPrepareServerGroupSchedule(
                        split,
                        audience,
                        selected,
                        total,
                        custom,
                        note,
                        scheduledAt,
                        key,
                    ) {
                        done()
                    }
                },
            )
        }
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
    groupRequestPayTarget?.let { target ->
        GroupPaymentRequestContributionDialog(
            request = target.request,
            useRemainingInitially = target.useRemaining,
            sending = sending,
            error = error,
            onDismiss = { groupRequestPayTarget = null },
            onConfirm = { amount, pin ->
                onContributeToGroupPaymentRequest(
                    target.request.id,
                    amount,
                    pin,
                ) {
                    groupRequestPayTarget = null
                }
            },
        )
    }
    serverScheduleApproval?.let { approval ->
        PaymentApprovalDialog(
            amountText = Money.format(
                approval.amountMinor,
                approval.currencyCode,
                approval.currencyScale,
            ),
            detail = buildString {
                append("To ").append(GroupPaymentCopy.nameList(approval.recipientNames)
                    ?: "the selected recipient")
                append(" · ").append(
                    formatScheduledFor(
                        approval.scheduledAtEpochMillis,
                        System.currentTimeMillis(),
                    ),
                )
                append(" · ").append(java.time.ZoneId.systemDefault().id)
            },
            sending = sending,
            error = error,
            biometricsAvailable = biometricsAvailable,
            onDismiss = onDismissServerScheduleApproval,
            onConfirm = { pin ->
                onCreateServerSchedule(pin) { }
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
    if (showGroupPaymentComposer && groupPaymentsEnabled) {
        GroupPaymentComposerDialog(
            members = groupMembers,
            // The wallet decides the currency; this is the label on the fields, and the draft
            // policy re-reads the real one before a single minor unit moves.
            currencyCode = groupPayments.values.firstOrNull()?.currencyCode ?: Money.SYMBOL,
            sending = sending,
            error = error,
            biometricsAvailable = biometricsAvailable,
            onDismiss = { showGroupPaymentComposer = false },
            onSend = onSendGroupPayment,
        )
    }
    groupAnswerTarget?.let { prompt ->
        GroupPaymentAnswerDialog(
            returningUnclaimed = prompt.returningUnclaimed,
            sending = sending,
            error = error,
            biometricsAvailable = biometricsAvailable,
            onDismiss = { groupAnswerTarget = null },
            onConfirm = { reason, pin ->
                if (prompt.returningUnclaimed) {
                    onReverseGroupPayment(prompt.message, reason, pin)
                } else {
                    onRejectGroupShare(prompt.message, reason)
                }
                groupAnswerTarget = null
            },
        )
    }

    ProvideVoiceNoteChatContext(voiceNoteChatContext) {
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
                        KitAvatar(
                            chat.name,
                            size = 40.dp,
                            online = chat.online,
                            avatarUrl = chat.avatarUrl,
                            isGroup = chat.isGroup,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            VerifiedAccountName(
                                name = chat.name,
                                verification = chat.accountVerification,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                    // While this thread's own call is live, both icons return to it — starting a
                    // second call over the first is never on offer.
                    IconButton(
                        enabled = liveCall != null || chat.peerUserId != null,
                        onClick = {
                            val live = liveCall
                            if (live != null) {
                                onReturnToActiveCall(live.callId)
                            } else {
                                chat.peerUserId?.let(onVideoCall)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Videocam,
                            contentDescription =
                                if (liveCall != null) "Return to call" else "Video call",
                        )
                    }
                    IconButton(
                        enabled = liveCall != null || chat.peerUserId != null,
                        onClick = {
                            val live = liveCall
                            if (live != null) {
                                onReturnToActiveCall(live.callId)
                            } else {
                                chat.peerUserId?.let(onVoiceCall)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Call,
                            contentDescription =
                                if (liveCall != null) "Return to call" else "Voice call",
                        )
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
                if (editTarget != null) {
                    EditComposerBar(target = editTarget, onCancel = onCancelEdit)
                } else {
                    replyTarget?.let { target ->
                        ReplyComposerBar(
                            target = target,
                            peerName = chat.name,
                            onCancel = onCancelReply,
                        )
                    }
                }
                Composer(
                    draft = if (editTarget != null) editState.text else composerState.text,
                    onDraft = {
                        if (editTarget != null) {
                            editState = editState.edited(it)
                        } else {
                            composerState = composerState.edited(it)
                        }
                        if (error != null) onClearError()
                    },
                    onSend = {
                        // Null means this exact composer content is already on its way, which is
                        // what a second tap before the durable commit looks like.
                        if (editTarget != null) {
                            editState.submitted()?.let { submitted ->
                                editState = submitted
                                onSubmitEdit(submitted.text) {
                                    editState = editState.clearIfUnchanged(submitted)
                                }
                            }
                        } else {
                            composerState.submitted()?.let { submitted ->
                                composerState = submitted
                                onSend(submitted.text) {
                                    composerState = composerState.clearIfUnchanged(submitted)
                                }
                            }
                        }
                    },
                    submissionInFlight = if (editTarget != null) {
                        editState.submittedGeneration != null
                    } else {
                        composerState.submittedGeneration != null
                    },
                    voiceDraftKey = chat.id,
                    onAttachLibrary = onAttachLibrary,
                    onAttachCamera = onAttachCamera,
                    onAttachVideoNote = onAttachVideoNote,
                    onAttachDocument = onAttachDocument,
                    onSendVoiceNote = onSendVoiceNote,
                    // A correction replaces words with words. Everything that would instead start
                    // a *new* message — an attachment, a voice note, a payment, a send-later — is
                    // withdrawn while the mode is open, so no gesture can quietly leave it.
                    mediaEnabled = mediaEnabled && editTarget == null,
                    onRequestPayment = {
                        runFinancialAction(
                            moneyMovementAllowed,
                            onVerifyIdentityRequired,
                        ) {
                            if (error != null) onClearError()
                            showRequestDialog = true
                        }
                    },
                    groupPaymentsEnabled = groupPaymentsEnabled && editTarget == null,
                    paymentsEnabled = editTarget == null,
                    onPayGroup = {
                        runFinancialAction(
                            moneyMovementAllowed,
                            onVerifyIdentityRequired,
                        ) {
                            if (error != null) onClearError()
                            showGroupPaymentComposer = true
                        }
                    },
                    serverPaymentsSchedulingEnabled =
                        serverPaymentsSchedulingEnabled && editTarget == null,
                    onSchedulePayment = {
                        runFinancialAction(
                            moneyMovementAllowed,
                            onVerifyIdentityRequired,
                        ) {
                            if (error != null) onClearError()
                            if (chat.isGroup) {
                                scheduleTarget = ScheduleTarget.ServerGroupPayment
                            } else {
                                showServerScheduleAmountDialog = true
                            }
                        }
                    },
                    sendEnabled = sendEnabled,
                    editing = editTarget != null,
                    schedulingEnabled = schedulingEnabled && editTarget == null,
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
        val currentOnOpenCamera by rememberUpdatedState(onOpenCamera)
        val cameraPullConnection = remember(mediaEnabled, cameraPullMaxPx, cameraPullThresholdPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val userInput = source == NestedScrollSource.UserInput
                    if (shouldReleaseOpeningBottomAnchor(userInput, available.y)) {
                        openingBottomAnchorActive = false
                    }
                    if (!mediaEnabled || !userInput) return Offset.Zero
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

                /**
                 * Where the release is decided, and the only place it is.
                 *
                 * A scroll container dispatches this from the end of its drag — one call, once the
                 * finger is genuinely off the glass — which is the same signal pull-to-refresh
                 * settles on. The gesture used to watch pointers on the surrounding box instead and
                 * open from there; that never fired for a pull the list had taken over, so the panel
                 * kept promising a camera that never arrived.
                 */
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!mediaEnabled || cameraRevealPx <= 0f) return Velocity.Zero
                    val open = CameraPull.shouldOpen(cameraRevealPx, cameraPullThresholdPx)
                    // Settle the panel first either way, so the reveal never stays stuck open.
                    animate(cameraRevealPx, 0f) { value, _ -> cameraRevealPx = value }
                    if (open) currentOnOpenCamera()
                    // The reveal — not the list — absorbed the drag that built this velocity, so the
                    // list must not fling on it and carry the thread away under the camera.
                    return available
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(cameraPullConnection),
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
            item(key = "conversation-date-heading") {
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
            item(key = "conversation-encryption-notice") {
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
                val rowFocused = focusedMessageId != null &&
                    conversationRows[i].messages.any { it.id == focusedMessageId }
                val focusTint by animateColorAsState(
                    targetValue = if (rowFocused) {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                    } else {
                        Color.Transparent
                    },
                    label = "conversationFocusHighlight",
                )
                // Inline wrapper so the group branch's return@items still returns from the
                // items lambda; the tint briefly marks the row a focus jump landed on.
                Box(Modifier.fillMaxWidth().background(focusTint)) {
                val group = conversationRows[i] as? ConversationRow.ImageGroup
                if (group != null) {
                    ImageGroupBubble(
                        messages = group.messages,
                        fromMe = group.messages.first().fromMe,
                        mediaFiles = mediaFiles,
                        mediaLoading = mediaLoading,
                        mediaErrors = mediaErrors,
                        onOpenMedia = onOpenMedia,
                        onOpenViewer = onOpenViewer,
                        reactable = reactionsEnabled,
                        onToggleReaction = onToggleReaction,
                        reportableMessageIds = reportableMessageIds,
                        onReportMessage = onReportMessage,
                        showSenderName = group.messages.first().id in senderNamedIds,
                    )
                    return@items
                }
                val message = (conversationRows[i] as ConversationRow.Single).message
                if (message.kind == MessageKind.CALL) {
                    CallLogBubble(
                        msg = message,
                        onCall = {
                            // With this thread's call already live, the log row returns to it
                            // instead of redialing a second call over the first.
                            val live = liveCall
                            if (live != null) {
                                onReturnToActiveCall(live.callId)
                            } else {
                                message.callDirection?.let {
                                    chat.peerUserId?.let { peer ->
                                        if (message.callVideo) {
                                            onVideoCall(peer)
                                        } else {
                                            onVoiceCall(peer)
                                        }
                                    }
                                }
                            }
                        },
                    )
                } else if (message.kind == MessageKind.GROUP_PAYMENT_REQUEST) {
                    val requestId = message.groupPaymentRequestId
                    if (requestId != null) {
                        val hint = message.mediaDescriptor?.let(KitGroupPaymentRequestMessage::parse)
                        GroupPaymentRequestChatCard(
                            request = groupPaymentRequests[requestId.lowercase()],
                            fallbackAmountMinor = hint?.amountMinor,
                            fallbackCurrencyCode = hint?.currencyCode ?: "UGX",
                            fallbackCurrencyScale = hint?.currencyScale ?: Money.SCALE,
                            fallbackNote = message.text.takeIf(String::isNotBlank),
                            displayName = displayGroupMemberName,
                            busy = sending,
                            onPayRemaining = { request ->
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) {
                                    groupRequestPayTarget = GroupPaymentRequestContributionTarget(
                                        request,
                                        true,
                                    )
                                }
                            },
                            onPayPartial = { request ->
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) {
                                    groupRequestPayTarget = GroupPaymentRequestContributionTarget(
                                        request,
                                        false,
                                    )
                                }
                            },
                            onCancel = {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) { onCancelGroupPaymentRequest(requestId) }
                            },
                        )
                    }
                } else if (message.kind == MessageKind.GROUP_PAYMENT_REQUEST_EVENT) {
                    val request = message.groupPaymentRequestId
                        ?.lowercase()?.let(groupPaymentRequests::get)
                    val contribution = message.groupPaymentRequestContributionId
                        ?.lowercase()?.let(groupPaymentRequestContributions::get)
                    groupPaymentRequestEventText(
                        message = message,
                        request = request,
                        contribution = contribution,
                        displayName = displayGroupMemberName,
                    )?.let { summary -> TimelineNotice(summary) }
                } else if (message.kind == MessageKind.GROUP_PAYMENT) {
                    // Only ever drawn from a descriptor the projection already vouched for, so a
                    // null here means the message is not one and nothing should be drawn at all.
                    message.groupPaymentDescriptor()?.let { descriptor ->
                        val payment = groupPayments[descriptor.groupPaymentId]
                        GroupPaymentChatCard(
                            descriptor = descriptor,
                            payment = payment,
                            // Buttons are drawn from the server's answer, and only while that
                            // answer is about the payment this card announces.
                            contradictsServer = payment != null &&
                                !descriptor.matchesAuthoritativePayment(payment),
                            isOutgoing = message.fromMe,
                            senderName = message.senderName?.takeIf(String::isNotBlank)
                                ?: message.senderUserId?.let(displayGroupMemberName)
                                ?: chat.name,
                            displayName = displayGroupMemberName,
                            isBusy = sending,
                            onAccept = {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) { onAcceptGroupShare(message) }
                            },
                            onDecline = {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) {
                                    groupAnswerTarget = GroupPaymentAnswerPrompt(
                                        message,
                                        returningUnclaimed = false,
                                    )
                                }
                            },
                            onReturnUnclaimed = {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) {
                                    groupAnswerTarget = GroupPaymentAnswerPrompt(
                                        message,
                                        returningUnclaimed = true,
                                    )
                                }
                            },
                        )
                    }
                } else if (message.kind == MessageKind.GROUP_PAYMENT_EVENT) {
                    // One member's answer, in gold, said the way a date heading is said.
                    message.groupPaymentDescriptor()?.let { descriptor ->
                        GroupPaymentCopy.outcome(
                            action = descriptor.action,
                            actorName = message.senderName?.takeIf(String::isNotBlank)
                                ?: message.senderUserId?.let(displayGroupMemberName)
                                ?: "A member",
                            isViewerActor = message.fromMe,
                        )?.let { GroupPaymentOutcomeChip(it) }
                    }
                } else if (message.kind == MessageKind.PAYMENT_EVENT) {
                    // Outcomes are the conversation talking about itself, not either person
                    // talking. They read like the encryption notice: centred, unattributed.
                    TimelineNotice(paymentEventSummary(message, chat.name))
                } else if (message.kind == MessageKind.SCHEDULED_PAYMENT_EVENT) {
                    message.mediaDescriptor?.let(KitScheduledPaymentMessage::parse)?.let {
                        TimelineNotice(scheduledPaymentEventSummary(it, message.fromMe, chat.name))
                    }
                } else if (message.kind == MessageKind.SCHEDULED_GROUP_PAYMENT_EVENT) {
                    message.mediaDescriptor?.let(KitScheduledGroupPaymentOutcomeMessage::parse)?.let {
                        TimelineNotice(
                            if (it.action == KitScheduledGroupPaymentOutcomeAction.FAILED) {
                                "Your scheduled group payment could not be sent"
                            } else {
                                "Your scheduled group payment was cancelled"
                            },
                        )
                    }
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
                        media = mediaFiles[message.id],
                        mediaLoading = message.id in mediaLoading,
                        mediaError = mediaErrors[message.id],
                        onOpenMedia = { onOpenMedia(message) },
                        onRetryMedia = { onRetryMedia(message) },
                        onOpenViewer = { onOpenViewer(message) },
                        albumMediaFiles = mediaFiles,
                        albumMediaLoading = mediaLoading,
                        albumMediaErrors = mediaErrors,
                        onOpenAlbumItem = { item -> onOpenAlbumItem(message, item) },
                        onRetryAlbumItem = { item -> onRetryAlbumItem(message, item) },
                        reactable = reactionsEnabled && message.acceptsReactions,
                        onToggleReaction = { emoji -> onToggleReaction(message, emoji) },
                        replyable = sendEnabled && message.acceptsReplies,
                        onReply = { onBeginReply(message) },
                        editable = sendEnabled && editsEnabled &&
                            message.acceptsEdits(editClock),
                        onEdit = { onBeginEdit(message) },
                        infoable = sendEnabled && message.acceptsDeliveryInfo,
                        onInfo = { onOpenMessageInfo(message) },
                        onJumpToQuoted = jumpToMessage,
                        quotedAuthorFallback = chat.name,
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
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) {
                                payTarget = message
                                shortfallForPaymentRequest(message)?.let(onTopUpNeeded)
                            }
                        },
                        onDeclineRequest = {
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) { onDeclineRequest(message) }
                        },
                        onCancelRequest = {
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) { onCancelRequest(message) }
                        },
                        onAcceptTransfer = {
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) { onAcceptTransfer(message) }
                        },
                        onRejectTransfer = {
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) {
                                reasonTarget = TransferReasonPrompt(message, reverse = false)
                            }
                        },
                        onReverseTransfer = {
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) {
                                reasonTarget = TransferReasonPrompt(message, reverse = true)
                            }
                        },
                        onSendScheduledNow = {
                            if (message.kind == MessageKind.PAYMENT_REQUEST) {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) { onSendScheduledNow(message) }
                            } else {
                                onSendScheduledNow(message)
                            }
                        },
                        onEditSchedule = {
                            if (message.kind == MessageKind.PAYMENT_REQUEST) {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) { scheduleTarget = ScheduleTarget.Existing(message) }
                            } else {
                                scheduleTarget = ScheduleTarget.Existing(message)
                            }
                        },
                        onCancelSchedule = {
                            if (message.kind == MessageKind.PAYMENT_REQUEST) {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                ) { onCancelScheduledSend(message) }
                            } else {
                                onCancelScheduledSend(message)
                            }
                        },
                        showSenderName = message.id in senderNamedIds,
                    )
                }
                }
            }
            serverScheduledDirect.forEach { schedule ->
                item(key = "server-schedule:${schedule.id}") {
                    ServerScheduledPaymentCard(
                        amount = schedule.amount,
                        currencyCode = schedule.currency.code,
                        scheduledFor = schedule.scheduledFor,
                        note = schedule.note,
                        group = false,
                        busy = sending,
                        onCancel = {
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) { onCancelServerSchedule(schedule.id, false) }
                        },
                    )
                }
            }
            serverScheduledGroup.forEach { schedule ->
                item(key = "server-group-schedule:${schedule.id}") {
                    ServerScheduledPaymentCard(
                        amount = schedule.totalAmount,
                        currencyCode = schedule.currency.code,
                        scheduledFor = schedule.scheduledFor,
                        note = schedule.note,
                        group = true,
                        busy = sending,
                        onCancel = {
                            runFinancialAction(
                                moneyMovementAllowed,
                                onVerifyIdentityRequired,
                            ) { onCancelServerSchedule(schedule.id, true) }
                        },
                    )
                }
            }
            if (serverSchedulesHaveMore) {
                item(key = "server-schedules-more") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = {
                                runFinancialAction(
                                    moneyMovementAllowed,
                                    onVerifyIdentityRequired,
                                    onLoadMoreServerSchedules,
                                )
                            },
                            enabled = !sending,
                        ) {
                            Text("Load older scheduled payments")
                        }
                    }
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
            item(key = "conversation-tail") { Spacer(Modifier.height(8.dp)) }
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
        // This thread's own live call, floating over the top of the list the way the new-message
        // chip floats over the bottom. Tapping it returns to the call; nothing here starts one.
        liveCall?.let { call ->
            LiveCallBanner(
                call = call,
                onReturn = { onReturnToActiveCall(call.callId) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
            )
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
    media: SecureMediaFile? = null,
    mediaLoading: Boolean = false,
    mediaError: String? = null,
    onOpenMedia: () -> Unit = {},
    onRetryMedia: () -> Unit = {},
    onOpenViewer: () -> Unit = {},
    /**
     * Album items key the same media maps per attachment via [albumItemMediaKey], so an album
     * bubble needs the whole maps where a single-attachment bubble needs one slice.
     */
    albumMediaFiles: Map<String, SecureMediaFile> = emptyMap(),
    albumMediaLoading: Set<String> = emptySet(),
    albumMediaErrors: Map<String, String> = emptyMap(),
    onOpenAlbumItem: (MessageMediaItem) -> Unit = {},
    onRetryAlbumItem: (MessageMediaItem) -> Unit = {},
    /** False while secure messaging is unavailable, so the palette is never offered offline. */
    reactable: Boolean = false,
    onToggleReaction: (String) -> Unit = {},
    /** False while secure messaging is unavailable, or for a bubble nobody can answer yet. */
    replyable: Boolean = false,
    onReply: () -> Unit = {},
    /** True only for one's own message, inside the fifteen minutes it may still be reworded. */
    editable: Boolean = false,
    onEdit: () -> Unit = {},
    /** True only for one's own message, which is the only one the server will report on. */
    infoable: Boolean = false,
    onInfo: () -> Unit = {},
    /** Takes the thread to the message this one quotes. */
    onJumpToQuoted: (String) -> Unit = {},
    /**
     * Who to credit a quoted message to when the message itself names nobody.
     *
     * A direct chat labels no bubble, because the header already says who the other person is —
     * so a quote of their message has nothing to read off, and the thread supplies their name.
     */
    quotedAuthorFallback: String = "",
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
    /**
     * Whether this bubble opens a run by its author, and so carries their name.
     *
     * The thread decides, not the bubble: a name is a heading for a stretch of messages, and the
     * bubble cannot see what came before it. A direct chat never labels anything, so the default
     * of true costs it nothing.
     */
    showSenderName: Boolean = true,
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
    val copyableText = msg.copyablePlaintext()
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

    SwipeToReplyRow(
        enabled = replyable,
        onReply = onReply,
        modifier = Modifier.fillMaxWidth(),
    ) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(if (msg.fromMe) Alignment.CenterEnd else Alignment.CenterStart)
                // The chip row is drawn REACTION_CHIP_OVERLAP higher than it is laid out, so that
                // much of the gap below is already spoken for.
                .padding(bottom = if (msg.reactions.isEmpty()) 8.dp else 2.dp)
                .widthIn(max = 300.dp),
        ) {
            DropdownMenu(
                expanded = actionMenuOpen,
                onDismissRequest = { actionMenuOpen = false },
            ) {
                // First in the menu because it is the commonest thing to want, and because the
                // swipe that also does it is not something a screen reader can perform.
                if (replyable) {
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        onClick = {
                            actionMenuOpen = false
                            onReply()
                        },
                    )
                }
                if (editable) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            actionMenuOpen = false
                            onEdit()
                        },
                    )
                }
                if (infoable) {
                    DropdownMenuItem(
                        text = { Text("Info") },
                        onClick = {
                            actionMenuOpen = false
                            onInfo()
                        },
                    )
                }
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
                    copyableText != null || reactable || replyable || editable || infoable ||
                    reportable || msg.isScheduledEntry
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
                    // Within a run by one member it is written once, on the message that opens it.
                    if (!msg.fromMe && showSenderName) {
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
                        val quotedAuthor = when {
                            msg.replyToFromMe -> "You"
                            else -> msg.replyToSenderName?.takeIf(String::isNotBlank)
                                ?: quotedAuthorFallback.takeIf(String::isNotBlank)
                                ?: "Message"
                        }
                        QuotedMessagePreview(
                            author = quotedAuthor,
                            preview = reply,
                            accent = kitNameAccent(quotedAuthor),
                            // Tinted from the bubble's own text rather than the theme, so the quote
                            // sits inside whichever of the two bubble colours is around it.
                            background = contentColor.copy(alpha = 0.10f),
                            onClick = msg.replyToMessageId?.let { target ->
                                { onJumpToQuoted(target) }
                            },
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
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
                            media = media,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                        )
                        MessageKind.VIDEO -> SecureVideoContent(
                            msg = msg,
                            media = media,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                            onOpenViewer = onOpenViewer,
                        )
                        MessageKind.DOCUMENT -> SecureDocumentContent(
                            msg = msg,
                            media = media,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                        )
                        MessageKind.IMAGE -> SecureImageContent(
                            msg = msg,
                            media = media,
                            mediaLoading = mediaLoading,
                            mediaError = mediaError,
                            onOpenMedia = onOpenMedia,
                            onRetryMedia = onRetryMedia,
                            onOpenViewer = onOpenViewer,
                        )
                        MessageKind.MEDIA_ALBUM -> MediaAlbumContent(
                            msg = msg,
                            mediaFiles = albumMediaFiles,
                            mediaLoading = albumMediaLoading,
                            mediaErrors = albumMediaErrors,
                            onOpenItem = onOpenAlbumItem,
                            onRetryItem = onRetryAlbumItem,
                        )
                        MessageKind.UNSUPPORTED_ATTACHMENT ->
                            Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                        // PAYMENT_EVENT, SYSTEM and both group-payment kinds never reach a bubble
                        // — the list renders the golden card and the outcome line full width — and
                        // CALL is handled by CallLogBubble before this point. [presentableText]
                        // keeps reserved media-namespace bytes from persisted or scheduled
                        // sources out of the one branch that renders raw text.
                        MessageKind.TEXT,
                        MessageKind.PAYMENT_EVENT,
                        MessageKind.GROUP_PAYMENT,
                        MessageKind.GROUP_PAYMENT_EVENT,
                        MessageKind.GROUP_PAYMENT_REQUEST,
                        MessageKind.GROUP_PAYMENT_REQUEST_EVENT,
                        MessageKind.SCHEDULED_PAYMENT_EVENT,
                        MessageKind.SCHEDULED_GROUP_PAYMENT_EVENT,
                        MessageKind.SYSTEM,
                        MessageKind.CALL,
                        -> Text(msg.presentableText(), style = MaterialTheme.typography.bodyLarge)
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
                        if (msg.editedAtEpochMillis > 0) {
                            // Beside the original time, not instead of it: the message still
                            // belongs where it was said, and the marker only admits it was
                            // reworded afterwards.
                            Text(
                                "Edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.65f),
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
                // Riding up over the bubble's bottom edge is what makes a reaction read as part
                // of the message rather than a separate little row underneath it. The rim is the
                // thread's own background, so the chip looks punched through the bubble.
                ringColor = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .align(if (msg.fromMe) Alignment.End else Alignment.Start)
                    .offset(y = -REACTION_CHIP_OVERLAP)
                    .padding(horizontal = 12.dp),
            )
        }
    }
    }
}

/**
 * The message being answered, drawn above the answer and inside the composer alike.
 *
 * One composable for both places on purpose: what someone is about to quote and what they ended up
 * quoting should be recognisably the same object, so choosing a message and reading the result
 * never feel like two different things.
 */
@Composable
internal fun QuotedMessagePreview(
    author: String,
    preview: String,
    accent: Color,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min),
    ) {
        // The bar down the leading edge is what makes a quote read as a quote at a glance, before
        // any of its words are. It takes the author's own accent, the same one their name carries
        // at the top of a bubble, so a run of answers to one person stays legible as such.
        Box(
            Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(accent),
        )
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                author,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.85f),
            )
        }
    }
}

/**
 * The bar above the composer while an answer is being written.
 *
 * It stays until the message is sent or the cross is tapped, because "which message am I
 * answering" is a thing someone can lose track of between picking it and finishing the sentence.
 */
@Composable
internal fun ReplyComposerBar(
    target: Message,
    peerName: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val author = when {
        target.fromMe -> "You"
        else -> target.senderName?.takeIf(String::isNotBlank)
            ?: peerName.takeIf(String::isNotBlank)
            ?: "Message"
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuotedMessagePreview(
            author = author,
            preview = target.replyPreviewLabel(),
            accent = kitNameAccent(author),
            background = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel reply")
        }
    }
}

/**
 * The bar above the composer while a message is being corrected.
 *
 * It shows the wording as it currently stands, so the change can be read against what everyone
 * else is still looking at, and it names the mode outright — an edit and an answer both put a
 * quote above the keyboard, and only the heading tells them apart.
 */
@Composable
internal fun EditComposerBar(
    target: Message,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        QuotedMessagePreview(
            author = "Edit message",
            preview = target.replyPreviewLabel(),
            accent = MaterialTheme.colorScheme.primary,
            background = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel edit")
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

/**
 * Which group payment is being answered, and whether the answer moves other people's money.
 *
 * Declining touches only this member's own share; returning the unclaimed shares is the sender
 * pulling back everybody else's, which is why only one of the two is approved.
 */
private data class GroupPaymentAnswerPrompt(
    val message: Message,
    val returningUnclaimed: Boolean,
)

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

    data class ServerPayment(val amountMinor: Long, val note: String?) : ScheduleTarget

    data object ServerGroupPayment : ScheduleTarget

    /** An entry already in the queue, being moved to a different time. */
    data class Existing(val message: Message) : ScheduleTarget
}

internal data class GroupPaymentRequestContributionTarget(
    val request: GroupPaymentRequestDto,
    val useRemaining: Boolean,
)


@Composable
private fun PaymentRequestDialog(
    sending: Boolean,
    onDismiss: () -> Unit,
    onRequest: (Long, String?) -> Unit,
    title: String = "Request a payment",
    explanation: String =
        "The request is shared securely in this chat. Money moves only when they approve it.",
    confirmLabel: String = "Request",
    schedulingEnabled: Boolean = false,
    onRequestLater: (Long, String?) -> Unit = { _, _ -> },
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amountText)
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(
                    explanation,
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
                    onValueChange = { note = it.take(280) },
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
            ) { Text(if (sending) "Sending…" else confirmLabel) }
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

@Composable
private fun GroupPaymentRequestContributionDialog(
    request: GroupPaymentRequestDto,
    useRemainingInitially: Boolean,
    sending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    val scale = request.currencyScale ?: return
    val remaining = request.remainingMinor ?: return
    var amountText by remember(request.id, remaining, useRemainingInitially) {
        mutableStateOf(
            if (useRemainingInitially) {
                java.math.BigDecimal.valueOf(remaining, scale).stripTrailingZeros().toPlainString()
            } else {
                ""
            },
        )
    }
    var pin by remember(request.id) { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amountText, scale)
    val validAmount = amountMinor != null && amountMinor in 1..remaining
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Contribute to request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Remaining: ${Money.format(remaining, request.currency.code, scale)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { value ->
                        amountText = value.filter { it.isDigit() || it == '.' }
                    },
                    enabled = !sending,
                    label = { Text("Amount (${request.currency.code})") },
                    visualTransformation = GroupedAmountTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountText.isNotEmpty() && !validAmount,
                    supportingText = if (amountText.isNotEmpty() && !validAmount) {
                        { Text("Enter no more than the remaining amount") }
                    } else null,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    enabled = !sending,
                    label = { Text("Wallet PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && validAmount && pin.isNotBlank(),
                onClick = { onConfirm(checkNotNull(amountMinor), pin) },
            ) { Text(if (sending) "Contributing…" else "Contribute") }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
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
    detail: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Pay $amountText") },
        text = {
            Column {
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
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

/**
 * The floating pill naming this thread's live call: its authenticated display name and, once the
 * answer anchor exists, a duration ticking each second from that anchor — never a wall clock,
 * which device time changes would corrupt. Tapping it returns to the call already in progress.
 */
@Composable
private fun LiveCallBanner(
    call: ActiveCallPresence,
    onReturn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seconds = rememberLiveCallSeconds(call)
    Surface(
        shape = CircleShape,
        color = KitTheme.colors.successContainer,
        shadowElevation = 4.dp,
        modifier = modifier.clip(CircleShape).clickable(onClick = onReturn),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (call.video) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = "Return to call",
                tint = KitTheme.colors.success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                call.name,
                style = MaterialTheme.typography.labelMedium,
                color = KitTheme.colors.onSuccessContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            if (call.anchor != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    formatCallDuration(seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = KitTheme.colors.onSuccessContainer,
                )
            }
        }
    }
}

/**
 * Seconds of connected time for [call], re-read every second from the call's authoritative answer
 * anchor against the monotonic clock. Before the anchor exists this stays at zero and callers
 * simply omit the timer, rather than counting from a guess.
 */
@Composable
internal fun rememberLiveCallSeconds(call: ActiveCallPresence): Long {
    var seconds by remember(call.callId, call.anchor) {
        mutableLongStateOf(CallDurationAnchorPolicy.seconds(call.anchor, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(call.callId, call.anchor) {
        while (true) {
            seconds = CallDurationAnchorPolicy.seconds(call.anchor, SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }
    return seconds
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
 * An end-to-end encrypted photo bubble. A user tap starts its serialized ciphertext download; the
 * decrypted copy stays in app-private, no-backup storage and is decoded straight from there.
 */
@Composable
private fun SecureImageContent(
    msg: Message,
    media: SecureMediaFile?,
    mediaLoading: Boolean,
    mediaError: String?,
    onOpenMedia: () -> Unit,
    onRetryMedia: () -> Unit,
    onOpenViewer: () -> Unit = {},
) {
    var bitmap by remember(media) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var decodeFailed by remember(media) { mutableStateOf(false) }
    var decoding by remember(media) { mutableStateOf(media != null) }
    LaunchedEffect(media) {
        bitmap = null
        decodeFailed = false
        decoding = media != null
        if (media != null) {
            bitmap = withContext(Dispatchers.Default) {
                secureImageDecodeMutex.withLock { decodeBoundedSecureImage(media.file) }
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
            media == null -> Box(
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
    decodeBoundedBitmap { options ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

/**
 * Decodes an opened attachment without ever holding its file in heap.
 *
 * A photo that arrived from another platform can be far larger than anything this app would
 * encode, and at a 200 MB cap "read it all, then decode it" is two copies of a problem. Decoding
 * from the file lets the bounds pass and the sampled pass each read only what they need.
 */
internal fun decodeBoundedSecureImage(file: File): androidx.compose.ui.graphics.ImageBitmap? =
    decodeBoundedBitmap { options -> BitmapFactory.decodeFile(file.path, options) }

private inline fun decodeBoundedBitmap(
    decode: (BitmapFactory.Options) -> android.graphics.Bitmap?,
): androidx.compose.ui.graphics.ImageBitmap? =
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
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
            decode(BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap()
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
    /** Stable conversation identity the voice-note draft is preserved under. */
    voiceDraftKey: String = "",
    onAttachLibrary: () -> Unit = {},
    onAttachCamera: () -> Unit = {},
    onAttachVideoNote: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onSendVoiceNote: (ByteArray) -> Unit = {},
    onVoiceNoteTooShort: () -> Unit = {},
    mediaEnabled: Boolean = false,
    onRequestPayment: () -> Unit = {},
    /** Whether asking for money is on offer. False while a correction is being written. */
    paymentsEnabled: Boolean = true,
    /** Whether this thread can pay its members. False everywhere but a group. */
    groupPaymentsEnabled: Boolean = false,
    onPayGroup: () -> Unit = {},
    serverPaymentsSchedulingEnabled: Boolean = false,
    onSchedulePayment: () -> Unit = {},
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
    /**
     * Whether what is being written replaces an existing message rather than starting a new one.
     *
     * Only the outbound control changes: a tick, because the thing this finishes is an edit that
     * is already in the transcript, not a message being launched into it.
     */
    editing: Boolean = false,
    /** Whether holding Send may offer to hold the message back until a chosen time. */
    schedulingEnabled: Boolean = false,
    onScheduleSend: () -> Unit = {},
) {
    val context = LocalContext.current
    // The recorder outlives this composable on purpose: a draft keyed to the conversation
    // survives navigation, recomposition, and configuration changes, and leaves the
    // registry only by being sent or explicitly discarded.
    val recorder = remember(voiceDraftKey) { VoiceNoteDrafts.recorder(voiceDraftKey, context) }
    var draftPhase by remember(voiceDraftKey) {
        mutableStateOf(
            if (recorder.hasDraft) VoiceNoteDraftPhase.PAUSED else VoiceNoteDraftPhase.IDLE,
        )
    }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var sendMenuOpen by remember { mutableStateOf(false) }
    var recordingElapsedMillis by remember(voiceDraftKey) {
        mutableStateOf(recorder.elapsedMillis())
    }
    var recordingLevel by remember { mutableStateOf(0f) }
    val preview = remember(voiceDraftKey) {
        VoiceNoteDraftPreviewPlayer(
            onFinished = {
                draftPhase = VoiceNoteDraftPolicy.endPreview(draftPhase) ?: draftPhase
            },
        )
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && VoiceNoteDraftPolicy.startRecording(draftPhase) != null) {
            runCatching { recorder.start() }
                .onSuccess { draftPhase = VoiceNoteDraftPhase.RECORDING }
        }
    }
    LaunchedEffect(draftPhase, voiceDraftKey) {
        while (draftPhase == VoiceNoteDraftPhase.RECORDING) {
            recordingElapsedMillis = recorder.elapsedMillis()
            recordingLevel = recorder.level()
            if (VoiceNoteDraftPolicy.capacityReached(recordingElapsedMillis)) {
                // The cap pauses the draft rather than sending it: encryption and upload
                // happen strictly at Send, and Send stays the user's explicit act.
                recorder.pause()
                recordingElapsedMillis = recorder.elapsedMillis()
                draftPhase = if (recorder.hasDraft) {
                    VoiceNoteDraftPhase.PAUSED
                } else {
                    VoiceNoteDraftPhase.IDLE
                }
            }
            delay(80)
        }
    }
    DisposableEffect(voiceDraftKey) {
        onDispose {
            // An ordinary UI interruption pauses and preserves the draft — the microphone
            // must not keep running behind the user's back, but nothing they said is lost.
            // Only the explicit discard, or Send, ever deletes it.
            preview.stop()
            recorder.pause()
            if (!recorder.hasDraft) VoiceNoteDrafts.release(voiceDraftKey)
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
                if (draftPhase != VoiceNoteDraftPhase.IDLE) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            // The one deliberate way a draft dies.
                            preview.stop()
                            recorder.cancel()
                            VoiceNoteDrafts.release(voiceDraftKey)
                            draftPhase = VoiceNoteDraftPhase.IDLE
                        }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "Discard recording",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        when (draftPhase) {
                            VoiceNoteDraftPhase.RECORDING -> IconButton(onClick = {
                                VoiceNoteDraftPolicy.pause(draftPhase)?.let { paused ->
                                    recorder.pause()
                                    recordingElapsedMillis = recorder.elapsedMillis()
                                    draftPhase = if (recorder.hasDraft) {
                                        paused
                                    } else {
                                        VoiceNoteDraftPhase.IDLE
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Rounded.Pause,
                                    contentDescription = "Pause recording",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            VoiceNoteDraftPhase.PAUSED -> IconButton(
                                onClick = {
                                    VoiceNoteDraftPolicy.beginPreview(
                                        draftPhase,
                                        recorder.hasPlayableSegments,
                                    )?.let { previewing ->
                                        draftPhase = previewing
                                        preview.play(recorder.previewFiles())
                                    }
                                },
                                enabled = recorder.hasPlayableSegments,
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "Listen to the draft",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            VoiceNoteDraftPhase.PREVIEWING -> IconButton(onClick = {
                                preview.stop()
                                VoiceNoteDraftPolicy.endPreview(draftPhase)?.let {
                                    draftPhase = it
                                }
                            }) {
                                Icon(
                                    Icons.Rounded.Stop,
                                    contentDescription = "Stop listening",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            VoiceNoteDraftPhase.IDLE -> Unit
                        }
                        if (draftPhase == VoiceNoteDraftPhase.RECORDING) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            formatVoiceNoteTime(recordingElapsedMillis),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.width(10.dp))
                        if (draftPhase == VoiceNoteDraftPhase.RECORDING) {
                            RecorderLevelWave(
                                level = recordingLevel,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Text(
                                if (draftPhase == VoiceNoteDraftPhase.PREVIEWING) {
                                    "Playing…"
                                } else {
                                    "Paused"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    VoiceNoteDraftPolicy.resume(
                                        draftPhase,
                                        recorder.elapsedMillis(),
                                    )?.let { next ->
                                        preview.stop()
                                        runCatching { recorder.resume() }
                                            .onSuccess { draftPhase = next }
                                    }
                                },
                                enabled = VoiceNoteDraftPolicy.resume(
                                    draftPhase,
                                    recordingElapsedMillis,
                                ) != null,
                            ) {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = "Resume recording",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                        // Paying the group lives here too, and does not depend on media: a
                        // text-only build still has money to send.
                        if (mediaEnabled || groupPaymentsEnabled) {
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
                                    if (mediaEnabled) {
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
                                            leadingIcon = {
                                                Icon(Icons.Rounded.PhotoCamera, null)
                                            },
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
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Description, null)
                                            },
                                            onClick = {
                                                attachMenuOpen = false
                                                onAttachDocument()
                                            },
                                        )
                                    }
                                    if (groupPaymentsEnabled) {
                                        DropdownMenuItem(
                                            text = { Text("Pay the group") },
                                            leadingIcon = { Icon(Icons.Rounded.Payments, null) },
                                            onClick = {
                                                attachMenuOpen = false
                                                onPayGroup()
                                            },
                                        )
                                    }
                                    if (serverPaymentsSchedulingEnabled) {
                                        DropdownMenuItem(
                                            text = { Text("Schedule payment") },
                                            leadingIcon = { Icon(Icons.Rounded.Schedule, null) },
                                            onClick = {
                                                attachMenuOpen = false
                                                onSchedulePayment()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (paymentsEnabled) {
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
            }
            Spacer(Modifier.width(8.dp))
            // One dimmed circle covers all three states: the outbound action is present and in the
            // same place whether or not the session is ready, so nothing jumps when it becomes so.
            val actionAlpha = if (sendEnabled) 1f else 0.38f
            if (draftPhase != VoiceNoteDraftPhase.IDLE) {
                Box(
                    Modifier
                        .size(50.dp)
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = actionAlpha),
                            CircleShape,
                        )
                        .clickable(enabled = sendEnabled) {
                            // The only place the draft leaves the device: the segments are
                            // stitched, read back, and handed to the encrypted send path.
                            preview.stop()
                            val finished = recorder.finish()
                            VoiceNoteDrafts.release(voiceDraftKey)
                            draftPhase = VoiceNoteDraftPhase.IDLE
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
                            if (editing) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Send,
                            contentDescription = when {
                                editing -> "Save the change"
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
