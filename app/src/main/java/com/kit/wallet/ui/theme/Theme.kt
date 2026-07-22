package com.kit.wallet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = KitNavy,
    onPrimary = KitWhite,
    primaryContainer = KitNavy100,
    onPrimaryContainer = KitNavy800,
    inversePrimary = KitNavy200,
    secondary = KitGreen600,
    onSecondary = KitWhite,
    secondaryContainer = KitGreen100,
    onSecondaryContainer = KitGreen900,
    tertiary = KitNavy500,
    onTertiary = KitWhite,
    tertiaryContainer = KitNavy050,
    onTertiaryContainer = KitNavy700,
    background = KitPaper,
    onBackground = KitInk,
    surface = KitWhite,
    onSurface = KitInk,
    surfaceVariant = KitFog,
    onSurfaceVariant = KitSlate,
    surfaceContainerLowest = KitWhite,
    surfaceContainerLow = KitPaper,
    surfaceContainer = KitFog,
    surfaceContainerHigh = Color(0xFFEAEEF3),
    surfaceContainerHighest = KitCloud,
    inverseSurface = KitNavy800,
    inverseOnSurface = KitFog,
    outline = KitMist,
    outlineVariant = KitCloud,
    error = KitError,
    onError = KitWhite,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = KitNavy200,
    onPrimary = KitNavy900,
    primaryContainer = KitNavy600,
    onPrimaryContainer = KitNavy100,
    inversePrimary = KitNavy600,
    secondary = KitGreen400,
    onSecondary = KitGreen900,
    secondaryContainer = KitGreen800,
    onSecondaryContainer = KitGreen200,
    tertiary = KitNavy300,
    onTertiary = KitNavy900,
    tertiaryContainer = KitNavy600,
    onTertiaryContainer = KitNavy100,
    background = KitNavy900,
    onBackground = KitFog,
    surface = KitNavy800,
    onSurface = KitFog,
    surfaceVariant = KitNavy700,
    onSurfaceVariant = KitNavy200,
    surfaceContainerLowest = KitNavy900,
    surfaceContainerLow = KitNavy800,
    surfaceContainer = Color(0xFF10263C),
    surfaceContainerHigh = KitNavy700,
    surfaceContainerHighest = KitNavy600,
    inverseSurface = KitFog,
    inverseOnSurface = KitNavy800,
    outline = KitNavy400,
    outlineVariant = KitNavy600,
    error = KitErrorDark,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/**
 * Kit-specific semantic colors that Material 3 does not model.
 * Access via [KitTheme.colors].
 */
@Immutable
data class KitExtendedColors(
    val success: Color,
    val onSuccessContainer: Color,
    val successContainer: Color,
    val readReceipt: Color,
    val warning: Color,
    val warningContainer: Color,
    val moneyIn: Color,
    val moneyOut: Color,
    val chatBubbleMe: Color,
    val onChatBubbleMe: Color,
    val chatBubbleOther: Color,
    val onChatBubbleOther: Color,
    val balanceCardStart: Color,
    val balanceCardEnd: Color,
)

private val LightExtended = KitExtendedColors(
    success = KitSuccess,
    successContainer = KitGreen100,
    onSuccessContainer = KitGreen900,
    readReceipt = KitReadReceipt,
    warning = KitWarning,
    warningContainer = Color(0xFFFFEFD0),
    moneyIn = KitGreen600,
    moneyOut = KitInk,
    chatBubbleMe = KitGreen100,
    onChatBubbleMe = KitGreen900,
    chatBubbleOther = KitWhite,
    onChatBubbleOther = KitInk,
    balanceCardStart = KitNavy700,
    balanceCardEnd = KitNavy900,
)

private val DarkExtended = KitExtendedColors(
    success = KitSuccessDark,
    successContainer = KitGreen800,
    onSuccessContainer = KitGreen200,
    readReceipt = KitReadReceiptDark,
    warning = KitWarningDark,
    warningContainer = Color(0xFF5C3D00),
    moneyIn = KitGreen400,
    moneyOut = KitFog,
    chatBubbleMe = KitGreen800,
    onChatBubbleMe = KitGreen100,
    chatBubbleOther = KitNavy700,
    onChatBubbleOther = KitFog,
    balanceCardStart = KitNavy600,
    balanceCardEnd = KitNavy900,
)

private val LocalKitColors = staticCompositionLocalOf { LightExtended }

object KitTheme {
    val colors: KitExtendedColors
        @Composable get() = LocalKitColors.current
}

@Composable
fun KitWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) DarkExtended else LightExtended

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalKitColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KitTypography,
            shapes = KitShapes,
            content = content,
        )
    }
}
