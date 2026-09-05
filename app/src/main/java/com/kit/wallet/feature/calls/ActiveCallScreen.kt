package com.kit.wallet.feature.calls

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.StopScreenShare
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.KitAvatarPhoto
import com.kit.wallet.ui.components.VerifiedAccountName
import com.kit.wallet.ui.components.initialsOf
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.theme.KitGreen100
import com.kit.wallet.ui.theme.KitGreen500
import com.kit.wallet.ui.theme.KitGreen700
import com.kit.wallet.ui.theme.KitNavy600
import com.kit.wallet.ui.theme.KitNavy700
import com.kit.wallet.ui.theme.KitNavy900
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.util.flow
import kotlin.math.roundToInt

@Composable
fun ActiveCallScreen(
    name: String,
    video: Boolean,
    onEnd: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    autoAccept: Boolean = false,
    viewModel: ActiveCallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canOpenChat by viewModel.canOpenChat.collectAsStateWithLifecycle()
    val openingChat by viewModel.openingChat.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Reading the configuration causes Compose to re-evaluate this after the activity enters or
    // leaves PiP, including when MainActivity handles the change without being recreated.
    @Suppress("UNUSED_VARIABLE")
    val configuration = LocalConfiguration.current
    val activity = context as? ComponentActivity
    val inPictureInPicture = activity?.isInPictureInPictureMode == true
    val requestedVideo = if (state.incoming) state.video else video
    val requiredPermissions = remember(requestedVideo) {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (requestedVideo) add(Manifest.permission.CAMERA)
        }.toTypedArray()
    }
    val permissions = remember(requiredPermissions) {
        buildList {
            addAll(requiredPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val requiredGranted = requiredPermissions.all { permission ->
            grants[permission] == true || ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (requiredGranted) {
            if (state.incoming) viewModel.accept(requestedVideo)
            else viewModel.start(requestedVideo)
        }
        else viewModel.permissionDenied()
    }
    // Mid-call upgrade to video only needs the camera; audio permission already exists.
    val cameraSwitchRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.switchToVideo()
    }
    // Screen sharing needs the user's MediaProjection consent; the granted result Intent is what
    // LiveKit needs to publish the screen track.
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenCaptureRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startScreenShare(data)
        }
    }
    val contactsToAdd by viewModel.callableContacts.collectAsStateWithLifecycle()
    var showAddPeople by remember { mutableStateOf(false) }
    var showAudioOutput by remember { mutableStateOf(false) }
    if (showAudioOutput) {
        AudioOutputDialog(
            devices = state.audioDevices,
            selected = state.selectedAudioDevice,
            onDismiss = { showAudioOutput = false },
            onSelect = {
                viewModel.selectAudioDevice(it)
                showAudioOutput = false
            },
        )
    }
    if (showAddPeople) {
        AddPeopleDialog(
            contacts = contactsToAdd,
            onDismiss = { showAddPeople = false },
            onPick = { contact ->
                viewModel.addParticipant(contact.id)
                showAddPeople = false
            },
        )
    }

    val connectCall = {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (state.incoming) viewModel.accept(requestedVideo)
            else viewModel.start(requestedVideo)
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        if (!state.incoming) {
            when (viewModel.consumeOutgoingCallLaunch()) {
                OutgoingCallLaunchAction.START -> connectCall()
                OutgoingCallLaunchAction.KEEP_CURRENT_ROUTE -> Unit
                OutgoingCallLaunchAction.EXIT_STALE_ROUTE -> onEnd()
            }
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.ENDED) onEnd()
    }
    // Answering from the notification skips the in-app Accept tap once the call is verified.
    var autoAcceptConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(state.incomingVerified, state.phase) {
        if (
            autoAccept && !autoAcceptConsumed && state.incoming &&
            state.incomingVerified && state.phase == CallPhase.INCOMING
        ) {
            autoAcceptConsumed = true
            connectCall()
        }
    }
    // Ring and vibrate for the callee only while the verified call is still ringing. Accepting,
    // declining, connecting or any error immediately flips this off and disposes the ringer.
    val ringing = state.incoming && state.phase == CallPhase.INCOMING
    DisposableEffect(ringing) {
        val ringer = if (ringing) CallRinger(context).also { it.start() } else null
        onDispose { ringer?.stop() }
    }
    // Standard telephony progress sounds for the caller: ringback while the peer's device rings
    // and a short disconnect burst when an active or ringing call terminates.
    val tones = remember { CallTonePlayer() }
    val outgoingRinging = !state.incoming && state.phase == CallPhase.RINGING
    DisposableEffect(outgoingRinging) {
        if (outgoingRinging) tones.startRingback()
        onDispose { tones.stopRingback() }
    }
    // Repeat the standard call-waiting tone while a second call is ringing in.
    LaunchedEffect(state.waitingCall != null) {
        if (state.waitingCall != null) {
            while (true) {
                tones.playCallWaiting()
                kotlinx.coroutines.delay(3_500)
            }
        }
    }
    var previousPhase by remember { mutableStateOf(state.phase) }
    LaunchedEffect(state.phase) {
        val was = previousPhase
        previousPhase = state.phase
        if (
            state.phase in setOf(CallPhase.ENDING, CallPhase.ENDED) &&
            was in setOf(
                CallPhase.CONNECTING,
                CallPhase.RINGING,
                CallPhase.CONNECTED,
                CallPhase.RECONNECTING,
            )
        ) {
            tones.playDisconnect()
        }
    }
    DisposableEffect(Unit) {
        onDispose { tones.release() }
    }
    val pictureInPictureEligible = shouldEnterCallPictureInPicture(state.video, state.phase)
    DisposableEffect(activity, pictureInPictureEligible) {
        if (activity == null) return@DisposableEffect onDispose { }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(pictureInPictureEligible)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
        activity.setPictureInPictureParams(params)
        val leaveHint = Runnable {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                pictureInPictureEligible &&
                !activity.isInPictureInPictureMode
            ) {
                activity.enterPictureInPictureMode(params)
            }
        }
        activity.addOnUserLeaveHintListener(leaveHint)
        onDispose {
            activity.removeOnUserLeaveHintListener(leaveHint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setPictureInPictureParams(
                    PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
                )
            }
        }
    }
    BackHandler { viewModel.end("cancelled") }

    ActiveCallContent(
        state = state.copy(name = state.name.ifBlank { name }),
        room = viewModel.room,
        compact = inPictureInPicture,
        onMute = viewModel::toggleMute,
        onSpeaker = { showAudioOutput = true },
        onCamera = {
            if (state.cameraEnabled ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.toggleCamera()
            } else {
                cameraSwitchRequest.launch(Manifest.permission.CAMERA)
            }
        },
        onFlip = viewModel::flipCamera,
        onSwitchToVideo = {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.switchToVideo()
            } else {
                cameraSwitchRequest.launch(Manifest.permission.CAMERA)
            }
        },
        onDeclineWaiting = viewModel::declineWaitingCall,
        onMergeWaiting = viewModel::mergeWaitingCall,
        onAddParticipant = { showAddPeople = true },
        onOpenChat = { viewModel.openChat(onOpenChat) },
        canOpenChat = canOpenChat,
        openingChat = openingChat,
        onToggleScreenShare = {
            if (state.screenSharing) {
                viewModel.stopScreenShare()
            } else {
                runCatching { mediaProjectionManager.createScreenCaptureIntent() }
                    .onSuccess(screenCaptureRequest::launch)
            }
        },
        onAccept = connectCall,
        onDecline = viewModel::decline,
        onRetry = {
            if (state.incoming && !state.incomingVerified) {
                viewModel.retry()
            } else if (requiredPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            ) {
                viewModel.retry()
            } else {
                permissionRequest.launch(requiredPermissions)
            }
        },
        onEnd = { viewModel.end() },
    )
}

