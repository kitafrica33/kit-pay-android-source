package com.kit.wallet.data.notifications

/** The app-owned notification surface for a verified, unexpired incoming call push. */
internal enum class IncomingCallNotificationSurface {
    FULL_SCREEN_RING,
    CALL_WAITING,
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
    )
}
