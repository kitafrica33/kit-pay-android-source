package com.kit.wallet.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import com.kit.wallet.MainActivity
import com.kit.wallet.R
import com.kit.wallet.data.messaging.ACTION_OPEN_AUTHORIZED_SECURE_MESSAGE
import com.kit.wallet.data.messaging.EXTRA_SECURE_MESSAGE_AUTHORIZATION
import com.kit.wallet.data.messaging.SecureMessageNavigationAuthorizer
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotification
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotificationSink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Displays content only after the encrypted event processor supplies authenticated durable text.
 * The server push remains an opaque wake-up and can neither choose notification copy nor routing.
 */
@Singleton
internal class AuthenticatedMessageNotificationSink @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authorizer: SecureMessageNavigationAuthorizer,
) : SecureMessagingIncomingNotificationSink {
    override fun publish(notification: SecureMessagingIncomingNotification) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Secure messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "End-to-end encrypted Kit Pay messages"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                // Always alert with the Kit Pay message tone and vibration for new messages.
                setSound(
                    "android.resource://${context.packageName}/${R.raw.msg_received}".toUri(),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
            },
        )
        val authorization = authorizer.issue(
            conversationId = notification.conversationId,
            sessionEpoch = notification.sessionEpoch,
        )
        val openApp = PendingIntent.getActivity(
            context,
            notification.messageId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_AUTHORIZED_SECURE_MESSAGE)
                // PendingIntent identity ignores extras. This opaque local-only URI prevents two
                // notifications (including hash collisions) from sharing one PendingIntent.
                .setData(
                    Uri.Builder()
                        .scheme("kitpay-internal")
                        .authority("secure-message")
                        .appendPath(authorization)
                        .build(),
                )
                .putExtra(EXTRA_SECURE_MESSAGE_AUTHORIZATION, authorization)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationTag = "$NOTIFICATION_TAG_PREFIX${notification.messageId}"
        // Direct reply from the shade. The MUTABLE PendingIntent lets the system inject the typed
        // text; the receiver sends it through the encrypted path and the runtime re-validates the
        // conversation against the current session.
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notification.messageId.hashCode(),
            MessageReplyReceiver.replyIntent(
                context = context,
                conversationId = notification.conversationId,
                notificationTag = notificationTag,
                notificationId = NOTIFICATION_ID,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_kit_mark,
            "Reply",
            replyPendingIntent,
        )
            .addRemoteInput(
                RemoteInput.Builder(MessageReplyReceiver.KEY_REPLY)
                    .setLabel("Reply securely")
                    .build(),
            )
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kit_mark)
            .setContentTitle("New secure message")
            .setContentText("Open Kit Pay to read it.")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kit_mark)
            .setContentTitle("New secure message")
            .setContentText(PRIVATE_COPY)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(replyAction)
            .build()
        // A stable tag makes sync replay an idempotent replacement rather than a duplicate alert.
        manager.notify(
            notificationTag,
            NOTIFICATION_ID,
            built,
        )
    }

    override fun cancelAll() {
        authorizer.revokeAll()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { notification ->
                notification.id == NOTIFICATION_ID &&
                    notification.tag?.startsWith(NOTIFICATION_TAG_PREFIX) == true
            }
            .forEach { notification -> manager.cancel(notification.tag, notification.id) }
    }

    private companion object {
        // Notification-channel sound/vibration is immutable once created, so bumping the id is
        // required for the explicit alert settings to take effect on upgrades.
        const val CHANNEL_ID = "kit_secure_messages_v2"
        const val NOTIFICATION_ID = 4_201
        const val NOTIFICATION_TAG_PREFIX = "kit_secure_message:"
        const val PRIVATE_COPY = "Open Kit Pay to read it."
    }
}
