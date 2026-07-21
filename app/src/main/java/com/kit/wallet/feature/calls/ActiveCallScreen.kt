package com.kit.wallet.feature.calls

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun ActiveCallScreen(
    name: String,
    video: Boolean,
    onEnd: () -> Unit,
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
    BackHandler { viewModel.end("cancelled") }

    ActiveCallContent(
        state = state.copy(name = state.name.ifBlank { name }),
        room = viewModel.room,
        onMute = viewModel::toggleMute,
        onSpeaker = viewModel::toggleSpeaker,
        onCamera = viewModel::toggleCamera,
        onFlip = viewModel::flipCamera,
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
                    "Secure WebRTC media",
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
                Surface(
                    color = if (state.video) Color(0xFF081524).copy(alpha = 0.62f) else Color.Transparent,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.padding(bottom = 26.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = if (state.video) 16.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
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
                            CallControl(Icons.Rounded.Cameraswitch, "Flip", onClick = onFlip)
                        } else {
                            CallControl(
                                Icons.AutoMirrored.Rounded.VolumeUp,
                                "Speaker",
                                active = state.speakerEnabled,
                                onClick = onSpeaker,
                            )
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
    Row(
        Modifier
            .fillMaxSize()
            .weight(1f)
            .padding(20.dp),
    ) {
        Column {
            Text(state.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                state.statusText(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 108.dp, height = 150.dp)
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
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(204.dp).background(KitGreen500.copy(alpha = 0.05f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(168.dp).background(KitGreen500.copy(alpha = 0.12f), CircleShape),
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
