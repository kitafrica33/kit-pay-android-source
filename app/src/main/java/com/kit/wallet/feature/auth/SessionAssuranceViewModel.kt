package com.kit.wallet.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.BiometricSigningKey
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.LoginUnlockPinRequest
import com.kit.wallet.data.remote.LoginBiometricAssertionRequest
import com.kit.wallet.data.remote.SessionAssuranceDto
import com.kit.wallet.data.remote.SessionAssuranceSignal
import com.kit.wallet.data.remote.SetPaymentPinRequest
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.CachedSessionAssurance
import com.kit.wallet.data.auth.toCachedSessionAssurance
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.Signature

data class BiometricUnlockRequest(
    val challengeId: String,
    val nonce: String,
    val signingPayload: String,
    val signature: Signature,
)

data class SessionAssuranceUiState(
    val required: Boolean = false,
    val checking: Boolean = false,
    val unlocking: Boolean = false,
    val methods: Set<String> = emptySet(),
    val biometricReady: Boolean = false,
    val biometricRequest: BiometricUnlockRequest? = null,
    val error: String? = null,
    /** The server requires identity verification on this device before any unlock can succeed. */
    val deviceIdentityRequired: Boolean = false,
    /** Raw device-identity status (required/pending/review/failed/verified) for gate copy. */
    val deviceIdentityStatus: String? = null,
)

