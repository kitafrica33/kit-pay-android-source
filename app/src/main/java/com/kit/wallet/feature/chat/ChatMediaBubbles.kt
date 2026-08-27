package com.kit.wallet.feature.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.ui.components.kitNameAccent
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.acceptsReactions
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
 * An end-to-end encrypted voice note. Tap downloads and decrypts once; playback runs through
 * [VoiceNotePlayer] with the deterministic waveform both platforms share.
 *
 * The bubble is a view onto the player, never the owner of it: the note goes on playing when this
 * row scrolls away or the chat is left, and the floating bar takes over as its control. Tapping
 * inside the waveform positions playback where the finger landed, and sliding along it scrubs.
 */
@Composable
internal fun SecureVoiceNoteContent(
    msg: Message,
    media: SecureMediaFile?,
    mediaLoading: Boolean,
    mediaError: String?,
    onOpenMedia: () -> Unit,
    onRetryMedia: () -> Unit,
) {
    val context = LocalContext.current
    val chatContext = LocalVoiceNoteChatContext.current
    val playback by VoiceNotePlayer.state.collectAsStateWithLifecycle()
    val isCurrent = playback.isCurrent(msg.id)
    val playing = isCurrent && !playback.isPaused
    val progress = if (isCurrent) playback.progress else 0f
    var playbackError by remember(msg.id) { mutableStateOf<String?>(null) }

    // The player is told where its own bubble is, so it knows whether anything on screen can still
    // stop it. Only the playing note's report is listened to, so a row scrolling by cannot lie.
    DisposableEffect(msg.id) {
        VoiceNotePlayer.noteSourceVisibility(true, msg.id)
        onDispose { VoiceNotePlayer.noteSourceVisibility(false, msg.id) }
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
                        media == null -> onOpenMedia()
                        else -> {
                            playbackError = null
                            try {
                                VoiceNotePlayer.toggle(
                                    context = context,
                                    messageId = msg.id,
                                    file = media.file,
                                    // An own note never needs a roster lookup to be named.
                                    playbackContext = chatContext.playbackContext(
                                        msg.senderUserId.takeUnless { msg.fromMe },
                                    ),
                                )
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
                playedFraction = progress,
                accent = accent,
                modifier = Modifier.voiceNoteSeekGestures(msg.id),
            )
            Text(
                when {
                    mediaError != null -> mediaError
                    playbackError != null -> playbackError.orEmpty()
                    media != null -> "Voice note"
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

/**
 * Tap-to-position and slide-to-scrub on the waveform, for whichever note is playing.
 *
 * The drag detector only claims the pointer once the finger has travelled horizontally, so a
 * mostly-vertical drag stays with the thread's scrolling — the same rule [VoiceNoteSeekPolicy]
 * states and iOS enforces with its gesture mask.
 */
private fun Modifier.voiceNoteSeekGestures(messageId: String): Modifier = this
    .pointerInput(messageId) {
        detectTapGestures { offset ->
            if (!VoiceNotePlayer.state.value.isCurrent(messageId)) return@detectTapGestures
            VoiceNotePlayer.seekToFraction(
                VoiceNoteSeekPolicy.fractionAtX(offset.x, size.width.toFloat()),
            )
        }
    }
    .pointerInput(messageId) {
        var start = 0f
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                val state = VoiceNotePlayer.state.value
                start = if (state.isCurrent(messageId)) state.progress else 0f
                travelled = 0f
            },
            onHorizontalDrag = { _, delta ->
                travelled += delta
                if (!VoiceNotePlayer.state.value.isCurrent(messageId)) return@detectHorizontalDragGestures
                VoiceNotePlayer.seekToFraction(
                    VoiceNoteSeekPolicy.scrubbedFraction(start, travelled, size.width.toFloat()),
                )
            },
        )
    }

/** 26 deterministic bars over a 138x22 track, identical shape math to the iOS waveform. */
@Composable
private fun VoiceNoteWaveform(
    messageId: String,
    playedFraction: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val fractions = remember(messageId) { voiceNoteWaveformFractions(messageId) }
    Canvas(modifier.size(width = VoiceNoteSeekPolicy.WAVEFORM_WIDTH.dp, height = 22.dp)) {
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
 * player fed from a private, named link to the opened file that is dropped when the player closes.
 */
@Composable
internal fun SecureVideoContent(
    msg: Message,
    media: SecureMediaFile?,
    mediaLoading: Boolean,
    mediaError: String?,
    onOpenMedia: () -> Unit,
    onRetryMedia: () -> Unit,
    onOpenViewer: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var playerFile by remember(msg.id) { mutableStateOf<File?>(null) }
    val poster by produceState<ImageBitmap?>(initialValue = null, media) {
        value = media?.let { opened ->
            withContext(Dispatchers.Default) { videoPosterFrame(opened.file)?.asImageBitmap() }
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
                        media == null -> onOpenMedia()
                        onOpenViewer != null -> onOpenViewer()
                        else -> playerFile = runCatching {
                            writeChatMediaTempFile(
                                context = context,
                                source = media.file,
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
                media != null -> "Play"
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
    media: SecureMediaFile?,
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
                media == null -> onOpenMedia()
                else -> {
                    openError = null
                    val result = runCatching {
                        val mediaType = msg.mediaType ?: "application/octet-stream"
                        val file = writeChatMediaTempFile(
                            context = context,
                            source = media.file,
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
                    .ifEmpty { if (media != null) "Tap to open" else "Tap to download" },
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

/**
 * A grouped-photo grid bubble: 2 side-by-side, 3 as one featured tile plus a stacked pair,
 * 4 as a 2x2 grid, and 5+ as 2x2 with a "+N" veil on the last visible tile. Every tile maps
 * to its own message: tap loads it or opens the connected gallery at exactly that photo.
 *
 * Reactions follow the same one-tile-one-message rule. A grid has no single bubble to hang chips
 * off, so each tile carries its own: long-press that tile to react, and its chips sit on it. A
 * reaction on a photo the "+N" veil hides is not visible until the group is opened.
 */
@Composable
internal fun ImageGroupBubble(
    messages: List<Message>,
    fromMe: Boolean,
    mediaFiles: Map<String, SecureMediaFile>,
    mediaLoading: Set<String>,
    mediaErrors: Map<String, String>,
    onOpenMedia: (Message) -> Unit,
    onOpenViewer: (Message) -> Unit,
    reactable: Boolean = false,
    onToggleReaction: (Message, String) -> Unit = { _, _ -> },
    reportableMessageIds: Set<String> = emptySet(),
    onReportMessage: (Message) -> Unit = {},
    /** Whether this grid opens a run by its author. Decided by the thread, as for a bubble. */
    showSenderName: Boolean = true,
) {
    val visible = messages.take(4)
    val hiddenCount = messages.size - visible.size
    val tileSpacing = 3.dp
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(if (fromMe) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(vertical = 3.dp)
                .width(262.dp)
                .clip(RoundedCornerShape(16.dp)),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(tileSpacing),
        ) {
            // A grid has no bubble to write a name inside, so the group author sits above the
            // tiles. Grouping only joins photos from one author, so one label covers the grid.
            if (!fromMe && showSenderName) {
                messages.firstOrNull()?.senderName?.takeIf(String::isNotBlank)?.let { author ->
                    Text(
                        author,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = kitNameAccent(author),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
            when (visible.size) {
                2 -> Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(tileSpacing)) {
                    visible.forEach { message ->
                        GroupImageTile(
                            message, mediaFiles[message.id], message.id in mediaLoading,
                            mediaErrors.containsKey(message.id), 0,
                            Modifier.weight(1f).aspectRatio(1f),
                            onOpenMedia, onOpenViewer, reactable, onToggleReaction,
                            message.id in reportableMessageIds, onReportMessage,
                        )
                    }
                }
                3 -> {
                    GroupImageTile(
                        visible[0], mediaFiles[visible[0].id], visible[0].id in mediaLoading,
                        mediaErrors.containsKey(visible[0].id), 0,
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        onOpenMedia, onOpenViewer, reactable, onToggleReaction,
                        visible[0].id in reportableMessageIds, onReportMessage,
                    )
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(tileSpacing)) {
                        visible.drop(1).forEach { message ->
                            GroupImageTile(
                                message, mediaFiles[message.id], message.id in mediaLoading,
                                mediaErrors.containsKey(message.id), 0,
                                Modifier.weight(1f).aspectRatio(1f),
                                onOpenMedia, onOpenViewer, reactable, onToggleReaction,
                                message.id in reportableMessageIds, onReportMessage,
                            )
                        }
                    }
                }
                else -> visible.chunked(2).forEachIndexed { rowIndex, rowMessages ->
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(tileSpacing)) {
                        rowMessages.forEachIndexed { columnIndex, message ->
                            val isLastVisible =
                                rowIndex == 1 && columnIndex == rowMessages.lastIndex
                            GroupImageTile(
                                message, mediaFiles[message.id], message.id in mediaLoading,
                                mediaErrors.containsKey(message.id),
                                if (isLastVisible) hiddenCount else 0,
                                Modifier.weight(1f).aspectRatio(1f),
                                onOpenMedia, onOpenViewer, reactable, onToggleReaction,
                                message.id in reportableMessageIds, onReportMessage,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupImageTile(
    message: Message,
    media: SecureMediaFile?,
    loading: Boolean,
    failed: Boolean,
    overflowCount: Int,
    modifier: Modifier,
    onOpenMedia: (Message) -> Unit,
    onOpenViewer: (Message) -> Unit,
    reactable: Boolean = false,
    onToggleReaction: (Message, String) -> Unit = { _, _ -> },
    reportable: Boolean = false,
    onReportMessage: (Message) -> Unit = {},
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, message.id, media) {
        value = media?.let { opened ->
            withContext(Dispatchers.Default) { decodeBoundedSecureImage(opened.file) }
        }
    }
    val canReact = reactable && message.acceptsReactions
    var paletteOpen by remember(message.id) { mutableStateOf(false) }
    var pickerOpen by remember(message.id) { mutableStateOf(false) }
    var reactorsOpen by remember(message.id) { mutableStateOf(false) }
    val myReactions = message.reactions.filter { it.fromMe }.mapTo(mutableSetOf()) { it.emoji }

    if (pickerOpen) {
        ReactionPickerDialog(
            selected = myReactions,
            onPick = { emoji ->
                pickerOpen = false
                onToggleReaction(message, emoji)
            },
            onDismiss = { pickerOpen = false },
        )
    }
    if (reactorsOpen) {
        ReactionReactorsDialog(
            reactions = message.reactions,
            onDismiss = { reactorsOpen = false },
        )
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .combinedClickable(
                enabled = !loading,
                onClick = { if (media != null) onOpenViewer(message) else onOpenMedia(message) },
                onLongClick = if (canReact || reportable) {
                    { paletteOpen = true }
                } else {
                    null
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = "Photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            failed -> Text(
                "Retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            media == null -> Icon(
                Icons.Rounded.Download,
                contentDescription = "Load photo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (overflowCount > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
            }
        }
        // Chips sit on the photo they belong to. A grid is one bubble made of several messages,
        // so putting them underneath would leave the reader guessing which tile they annotate.
        MessageReactionChips(
            reactions = message.reactions,
            onToggle = { emoji -> onToggleReaction(message, emoji) },
            onShowReactors = { reactorsOpen = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp),
        )
        DropdownMenu(expanded = paletteOpen, onDismissRequest = { paletteOpen = false }) {
            if (canReact) {
                QuickReactionPalette(
                    selected = myReactions,
                    onPick = { emoji ->
                        paletteOpen = false
                        onToggleReaction(message, emoji)
                    },
                    onMore = {
                        paletteOpen = false
                        pickerOpen = true
                    },
                )
            }
            if (reportable) {
                DropdownMenuItem(
                    text = { Text("Report message") },
                    onClick = {
                        paletteOpen = false
                        onReportMessage(message)
                    },
                )
            }
        }
    }
}
