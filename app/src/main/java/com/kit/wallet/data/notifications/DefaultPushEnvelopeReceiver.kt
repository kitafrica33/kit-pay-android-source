package com.kit.wallet.data.notifications

import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.kit.wallet.IncomingCallRelayActivity
import com.kit.wallet.MainActivity
import com.kit.wallet.R
import com.kit.wallet.feature.calls.KitTelecomBridge
import com.kit.wallet.feature.calls.KitTelecomDisconnect
import com.kit.wallet.data.realtime.KitForegroundSource
import com.kit.wallet.data.repository.MobileMoneyRepository
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import com.kit.wallet.worker.NotificationRecoveryScheduler
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPushEnvelopeReceiver @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val callEvents: CallLifecycleEventBus,
    private val activeCallState: ActiveCallStateHolder,
    private val incomingCallRelay: IncomingCallRelay,
    private val replayLedger: IncomingCallReplayLedger,
    private val telecom: KitTelecomBridge,
    private val ringDeadlines: CallRingDeadlineCoordinator,
    private val foregroundSource: KitForegroundSource,
    private val messagingSync: SecureMessagingSyncScheduler,
    private val mobileMoney: MobileMoneyRepository,
    private val elapsedRealtimeClock: ElapsedRealtimeClock,
    private val bootSessionIdProvider: BootSessionIdProvider,
    private val sessions: SessionStore,
    private val alertDelivery: NotificationAlertDelivery,
    private val notificationRecovery: NotificationRecoveryScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PushEnvelopeReceiver, NotificationInboxAlertSink {
    override fun receive(envelope: PushEnvelope) {
        val messagingData = envelope.data
        if (MessagingWakePayload.isCandidate(messagingData)) {
            // Secure-message pushes are opaque data-only wake-ups. A malformed, decorated or
            // provider-analytics-marked wake is discarded and never reaches an alert renderer.
            if (envelope.isVerifiedMessagingWake()) {
                // FCM's high-priority execution window is brief. The expedited lane performs the
                // authenticated pull/decrypt now, so the resulting locally-authenticated message
                // notification does not wait for an OEM's ordinary background-work cadence.
                messagingSync.scheduleUrgentMessageWake()
            }
            return
        }

        val owner = sessions.current()?.fence()
        if (!explicitPushRecipientMatches(envelope.data, owner)) return
        if (envelope.data.containsKey("recipient_user_id")) {
            // Explicitly owned lifecycle pushes must not retire another logged-in account's
            // ring while credential replacement races this FCM callback.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    sessions.withCurrentSession(checkNotNull(owner)) { receiveNonMessaging(envelope) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notificationRecovery.schedule()
                }
            }
        } else {
            receiveNonMessaging(envelope)
        }
    }

    private fun receiveNonMessaging(envelope: PushEnvelope) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val lifecycleEvent = CallLifecycleEvent.fromData(envelope.data)
        if (lifecycleEvent != null) {
            // Persist the tombstone before removing any local surface. A crash between these two
            // operations can only leave an old banner behind; it can never admit a late ring.
            if (lifecycleEvent.endsRingingSurface()) {
                replayLedger.retire(
                    lifecycleEvent.callId,
                    lifecycleEvent.ringingRetirementDisposition(),
                )
                incomingCallRelay.retire(lifecycleEvent.callId)
                ringDeadlines.cancel(lifecycleEvent.callId)
            }
            callEvents.publish(envelope.data)
            if (lifecycleEvent.endsRingingSurface()) {
                manager.cancel(callTag(lifecycleEvent.callId), CALL_NOTIFICATION_ID)
            }
            when (lifecycleEvent.kind) {
                CallLifecycleKind.ANSWERED ->
                    telecom.finishRingingAsAnsweredElsewhere(lifecycleEvent.callId)
                CallLifecycleKind.DECLINED -> if (lifecycleEvent.terminal) {
                    telecom.finish(lifecycleEvent.callId, KitTelecomDisconnect.REJECTED)
                }
                CallLifecycleKind.ENDED ->
                    telecom.finish(lifecycleEvent.callId, KitTelecomDisconnect.REMOTE)
                CallLifecycleKind.MISSED ->
                    telecom.finish(lifecycleEvent.callId, KitTelecomDisconnect.MISSED)
            }
            if (lifecycleEvent.kind == CallLifecycleKind.MISSED) enqueueAlert(envelope)
            return
        }

        val incomingCall = IncomingCallPayload.fromData(envelope.data)
        if (incomingCall != null) {
            IncomingCallDiagnostics.pushReceived(context, envelope)
            // Push display fields are hints, not identity evidence. Until GET /calls/{id}
            // succeeds, both the lock-screen alert and Telecom receive generic presentation.
            showIncomingCall(
                manager,
                incomingCall.copy(callerName = "Kit Pay contact"),
                phone = null,
            )
            return
        }

        enqueueAlert(envelope)
    }

    private fun enqueueAlert(envelope: PushEnvelope) {
        val owner = sessions.current()?.fence() ?: return
        if (!envelope.data.containsKey("recipient_user_id")) {
            // An older display payload carries no proof of which login owns its private copy.
            // Recover it through the authenticated inbox instead of attributing it to this login.
            notificationRecovery.schedule()
            return
        }
        // Start on FCM's existing execution window; a contended session lock may suspend. The
        // durable inbox/maintenance path recovers if Android kills this process afterward.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                recoverAlert(owner, envelope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notificationRecovery.schedule()
            }
        }
    }

    override suspend fun recoverAlert(owner: SessionFence, envelope: PushEnvelope): Boolean {
        val data = envelope.data
        if (MessagingWakePayload.isCandidate(data)) return true
        if (!explicitPushRecipientMatches(data, owner)) return true
        val missed = MissedCallAlert.fromData(data, owner)
        if (data["type"]?.startsWith("call.") == true && missed == null) return true
        val settlement = MobileMoneySettlementAlert.fromData(data)
        if (MobileMoneySettlementAlert.isCandidate(data) && settlement == null) return true
        val claimAlert = PaymentClaimAlert.fromData(envelope.data)
        val financialAlert = FinancialPaymentAlert.fromData(envelope.data)
        val notificationId = PaymentClaimAlert.canonicalUuid(data["notification_id"])
        val identity = missed?.identity ?: notificationId?.let { "notification:$it" } ?: return true
        if (missed == null && settlement == null && claimAlert == null && financialAlert == null &&
            envelope.notification == null
        ) return true
        val payment = settlement != null || claimAlert != null || financialAlert != null
        val channel = if (payment) PAYMENTS_CHANNEL_ID else ALERTS_CHANNEL_ID
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = missed?.tag ?: settlement?.notificationTag ?: claimAlert?.notificationTag
            ?: financialAlert?.notificationTag ?: checkNotNull(notificationId)
        val occurredAt = envelope.occurredAtEpochMillis ?: System.currentTimeMillis()
        var quota = RecoveredAlertQuotaPlan(true, emptyList())
        return alertDelivery.deliver(
            owner = owner,
            identity = identity,
            canDisplay = {
                quota = planRecoveredAlertQuota(
                    manager.activeNotifications.mapNotNull { status ->
                        val activeTag = status.tag ?: return@mapNotNull null
                        if (status.id != PAYMENT_NOTIFICATION_ID && status.id != MISSED_NOTIFICATION_ID &&
                            !(status.id == 0 && PaymentClaimAlert.canonicalUuid(activeTag) != null)
                        ) return@mapNotNull null
                        ActiveRecoveredAlert(activeTag, status.id, status.notification.`when`)
                    }, tag, occurredAt,
                )
                manager.areNotificationsEnabled() &&
                    manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE &&
                    quota.display
            },
            alreadyDisplayed = {
                // Ordinary FCM display messages are rendered automatically while backgrounded.
                // The backend uses their notification_id as tag, numeric id 0, matching below.
                manager.activeNotifications.any {
                    it.tag == tag && (!payment ||
                        (envelope.occurredAtEpochMillis != null && it.notification.`when` >= occurredAt))
                }
            },
            onAuthenticatedHint = {
                settlement?.let { mobileMoney.reconcileSettlementHint(it.operationId) }
            },
        ) {
            quota.cancel.forEach { manager.cancel(it.tag, it.id) }
            when {
                missed != null -> showMissedCall(manager, missed, occurredAt)
                settlement != null -> showMobileMoneySettlement(manager, envelope, settlement)
                claimAlert != null -> showPaymentClaim(manager, envelope, claimAlert)
                financialAlert != null -> showFinancialPayment(manager, envelope, financialAlert)
                else -> showGeneralAlert(manager, envelope, checkNotNull(notificationId))
            }
        }
    }

    private fun showGeneralAlert(manager: NotificationManager, envelope: PushEnvelope, notificationId: String) {
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
            notificationId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationId,
            0,
            NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle(notification.title ?: context.getString(R.string.app_name))
                .setContentText(notification.body.orEmpty())
                .setWhen(envelope.occurredAtEpochMillis ?: System.currentTimeMillis())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build(),
        )
    }

    private fun showMissedCall(manager: NotificationManager, alert: MissedCallAlert, occurredAt: Long) {
        manager.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, "Kit Pay alerts", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            alert.tag.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(CALL_HISTORY_NOTIFICATION_LINK))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            alert.tag,
            MISSED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle("Missed call")
                .setContentText("Open Kit Pay to view your calls.")
                .setWhen(occurredAt)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private fun showFinancialPayment(
        manager: NotificationManager,
        envelope: PushEnvelope,
        alert: FinancialPaymentAlert,
    ) {
        manager.createNotificationChannel(
            NotificationChannel(
                PAYMENTS_CHANNEL_ID,
                "Kit Pay payments",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE },
        )
        val open = PendingIntent.getActivity(
            context,
            alert.notificationTag.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                // Never trust the server-provided deep_link; reconstruct it from canonical IDs.
                .setData(Uri.parse(alert.deepLink()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            alert.notificationTag,
            PAYMENT_NOTIFICATION_ID,
            NotificationCompat.Builder(context, PAYMENTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle(envelope.notification?.title ?: context.getString(R.string.app_name))
                .setContentText(envelope.notification?.body ?: "Open Kit Pay to view this payment.")
                .setWhen(envelope.occurredAtEpochMillis ?: System.currentTimeMillis())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, PAYMENTS_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_kit_mark)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText("New payment activity")
                        .build(),
                )
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private fun showMobileMoneySettlement(
        manager: NotificationManager,
        envelope: PushEnvelope,
        alert: MobileMoneySettlementAlert,
    ) {
        manager.createNotificationChannel(
            NotificationChannel(
                PAYMENTS_CHANNEL_ID,
                "Kit Pay payments",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE },
        )
        val open = PendingIntent.getActivity(
            context,
            alert.notificationTag.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(alert.link().deepLinkUri()))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            alert.notificationTag,
            PAYMENT_NOTIFICATION_ID,
            NotificationCompat.Builder(context, PAYMENTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle(
                    alert.title
                        ?: envelope.notification?.title
                        ?: context.getString(R.string.app_name),
                )
                .setContentText(
                    alert.body
                        ?: envelope.notification?.body
                        ?: "Open Kit Pay to view this payment.",
                )
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, PAYMENTS_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_kit_mark)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText("New payment activity")
                        .build(),
                )
                .setOnlyAlertOnce(true)
                .setWhen(envelope.occurredAtEpochMillis ?: System.currentTimeMillis())
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    /**
     * A held-transfer alert on its own high-importance private channel.
     *
     * From Android 0.2.32 these arrive data-only, so without this branch they would fall through
     * to the visible-envelope path and be dropped. The content intent's data URI is reconstructed
     * from the validated claim id — the payload's own `deep_link` is never read — and the tap
     * still has to survive a fresh authoritative claim fetch before anything specific opens.
     * The server's per-claim tag is reused as the notification tag, so a reminder or duplicate
     * delivery replaces the claim's alert instead of stacking another.
     */
    private fun showPaymentClaim(
        manager: NotificationManager,
        envelope: PushEnvelope,
        alert: PaymentClaimAlert,
    ) {
        manager.createNotificationChannel(
            NotificationChannel(
                PAYMENTS_CHANNEL_ID,
                "Kit Pay payments",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Payments waiting on you and how they were settled"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
        val openClaim = PendingIntent.getActivity(
            context,
            alert.notificationTag.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(alert.claimLink().exactDeepLinkUri()))
                .apply {
                    alert.conversationId?.let {
                        putExtra(PaymentClaimAlert.EXTRA_CONVERSATION_HINT, it)
                    }
                    alert.groupPaymentId?.let {
                        putExtra(PaymentClaimAlert.EXTRA_GROUP_PAYMENT_HINT, it)
                    }
                }
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            alert.notificationTag,
            PAYMENT_NOTIFICATION_ID,
            NotificationCompat.Builder(context, PAYMENTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle(
                    alert.title
                        ?: envelope.notification?.title
                        ?: context.getString(R.string.app_name),
                )
                .setContentText(
                    alert.body
                        ?: envelope.notification?.body
                        ?: "Open Kit Pay to view this payment.",
                )
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                // Amounts and names stay off the lock screen; the redaction names no one.
                .setPublicVersion(
                    NotificationCompat.Builder(context, PAYMENTS_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_kit_mark)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText("New payment activity")
                        .build(),
                )
                .setAutoCancel(true)
                .setContentIntent(openClaim)
                .setWhen(envelope.occurredAtEpochMillis ?: System.currentTimeMillis())
                .build(),
        )
    }

    private fun showIncomingCall(
        manager: NotificationManager,
        call: IncomingCallPayload,
        phone: String?,
    ) {
        val expiresAt = call.ringExpiresAt ?: return
        val activeCallId = activeCallState.activeCallId.value
        if (activeCallId == call.callId) return
        val ringLease = replayLedger.admitRing(
            callId = call.callId,
            ringExpiresAt = expiresAt,
            serverTime = call.serverTime,
        ) ?: return
        val timeoutMillis = ringLease.remainingMillis(
            elapsedRealtimeClock.millis(),
            bootSessionIdProvider.currentBootId(),
        ) ?: return
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
                ringExpiresAt = expiresAt,
            )
        }
        if (deliveryPlan.notificationSurface == IncomingCallNotificationSurface.CALL_WAITING) {
            if (deliveryPlan.relayToActiveCall) incomingCallRelay.publish(call)
            showCallWaitingNotification(
                manager = manager,
                call = call,
                timeoutMillis = timeoutMillis,
                target = deliveryPlan.notificationTarget,
            )
            // Schedule after every local surface exists. An already-elapsed deadline can now
            // remove the banner as well as Telecom instead of firing just before the relay.
            ringDeadlines.schedule(call.callId, ringLease)
            reconcileIncomingCallPublication(call.callId, expiresAt)
            return
        }

        val ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_RINGTONE,
        ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        manager.createNotificationChannel(
            NotificationChannel(
                INCOMING_CALLS_CHANNEL_ID,
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
            IncomingCallRelayActivity.intent(
                context = context,
                callId = call.callId,
                purpose = IncomingCallLaunchPurpose.OPEN,
                ringExpiresAt = expiresAt,
            )
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Answering from the banner opens the verified call screen and accepts once validated;
        // declining is handled entirely in the background so the status bar stays usable.
        val answerCall = PendingIntent.getActivity(
            context,
            call.callId.hashCode() + 1,
            IncomingCallRelayActivity.intent(
                context = context,
                callId = call.callId,
                purpose = IncomingCallLaunchPurpose.ANSWER,
                ringExpiresAt = expiresAt,
            )
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
        val access = incomingCallNotificationAccess(context)
        val alertPlan = incomingCallAlertPlan(access)
        IncomingCallDiagnostics.alertEvaluated(access, alertPlan)
        val notification = NotificationCompat.Builder(context, INCOMING_CALLS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kit_mark)
                .setContentTitle(
                    if (call.video) "Incoming Kit Pay video call" else "Incoming Kit Pay call",
                )
                .setContentText("Open Kit Pay to answer.")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                // The system call banner offers Answer and Decline directly from the status bar.
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineCall, answerCall))
                .addPerson(caller)
                .setOngoing(true)
                .setTimeoutAfter(timeoutMillis)
                .setContentIntent(openCall)
                .setPublicVersion(
                    NotificationCompat.Builder(context, INCOMING_CALLS_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_kit_mark)
                        .setContentTitle("Incoming Kit Pay call")
                        .setContentText("Open Kit Pay to answer.")
                        .build(),
                )
        if (alertPlan.useFullScreenIntent) {
            notification.setFullScreenIntent(openCall, true)
        }
        if (alertPlan.showSettingsAction) {
            notification.addAction(0, "Enable call alerts", callAlertSettingsIntent(access))
        }
        if (
            shouldRelayBlockedIncomingCallInForeground(
                alertMode = alertPlan.mode,
                foregrounded = foregroundSource.foregrounded.value,
                surface = deliveryPlan.notificationSurface,
            )
        ) {
            runCatching {
                context.startActivity(
                    IncomingCallRelayActivity.intent(
                        context = context,
                        callId = call.callId,
                        purpose = IncomingCallLaunchPurpose.OPEN,
                        ringExpiresAt = expiresAt,
                    ).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            }
        }
        val published = runCatching {
            val ringingNotification = notification.build().apply {
                // NotificationManager owns sound/vibration even if the app process is killed.
                // The channel, DND and system ringer policy still control audibility. The
                // existing monotonic deadline + timeoutAfter bound this insistent alert.
                flags = flags or Notification.FLAG_INSISTENT
            }
            manager.notify(callTag(call.callId), CALL_NOTIFICATION_ID, ringingNotification)
        }.isSuccess
        IncomingCallDiagnostics.notificationPublished(alertPlan.mode, published)
        ringDeadlines.schedule(call.callId, ringLease)
        reconcileIncomingCallPublication(call.callId, expiresAt)
    }

    /**
     * Closes the admit/publish race with a concurrent answer, decline or terminal push.
     *
     * If terminal retirement committed before this final ledger read, remove anything this
     * delivery just recreated. Natural expiry stays owned by the armed deadline so it publishes a
     * missed lifecycle; retirement that commits afterward removes the surfaces on its own path.
     */
    private fun reconcileIncomingCallPublication(callId: String, ringExpiresAt: String) {
        reconcilePublishedIncomingCall(
            callId = callId,
            authorization = {
                replayLedger.publicationAuthorization(callId, ringExpiresAt)
            },
            retireSurfaces = ringDeadlines::retire,
            finishRinging = { retiredCallId, disposition ->
                telecom.finishRinging(retiredCallId, disposition.telecomDisconnect())
            },
        )
    }

    private fun callAlertSettingsIntent(access: IncomingCallNotificationAccess): PendingIntent {
        return PendingIntent.getActivity(
            context,
            CALL_ALERT_SETTINGS_REQUEST_CODE,
            incomingCallAlertSettingsIntent(context, access),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A call-waiting call arriving during another call: a quiet heads-up alert (no full-screen
     * takeover) that opens the active call screen, which shows the in-app waiting banner and plays
     * the call-waiting tone. Decline is still offered from the shade.
     */
    private fun showCallWaitingNotification(
        manager: NotificationManager,
        call: IncomingCallPayload,
        timeoutMillis: Long,
        target: IncomingCallNotificationTarget,
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
                // No waiting-call deep link: bring the existing task and its active-call controls
                // forward instead of constructing a second incoming-call screen/ViewModel.
                .setAction(ACTION_RETURN_TO_ACTIVE_CALL)
                .apply { target.deepLink(call)?.let { data = Uri.parse(it) } }
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
        const val PAYMENTS_CHANNEL_ID = "kit_payments"
        const val PAYMENT_NOTIFICATION_ID = 4_201
        const val MISSED_NOTIFICATION_ID = 4_105
        // Bumped from "kit_incoming_calls": notification-channel sound and vibration are immutable
        // once created, so a new id is required for the ringtone settings to apply on upgrades.
        const val CALL_NOTIFICATION_ID = 4_101
        const val CALL_ALERT_SETTINGS_REQUEST_CODE = 4_102
        const val ACTION_RETURN_TO_ACTIVE_CALL = "com.kit.wallet.action.RETURN_TO_ACTIVE_CALL"

        fun callTag(callId: String) = "kit_call:$callId"
    }
}

/** Final authorization fence for surfaces created after an earlier successful ring admission. */
internal fun reconcilePublishedIncomingCall(
    callId: String,
    authorization: () -> IncomingCallPublicationAuthorization,
    retireSurfaces: (String, IncomingCallRetirementDisposition) -> Unit,
    finishRinging: (String, IncomingCallRetirementDisposition) -> Unit,
): Boolean {
    return when (val result = authorization()) {
        IncomingCallPublicationAuthorization.Authorized -> true
        IncomingCallPublicationAuthorization.Expired -> false
        is IncomingCallPublicationAuthorization.Retired -> {
            retireSurfaces(callId, result.disposition)
            finishRinging(callId, result.disposition)
            false
        }
    }
}

internal fun CallLifecycleEvent.ringingRetirementDisposition(): IncomingCallRetirementDisposition =
    when (kind) {
        CallLifecycleKind.ANSWERED -> IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE
        CallLifecycleKind.DECLINED -> IncomingCallRetirementDisposition.REJECTED
        CallLifecycleKind.ENDED -> IncomingCallRetirementDisposition.REMOTE
        CallLifecycleKind.MISSED -> IncomingCallRetirementDisposition.MISSED
    }

private fun IncomingCallRetirementDisposition.telecomDisconnect(): KitTelecomDisconnect =
    when (this) {
        IncomingCallRetirementDisposition.ANSWERED_ELSEWHERE ->
            KitTelecomDisconnect.ANSWERED_ELSEWHERE
        IncomingCallRetirementDisposition.REJECTED -> KitTelecomDisconnect.REJECTED
        IncomingCallRetirementDisposition.REMOTE -> KitTelecomDisconnect.REMOTE
        IncomingCallRetirementDisposition.MISSED -> KitTelecomDisconnect.MISSED
        IncomingCallRetirementDisposition.LOCAL -> KitTelecomDisconnect.LOCAL
        IncomingCallRetirementDisposition.ERROR -> KitTelecomDisconnect.ERROR
        IncomingCallRetirementDisposition.UNKNOWN -> KitTelecomDisconnect.ANSWERED_ELSEWHERE
    }
