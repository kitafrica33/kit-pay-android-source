package com.kit.wallet.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kit.wallet.ui.model.AccountVerification
import com.kit.wallet.ui.theme.KitTheme

private const val VERIFICATION_BADGE_INLINE_ID = "account-verification-badge"

/**
 * The public blue seal granted by the backend's structured account-verification designation.
 *
 * No fallback is drawn: a missing or unrecognised designation remains visibly unbadged, and KYC,
 * names, status text, or avatars can never create this mark.
 */
@Composable
private fun AccountVerificationBadge(
    verification: AccountVerification,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    Icon(
        imageVector = Icons.Rounded.Verified,
        contentDescription = verification.designation.contentDescription,
        tint = KitTheme.colors.verifiedBadge,
        modifier = modifier.size(size),
    )
}

/**
 * A displayed account name and, when the server grants one, its blue verification seal.
 *
 * The seal is inline content rather than decoration on the nearby profile photo. Keeping the two
 * in one text layout makes the mark follow the name through wrapping, truncation, and every avatar
 * size while ensuring an image or initials can never wear it.
 */
@Composable
fun VerifiedAccountName(
    name: String,
    verification: AccountVerification?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    badgeSize: Dp = 16.dp,
) {
    val text = buildAnnotatedString {
        append(name)
        if (verification != null) {
            append('\u00A0')
            appendInlineContent(
                id = VERIFICATION_BADGE_INLINE_ID,
                alternateText = verification.designation.contentDescription,
            )
        }
    }
    val inlineContent = if (verification == null) {
        emptyMap()
    } else {
        val placeholderSize = with(LocalDensity.current) { badgeSize.toSp() }
        mapOf(
            VERIFICATION_BADGE_INLINE_ID to InlineTextContent(
                placeholder = Placeholder(
                    width = placeholderSize,
                    height = placeholderSize,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                AccountVerificationBadge(verification = verification, size = badgeSize)
            },
        )
    }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        inlineContent = inlineContent,
        style = style,
    )
}
