package com.kit.wallet.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.messaging.SecureMessagingContract
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.realtime.KitNetworkEvent
import com.kit.wallet.data.realtime.KitNetworkSource
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val CAPABILITIES_FOREGROUND_REFRESH_INTERVAL_MILLIS = 5 * 60 * 1_000L

data class AppCapabilities(
    val features: Map<String, Boolean> = emptyMap(),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    /**
     * The last answer the server actually gave, kept across a *transport* failure only.
     *
     * There is a real difference between "the server says messaging is off for you" and "we could
     * not ask" — and the app used to treat them identically, so losing the network took the
     * Messages tab off the bottom bar and bounced anyone reading a chat back to Home. Discovery
     * stays fail-closed for everything that acts ([enabled]); this exists so the surfaces that
     * merely *show* what is already on the device can keep showing it. Cleared at a session
     * boundary, where another account's view must never survive.
     */
    val retainedFeatures: Map<String, Boolean> = emptyMap(),
    val secureMessagingClientReady: Boolean = false,
    val messagingProtocolReady: Boolean = false,
    val messagingProtocolVersion: String? = null,
    val messagingProtocolSuite: String? = null,
    val messagingProtocolPostQuantum: Boolean? = null,
    val pushMessagingConfigured: Boolean = false,
    val biometricTokensAvailable: Boolean = false,
    // The current scanner is presentation-only: it has no CameraX/QR decoder integration.
    val qrScannerClientReady: Boolean = false,
) {
    fun enabled(feature: String): Boolean = loaded && !loadFailed && features[feature] == true

    fun allEnabled(vararg required: String): Boolean = required.all(::enabled)

    /**
     * Whether a feature was enabled the last time the server answered — which, when discovery is
     * currently failing, is the most truthful thing this device knows.
     *
     * For read-only surfaces only: a tab, a list of what is already stored locally, a route the
     * user is standing on. Anything that transacts, creates or transmits must use [enabled],
     * which stays fail-closed. Once a refresh succeeds this collapses back to [enabled] exactly,
     * so a feature the server has genuinely turned off disappears on the next successful poll.
     */
    fun lastKnownEnabled(feature: String): Boolean =
        enabled(feature) || (loadFailed && retainedFeatures[feature] == true)

    /**
     * Whether the user should be able to discover the messaging surface. The entry remains
     * visible when the backend advertises messaging even if this build cannot safely exchange
     * messages yet; the Chats screen then explains the end-to-end-encryption requirement — and
     * it survives an offline capability refresh, because the conversations behind it are stored
     * on this device and remain readable with no server at all.
     */
    val messagingEntryVisible: Boolean
        get() = lastKnownEnabled(KitFeature.MESSAGING)

    val messagingServerCompatible: Boolean
        get() = enabled(KitFeature.MESSAGING) &&
            SecureMessagingContract.matchesServerAdvertisement(
                ready = messagingProtocolReady,
                version = messagingProtocolVersion,
                suite = messagingProtocolSuite,
                postQuantum = messagingProtocolPostQuantum,
            )

    val messagingUsable: Boolean
        get() = messagingServerCompatible && secureMessagingClientReady

    /**
     * Wallet-backed service surfaces only become usable when both the service rollout and the
     * wallet they operate on are enabled. Keeping these dependencies here gives the dashboard,
     * navigation guard, and deep-link handling one server-driven source of truth.
     */
    val billPaymentsUsable: Boolean
        get() = allEnabled(KitFeature.WALLETS, KitFeature.BILLS)

    val airtimeUsable: Boolean
        get() = allEnabled(KitFeature.WALLETS, KitFeature.AIRTIME)

    val bankTransfersUsable: Boolean
        get() = allEnabled(KitFeature.WALLETS, KitFeature.BANK_TRANSFERS)

    val bankDepositsUsable: Boolean
        get() = allEnabled(KitFeature.WALLETS, KitFeature.BANK_DEPOSITS)

    val bankUsable: Boolean
        get() = bankTransfersUsable || bankDepositsUsable

    val mobileMoneyUsable: Boolean
        get() = allEnabled(KitFeature.WALLETS, KitFeature.MOBILE_MONEY)

    val qrPaymentsUsable: Boolean
        get() = allEnabled(
            KitFeature.WALLETS,
            KitFeature.MERCHANT_PAYMENTS,
            KitFeature.QR_PAYMENTS,
        ) &&
            qrScannerClientReady

    /**
     * Central navigation guard. Unknown feature-backed screens are not inferred from a route;
     * every route listed here mirrors the backend feature names above.
     */
    fun routeUsable(route: String?): Boolean {
        if (
            route == Dest.SEND ||
            route == Dest.SEND_ROUTE ||
            route?.startsWith("${Dest.SEND}?contactId=") == true
        ) {
            return allEnabled(KitFeature.WALLETS, KitFeature.INTERNAL_TRANSFERS)
        }
        return when (route) {
            // Reading is not exchanging. A conversation's transcript, its title and its member
            // list all come out of this device's own encrypted store, so the routes that only
            // display them stay open with no session and no network — the composer inside gates
            // itself. Being pulled out of a chat you are reading because a key revalidation blipped
            // is a bug, not a safety property.
            Dest.CHATS, Dest.CONVERSATION, Dest.GROUP_PROFILE -> messagingEntryVisible
            // These three do exchange: starting a conversation, picking someone to start it with,
            // and changing who is in a group are all server-authenticated actions that need a
            // ready end-to-end session before they can be honoured.
            Dest.CONTACTS, Dest.NEW_GROUP, Dest.GROUP_ADD, Dest.GROUP_DESCRIPTION ->
                messagingUsable
            // Including an in-progress call: a failed capability poll must never hang one up.
            Dest.CALLS, Dest.CALL_CONTACTS, Dest.VOICE_CALL, Dest.VIDEO_CALL, Dest.INCOMING_CALL ->
                lastKnownEnabled(KitFeature.CALLS)
            Dest.BILLS, Dest.BILL_PAY -> billPaymentsUsable
            Dest.AIRTIME -> airtimeUsable
            Dest.BANK -> bankUsable
            Dest.MOBILE_MONEY -> mobileMoneyUsable
            // Receive shares the authenticated user's existing Kit tag/phone; it does not depend
            // on the still-unimplemented QR scanner or a separate client protocol.
            Dest.RECEIVE -> enabled(KitFeature.WALLETS)
            Dest.REQUEST -> allEnabled(KitFeature.WALLETS, KitFeature.PAYMENT_REQUESTS)
            Dest.SCAN -> qrPaymentsUsable
            Dest.TRANSACTIONS, Dest.TX_DETAIL -> enabled(KitFeature.WALLETS)
            Dest.KYC -> enabled(KitFeature.KYC)
            Dest.REGISTER -> enabled(KitFeature.EMAIL_REGISTRATION)
            Dest.FORGOT_PASSWORD -> enabled(KitFeature.EMAIL_RECOVERY)
            else -> true
        }
    }
}

