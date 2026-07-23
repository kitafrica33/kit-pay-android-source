package com.kit.wallet.data.messaging

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The message-ready authority exposed to the real sync engine and encrypted chat repository.
 *
 * The transport handle remains opaque outside the messaging package. Its lifecycle fence is
 * checked on every registry read, so retaining this wrapper cannot revive a logged-out epoch.
 */
class SecureMessagingActiveSession internal constructor(
    internal val transport: RemoteSecureMessagingTransport.Session,
    internal val fence: SecureMessagingSessionFence,
    internal val activation: SecureMessagingActivationCapability,
) {
    val binding: SecureMessagingSessionBinding = transport.binding
}

/**
 * Process-local registry for the sole transport session allowed to exchange messages.
 *
 * A session is published only after the lifecycle reaches READY. Leaving READY synchronously
 * removes the handle from [activeSession], while [currentOrNull] independently rechecks the
 * generation fence to remain fail closed across races with logout or session replacement.
 */
@Singleton
class SecureMessagingActiveSessionRegistry @Inject constructor(
    private val lifecycle: SecureMessagingLifecycleGuard,
) {
    private val lock = Any()
    private val mutableActiveSession = MutableStateFlow<SecureMessagingActiveSession?>(null)
    private var retainedDuringRevalidation: SecureMessagingActiveSession? = null

    val activeSession: StateFlow<SecureMessagingActiveSession?> = mutableActiveSession.asStateFlow()

    init {
        lifecycle.addReadinessInvalidationListener(::onLifecycleTransition)
    }

    fun currentOrNull(): SecureMessagingActiveSession? = synchronized(lock) {
        val active = mutableActiveSession.value ?: return@synchronized null
        if (!isCurrentAndReady(active)) {
            if (isCurrentTransientRevalidation(active)) {
                retainLocked(active)
            } else {
                clearLocked()
            }
            null
        } else {
            active
        }
    }

    fun requireCurrent(): SecureMessagingActiveSession = checkNotNull(currentOrNull()) {
        "Secure messaging has no active message-ready session"
    }

    /**
     * Commits a non-suspending projection only while [expected] is still the exact published
     * activation. The registry lock closes replacement races; the lifecycle guard holds its own
     * lock across the callback so leaving READY cannot interleave with observable publication.
     */
    internal fun publishIfCurrent(
        expected: SecureMessagingActiveSession?,
        publication: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (mutableActiveSession.value !== expected) return@synchronized false
        if (expected == null) {
            publication()
            return@synchronized true
        }
        if (!lifecycle.runIfCurrentAndReady(expected.fence, publication)) {
            clearLocked()
            return@synchronized false
        }
        true
    }

    internal fun publish(
        transport: RemoteSecureMessagingTransport.Session,
        fence: SecureMessagingSessionFence,
    ): SecureMessagingActiveSession = synchronized(lock) {
        check(transport.binding == fence.binding) {
            "Secure messaging transport and lifecycle bindings differ"
        }
        lifecycle.assertCurrent(fence, readyRequired = true)
        mutableActiveSession.value?.let { existing ->
            check(existing.transport === transport && existing.fence === fence) {
                "Another secure messaging session is already active"
            }
            return@synchronized existing
        }
        retainedDuringRevalidation?.let { existing ->
            check(existing.transport === transport && existing.fence === fence) {
                "Another secure messaging session is retained during revalidation"
            }
            retainedDuringRevalidation = null
        }

        val active = SecureMessagingActiveSession(
            transport = transport,
            fence = fence,
            activation = lifecycle.activationCapability(fence, readyRequired = true),
        )
        mutableActiveSession.value = active
        try {
            // Close the race in which erasure starts after the first check but before publication.
            lifecycle.assertCurrent(fence, readyRequired = true)
        } catch (error: Throwable) {
            clearLocked()
            throw error
        }
        active
    }

    internal fun clearIfOwnedBy(fence: SecureMessagingSessionFence) = synchronized(lock) {
        if (mutableActiveSession.value?.fence === fence ||
            retainedDuringRevalidation?.fence === fence
        ) {
            clearLocked()
        }
    }

    internal fun clear() = synchronized(lock) {
        clearLocked()
    }

    /** Returns the exact withdrawn handle while a self-lifecycle status check is retrying. */
    internal fun retainedForRevalidation(
        binding: SecureMessagingSessionBinding,
    ): SecureMessagingActiveSession? = synchronized(lock) {
        val retained = retainedDuringRevalidation ?: return@synchronized null
        if (retained.binding != binding || !isCurrentTransientRevalidation(retained)) {
            clearLocked()
            return@synchronized null
        }
        retained
    }

    private fun isCurrentAndReady(active: SecureMessagingActiveSession): Boolean =
        runCatching {
            lifecycle.assertCurrent(active.fence, readyRequired = true)
        }.isSuccess

    private fun isCurrentTransientRevalidation(active: SecureMessagingActiveSession): Boolean {
        val snapshot = lifecycle.snapshot()
        if (snapshot.stage !in TRANSIENT_REVALIDATION_STAGES ||
            snapshot.binding != active.binding
        ) {
            return false
        }
        return runCatching { lifecycle.assertCurrent(active.fence) }.isSuccess
    }

    private fun onLifecycleTransition() = synchronized(lock) {
        val active = mutableActiveSession.value
        val retained = retainedDuringRevalidation
        when {
            active != null && isCurrentTransientRevalidation(active) -> retainLocked(active)
            retained != null && isCurrentAndReady(retained) -> {
                retainedDuringRevalidation = null
                mutableActiveSession.value = retained
            }
            retained != null && !isCurrentTransientRevalidation(retained) -> clearLocked()
            active != null && !isCurrentAndReady(active) -> clearLocked()
        }
    }

    private fun retainLocked(active: SecureMessagingActiveSession) {
        retainedDuringRevalidation?.let { existing ->
            check(existing === active) { "Another secure messaging session is already retained" }
        }
        retainedDuringRevalidation = active
        mutableActiveSession.value = null
    }

    private fun clearLocked() {
        mutableActiveSession.value = null
        retainedDuringRevalidation = null
    }

    private companion object {
        val TRANSIENT_REVALIDATION_STAGES = setOf(
            SecureMessagingRuntimeStage.PREPARING_KEYS,
            SecureMessagingRuntimeStage.SYNCING_ROSTER,
        )
    }
}

