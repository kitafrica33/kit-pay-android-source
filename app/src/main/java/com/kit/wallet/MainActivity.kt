package com.kit.wallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kit.wallet.data.messaging.ACTION_OPEN_AUTHORIZED_SECURE_MESSAGE
import com.kit.wallet.data.messaging.EXTRA_SECURE_MESSAGE_AUTHORIZATION
import com.kit.wallet.data.messaging.SecureMessageNavigationAuthorizer
import com.kit.wallet.data.messaging.SecureMessagingAuthenticationEpochChangedException
import com.kit.wallet.data.messaging.SecureMessagingCryptographicFailureException
import com.kit.wallet.data.messaging.SecureMessagingProtocolUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.isRetryableSecureMessagingStateFailure
import com.kit.wallet.data.notifications.IncomingCallPayload
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.SessionFence
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
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var messagingSyncScheduler: SecureMessagingSyncScheduler
    @Inject lateinit var messagingSyncEngine: SecureMessagingSyncEngine
    @Inject lateinit var secureMessageAuthorizer: SecureMessageNavigationAuthorizer
    private val foregroundStartMutex = Mutex()
    private var foregroundStartJob: Job? = null
    private var pendingDeepLink by mutableStateOf<String?>(null)
    private var pendingSecureMessage by mutableStateOf<PendingSecureMessageRoute?>(null)
    private var pendingTextShare by mutableStateOf<IncomingTextShareRequest?>(null)
    private var deferredTextShare: IncomingTextShareRequest? = null
    private var pendingTextShareSending = false
    private var sessionRestorationActionInFlight by mutableStateOf(false)
    private var sessionRestorationActionFailed by mutableStateOf(false)
    private var sessionRestorationDiscardConfirmation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingDeepLink = savedInstanceState?.getString(STATE_PENDING_DEEP_LINK)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val activeSession by sessions.session.collectAsStateWithLifecycle()
            val sessionRestorationPending by sessions.restorationPending.collectAsStateWithLifecycle()
            val sessionRestorationRetryable by
                sessions.restorationRetryable.collectAsStateWithLifecycle()
            LaunchedEffect(activeSession?.sessionId, pendingSecureMessage?.sessionEpoch) {
                if (pendingSecureMessage?.sessionEpoch != activeSession?.sessionId) {
                    pendingSecureMessage = null
                }
            }
            LaunchedEffect(sessionRestorationPending) {
                if (!sessionRestorationPending) {
                    sessionRestorationDiscardConfirmation = false
                    sessionRestorationActionFailed = false
                }
            }
            KitWalletTheme {
                if (sessionRestorationPending) {
                    SessionRestorationGate(
                        automaticRetryAvailable = sessionRestorationRetryable,
                        actionInFlight = sessionRestorationActionInFlight,
                        actionFailed = sessionRestorationActionFailed,
                        discardConfirmationRequested = sessionRestorationDiscardConfirmation,
                        onRetry = ::retryRetainedSessionFromUi,
                        onRequestSignInAgain = {
                            sessionRestorationDiscardConfirmation = true
                        },
                        onCancelSignInAgain = {
                            sessionRestorationDiscardConfirmation = false
                        },
                        onConfirmSignInAgain = ::discardRetainedSessionFromUi,
                    )
                } else {
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingDeepLink?.let { outState.putString(STATE_PENDING_DEEP_LINK, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        foregroundStartJob?.cancel()
        foregroundStartJob = lifecycleScope.launch {
            // Cancellation is asynchronous; the mutex ensures a rapid stop/start or duplicate
            // start cannot enter restoration/activation while its predecessor is still unwinding.
            foregroundStartMutex.withLock {
                restoreRetainedSessionWithRetries(
                    pending = { sessions.restorationPending.value },
                    retryable = { sessions.restorationRetryable.value },
                    retry = sessions::retryRestore,
                    waitBeforeNextAttempt = { delay(it) },
                )
            }
            observeForegroundSecureMessagingSessions(
                sessionFences = sessions.session.map { it?.fence() },
                serializationMutex = foregroundStartMutex,
                currentSession = { sessions.current()?.fence() },
                engineReady = messagingSyncEngine.isReady,
                schedule = messagingSyncScheduler::schedule,
                synchronize = {
                    withContext(Dispatchers.IO) {
                        messagingSyncEngine.synchronize()
                    }
                },
                waitBeforeNextAttempt = { delay(it) },
            )
        }
    }

    override fun onStop() {
        foregroundStartJob?.cancel()
        super.onStop()
    }

    private fun retryRetainedSessionFromUi() {
        if (sessionRestorationActionInFlight) return
        sessionRestorationDiscardConfirmation = false
        sessionRestorationActionInFlight = true
        sessionRestorationActionFailed = false
        lifecycleScope.launch {
            val restored = runCatching { sessions.retryRestore() }.getOrDefault(false)
            sessionRestorationActionFailed = !restored && sessions.restorationPending.value
            sessionRestorationActionInFlight = false
        }
    }

    private fun discardRetainedSessionFromUi() {
        if (sessionRestorationActionInFlight) return
        sessionRestorationActionInFlight = true
        sessionRestorationActionFailed = false
        lifecycleScope.launch {
            val discarded = runCatching { sessions.discardPendingRestoration() }.isSuccess &&
                !sessions.restorationPending.value
            sessionRestorationActionFailed = !discarded
            sessionRestorationActionInFlight = false
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.takeAuthorizedSecureMessageRoute(
            authorizer = secureMessageAuthorizer,
            currentSessionEpoch = sessions.current()?.sessionId,
        )?.let { pendingSecureMessage = it }
        intent?.takeKitDeepLink()?.let { pendingDeepLink = it }
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

@Composable
private fun SessionRestorationGate(
    automaticRetryAvailable: Boolean,
    actionInFlight: Boolean,
    actionFailed: Boolean,
    discardConfirmationRequested: Boolean,
    onRetry: () -> Unit,
    onRequestSignInAgain: () -> Unit,
    onCancelSignInAgain: () -> Unit,
    onConfirmSignInAgain: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {}
    if (discardConfirmationRequested) {
        AlertDialog(
            onDismissRequest = onCancelSignInAgain,
            title = { Text("Erase this device's secure session?") },
            text = {
                Text(
                    "Signing in again erases the saved login plus this device's local " +
                        "end-to-end encrypted message keys and history. History can be " +
                        "recovered only if another enrolled device still has it.",
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmSignInAgain, enabled = !actionInFlight) {
                    Text("Erase and sign in")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelSignInAgain, enabled = !actionInFlight) {
                    Text("Keep session")
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Restore your Kit Pay session") },
        text = {
            Text(
                when {
                    actionFailed -> "Kit Pay could not complete that action. Unlock your device and retry."
                    automaticRetryAvailable ->
                        "Your encrypted sign-in is still safe. Unlock this device, then retry."
                    else -> "Kit Pay could not safely open the saved sign-in. Retry, or choose " +
                        "Sign in again to review the local data that must be erased."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onRetry, enabled = !actionInFlight) { Text("Retry") }
        },
        dismissButton = {
            TextButton(onClick = onRequestSignInAgain, enabled = !actionInFlight) {
                Text("Sign in again")
            }
        },
    )
}

private const val SESSION_RESTORE_ATTEMPTS = 3
private const val SESSION_RESTORE_RETRY_DELAY_MILLIS = 250L
private const val FOREGROUND_MESSAGING_SYNC_ATTEMPTS = 4
private const val FOREGROUND_MESSAGING_SYNC_RETRY_DELAY_MILLIS = 5_000L
private const val FOREGROUND_MESSAGING_SYNC_MAX_COOLDOWN_MILLIS = 60_000L

@VisibleForTesting
internal suspend fun restoreRetainedSessionWithRetries(
    attempts: Int = SESSION_RESTORE_ATTEMPTS,
    pending: () -> Boolean,
    retryable: () -> Boolean,
    retry: suspend () -> Boolean,
    waitBeforeNextAttempt: suspend (Long) -> Unit,
): Boolean {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        if (!pending()) return true
        if (!retryable()) return false
        if (retry()) return true
        if (attempt < attempts - 1) {
            waitBeforeNextAttempt(SESSION_RESTORE_RETRY_DELAY_MILLIS * (attempt + 1L))
        }
    }
    return !pending()
}

@VisibleForTesting
internal suspend fun scheduleAndSynchronizeForegroundSecureMessaging(
    expectedSession: SessionFence?,
    currentSession: () -> SessionFence?,
    engineReady: Boolean,
    schedule: () -> Unit,
    synchronize: suspend () -> Unit,
    waitBeforeNextAttempt: suspend (Long) -> Unit,
): Boolean {
    // WorkManager remains the durable fallback, but an enqueue/storage failure must not prevent
    // this foreground process from activating secure messaging immediately.
    runCatching {
        scheduleAuthenticatedMessagingCatchUp(
            hasSession = expectedSession != null,
            schedule = schedule,
        )
    }
    return synchronizeForegroundSecureMessagingWithRetries(
        expectedSession = expectedSession,
        currentSession = currentSession,
        engineReady = engineReady,
        synchronize = synchronize,
        waitBeforeNextAttempt = waitBeforeNextAttempt,
    )
}

/**
 * Keeps foreground activation attached to the authenticated-session owner, including a login that
 * completes after this Activity has already started. Structurally equal fences are credential
 * refreshes of the same owner and do not restart work; replacement cancels the obsolete attempt.
 */
@VisibleForTesting
internal suspend fun observeForegroundSecureMessagingSessions(
    sessionFences: Flow<SessionFence?>,
    serializationMutex: Mutex,
    currentSession: () -> SessionFence?,
    engineReady: Boolean,
    schedule: () -> Unit,
    synchronize: suspend () -> Unit,
    waitBeforeNextAttempt: suspend (Long) -> Unit,
) {
    sessionFences.distinctUntilChanged().collectLatest { expectedSession ->
        serializationMutex.withLock {
            scheduleAndSynchronizeForegroundSecureMessaging(
                expectedSession = expectedSession,
                currentSession = currentSession,
                engineReady = engineReady,
                schedule = schedule,
                synchronize = synchronize,
                waitBeforeNextAttempt = waitBeforeNextAttempt,
            )
        }
    }
}

@VisibleForTesting
internal suspend fun synchronizeForegroundSecureMessagingWithRetries(
    expectedSession: SessionFence?,
    currentSession: () -> SessionFence?,
    engineReady: Boolean,
    attempts: Int = FOREGROUND_MESSAGING_SYNC_ATTEMPTS,
    synchronize: suspend () -> Unit,
    waitBeforeNextAttempt: suspend (Long) -> Unit,
): Boolean {
    require(attempts > 0)
    if (expectedSession == null || !engineReady) return false

    var failedAttempts = 0
    var cycleCooldownMillis = FOREGROUND_MESSAGING_SYNC_RETRY_DELAY_MILLIS
    while (currentSession() == expectedSession) {
        if (currentSession() != expectedSession) return false
        try {
            synchronize()
            return currentSession() == expectedSession
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (currentSession() != expectedSession) return false
            failedAttempts++
            if (failedAttempts >= attempts &&
                !isRetryableForegroundSecureMessagingFailure(error)
            ) {
                return false
            }
            val retryDelay = if (failedAttempts >= attempts) {
                cycleCooldownMillis.also {
                    cycleCooldownMillis = (cycleCooldownMillis * 2)
                        .coerceAtMost(FOREGROUND_MESSAGING_SYNC_MAX_COOLDOWN_MILLIS)
                }
            } else {
                FOREGROUND_MESSAGING_SYNC_RETRY_DELAY_MILLIS
            }
            // collectLatest/onStop cancellation ends this loop immediately. While the same login
            // remains foregrounded, Android 9 recovery must not depend on delayed OEM WorkManager.
            waitBeforeNextAttempt(retryDelay)
        }
    }
    return false
}

@VisibleForTesting
internal fun isRetryableForegroundSecureMessagingFailure(error: Throwable): Boolean {
    if (error is SecureMessagingCryptographicFailureException ||
        error is SecureMessagingProtocolUnavailableException ||
        error is SecureMessagingAuthenticationEpochChangedException
    ) {
        return false
    }
    if (isRetryableSecureMessagingStateFailure(error)) return true
    return when (error) {
        is IOException,
        is SecureMessagingStateConflictException,
        -> true
        is KitWalletApiException ->
            error.statusCode == null ||
                error.statusCode == 408 ||
                error.statusCode == 425 ||
                error.statusCode == 429 ||
                error.statusCode >= 500
        else -> false
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
 * Takes one validated external navigation route from this Activity Intent.
 *
 * Provider-delivered notification taps can carry call data as activity extras rather than as an
 * Intent data URI.
 *
 * MainActivity is single-top for call notifications. Leaving the route on its retained Intent
 * lets a later Activity recreation replay an already-consumed call over whatever screen the user
 * opened next. Clear the source before navigation; [MainActivity.onSaveInstanceState] retains only
 * a route that is still legitimately waiting for session/capability readiness.
 */
@VisibleForTesting
internal fun Intent.takeKitDeepLink(): String? {
    dataString?.let { raw ->
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val isKycReturn = uri?.let {
            it.scheme == "kitwallet" &&
                it.host == "kyc" &&
                it.path == "/status" &&
                it.query == null &&
                it.fragment == null
        } == true
        val callPayload = IncomingCallPayload.fromDeepLink(raw)
        val canonicalRoute = when {
            isKycReturn -> KYC_STATUS_DEEP_LINK
            callPayload != null -> callPayload.deepLinkUri(callPayload.acceptRequested)
            else -> null
        }
        if (canonicalRoute != null) {
            data = null
            CALL_PAYLOAD_KEYS.forEach(::removeExtra)
            action = null
            return canonicalRoute
        }
        return null
    }
    val payloadData = CALL_PAYLOAD_KEYS.mapNotNull { key ->
        getStringExtra(key)?.let { value -> key to value }
    }.toMap()
    val route = IncomingCallPayload.fromData(payloadData)?.deepLinkUri() ?: return null
    CALL_PAYLOAD_KEYS.forEach(::removeExtra)
    action = null
    return route
}

private val CALL_PAYLOAD_KEYS = listOf(
    "type",
    "call_id",
    "call_type",
    "video",
    "initiator_name",
    "initiator_user_id",
    "ring_expires_at",
)

private const val STATE_PENDING_DEEP_LINK = "kit.pending_deep_link"
private const val KYC_STATUS_DEEP_LINK = "kitwallet://kyc/status"
