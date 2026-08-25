package com.kit.wallet.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/**
 * Flat illustrations drawn on the device and handed to the conversation as though they were
 * decrypted photo payloads. The media screenshots therefore go through the real photo bubbles,
 * the real grouped grid and the real bounded decoder rather than a mocked-up image view.
 *
 * Nothing here depicts a real person, place or photograph: a store listing must not ship a
 * customer's picture, and an invented scene cannot be mistaken for one.
 */
internal object StoreScreenshotImages {

    /** The featured 16:9 tile of a three-photo group. */
    val lakeSunset: ByteArray by lazy { jpeg(1280, 720) { canvas, w, h -> drawLakeSunset(canvas, w, h) } }

    /** The two square tiles stacked beside it. */
    val greenHills: ByteArray by lazy { jpeg(900, 900) { canvas, w, h -> drawGreenHills(canvas, w, h) } }
    val cityDusk: ByteArray by lazy { jpeg(900, 900) { canvas, w, h -> drawCityDusk(canvas, w, h) } }

    private fun jpeg(width: Int, height: Int, draw: (Canvas, Int, Int) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), width, height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun fill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        canvas.drawRect(left, top, right, bottom, Paint().apply { this.color = color })
    }

    private fun gradient(
        canvas: Canvas,
        top: Float,
        bottom: Float,
        width: Int,
        colors: IntArray,
    ) {
        val paint = Paint().apply {
            shader = LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
    }

    private fun ridge(canvas: Canvas, color: Int, width: Int, points: List<Pair<Float, Float>>, base: Float) {
        val path = Path()
        path.moveTo(0f, points.first().second)
        points.forEach { (x, y) -> path.lineTo(x, y) }
        path.lineTo(width.toFloat(), base)
        path.lineTo(0f, base)
        path.close()
        canvas.drawPath(path, Paint().apply { this.color = color; isAntiAlias = true })
    }

    private fun drawLakeSunset(canvas: Canvas, w: Int, h: Int) {
        val horizon = h * 0.60f
        gradient(canvas, 0f, horizon, w, intArrayOf(0xFFE0603C.toInt(), 0xFFF29A4E.toInt(), 0xFFFBD489.toInt()))
        canvas.drawCircle(w * 0.66f, horizon - h * 0.16f, h * 0.12f, Paint().apply {
            color = 0xFFFFF1C0.toInt()
            isAntiAlias = true
        })
        ridge(
            canvas, 0xFF8A4A56.toInt(), w,
            listOf(
                0f to horizon - h * 0.06f,
                w * 0.22f to horizon - h * 0.13f,
                w * 0.41f to horizon - h * 0.04f,
                w * 0.63f to horizon - h * 0.11f,
                w * 0.85f to horizon - h * 0.03f,
                w.toFloat() to horizon - h * 0.08f,
            ),
            horizon,
        )
        gradient(canvas, horizon, h.toFloat(), w, intArrayOf(0xFFC96A50.toInt(), 0xFF7A4462.toInt(), 0xFF463253.toInt()))
        // The sun's reflection: broken bands of light widening towards the near shore.
        val glow = Paint().apply { color = 0x66FFE6AE; isAntiAlias = true }
        var y = horizon + h * 0.02f
        var band = 0
        while (y < h) {
            val spread = w * (0.03f + band * 0.012f)
            canvas.drawRoundRect(
                w * 0.66f - spread, y, w * 0.66f + spread, y + h * 0.018f,
                h * 0.01f, h * 0.01f, glow,
            )
            y += h * 0.055f
            band++
        }
    }

    private fun drawGreenHills(canvas: Canvas, w: Int, h: Int) {
        gradient(canvas, 0f, h * 0.62f, w, intArrayOf(0xFF9FD8EF.toInt(), 0xFFDCF1F8.toInt()))
        canvas.drawCircle(w * 0.78f, h * 0.16f, h * 0.075f, Paint().apply {
            color = 0xFFFFF3C9.toInt()
            isAntiAlias = true
        })
        val cloud = Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true }
        canvas.drawCircle(w * 0.22f, h * 0.20f, h * 0.055f, cloud)
        canvas.drawCircle(w * 0.31f, h * 0.22f, h * 0.042f, cloud)
        canvas.drawCircle(w * 0.14f, h * 0.23f, h * 0.038f, cloud)
        ridge(
            canvas, 0xFF7FB86A.toInt(), w,
            listOf(
                0f to h * 0.56f,
                w * 0.30f to h * 0.44f,
                w * 0.58f to h * 0.57f,
                w.toFloat() to h * 0.47f,
            ),
            h.toFloat(),
        )
        ridge(
            canvas, 0xFF569150.toInt(), w,
            listOf(
                0f to h * 0.70f,
                w * 0.26f to h * 0.62f,
                w * 0.62f to h * 0.72f,
                w.toFloat() to h * 0.64f,
            ),
            h.toFloat(),
        )
        ridge(
            canvas, 0xFF356B3A.toInt(), w,
            listOf(
                0f to h * 0.84f,
                w * 0.40f to h * 0.78f,
                w * 0.74f to h * 0.86f,
                w.toFloat() to h * 0.80f,
            ),
            h.toFloat(),
        )
    }

    private fun drawCityDusk(canvas: Canvas, w: Int, h: Int) {
        gradient(
            canvas, 0f, h * 0.70f, w,
            intArrayOf(0xFF2B2350.toInt(), 0xFF6B3A6C.toInt(), 0xFFD87A5C.toInt()),
        )
        canvas.drawCircle(w * 0.24f, h * 0.19f, h * 0.06f, Paint().apply {
            color = 0xFFF6E9C4.toInt()
            isAntiAlias = true
        })
        fill(canvas, 0f, h * 0.70f, w.toFloat(), h.toFloat(), 0xFF1B1733.toInt())
        // Blocks of skyline with a scattering of lit windows.
        val block = Paint().apply { color = 0xFF241E3F.toInt() }
        val window = Paint().apply { color = 0xFFF2C778.toInt() }
        val heights = listOf(0.30f, 0.44f, 0.22f, 0.52f, 0.36f, 0.26f, 0.46f)
        val slot = w / heights.size.toFloat()
        heights.forEachIndexed { index, factor ->
            val left = index * slot + slot * 0.08f
            val right = (index + 1) * slot - slot * 0.08f
            val top = h * 0.70f - h * factor
            canvas.drawRect(left, top, right, h * 0.72f, block)
            var wy = top + h * 0.035f
            var row = 0
            while (wy < h * 0.66f) {
                var wx = left + slot * 0.14f
                var column = 0
                while (wx < right - slot * 0.16f) {
                    if ((index + row + column) % 3 != 0) {
                        canvas.drawRect(wx, wy, wx + slot * 0.10f, wy + h * 0.016f, window)
                    }
                    wx += slot * 0.22f
                    column++
                }
                wy += h * 0.045f
                row++
            }
        }
    }
}
