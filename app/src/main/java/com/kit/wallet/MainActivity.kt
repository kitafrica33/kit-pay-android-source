package com.kit.wallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.messaging.ACTION_OPEN_AUTHORIZED_SECURE_MESSAGE
import com.kit.wallet.data.messaging.EXTRA_SECURE_MESSAGE_AUTHORIZATION
import com.kit.wallet.data.messaging.SecureMessageNavigationAuthorizer
import com.kit.wallet.data.notifications.IncomingCallPayload
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.feature.chat.ACTION_OPEN_TEXT_SHARE
import com.kit.wallet.feature.chat.EXTRA_TEXT_SHARE_TOKEN
import com.kit.wallet.feature.chat.IncomingTextShareRequest
import com.kit.wallet.feature.chat.IncomingTextShareStore
import com.kit.wallet.navigation.KitApp
import com.kit.wallet.ui.theme.KitWalletTheme
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import com.kit.wallet.worker.scheduleAuthenticatedMessagingCatchUp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var messagingSyncScheduler: SecureMessagingSyncScheduler
    @Inject lateinit var secureMessageAuthorizer: SecureMessageNavigationAuthorizer
    private var pendingDeepLink by mutableStateOf<String?>(null)
    private var pendingSecureMessage by mutableStateOf<PendingSecureMessageRoute?>(null)
    private var pendingTextShare by mutableStateOf<IncomingTextShareRequest?>(null)
    private var deferredTextShare: IncomingTextShareRequest? = null
    private var pendingTextShareSending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val activeSession by sessions.session.collectAsStateWithLifecycle()
            LaunchedEffect(activeSession?.sessionId, pendingSecureMessage?.sessionEpoch) {
                if (pendingSecureMessage?.sessionEpoch != activeSession?.sessionId) {
                    pendingSecureMessage = null
                }
            }
            KitWalletTheme {
                KitApp(
                    deepLinkUri = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null },
                    secureMessageConversationId = pendingSecureMessage
                        ?.takeIf { it.sessionEpoch == activeSession?.sessionId }
                        ?.conversationId,
                    onSecureMessageRouteConsumed = { pendingSecureMessage = null },
                    incomingTextShare = pendingTextShare,
                    onTextShareConsumed = { token ->
                        if (pendingTextShare?.token == token) {
                            pendingTextShare = deferredTextShare
                            deferredTextShare = null
                            pendingTextShareSending = false
                        }
                    },
                    onTextShareSendingChanged = { token, sending ->
                        if (pendingTextShare?.token == token) {
                            pendingTextShareSending = sending
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        scheduleAuthenticatedMessagingCatchUp(
            hasSession = sessions.current() != null,
            schedule = messagingSyncScheduler::schedule,
        )
    }

    private fun handleIntent(intent: Intent?) {
        intent?.takeAuthorizedSecureMessageRoute(
            authorizer = secureMessageAuthorizer,
            currentSessionEpoch = sessions.current()?.sessionId,
        )?.let { pendingSecureMessage = it }
        intent?.kitDeepLink()?.let { pendingDeepLink = it }
        val incomingTextShare = intent?.takeIncomingTextShare() ?: return
        if (pendingTextShareSending && pendingTextShare != null) {
            // Keep the explicitly confirmed send visible until it resolves. Retain at most the
            // newest follow-up share in memory so repeated external Intents cannot grow a queue.
            deferredTextShare = incomingTextShare
        } else {
            pendingTextShare = incomingTextShare
            deferredTextShare = null
            pendingTextShareSending = false
        }
    }
}

private data class PendingSecureMessageRoute(
    val conversationId: String,
    val sessionEpoch: String,
)

private fun Intent.takeAuthorizedSecureMessageRoute(
    authorizer: SecureMessageNavigationAuthorizer,
    currentSessionEpoch: String?,
): PendingSecureMessageRoute? {
    if (action != ACTION_OPEN_AUTHORIZED_SECURE_MESSAGE) return null
    val token = getStringExtra(EXTRA_SECURE_MESSAGE_AUTHORIZATION)
    removeExtra(EXTRA_SECURE_MESSAGE_AUTHORIZATION)
    data = null
    action = null
    val sessionEpoch = currentSessionEpoch ?: return null
    return authorizer.consume(token, sessionEpoch)?.let { conversationId ->
        PendingSecureMessageRoute(conversationId, sessionEpoch)
    }
}

private fun Intent.takeIncomingTextShare(): IncomingTextShareRequest? {
    if (action != ACTION_OPEN_TEXT_SHARE) return null
    val token = getStringExtra(EXTRA_TEXT_SHARE_TOKEN).orEmpty()

    // Do not leave even the opaque one-time hand-off token on the Activity Intent. The actual
    // shared text never enters this Intent and cannot be reconstructed after process death.
    removeExtra(EXTRA_TEXT_SHARE_TOKEN)
    action = null
    return token.takeIf(String::isNotBlank)?.let(IncomingTextShareStore::take)
}

/**
 * Provider-delivered notification taps can carry call data as activity extras rather than as an
 * Intent data URI.
 */
private fun Intent.kitDeepLink(): String? {
    dataString?.let { raw ->
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val isKycReturn = uri?.let {
            it.scheme == "kitwallet" &&
                it.host == "kyc" &&
                it.path == "/status" &&
                it.query == null &&
                it.fragment == null
        } == true
        if (isKycReturn || IncomingCallPayload.fromDeepLink(raw) != null) return raw
        return null
    }
    val payloadData = CALL_PAYLOAD_KEYS.mapNotNull { key ->
        getStringExtra(key)?.let { value -> key to value }
    }.toMap()
    return IncomingCallPayload.fromData(payloadData)?.deepLinkUri()
}

private val CALL_PAYLOAD_KEYS = listOf(
    "type",
    "call_id",
    "call_type",
    "video",
    "initiator_name",
    "ring_expires_at",
)
