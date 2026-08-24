package com.kit.wallet.feature.chat.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the editor decided; baking and encoding happen later on a worker dispatcher. */
internal sealed interface CaptureSendSpec {
    data class Photo(
        val bitmap: Bitmap,
        val filter: CaptureFilter,
        val strokes: List<EditorStroke>,
        val texts: List<EditorText>,
        val caption: String?,
    ) : CaptureSendSpec

    data class Video(
        val startMillis: Long,
        val endMillis: Long,
        val muted: Boolean,
        val caption: String?,
    ) : CaptureSendSpec
}

internal data class EditorStroke(
    val points: List<EditorPoint>,
    val colorArgb: Int,
    val widthFraction: Float,
)

/** Draggable overlay text; emoji stickers are the same thing at a larger size. */
internal data class EditorText(
    val id: Long,
    val text: String,
    val center: EditorPoint,
    val colorArgb: Int,
    val sizeFraction: Float,
)

/**
 * Bakes the working bitmap, the chosen colour grade and every overlay into one new bitmap using
 * the exact coefficients and normalized coordinates the preview rendered, so the recipient's
 * pixels match the sender's screen.
 */
internal fun bakePhotoDraft(spec: CaptureSendSpec.Photo): Bitmap {
    val graded = applyCaptureFilter(spec.bitmap, spec.filter)
    if (spec.strokes.isEmpty() && spec.texts.isEmpty()) return graded
    val output = if (graded === spec.bitmap) {
        spec.bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return graded
    } else {
        graded
    }
    val canvas = AndroidCanvas(output)
    val width = output.width.toFloat()
    val height = output.height.toFloat()
    spec.strokes.forEach { stroke ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.colorArgb
            style = Paint.Style.STROKE
            strokeWidth = stroke.widthFraction * width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val first = stroke.points.firstOrNull() ?: return@forEach
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(first.x * width, first.y * height, paint.strokeWidth.coerceAtLeast(1f) / 2f, paint)
        } else {
            val path = android.graphics.Path().apply {
                moveTo(first.x * width, first.y * height)
                stroke.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
            }
            canvas.drawPath(path, paint)
        }
    }
    spec.texts.forEach { item ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.colorArgb
            textSize = item.sizeFraction * width
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = item.center.y * height - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(item.text, item.center.x * width, baseline, paint)
    }
    return output
}

internal fun rotateBitmapQuarter(source: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

internal fun cropBitmap(source: Bitmap, crop: EditorRect): Bitmap {
    val left = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 1)
    val top = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 1)
    val width = (crop.width * source.width).roundToInt().coerceIn(1, source.width - left)
    val height = (crop.height * source.height).roundToInt().coerceIn(1, source.height - top)
    return Bitmap.createBitmap(source, left, top, width, height)
}

@Composable
internal fun CaptureDraftEditor(
    draft: CameraCaptureDraft,
    busy: Boolean,
    onDiscard: () -> Unit,
    onSend: (CaptureSendSpec) -> Unit,
) {
    when (draft) {
        is CameraCaptureDraft.Photo -> PhotoDraftEditor(draft.bitmap, busy, onDiscard, onSend)
        is CameraCaptureDraft.Video -> VideoDraftEditor(draft.file, busy, onDiscard, onSend)
    }
}

private enum class PhotoTool { NONE, DRAW, ADJUST }

private val DRAW_COLORS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF111111.toInt(),
    0xFFE53935.toInt(),
    0xFFFFC107.toInt(),
    0xFF34B98B.toInt(),
    0xFF2196F3.toInt(),
    0xFFF06292.toInt(),
)

private val DRAW_WIDTH_FRACTIONS = listOf(0.008f, 0.016f, 0.030f)

private const val TEXT_SIZE_FRACTION = 0.08f
private const val STICKER_SIZE_FRACTION = 0.18f
private const val MIN_CROP_FRACTION = 0.15f

private val STICKER_EMOJI = listOf(
    "😀", "😂", "😍", "🥰", "😎", "🤩", "😢", "😡",
    "👍", "🙏", "🔥", "❤️", "💯", "🎉", "👏", "🤝",
    "💰", "🥳", "😅", "🤔", "😭", "🙌", "✨", "🇺🇬",
)

