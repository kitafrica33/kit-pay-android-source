package com.kit.wallet.data.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
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

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val request = MessageReplyPolicy.request(
            conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
            expectedSessionEpoch = intent.getStringExtra(EXTRA_SESSION_EPOCH),
            clientMessageId = intent.getStringExtra(EXTRA_CLIENT_MESSAGE_ID),
            text = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_REPLY)
                ?.toString(),
        ) ?: return
        val notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val manager = context.getSystemService(NotificationManager::class.java)
        val pending = goAsync()
        applicationScope.launch {
            try {
                val delivered = deliverMessageReply(
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
                if (delivered && notificationTag != null && notificationId != -1) {
                    manager?.cancel(notificationTag, notificationId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.kit.wallet.action.REPLY_MESSAGE"
        const val KEY_REPLY = "kit_reply_text"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_SESSION_EPOCH = "session_epoch"
        const val EXTRA_CLIENT_MESSAGE_ID = "client_message_id"
        const val EXTRA_NOTIFICATION_TAG = "notification_tag"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun replyIntent(
            context: Context,
            conversationId: String,
            sessionEpoch: String,
            clientMessageId: String,
            notificationTag: String,
            notificationId: Int,
        ): Intent = Intent(context, MessageReplyReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_SESSION_EPOCH, sessionEpoch)
            .putExtra(EXTRA_CLIENT_MESSAGE_ID, clientMessageId)
            .putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
}
