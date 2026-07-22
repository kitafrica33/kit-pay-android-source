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
 * Handles call actions initiated by Android Telecom or the incoming-call notification even when
 * the activity is not visible. Every action is reported to the backend immediately.
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var calls: CallRepository

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_DECLINE, ACTION_END)) return
        val callId = IncomingCallPayload.callId(
            mapOf("call_id" to intent.getStringExtra(EXTRA_CALL_ID).orEmpty()),
        ) ?: return
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(notificationTag(callId), NOTIFICATION_ID)
        val pending = goAsync()
        applicationScope.launch {
            try {
                runCatching {
                    if (intent.action == ACTION_DECLINE) calls.decline(callId)
                    else calls.end(callId, intent.getStringExtra(EXTRA_REASON) ?: "cancelled")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.kit.wallet.action.DECLINE_CALL"
        const val ACTION_END = "com.kit.wallet.action.END_CALL"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_REASON = "reason"

        // Mirrors DefaultPushEnvelopeReceiver's incoming-call notification identity.
        const val NOTIFICATION_ID = 4_101

        fun notificationTag(callId: String) = "kit_call:$callId"

        fun declineIntent(context: Context, callId: String): Intent =
            Intent(context, CallActionReceiver::class.java)
                .setAction(ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, callId)

        fun endIntent(context: Context, callId: String, reason: String): Intent =
            Intent(context, CallActionReceiver::class.java)
                .setAction(ACTION_END)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_REASON, reason)
    }
}
