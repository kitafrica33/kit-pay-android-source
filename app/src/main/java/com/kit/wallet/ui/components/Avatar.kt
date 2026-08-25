package com.kit.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kit.wallet.data.media.ProfileAvatarImages
import com.kit.wallet.data.media.isTrustedProfileAvatarUrl
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kit.wallet.ui.theme.KitTheme

private val AvatarPalette = listOf(
    Color(0xFF1D4166) to Color(0xFFD6E4F2), // navy
    Color(0xFF127A52) to Color(0xFFD6F4E8), // green
    Color(0xFF7A4EA3) to Color(0xFFEDE2F8), // violet
    Color(0xFFA34E6B) to Color(0xFFF8E2EA), // rose
    Color(0xFF9A6B1F) to Color(0xFFF8EDD4), // amber
    Color(0xFF2B6F8A) to Color(0xFFDCF0F8), // teal
)

/**
 * Which palette entry a name gets. Non-negative for every possible hash code, including the one
 * `abs` cannot make positive.
 */
private fun paletteIndexOf(name: String): Int = name.hashCode().mod(AvatarPalette.size)

/**
 * The colour a name is written in when it labels something next to, rather than inside, an avatar
 * — a group author above their message, say — so the label and the avatar read as one person.
 *
 * The ink half of the pair is meant for pale surfaces and the tint half for dark ones; picking by
 * the surface's own luminance keeps the label legible whichever theme is in force, including a
 * theme forced against the system setting.
 */
@Composable
fun kitNameAccent(name: String): Color {
    val (ink, tint) = AvatarPalette[paletteIndexOf(name)]
    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) tint else ink
}

fun initialsOf(name: String): String =
    name.split(" ").filter { it.isNotBlank() && it.first().isLetter() }
        .take(2).map { it.first().uppercaseChar() }.joinToString("")
        .ifEmpty { "•" }

/**
 * Deterministic initials avatar. Same name always yields the same color pair,
 * so lists feel stable across sessions.
 *
 * When the person has a photo it is layered over the initials, which is what lets the avatar be
 * correct at every moment: the initials are right immediately, the photo replaces them when it
 * arrives, and if it never arrives — offline, first run, a photo since removed — the initials were
 * never a placeholder that had to be taken away.
 */
@Composable
fun KitAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    online: Boolean = false,
    avatarUrl: String? = null,
) {
    val (fg, bg) = AvatarPalette[paletteIndexOf(name)]
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Initials render first and stay visible until the moderated photo loads, so lists
            // never flash empty circles offline or while the image is fetched.
            Text(
                text = initialsOf(name),
                color = fg,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.SemiBold,
            )
            KitAvatarPhoto(avatarUrl = avatarUrl, size = size)
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

/**
 * The photo half of an avatar, wherever one is drawn.
 *
 * Nothing is requested until this composes, so a contact list only downloads the faces that
 * actually come into view, and it downloads each at the size it will be drawn rather than at
 * whatever the server happened to store. Once fetched, the photo is served from the app's own
 * profile-photo store — see [ProfileAvatarImages] — which means the second sighting, and every
 * sighting after a restart or with no network at all, costs nothing.
 */
@Composable
fun KitAvatarPhoto(
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    // A URL pointing anywhere but the Kit Pay API is not fetched at all: see
    // isTrustedProfileAvatarUrl. The initials underneath are already a complete avatar.
    if (!isTrustedProfileAvatarUrl(avatarUrl)) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixels = remember(size, density) { with(density) { size.roundToPx() } }
    val request = remember(avatarUrl, pixels) {
        ImageRequest.Builder(context)
            .data(avatarUrl)
            .size(pixels)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(shape),
    )
}
