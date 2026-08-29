package com.kit.wallet.feature.support

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kit.wallet.ui.theme.KitTheme

/**
 * The blue verified check worn only by official Kit Pay support.
 *
 * Callers pass the server-derived verification flag; when it is false nothing
 * is drawn at all — there is no unverified variant, no grey fallback, nothing
 * an unverified sender could wear that resembles it. The blue is deliberately
 * not the green KYC family (a claim about the customer, not the speaker) and
 * not the read-receipt sky blue.
 */
@Composable
fun SupportVerifiedBadge(
    verified: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    if (!verified) return
    Icon(
        Icons.Rounded.Verified,
        contentDescription = "Verified Kit Pay support",
        tint = KitTheme.colors.verifiedBadge,
        modifier = modifier.size(size),
    )
}

/**
 * The persistent privacy notice on every support surface: support threads are
 * read by Kit Pay staff. Constant copy from the negotiated contract — never
 * derived from per-ticket flags, and support never renders E2EE iconography.
 */
@Composable
fun ServerReadableNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = KitTheme.colors.verifiedBadgeContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Visibility,
                contentDescription = null,
                tint = KitTheme.colors.onVerifiedBadgeContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Kit Pay support staff can read this conversation. " +
                    "It is not end-to-end encrypted like your chats.",
                style = MaterialTheme.typography.bodySmall,
                color = KitTheme.colors.onVerifiedBadgeContainer,
            )
        }
    }
}

/**
 * The support avatar: the ticket-scoped agent photo when the server provided
 * one this session (memory-only bytes — see SupportRepository.agentAvatar),
 * otherwise a locally drawn support glyph. Never a customer-style photo from
 * any other source.
 */
@Composable
fun SupportAvatar(
    photo: ImageBitmap?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier
                .size(size)
                .background(KitTheme.colors.verifiedBadgeContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.SupportAgent,
                contentDescription = null,
                tint = KitTheme.colors.onVerifiedBadgeContainer,
                modifier = Modifier.fillMaxSize(0.6f),
            )
        }
    }
}
