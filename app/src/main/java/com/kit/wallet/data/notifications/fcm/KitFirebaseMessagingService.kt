package com.kit.wallet.data.notifications.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kit.wallet.data.notifications.PushEnvelopeReceiver
import com.kit.wallet.data.notifications.PushTokenCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FCM system entry point. All application behavior lives behind provider-neutral interfaces. */
@AndroidEntryPoint
class KitFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var tokens: PushTokenCoordinator
    @Inject lateinit var receiver: PushEnvelopeReceiver

    override fun onNewToken(token: String) {
        tokens.tokenChanged(provider = FirebasePushMessagingTransport.PROVIDER, token = token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        receiver.receive(FcmPushEnvelopeMapper.map(message))
    }
}
