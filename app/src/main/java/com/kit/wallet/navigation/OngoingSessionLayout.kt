package com.kit.wallet.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Reserves the cutout/status area once when persistent call or playback controls are visible. */
@Composable
internal fun OngoingSessionLayout(
    hasBars: Boolean,
    bars: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    safeInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    content: @Composable () -> Unit,
) {
    Column(if (hasBars) modifier.windowInsetsPadding(safeInsets) else modifier) {
        if (hasBars) bars()
        content()
    }
}