@Composable
private fun PhotoDraftEditor(
    original: Bitmap,
    busy: Boolean,
    onDiscard: () -> Unit,
    onSend: (CaptureSendSpec) -> Unit,
) {
    var working by remember(original) { mutableStateOf(original) }
    // The working bitmap is replaced destructively on rotate/crop; only intermediates are
    // recycled here — the flow owns and releases the original capture.
    val workingRef = remember(original) { arrayOfNulls<Bitmap>(1) }
    workingRef[0] = working
    DisposableEffect(original) {
        onDispose {
            workingRef[0]?.takeIf { it !== original }?.recycle()
        }
    }
    var tool by remember { mutableStateOf(PhotoTool.NONE) }
    var selectedFilter by remember { mutableStateOf(DEFAULT_CAPTURE_FILTER) }
    val strokes = remember { mutableStateListOf<EditorStroke>() }
    val texts = remember { mutableStateListOf<EditorText>() }
    var nextOverlayId by remember { mutableLongStateOf(1L) }
    var selectedTextId by remember { mutableStateOf<Long?>(null) }
    var drawColor by remember { mutableStateOf(DRAW_COLORS.first()) }
    var drawWidth by remember { mutableStateOf(DRAW_WIDTH_FRACTIONS[1]) }
    var currentStrokePoints by remember { mutableStateOf<List<EditorPoint>?>(null) }
    var cropRect by remember { mutableStateOf(EditorRect.FULL) }
    var caption by remember { mutableStateOf("") }
    var textEntry by remember { mutableStateOf<String?>(null) }
    var stickerPickerOpen by remember { mutableStateOf(false) }

    fun replaceWorking(next: Bitmap) {
        val previous = working
        working = next
        if (previous !== original) previous.recycle()
    }

    fun rotateWorking() {
        val aspect = working.width.toFloat() / working.height.toFloat()
        replaceWorking(rotateBitmapQuarter(working))
        for (i in strokes.indices) {
            val stroke = strokes[i]
            strokes[i] = stroke.copy(
                points = stroke.points.map(::rotateQuarterClockwise),
                widthFraction = widthFractionAfterQuarterRotation(stroke.widthFraction, aspect),
            )
        }
        for (i in texts.indices) {
            val item = texts[i]
            texts[i] = item.copy(
                center = rotateQuarterClockwise(item.center),
                sizeFraction = widthFractionAfterQuarterRotation(item.sizeFraction, aspect),
            )
        }
        cropRect = EditorRect.FULL
    }

    fun applyCrop() {
        val crop = cropRect
        cropRect = EditorRect.FULL
        tool = PhotoTool.NONE
        if (crop.width >= 0.999f && crop.height >= 0.999f) return
        replaceWorking(cropBitmap(working, crop))
        for (i in strokes.indices) {
            val stroke = strokes[i]
            strokes[i] = stroke.copy(
                points = stroke.points.map { mapThroughCrop(it, crop) },
                widthFraction = widthFractionAfterCrop(stroke.widthFraction, crop),
            )
        }
        for (i in texts.indices) {
            val item = texts[i]
            texts[i] = item.copy(
                center = mapThroughCrop(item.center, crop),
                sizeFraction = widthFractionAfterCrop(item.sizeFraction, crop),
            )
        }
    }

    val imageBitmap = remember(working) { working.asImageBitmap() }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDiscard, enabled = !busy) {
                Icon(Icons.Rounded.Close, contentDescription = "Discard capture", tint = Color.White)
            }
            Row {
                if (tool == PhotoTool.DRAW && strokes.isNotEmpty()) {
                    IconButton(onClick = { strokes.removeAt(strokes.lastIndex) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = "Undo drawing",
                            tint = Color.White,
                        )
                    }
                }
                EditorToolIcon(
                    icon = Icons.Rounded.Brush,
                    label = "Draw",
                    active = tool == PhotoTool.DRAW,
                ) { tool = if (tool == PhotoTool.DRAW) PhotoTool.NONE else PhotoTool.DRAW }
                EditorToolIcon(icon = Icons.Rounded.Title, label = "Add text", active = false) {
                    tool = PhotoTool.NONE
                    textEntry = ""
                }
                EditorToolIcon(
                    icon = Icons.Rounded.AddReaction,
                    label = "Add sticker",
                    active = stickerPickerOpen,
                ) {
                    tool = PhotoTool.NONE
                    stickerPickerOpen = true
                }
                EditorToolIcon(
                    icon = Icons.Rounded.Crop,
                    label = "Crop and rotate",
                    active = tool == PhotoTool.ADJUST,
                ) { tool = if (tool == PhotoTool.ADJUST) PhotoTool.NONE else PhotoTool.ADJUST }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The drawing surface IS the image rect: the same aspect box hosts the image, the
            // stroke canvas and the overlays, so screen-to-bitmap is one uniform scale.
            BoxWithConstraints(
                Modifier.aspectRatio(working.width.toFloat() / working.height.toFloat()),
            ) {
                val boxWidthPx = constraints.maxWidth.toFloat()
                val boxHeightPx = constraints.maxHeight.toFloat()
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Captured photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = selectedFilter.composeColorFilter(),
                )
                Canvas(Modifier.matchParentSize()) {
                    (strokes + listOfNotNull(
                        currentStrokePoints?.let { EditorStroke(it, drawColor, drawWidth) },
                    )).forEach { stroke ->
                        val first = stroke.points.firstOrNull() ?: return@forEach
                        val strokePx = stroke.widthFraction * size.width
                        if (stroke.points.size == 1) {
                            drawCircle(
                                color = Color(stroke.colorArgb),
                                radius = strokePx.coerceAtLeast(1f) / 2f,
                                center = Offset(first.x * size.width, first.y * size.height),
                            )
                        } else {
                            val path = Path().apply {
                                moveTo(first.x * size.width, first.y * size.height)
                                stroke.points.drop(1).forEach {
                                    lineTo(it.x * size.width, it.y * size.height)
                                }
                            }
                            drawPath(
                                path = path,
                                color = Color(stroke.colorArgb),
                                style = Stroke(
                                    width = strokePx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                    }
                }
                texts.forEach { item ->
                    key(item.id) {
                        var measured by remember { mutableStateOf(IntSize.Zero) }
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(
                                        (item.center.x * boxWidthPx - measured.width / 2f).roundToInt(),
                                        (item.center.y * boxHeightPx - measured.height / 2f).roundToInt(),
                                    )
                                }
                                .onSizeChanged { measured = it }
                                .pointerInput(item.id) {
                                    detectTapGestures {
                                        selectedTextId =
                                            if (selectedTextId == item.id) null else item.id
                                    }
                                }
                                .pointerInput(item.id, boxWidthPx, boxHeightPx) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val index = texts.indexOfFirst { it.id == item.id }
                                        if (index >= 0) {
                                            val current = texts[index]
                                            texts[index] = current.copy(
                                                center = EditorPoint(
                                                    (current.center.x + drag.x / boxWidthPx)
                                                        .coerceIn(0f, 1f),
                                                    (current.center.y + drag.y / boxHeightPx)
                                                        .coerceIn(0f, 1f),
                                                ),
                                            )
                                        }
                                    }
                                },
                        ) {
                            Text(
                                item.text,
                                color = Color(item.colorArgb),
                                fontSize = with(density) { (item.sizeFraction * boxWidthPx).toSp() },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            if (selectedTextId == item.id) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 14.dp, y = (-14).dp)
                                        .clickable {
                                            texts.removeAll { removed -> removed.id == item.id }
                                            selectedTextId = null
                                        },
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Remove",
                                        tint = Color.White,
                                        modifier = Modifier.padding(4.dp).size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (tool == PhotoTool.DRAW) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(boxWidthPx, boxHeightPx, drawColor, drawWidth) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentStrokePoints = listOf(
                                            EditorPoint(
                                                (offset.x / boxWidthPx).coerceIn(0f, 1f),
                                                (offset.y / boxHeightPx).coerceIn(0f, 1f),
                                            ),
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentStrokePoints = currentStrokePoints?.plus(
                                            EditorPoint(
                                                (change.position.x / boxWidthPx).coerceIn(0f, 1f),
                                                (change.position.y / boxHeightPx).coerceIn(0f, 1f),
                                            ),
                                        )
                                    },
                                    onDragEnd = {
                                        currentStrokePoints?.let {
                                            strokes.add(EditorStroke(it, drawColor, drawWidth))
                                        }
                                        currentStrokePoints = null
                                    },
                                    onDragCancel = { currentStrokePoints = null },
                                )
                            },
                    )
                }
                if (tool == PhotoTool.ADJUST) {
                    CropOverlay(
                        cropRect = cropRect,
                        boxWidthPx = boxWidthPx,
                        boxHeightPx = boxHeightPx,
                        onCropChange = { cropRect = it },
                    )
                }
            }
        }

        when (tool) {
            PhotoTool.DRAW -> DrawControls(
                selectedColor = drawColor,
                onColor = { drawColor = it },
                selectedWidth = drawWidth,
                onWidth = { drawWidth = it },
            )
            PhotoTool.ADJUST -> AdjustControls(
                onRotate = ::rotateWorking,
                onApply = ::applyCrop,
            )
            PhotoTool.NONE -> {
                FilterStrip(
                    source = working,
                    selected = selectedFilter,
                    onSelect = { selectedFilter = it },
                )
                CaptionSendRow(
                    caption = caption,
                    onCaption = { caption = it },
                    sendEnabled = !busy,
                    onSend = {
                        onSend(
                            CaptureSendSpec.Photo(
                                bitmap = working,
                                filter = selectedFilter,
                                strokes = strokes.toList(),
                                texts = texts.toList(),
                                caption = clampCaptionToWire(caption),
                            ),
                        )
                    },
                )
            }
        }
    }

    textEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { textEntry = null },
            title = { Text("Add text") },
            text = {
                OutlinedTextField(
                    value = entry,
                    onValueChange = { textEntry = it.take(80) },
                    singleLine = true,
                    label = { Text("Text") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = entry.isNotBlank(),
                    onClick = {
                        texts.add(
                            EditorText(
                                id = nextOverlayId++,
                                text = entry.trim(),
                                center = EditorPoint(0.5f, 0.5f),
                                colorArgb = drawColor,
                                sizeFraction = TEXT_SIZE_FRACTION,
                            ),
                        )
                        textEntry = null
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { textEntry = null }) { Text("Cancel") }
            },
        )
    }
    if (stickerPickerOpen) {
        AlertDialog(
            onDismissRequest = { stickerPickerOpen = false },
            title = { Text("Add a sticker") },
            text = {
                Column {
                    STICKER_EMOJI.chunked(8).forEach { row ->
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            row.forEach { emoji ->
                                Text(
                                    emoji,
                                    fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clickable {
                                            texts.add(
                                                EditorText(
                                                    id = nextOverlayId++,
                                                    text = emoji,
                                                    center = EditorPoint(0.5f, 0.5f),
                                                    colorArgb = 0xFFFFFFFF.toInt(),
                                                    sizeFraction = STICKER_SIZE_FRACTION,
                                                ),
                                            )
                                            stickerPickerOpen = false
                                        },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { stickerPickerOpen = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun EditorToolIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.secondary else Color.White,
        )
    }
}

@Composable
private fun DrawControls(
    selectedColor: Int,
    onColor: (Int) -> Unit,
    selectedWidth: Float,
    onWidth: (Float) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row {
            DRAW_COLORS.forEach { color ->
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(if (color == selectedColor) 30.dp else 24.dp)
                        .border(
                            width = 2.dp,
                            color = if (color == selectedColor) Color.White else Color.White.copy(alpha = 0.4f),
                            shape = CircleShape,
                        )
                        .padding(3.dp)
                        .background(Color(color), CircleShape)
                        .clickable { onColor(color) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DRAW_WIDTH_FRACTIONS.forEach { width ->
                Box(
                    Modifier
                        .padding(start = 10.dp)
                        .size(30.dp)
                        .border(
                            width = 1.dp,
                            color = if (width == selectedWidth) Color.White else Color.White.copy(alpha = 0.35f),
                            shape = CircleShape,
                        )
                        .clickable { onWidth(width) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size((width * 500f).dp.coerceAtLeast(4.dp))
                            .background(Color.White, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdjustControls(onRotate: () -> Unit, onApply: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRotate) {
            Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = "Rotate", tint = Color.White)
        }
        Text(
            "Drag the corners to crop",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelMedium,
        )
        IconButton(onClick = onApply) {
            Icon(Icons.Rounded.Check, contentDescription = "Apply crop", tint = Color.White)
        }
    }
}

@Composable
private fun CropOverlay(
    cropRect: EditorRect,
    boxWidthPx: Float,
    boxHeightPx: Float,
    onCropChange: (EditorRect) -> Unit,
) {
    // The drag handlers live in pointerInput blocks that never restart, so they must read the
    // rect through updated state — a captured parameter would stay EditorRect.FULL forever and
    // every drag would compute from a stale base.
    val currentCrop by rememberUpdatedState(cropRect)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.matchParentSize()) {
            val left = cropRect.left * size.width
            val top = cropRect.top * size.height
            val right = cropRect.right * size.width
            val bottom = cropRect.bottom * size.height
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, size = Size(size.width, top))
            drawRect(dim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
            drawRect(dim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
            drawRect(dim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        listOf(
            Triple(CropHandle.TOP_LEFT, cropRect.left, cropRect.top),
            Triple(CropHandle.TOP_RIGHT, cropRect.right, cropRect.top),
            Triple(CropHandle.BOTTOM_LEFT, cropRect.left, cropRect.bottom),
            Triple(CropHandle.BOTTOM_RIGHT, cropRect.right, cropRect.bottom),
        ).forEach { (handle, anchorX, anchorY) ->
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (anchorX * boxWidthPx - 22.dp.toPx()).roundToInt(),
                            (anchorY * boxHeightPx - 22.dp.toPx()).roundToInt(),
                        )
                    }
                    .size(44.dp)
                    .pointerInput(handle, boxWidthPx, boxHeightPx) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onCropChange(
                                resizeCropRect(
                                    rect = currentCrop,
                                    handle = handle,
                                    dx = drag.x / boxWidthPx,
                                    dy = drag.y / boxHeightPx,
                                    minSize = MIN_CROP_FRACTION,
                                ),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun FilterStrip(
    source: Bitmap,
    selected: CaptureFilter,
    onSelect: (CaptureFilter) -> Unit,
) {
    val thumbnail = remember(source) {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = 96f / longest
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
    DisposableEffect(thumbnail) {
        onDispose { if (thumbnail !== source) thumbnail.recycle() }
    }
    val thumbnailImage = remember(thumbnail) { thumbnail.asImageBitmap() }
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(CAPTURE_FILTERS, key = { it.id }) { filter ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(filter) },
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .border(
                            width = 2.dp,
                            color = if (filter == selected) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                Color.White.copy(alpha = 0.25f)
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(2.dp),
                ) {
                    Image(
                        bitmap = thumbnailImage,
                        contentDescription = filter.label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        colorFilter = filter.composeColorFilter(),
                    )
                }
                Text(
                    filter.label,
                    color = if (filter == selected) Color.White else Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun CaptionSendRow(
    caption: String,
    onCaption: (String) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.weight(1f),
        ) {
            TextField(
                value = caption,
                onValueChange = { onCaption(it.take(512)) },
                placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.6f)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(50.dp)
                .background(MaterialTheme.colorScheme.secondary, CircleShape)
                .clickable(enabled = sendEnabled, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Send",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun VideoDraftEditor(
    file: File,
    busy: Boolean,
    onDiscard: () -> Unit,
    onSend: (CaptureSendSpec) -> Unit,
) {
    val durationMillis = remember(file) { ChatVideoTranscoder.durationMillis(file).coerceAtLeast(1L) }
    var range by remember(file) { mutableStateOf(0f..durationMillis.toFloat()) }
    // Microphone permission may have been refused, in which case the clip has no audio track
    // and the sound toggle must not promise sound the recipient will never hear.
    val soundAvailable = remember(file) { ChatVideoTranscoder.hasAudioTrack(file) }
    var muted by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    // The trim-start frame IS the recipient's poster, so previewing it keeps the editor honest.
    val poster by produceState<ImageBitmap?>(initialValue = null, file, range.start.toLong() / 250L) {
        value = withContext(Dispatchers.IO) {
            ChatVideoTranscoder.posterFrame(file, range.start.toLong(), 1_280)?.let { bytes ->
                try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }
    val trimmedMillis = (range.endInclusive - range.start).toLong()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDiscard, enabled = !busy) {
                Icon(Icons.Rounded.Close, contentDescription = "Discard capture", tint = Color.White)
            }
            IconButton(onClick = { muted = !muted }, enabled = soundAvailable) {
                Icon(
                    if (muted || !soundAvailable) {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    contentDescription = when {
                        !soundAvailable -> "No sound was recorded"
                        muted -> "Sound off"
                        else -> "Sound on"
                    },
                    tint = if (soundAvailable) Color.White else Color.White.copy(alpha = 0.5f),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frame = poster
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "Video cover frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatRecordingClock(range.start.toLong()),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "${formatRecordingClock(trimmedMillis)} clip",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    formatRecordingClock(range.endInclusive.toLong()),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = 0f..durationMillis.toFloat(),
            )
        }
        CaptionSendRow(
            caption = caption,
            onCaption = { caption = it },
            sendEnabled = !busy && trimmedMillis >= MIN_CLIP_MILLIS,
            onSend = {
                onSend(
                    CaptureSendSpec.Video(
                        startMillis = range.start.toLong(),
                        endMillis = range.endInclusive.toLong(),
                        muted = muted,
                        caption = clampCaptionToWire(caption),
                    ),
                )
            },
        )
    }
}
