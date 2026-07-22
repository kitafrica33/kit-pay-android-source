package com.kit.wallet.feature.calls

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.ui.components.initialsOf
import com.kit.wallet.ui.theme.KitGreen100
import com.kit.wallet.ui.theme.KitGreen500
import com.kit.wallet.ui.theme.KitGreen700
import com.kit.wallet.ui.theme.KitNavy600
import com.kit.wallet.ui.theme.KitNavy700
import com.kit.wallet.ui.theme.KitNavy900
import kotlin.math.roundToInt

@Composable
fun ActiveCallScreen(
    name: String,
    video: Boolean,
    onEnd: () -> Unit,
    autoAccept: Boolean = false,
    viewModel: ActiveCallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        if (!state.incoming) connectCall()
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
    BackHandler { viewModel.end("cancelled") }

    ActiveCallContent(
        state = state.copy(name = state.name.ifBlank { name }),
        room = viewModel.room,
        onMute = viewModel::toggleMute,
        onSpeaker = viewModel::toggleSpeaker,
        onCamera = viewModel::toggleCamera,
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
        onSwitchToAudio = viewModel::switchToAudio,
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

@Composable
private fun ActiveCallContent(
    state: ActiveCallUiState,
    room: io.livekit.android.room.Room,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onCamera: () -> Unit,
    onFlip: () -> Unit,
    onSwitchToVideo: () -> Unit,
    onSwitchToAudio: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onEnd: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    if (state.video) listOf(Color(0xFF35566F), Color(0xFF1C3A52), KitNavy900)
                    else listOf(KitNavy600, KitNavy700, KitNavy900),
                ),
            ),
    ) {
        if (state.video && state.remoteVideoTrack != null) {
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
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
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

            if (state.video) {
                VideoCallBody(state, room, onFlip)
            } else {
                VoiceCallBody(state)
            }

            state.error?.let { error ->
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

            if (state.phase in setOf(
                    CallPhase.INCOMING,
                    CallPhase.CONNECTING,
                    CallPhase.RINGING,
                    CallPhase.CONNECTED,
                    CallPhase.RECONNECTING,
                )
            ) {
                val connected = state.phase in setOf(CallPhase.CONNECTED, CallPhase.RECONNECTING)
                Surface(
                    color = if (state.video) Color(0xFF081524).copy(alpha = 0.62f) else Color.Transparent,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.padding(bottom = 26.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = if (state.video) 16.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (state.video) 14.dp else 20.dp),
                    ) {
                        if (state.phase == CallPhase.INCOMING) {
                            CallControl(
                                Icons.Rounded.Call,
                                "Accept",
                                success = true,
                                onClick = onAccept,
                            )
                            CallControl(
                                Icons.Rounded.CallEnd,
                                "Decline",
                                danger = true,
                                onClick = onDecline,
                            )
                        } else if (state.video) {
                            CallControl(
                                if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                "Mute",
                                active = state.muted,
                                onClick = onMute,
                            )
                            CallControl(
                                if (state.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                "Camera",
                                active = !state.cameraEnabled,
                                onClick = onCamera,
                            )
                            CallControl(Icons.Rounded.Call, "Audio", onClick = onSwitchToAudio)
                        } else {
                            CallControl(
                                Icons.AutoMirrored.Rounded.VolumeUp,
                                "Speaker",
                                active = state.speakerEnabled,
                                onClick = onSpeaker,
                            )
                            if (connected) {
                                CallControl(Icons.Rounded.Videocam, "Video", onClick = onSwitchToVideo)
                            }
                            CallControl(
                                if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                "Mute",
                                active = state.muted,
                                onClick = onMute,
                            )
                        }
                        CallControl(Icons.Rounded.CallEnd, "End", danger = true, onClick = onEnd)
                    }
                }
            }
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
    Box(
        Modifier
            .fillMaxSize()
            .weight(1f)
            .padding(20.dp)
            .onSizeChanged { containerSize = it },
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text(state.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
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
                LiveKitVideoRenderer(
                    room = room,
                    track = state.localVideoTrack,
                    mirror = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Icon(
                Icons.Rounded.Cameraswitch,
                contentDescription = "Switch camera",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clickable(onClick = onFlip)
                    .padding(8.dp)
                    .size(20.dp),
            )
        }
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
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(state.name, style = MaterialTheme.typography.headlineMedium, color = Color.White)
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
    onClick: () -> Unit,
) {
    val background = when {
        danger -> Color(0xFFE5484D)
        success -> KitGreen500
        active -> Color.White.copy(alpha = 0.95f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val foreground = if (active && !danger) KitNavy700 else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(58.dp)
                .background(background, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.82f))
    }
}