/** Idempotent key provisioning/reconciliation step supplied by the reviewed key manager. */
fun interface SecureMessagingKeyActivation {
    suspend fun reconcile(session: RemoteSecureMessagingTransport.Session)
}

/** Crash-safe roster recovery and encrypted catch-up supplied by the reviewed sync processor. */
fun interface SecureMessagingInitialSyncActivation {
    suspend fun synchronize(session: RemoteSecureMessagingTransport.Session)
}

/** Fail-closed production binding until the key-reconciliation slice is installed. */
@Singleton
class SecureMessagingUnavailableKeyActivation @Inject constructor() : SecureMessagingKeyActivation {
    override suspend fun reconcile(session: RemoteSecureMessagingTransport.Session): Nothing {
        throw SecureMessagingProtocolUnavailableException(
            "Secure messaging key reconciliation is not installed",
        )
    }
}

/** Fail-closed production binding until the encrypted event processor is installed. */
@Singleton
class SecureMessagingUnavailableInitialSyncActivation @Inject constructor() :
    SecureMessagingInitialSyncActivation {
    override suspend fun synchronize(session: RemoteSecureMessagingTransport.Session): Nothing {
        throw SecureMessagingProtocolUnavailableException(
            "Secure messaging initial synchronization is not installed",
        )
    }
}

/**
 * Owns the only path from an authenticated binding to a message-ready transport session.
 *
 * [ensureActivated] is idempotent and serializes callers. Network or storage failures retain the
 * exact fenced attempt and retry only its incomplete idempotent stage; a later login can never
 * reuse it. Nothing is published until capabilities, keys and initial encrypted sync all succeed
 * for the same authentication epoch.
 */
