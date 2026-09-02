package com.kit.wallet.data.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.kit.wallet.MainActivity
import com.kit.wallet.R
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Handles the direct-reply action on a secure-message notification, so the user can answer from
 * the notification shade without opening the app. The reply is sent through the same end-to-end
 * encrypted path as any message; the runtime independently validates that the target conversation
 * belongs to the current authenticated session and fails closed otherwise. The receiver is not
 * exported, so only Kit Pay's own notification action can trigger it.
 */
@AndroidEntryPoint
class MessageReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var chatRepo: ChatRepository

    @Inject lateinit var sessions: SessionStore

    @Inject lateinit var messagingLifecycle: SecureMessagingSessionLifecycle

    @Inject internal lateinit var notificationLedger: SecureMessageNotificationLedger

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val sessionEpoch = intent.getStringExtra(EXTRA_SESSION_EPOCH)
        val clientMessageId = intent.getStringExtra(EXTRA_CLIENT_MESSAGE_ID)
        val request = MessageReplyPolicy.request(
            conversationId = conversationId,
            expectedSessionEpoch = sessionEpoch,
            clientMessageId = clientMessageId,
            text = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_REPLY)
                ?.toString(),
        )
        val expectedSourceMessageDigest = intent.getStringExtra(EXTRA_SOURCE_MESSAGE_DIGEST)
        val notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (
            notificationTag == null ||
            notificationId != SECURE_MESSAGE_NOTIFICATION_ID ||
            secureMessageConversationNotificationTag(conversationId) != notificationTag
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // End SystemUI's RemoteInput progress state before any suspend point. The row now says
        // only that delivery is not yet confirmed and retains Reply. If the process is killed or
        // this coroutine is cancelled, the user is left with a truthful, actionable surface.
        runCatching {
            prepareReplyRecoveryNotification(
                context = context,
                manager = manager,
                ledger = notificationLedger,
                conversationId = conversationId,
                sessionEpoch = sessionEpoch,
                clientMessageId = clientMessageId,
                expectedSourceMessageDigest = expectedSourceMessageDigest,
                notificationTag = notificationTag,
                notificationId = notificationId,
            )
        }
        val pending = goAsync()
        applicationScope.launch {
            val delivered = request != null && deliverMessageReply(
                request = request,
                sessionFences = sessions.session.map { it?.fence() },
                stateAvailable = messagingLifecycle.stateAvailable,
                currentSession = { sessions.current()?.fence() },
            ) { owner, reply ->
                chatRepo.captureNotificationReplyForOwner(
                    owner = owner,
                    chatId = reply.conversationId,
                    text = reply.text,
                    clientMessageId = reply.clientMessageId,
                )
            }
            if (delivered) {
                synchronized(SECURE_MESSAGE_NOTIFICATION_MUTATION_LOCK) {
                    val active = runCatching {
                        manager.activeNotifications.singleOrNull {
                            it.tag == notificationTag && it.id == notificationId
                        }
                    }
                    val activeDigest = replyNotificationSourceDigest(
                        activeQuerySucceeded = active.isSuccess,
                        activeSourceMessageDigest = active.getOrNull()
                            ?.notification
                            ?.extras
                            ?.getString(EXTRA_SECURE_MESSAGE_DIGEST),
                        recordedSourceMessageDigest = notificationLedger.currentDigest(
                            notificationTag,
                        ),
                    )
                    if (
                        MessageReplyPolicy.notificationAction(
                            delivered = true,
                            expectedSourceMessageDigest = expectedSourceMessageDigest,
                            activeSourceMessageDigest = activeDigest,
                        ) == MessageReplyNotificationAction.CANCEL &&
                        runCatching {
                            manager.cancel(notificationTag, notificationId)
                        }.isSuccess
                    ) {
                        notificationLedger.remove(notificationTag)
                    }
                }
            }
        }.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_REPLY = "com.kit.wallet.action.REPLY_MESSAGE"
        const val KEY_REPLY = "kit_reply_text"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_SESSION_EPOCH = "session_epoch"
        const val EXTRA_CLIENT_MESSAGE_ID = "client_message_id"
        const val EXTRA_SOURCE_MESSAGE_DIGEST = "source_message_digest"
        const val EXTRA_NOTIFICATION_TAG = "notification_tag"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun replyIntent(
            context: Context,
            conversationId: String,
            sessionEpoch: String,
            clientMessageId: String,
            sourceMessageDigest: String,
            notificationTag: String,
            notificationId: Int,
        ): Intent = Intent(context, MessageReplyReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_SESSION_EPOCH, sessionEpoch)
            .putExtra(EXTRA_CLIENT_MESSAGE_ID, clientMessageId)
            .putExtra(EXTRA_SOURCE_MESSAGE_DIGEST, sourceMessageDigest)
            .putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
}

