package com.kit.wallet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.kit.wallet.data.notifications.ACTION_OPEN_AUTHORIZED_INCOMING_CALL
import com.kit.wallet.data.notifications.EXTRA_INCOMING_CALL_AUTHORIZATION
import com.kit.wallet.data.notifications.IncomingCallLaunchAuthorizer
import com.kit.wallet.data.notifications.IncomingCallLaunchPurpose
import com.kit.wallet.data.notifications.IncomingCallReplayLedger
import com.kit.wallet.data.session.SessionStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * App-private, synchronous bridge between an immutable call PendingIntent (or Telecom) and the
 * exported launcher activity. The bridge is deliberately the component that mints the one-time
 * process capability: a cold-start PendingIntent therefore never has to persist a bearer token,
 * and an explicit attacker launch of [MainActivity] cannot manufacture a call route.
 */
@AndroidEntryPoint
class IncomingCallRelayActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionStore
    @Inject internal lateinit var authorizer: IncomingCallLaunchAuthorizer
    @Inject lateinit var replayLedger: IncomingCallReplayLedger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent.takeRelayRequest()
        val token = request?.let { trusted ->
            val serverExpiry = runCatching {
                java.time.Instant.parse(trusted.ringExpiresAt)
            }.getOrNull() ?: return@let null
            if (!replayLedger.authorizesLaunch(trusted.callId, serverExpiry)) return@let null
            sessions.current()?.fence()?.let { fence ->
                authorizer.issue(
                    callId = trusted.callId,
                    purpose = trusted.purpose,
                    session = fence,
                    ringExpiresAt = trusted.ringExpiresAt,
                )
            }
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                if (token != null) {
                    action = ACTION_OPEN_AUTHORIZED_INCOMING_CALL
                    putExtra(EXTRA_INCOMING_CALL_AUTHORIZATION, token)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    companion object {
        private const val ACTION_RELAY_INCOMING_CALL =
            "com.kit.wallet.action.RELAY_INCOMING_CALL"
        private const val EXTRA_CALL_ID = "com.kit.wallet.extra.RELAY_CALL_ID"
        private const val EXTRA_PURPOSE = "com.kit.wallet.extra.RELAY_CALL_PURPOSE"
        private const val EXTRA_RING_EXPIRES_AT =
            "com.kit.wallet.extra.RELAY_RING_EXPIRES_AT"

        internal fun intent(
            context: Context,
            callId: String,
            purpose: IncomingCallLaunchPurpose,
            ringExpiresAt: String,
        ): Intent = Intent(context, IncomingCallRelayActivity::class.java)
            .setAction(ACTION_RELAY_INCOMING_CALL)
            .putExtra(EXTRA_CALL_ID, callId)
            .putExtra(EXTRA_PURPOSE, purpose.name)
            .putExtra(EXTRA_RING_EXPIRES_AT, ringExpiresAt)

        private fun Intent.takeRelayRequest(): RelayRequest? {
            val suppliedAction = action
            val callId = getStringExtra(EXTRA_CALL_ID)
            val purpose = getStringExtra(EXTRA_PURPOSE)
            val expiresAt = getStringExtra(EXTRA_RING_EXPIRES_AT)
            replaceExtras(null as Bundle?)
            data = null
            action = null
            if (suppliedAction != ACTION_RELAY_INCOMING_CALL) return null
            val parsedPurpose = runCatching {
                IncomingCallLaunchPurpose.valueOf(purpose.orEmpty())
            }.getOrNull() ?: return null
            return RelayRequest(
                callId = callId.orEmpty(),
                purpose = parsedPurpose,
                ringExpiresAt = expiresAt.orEmpty(),
            )
        }
    }
}

private data class RelayRequest(
    val callId: String,
    val purpose: IncomingCallLaunchPurpose,
    val ringExpiresAt: String,
)
