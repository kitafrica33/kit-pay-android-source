package com.kit.wallet.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.BiometricSigningKey
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.LoginUnlockPinRequest
import com.kit.wallet.data.remote.LoginBiometricAssertionRequest
import com.kit.wallet.data.remote.SessionAssuranceDto
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
)

@HiltViewModel
class SessionAssuranceViewModel @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val biometricKey: BiometricSigningKey,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var generation = 0L
    private var lastSessionId: String? = null

    fun reconcile(signedIn: Boolean, supported: Boolean) {
        val sessionId = sessions.current()?.sessionId
        if (!signedIn || !supported || sessionId == null) {
            refreshJob?.cancel()
            generation++
            lastSessionId = sessionId
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
                    mutableState.value = if (cached != null) {
                        initialState().copy(checking = false)
                    } else {
                        SessionAssuranceUiState(
                            required = true,
                            error = error.message ?: "Could not verify this session",
                        )
                    }
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
                            biometricKey.signature(accountId),
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
                val signature = biometricKey.sign(authenticated, request.signingPayload)
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
            biometricReady = sessions.current()?.accountId?.let(biometricKey::hasKey) == true,
            error = if (!identityReady) "Identity verification is required for this session." else null,
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
                runCatching { biometricKey.hasKey(it) }.getOrDefault(false)
            } == true,
            error = if (!identityReady) "Identity verification is required for this session." else null,
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