internal fun shouldEnterCallPictureInPicture(video: Boolean, phase: CallPhase): Boolean =
    video && phase in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)

@Composable
internal fun ActiveCallContent(
    state: ActiveCallUiState,
    room: io.livekit.android.room.Room,
    compact: Boolean,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onCamera: () -> Unit,
    onFlip: () -> Unit,
    onSwitchToVideo: () -> Unit,
    onDeclineWaiting: () -> Unit,
    onMergeWaiting: () -> Unit,
    onAddParticipant: () -> Unit,
    onOpenChat: () -> Unit,
    canOpenChat: Boolean,
    openingChat: Boolean,
    onToggleScreenShare: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onEnd: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    if (state.video) listOf(Color(0xFF35566F), Color(0xFF1C3A52), KitNavy900)
                    else listOf(KitNavy600, KitNavy700, KitNavy900),
                ),
            ),
    ) {
        val shortLayout = maxHeight < 560.dp
        // A group video call renders every remote participant as a tile; a one-to-one video call
        // fills the screen with the single remote video behind the local self-view.
        if (state.remoteScreenShare != null) {
            // The fitted share is rendered inside the safe content area below, without controls
            // or the self-view covering the document. PiP still shows the complete shared frame.
            if (compact) LiveKitVideoRenderer(
                room = room,
                track = state.remoteScreenShare?.videoTrack,
                fit = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.video && state.isGroup) {
            GroupVideoGrid(state = state, room = room, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        } else if (state.video && state.remoteVideoTrack != null) {
            LiveKitVideoRenderer(
                room = room,
                track = state.remoteVideoTrack,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!compact && !shortLayout) Spacer(Modifier.height(14.dp))
            if (!compact && !shortLayout) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "Kit Pay secure media",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            if (compact && state.video) {
                Spacer(Modifier.weight(1f))
            } else if (state.remoteScreenShare != null) {
                SharedScreenCallBody(state.remoteScreenShare!!, room, shortLayout)
            } else if (state.video) {
                VideoCallBody(state, room, onFlip)
            } else {
                VoiceCallBody(state)
            }

            if (!compact) state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        if (state.phase == CallPhase.ERROR) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CallControl(Icons.Rounded.Refresh, "Retry", onClick = onRetry)
                                CallControl(Icons.Rounded.CallEnd, "Close", danger = true, onClick = onEnd)
                            }
                        }
                    }
                }
            }

            if (!compact && state.phase in setOf(
                    CallPhase.INCOMING,
                    CallPhase.CONNECTING,
                    CallPhase.RINGING,
                    CallPhase.CONNECTED,
                    CallPhase.RECONNECTING,
                )
            ) {
                val connected = state.phase in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)
                if (state.phase == CallPhase.INCOMING) {
                    // A ringing call gets a dedicated wide answer layout: two large,
                    // well-separated targets that cannot be confused or mis-tapped.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 44.dp, end = 44.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CallControl(
                            Icons.Rounded.CallEnd,
                            "Decline",
                            danger = true,
                            size = 68.dp,
                            onClick = onDecline,
                        )
                        CallControl(
                            Icons.Rounded.Call,
                            "Accept",
                            success = true,
                            size = 68.dp,
                            onClick = onAccept,
                        )
                    }
                } else {
                    // Add people to the call and share the screen — available once connected.
                    if (connected) {
                        Row(
                            Modifier.padding(bottom = if (shortLayout) 8.dp else 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            CallControl(
                                Icons.AutoMirrored.Rounded.VolumeUp,
                                callAudioDeviceLabel(state.selectedAudioDevice),
                                active = state.speakerEnabled,
                                size = 48.dp,
                                onClick = onSpeaker,
                            )
                            CallControl(
                                Icons.Rounded.PersonAdd,
                                "Add",
                                size = 44.dp,
                                onClick = onAddParticipant,
                            )
                            if (canOpenChat) {
                                CallControl(
                                    Icons.AutoMirrored.Rounded.Chat,
                                    if (openingChat) "Opening…" else "Chat",
                                    size = 44.dp,
                                    onClick = { if (!openingChat) onOpenChat() },
                                )
                            }
                            CallControl(
                                if (state.screenSharing) {
                                    Icons.AutoMirrored.Rounded.StopScreenShare
                                } else {
                                    Icons.AutoMirrored.Rounded.ScreenShare
                                },
                                if (state.screenSharing) "Stop share" else "Share",
                                active = state.screenSharing,
                                size = 44.dp,
                                enabled = !state.mediaChanging,
                                onClick = onToggleScreenShare,
                            )
                        }
                    }
                    // One consistent glass capsule for voice and video, mirroring the iOS panel.
                    Surface(
                        color = Color(0xFF081524).copy(alpha = if (state.video) 0.55f else 0.35f),
                        shape = RoundedCornerShape(38.dp),
                        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier.padding(bottom = if (shortLayout) 12.dp else 26.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = if (shortLayout) 8.dp else 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state.video) {
                                CallControl(
                                    if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                    if (state.muted) "Unmute" else "Mute",
                                    active = state.muted,
                                    enabled = connected && !state.mediaChanging,
                                    onClick = onMute,
                                )
                                CallControl(
                                    if (state.cameraEnabled) {
                                        Icons.Rounded.Videocam
                                    } else {
                                        Icons.Rounded.VideocamOff
                                    },
                                    if (state.cameraEnabled) "Camera off" else "Camera on",
                                    active = !state.cameraEnabled,
                                    enabled = connected && !state.mediaChanging,
                                    onClick = onCamera,
                                )
                            } else {
                                if (connected) {
                                    CallControl(
                                        Icons.Rounded.Videocam,
                                        "Video",
                                        enabled = !state.mediaChanging,
                                        onClick = onSwitchToVideo,
                                    )
                                }
                                CallControl(
                                    if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                    if (state.muted) "Unmute" else "Mute",
                                    active = state.muted,
                                    enabled = connected && !state.mediaChanging,
                                    onClick = onMute,
                                )
                            }
                            CallControl(
                                Icons.Rounded.CallEnd,
                                "End",
                                danger = true,
                                onClick = onEnd,
                            )
                        }
                    }
                }
            }
        }

        if (!compact) state.waitingCall?.let { waiting ->
            CallWaitingBanner(
                waiting = waiting,
                merging = state.mergingWaitingCall,
                onDecline = onDeclineWaiting,
                onMerge = onMergeWaiting,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** The call-waiting overlay: shows the second caller with Decline and Merge-to-group actions. */
@Composable
private fun CallWaitingBanner(
    waiting: WaitingCall,
    merging: Boolean,
    onDecline: () -> Unit,
    onMerge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF0B1D2E).copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Call waiting",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Text(
                    waiting.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    if (waiting.video) "Incoming video call" else "Incoming voice call",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            CallControl(Icons.Rounded.CallEnd, "Decline", danger = true, onClick = onDecline)
            Spacer(Modifier.width(14.dp))
            CallControl(
                Icons.Rounded.PersonAdd,
                if (merging) "Merging…" else "Merge",
                success = true,
                onClick = { if (!merging) onMerge() },
            )
        }
    }
}

