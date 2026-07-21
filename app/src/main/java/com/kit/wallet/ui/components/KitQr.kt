package com.kit.wallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.kit.wallet.R
import kotlin.random.Random

/**
 * Decorative QR placeholder rendered on-device. The real payload comes from
 * the wallet API (dynamic/static QR endpoints); this keeps the receive/scan
 * flows visually complete in the UI-only milestone. The seed makes the
 * pattern stable per user/amount so previews don't flicker.
 */
@Composable
fun KitQrCode(
    seed: String,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White, MaterialTheme.shapes.large)
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fg = Color(0xFF122D46)
        Canvas(Modifier.aspectRatio(1f)) {
            val n = 29
            val cell = size.minDimension / n
            val rnd = Random(seed.hashCode())

            fun finder(cx: Int, cy: Int) {
                drawRoundRect(
                    color = fg,
                    topLeft = Offset(cx * cell, cy * cell),
                    size = Size(cell * 7, cell * 7),
                    cornerRadius = CornerRadius(cell * 2f),
                    style = Stroke(width = cell),
                )
                drawRoundRect(
                    color = fg,
                    topLeft = Offset((cx + 2) * cell, (cy + 2) * cell),
                    size = Size(cell * 3, cell * 3),
                    cornerRadius = CornerRadius(cell * 1f),
                )
            }

            for (x in 0 until n) {
                for (y in 0 until n) {
                    val inFinder = (x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9)
                    val inCenter = x in (n / 2 - 4)..(n / 2 + 4) && y in (n / 2 - 4)..(n / 2 + 4)
                    if (!inFinder && !inCenter && rnd.nextFloat() < 0.42f) {
                        drawRoundRect(
                            color = fg,
                            topLeft = Offset(x * cell + cell * 0.1f, y * cell + cell * 0.1f),
                            size = Size(cell * 0.8f, cell * 0.8f),
                            cornerRadius = CornerRadius(cell * 0.25f),
                        )
                    }
                }
            }
            finder(0, 0)
            finder(n - 7, 0)
            finder(0, n - 7)
        }
        // Kit mark centered, as on branded payment QRs
        Image(
            painter = painterResource(R.drawable.ic_kit_mark),
            contentDescription = null,
            modifier = Modifier.size(44.dp),
        )
    }
}
