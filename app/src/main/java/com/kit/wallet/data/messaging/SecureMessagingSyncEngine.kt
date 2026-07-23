package com.kit.wallet.data.messaging

import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SecureMessagingTransportValidator
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionTokens
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** Foreground action variant that must not redirect an obsolete activation into a newer one. */
    suspend fun synchronize(expectedActivation: SecureMessagingSessionFence) {
        error("Exact-activation secure messaging synchronization is unavailable")
    }

    /**
     * Lost-record-key recovery may erase [expectedActivation] and activate its one immediate
     * successor. This is deliberately separate from exact foreground synchronization.
     */
    suspend fun recoverPermanentlyUnavailableState(
        expectedActivation: SecureMessagingSessionFence,
    ) {
        error("Exact-activation secure messaging recovery is unavailable")
    }
}

/**
 * Process-local wake emitted only after one exact active messaging identity completes a full sync.
 * It is deliberately separate from projection mutations: a no-op sync can still prove that an
 * Android 9 Keystore provider has recovered and that an exhausted UI baseline should retry.
 */
@Singleton
internal class SecureMessagingSyncCompletionSignal @Inject constructor() {
    private val mutableCompletions = MutableSharedFlow<SecureMessagingActiveSession>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val completions: SharedFlow<SecureMessagingActiveSession> =
        mutableCompletions.asSharedFlow()

    fun completed(active: SecureMessagingActiveSession) {
        check(mutableCompletions.tryEmit(active)) {
            "The secure-messaging sync completion wake could not be published"
        }
    }
}

@Singleton
class SecureMessagingUnavailableSyncEngine @Inject constructor() : SecureMessagingSyncEngine {
    override val isReady: Boolean = false

    override suspend fun synchronize(): Nothing =
        error("Secure messaging sync requires the reviewed end-to-end encryption engine")

    override suspend fun synchronize(expectedActivation: SecureMessagingSessionFence): Nothing =
        synchronize()

