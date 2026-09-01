package com.kit.wallet.feature.chat

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kit.wallet.data.messaging.SecureMediaFile
import com.kit.wallet.data.messaging.SecureMediaLease
import com.kit.wallet.data.messaging.chatMediaFileExtension
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.MessageReaction
import com.kit.wallet.ui.model.acceptsReactions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Media kinds the connected gallery can page through. */
internal val GALLERY_MEDIA_KINDS = setOf(MessageKind.IMAGE, MessageKind.VIDEO)

/** Resolves and reports exactly the visible gallery page, including pages hidden behind a +N tile. */
internal fun reportCurrentGalleryMessage(
    mediaMessages: List<Message>,
    currentPage: Int,
    reportableMessageIds: Set<String>,
    onReportMessage: (Message) -> Unit,
): Boolean {
    val current = mediaMessages.getOrNull(currentPage)
        ?.takeIf { it.id in reportableMessageIds }
        ?: return false
    onReportMessage(current)
    return true
}

/**
 * WhatsApp-style connected full-screen gallery over the conversation's media messages:
 * open on the tapped item, swipe left/right through the rest, pinch/pan/double-tap zoom
 * for photos, inline playback for videos, and share/save actions. Each page reads the opened
 * attachment from the conversation's bounded on-disk cache; adjacent pages load on demand.
 */
