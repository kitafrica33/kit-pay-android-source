package com.kit.wallet.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.kit.wallet.MainActivity
import com.kit.wallet.R
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPushEnvelopeReceiver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val callEvents: CallLifecycleEventBus,
    private val messagingSync: SecureMessagingSyncScheduler,
    private val clock: Clock,
) : PushEnvelopeReceiver {
    override fun receive(envelope: PushEnvelope) {
        val messagingData = envelope.data
        if (MessagingWakePayload.isCandidate(messagingData)) {
            // Secure-message pushes are opaque data-only wake-ups. A malformed, decorated or
            // provider-analytics-marked wake is discarded and never reaches an alert renderer.
            if (envelope.isVerifiedMessagingWake()) {
                messagingSync.schedule()
            }
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val lifecycleEvent = CallLifecycleEvent.fromData(envelope.data)
        if (lifecycleEvent != null) {
            callEvents.publish(envelope.data)
            manager.cancel(callTag(lifecycleEvent.callId), CALL_NOTIFICATION_ID)
            return
        }

        val incomingCall = IncomingCallPayload.fromData(envelope.data)
        if (incomingCall != null) {
            showIncomingCall(manager, envelope, incomingCall)
            return
        }

        val notification = envelope.notification ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Kit Pay alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val openApp = PendingIntent.getActivity(
            context,
            envelope.data["notification_id"]?.hashCode() ?: envelope.messageId?.hashCode() ?: 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            envelope.data["notification_id"]?.hashCode() ?: envelope.messageId?.hashCode() ?: 0,
            NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle(notification.title ?: context.getString(R.string.app_name))
                .setContentText(notification.body.orEmpty())
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build(),
        )
    }

    private fun showIncomingCall(
        manager: NotificationManager,
        envelope: PushEnvelope,
        call: IncomingCallPayload,
    ) {
        val expiresAt = call.ringExpiresAt ?: return
        val timeoutMillis = runCatching {
            Duration.between(Instant.now(clock), Instant.parse(expiresAt)).toMillis()
        }.getOrNull()?.coerceAtMost(MAX_RING_TIMEOUT_MILLIS) ?: return
        if (timeoutMillis <= 0) return

        manager.createNotificationChannel(
            NotificationChannel(
                CALLS_CHANNEL_ID,
                "Incoming Kit Pay calls",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val openCall = PendingIntent.getActivity(
            context,
            call.callId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse(call.deepLinkUri()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            callTag(call.callId),
            CALL_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle(
                    envelope.notification?.title
                        ?: if (call.video) "Incoming video call" else "Incoming voice call",
                )
                .setContentText(envelope.notification?.body ?: "${call.callerName} is calling.")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(timeoutMillis)
                .setContentIntent(openCall)
                .build(),
        )
    }

    private companion object {
        const val ALERTS_CHANNEL_ID = "kit_wallet_alerts"
        const val CALLS_CHANNEL_ID = "kit_incoming_calls"
        const val CALL_NOTIFICATION_ID = 4_101
        const val MAX_RING_TIMEOUT_MILLIS = 60_000L

        fun callTag(callId: String) = "kit_call:$callId"
    }
}