    override suspend fun recoverPermanentlyUnavailableState(
        expectedActivation: SecureMessagingSessionFence,
    ): Nothing = synchronize()
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
    private val messageHistory: AccountMessageHistoryRetention =
        NoOpAccountMessageHistoryRetention,
    private val syncCompletions: SecureMessagingSyncCompletionSignal =
        SecureMessagingSyncCompletionSignal(),
) : SecureMessagingSyncEngine {
    private enum class RecoverySnapshotPolicy {
        REQUIRE_READABLE_STATE,
        ALLOW_PROVEN_PERMANENTLY_UNAVAILABLE_STATE,
    }

    private sealed interface PendingEnrollmentRecovery {
        val session: SessionFence
        val activationFence: SecureMessagingSessionFence
        val snapshotPolicy: RecoverySnapshotPolicy

        data class RemoteReset(
            override val session: SessionFence,
            override val activationFence: SecureMessagingSessionFence,
            val target: SecureMessagingEnrollmentResetTarget,
            override val snapshotPolicy: RecoverySnapshotPolicy,
        ) : PendingEnrollmentRecovery

        data class LocalReset(
            override val session: SessionFence,
            override val activationFence: SecureMessagingSessionFence,
            override val snapshotPolicy: RecoverySnapshotPolicy,
        ) : PendingEnrollmentRecovery

        data class FreshAuthentication(
            override val session: SessionFence,
            override val activationFence: SecureMessagingSessionFence,
            override val snapshotPolicy: RecoverySnapshotPolicy,
        ) : PendingEnrollmentRecovery
    }

    /** Mutable authority retained only for one serialized synchronization call. */
    private data class ExpectedSynchronization(
        val initial: SecureMessagingSessionFence,
        val allowRecoverySuccessor: Boolean,
        var initialRecoveryCompleted: Boolean = false,
        var successorRecovery: SecureMessagingSessionFence? = null,
        var successorRecoveryCompleted: Boolean = false,
        var successor: SecureMessagingSessionFence? = null,
    )

    private val synchronizationMutex = Mutex()
    private var pendingEnrollmentRecovery: PendingEnrollmentRecovery? = null

    override val isReady: Boolean = true

    override suspend fun synchronize() = synchronizeExpected(expected = null)

    override suspend fun synchronize(expectedActivation: SecureMessagingSessionFence) =
        synchronizeExpected(
            ExpectedSynchronization(
                initial = expectedActivation,
                allowRecoverySuccessor = false,
            ),
        )

    override suspend fun recoverPermanentlyUnavailableState(
        expectedActivation: SecureMessagingSessionFence,
    ) = synchronizeExpected(
        ExpectedSynchronization(
            initial = expectedActivation,
            allowRecoverySuccessor = true,
        ),
    )

    private suspend fun synchronizeExpected(
        expected: ExpectedSynchronization?,
    ) = synchronizationMutex.withLock {
        assertExpectedSynchronizationEntry(expected)
        var completedRecordKeyRetries = 0
        while (true) {
            assertExpectedSynchronizationCurrent(expected)
            val sessionTarget = sessions.current()?.fence()
                ?: throw SecureMessagingAuthenticationEpochChangedException(
                    "Secure messaging requires an authenticated session",
                )
            var activeForLostKeyRecovery: SecureMessagingActiveSession? = null
            try {
                val sessionEpoch = sessionTarget.sessionId
                pendingEnrollmentRecovery?.let { pending ->
                    if (pending.session == sessionTarget) {
                        assertExpectedRecoveryCurrent(expected, pending.activationFence)
                        completePendingEnrollmentRecovery(pending, expected)
                        pendingEnrollmentRecovery = null
                        if (recordExpectedRecoveryCompletion(expected, pending)) {
                            throw activationChanged(
                                "Exact secure messaging synchronization completed recovery " +
                                    "without entering the replacement activation",
                            )
                        }
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
                assertExpectedSynchronizationCurrent(expected)
                val binding = bindingResolver.resolve(sessionEpoch)
                bindingResolver.assertCurrent(binding)
                assertExpectedSynchronizationCurrent(expected)
                val active = try {
                    activation.ensureActivated(binding)
                } catch (required: SecureMessagingReauthenticationRequiredException) {
                    assertExpectedRecoveryCurrent(expected, required.activationFence)
                    pendingEnrollmentRecovery = PendingEnrollmentRecovery.RemoteReset(
                        session = sessionTarget,
                        activationFence = required.activationFence,
                        target = required.target,
                        snapshotPolicy = required.recoverySnapshotPolicy(),
                    )
                    continue
                } catch (required: SecureMessagingLocalEnrollmentResetRequiredException) {
                    assertExpectedRecoveryCurrent(expected, required.activationFence)
                    pendingEnrollmentRecovery = PendingEnrollmentRecovery.LocalReset(
                        session = sessionTarget,
                        activationFence = required.activationFence,
                        snapshotPolicy = required.recoverySnapshotPolicy(),
                    )
                    continue
                } catch (required: SecureMessagingFreshAuthenticationRequiredException) {
                    assertExpectedRecoveryCurrent(expected, required.activationFence)
                    pendingEnrollmentRecovery = PendingEnrollmentRecovery.FreshAuthentication(
                        session = sessionTarget,
                        activationFence = required.activationFence,
                        snapshotPolicy = required.recoverySnapshotPolicy(),
                    )
                    continue
                }
                acceptActiveForExpectedSynchronization(expected, active.fence)
                activeForLostKeyRecovery = active
                check(active.binding == binding) {
                    "Secure-messaging activation returned another authentication epoch"
                }
                bindingResolver.assertCurrent(binding)
                assertActiveForExpectedSynchronization(expected, active.fence)
                try {
                    processor.synchronize(active.transport)
                } catch (required: SecureMessagingReauthenticationRequiredException) {
                    assertExpectedRecoveryCurrent(expected, required.activationFence)
                    pendingEnrollmentRecovery = PendingEnrollmentRecovery.RemoteReset(
                        session = sessionTarget,
                        activationFence = required.activationFence,
                        target = required.target,
                        snapshotPolicy = required.recoverySnapshotPolicy(),
                    )
                    continue
                } catch (required: SecureMessagingLocalEnrollmentResetRequiredException) {
                    assertExpectedRecoveryCurrent(expected, required.activationFence)
                    pendingEnrollmentRecovery = PendingEnrollmentRecovery.LocalReset(
                        session = sessionTarget,
                        activationFence = required.activationFence,
                        snapshotPolicy = required.recoverySnapshotPolicy(),
                    )
                    continue
                } catch (required: SecureMessagingFreshAuthenticationRequiredException) {
                    assertExpectedRecoveryCurrent(expected, required.activationFence)
                    pendingEnrollmentRecovery = PendingEnrollmentRecovery.FreshAuthentication(
                        session = sessionTarget,
                        activationFence = required.activationFence,
                        snapshotPolicy = required.recoverySnapshotPolicy(),
                    )
                    continue
                }
                bindingResolver.assertCurrent(binding)
                assertActiveForExpectedSynchronization(expected, active.fence)
                processor.recoverPendingOutbox(active.transport)
                bindingResolver.assertCurrent(binding)
                assertActiveForExpectedSynchronization(expected, active.fence)
                processor.recoverPendingHistory(active.transport)
                bindingResolver.assertCurrent(binding)
                assertActiveForExpectedSynchronization(expected, active.fence)
                syncCompletions.completed(active)
                return@withLock
            } catch (error: Throwable) {
                if (isTransientSecureMessagingRecordKeyFailure(error) &&
                    completedRecordKeyRetries < MAX_INTERNAL_RECORD_KEY_RETRIES
                ) {
                    assertExpectedSynchronizationCurrent(expected)
                    completedRecordKeyRetries++
                    delay(INTERNAL_RECORD_KEY_RETRY_DELAY_MILLIS)
                    continue
                }
                val active = activeForLostKeyRecovery
                if (active != null && isRecoverableSecureMessagingStateLoss(error)) {
                    // A ready session can lose its Android-Keystore alias after activation or
                    // expose migration-fenced unreadable pre-code-19 state. The storage failure
                    // may arrive directly or through the crypto boundary. Quarantine before
                    // fenced erasure; normal activation then obtains and resets the pinned epoch.
                    val quarantineFailure = if (
                        error is SecureMessagingCryptographicFailureException
                    ) {
                        error
                    } else {
                        SecureMessagingCryptographicFailureException(
                            quarantineReason = SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                            message = "The secure messaging state is permanently unavailable",
                            cause = error,
                        )
                    }
                    runCatching { active.transport.quarantine(quarantineFailure) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                    assertExpectedRecoveryCurrent(expected, active.fence)
                    pendingEnrollmentRecovery = PendingEnrollmentRecovery.LocalReset(
                        session = sessionTarget,
                        activationFence = active.fence,
                        snapshotPolicy = RecoverySnapshotPolicy
                            .ALLOW_PROVEN_PERMANENTLY_UNAVAILABLE_STATE,
                    )
                    continue
                }
                throw error
            }
        }
    }

    private fun assertExpectedSynchronizationEntry(expected: ExpectedSynchronization?) {
        if (expected == null) return
        if (activation.activeSession.value?.fence !== expected.initial) {
            throw activationChanged(
                "Secure messaging activation changed before exact synchronization",
            )
        }
    }

    private fun assertExpectedSynchronizationCurrent(expected: ExpectedSynchronization?) {
        if (expected == null) return
        if (!expected.initialRecoveryCompleted) {
            if (activation.ownsGeneration(expected.initial)) return
            throw activationChanged(
                "Secure messaging activation changed during exact synchronization",
            )
        }
        if (!expected.allowRecoverySuccessor) {
            throw activationChanged(
                "Exact secure messaging synchronization cannot enter a replacement activation",
            )
        }
        val successor = expected.successor
        if (successor == null) {
            val successorRecovery = expected.successorRecovery
            if (successorRecovery != null) {
                if (activation.ownsGeneration(successorRecovery)) return
                throw activationChanged(
                    "Secure messaging recovery attempt changed during synchronization",
                )
            }
            if (activation.hasNoGeneration()) return
            throw activationChanged(
                "Another secure messaging activation won the recovery successor race",
            )
        }
        if (!activation.ownsGeneration(successor)) {
            throw activationChanged(
                "Secure messaging recovery successor changed during synchronization",
            )
        }
    }

    private fun assertExpectedRecoveryCurrent(
        expected: ExpectedSynchronization?,
        recoveryFence: SecureMessagingSessionFence,
    ) {
        if (expected == null) return
        if (!expected.initialRecoveryCompleted) {
            if (recoveryFence === expected.initial &&
                activation.ownsGeneration(expected.initial)
            ) {
                return
            }
            throw activationChanged(
                "Secure messaging recovery belongs to another activation",
            )
        }
        if (!expected.allowRecoverySuccessor ||
            expected.successor != null ||
            expected.successorRecoveryCompleted ||
            recoveryFence.binding != expected.initial.binding ||
            !activation.ownsGeneration(recoveryFence)
        ) {
            throw activationChanged(
                "Secure messaging recovery belongs to another activation",
            )
        }
        val successorRecovery = expected.successorRecovery
        if (successorRecovery == null) {
            expected.successorRecovery = recoveryFence
        } else if (recoveryFence !== successorRecovery) {
            throw activationChanged(
                "Secure messaging recovery resolved more than one replacement attempt",
            )
        }
    }

    private fun acceptActiveForExpectedSynchronization(
        expected: ExpectedSynchronization?,
        activeFence: SecureMessagingSessionFence,
    ) {
        if (expected == null) return
        if (!expected.initialRecoveryCompleted) {
            if (activeFence !== expected.initial) {
                throw activationChanged(
                    "Exact synchronization resolved another secure messaging activation",
                )
            }
        } else {
            if (!expected.allowRecoverySuccessor) {
                throw activationChanged(
                    "Exact synchronization cannot accept a replacement activation",
                )
            }
            if (expected.successorRecovery != null) {
                throw activationChanged(
                    "Secure messaging recovery attempt was not erased before replacement",
                )
            }
            val successor = expected.successor
            if (successor == null) {
                expected.successor = activeFence
            } else if (successor !== activeFence) {
                throw activationChanged(
                    "Secure messaging recovery resolved more than one successor",
                )
            }
        }
        assertActiveForExpectedSynchronization(expected, activeFence)
    }

    private fun assertActiveForExpectedSynchronization(
        expected: ExpectedSynchronization?,
        activeFence: SecureMessagingSessionFence,
    ) {
        if (expected == null) return
        val required = expected.successor ?: expected.initial
        if (activeFence !== required || !activation.ownsGeneration(required)) {
            throw activationChanged(
                "Secure messaging activation changed before processor work",
            )
        }
    }

    private fun activationChanged(message: String) =
        SecureMessagingAuthenticationEpochChangedException(message)

    private suspend fun completePendingEnrollmentRecovery(
        pending: PendingEnrollmentRecovery,
        expected: ExpectedSynchronization?,
    ) {
        try {
            when (pending) {
                is PendingEnrollmentRecovery.LocalReset -> resetLocalState(pending)
                is PendingEnrollmentRecovery.FreshAuthentication ->
                    authRepository.requireFreshAuthenticationForSecureMessagingRecovery(
                        pending.session,
                        pending.activationFence,
                    )
                is PendingEnrollmentRecovery.RemoteReset -> {
                    authRepository.recoverMissingSecureMessagingEnrollment(
                        expectedSession = pending.session,
                        activationFence = pending.activationFence,
                        target = pending.target,
                    )
                    val localReset = PendingEnrollmentRecovery.LocalReset(
                        session = pending.session,
                        activationFence = pending.activationFence,
                        snapshotPolicy = pending.snapshotPolicy,
                    )
                    // Once the server reset is proved, a local snapshot retry must never replay
                    // that remote mutation or retarget it to a newer session generation.
                    pendingEnrollmentRecovery = localReset
                    resetLocalState(localReset)
                }
            }
        } catch (error: Throwable) {
            if (sessions.current()?.fence() != pending.session) {
                pendingEnrollmentRecovery = null
                throw SecureMessagingAuthenticationEpochChangedException(
                    "Authentication changed during secure-messaging recovery",
                )
            }
            completedLocalResetAwaitingActivation(pending)?.let { completed ->
                // Session storage completed the destructive reset and durably removed its crash
                // fence before reopening failed or propagated cancellation. Never retain that
                // erased activation as pending: the exact-session gate retry now owns reopening.
                pendingEnrollmentRecovery = null
                recordExpectedRecoveryCompletion(expected, completed)
            }
            throw error
        }
    }

    private fun completedLocalResetAwaitingActivation(
        pending: PendingEnrollmentRecovery,
    ): PendingEnrollmentRecovery.LocalReset? {
        val reset = pendingEnrollmentRecovery as? PendingEnrollmentRecovery.LocalReset
            ?: return null
        if (reset.session != pending.session ||
            reset.activationFence !== pending.activationFence ||
            sessions.current()?.fence() != reset.session ||
            activation.ownsGeneration(reset.activationFence)
        ) {
            return null
        }
        return reset.takeIf {
            sessions.restorationPending.value || sessionLifecycle.stateAvailable.value
        }
    }

    /** Returns true when an exact non-recovery synchronization must stop after erasing its fence. */
    private fun recordExpectedRecoveryCompletion(
        expected: ExpectedSynchronization?,
        completed: PendingEnrollmentRecovery,
    ): Boolean {
        if (expected?.initial === completed.activationFence) {
            expected.initialRecoveryCompleted = true
            return !expected.allowRecoverySuccessor
        }
        if (expected?.successorRecovery === completed.activationFence) {
            // A lost-key local reset can leave the old server enrollment in place. Production key
            // reconciliation discovers that only after beginning an unpublished successor
            // generation; its exact reset must finish before accepting the final successor.
            expected.successorRecovery = null
            expected.successorRecoveryCompleted = true
        }
        return false
    }

    private suspend fun resetLocalState(pending: PendingEnrollmentRecovery) {
        try {
            resetLocalStateOnce(pending)
        } catch (error: Throwable) {
            if (pending.snapshotPolicy == RecoverySnapshotPolicy.REQUIRE_READABLE_STATE &&
                sessions.current()?.fence() == pending.session &&
                isRecoverableSecureMessagingStateLoss(error)
            ) {
                val promoted = PendingEnrollmentRecovery.LocalReset(
                    session = pending.session,
                    activationFence = pending.activationFence,
                    snapshotPolicy = RecoverySnapshotPolicy
                        .ALLOW_PROVEN_PERMANENTLY_UNAVAILABLE_STATE,
                )
                pendingEnrollmentRecovery = promoted
                resetLocalStateOnce(promoted)
                return
            }
            throw error
        }
    }

    private suspend fun resetLocalStateOnce(pending: PendingEnrollmentRecovery) {
        if (sessions.current()?.fence() != pending.session ||
            !sessions.resetSecureMessagingStateIfCurrent(
                expected = pending.session,
                activationFence = pending.activationFence,
                allowPermanentlyUnavailableSnapshot =
                    pending.snapshotPolicy == RecoverySnapshotPolicy
                        .ALLOW_PROVEN_PERMANENTLY_UNAVAILABLE_STATE,
                finalMessagingSnapshot = {
                    messageHistory.snapshotActiveHistory(pending.session)
                },
            )
        ) {
            throw SecureMessagingAuthenticationEpochChangedException(
                "Authentication changed before secure-messaging state reset",
            )
        }
    }

    private fun Throwable.recoverySnapshotPolicy(): RecoverySnapshotPolicy =
        if (isRecoverableSecureMessagingStateLoss(this)) {
            RecoverySnapshotPolicy.ALLOW_PROVEN_PERMANENTLY_UNAVAILABLE_STATE
        } else {
            RecoverySnapshotPolicy.REQUIRE_READABLE_STATE
        }

    private companion object {
        const val MAX_INTERNAL_RECORD_KEY_RETRIES = 3
        const val INTERNAL_RECORD_KEY_RETRY_DELAY_MILLIS = 5_000L
    }
}
