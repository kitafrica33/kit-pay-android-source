package com.kit.wallet.data.notifications

/** The app-owned notification surface for a verified, unexpired incoming call push. */
internal enum class IncomingCallNotificationSurface {
    FULL_SCREEN_RING,
    CALL_WAITING,
}

/** Where a notification tap must take the existing task. */
internal enum class IncomingCallNotificationTarget {
    INCOMING_CALL,
    ACTIVE_CALL,
}

/** Active-call navigation intentionally has no new deep link; the existing task stays in place. */
internal fun IncomingCallNotificationTarget.deepLink(
    call: IncomingCallPayload,
    accept: Boolean = false,
): String? = when (this) {
    IncomingCallNotificationTarget.INCOMING_CALL -> call.deepLinkUri(accept)
    IncomingCallNotificationTarget.ACTIVE_CALL -> null
}

/**
 * Keeps Telecom lifecycle tracking independent from the notification surface. Call-waiting calls
 * still need a Telecom connection for audio arbitration and system Recents, but only the primary
 * incoming call may use the app's full-screen ringing notification.
 */
internal data class IncomingCallDeliveryPlan(
    val trackWithTelecom: Boolean,
    val relayToActiveCall: Boolean,
    val notificationSurface: IncomingCallNotificationSurface,
    val notificationTarget: IncomingCallNotificationTarget,
)

internal fun incomingCallDeliveryPlan(
    activeCallId: String?,
    incomingCallId: String,
): IncomingCallDeliveryPlan {
    val isCallWaiting = activeCallId != null && activeCallId != incomingCallId
    return IncomingCallDeliveryPlan(
        trackWithTelecom = true,
        relayToActiveCall = isCallWaiting,
        notificationSurface = if (isCallWaiting) {
            IncomingCallNotificationSurface.CALL_WAITING
        } else {
            IncomingCallNotificationSurface.FULL_SCREEN_RING
        },
        notificationTarget = if (isCallWaiting) {
            IncomingCallNotificationTarget.ACTIVE_CALL
        } else {
            IncomingCallNotificationTarget.INCOMING_CALL
        },
    )
}
