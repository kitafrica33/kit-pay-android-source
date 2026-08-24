package com.kit.wallet.feature.chat.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

/**
 * A colour-grade applied to a captured still. Every filter is a plain 4x5 colour matrix so the
 * same coefficients drive the live preview tile and the pixels that are actually encoded and
 * sent — the preview can never promise a look the recipient will not receive.
 */
internal data class CaptureFilter(
    val id: String,
    val label: String,
    val coefficients: FloatArray?,
) {
    val isOriginal: Boolean get() = coefficients == null

    /** Compose-side filter for previewing the grade without touching the source bitmap. */
    fun composeColorFilter(): ColorFilter? =
        coefficients?.let { ColorFilter.colorMatrix(ComposeColorMatrix(it.copyOf())) }

    override fun equals(other: Any?): Boolean = other is CaptureFilter && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

private fun saturation(amount: Float): FloatArray =
    ColorMatrix().apply { setSaturation(amount) }.array

private fun grade(
    redScale: Float = 1f,
    greenScale: Float = 1f,
    blueScale: Float = 1f,
    redShift: Float = 0f,
    greenShift: Float = 0f,
    blueShift: Float = 0f,
    saturation: Float = 1f,
): FloatArray {
    val matrix = ColorMatrix().apply { setSaturation(saturation) }
    matrix.postConcat(
        ColorMatrix(
            floatArrayOf(
                redScale, 0f, 0f, 0f, redShift,
                0f, greenScale, 0f, 0f, greenShift,
                0f, 0f, blueScale, 0f, blueShift,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    return matrix.array
}

internal val CAPTURE_FILTERS: List<CaptureFilter> = listOf(
    CaptureFilter("original", "Original", null),
    CaptureFilter("vivid", "Vivid", grade(saturation = 1.45f, redScale = 1.05f, blueScale = 1.03f)),
    CaptureFilter("kampala", "Kampala", grade(saturation = 1.2f, redScale = 1.12f, greenScale = 1.02f, blueScale = 0.9f, redShift = 6f)),
    CaptureFilter("cool", "Cool", grade(saturation = 1.05f, redScale = 0.92f, blueScale = 1.15f, blueShift = 8f)),
    CaptureFilter("bright", "Bright", grade(redShift = 22f, greenShift = 22f, blueShift = 22f, saturation = 1.1f)),
    CaptureFilter("fade", "Fade", grade(saturation = 0.72f, redShift = 26f, greenShift = 24f, blueShift = 20f)),
    CaptureFilter("mono", "Mono", saturation(0f)),
    CaptureFilter("noir", "Noir", grade(saturation = 0f, redScale = 1.25f, greenScale = 1.25f, blueScale = 1.25f, redShift = -28f, greenShift = -28f, blueShift = -28f)),
    CaptureFilter("sepia", "Sepia", grade(saturation = 0f, redScale = 1.15f, greenScale = 1.0f, blueScale = 0.8f, redShift = 18f, greenShift = 6f)),
    CaptureFilter("dusk", "Dusk", grade(saturation = 0.9f, redScale = 1.08f, greenScale = 0.95f, blueScale = 1.1f, blueShift = 12f)),
)

internal val DEFAULT_CAPTURE_FILTER: CaptureFilter = CAPTURE_FILTERS.first()

/**
 * Bakes [filter] into a new bitmap. The source is left untouched so the editor can re-grade from
 * the original at any time without accumulating rounding error.
 */
internal fun applyCaptureFilter(source: Bitmap, filter: CaptureFilter): Bitmap {
    val coefficients = filter.coefficients ?: return source
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(coefficients.copyOf()))
    }
    Canvas(output).drawBitmap(source, 0f, 0f, paint)
    return output
}
