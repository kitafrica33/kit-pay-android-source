package com.kit.wallet.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
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
import java.time.Instant
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
    private val publicationLock = Any()

    init {
        // Upgrade cleanup cannot reconstruct a conversation identity from code 22's message-only
        // tags. Remove those rows as soon as this singleton starts so they cannot consume all 50
        // package slots before the first code 23 message or incoming call arrives.
        synchronized(publicationLock) {
            val manager = context.getSystemService(NotificationManager::class.java)
            cancelLegacyNotifications(manager, manager.activeNotifications.toList())
        }
    }

    override fun publish(notification: SecureMessagingIncomingNotification) =
        synchronized(publicationLock) {
            publishLocked(notification)
        }

    private fun publishLocked(notification: SecureMessagingIncomingNotification) {
        val presentation = SecureMessageNotificationPresentationFactory.create(notification)
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.toList()
        // Code 23 replaces the old one-notification-per-message identity. Remove those legacy
        // rows before publishing so an upgrade immediately recovers package quota headroom.
        cancelLegacyNotifications(manager, active)

        val notificationTag = secureMessageConversationNotificationTag(
            notification.conversationId,
        )
        val messageDigest = secureMessageIdentifierDigest(notification.messageId)
        val quotaPlan = planSecureMessageNotificationPublication(
            active = active.mapNotNull { status ->
                val tag = status.tag ?: return@mapNotNull null
                if (
                    status.id != SECURE_MESSAGE_NOTIFICATION_ID ||
                    !tag.startsWith(SECURE_MESSAGE_CONVERSATION_TAG_PREFIX)
                ) {
                    return@mapNotNull null
                }
                val displayedAt = status.notification.`when`
                    .takeIf { it > 0L } ?: status.postTime
                val fallbackSentAt = Instant.ofEpochMilli(displayedAt)
                val extras = status.notification.extras
                val exactNano = extras.getInt(EXTRA_SECURE_MESSAGE_SENT_AT_NANO, -1)
                val hasExactTime = extras.containsKey(
                    EXTRA_SECURE_MESSAGE_SENT_AT_EPOCH_SECOND,
                ) && exactNano in 0..999_999_999
                ActiveSecureMessageNotification(
                    tag = tag,
                    messageDigest = extras.getString(EXTRA_SECURE_MESSAGE_DIGEST),
                    sentAtEpochSecond = if (hasExactTime) {
                        extras.getLong(EXTRA_SECURE_MESSAGE_SENT_AT_EPOCH_SECOND)
                    } else {
                        fallbackSentAt.epochSecond
                    },
                    sentAtNano = if (hasExactTime) exactNano else fallbackSentAt.nano,
                    postedAtEpochMillis = status.postTime,
                )
            },
            targetTag = notificationTag,
            incomingMessageDigest = messageDigest,
            incomingSentAtEpochSecond = notification.sentAt.epochSecond,
            incomingSentAtNano = notification.sentAt.nano,
        )
        quotaPlan.tagsToCancel.forEach { tag ->
            manager.cancel(tag, SECURE_MESSAGE_NOTIFICATION_ID)
        }
        // A recovered history item must never replace a newer active preview for this direct
        // conversation. Returning normally also lets the durable publisher mark it handled.
        if (!quotaPlan.shouldPublish) return

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
            notificationTag.hashCode(),
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
        // Direct reply from the shade. The MUTABLE PendingIntent lets the system inject the typed
        // text; the receiver sends it through the encrypted path and the runtime re-validates the
        // conversation against the current session.
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationTag.hashCode(),
            MessageReplyReceiver.replyIntent(
                context = context,
                conversationId = notification.conversationId,
                notificationTag = notificationTag,
                notificationId = NOTIFICATION_ID,
            ).setData(
                Uri.Builder()
                    .scheme("kitpay-internal")
                    .authority("secure-message-reply")
                    .appendPath(authorization)
                    .build(),
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
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(PUBLIC_COPY)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kit_mark)
            .setContentTitle(presentation.sender)
            .setContentText(presentation.preview)
            .setWhen(notification.sentAt.toEpochMilli())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // An ambiguous retry of the same durable message stays quiet. A genuinely newer
            // message in this conversation replaces the row and still sounds/vibrates normally.
            .setOnlyAlertOnce(quotaPlan.onlyAlertOnce)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(replyAction)
            .addExtras(
                Bundle().apply {
                    putString(EXTRA_SECURE_MESSAGE_DIGEST, messageDigest)
                    putLong(
                        EXTRA_SECURE_MESSAGE_SENT_AT_EPOCH_SECOND,
                        notification.sentAt.epochSecond,
                    )
                    putInt(EXTRA_SECURE_MESSAGE_SENT_AT_NANO, notification.sentAt.nano)
                },
            )
            .build()
        // A stable per-conversation tag preserves only the latest sender/preview and keeps direct
        // reply/tap routing attached to the exact notification currently visible to the user.
        manager.notify(
            notificationTag,
            SECURE_MESSAGE_NOTIFICATION_ID,
            built,
        )
    }

    private fun cancelLegacyNotifications(
        manager: NotificationManager,
        active: List<android.service.notification.StatusBarNotification>,
    ) {
        active.filter { status ->
            status.id == SECURE_MESSAGE_NOTIFICATION_ID &&
                status.tag?.startsWith(LEGACY_SECURE_MESSAGE_TAG_PREFIX) == true
        }.forEach { status -> manager.cancel(status.tag, status.id) }
    }

    override fun cancelAll() = synchronized(publicationLock) {
        authorizer.revokeAll()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { notification ->
                notification.id == SECURE_MESSAGE_NOTIFICATION_ID &&
                    (
                        notification.tag?.startsWith(
                            SECURE_MESSAGE_CONVERSATION_TAG_PREFIX,
                        ) == true ||
                            notification.tag?.startsWith(
                                LEGACY_SECURE_MESSAGE_TAG_PREFIX,
                            ) == true
                        )
            }
            .forEach { notification -> manager.cancel(notification.tag, notification.id) }
    }

    private companion object {
        // Notification-channel sound/vibration is immutable once created, so bumping the id is
        // required for the explicit alert settings to take effect on upgrades.
        const val CHANNEL_ID = "kit_secure_messages_v2"
        const val NOTIFICATION_ID = SECURE_MESSAGE_NOTIFICATION_ID
        const val PUBLIC_COPY = "New secure message"
    }
}