@Composable
private fun ColumnScope.VideoCallBody(
    state: ActiveCallUiState,
    room: io.livekit.android.room.Room,
    onFlip: () -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // The self-view floats over the call and can be dragged anywhere inside the safe area.
    // The offset is relative to its top-end anchor and clamped so it can never leave the screen.
    var previewOffset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(containerSize, previewSize) {
        previewOffset = Offset(
            previewOffset.x.coerceIn(-(containerSize.width - previewSize.width).coerceAtLeast(0).toFloat(), 0f),
            previewOffset.y.coerceIn(0f, (containerSize.height - previewSize.height).coerceAtLeast(0).toFloat()),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .weight(1f)
            .padding(20.dp)
            .onSizeChanged { containerSize = it },
    ) {
        Column(Modifier.align(Alignment.TopStart).padding(end = 120.dp)) {
            Text(
                state.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.statusText(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(previewOffset.x.roundToInt(), previewOffset.y.roundToInt()) }
                .size(width = 108.dp, height = 150.dp)
                .onSizeChanged { previewSize = it }
                .pointerInput(containerSize) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val maxLeft = (containerSize.width - previewSize.width).coerceAtLeast(0)
                        val maxDown = (containerSize.height - previewSize.height).coerceAtLeast(0)
                        previewOffset = Offset(
                            (previewOffset.x + dragAmount.x).coerceIn(-maxLeft.toFloat(), 0f),
                            (previewOffset.y + dragAmount.y).coerceIn(0f, maxDown.toFloat()),
                        )
                    }
                }
                .clip(MaterialTheme.shapes.medium)
                .background(Color(0xFF2B4A66)),
        ) {
            if (state.localVideoTrack != null && state.cameraEnabled) {
                val localTrack = state.localVideoTrack as? LocalVideoTrack
                val mirror = if (localTrack != null) {
                    val options by localTrack::options.flow.collectAsStateWithLifecycle()
                    options.position == CameraPosition.FRONT
                } else false
                LiveKitVideoRenderer(
                    room = room,
                    track = state.localVideoTrack,
                    mirror = mirror,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.cameraEnabled) Icon(
                Icons.Rounded.Cameraswitch,
                contentDescription = "Switch camera",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clickable(onClick = onFlip)
                    .size(48.dp)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.SharedScreenCallBody(
    participant: RemoteCallParticipant,
    room: io.livekit.android.room.Room,
    shortLayout: Boolean,
) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(if (shortLayout) 8.dp else 16.dp)) {
        Text(
            participant.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Sharing screen",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(if (shortLayout) 6.dp else 12.dp))
        LiveKitVideoRenderer(
            room = room,
            track = participant.videoTrack,
            fit = true,
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                .semantics { contentDescription = "${participant.name}'s shared screen" },
        )
    }
}

@Composable
private fun ColumnScope.VoiceCallBody(state: ActiveCallUiState) {
    // A calm breathing pulse: the halo rings expand and brighten together like a heartbeat
    // while the call is active, and rest still once the call leaves its live phases.
    val live = state.phase in setOf(
        CallPhase.INCOMING,
        CallPhase.CONNECTING,
        CallPhase.RINGING,
        CallPhase.CONNECTED,
        CallPhase.RECONNECTING,
    )
    val breathing = rememberInfiniteTransition(label = "voice-call-breathing")
    val outerScale by breathing.animateFloat(
        initialValue = 1f,
        targetValue = if (live) 1.09f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "outer-ring-scale",
    )
    val innerScale by breathing.animateFloat(
        initialValue = 1f,
        targetValue = if (live) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150, delayMillis = 120, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inner-ring-scale",
    )
    val haloAlpha by breathing.animateFloat(
        initialValue = 0.05f,
        targetValue = if (live) 0.12f else 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo-alpha",
    )
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(204.dp)
                .graphicsLayer {
                    scaleX = outerScale
                    scaleY = outerScale
                }
                .background(KitGreen500.copy(alpha = haloAlpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(168.dp)
                    .graphicsLayer {
                        scaleX = innerScale
                        scaleY = innerScale
                    }
                    .background(KitGreen500.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(132.dp).background(KitGreen100, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initialsOf(state.name),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KitGreen700,
                    )
                    KitAvatarPhoto(avatarUrl = state.avatarUrl, size = 132.dp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        VerifiedAccountName(
            name = state.name,
            verification = state.accountVerification,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            badgeSize = 24.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            state.statusText(),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}

private fun ActiveCallUiState.statusText(): String = when (phase) {
    CallPhase.IDLE -> "Preparing call…"
    CallPhase.VALIDATING -> "Checking incoming call…"
    CallPhase.INCOMING -> if (video) "Incoming Kit Pay video call" else "Incoming Kit Pay voice call"
    CallPhase.CONNECTING -> "Connecting securely…"
    CallPhase.RINGING -> "Ringing…"
    CallPhase.CONNECTED -> "%02d:%02d • Kit Pay %s".format(
        durationSeconds / 60,
        durationSeconds % 60,
        if (video) "video" else "voice",
    )
    CallPhase.RECONNECTING -> "Reconnecting…"
    CallPhase.ENDING -> "Ending call…"
    CallPhase.ENDED -> "Call ended"
    CallPhase.ERROR -> "Could not connect"
}

@Composable
private fun CallControl(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    success: Boolean = false,
    size: Dp = 52.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // The end-call red matches the iOS call panel exactly; the rest stays on the Kit palette.
    val background = when {
        danger -> Color(0xFFFA0640)
        success -> KitGreen500
        active -> Color.White.copy(alpha = 0.95f)
        else -> Color.White.copy(alpha = 0.16f)
    }
    val foreground = if (active && !danger) KitNavy700 else Color.White
    Column(
        modifier = Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(size.coerceAtLeast(48.dp))
                .background(background, CircleShape)
                .then(
                    if (danger || success || active) {
                        Modifier
                    } else {
                        Modifier.border(0.8.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                    },
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(size * 22 / 52),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.82f),
        )
    }
}

/** A tile grid of the other participants in a group video call: their camera/screen or an avatar. */
@Composable
private fun GroupVideoGrid(
    state: ActiveCallUiState,
    room: io.livekit.android.room.Room,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 56.dp)) {
        state.remoteParticipants.chunked(2).forEach { rowParticipants ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                rowParticipants.forEach { participant ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(3.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color(0xFF2B4A66)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (participant.videoTrack != null) {
                            LiveKitVideoRenderer(
                                room = room,
                                track = participant.videoTrack,
                                fit = participant.screenSharing,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // A camera that is off should show the person, not a monogram standing
                            // in for one. The photo is the same cached copy the chat list drew, so
                            // this costs no download at the moment a call is being carried.
                            KitAvatar(
                                name = participant.name,
                                size = 96.dp,
                                avatarUrl = participant.avatarUrl,
                            )
                        }
                        VerifiedAccountName(
                            name = participant.name,
                            verification = participant.accountVerification,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            badgeSize = 12.dp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp),
                        )
                    }
                }
                // Keep a single trailing tile left-aligned in its row.
                if (rowParticipants.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AudioOutputDialog(
    devices: List<AudioDevice>,
    selected: AudioDevice?,
    onDismiss: () -> Unit,
    onSelect: (AudioDevice?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio output") },
        text = {
            Column {
                TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Automatic · prefer headphones")
                }
                devices.forEach { device ->
                    TextButton(onClick = { onSelect(device) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            callAudioDeviceLabel(device) + if (device == selected) " · In use" else "",
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** In-call people picker: tap a Kit Pay contact to invite them into the call. */
@Composable
private fun AddPeopleDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onPick: (Contact) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to call") },
        text = {
            if (contacts.isEmpty()) {
                Text(
                    "No Kit Pay contacts to add yet. Sync your contacts to find people on Kit Pay.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(contacts.size) { index ->
                        val contact = contacts[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(contact) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KitAvatar(
                                contact.name,
                                size = 40.dp,
                                avatarUrl = contact.avatarUrl,
                            )
                            Spacer(Modifier.width(12.dp))
                            VerifiedAccountName(
                                name = contact.name,
                                verification = contact.accountVerification,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
