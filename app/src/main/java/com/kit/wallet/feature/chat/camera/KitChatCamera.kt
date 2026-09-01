package com.kit.wallet.feature.chat.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kit.wallet.data.media.uploadSampleSize
import android.net.Uri
import com.kit.wallet.data.messaging.KitChatMediaLimits
import com.kit.wallet.data.messaging.SecureMediaProcessingPlan
import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.messaging.SecureMediaVideoEditPlan
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.feature.chat.CHAT_IMAGE_MAX_DIMENSION
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Press-and-hold on the shutter longer than this starts a video recording (Instagram feel). */
internal const val SHUTTER_HOLD_TO_RECORD_MILLIS = 250L

/** A capture the editor can still change; nothing is encoded or sent yet. */
internal sealed interface CameraCaptureDraft {
    val originatedAtNanos: Long

    data class Photo(
        val bitmap: Bitmap,
        override val originatedAtNanos: Long = System.nanoTime(),
    ) : CameraCaptureDraft

    data class Video(
        val file: File,
        override val originatedAtNanos: Long = System.nanoTime(),
    ) : CameraCaptureDraft

    fun release() {
        when (this) {
            is Photo -> bitmap.recycle()
            is Video -> file.delete()
        }
    }
}

/**
 * The full in-app capture journey: full-screen camera (tap for a photo, hold for a video),
 * then the draft editor (filters, drawing, text, stickers, crop for photos; trim, mute and
 * caption for videos), then an ownership handoff on a worker dispatcher. The caller receives a
 * way to open the original immediately; upload-only encoding/remuxing happens later in durable
 * background work, and the caller releases this handoff file after local adoption.
 */
@Composable
internal fun KitChatCameraFlow(
    maxTransferBytes: Long,
    onDismiss: () -> Unit,
    onSendMedia: (EncodedCaptureMedia) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<CameraCaptureDraft?>(null) }
    var preparing by remember { mutableStateOf(false) }
    fun closeFlow() {
        if (preparing) return
        draft?.release()
        draft = null
        onDismiss()
    }
    DisposableEffect(Unit) {
        onDispose { draft?.release() }
    }
    Dialog(
        onDismissRequest = ::closeFlow,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val current = draft
            if (current == null) {
                KitChatCameraCapture(
                    maxRecordingBytes = maxTransferBytes,
                    onClose = ::closeFlow,
                    onCaptured = { captured ->
                        // A fumbled hold produces an unplayable blink of a clip; drop it quietly
                        // and stay in the camera instead of opening an editor for nothing.
                        if (
                            captured is CameraCaptureDraft.Video &&
                            ChatVideoTranscoder.durationMillis(captured.file) < MIN_CLIP_MILLIS
                        ) {
                            captured.release()
                        } else {
                            draft = captured
                        }
                    },
                    onUnavailable = { message ->
                        onError(message)
                        closeFlow()
                    },
                )
            } else {
                CaptureDraftEditor(
                    draft = current,
                    busy = preparing,
                    onDiscard = {
                        current.release()
                        draft = null
                    },
                    onSend = { spec ->
                        scope.launch {
                            preparing = true
                            val encoded = withContext(Dispatchers.Default) {
                                runCatching {
                                    encodeCaptureDraft(context, current, spec, maxTransferBytes)
                                }.getOrNull()
                            }
                            preparing = false
                            if (encoded == null) {
                                onError("That capture could not be prepared")
                            } else {
                                onSendMedia(encoded)
                                current.release()
                                draft = null
                                onDismiss()
                            }
                        }
                    },
                )
            }
            if (preparing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        // The scrim must own hit-testing while encoding runs: an editor tap
                        // underneath could recycle the very bitmap being encoded.
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

/** A library video staged into the capture cache so the trim editor can seek and cut it. */
internal class LibraryVideoDraft(
    val file: File,
    val mediaType: String,
    val originatedAtNanos: Long,
)

/**
 * Copies a picked library video into the capture cache. The copy is what makes trimming
 * possible at all — MediaExtractor needs a seekable file, not a one-shot content stream —
 * and it is bounded by [MAX_LIBRARY_VIDEO_SOURCE_BYTES] so a pathological pick cannot fill
 * the disk. Runs on a worker dispatcher; throws with a customer-readable message.
 */
internal fun stageLibraryVideoForEditing(
    context: Context,
    uri: Uri,
    resolvedMediaType: String,
    originatedAtNanos: Long = System.nanoTime(),
): LibraryVideoDraft {
    val directory = File(context.cacheDir, "chat-capture").apply { mkdirs() }
    val staged = File(directory, "library-${UUID.randomUUID()}.video")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("The selected video could not be opened")
        input.use { source ->
            staged.outputStream().use { sink ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= MAX_LIBRARY_VIDEO_SOURCE_BYTES) {
                        "That video is too large to edit here. Choose a shorter video."
                    }
                    sink.write(buffer, 0, read)
                }
                check(total > 0L) { "The selected video could not be opened" }
            }
        }
        check(ChatVideoTranscoder.durationMillis(staged) >= MIN_CLIP_MILLIS) {
            "That video is too short to send"
        }
        return LibraryVideoDraft(
            file = staged,
            mediaType = KitMediaMessage.normalizeMediaType(resolvedMediaType) ?: "video/mp4",
            originatedAtNanos = originatedAtNanos,
        )
    } catch (error: Exception) {
        staged.delete()
        throw error
    }
}

