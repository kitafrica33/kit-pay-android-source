package com.kit.wallet.data.notifications

import android.app.NotificationManager

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
