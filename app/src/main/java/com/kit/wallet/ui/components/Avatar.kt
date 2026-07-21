package com.kit.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kit.wallet.ui.theme.KitTheme
import kotlin.math.abs

private val AvatarPalette = listOf(
    Color(0xFF1D4166) to Color(0xFFD6E4F2), // navy
    Color(0xFF127A52) to Color(0xFFD6F4E8), // green
    Color(0xFF7A4EA3) to Color(0xFFEDE2F8), // violet
    Color(0xFFA34E6B) to Color(0xFFF8E2EA), // rose
    Color(0xFF9A6B1F) to Color(0xFFF8EDD4), // amber
    Color(0xFF2B6F8A) to Color(0xFFDCF0F8), // teal
)

fun initialsOf(name: String): String =
    name.split(" ").filter { it.isNotBlank() && it.first().isLetter() }
        .take(2).map { it.first().uppercaseChar() }.joinToString("")
        .ifEmpty { "•" }

/**
 * Deterministic initials avatar. Same name always yields the same color pair,
 * so lists feel stable across sessions.
 */
@Composable
fun KitAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    online: Boolean = false,
) {
    val (fg, bg) = AvatarPalette[abs(name.hashCode()) % AvatarPalette.size]
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsOf(name),
                color = fg,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (online) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .background(KitTheme.colors.success, CircleShape),
            )
        }
    }
}
