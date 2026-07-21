package com.kit.wallet.data.notifications.fcm

import com.google.firebase.messaging.RemoteMessage
import com.kit.wallet.data.notifications.PushEnvelope
import com.kit.wallet.data.notifications.PushNotificationContent

internal object FcmPushEnvelopeMapper {
    fun map(message: RemoteMessage): PushEnvelope = map(
        data = message.data,
        rawEnvelopeKeys = message.toIntent().extras?.keySet(),
        notification = message.notification?.let { remoteNotification ->
            PushNotificationContent(
                title = remoteNotification.title,
                body = remoteNotification.body,
            )
        },
        messageId = message.messageId,
    )

    internal fun map(
        data: Map<String, String>,
        rawEnvelopeKeys: Set<String>?,
        notification: PushNotificationContent?,
        messageId: String?,
    ): PushEnvelope {
        val completeEnvelope = rawEnvelopeKeys != null &&
            data.keys.all(rawEnvelopeKeys::contains)
        val analyticsFree = rawEnvelopeKeys != null &&
            rawEnvelopeKeys.none { key -> key.startsWith(FCM_ANALYTICS_KEY_PREFIX) }
        return PushEnvelope(
            data = data,
            notification = notification,
            messageId = messageId,
            opaqueWakeVerified = notification == null && completeEnvelope && analyticsFree,
        )
    }

    private const val FCM_ANALYTICS_KEY_PREFIX = "google.c.a."
}
