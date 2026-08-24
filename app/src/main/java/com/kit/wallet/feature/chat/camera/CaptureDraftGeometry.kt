package com.kit.wallet.feature.chat.camera

/**
 * Pure geometry for the capture editor. Overlay positions and sizes are stored in normalized
 * image coordinates (0..1 of the working bitmap), so the on-screen preview and the baked pixels
 * share a single uniform mapping and the recipient sees exactly what the sender saw. Rotation
 * and crop are destructive to the working bitmap, so existing overlays are re-mapped through the
 * same transform with these helpers.
 */
internal data class EditorPoint(val x: Float, val y: Float)

internal data class EditorRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(point: EditorPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    companion object {
        val FULL = EditorRect(0f, 0f, 1f, 1f)
    }
}

/** Where a normalized point lands after the image rotates 90° clockwise. */
internal fun rotateQuarterClockwise(point: EditorPoint): EditorPoint =
    EditorPoint(x = 1f - point.y, y = point.x)

/**
 * A size stored as a fraction of image width keeps its pixel size across a 90° rotation only if
 * it is rescaled by the pre-rotation aspect ratio (width / height), because the rotated image's
 * width is the old height.
 */
internal fun widthFractionAfterQuarterRotation(fraction: Float, aspectRatio: Float): Float =
    fraction * aspectRatio

/** Where a normalized point lands inside the cropped image's own normalized space. */
internal fun mapThroughCrop(point: EditorPoint, crop: EditorRect): EditorPoint {
    val width = crop.width.takeIf { it > 0f } ?: return point
    val height = crop.height.takeIf { it > 0f } ?: return point
    return EditorPoint(
        x = (point.x - crop.left) / width,
        y = (point.y - crop.top) / height,
    )
}

/** A width-fraction keeps its pixel size across a crop by growing with the removed width. */
internal fun widthFractionAfterCrop(fraction: Float, crop: EditorRect): Float =
    if (crop.width > 0f) fraction / crop.width else fraction

internal enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Moves one crop corner by a normalized drag, clamped to the image bounds and to [minSize] on
 * both axes so the crop window can never invert or collapse.
 */
internal fun resizeCropRect(
    rect: EditorRect,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    minSize: Float,
): EditorRect {
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom
    when (handle) {
        CropHandle.TOP_LEFT -> {
            left = (left + dx).coerceIn(0f, right - minSize)
            top = (top + dy).coerceIn(0f, bottom - minSize)
        }
        CropHandle.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + minSize, 1f)
            top = (top + dy).coerceIn(0f, bottom - minSize)
        }
        CropHandle.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0f, right - minSize)
            bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        }
        CropHandle.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + minSize, 1f)
            bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        }
    }
    return EditorRect(left, top, right, bottom)
}

/** The kit-media-v1 caption bound; clamping never splits a surrogate pair. */
internal fun clampCaptionToWire(text: String, maxUtf8Bytes: Int = 2_048): String? {
    var candidate = text.trim().take(maxUtf8Bytes)
    while (candidate.isNotEmpty() && candidate.toByteArray(Charsets.UTF_8).size > maxUtf8Bytes) {
        candidate = candidate.dropLast(1)
        if (candidate.isNotEmpty() && candidate.last().isHighSurrogate()) {
            candidate = candidate.dropLast(1)
        }
    }
    return candidate.takeIf(String::isNotBlank)
}