@HiltViewModel
class AppCapabilitiesViewModel @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    chatRepository: ChatRepository,
    pushMessagingTransport: PushMessagingTransport,
    networkSource: KitNetworkSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AppCapabilities(
            secureMessagingClientReady = chatRepository.readiness.value,
            pushMessagingConfigured = pushMessagingTransport.configured,
        ),
    )
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var refreshGeneration: Long = 0
    private var observedSecureMessagingClientReady = chatRepository.readiness.value

    init {
        viewModelScope.launch {
            chatRepository.readiness.collectLatest { ready ->
                val becameReady = ready && !observedSecureMessagingClientReady
                observedSecureMessagingClientReady = ready
                mutableState.update { it.copy(secureMessagingClientReady = ready) }
                if (becameReady) {
                    // Local activation has just passed its own fresh server capability check.
                    // Replace any older UI discovery response from before a readiness rollout.
                    startRefresh(cancelInFlight = true, invalidateSnapshot = false)
                }
            }
        }
        viewModelScope.launch {
            // A failed poll otherwise sits fail-closed for up to five minutes after the network
            // has already come back — which is precisely the window a user spends walking out of
            // a lift and wondering why half the app is missing. A new default network is new
            // information, so retry against it at once. Only when the previous attempt actually
            // failed: a working session does not need re-asking on every Wi-Fi handover.
            networkSource.events.collect { event ->
                if (event == KitNetworkEvent.Available && mutableState.value.loadFailed) {
                    startRefresh(cancelInFlight = true, invalidateSnapshot = false)
                }
            }
        }
        networkSource.start()
        refresh()
    }

    fun refresh() {
        startRefresh(cancelInFlight = false, invalidateSnapshot = false)
    }

    /**
     * Refreshes immediately whenever the app enters the foreground, then periodically while it
     * remains foregrounded. The lifecycle caller owns cancellation, so no polling survives pause.
     */
    suspend fun refreshWhileForeground(
        intervalMillis: Long = CAPABILITIES_FOREGROUND_REFRESH_INTERVAL_MILLIS,
    ) {
        require(intervalMillis > 0) { "Capability refresh interval must be positive" }
        refresh()
        while (true) {
            delay(intervalMillis)
            refresh()
        }
    }

    /**
     * Authentication changes alter the response of the optional-auth capabilities endpoint.
     * Fence and cancel discovery from the previous session before loading the new session's view.
     */
    fun onSessionChanged() {
        startRefresh(cancelInFlight = true, invalidateSnapshot = true)
    }

    private fun startRefresh(cancelInFlight: Boolean, invalidateSnapshot: Boolean) {
        if (!cancelInFlight && refreshJob?.isActive == true) return

        if (cancelInFlight) refreshJob?.cancel()
        val generation = ++refreshGeneration

        if (invalidateSnapshot) {
            // A session boundary must never present another account's capabilities. Same-session
            // foreground refreshes retain the last successful snapshot until the server replies,
            // avoiding periodic navigation churn; either a false response or a failure still
            // replaces it with a fail-closed state below.
            mutableState.update {
                it.copy(
                    features = emptyMap(),
                    retainedFeatures = emptyMap(),
                    loaded = false,
                    loadFailed = false,
                    messagingProtocolReady = false,
                    messagingProtocolVersion = null,
                    messagingProtocolSuite = null,
                    messagingProtocolPostQuantum = null,
                    biometricTokensAvailable = false,
                )
            }
        }
        refreshJob = viewModelScope.launch {
            try {
                val response = apiCalls.execute { api.capabilities() }
                if (generation != refreshGeneration) return@launch
                val features = response.features
                    .orEmpty()
                    .mapValues { (_, enabled) -> enabled == true }
                val messagingProtocol = response.protocols?.messaging
                val biometricTokens = response.authentication?.get("biometric_tokens") == true
                mutableState.update {
                    it.copy(
                        features = features,
                        retainedFeatures = features,
                        loaded = true,
                        loadFailed = false,
                        messagingProtocolReady = messagingProtocol?.ready == true,
                        messagingProtocolVersion = messagingProtocol?.version,
                        messagingProtocolSuite = messagingProtocol?.suite,
                        messagingProtocolPostQuantum = messagingProtocol?.postQuantum,
                        biometricTokensAvailable = biometricTokens,
                    )
                }
            } catch (cancelled: CancellationException) {
                // Structured cancellation must not be converted into a completed failed load.
                throw cancelled
            } catch (_: Exception) {
                if (generation != refreshGeneration) return@launch
                // Capability discovery is fail-closed: unavailable services stay hidden until
                // a later successful refresh. `retainedFeatures` is deliberately left alone —
                // it is not consulted by `enabled`, only by the display-only surfaces that would
                // otherwise blank out the user's own locally stored chats and call history.
                mutableState.update {
                    it.copy(
                        features = emptyMap(),
                        loaded = true,
                        loadFailed = true,
                        messagingProtocolReady = false,
                        messagingProtocolVersion = null,
                        messagingProtocolSuite = null,
                        messagingProtocolPostQuantum = null,
                        biometricTokensAvailable = false,
                    )
                }
            }
        }
    }
}
