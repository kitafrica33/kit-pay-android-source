package com.kit.wallet.feature.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.kit.wallet.ui.model.Message
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Human byte label matching the iOS bubble subtitles. */
internal fun chatMediaByteLabel(bytes: Int): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}

/**
 * An end-to-end encrypted voice note. Tap downloads and decrypts once; playback runs entirely
 * from memory through [VoiceNotePlayer] with the deterministic waveform both platforms share.
 */
@Composable
internal fun SecureVoiceNoteContent(
    msg: Message,
    mediaBytes: ByteArray?,
    mediaLoading: Boolean,
    mediaError: String?,
    onOpenMedia: () -> Unit,
    onRetryMedia: () -> Unit,
) {
    var playing by remember(msg.id) { mutableStateOf(VoiceNotePlayer.isPlaying(msg.id)) }
    var progress by remember(msg.id) { mutableFloatStateOf(0f) }
    var playbackError by remember(msg.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(playing) {
        while (playing) {
            progress = VoiceNotePlayer.progress(msg.id)
            if (!VoiceNotePlayer.isPlaying(msg.id)) playing = false
            delay(120)
        }
    }
    DisposableEffect(msg.id) {
        onDispose { VoiceNotePlayer.pause(msg.id) }
    }
    val accent = LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .background(accent.copy(alpha = 0.16f), CircleShape)
                .clickable(enabled = !mediaLoading) {
                    when {
                        mediaError != null -> onRetryMedia()
                        mediaBytes == null -> onOpenMedia()
                        playing -> {
                            VoiceNotePlayer.pause(msg.id)
                            playing = false
                        }
                        else -> {
                            playbackError = null
                            try {
                                VoiceNotePlayer.play(msg.id, mediaBytes) { playing = false }
                                playing = true
                            } catch (error: Exception) {
                                playbackError =
                                    error.message ?: "This voice note could not be played"
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (mediaLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause voice note" else "Play voice note",
                    tint = accent,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            VoiceNoteWaveform(
                messageId = msg.id,
                playedFraction = if (playing || progress > 0f) progress else 0f,
                accent = accent,
            )
            Text(
                when {
                    mediaError != null -> mediaError
                    playbackError != null -> playbackError.orEmpty()
                    mediaBytes != null -> "Voice note"
                    else -> chatMediaByteLabel(msg.mediaPlaintextBytes).ifEmpty { "Voice note" }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (mediaError != null || playbackError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    accent.copy(alpha = 0.7f)
                },
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 26 deterministic bars over a 138x22 track, identical shape math to the iOS waveform. */
@Composable
private fun VoiceNoteWaveform(messageId: String, playedFraction: Float, accent: Color) {
    val fractions = remember(messageId) { voiceNoteWaveformFractions(messageId) }
    Canvas(Modifier.size(width = 138.dp, height = 22.dp)) {
        val barWidth = 2.6.dp.toPx()
        val spacing = 2.4.dp.toPx()
        fractions.forEachIndexed { index, fraction ->
            val played = (index + 1f) / fractions.size <= playedFraction
            val barHeight = size.height * fraction
            drawRoundRect(
                color = if (played) accent else accent.copy(alpha = 0.38f),
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

/**
 * An end-to-end encrypted video bubble: poster frame with a play badge, then a full-screen
 * player fed from a private temp file that is deleted the moment the player closes.
 */
@Composable
internal fun SecureVideoContent(
    msg: Message,
    mediaBytes: ByteArray?,
    mediaLoading: Boolean,
    mediaError: String?,
    onOpenMedia: () -> Unit,
    onRetryMedia: () -> Unit,
) {
    val context = LocalContext.current
    var playerFile by remember(msg.id) { mutableStateOf<File?>(null) }
    val poster by produceState<ImageBitmap?>(initialValue = null, mediaBytes) {
        value = mediaBytes?.let { bytes ->
            withOwnedSecureMediaSnapshot(bytes) { owned ->
                withContext(Dispatchers.Default) {
                    videoPosterFrame(context, owned, msg.mediaType ?: "video/mp4")
                        ?.asImageBitmap()
                }
            }
        }
    }
    Column {
        Box(
            Modifier
                .size(width = 248.dp, height = 186.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .clickable(enabled = !mediaLoading) {
                    when {
                        mediaError != null -> onRetryMedia()
                        mediaBytes == null -> onOpenMedia()
                        else -> playerFile = runCatching {
                            writeChatMediaTempFile(
                                context = context,
                                plaintext = mediaBytes,
                                mediaType = msg.mediaType ?: "video/mp4",
                                displayName = null,
                            )
                        }.getOrNull()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            poster?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Encrypted video",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            when {
                mediaLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.dp,
                )
                else -> Box(
                    Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        Text(
            when {
                mediaError != null -> mediaError
                mediaBytes != null -> "Play"
                else -> chatMediaByteLabel(msg.mediaPlaintextBytes).ifEmpty { "Video" }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (mediaError != null) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current.copy(alpha = 0.7f)
            },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    playerFile?.let { file ->
        SecureVideoPlayerDialog(file = file, onDismiss = {
            deleteChatMediaTempFile(file)
            playerFile = null
        })
    }
}

@Composable
private fun SecureVideoPlayerDialog(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { player -> player.start() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view -> view.stopPlayback() },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close video", tint = Color.White)
            }
        }
    }
}

/**
 * An end-to-end encrypted document: filename tile, then hand-off to the system viewer via the
 * app's FileProvider grant. The caption carries the filename, exactly like iOS.
 */
@Composable
internal fun SecureDocumentContent(
    msg: Message,
    mediaBytes: ByteArray?,
    mediaLoading: Boolean,
    mediaError: String?,
    onOpenMedia: () -> Unit,
    onRetryMedia: () -> Unit,
) {
    val context = LocalContext.current
    var openError by remember(msg.id) { mutableStateOf<String?>(null) }
    val accent = LocalContentColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = !mediaLoading) {
            when {
                mediaError != null -> onRetryMedia()
                mediaBytes == null -> onOpenMedia()
                else -> {
                    openError = null
                    val result = runCatching {
                        val mediaType = msg.mediaType ?: "application/octet-stream"
                        val file = writeChatMediaTempFile(
                            context = context,
                            plaintext = mediaBytes,
                            mediaType = mediaType,
                            displayName = msg.text.takeIf { it.isNotBlank() && it != "Document" },
                        )
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.chatmedia",
                            file,
                        )
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, mediaType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }
                    result.exceptionOrNull()?.let { error ->
                        openError = if (error is ActivityNotFoundException) {
                            "No app on this phone can open this document"
                        } else {
                            "This document could not be opened"
                        }
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (mediaLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = accent)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                msg.text.ifBlank { "Document" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                mediaError ?: openError ?: chatMediaByteLabel(msg.mediaPlaintextBytes)
                    .ifEmpty { if (mediaBytes != null) "Tap to open" else "Tap to download" },
                style = MaterialTheme.typography.labelSmall,
                color = if (mediaError != null || openError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    accent.copy(alpha = 0.7f)
                },
            )
        }
    }
}
