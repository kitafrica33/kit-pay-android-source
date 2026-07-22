package com.kit.wallet.data.messaging

import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SecureMessagingTransportValidator
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Boundary used by opaque FCM wake-ups. A reviewed E2EE repository will eventually implement
 * authenticated ciphertext sync, ratchet updates and encrypted local persistence atomically.
 */
interface SecureMessagingSyncEngine {
    /** Implementation availability, not whether a process-local active session already exists. */
    val isReady: Boolean

    suspend fun synchronize()
}

@Singleton
class SecureMessagingUnavailableSyncEngine @Inject constructor() : SecureMessagingSyncEngine {
    override val isReady: Boolean = false

    override suspend fun synchronize(): Nothing =
        error("Secure messaging sync requires the reviewed end-to-end encryption engine")
}

class SecureMessagingAuthenticationEpochChangedException(message: String) :
    IllegalStateException(message)

class SecureMessagingStateNotReadyException(message: String) : IOException(message)

private data class SecureMessagingStateAvailability(
    val available: Boolean,
    val sessionEpoch: String?,
)

internal suspend fun awaitSecureMessagingStateAvailability(
    expectedSessionEpoch: String,
    stateAvailable: Flow<Boolean>,
    sessions: Flow<SessionTokens?>,
    timeoutMillis: Long = 10_000L,
) {
    require(timeoutMillis > 0) { "Secure-messaging state wait must be bounded" }
    val resolved = withTimeoutOrNull(timeoutMillis) {
        combine(stateAvailable, sessions) { available, session ->
            SecureMessagingStateAvailability(available, session?.sessionId)
        }.first { state ->
            state.available || state.sessionEpoch != expectedSessionEpoch
        }
    } ?: throw SecureMessagingStateNotReadyException(
        "Secure messaging state is still opening for the restored session",
    )
    if (resolved.sessionEpoch != expectedSessionEpoch) {
        throw SecureMessagingAuthenticationEpochChangedException(
            "Authentication epoch changed while secure messaging state was opening",
        )
    }
    check(resolved.available) { "Secure messaging state did not become available" }
}

