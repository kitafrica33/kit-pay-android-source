package com.kit.wallet.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kit.wallet.ui.model.AccountVerification
import com.kit.wallet.ui.theme.KitTheme

/**
 * The public blue seal granted by the backend's structured account-verification designation.
 *
 * No fallback is drawn: a missing or unrecognised designation remains visibly unbadged, and KYC,
 * names, status text, or avatars can never create this mark.
 */
@Composable
fun AccountVerificationBadge(
    verification: AccountVerification?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    if (verification == null) return
    Icon(
        imageVector = Icons.Rounded.Verified,
        contentDescription = verification.designation.contentDescription,
        tint = KitTheme.colors.verifiedBadge,
        modifier = modifier.size(size),
    )
}
