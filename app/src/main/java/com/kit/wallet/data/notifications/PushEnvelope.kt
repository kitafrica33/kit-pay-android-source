package com.kit.wallet.data.notifications

data class PushNotificationContent(
    val title: String?,
    val body: String?,
)

enum class PushDeliveryPriority { HIGH, NORMAL, UNKNOWN }

/** Provider-neutral message delivered by the active push transport adapter. */
data class PushEnvelope(
    val data: Map<String, String>,
    val notification: PushNotificationContent? = null,
    val messageId: String? = null,
    /** Effective FCM priority and the priority originally requested by the server. */
    val deliveredPriority: PushDeliveryPriority = PushDeliveryPriority.UNKNOWN,
    val originalPriority: PushDeliveryPriority = PushDeliveryPriority.UNKNOWN,
    /**
     * True only when the adapter observed a complete raw envelope, found no provider analytics
     * metadata and confirmed that the provider supplied no display-notification payload.
     */
    val opaqueWakeVerified: Boolean = false,
) {
    val priorityWasDowngraded: Boolean
        get() = originalPriority == PushDeliveryPriority.HIGH &&
            deliveredPriority != PushDeliveryPriority.HIGH
}

interface PushEnvelopeReceiver {
    fun receive(envelope: PushEnvelope)
}

internal fun PushEnvelope.isVerifiedMessagingWake(): Boolean =
    opaqueWakeVerified && MessagingWakePayload.matches(data)