/** Resolves an authenticated epoch without trusting stale profile/device cache projections. */
@Singleton
internal class SecureMessagingAuthBindingResolver @Inject constructor(
    private val sessions: SessionStore,
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val deviceIdentity: DeviceIdentityProvider,
) {
    suspend fun resolve(expectedSessionEpoch: String? = null): SecureMessagingSessionBinding {
        val sessionEpoch = currentSessionEpoch()
        if (expectedSessionEpoch != null && expectedSessionEpoch != sessionEpoch) {
            throw SecureMessagingAuthenticationEpochChangedException(
                "Authentication epoch changed before resolving secure messaging",
            )
        }
        val profile = apiCalls.execute { api.profile() }
        assertCurrent(sessionEpoch)
        require(UUID_PATTERN.matches(profile.id)) { "Invalid authenticated profile ID" }

        val device = SecureMessagingTransportValidator.requireCurrentServerDevice(
            apiCalls.execute { api.devices() },
        )
        assertCurrent(sessionEpoch)
        val installationId = deviceIdentity.registration().installationId
        val binding = SecureMessagingSessionBinding(
            sessionEpoch = sessionEpoch,
            userId = profile.id,
            serverDeviceId = device.id,
            installationId = installationId,
        )
        assertCurrent(binding)
        return binding
    }

    fun assertCurrent(binding: SecureMessagingSessionBinding) {
        assertCurrent(binding.sessionEpoch)
        check(deviceIdentity.registration().installationId == binding.installationId) {
            "Secure-messaging installation identity changed"
        }
    }

    fun currentSessionEpoch(): String = sessions.current()?.sessionId?.also {
        require(it.isNotBlank()) { "Authenticated session omitted its epoch" }
    } ?: throw SecureMessagingAuthenticationEpochChangedException(
        "Secure messaging requires an authenticated session",
    )

    private fun assertCurrent(expectedSessionEpoch: String) {
        if (sessions.current()?.sessionId != expectedSessionEpoch) {
            throw SecureMessagingAuthenticationEpochChangedException(
                "Authentication epoch changed while resolving secure messaging",
            )
        }
    }

    private companion object {
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}

/** WorkManager-facing engine that can bootstrap a fresh process before a registry exists. */
@Singleton
class RealSecureMessagingSyncEngine @Inject internal constructor(
    private val bindingResolver: SecureMessagingAuthBindingResolver,
    private val activation: SecureMessagingActivationCoordinator,
    private val processor: SecureMessagingEventProcessor,
    private val sessions: SessionStore,
    private val sessionLifecycle: SecureMessagingSessionLifecycle,
    private val authRepository: AuthRepository,
) : SecureMessagingSyncEngine {
    private sealed interface PendingEnrollmentRecovery {
        val sessionEpoch: String
        val activationFence: SecureMessagingSessionFence

        data class RemoteReset(
            override val sessionEpoch: String,
            override val activationFence: SecureMessagingSessionFence,
            val target: SecureMessagingEnrollmentResetTarget,
        ) : PendingEnrollmentRecovery

        data class LocalReset(
            override val sessionEpoch: String,
            override val activationFence: SecureMessagingSessionFence,
        ) : PendingEnrollmentRecovery

        data class FreshAuthentication(
            override val sessionEpoch: String,
            override val activationFence: SecureMessagingSessionFence,
        ) : PendingEnrollmentRecovery
    }

    private val synchronizationMutex = Mutex()
    private var pendingEnrollmentRecovery: PendingEnrollmentRecovery? = null

    override val isReady: Boolean = true

    override suspend fun synchronize() = synchronizationMutex.withLock {
        while (true) {
            val sessionEpoch = bindingResolver.currentSessionEpoch()
            pendingEnrollmentRecovery?.let { pending ->
                if (pending.sessionEpoch == sessionEpoch) {
                    completePendingEnrollmentRecovery(pending)
                    pendingEnrollmentRecovery = null
                    return@let
                }
                pendingEnrollmentRecovery = null
            }
            if (pendingEnrollmentRecovery == null) {
                awaitSecureMessagingStateAvailability(
                    expectedSessionEpoch = sessionEpoch,
                    stateAvailable = sessionLifecycle.stateAvailable,
                    sessions = sessions.session,
                )
            } else {
                continue
            }
            val binding = bindingResolver.resolve(sessionEpoch)
            bindingResolver.assertCurrent(binding)
            val active = try {
                activation.ensureActivated(binding)
            } catch (required: SecureMessagingReauthenticationRequiredException) {
                pendingEnrollmentRecovery = PendingEnrollmentRecovery.RemoteReset(
                    sessionEpoch = binding.sessionEpoch,
                    activationFence = required.activationFence,
                    target = required.target,
                )
                continue
            } catch (required: SecureMessagingLocalEnrollmentResetRequiredException) {
                pendingEnrollmentRecovery = PendingEnrollmentRecovery.LocalReset(
                    sessionEpoch = binding.sessionEpoch,
                    activationFence = required.activationFence,
                )
                continue
            } catch (required: SecureMessagingFreshAuthenticationRequiredException) {
                pendingEnrollmentRecovery = PendingEnrollmentRecovery.FreshAuthentication(
                    sessionEpoch = binding.sessionEpoch,
                    activationFence = required.activationFence,
                )
                continue
            }
            check(active.binding == binding) {
                "Secure-messaging activation returned another authentication epoch"
            }
            bindingResolver.assertCurrent(binding)
            try {
                processor.synchronize(active.transport)
            } catch (required: SecureMessagingReauthenticationRequiredException) {
                pendingEnrollmentRecovery = PendingEnrollmentRecovery.RemoteReset(
                    sessionEpoch = binding.sessionEpoch,
                    activationFence = required.activationFence,
                    target = required.target,
                )
                continue
            } catch (required: SecureMessagingLocalEnrollmentResetRequiredException) {
                pendingEnrollmentRecovery = PendingEnrollmentRecovery.LocalReset(
                    sessionEpoch = binding.sessionEpoch,
                    activationFence = required.activationFence,
                )
                continue
            } catch (required: SecureMessagingFreshAuthenticationRequiredException) {
                pendingEnrollmentRecovery = PendingEnrollmentRecovery.FreshAuthentication(
                    sessionEpoch = binding.sessionEpoch,
                    activationFence = required.activationFence,
                )
                continue
            }
            bindingResolver.assertCurrent(binding)
            processor.recoverPendingOutbox(active.transport)
            bindingResolver.assertCurrent(binding)
            processor.recoverPendingHistory(active.transport)
            bindingResolver.assertCurrent(binding)
            return@withLock
        }
    }

    private suspend fun completePendingEnrollmentRecovery(
        pending: PendingEnrollmentRecovery,
    ) {
        try {
            when (pending) {
                is PendingEnrollmentRecovery.LocalReset -> resetLocalState(pending)
                is PendingEnrollmentRecovery.FreshAuthentication ->
                    authRepository.requireFreshAuthenticationForSecureMessagingRecovery(
                        pending.sessionEpoch,
                    )
                is PendingEnrollmentRecovery.RemoteReset -> {
                    authRepository.recoverMissingSecureMessagingEnrollment(
                        expectedSessionEpoch = pending.sessionEpoch,
                        target = pending.target,
                    )
                    resetLocalState(pending)
                }
            }
        } catch (error: Throwable) {
            if (sessions.current()?.sessionId != pending.sessionEpoch) {
                pendingEnrollmentRecovery = null
                throw SecureMessagingAuthenticationEpochChangedException(
                    "Authentication changed during secure-messaging recovery",
                )
            }
            throw error
        }
    }

    private suspend fun resetLocalState(pending: PendingEnrollmentRecovery) {
        val expected = sessions.current()?.fence()
            ?: throw SecureMessagingAuthenticationEpochChangedException(
                "Authentication ended before secure-messaging recovery",
            )
        if (expected.sessionId != pending.sessionEpoch ||
            !sessions.resetSecureMessagingStateIfCurrent(expected, pending.activationFence)
        ) {
            throw SecureMessagingAuthenticationEpochChangedException(
                "Authentication changed before secure-messaging state reset",
            )
        }
    }
}
