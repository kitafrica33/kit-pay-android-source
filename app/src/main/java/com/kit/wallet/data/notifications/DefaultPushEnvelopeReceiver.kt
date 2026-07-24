package com.kit.wallet.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.kit.wallet.MainActivity
import com.kit.wallet.R
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.resolveCallPresentation
import com.kit.wallet.feature.calls.KitTelecomBridge
import com.kit.wallet.feature.calls.KitTelecomDisconnect
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
    private val activeCallState: ActiveCallStateHolder,
    private val incomingCallRelay: IncomingCallRelay,
    private val contacts: ContactRepository,
    private val telecom: KitTelecomBridge,
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
            when (lifecycleEvent.kind) {
                CallLifecycleKind.ANSWERED -> telecom.markConnecting(lifecycleEvent.callId)
                CallLifecycleKind.DECLINED -> if (lifecycleEvent.terminal) {
                    telecom.finish(lifecycleEvent.callId, KitTelecomDisconnect.REJECTED)
                }
                CallLifecycleKind.ENDED ->
                    telecom.finish(lifecycleEvent.callId, KitTelecomDisconnect.REMOTE)
                CallLifecycleKind.MISSED ->
                    telecom.finish(lifecycleEvent.callId, KitTelecomDisconnect.MISSED)
            }
            return
        }

        val incomingCall = IncomingCallPayload.fromData(envelope.data)
        if (incomingCall != null) {
            val presentation = resolveCallPresentation(
                serverName = incomingCall.callerName,
                participantUserIds = listOfNotNull(incomingCall.callerUserId),
                contacts = contacts.contacts.value,
            )
            val presentedCall = incomingCall.copy(callerName = presentation.name)
            showIncomingCall(manager, envelope, presentedCall, presentation.phone)
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
        phone: String?,
    ) {
        val expiresAt = call.ringExpiresAt ?: return
        val timeoutMillis = runCatching {
            Duration.between(Instant.now(clock), Instant.parse(expiresAt)).toMillis()
        }.getOrNull()?.coerceAtMost(MAX_RING_TIMEOUT_MILLIS) ?: return
        if (timeoutMillis <= 0) return

        val activeCallId = activeCallState.activeCallId.value
        val deliveryPlan = incomingCallDeliveryPlan(activeCallId, call.callId)

        // Telecom tracking is common to both surfaces so call-waiting calls participate in audio
        // arbitration and can reach system Recents. The plan keeps that lifecycle registration
        // separate from the app-owned notification, which remains quiet for call waiting.
        if (deliveryPlan.trackWithTelecom) {
            telecom.trackIncoming(
                callId = call.callId,
                name = call.callerName,
                phone = phone,
                video = call.video,
            )
        }

        if (deliveryPlan.notificationSurface == IncomingCallNotificationSurface.CALL_WAITING) {
            if (deliveryPlan.relayToActiveCall) incomingCallRelay.publish(call)
            showCallWaitingNotification(manager, envelope, call, timeoutMillis)
            return
        }

        val ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_RINGTONE,
        ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        manager.createNotificationChannel(
            NotificationChannel(
                CALLS_CHANNEL_ID,
                "Incoming Kit Pay calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Rings for incoming Kit Pay voice and video calls"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 700L, 900L, 700L, 900L)
            },
        )
        val openCall = PendingIntent.getActivity(
            context,
            call.callId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse(call.deepLinkUri()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Answering from the banner opens the verified call screen and accepts once validated;
        // declining is handled entirely in the background so the status bar stays usable.
        val answerCall = PendingIntent.getActivity(
            context,
            call.callId.hashCode() + 1,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse(call.deepLinkUri(accept = true)))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declineCall = PendingIntent.getBroadcast(
            context,
            call.callId.hashCode() + 2,
            CallActionReceiver.declineIntent(context, call.callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = androidx.core.app.Person.Builder()
            .setName(call.callerName)
            .setImportant(true)
            .build()
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
                // The system call banner offers Answer and Decline directly from the status bar.
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineCall, answerCall))
                .addPerson(caller)
                .setOngoing(true)
                .setTimeoutAfter(timeoutMillis)
                .setContentIntent(openCall)
                // Surface the ringing call full-screen on a locked/backgrounded device. When the
                // OS declines the full-screen intent it falls back to a ringing heads-up alert.
                .setFullScreenIntent(openCall, true)
                .build(),
        )
    }

    /**
     * A call-waiting call arriving during another call: a quiet heads-up alert (no full-screen
     * takeover) that opens the active call screen, which shows the in-app waiting banner and plays
     * the call-waiting tone. Decline is still offered from the shade.
     */
    private fun showCallWaitingNotification(
        manager: NotificationManager,
        envelope: PushEnvelope,
        call: IncomingCallPayload,
        timeoutMillis: Long,
    ) {
        manager.createNotificationChannel(
            NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Kit Pay alerts",
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
        val declineCall = PendingIntent.getBroadcast(
            context,
            call.callId.hashCode() + 2,
            CallActionReceiver.declineIntent(context, call.callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            callTag(call.callId),
            CALL_NOTIFICATION_ID,
            NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle("Call waiting")
                .setContentText("${call.callerName} is calling.")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(timeoutMillis)
                .setContentIntent(openCall)
                .addAction(0, "Decline", declineCall)
                .build(),
        )
    }

    private companion object {
        const val ALERTS_CHANNEL_ID = "kit_wallet_alerts"
        // Bumped from "kit_incoming_calls": notification-channel sound and vibration are immutable
        // once created, so a new id is required for the ringtone settings to apply on upgrades.
        const val CALLS_CHANNEL_ID = "kit_incoming_calls_v2"
        const val CALL_NOTIFICATION_ID = 4_101
        const val MAX_RING_TIMEOUT_MILLIS = 60_000L

        fun callTag(callId: String) = "kit_call:$callId"
    }
}