/**
 * The capture editor alone, for a video that arrived from the photo library rather than the
 * lens. Same trim, mute, and caption; same encoder; the only difference is that there is no
 * camera to fall back to, so discarding the draft closes the flow.
 */
@Composable
internal fun KitChatVideoEditorFlow(
    draft: LibraryVideoDraft,
    maxTransferBytes: Long,
    onDismiss: () -> Unit,
    onSendMedia: (EncodedCaptureMedia) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val captureDraft = remember(draft) {
        CameraCaptureDraft.Video(draft.file, draft.originatedAtNanos)
    }
    fun closeFlow() {
        if (preparing) return
        captureDraft.release()
        onDismiss()
    }
    DisposableEffect(Unit) {
        onDispose { captureDraft.release() }
    }
    Dialog(
        onDismissRequest = ::closeFlow,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            CaptureDraftEditor(
                draft = captureDraft,
                busy = preparing,
                onDiscard = ::closeFlow,
                onSend = { spec ->
                    scope.launch {
                        preparing = true
                        val encoded = withContext(Dispatchers.Default) {
                            runCatching {
                                encodeCaptureDraft(
                                    context,
                                    captureDraft,
                                    spec,
                                    maxTransferBytes,
                                    sourceMediaType = draft.mediaType,
                                )
                            }.getOrNull()
                        }
                        preparing = false
                        if (encoded == null) {
                            onError(
                                "That video could not be prepared. Trim it to a shorter clip " +
                                    "and try again.",
                            )
                        } else {
                            onSendMedia(encoded)
                            captureDraft.release()
                            onDismiss()
                        }
                    }
                },
            )
            if (preparing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        // The scrim must own hit-testing while encoding runs, exactly as in
                        // the camera flow: a tap underneath could release the file mid-read.
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

internal class EncodedCaptureMedia(
    val source: SecureMediaSource,
    val mediaType: String,
    val caption: String?,
    /**
     * Drops whatever the encoder wrote for this capture. Called once the send path has finished
     * reading the source — a recorded video is handed over as a file rather than copied into
     * heap, so somebody has to own the moment it stops being needed.
     */
    val release: () -> Unit = {},
)

/** Publishes an editor result as a local playback original. Wire optimization happens later. */
internal fun encodeCaptureDraft(
    context: Context,
    draft: CameraCaptureDraft,
    spec: CaptureSendSpec,
    maxTransferBytes: Long,
    sourceMediaType: String = "video/mp4",
): EncodedCaptureMedia? = when {
    draft is CameraCaptureDraft.Photo && spec is CaptureSendSpec.Photo -> {
        val baked = bakePhotoDraft(spec)
        val directory = File(context.cacheDir, "chat-capture").apply { mkdirs() }
        val payload = File(directory, "send-${UUID.randomUUID()}.jpg")
        try {
            val written = runCatching {
                FileOutputStream(payload).use { output ->
                    check(baked.compress(Bitmap.CompressFormat.JPEG, 100, output))
                    output.flush()
                    output.fd.sync()
                }
                payload.isFile && payload.length() in 1..maxTransferBytes
            }.getOrDefault(false)
            if (written) {
                EncodedCaptureMedia(
                    SecureMediaSource.ofFile(
                        file = payload,
                        originatedAtNanos = draft.originatedAtNanos,
                        originalMediaType = "image/jpeg",
                        processingPlan = SecureMediaProcessingPlan.CHAT_IMAGE_JPEG,
                    ),
                    "image/jpeg",
                    spec.caption,
                    release = { payload.delete() },
                )
            } else {
                payload.delete()
                null
            }
        } finally {
            if (baked !== spec.bitmap) baked.recycle()
        }
    }
    draft is CameraCaptureDraft.Video && spec is CaptureSendSpec.Video -> {
        encodeVideoDraft(
            context,
            draft.file,
            spec,
            maxTransferBytes,
            sourceMediaType,
            draft.originatedAtNanos,
        )
    }
    else -> null
}

private fun encodeVideoDraft(
    context: Context,
    source: File,
    spec: CaptureSendSpec.Video,
    maxTransferBytes: Long,
    sourceMediaType: String,
    sourceOriginNanos: Long,
): EncodedCaptureMedia? {
    val durationMillis = ChatVideoTranscoder.durationMillis(source)
    if (durationMillis <= 0L) return null
    val untouched = spec.startMillis <= 0L && spec.endMillis >= durationMillis && !spec.muted
    val editPlan = if (untouched) {
        null
    } else {
        val planned = planVideoTrim(
            spec.startMillis,
            spec.endMillis,
            durationMillis,
            keepAudio = !spec.muted,
        ) ?: return null
        SecureMediaVideoEditPlan(
            startMicros = planned.startMicros,
            endMicros = planned.endMicros,
            keepAudio = planned.keepAudio,
        )
    }
    if (source.length() <= 0L || source.length() > MAX_LIBRARY_VIDEO_SOURCE_BYTES) return null
    if (untouched && source.length() > maxTransferBytes) return null
    val directory = File(context.cacheDir, "chat-capture").apply { mkdirs() }
    // Transfer ownership with a same-directory rename. The editor can close immediately and the
    // sender can play this original at once; a durable worker performs any trim/mute remux later.
    val payload = File(directory, "send-${UUID.randomUUID()}.video")
        .takeIf { source.renameTo(it) }
        ?: return null
    return EncodedCaptureMedia(
        source = SecureMediaSource.ofFile(
            file = payload,
            originatedAtNanos = sourceOriginNanos,
            originalMediaType = sourceMediaType,
            durationMillis = durationMillis,
            processingPlan = if (editPlan == null) {
                SecureMediaProcessingPlan.PASSTHROUGH
            } else {
                SecureMediaProcessingPlan.CHAT_VIDEO_MP4
            },
            videoEditPlan = editPlan,
        ),
        mediaType = videoSendMediaType(edited = !untouched, sourceMediaType = sourceMediaType),
        caption = spec.caption,
        release = { payload.delete() },
    )
}

/** One bound CameraX pipeline. LIMITED devices reject the combined trio; see [bindCameraSession]. */
private class CameraSession(
    val camera: Camera,
    val imageCapture: ImageCapture?,
    val videoCapture: VideoCapture<Recorder>?,
)

/**
 * Binds preview + photo + video in one lifecycle bind so mode switches are instant, and falls
 * back to per-mode binding when the device cannot serve all three use cases at once. In the
 * fallback, photo mode is bound by default and recording rebinds on demand.
 */
private fun bindCameraSession(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    selector: CameraSelector,
    videoOnly: Boolean,
): CameraSession {
    provider.unbindAll()
    val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(previewView.surfaceProvider)
    }
    val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    val recorder = Recorder.Builder()
        .setQualitySelector(
            QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.HD)),
        )
        .build()
    val videoCapture = VideoCapture.withOutput(recorder)
    if (videoOnly) {
        val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
        return CameraSession(camera, imageCapture = null, videoCapture = videoCapture)
    }
    try {
        val camera = provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            imageCapture,
            videoCapture,
        )
        return CameraSession(camera, imageCapture, videoCapture)
    } catch (_: Exception) {
        provider.unbindAll()
    }
    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    return CameraSession(camera, imageCapture, videoCapture = null)
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

