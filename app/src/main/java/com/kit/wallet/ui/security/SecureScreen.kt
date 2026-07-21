package com.kit.wallet.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Prevents screenshots and recent-app previews while short-lived credentials are visible. */
@Composable
fun SecureScreen() {
    val view = LocalView.current

    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val wasSecure = window?.let {
            it.attributes.flags.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        } ?: false
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        onDispose {
            if (!wasSecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