@Singleton
class SecureMessagingActivationCoordinator @Inject constructor(
    private val transport: RemoteSecureMessagingTransport,
    private val lifecycle: SecureMessagingLifecycleGuard,
    private val sessions: SecureMessagingActiveSessionRegistry,
    private val keyActivation: SecureMessagingKeyActivation,
    private val initialSyncActivation: SecureMessagingInitialSyncActivation,
) {
    private data class Attempt(
        val binding: SecureMessagingSessionBinding,
        val fence: SecureMessagingSessionFence,
        var transportSession: RemoteSecureMessagingTransport.Session? = null,
    )

    private val mutex = Mutex()
    private var pendingAttempt: Attempt? = null

    val activationState: StateFlow<SecureMessagingRuntimeSnapshot> = lifecycle.runtime
    val activeSession: StateFlow<SecureMessagingActiveSession?> = sessions.activeSession

    internal fun ownsGeneration(fence: SecureMessagingSessionFence): Boolean =
        lifecycle.ownsGeneration(fence)

    internal fun hasNoGeneration(): Boolean =
        lifecycle.snapshot().stage == SecureMessagingRuntimeStage.NO_SESSION

    suspend fun ensureActivated(
        binding: SecureMessagingSessionBinding,
    ): SecureMessagingActiveSession = mutex.withLock {
        sessions.currentOrNull()?.let { active ->
            check(active.binding == binding) {
                "Secure messaging must erase the active epoch before session replacement"
            }
            return@withLock active
        }
        sessions.retainedForRevalidation(binding)?.let { retained ->
            return@withLock retained
        }

        val attempt = currentOrNewAttempt(binding)
        try {
            val active = advance(attempt)
            pendingAttempt = null
            active
        } catch (error: Throwable) {
            val permanentlyMissingRecordKey =
                isPermanentlyMissingSecureMessagingRecordKey(error)
            if (permanentlyMissingRecordKey) {
                // Initial roster/projection recovery can discover the same lost Android-Keystore
                // alias after key reconciliation. Convert that exact storage failure into the
                // fenced local-reset path instead of leaving this login quarantined forever.
                runCatching {
                    lifecycle.quarantine(
                        attempt.fence,
                        SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                    )
                }.exceptionOrNull()?.let(error::addSuppressed)
            } else if (error is SecureMessagingKeyReconciliationException && isCurrent(attempt)) {
                runCatching {
                    lifecycle.quarantine(attempt.fence, error.quarantineReason)
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            sessions.clearIfOwnedBy(attempt.fence)
            if (!isCurrent(attempt)) pendingAttempt = null
            if (
                permanentlyMissingRecordKey &&
                error !is SecureMessagingReauthenticationRequiredException &&
                error !is SecureMessagingLocalEnrollmentResetRequiredException &&
                error !is SecureMessagingFreshAuthenticationRequiredException
            ) {
                throw SecureMessagingLocalEnrollmentResetRequiredException(
                    activationFence = attempt.fence,
                    message = "The secure messaging record key is permanently unavailable",
                    cause = error,
                )
            }
            throw error
        }
    }

    private fun currentOrNewAttempt(binding: SecureMessagingSessionBinding): Attempt {
        pendingAttempt?.let { pending ->
            if (!isCurrent(pending)) {
                pendingAttempt = null
            } else {
                check(pending.binding == binding) {
                    "Secure messaging must erase the pending epoch before session replacement"
                }
                return pending
            }
        }

        check(lifecycle.snapshot().stage == SecureMessagingRuntimeStage.NO_SESSION) {
            "Secure messaging lifecycle is already owned by another activation"
        }
        return Attempt(binding, lifecycle.beginSession(binding)).also { pendingAttempt = it }
    }

    private suspend fun advance(attempt: Attempt): SecureMessagingActiveSession {
        lifecycle.assertCurrent(attempt.fence)
        var stage = lifecycle.snapshot().stage

        if (stage in setOf(
                SecureMessagingRuntimeStage.ACTIVATING,
                SecureMessagingRuntimeStage.CHECKING_CAPABILITIES,
            ) && attempt.transportSession == null
        ) {
            attempt.transportSession = transport.openSession(lifecycle, attempt.fence)
            stage = lifecycle.snapshot().stage
        }

        val transportSession = checkNotNull(attempt.transportSession) {
            "Secure messaging capability check did not issue a transport session"
        }
        check(transportSession.binding == attempt.binding) {
            "Secure messaging activation returned a different session binding"
        }

        if (stage in setOf(
                SecureMessagingRuntimeStage.CHECKING_CAPABILITIES,
                SecureMessagingRuntimeStage.PREPARING_KEYS,
            )
        ) {
            lifecycle.beginKeyPreparation(attempt.fence)
            keyActivation.reconcile(transportSession)
            lifecycle.assertCurrent(attempt.fence)
            stage = lifecycle.snapshot().stage
            check(stage == SecureMessagingRuntimeStage.PREPARING_KEYS) {
                "Secure messaging key activation changed lifecycle authority"
            }
        }

        if (stage in setOf(
                SecureMessagingRuntimeStage.PREPARING_KEYS,
                SecureMessagingRuntimeStage.SYNCING_ROSTER,
            )
        ) {
            lifecycle.beginRosterSync(attempt.fence)
            initialSyncActivation.synchronize(transportSession)
            lifecycle.assertCurrent(attempt.fence)
            stage = lifecycle.snapshot().stage
            check(stage == SecureMessagingRuntimeStage.SYNCING_ROSTER) {
                "Secure messaging initial sync changed lifecycle authority"
            }
        }

        check(stage in setOf(
            SecureMessagingRuntimeStage.SYNCING_ROSTER,
            SecureMessagingRuntimeStage.READY,
        )) {
            "Secure messaging activation stopped at unexpected stage $stage"
        }
        lifecycle.finishActivation(attempt.fence)
        lifecycle.assertCurrent(attempt.fence, readyRequired = true)
        return sessions.publish(transportSession, attempt.fence)
    }

    private fun isCurrent(attempt: Attempt): Boolean = runCatching {
        lifecycle.assertCurrent(attempt.fence)
    }.isSuccess
}
