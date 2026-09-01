package com.kit.wallet.feature.calls

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kit.wallet.data.notifications.callAlertsBlocked

/**
 * The in-app warning for the one alert tier the notification system cannot explain about itself:
 * when Android will not render Kit Pay notifications at all, an incoming call rings nowhere, and
 * the "Enable call alerts" action that normally rides on the call notification can never appear.
 * So the warning lives on the communication surfaces instead, and is re-proved on every return
 * to the foreground — the person may have just fixed, or just broken, the permission in Settings.
 */
@Composable
internal fun CallAlertBlockedWarning(modifier: Modifier = Modifier) {
    // Previews render with a resumed fake lifecycle and a context whose notification service is
    // absent, which would read as "blocked" and paint the warning into every static preview of
    // the surfaces that host it. Inspection is not a phone that cannot ring.
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        blocked = callAlertsBlocked(
            postNotificationsGranted = context.postNotificationsGranted(),
            appNotificationsEnabled = context.appNotificationsEnabled(),
        )
        onPauseOrDispose { }
    }
    if (!blocked) return
    CallAlertBlockedCard(
        onRecovered = {
            blocked = callAlertsBlocked(
                postNotificationsGranted = context.postNotificationsGranted(),
                appNotificationsEnabled = context.appNotificationsEnabled(),
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun CallAlertBlockedCard(onRecovered: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Registered only while the warning is showing, so screens hosting the banner do not pay
    // for activity-result plumbing they are not using.
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onRecovered() }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Calls cannot ring on this phone",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Android is not letting Kit Pay show notifications, so an incoming call " +
                    "cannot alert you. Kit Pay calls only ring while alerts are on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = {
                    when (
                        callAlertRecovery(
                            postNotificationsGranted = context.postNotificationsGranted(),
                            promptAvailable = context.canPromptForPostNotifications(),
                        )
                    ) {
                        CallAlertRecovery.REQUEST_PERMISSION ->
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        CallAlertRecovery.OPEN_NOTIFICATION_SETTINGS -> runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }
                },
            ) {
                Text(
                    "Turn on call alerts",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

internal enum class CallAlertRecovery { REQUEST_PERMISSION, OPEN_NOTIFICATION_SETTINGS }

/**
 * How the warning's button recovers alerting. The system prompt is only worth launching while
 * Android would still show it; every other shape of "off" — a spent or never-askable permission,
 * or the app-level notification toggle — can only be undone from the notification settings
 * screen, so the button must lead there rather than fire a prompt that returns denied unshown.
 */
internal fun callAlertRecovery(
    postNotificationsGranted: Boolean,
    promptAvailable: Boolean,
): CallAlertRecovery = if (!postNotificationsGranted && promptAvailable) {
    CallAlertRecovery.REQUEST_PERMISSION
} else {
    CallAlertRecovery.OPEN_NOTIFICATION_SETTINGS
}

private fun Context.postNotificationsGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.appNotificationsEnabled(): Boolean =
    getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false

/** Whether the system would still put the notification permission prompt in front of the user. */
private fun Context.canPromptForPostNotifications(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