/**
 * Decodes an in-memory CameraX JPEG capture to at most [CHAT_IMAGE_MAX_DIMENSION] on its longest
 * side, upright per the capture rotation, and mirrored for the front camera so the sent photo is
 * exactly the preview the customer framed.
 */
internal fun decodeCapturedJpeg(bytes: ByteArray, rotationDegrees: Int, mirrored: Boolean): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = uploadSampleSize(max(bounds.outWidth, bounds.outHeight), CHAT_IMAGE_MAX_DIMENSION)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    if (rotationDegrees % 360 == 0 && !mirrored) return decoded
    val matrix = Matrix().apply {
        postRotate(rotationDegrees.toFloat())
        if (mirrored) postScale(-1f, 1f)
    }
    val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (transformed !== decoded) decoded.recycle()
    return transformed
}

@Composable
private fun KitChatCameraCapture(
    maxRecordingBytes: Long,
    onClose: () -> Unit,
    onCaptured: (CameraCaptureDraft) -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var videoOnlyBinding by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var recordingElapsedMillis by remember { mutableLongStateOf(0L) }
    // CameraX delivers the recording's Finalize event after this composable can already be
    // gone (close/back mid-recording); the listener must then delete the file, not publish a
    // draft into dead state.
    val disposed = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose { disposed.set(true) }
    }
    val recording = activeRecording != null

    LaunchedEffect(Unit) {
        provider = runCatching { awaitCameraProvider(context) }.getOrNull()
        if (provider == null) onUnavailable("The camera could not be started")
    }
    LaunchedEffect(provider, lensFacing, videoOnlyBinding) {
        val ready = provider ?: return@LaunchedEffect
        session = runCatching {
            bindCameraSession(
                provider = ready,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                selector = CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                videoOnly = videoOnlyBinding,
            )
        }.getOrNull()
        if (session == null) onUnavailable("The camera could not be started")
    }
    DisposableEffect(provider) {
        onDispose {
            runCatching { activeRecording?.stop() }
            provider?.unbindAll()
        }
    }
    // Recording clock: drives the timer label and stops at the video-note duration ceiling.
    LaunchedEffect(recording) {
        recordingElapsedMillis = 0L
        while (activeRecording != null) {
            recordingElapsedMillis = SystemClock.elapsedRealtime() - recordingStartedAt
            if (recordingElapsedMillis >= KitChatMediaLimits.VIDEO_NOTE_MAX_DURATION_SECONDS * 1_000L) {
                runCatching { activeRecording?.stop() }
            }
            delay(100)
        }
    }

    fun capturePhoto() {
        val capture = session?.imageCapture ?: return
        capture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val jpeg = try {
                        val buffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also(buffer::get)
                    } finally {
                        image.close()
                    }
                    // The full-resolution capture JPEG is plaintext; wipe it once decoded,
                    // like every other picker/capture byte array.
                    scope.launch {
                        val bitmap = try {
                            withContext(Dispatchers.Default) {
                                decodeCapturedJpeg(jpeg, rotation, mirrored)
                            }
                        } finally {
                            jpeg.fill(0)
                        }
                        if (bitmap != null) {
                            onCaptured(CameraCaptureDraft.Photo(bitmap))
                        } else {
                            onUnavailable("The photo could not be processed")
                        }
                    }.invokeOnCompletion { jpeg.fill(0) }
                }

                override fun onError(exception: ImageCaptureException) {
                    onUnavailable("The photo could not be captured")
                }
            },
        )
    }

    /** LIMITED-tier fallback: rebind to the video pipeline and wait for it before recording. */
    suspend fun ensureVideoCapture(): VideoCapture<Recorder>? {
        session?.videoCapture?.let { return it }
        videoOnlyBinding = true
        val capture = withTimeoutOrNull(2_000L) {
            snapshotFlow { session }.first { it?.videoCapture != null }?.videoCapture
        }
        // A failed rebind must not strand the camera in the photo-less video binding.
        if (capture == null) videoOnlyBinding = false
        return capture
    }

    suspend fun startRecording(): Boolean {
        val videoCapture = ensureVideoCapture() ?: return false
        val bound = session ?: return false
        val directory = File(context.cacheDir, "chat-capture").apply { mkdirs() }
        val file = File(directory, "capture-${UUID.randomUUID()}.mp4")
        // The cap is read from live policy at open time, so when the transfer limit rises the
        // camera immediately records longer clips without an app change.
        val output = FileOutputOptions.Builder(file)
            .setFileSizeLimit(maxRecordingBytes)
            .build()
        val pending = videoCapture.output.prepareRecording(context, output)
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) pending.withAudioEnabled()
        if (flashOn && bound.camera.cameraInfo.hasFlashUnit()) {
            bound.camera.cameraControl.enableTorch(true)
        }
        recordingStartedAt = SystemClock.elapsedRealtime()
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (disposed.get()) {
                    file.delete()
                    return@start
                }
                activeRecording = null
                runCatching { session?.camera?.cameraControl?.enableTorch(false) }
                if (videoOnlyBinding) videoOnlyBinding = false
                val usable = !event.hasError() || event.error in USABLE_FINALIZE_ERRORS
                if (usable && file.length() > 0L) {
                    onCaptured(CameraCaptureDraft.Video(file))
                } else {
                    file.delete()
                    if (event.hasError()) onUnavailable("The video could not be recorded")
                }
            }
        }
        return true
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Tap to focus and pinch to zoom live directly on the preview surface. These handlers
        // (and the shutter's) are keyed on Unit and read the current session through state, so
        // a rebind mid-gesture never cancels an in-flight press handler.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val camera = session?.camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        camera.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).build(),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val camera = session?.camera ?: return@detectTransformGestures
                        val zoomState = camera.cameraInfo.zoomState.value
                            ?: return@detectTransformGestures
                        camera.cameraControl.setZoomRatio(
                            (zoomState.zoomRatio * zoom)
                                .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio),
                        )
                    }
                },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close camera", tint = Color.White)
            }
            Row {
                IconButton(onClick = { flashOn = !flashOn }) {
                    Icon(
                        if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        contentDescription = if (flashOn) "Flash on" else "Flash off",
                        tint = Color.White,
                    )
                }
                if (!recording) {
                    IconButton(onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }) {
                        Icon(
                            Icons.Rounded.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (recording) {
                Surface(color = Color.Black.copy(alpha = 0.45f), shape = CircleShape) {
                    Text(
                        formatRecordingClock(recordingElapsedMillis),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            } else {
                Text(
                    "Tap for a photo, hold for a video",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(14.dp))
            val shutterScale by animateFloatAsState(
                targetValue = if (recording) 1.18f else 1f,
                label = "shutterScale",
            )
            Box(
                Modifier
                    .size(78.dp)
                    .scale(shutterScale)
                    .background(Color.White.copy(alpha = 0.28f), CircleShape)
                    .padding(6.dp)
                    .background(
                        if (recording) Color(0xFFE53935) else Color.White,
                        CircleShape,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            // One press handler owns both behaviours; adding onTap alongside
                            // would double-fire the photo path.
                            onPress = {
                                val releasedInTime = withTimeoutOrNull(SHUTTER_HOLD_TO_RECORD_MILLIS) {
                                    tryAwaitRelease()
                                }
                                when {
                                    releasedInTime == null -> {
                                        if (startRecording()) {
                                            tryAwaitRelease()
                                            runCatching { activeRecording?.stop() }
                                        }
                                    }
                                    releasedInTime -> capturePhoto()
                                }
                            },
                        )
                    },
            )
        }
    }
}

private val USABLE_FINALIZE_ERRORS = setOf(
    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
)

internal fun formatRecordingClock(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
