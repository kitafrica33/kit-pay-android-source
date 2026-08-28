package com.kit.wallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
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
import com.kit.wallet.data.notifications.ActiveCallReturnLink
import com.kit.wallet.data.notifications.ACTION_OPEN_AUTHORIZED_INCOMING_CALL
import com.kit.wallet.data.notifications.AuthorizedIncomingCallLaunch
import com.kit.wallet.data.notifications.EXTRA_INCOMING_CALL_AUTHORIZATION
import com.kit.wallet.data.notifications.IncomingCallLaunchAuthorizer
import com.kit.wallet.data.notifications.IncomingCallLaunchPurpose
import com.kit.wallet.data.notifications.IncomingCallReplayLedger
import com.kit.wallet.data.notifications.PaymentClaimAlert
import com.kit.wallet.data.notifications.PaymentClaimLink
import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.notifications.canonicalIncomingCallId
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.WalletRefreshTrigger
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.feature.chat.ChatMediaScratch
import com.kit.wallet.feature.chat.ACTION_OPEN_TEXT_SHARE
import com.kit.wallet.feature.chat.EXTRA_TEXT_SHARE_TOKEN
import com.kit.wallet.feature.chat.IncomingTextShareRequest
import com.kit.wallet.feature.chat.IncomingTextShareStore
import com.kit.wallet.feature.chat.SharedInboxStore
import com.kit.wallet.navigation.KitApp
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.ui.theme.KitWalletTheme
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import com.kit.wallet.worker.scheduleAuthenticatedMessagingCatchUp
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import java.time.Duration
import java.time.Instant
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
class MainActivity : FragmentActivity() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var messagingSyncScheduler: SecureMessagingSyncScheduler
    @Inject lateinit var messagingSyncEngine: SecureMessagingSyncEngine
    @Inject lateinit var secureMessageAuthorizer: SecureMessageNavigationAuthorizer
    @Inject internal lateinit var incomingCallAuthorizer: IncomingCallLaunchAuthorizer
    @Inject lateinit var incomingCallReplayLedger: IncomingCallReplayLedger
    @Inject lateinit var pushTokens: PushTokenCoordinator
    @Inject lateinit var walletRefresh: WalletRefreshTrigger
    private val foregroundStartMutex = Mutex()
    private var foregroundStartJob: Job? = null
    private var pendingDeepLink by mutableStateOf<String?>(null)
    private var pendingAuthorizedIncomingCall by
        mutableStateOf<AuthorizedIncomingCallLaunch?>(null)
    private var authorizedIncomingCallLease by
        mutableStateOf<AuthorizedIncomingCallLaunch?>(null)
    private var incomingCallLeaseExpiryJob: Job? = null
    private var pendingSecureMessage by mutableStateOf<PendingSecureMessageRoute?>(null)
    private var pendingTextShare by mutableStateOf<IncomingTextShareRequest?>(null)
    private val queuedTextShares = ArrayDeque<IncomingTextShareRequest>()
    private var pendingTextShareSending = false
    private var sessionRestorationActionInFlight by mutableStateOf(false)
    private var sessionRestorationActionFailed by mutableStateOf(false)
    private var sessionRestorationDiscardConfirmation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingDeepLink = savedInstanceState?.getString(STATE_PENDING_DEEP_LINK)
        savedInstanceState
            ?.restoreAuthorizedIncomingCall(
                currentSession = sessions.current()?.fence(),
                now = Instant.now(),
            )
            ?.takeIf { restored ->
                incomingCallReplayLedger.authorizesLaunch(
                    restored.launch.callId,
                    restored.launch.ringExpiresAt,
                )
            }
            ?.let { restored ->
                installAuthorizedIncomingCall(restored.launch, restored.pendingNavigation)
            }
        // A share nobody ever delivered is plaintext the user has forgotten about — including
        // anything a previous process left staged. It goes before this one reads its own intent.
        lifecycleScope.launch(Dispatchers.IO) {
            SharedInboxStore.purgeExpired(applicationContext)
            // Same reasoning one directory over: a viewer or capture that died with the process
            // left decrypted bytes in the cache, and nothing else will ever come back for them.
            ChatMediaScratch.purgeStaleOnce(applicationContext)
        }
        restoreRetainedTextShares()
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
            LaunchedEffect(activeSession?.fence(), authorizedIncomingCallLease) {
                val lease = authorizedIncomingCallLease ?: return@LaunchedEffect
                if (lease.session != activeSession?.fence()) {
                    incomingCallAuthorizer.revokeAll()
                    clearAuthorizedIncomingCall(lease.callId)
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
                    Box(Modifier.fillMaxSize()) {
                        KitApp(
                            deepLinkUri = pendingDeepLink,
                            onDeepLinkConsumed = { pendingDeepLink = null },
                            authorizedIncomingCall = pendingAuthorizedIncomingCall,
                            activeAuthorizedIncomingCall = authorizedIncomingCallLease,
                            onAuthorizedIncomingCallRejected = ::clearAuthorizedIncomingCall,
                            onAuthorizedIncomingCallSurfaceChanged = { callId, visible ->
                                if (visible && authorizedIncomingCallLease?.callId == callId) {
                                    if (pendingAuthorizedIncomingCall?.callId == callId) {
                                        pendingAuthorizedIncomingCall = null
                                    }
                                    setIncomingCallKeyguardVisibility(true)
                                } else if (!visible) {
                                    clearAuthorizedIncomingCall(callId)
                                }
                            },
                            secureMessageConversationId = pendingSecureMessage
                                ?.takeIf { it.sessionEpoch == activeSession?.sessionId }
                                ?.conversationId,
                            onSecureMessageRouteConsumed = { pendingSecureMessage = null },
                            incomingTextShare = pendingTextShare,
                            incomingTextShareOwnerMatches = pendingTextShare?.let { request ->
                                val batch = (request.payload as? com.kit.wallet.feature.chat.IncomingTextShare.Accepted)
                                    ?.batch
                                batch?.owner?.matches(activeSession?.fence()) ?: true
                            } ?: true,
                            onTextShareConsumed = { token ->
                                consumeTextShare(token)
                            },
                            onTextShareDeferred = { token ->
                                deferTextShare(token)
                            },
                            onTextShareSendingChanged = { token, sending ->
                                if (pendingTextShare?.token == token) {
                                    pendingTextShareSending = sending
                                }
                            },
                            onNotificationCapabilityChanged = { pushTokens.capabilityPolicyChanged() },
                        )
                        pendingAuthorizedIncomingCall?.let { launch ->
                            IncomingCallPrivacyCover(
                                callId = launch.callId,
                                onFirstOpaqueFrame = {
                                    if (authorizedIncomingCallLease?.callId == launch.callId) {
                                        setIncomingCallKeyguardVisibility(true)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A pinned partial share can be deferred without deleting its remaining plaintext. A
        // normal launcher reopen reaches this singleTask instance through onNewIntent, so merge
        // retained manifests before consuming any new share token.
        restoreRetainedTextShares()
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingDeepLink?.let { outState.putString(STATE_PENDING_DEEP_LINK, it) }
        authorizedIncomingCallLease?.let { lease ->
            outState.saveAuthorizedIncomingCall(
                lease,
                pendingNavigation = pendingAuthorizedIncomingCall?.callId == lease.callId,
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        // The backend has no wallet push, so money received while backgrounded only becomes
        // visible on the next sync; returning to the foreground is that moment.
        if (sessions.current() != null) walletRefresh.refreshNow()
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
        intent?.takeAuthorizedIncomingCallLaunch(
            authorizer = incomingCallAuthorizer,
            currentSession = sessions.current()?.fence(),
        )?.takeIf { launch ->
            incomingCallReplayLedger.authorizesLaunch(launch.callId, launch.ringExpiresAt)
        }?.let { launch ->
            if (launch.acceptRequested) incomingCallReplayLedger.retire(launch.callId)
            installAuthorizedIncomingCall(launch, pendingNavigation = true)
        }
        intent?.takeAuthorizedSecureMessageRoute(
            authorizer = secureMessageAuthorizer,
            currentSessionEpoch = sessions.current()?.sessionId,
        )?.let { pendingSecureMessage = it }
        intent?.takeKitDeepLink()?.let { pendingDeepLink = it }
        val incomingTextShare = intent?.takeIncomingTextShare(applicationContext) ?: return
        installIncomingTextShare(incomingTextShare)
    }

    private fun installAuthorizedIncomingCall(
        launch: AuthorizedIncomingCallLaunch,
        pendingNavigation: Boolean,
    ) {
        authorizedIncomingCallLease = launch
        pendingAuthorizedIncomingCall = launch.takeIf { pendingNavigation }
        setIncomingCallKeyguardVisibility(false)
        incomingCallLeaseExpiryJob?.cancel()
        incomingCallLeaseExpiryJob = lifecycleScope.launch {
            val remaining = runCatching {
                Duration.between(Instant.now(), launch.expiresAt).toMillis()
            }.getOrDefault(0L)
            if (remaining > 0L) delay(remaining)
            clearAuthorizedIncomingCall(launch.callId)
        }
    }

    private fun clearAuthorizedIncomingCall(callId: String) {
        if (authorizedIncomingCallLease?.callId != callId) return
        incomingCallLeaseExpiryJob?.cancel()
        incomingCallLeaseExpiryJob = null
        pendingAuthorizedIncomingCall = null
        authorizedIncomingCallLease = null
        setIncomingCallKeyguardVisibility(false)
    }

    @Suppress("DEPRECATION")
    private fun setIncomingCallKeyguardVisibility(visible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(visible)
            setTurnScreenOn(visible)
        } else {
            val legacyFlags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (visible) window.addFlags(legacyFlags) else window.clearFlags(legacyFlags)
        }
    }

    private fun installIncomingTextShare(incomingTextShare: IncomingTextShareRequest) {
        if (
            pendingTextShare?.token == incomingTextShare.token ||
            queuedTextShares.any { it.token == incomingTextShare.token }
        ) return
        val currentPendingShare = pendingTextShare
        if (
            incomingTextShare.payload is com.kit.wallet.feature.chat.IncomingTextShare.Rejected &&
            currentPendingShare != null
        ) {
            // A malformed/oversized/over-capacity share owns no plaintext. Keep at most the latest
            // explanation behind the active review instead of deleting or displacing that review.
            queuedTextShares.removeAll {
                it.payload is com.kit.wallet.feature.chat.IncomingTextShare.Rejected
            }
            queuedTextShares.addLast(incomingTextShare)
            return
        }
        if (
            currentPendingShare != null &&
            (pendingTextShareSending || currentPendingShare.hasPinnedDestination())
        ) {
            // Keep the explicitly confirmed send visible until it resolves. Persistence admits at
            // most four retained batches, so keeping the in-memory order cannot grow unbounded.
            queuedTextShares.addLast(incomingTextShare)
        } else {
            pendingTextShare?.takeUnless(IncomingTextShareRequest::hasPinnedDestination)
                ?.let(::retireTextShare)
            pendingTextShare = incomingTextShare
            pendingTextShareSending = false
        }
    }

    private fun restoreRetainedTextShares() {
        val retained = IncomingTextShareStore.restore(applicationContext)
        if (retained.isEmpty()) return
        // Merge rather than replace: onNewIntent can arrive while a confirmed share is still
        // queueing. A partially queued batch becomes reachable again on ordinary launcher reopen
        // without displacing or duplicating any request already shown in this process.
        val knownTokens = buildSet {
            pendingTextShare?.token?.let(::add)
            queuedTextShares.forEach { add(it.token) }
        }
        retained.filterNot { it.token in knownTokens }.forEach(queuedTextShares::addLast)
        if (pendingTextShare == null) {
            pendingTextShare = queuedTextShares.removeFirstOrNull()
            pendingTextShareSending = false
        }
    }

    private fun consumeTextShare(token: String) {
        IncomingTextShareStore.acknowledge(applicationContext, token)
        if (pendingTextShare?.token == token) {
            pendingTextShare = queuedTextShares.removeFirstOrNull()
            pendingTextShareSending = false
        } else {
            queuedTextShares.removeAll { it.token == token }
        }
    }

    /** Hides a pinned retry without deleting its remaining plaintext; restart restores it. */
    private fun deferTextShare(token: String) {
        if (pendingTextShare?.token == token) {
            pendingTextShare = queuedTextShares.removeFirstOrNull()
            pendingTextShareSending = false
        } else {
            queuedTextShares.removeAll { it.token == token }
        }
    }

    private fun retireTextShare(request: IncomingTextShareRequest) {
        IncomingTextShareStore.acknowledge(applicationContext, request.token)
    }
}

private fun IncomingTextShareRequest.hasPinnedDestination(): Boolean =
    ((payload as? com.kit.wallet.feature.chat.IncomingTextShare.Accepted)
        ?.batch?.pinnedConversationId != null)

@Composable
private fun IncomingCallPrivacyCover(
    callId: String,
    onFirstOpaqueFrame: () -> Unit,
) {
    // Wait across composition and the following draw before allowing this window above the
    // keyguard. The first frame callback precedes drawing; the second cannot run until after that
    // opaque frame was submitted, so a warm wallet/chat frame is never exposed.
    LaunchedEffect(callId) {
        withFrameNanos { }
        withFrameNanos { }
        onFirstOpaqueFrame()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Incoming Kit Pay call", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Checking the call securely…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
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
    BackHandler {
        if (discardConfirmationRequested && !actionInFlight) onCancelSignInAgain()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                if (discardConfirmationRequested) {
                    "Erase this device's secure session?"
                } else {
                    "Restore your Kit Pay session"
                },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (discardConfirmationRequested) {
                    "Signing in again erases the saved login plus this device's local " +
                        "end-to-end encrypted message keys and history. History can be " +
                        "recovered only if another enrolled device still has it."
                } else {
                    when {
                        actionFailed ->
                            "Kit Pay could not complete that action. Unlock your device and retry."
                        automaticRetryAvailable ->
                            "Your encrypted sign-in is still safe. Unlock this device, then retry."
                        else ->
                            "Kit Pay could not safely open the saved sign-in. Retry, or sign in " +
                                "again after reviewing the local data that must be erased."
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            if (discardConfirmationRequested) {
                KitGreenButton(
                    text = "Erase and sign in",
                    onClick = onConfirmSignInAgain,
                    enabled = !actionInFlight,
                    loading = actionInFlight,
                )
                Spacer(Modifier.height(12.dp))
                KitOutlinedButton(
                    text = "Keep session",
                    onClick = onCancelSignInAgain,
                    enabled = !actionInFlight,
                )
            } else {
                KitGreenButton(
                    text = "Retry",
                    onClick = onRetry,
                    enabled = !actionInFlight,
                    loading = actionInFlight,
                )
                Spacer(Modifier.height(12.dp))
                KitOutlinedButton(
                    text = "Sign in again",
                    onClick = onRequestSignInAgain,
                    enabled = !actionInFlight,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!discardConfirmationRequested) {
                Text(
                    "Your wallet remains locked until the session is safely restored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
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

private fun Intent.takeAuthorizedIncomingCallLaunch(
    authorizer: IncomingCallLaunchAuthorizer,
    currentSession: SessionFence?,
): AuthorizedIncomingCallLaunch? {
    if (action != ACTION_OPEN_AUTHORIZED_INCOMING_CALL) return null
    val token = getStringExtra(EXTRA_INCOMING_CALL_AUTHORIZATION)
    // Consume the entire private hand-off before validating it. Neither a rejected token nor any
    // attacker-supplied companion extras may remain available to another routing branch.
    replaceExtras(null as Bundle?)
    data = null
    action = null
    return authorizer.consume(token, currentSession)
}

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

private fun Intent.takeIncomingTextShare(context: android.content.Context): IncomingTextShareRequest? {
    if (action != ACTION_OPEN_TEXT_SHARE) return null
    val token = getStringExtra(EXTRA_TEXT_SHARE_TOKEN).orEmpty()

    // Do not leave even the opaque one-time hand-off token on the Activity Intent. The actual
    // shared text never enters this Intent; the opaque token claims its private durable manifest.
    removeExtra(EXTRA_TEXT_SHARE_TOKEN)
    action = null
    return token.takeIf(String::isNotBlank)?.let {
        IncomingTextShareStore.claim(context, it)
    }
}

/**
 * Takes one validated external navigation route from this Activity Intent.
 *
 * MainActivity is single-top for call notifications. Leaving the route on its retained Intent
 * lets a later Activity recreation replay an already-consumed call over whatever screen the user
 * opened next. Clear the source before navigation; [MainActivity.onSaveInstanceState] retains only
 * a route that is still legitimately waiting for session/capability readiness.
 */
@VisibleForTesting
internal fun Intent.takeKitDeepLink(): String? {
    val suppliedCallExtras = CALL_PAYLOAD_KEYS.any(::hasExtra)
    CALL_PAYLOAD_KEYS.forEach(::removeExtra)
    dataString?.let { raw ->
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val isUntrustedCallRoute = uri?.let {
            it.scheme == "kitwallet" && it.host == "call" && it.path == "/incoming"
        } == true
        if (isUntrustedCallRoute) {
            data = null
            action = null
            return null
        }
        val isKycReturn = uri?.let {
            it.scheme == "kitwallet" &&
                it.host == "kyc" &&
                it.path == "/status" &&
                it.query == null &&
                it.fragment == null
        } == true
        val returnLink = ActiveCallReturnLink.fromDeepLink(raw)
        val claimLink = PaymentClaimLink.fromDeepLink(raw)
        val canonicalRoute = when {
            isKycReturn -> KYC_STATUS_DEEP_LINK
            // The ongoing-call notification: return to the call the app is already in.
            returnLink != null -> returnLink.deepLinkUri()
            // A claim alert: the link is the locally reconstructed one and the group hints ride
            // as extras this app itself validated; a hint that no longer parses drops the route.
            claimLink != null -> claimLink.withExtraHints(
                conversationId = getStringExtra(PaymentClaimAlert.EXTRA_CONVERSATION_HINT),
                groupPaymentId = getStringExtra(PaymentClaimAlert.EXTRA_GROUP_PAYMENT_HINT),
            )?.deepLinkUri()
            else -> null
        }
        if (canonicalRoute != null) {
            data = null
            CLAIM_HINT_KEYS.forEach(::removeExtra)
            action = null
            return canonicalRoute
        }
        return null
    }
    if (suppliedCallExtras) action = null
    return null
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

private val CLAIM_HINT_KEYS = listOf(
    PaymentClaimAlert.EXTRA_CONVERSATION_HINT,
    PaymentClaimAlert.EXTRA_GROUP_PAYMENT_HINT,
)

private const val STATE_PENDING_DEEP_LINK = "kit.pending_deep_link"
private const val KYC_STATUS_DEEP_LINK = "kitwallet://kyc/status"

private const val STATE_CALL_ID = "kit.incoming_call.id"
private const val STATE_CALL_PURPOSE = "kit.incoming_call.purpose"
private const val STATE_CALL_SESSION_ID = "kit.incoming_call.session_id"
private const val STATE_CALL_CACHE_SCOPE = "kit.incoming_call.cache_scope"
private const val STATE_CALL_ACCOUNT_ID = "kit.incoming_call.account_id"
private const val STATE_CALL_EXPIRES_AT = "kit.incoming_call.expires_at"
private const val STATE_CALL_RING_EXPIRES_AT = "kit.incoming_call.ring_expires_at"
private const val STATE_CALL_PENDING_NAVIGATION = "kit.incoming_call.pending_navigation"

private data class RestoredIncomingCallLaunch(
    val launch: AuthorizedIncomingCallLaunch,
    val pendingNavigation: Boolean,
)

private fun Bundle.saveAuthorizedIncomingCall(
    launch: AuthorizedIncomingCallLaunch,
    pendingNavigation: Boolean,
) {
    putString(STATE_CALL_ID, launch.callId)
    putString(STATE_CALL_PURPOSE, launch.purpose.name)
    putString(STATE_CALL_SESSION_ID, launch.session.sessionId)
    putString(STATE_CALL_CACHE_SCOPE, launch.session.cacheScopeId)
    putString(STATE_CALL_ACCOUNT_ID, launch.session.accountId)
    putString(STATE_CALL_EXPIRES_AT, launch.expiresAt.toString())
    putString(STATE_CALL_RING_EXPIRES_AT, launch.ringExpiresAt.toString())
    putBoolean(STATE_CALL_PENDING_NAVIGATION, pendingNavigation)
}

private fun Bundle.restoreAuthorizedIncomingCall(
    currentSession: SessionFence?,
    now: Instant,
): RestoredIncomingCallLaunch? {
    val callId = canonicalIncomingCallId(getString(STATE_CALL_ID)) ?: return null
    val purpose = runCatching {
        IncomingCallLaunchPurpose.valueOf(getString(STATE_CALL_PURPOSE).orEmpty())
    }.getOrNull() ?: return null
    val expectedSession = SessionFence(
        sessionId = getString(STATE_CALL_SESSION_ID).orEmpty(),
        cacheScopeId = getString(STATE_CALL_CACHE_SCOPE).orEmpty(),
        accountId = getString(STATE_CALL_ACCOUNT_ID),
    )
    if (currentSession == null || currentSession != expectedSession) return null
    val expiresAt = getString(STATE_CALL_EXPIRES_AT)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return null
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(Duration.ofMinutes(1)))) return null
    val ringExpiresAt = getString(STATE_CALL_RING_EXPIRES_AT)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?.takeIf { it.isAfter(now) }
        ?: return null
    return RestoredIncomingCallLaunch(
        launch = AuthorizedIncomingCallLaunch(
            callId,
            purpose,
            expectedSession,
            ringExpiresAt,
            expiresAt,
        ),
        pendingNavigation = getBoolean(STATE_CALL_PENDING_NAVIGATION, false),
    )
}
