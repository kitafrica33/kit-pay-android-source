package com.kit.wallet.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.messaging.SecureMessagingContract
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppCapabilities(
    val features: Map<String, Boolean> = emptyMap(),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val secureMessagingClientReady: Boolean = false,
    val messagingProtocolReady: Boolean = false,
    val messagingProtocolVersion: String? = null,
    val messagingProtocolSuite: String? = null,
    val messagingProtocolPostQuantum: Boolean? = null,
    val pushMessagingConfigured: Boolean = false,
    // The current scanner is presentation-only: it has no CameraX/QR decoder integration.
    val qrScannerClientReady: Boolean = false,
) {
    fun enabled(feature: String): Boolean = loaded && !loadFailed && features[feature] == true

    fun allEnabled(vararg required: String): Boolean = required.all(::enabled)

    /**
     * Whether the user should be able to discover the messaging surface. The entry remains
     * visible when the backend advertises messaging even if this build cannot safely exchange
     * messages yet; the Chats screen then explains the end-to-end-encryption requirement.
     */
    val messagingEntryVisible: Boolean
        get() = enabled(KitFeature.MESSAGING)

    val messagingServerCompatible: Boolean
        get() = messagingEntryVisible &&
            SecureMessagingContract.matchesServerAdvertisement(
                ready = messagingProtocolReady,
                version = messagingProtocolVersion,
                suite = messagingProtocolSuite,
                postQuantum = messagingProtocolPostQuantum,
            )

    val messagingUsable: Boolean
        get() = messagingServerCompatible && secureMessagingClientReady

    val qrPaymentsUsable: Boolean
        get() = allEnabled(KitFeature.MERCHANT_PAYMENTS, KitFeature.QR_PAYMENTS) &&
            qrScannerClientReady

    /**
     * Central navigation guard. Unknown feature-backed screens are not inferred from a route;
     * every route listed here mirrors the backend feature names above.
     */
    fun routeUsable(route: String?): Boolean = when (route) {
        // The top-level screen is safe to expose because its unavailable state never reads or
        // sends plaintext. Conversation creation and content remain closed until E2EE is ready.
        Dest.CHATS -> messagingEntryVisible
        Dest.CONVERSATION, Dest.CONTACTS -> messagingUsable
        Dest.CALLS, Dest.CALL_CONTACTS, Dest.VOICE_CALL, Dest.VIDEO_CALL, Dest.INCOMING_CALL ->
            enabled(KitFeature.CALLS)
        Dest.BILLS, Dest.BILL_PAY -> enabled(KitFeature.BILLS)
        Dest.AIRTIME -> enabled(KitFeature.AIRTIME)
        Dest.BANK -> enabled(KitFeature.BANK_TRANSFERS)
        Dest.MOBILE_MONEY -> enabled(KitFeature.MOBILE_MONEY)
        Dest.SEND -> allEnabled(KitFeature.WALLETS, KitFeature.INTERNAL_TRANSFERS)
        // Receive shares the authenticated user's existing Kit tag/phone; it does not depend on
        // the still-unimplemented QR scanner or a separate client protocol.
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

@HiltViewModel
class AppCapabilitiesViewModel @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    chatRepository: ChatRepository,
    pushMessagingTransport: PushMessagingTransport,
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
                    startRefresh(cancelInFlight = true)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        startRefresh(cancelInFlight = false)
    }

    /**
     * Authentication changes alter the response of the optional-auth capabilities endpoint.
     * Fence and cancel discovery from the previous session before loading the new session's view.
     */
    fun onSessionChanged() {
        startRefresh(cancelInFlight = true)
    }

    private fun startRefresh(cancelInFlight: Boolean) {
        if (!cancelInFlight && refreshJob?.isActive == true) return

        if (cancelInFlight) refreshJob?.cancel()
        val generation = ++refreshGeneration

        // Never continue presenting a capability from an older discovery result while a new
        // session is checking whether that feature is still enabled.
        mutableState.update {
            it.copy(
                features = emptyMap(),
                loaded = false,
                loadFailed = false,
                messagingProtocolReady = false,
                messagingProtocolVersion = null,
                messagingProtocolSuite = null,
                messagingProtocolPostQuantum = null,
            )
        }
        refreshJob = viewModelScope.launch {
            try {
                val response = apiCalls.execute { api.capabilities() }
                if (generation != refreshGeneration) return@launch
                val features = response.features
                    .orEmpty()
                    .mapValues { (_, enabled) -> enabled == true }
                val messagingProtocol = response.protocols?.messaging
                mutableState.update {
                    it.copy(
                        features = features,
                        loaded = true,
                        loadFailed = false,
                        messagingProtocolReady = messagingProtocol?.ready == true,
                        messagingProtocolVersion = messagingProtocol?.version,
                        messagingProtocolSuite = messagingProtocol?.suite,
                        messagingProtocolPostQuantum = messagingProtocol?.postQuantum,
                    )
                }
            } catch (cancelled: CancellationException) {
                // Structured cancellation must not be converted into a completed failed load.
                throw cancelled
            } catch (_: Exception) {
                if (generation != refreshGeneration) return@launch
                // Capability discovery is fail-closed: unavailable services stay hidden until
                // a later successful refresh.
                mutableState.update {
                    it.copy(
                        features = emptyMap(),
                        loaded = true,
                        loadFailed = true,
                        messagingProtocolReady = false,
                        messagingProtocolVersion = null,
                        messagingProtocolSuite = null,
                        messagingProtocolPostQuantum = null,
                    )
                }
            }
        }
    }
}
