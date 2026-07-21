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

    val activeSession: StateFlow<SecureMessagingActiveSession?> = mutableActiveSession.asStateFlow()

    init {
        lifecycle.addReadinessInvalidationListener(::clear)
    }

    fun currentOrNull(): SecureMessagingActiveSession? = synchronized(lock) {
        val active = mutableActiveSession.value ?: return@synchronized null
        if (!isCurrentAndReady(active)) {
            clearLocked()
            null
        } else {
            active
        }
    }

    fun requireCurrent(): SecureMessagingActiveSession = checkNotNull(currentOrNull()) {
        "Secure messaging has no active message-ready session"
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

        val active = SecureMessagingActiveSession(transport, fence)
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
        if (mutableActiveSession.value?.fence === fence) clearLocked()
    }

    internal fun clear() = synchronized(lock) {
        clearLocked()
    }

    private fun isCurrentAndReady(active: SecureMessagingActiveSession): Boolean =
        runCatching {
            lifecycle.assertCurrent(active.fence, readyRequired = true)
        }.isSuccess

    private fun clearLocked() {
        mutableActiveSession.value = null
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

    suspend fun ensureActivated(
        binding: SecureMessagingSessionBinding,
    ): SecureMessagingActiveSession = mutex.withLock {
        sessions.currentOrNull()?.let { active ->
            check(active.binding == binding) {
                "Secure messaging must erase the active epoch before session replacement"
            }
            return@withLock active
        }

        val attempt = currentOrNewAttempt(binding)
        try {
            val active = advance(attempt)
            pendingAttempt = null
            active
        } catch (error: Throwable) {
            if (error is SecureMessagingKeyReconciliationException && isCurrent(attempt)) {
                runCatching {
                    lifecycle.quarantine(attempt.fence, error.quarantineReason)
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            sessions.clearIfOwnedBy(attempt.fence)
            if (!isCurrent(attempt)) pendingAttempt = null
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