private fun prepareReplyRecoveryNotification(
    context: Context,
    manager: NotificationManager,
    ledger: SecureMessageNotificationLedger,
    conversationId: String,
    sessionEpoch: String?,
    clientMessageId: String?,
    expectedSourceMessageDigest: String?,
    notificationTag: String,
    notificationId: Int,
): Boolean = synchronized(SECURE_MESSAGE_NOTIFICATION_MUTATION_LOCK) {
    val active = runCatching {
        manager.activeNotifications.singleOrNull {
            it.tag == notificationTag && it.id == notificationId
        }
    }
    val activeDigest = replyNotificationSourceDigest(
        activeQuerySucceeded = active.isSuccess,
        activeSourceMessageDigest = active.getOrNull()
            ?.notification
            ?.extras
            ?.getString(EXTRA_SECURE_MESSAGE_DIGEST),
        recordedSourceMessageDigest = ledger.currentDigest(notificationTag),
    )
    if (
        MessageReplyPolicy.notificationAction(
            delivered = false,
            expectedSourceMessageDigest = expectedSourceMessageDigest,
            activeSourceMessageDigest = activeDigest,
        ) != MessageReplyNotificationAction.SHOW_FAILURE
    ) {
        return@synchronized false
    }
    val recovery = active.getOrNull()?.notification?.let { source ->
        replyNotConfirmedNotification(context, source)
    } ?: genericReplyNotConfirmedNotification(
        context = context,
        conversationId = conversationId,
        sessionEpoch = sessionEpoch,
        clientMessageId = clientMessageId,
        sourceMessageDigest = checkNotNull(expectedSourceMessageDigest),
        notificationTag = notificationTag,
        notificationId = notificationId,
    )
    recovery != null && runCatching {
        manager.notify(notificationTag, notificationId, recovery)
    }.isSuccess
}

internal const val MESSAGE_REPLY_NOT_CONFIRMED_COPY =
    "Reply not confirmed. Tap Reply to try again."

/**
 * Reposts the exact source row to end Android's inline-reply progress state. Recovery preserves
 * its open and Reply actions; stripping every remote-input extra ensures typed text is never
 * echoed into the notification when durable capture could not be confirmed.
 */
private fun replyNotConfirmedNotification(
    context: Context,
    source: Notification,
): Notification {
    val sanitizedExtras = Bundle(source.extras).apply {
        remove(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        remove(Notification.EXTRA_REMOTE_INPUT_DRAFT)
        remove(REMOTE_INPUT_HISTORY_ITEMS_EXTRA)
        remove(REMOTE_INPUT_SPINNER_EXTRA)
    }
    return NotificationCompat.Builder(context, source)
        .setExtras(sanitizedExtras)
        .setRemoteInputHistory(null)
        .setContentText(MESSAGE_REPLY_NOT_CONFIRMED_COPY)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()
}

/** Generic privacy-preserving fallback when an OEM refuses the active-notification query. */
private fun genericReplyNotConfirmedNotification(
    context: Context,
    conversationId: String,
    sessionEpoch: String?,
    clientMessageId: String?,
    sourceMessageDigest: String,
    notificationTag: String,
    notificationId: Int,
): Notification? {
    val route = MessageReplyPolicy.request(
        conversationId = conversationId,
        expectedSessionEpoch = sessionEpoch,
        clientMessageId = clientMessageId,
        text = "retry",
    ) ?: return null
    val retry = PendingIntent.getBroadcast(
        context,
        notificationTag.hashCode(),
        MessageReplyReceiver.replyIntent(
            context = context,
            conversationId = route.conversationId,
            sessionEpoch = route.expectedSessionEpoch,
            clientMessageId = route.clientMessageId,
            sourceMessageDigest = sourceMessageDigest,
            notificationTag = notificationTag,
            notificationId = notificationId,
        ).setData(
            Uri.Builder()
                .scheme("kitpay-internal")
                .authority("secure-message-reply-recovery")
                .appendPath(sourceMessageDigest)
                .build(),
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    val replyAction = NotificationCompat.Action.Builder(
        R.drawable.ic_kit_mark,
        "Reply",
        retry,
    )
        .addRemoteInput(
            RemoteInput.Builder(MessageReplyReceiver.KEY_REPLY)
                .setLabel("Reply securely")
                .build(),
        )
        .setAllowGeneratedReplies(false)
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        .build()
    val openApp = PendingIntent.getActivity(
        context,
        notificationTag.hashCode(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val publicVersion = NotificationCompat.Builder(
        context,
        SECURE_MESSAGE_NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_kit_mark)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText("New secure message")
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    return NotificationCompat.Builder(context, SECURE_MESSAGE_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_kit_mark)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(MESSAGE_REPLY_NOT_CONFIRMED_COPY)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setAutoCancel(true)
        .setContentIntent(openApp)
        .addAction(replyAction)
        .addExtras(Bundle().apply {
            putString(EXTRA_SECURE_MESSAGE_DIGEST, sourceMessageDigest)
        })
        .build()
}

private const val REMOTE_INPUT_HISTORY_ITEMS_EXTRA = "android.remoteInputHistoryItems"
private const val REMOTE_INPUT_SPINNER_EXTRA = "android.remoteInputSpinner"
