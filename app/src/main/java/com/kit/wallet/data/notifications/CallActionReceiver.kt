package com.kit.wallet.data.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles the Decline action on the incoming-call notification so a ringing Kit Pay call can be
 * rejected straight from the status bar or lock screen without opening the app. The decline is
 * reported to the backend so the caller stops ringing immediately.
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var calls: CallRepository

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val callId = IncomingCallPayload.callId(
            mapOf("call_id" to intent.getStringExtra(EXTRA_CALL_ID).orEmpty()),
        ) ?: return
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(notificationTag(callId), NOTIFICATION_ID)
        val pending = goAsync()
        applicationScope.launch {
            try {
                runCatching { calls.decline(callId) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.kit.wallet.action.DECLINE_CALL"
        const val EXTRA_CALL_ID = "call_id"

        // Mirrors DefaultPushEnvelopeReceiver's incoming-call notification identity.
        const val NOTIFICATION_ID = 4_101

        fun notificationTag(callId: String) = "kit_call:$callId"

        fun declineIntent(context: Context, callId: String): Intent =
            Intent(context, CallActionReceiver::class.java)
                .setAction(ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, callId)
    }
}
