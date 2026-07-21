package com.kit.wallet.data.notifications.fcm

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.kit.wallet.BuildConfig
import com.kit.wallet.data.notifications.PushMessagingTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class FirebasePushMessagingTransport @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PushMessagingTransport {
    override val provider: String = PROVIDER
    override val configured: Boolean
        get() = BuildConfig.FIREBASE_CONFIGURED

    override fun initialize() {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .build(),
        )
    }

    override suspend fun currentToken(): String {
        check(configured) { "FCM is not configured for this build." }
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (continuation.isActive) continuation.resume(token)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    internal companion object {
        const val PROVIDER = "fcm"
    }
}