@Composable
internal fun ConversationMediaGallery(
    chatName: String,
    mediaMessages: List<Message>,
    initialMessageId: String,
    mediaFiles: Map<String, SecureMediaFile>,
    mediaLoading: Set<String>,
    mediaErrors: Map<String, String>,
    onLoad: (Message) -> Unit,
    onRetry: (Message) -> Unit,
    onDismiss: () -> Unit,
    reactionsEnabled: Boolean = false,
    onToggleReaction: (Message, String) -> Unit = { _, _ -> },
    reportableMessageIds: Set<String> = emptySet(),
    onReportMessage: (Message) -> Unit = {},
) {
    if (mediaMessages.isEmpty()) {
        onDismiss()
        return
    }
    val context = LocalContext.current
    val initialPage = mediaMessages.indexOfFirst { it.id == initialMessageId }
        .coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { mediaMessages.size }
    var actionNotice by remember { mutableStateOf<String?>(null) }
    var paletteOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var reactorsOpen by remember { mutableStateOf(false) }
    // A palette or a reactor list left open over the next photo would read as belonging to it.
    LaunchedEffect(pagerState.currentPage) {
        paletteOpen = false
        reactorsOpen = false
    }

    // Load the visible page and its neighbours so swiping stays smooth.
    LaunchedEffect(pagerState.currentPage, mediaMessages.size) {
        listOf(pagerState.currentPage - 1, pagerState.currentPage, pagerState.currentPage + 1)
            .filter { it in mediaMessages.indices }
            .map(mediaMessages::get)
            .forEach { message ->
                if (
                    mediaFiles[message.id]?.exists != true &&
                    message.id !in mediaLoading &&
                    !mediaErrors.containsKey(message.id)
                ) {
                    onLoad(message)
                }
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                key = { index -> mediaMessages[index].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val message = mediaMessages[page]
                val media = mediaFiles[message.id]
                when {
                    media != null && message.kind == MessageKind.IMAGE ->
                        ZoomableGalleryImage(messageId = message.id, media = media)
                    media != null && message.kind == MessageKind.VIDEO ->
                        GalleryVideoPage(message = message, media = media)
                    mediaErrors.containsKey(message.id) -> GalleryStatePage(
                        text = mediaErrors[message.id] ?: "This media could not be loaded",
                        actionLabel = "Tap to retry",
                        onAction = { onRetry(message) },
                    )
                    // The quiet page can outlive its moment: a probe released as "still
                    // preparing" leaves no error and no file, and nothing re-fires the
                    // neighbour loader until the page or list changes. A tap re-proves the
                    // attachment against the queue's current truth; while a load is genuinely
                    // in flight, the claim makes it a no-op.
                    else -> GalleryStatePage(
                        text = null,
                        actionLabel = null,
                        onAction = { onLoad(message) },
                    )
                }
            }

            // Top chrome: back, title/position, share and save for the current item.
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        chatName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    val current = mediaMessages.getOrNull(pagerState.currentPage)
                    Text(
                        "${pagerState.currentPage + 1} of ${mediaMessages.size}" +
                            (current?.time?.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                val current = mediaMessages.getOrNull(pagerState.currentPage)
                val currentMedia = current?.let { mediaFiles[it.id] }
                if (current != null && current.id in reportableMessageIds) {
                    IconButton(
                        onClick = {
                            reportCurrentGalleryMessage(
                                mediaMessages = mediaMessages,
                                currentPage = pagerState.currentPage,
                                reportableMessageIds = reportableMessageIds,
                                onReportMessage = onReportMessage,
                            )
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Report,
                            contentDescription = "Report this message",
                            tint = Color.White,
                        )
                    }
                }
                IconButton(
                    enabled = currentMedia != null,
                    onClick = {
                        val message = current ?: return@IconButton
                        val media = currentMedia ?: return@IconButton
                        actionNotice = runCatching {
                            shareGalleryMedia(context, message, media)
                            null
                        }.getOrElse { "This media could not be shared" }
                    },
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = "Share",
                        tint = Color.White.copy(alpha = if (currentMedia != null) 1f else 0.4f),
                    )
                }
                IconButton(
                    enabled = currentMedia != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onClick = {
                        val message = current ?: return@IconButton
                        val media = currentMedia ?: return@IconButton
                        actionNotice = runCatching {
                            saveGalleryMedia(context, message, media.file)
                            "Saved to your gallery"
                        }.getOrElse { "This media could not be saved" }
                    },
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Save",
                        tint = Color.White.copy(
                            alpha = if (
                                currentMedia != null &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ) {
                                1f
                            } else {
                                0.4f
                            },
                        ),
                    )
                }
            }

            // The same reactions the bubble carries, on the item currently on screen: opening a
            // photo full-screen is where a reaction is most often wanted, and leaving the gallery
            // to find the bubble again is the long way round.
            val reacting = mediaMessages.getOrNull(pagerState.currentPage)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ownEmoji = reacting?.reactions.orEmpty()
                    .filter(MessageReaction::fromMe)
                    .map(MessageReaction::emoji)
                    .toSet()
                if (paletteOpen && reacting != null) {
                    Surface(
                        shape = CircleShape,
                        shadowElevation = 6.dp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        QuickReactionPalette(
                            selected = ownEmoji,
                            onPick = { emoji ->
                                onToggleReaction(reacting, emoji)
                                paletteOpen = false
                            },
                            onMore = {
                                paletteOpen = false
                                pickerOpen = true
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        MessageReactionChips(
                            reactions = reacting?.reactions.orEmpty(),
                            onToggle = { emoji ->
                                reacting?.let { onToggleReaction(it, emoji) }
                            },
                            onShowReactors = { reactorsOpen = true },
                        )
                    }
                    // A send still on its way has no stable ID to pin a reaction to, exactly as in
                    // the transcript, so the affordance is absent rather than inert.
                    if (reacting != null && reactionsEnabled && reacting.acceptsReactions) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                                .clip(CircleShape)
                                .clickable { paletteOpen = !paletteOpen }
                                .semantics {
                                    contentDescription = if (reacting.kind == MessageKind.VIDEO) {
                                        "React to this video"
                                    } else {
                                        "React to this photo"
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🙂", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            if (pickerOpen && reacting != null) {
                ReactionPickerDialog(
                    selected = reacting.reactions.filter(MessageReaction::fromMe)
                        .map(MessageReaction::emoji)
                        .toSet(),
                    onPick = { emoji ->
                        onToggleReaction(reacting, emoji)
                        pickerOpen = false
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
            val reactors = reacting?.reactions.orEmpty()
            // Removing the last reaction while the list is open closes it rather than leaving an
            // empty sheet naming nobody.
            if (reactorsOpen && reactors.isNotEmpty()) {
                ReactionReactorsDialog(reactors) { reactorsOpen = false }
            }

            actionNotice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        // Clears the reaction row rather than landing on top of it.
                        .padding(bottom = 84.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LaunchedEffect(notice) {
                    kotlinx.coroutines.delay(2_200)
                    actionNotice = null
                }
            }
        }
    }
}

/** Pinch, pan and double-tap zoom for one decrypted photo, clamped to sensible bounds. */
@Composable
private fun ZoomableGalleryImage(messageId: String, media: SecureMediaFile) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, messageId) {
        value = withContext(Dispatchers.Default) { decodeBoundedSecureImage(media.file) }
    }
    var scale by remember(messageId) { mutableFloatStateOf(1f) }
    var offset by remember(messageId) { mutableStateOf(Offset.Zero) }
    val animatedScale by animateFloatAsState(scale, label = "galleryZoom")
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(messageId) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = Offset(
                                (size.width / 2f - tap.x) * 1.5f,
                                (size.height / 2f - tap.y) * 1.5f,
                            )
                        }
                    },
                )
            }
            .transformable(transformState),
        contentAlignment = Alignment.Center,
    ) {
        val rendered = bitmap
        if (rendered != null) {
            Image(
                bitmap = rendered,
                contentDescription = "Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Full-screen playback through a stable lease on the local copy, without a second large file. */
@Composable
private fun GalleryVideoPage(message: Message, media: SecureMediaFile) {
    val context = LocalContext.current
    var playing by remember(message.id) { mutableStateOf(false) }
    var playbackLease by remember(message.id) {
        mutableStateOf<SecureMediaLease?>(null)
    }
    var playbackFailed by remember(message.id) { mutableStateOf(false) }
    DisposableEffect(playbackLease) {
        val heldLease = playbackLease
        onDispose { heldLease?.close() }
    }
    // A video the user started is theirs until it ends: leaving Kit Pay hands it to the system's
    // floating window instead of cutting it off, and the window goes when the video does.
    ChatVideoPictureInPictureEffect(isPlaying = playing)
    if (!playing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val poster by produceState<ImageBitmap?>(initialValue = null, message.id) {
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        chatMediaPlaybackLease(context, media).use { lease ->
                            videoPosterFrame(lease.file)?.asImageBitmap()
                        }
                    }.getOrNull()
                }
            }
            poster?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Video",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .pointerInput(message.id) {
                        detectTapGestures {
                            // Two things must never share the audio route.
                            VoiceNotePlayer.stop()
                            playbackFailed = false
                            runCatching { chatMediaPlaybackLease(context, media) }
                                .onSuccess { lease ->
                                    playbackLease = lease
                                    playing = true
                                }
                                .onFailure { playbackFailed = true }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (playbackFailed) {
                    Text(
                        "Retry",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    } else {
        val lease = playbackLease ?: return
        AndroidView(
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    setVideoPath(lease.file.absolutePath)
                    setMediaController(android.widget.MediaController(viewContext).also {
                        it.setAnchorView(this)
                    })
                    setOnPreparedListener { player -> player.start() }
                    // The end of the video is the end of the floating window.
                    setOnCompletionListener {
                        playing = false
                        playbackLease = null
                    }
                    setOnErrorListener { _, _, _ ->
                        playing = false
                        playbackLease = null
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view -> view.stopPlayback() },
        )
    }
}

@Composable
private fun GalleryStatePage(text: String?, actionLabel: String?, onAction: () -> Unit) {
    // The spinner page shows no action label, so the whole surface takes the tap.
    val tappable = if (text == null) {
        Modifier.pointerInput(Unit) { detectTapGestures { onAction() } }
    } else {
        Modifier
    }
    Box(Modifier.fillMaxSize().then(tappable), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (text == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null) {
                    Spacer(Modifier.width(0.dp))
                    Text(
                        actionLabel,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .pointerInput(Unit) { detectTapGestures { onAction() } },
                    )
                }
            }
        }
    }
}

private fun shareGalleryMedia(
    context: android.content.Context,
    message: Message,
    media: SecureMediaFile,
) {
    val mediaType = message.mediaType
        ?: if (message.kind == MessageKind.VIDEO) "video/mp4" else "image/jpeg"
    launchWithChatMediaUri(context, media) { uri ->
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(mediaType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null,
            ),
        )
    }
}

/** Saves the decrypted media into the public gallery via MediaStore (API 29+ scoped storage). */
private fun saveGalleryMedia(
    context: android.content.Context,
    message: Message,
    source: File,
) {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Saving requires Android 10" }
    val mediaType = message.mediaType
        ?: if (message.kind == MessageKind.VIDEO) "video/mp4" else "image/jpeg"
    val isVideo = message.kind == MessageKind.VIDEO
    val collection = if (isVideo) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val values = ContentValues().apply {
        put(
            MediaStore.MediaColumns.DISPLAY_NAME,
            "KitPay-${message.id.take(8)}.${chatMediaFileExtension(mediaType)}",
        )
        put(MediaStore.MediaColumns.MIME_TYPE, mediaType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Kit Pay")
    }
    val uri = checkNotNull(context.contentResolver.insert(collection, values)) {
        "The gallery did not accept this media"
    }
    // Streamed, not written in one go: a saved video is as large as the wire now allows.
    context.contentResolver.openOutputStream(uri)?.use { output ->
        source.inputStream().use { input -> input.copyTo(output) }
    } ?: error("The gallery entry could not be written")
}

/** Cleans stale decrypted temp files left behind by interrupted viewers, on a best-effort basis. */
internal fun File.isChatMediaTempDirectory(): Boolean = name.length == 36 && isDirectory