@HiltViewModel
class SessionAssuranceViewModel @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val biometricKey: BiometricSigningKey? = null,
    lockSignals: SessionAssuranceSignal? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var generation = 0L
    private var lastSessionId: String? = null

    init {
        // Any API call answered with HTTP 428 proves the server considers this login locked, even
        // if an earlier assurance poll believed otherwise. Re-verify immediately so the unlock
        // gate appears instead of every screen failing with the raw unlock error copy.
        lockSignals?.let { signals ->
            viewModelScope.launch {
                signals.locked.collect {
                    if (!mutableState.value.required && refreshJob?.isActive != true) refresh()
                }
            }
        }
    }

    fun reconcile(signedIn: Boolean, supported: Boolean) {
        val sessionId = sessions.current()?.sessionId
        if (!signedIn || !supported || sessionId == null) {
            refreshJob?.cancel()
            generation++
            // This session is deliberately NOT recorded as reconciled: capability discovery may
            // still be in flight, and the first supported call must verify this exact login with
            // the server. Recording it here previously suppressed that verification forever,
            // leaving locked sessions without an unlock prompt.
            lastSessionId = null
            mutableState.value = SessionAssuranceUiState()
            return
        }
        if (sessionId == lastSessionId && (refreshJob?.isActive == true ||
                mutableState.value.required || (!mutableState.value.checking && mutableState.value.error == null))
        ) return
        lastSessionId = sessionId
        refresh()
    }

    fun refresh() {
        val expected = sessions.snapshot()
        if (expected.fence == null || refreshJob?.isActive == true) return
        val requestGeneration = ++generation
        mutableState.value = initialState().copy(checking = true)
        refreshJob = viewModelScope.launch {
            try {
                val assurance = apiCalls.execute { api.sessionAssurance() }.sessionAssurance
                if (requestGeneration == generation && sessions.snapshot() == expected) publish(assurance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    val cached = sessions.current()?.cachedAssurance
                    val serverWithoutAssurance =
                        (error as? KitWalletApiException)?.statusCode == 404
                    mutableState.value = when {
                        // An older service without session-assurance endpoints never locks logins.
                        serverWithoutAssurance -> SessionAssuranceUiState()
                        cached != null -> initialState().copy(checking = false)
                        else -> SessionAssuranceUiState(
                            required = true,
                            error = error.message ?: "Could not verify this session",
                        )
                    }
                }
            }
        }
    }

    /**
     * Creates the account's first wallet PIN from inside the unlock gate. The server treats the
     * first PIN creation as the `pin_setup` unlock method, so a brand-new account that has neither
     * a PIN nor a biometric key can bootstrap itself without leaving the gate.
     */
    fun createPinAndUnlock(pin: String, confirmation: String) {
        if (mutableState.value.unlocking) return
        if (!pin.matches(Regex("^[0-9]{4}$")) || pin != confirmation) {
            mutableState.value = mutableState.value.copy(
                error = "Enter the same four-digit PIN twice",
            )
            return
        }
        val expected = sessions.snapshot()
        if (expected.fence == null) return
        val requestGeneration = ++generation
        mutableState.value = mutableState.value.copy(unlocking = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val result = apiCalls.execute {
                    api.setPaymentPin(
                        SetPaymentPinRequest(pin = pin, pinConfirmation = confirmation),
                    )
                }
                check(result.paymentPinSet == true) { "The wallet PIN was not saved" }
                // Newer services return the fresh assurance with the PIN receipt; older ones are
                // asked directly so the gate reflects the pin_setup unlock without a relaunch.
                val assurance = result.sessionAssurance
                    ?: apiCalls.execute { api.sessionAssurance() }.sessionAssurance
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    publish(assurance)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    mutableState.value = mutableState.value.copy(
                        unlocking = false,
                        error = error.message ?: "The wallet PIN could not be created",
                    )
                }
            }
        }
    }

    fun unlockWithPin(pin: String) {
        if (!pin.matches(Regex("^[0-9]{4}$")) || mutableState.value.unlocking) return
        val expected = sessions.snapshot()
        if (expected.fence == null) return
        val requestGeneration = ++generation
        mutableState.value = mutableState.value.copy(unlocking = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val assurance = apiCalls.execute {
                    api.unlockSessionWithPin(LoginUnlockPinRequest(pin))
                }.sessionAssurance
                if (requestGeneration == generation && sessions.snapshot() == expected) publish(assurance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    mutableState.value = mutableState.value.copy(
                        unlocking = false,
                        error = error.message ?: "The PIN could not unlock this session",
                    )
                }
            }
        }
    }

    fun requestBiometricUnlock() {
        val expected = sessions.snapshot()
        val accountId = expected.fence?.accountId ?: return
        val signingKey = biometricKey ?: return
        if (!mutableState.value.biometricReady || mutableState.value.unlocking) return
        val requestGeneration = ++generation
        mutableState.value = mutableState.value.copy(unlocking = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val challenge = apiCalls.execute { api.createLoginBiometricChallenge() }
                check(Instant.parse(challenge.expiresAt).isAfter(Instant.now())) {
                    "The biometric challenge has expired"
                }
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    mutableState.value = mutableState.value.copy(
                        biometricRequest = BiometricUnlockRequest(
                            challenge.challengeId,
                            challenge.nonce,
                            challenge.signingPayload,
                            signingKey.signature(accountId),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    mutableState.value = mutableState.value.copy(
                        unlocking = false,
                        biometricRequest = null,
                        error = error.message ?: "Biometric unlock could not start",
                    )
                }
            }
        }
    }

    fun completeBiometricUnlock(request: BiometricUnlockRequest, authenticated: Signature) {
        if (mutableState.value.biometricRequest !== request) return
        val expected = sessions.snapshot()
        if (expected.fence == null) return
        val requestGeneration = ++generation
        mutableState.value = mutableState.value.copy(biometricRequest = null)
        refreshJob = viewModelScope.launch {
            try {
                val signature = checkNotNull(biometricKey) { "Biometric signing is unavailable" }
                    .sign(authenticated, request.signingPayload)
                val result = apiCalls.execute {
                    api.assertLoginBiometricChallenge(
                        LoginBiometricAssertionRequest(request.challengeId, request.nonce, signature),
                    )
                }
                check(result.method.equals("biometric_signature", ignoreCase = true)) {
                    "The server did not confirm biometric unlock"
                }
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    publish(result.sessionAssurance)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation && sessions.snapshot() == expected) {
                    mutableState.value = mutableState.value.copy(
                        unlocking = false,
                        error = error.message ?: "Biometric unlock failed",
                    )
                }
            }
        }
    }

    fun cancelBiometricUnlock(message: String? = null) {
        if (mutableState.value.biometricRequest == null) return
        mutableState.value = mutableState.value.copy(
            unlocking = false,
            biometricRequest = null,
            error = message,
        )
    }

    private suspend fun publish(assurance: SessionAssuranceDto) {
        val expected = sessions.current()?.fence() ?: return
        if (!sessions.updateCachedAssurance(expected, assurance.toCachedSessionAssurance())) return
        val identityReady = assurance.deviceIdentityReady()
        val full = assurance.grantsFullAccess()
        mutableState.value = SessionAssuranceUiState(
            required = !full,
            methods = assurance.loginUnlock.methods.map(String::lowercase).toSet(),
            biometricReady = sessions.current()?.accountId?.let {
                runCatching { biometricKey?.hasKey(it) == true }.getOrDefault(false)
            } == true,
            deviceIdentityRequired = !identityReady,
            deviceIdentityStatus = assurance.deviceIdentity.status,
        )
    }

    private fun initialState(): SessionAssuranceUiState {
        val session = sessions.current() ?: return SessionAssuranceUiState()
        val cached = session.cachedAssurance ?: return SessionAssuranceUiState(
            required = true,
            checking = true,
        )
        val identityReady = !cached.deviceIdentityRequired ||
            cached.deviceIdentityStatus.equals("verified", ignoreCase = true)
        val full = cached.grantsFullAccess()
        return SessionAssuranceUiState(
            required = !full,
            methods = cached.loginUnlockMethods.map(String::lowercase).toSet(),
            biometricReady = session.accountId?.let {
                runCatching { biometricKey?.hasKey(it) == true }.getOrDefault(false)
            } == true,
            deviceIdentityRequired = !identityReady,
            deviceIdentityStatus = cached.deviceIdentityStatus,
        )
    }
}

internal fun SessionAssuranceDto.deviceIdentityReady(): Boolean =
    !deviceIdentity.required || deviceIdentity.status.equals("verified", ignoreCase = true)

internal fun SessionAssuranceDto.grantsFullAccess(): Boolean =
    access.equals("full", ignoreCase = true) && deviceIdentityReady() &&
        (!loginUnlock.required || loginUnlock.status.equals("unlocked", ignoreCase = true))

internal fun CachedSessionAssurance.grantsFullAccess(): Boolean =
    access.equals("full", ignoreCase = true) &&
        (!deviceIdentityRequired || deviceIdentityStatus.equals("verified", ignoreCase = true)) &&
        (!loginUnlockRequired || loginUnlockStatus.equals("unlocked", ignoreCase = true))
