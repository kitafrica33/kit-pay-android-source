package com.kit.wallet.data.notifications.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kit.wallet.data.notifications.PushEnvelopeReceiver
import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.worker.NotificationRecoveryScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FCM system entry point. All application behavior lives behind provider-neutral interfaces. */
@AndroidEntryPoint
class KitFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var tokens: PushTokenCoordinator
    @Inject lateinit var receiver: PushEnvelopeReceiver
    @Inject internal lateinit var recovery: NotificationRecoveryScheduler

    override fun onNewToken(token: String) {
        // Persist before starting process-local registration: Android may stop this service as
        // soon as the callback returns, including while the first HTTP request is offline.
        recovery.schedule()
        tokens.tokenChanged(provider = FirebasePushMessagingTransport.PROVIDER, token = token)
    }

    override fun onDeletedMessages() {
        // FCM drops queued messages after an extended disconnect or queue overflow. Recovery
        // pulls the durable inbox and encrypted-message history; it never replays stale rings.
        recovery.schedule()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        receiver.receive(FcmPushEnvelopeMapper.map(message))
    }
}
