package com.kit.wallet.data.notifications

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

internal data class IncomingCallNotificationAccess(
    val postNotificationsGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val channelImportance: Int,
    val channelHasSound: Boolean,
    val fullScreenIntentAllowed: Boolean,
)

internal enum class IncomingCallAlertMode {
    FULL_SCREEN,
    HEADS_UP,
    PASSIVE,
    BLOCKED,
}

internal data class IncomingCallAlertPlan(
    val mode: IncomingCallAlertMode,
    val showSettingsAction: Boolean,
) {
    val useFullScreenIntent: Boolean get() = mode == IncomingCallAlertMode.FULL_SCREEN
}

internal fun incomingCallAlertPlan(access: IncomingCallNotificationAccess): IncomingCallAlertPlan {
    if (!access.postNotificationsGranted || !access.appNotificationsEnabled) {
        return IncomingCallAlertPlan(IncomingCallAlertMode.BLOCKED, showSettingsAction = true)
    }
    if (
        access.channelImportance < NotificationManager.IMPORTANCE_HIGH
    ) {
        return IncomingCallAlertPlan(IncomingCallAlertMode.PASSIVE, showSettingsAction = true)
    }
    return if (access.fullScreenIntentAllowed) {
        IncomingCallAlertPlan(IncomingCallAlertMode.FULL_SCREEN, showSettingsAction = false)
    } else {
        IncomingCallAlertPlan(IncomingCallAlertMode.HEADS_UP, showSettingsAction = true)
    }
}

/** Current user-controlled Android gates, safe to evaluate whenever a foreground screen resumes. */
internal fun incomingCallNotificationAccess(context: Context): IncomingCallNotificationAccess {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.getNotificationChannel(INCOMING_CALLS_CHANNEL_ID)
    } else {
        null
    }
    return IncomingCallNotificationAccess(
        postNotificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        appNotificationsEnabled = manager.areNotificationsEnabled(),
        // A missing channel will be created at HIGH before the first notification.
        channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_HIGH,
        channelHasSound = channel?.sound != null || channel == null,
        fullScreenIntentAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false),
    )
}

internal fun incomingCallAlertSettingsIntent(
    context: Context,
    access: IncomingCallNotificationAccess,
): Intent {
    val fullScreenAccessIsOnlyMissingGate =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            access.postNotificationsGranted &&
            access.appNotificationsEnabled &&
            access.channelImportance >= NotificationManager.IMPORTANCE_HIGH &&
            !access.fullScreenIntentAllowed
    return when {
        !access.postNotificationsGranted || !access.appNotificationsEnabled ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        fullScreenAccessIsOnlyMissingGate ->
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:${context.packageName}"))
        else ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, INCOMING_CALLS_CHANNEL_ID)
    }
}

internal fun IncomingCallAlertPlan.foregroundReadinessCopy(): String = when (mode) {
    IncomingCallAlertMode.FULL_SCREEN ->
        "Ready to ring and appear over the lock screen"
    IncomingCallAlertMode.HEADS_UP ->
        "Heads-up alerts only · allow full-screen call alerts"
    IncomingCallAlertMode.PASSIVE ->
        "Call alerts are quiet or minimized · review notification settings"
    IncomingCallAlertMode.BLOCKED ->
        "Call notifications are off · incoming calls may be missed"
}

internal const val INCOMING_CALLS_CHANNEL_ID = "kit_incoming_calls_v2"

/** PII-free native diagnostics for OEM/FCM/permission failures that cannot be reproduced in UI. */
internal object IncomingCallDiagnostics {
    private const val TAG = "KitIncomingCall"

    fun pushReceived(context: Context, envelope: PushEnvelope) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        Log.i(
            TAG,
            "push_received delivered_priority=${envelope.deliveredPriority.name} " +
                "original_priority=${envelope.originalPriority.name} " +
                "priority_downgraded=${envelope.priorityWasDowngraded} " +
                "keyguard_locked=${keyguard.isKeyguardLocked} " +
                "device_idle=${power.isDeviceIdleMode} interactive=${power.isInteractive}",
        )
    }

    fun alertEvaluated(
        access: IncomingCallNotificationAccess,
        plan: IncomingCallAlertPlan,
    ) {
        Log.i(
            TAG,
            "alert_evaluated mode=${plan.mode.name} " +
                "post_permission=${access.postNotificationsGranted} " +
                "app_notifications=${access.appNotificationsEnabled} " +
                "channel_importance=${access.channelImportance} " +
                "channel_sound=${access.channelHasSound} " +
                "full_screen_access=${access.fullScreenIntentAllowed}",
        )
    }

    fun notificationPublished(mode: IncomingCallAlertMode, accepted: Boolean) {
        Log.i(TAG, "notification_published mode=${mode.name} manager_accepted=$accepted")
    }

    fun telecomRegistration(stage: String, successful: Boolean) {
        Log.i(TAG, "telecom_registration stage=$stage successful=$successful")
    }
}
