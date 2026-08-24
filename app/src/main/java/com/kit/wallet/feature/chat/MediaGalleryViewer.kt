package com.kit.wallet.feature.chat

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.kit.wallet.data.messaging.chatMediaFileExtension
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Media kinds the connected gallery can page through. */
internal val GALLERY_MEDIA_KINDS = setOf(MessageKind.IMAGE, MessageKind.VIDEO)

/**
 * WhatsApp-style connected full-screen gallery over the conversation's media messages:
 * open on the tapped item, swipe left/right through the rest, pinch/pan/double-tap zoom
 * for photos, inline playback for videos, and share/save actions. Decrypted bytes come
 * from the conversation's bounded in-memory media cache; adjacent pages load on demand.
 */
@Composable
internal fun ConversationMediaGallery(
    chatName: String,
    mediaMessages: List<Message>,
    initialMessageId: String,
    mediaBytes: Map<String, ByteArray>,
    mediaLoading: Set<String>,
    mediaErrors: Map<String, String>,
    onLoad: (Message) -> Unit,
    onRetry: (Message) -> Unit,
    onDismiss: () -> Unit,
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

    // Load the visible page and its neighbours so swiping stays smooth.
    LaunchedEffect(pagerState.currentPage, mediaMessages.size) {
        listOf(pagerState.currentPage - 1, pagerState.currentPage, pagerState.currentPage + 1)
            .filter { it in mediaMessages.indices }
            .map(mediaMessages::get)
            .forEach { message ->
                if (
                    !mediaBytes.containsKey(message.id) &&
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
                val bytes = mediaBytes[message.id]
                when {
                    bytes != null && message.kind == MessageKind.IMAGE ->
                        ZoomableGalleryImage(messageId = message.id, bytes = bytes)
                    bytes != null && message.kind == MessageKind.VIDEO ->
                        GalleryVideoPage(message = message, bytes = bytes)
                    mediaErrors.containsKey(message.id) -> GalleryStatePage(
                        text = mediaErrors[message.id] ?: "This media could not be loaded",
                        actionLabel = "Tap to retry",
                        onAction = { onRetry(message) },
                    )
                    else -> GalleryStatePage(text = null, actionLabel = null, onAction = {})
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
                val currentBytes = current?.let { mediaBytes[it.id] }
                IconButton(
                    enabled = currentBytes != null,
                    onClick = {
                        val message = current ?: return@IconButton
                        val bytes = currentBytes ?: return@IconButton
                        actionNotice = runCatching {
                            shareGalleryMedia(context, message, bytes)
                            null
                        }.getOrElse { "This media could not be shared" }
                    },
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = "Share",
                        tint = Color.White.copy(alpha = if (currentBytes != null) 1f else 0.4f),
                    )
                }
                IconButton(
                    enabled = currentBytes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onClick = {
                        val message = current ?: return@IconButton
                        val bytes = currentBytes ?: return@IconButton
                        actionNotice = runCatching {
                            saveGalleryMedia(context, message, bytes)
                            "Saved to your gallery"
                        }.getOrElse { "This media could not be saved" }
                    },
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Save",
                        tint = Color.White.copy(
                            alpha = if (
                                currentBytes != null &&
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

            actionNotice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
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
private fun ZoomableGalleryImage(messageId: String, bytes: ByteArray) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, messageId) {
        value = withOwnedSecureMediaSnapshot(bytes) { owned ->
            withContext(Dispatchers.Default) { decodeBoundedSecureImage(owned) }
        }
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

/** Full-screen playback for one decrypted video from a private temp file, cleaned on leave. */
@Composable
private fun GalleryVideoPage(message: Message, bytes: ByteArray) {
    val context = LocalContext.current
    var playing by remember(message.id) { mutableStateOf(false) }
    if (!playing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val poster by produceState<ImageBitmap?>(initialValue = null, message.id) {
                value = withOwnedSecureMediaSnapshot(bytes) { owned ->
                    withContext(Dispatchers.Default) {
                        videoPosterFrame(context, owned, message.mediaType ?: "video/mp4")
                            ?.asImageBitmap()
                    }
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
                    .pointerInput(message.id) { detectTapGestures { playing = true } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    } else {
        val file = remember(message.id) {
            writeChatMediaTempFile(
                context = context,
                plaintext = bytes,
                mediaType = message.mediaType ?: "video/mp4",
                displayName = null,
            )
        }
        androidx.compose.runtime.DisposableEffect(message.id) {
            onDispose { deleteChatMediaTempFile(file) }
        }
        AndroidView(
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    setVideoPath(file.absolutePath)
                    setMediaController(android.widget.MediaController(viewContext).also {
                        it.setAnchorView(this)
                    })
                    setOnPreparedListener { player -> player.start() }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view -> view.stopPlayback() },
        )
    }
}

@Composable
private fun GalleryStatePage(text: String?, actionLabel: String?, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
    bytes: ByteArray,
) {
    val mediaType = message.mediaType
        ?: if (message.kind == MessageKind.VIDEO) "video/mp4" else "image/jpeg"
    val file = writeChatMediaTempFile(context, bytes, mediaType, displayName = null)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.chatmedia", file)
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

/** Saves the decrypted media into the public gallery via MediaStore (API 29+ scoped storage). */
private fun saveGalleryMedia(
    context: android.content.Context,
    message: Message,
    bytes: ByteArray,
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
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: error("The gallery entry could not be written")
}

/** Cleans stale decrypted temp files left behind by interrupted viewers, on a best-effort basis. */
internal fun File.isChatMediaTempDirectory(): Boolean = name.length == 36 && isDirectory
