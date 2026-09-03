package com.kit.wallet.data.notifications

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.RegisterPushTokenRequest
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PushTokenCoordinator @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val transport: PushMessagingTransport,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private data class RegistrationAttempt(
        val owner: SessionFence,
        val generation: Long,
    )

    private val registrationLock = Any()
    private val registrationMutationLock = Mutex()
    private var registrationGeneration = 0L
    private var registrationAttempt: RegistrationAttempt? = null
    private var registrationJob: Job? = null
    private var registrationSuppressedOwner: SessionFence? = null

    fun start() {
        if (!transport.configured) return
        scope.launch {
            sessions.session.map { it?.fence() }.distinctUntilChanged().collect { owner ->
                if (owner == null) {
                    cancelRegistration()
                } else {
                    scheduleRegistration(owner, transport::currentToken)
                }
            }
        }
    }

    fun tokenChanged(provider: String, token: String): Job? {
        val owner = sessions.current()?.fence()
        if (provider != transport.provider || !transport.configured || owner == null) return null
        return scheduleRegistration(owner) { token }
    }

    /** Replays registration when a foreground capability refresh changes server policy. */
    fun capabilityPolicyChanged(): Job? {
        val owner = sessions.current()?.fence()
        if (!transport.configured || owner == null) return null
        return scheduleRegistration(owner, transport::currentToken)
    }

    private fun scheduleRegistration(
        owner: SessionFence,
        token: suspend () -> String,
    ): Job? =
        synchronized(registrationLock) {
            if (sessions.current()?.fence() != owner || registrationSuppressedOwner == owner) {
                return@synchronized null
            }
            val predecessor = registrationJob
            val attempt = RegistrationAttempt(owner, ++registrationGeneration)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Joining the superseded generation prevents an older token mutation from
                    // completing after this one. The mutation lock also preserves ordering when
                    // an intermediate handoff is itself cancelled before it finishes the join.
                    predecessor?.cancelAndJoin()
                    registrationMutationLock.withLock {
                        if (!isCurrent(attempt)) return@withLock
                        registerWithRetry(
                            expectedOwner = owner,
                            tokenProvider = token,
                            isCurrentGeneration = { isCurrent(attempt) },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Only terminal policy/protocol failures reach here. Connectivity and retryable
                    // server failures stay in registerWithRetry until this job is superseded by a
                    // token/session/capability event or logout cancels it.
                }
            }
            registrationAttempt = attempt
            registrationJob = job
            job.start()
            job
        }

    suspend fun unregisterBeforeLogout(expectedOwner: SessionFence) {
        val inFlight = synchronized(registrationLock) {
            // Reject callbacks for the retiring owner even while its authenticated logout is
            // still in flight. Never cancel a successor owner's registration from an old logout.
            registrationSuppressedOwner = expectedOwner
            if (registrationAttempt?.owner == expectedOwner) {
                registrationGeneration++
                registrationAttempt = null
                registrationJob.also { registrationJob = null }
            } else {
                null
            }
        }
        inFlight?.cancelAndJoin()
        registrationMutationLock.withLock {
            runFencedMutation(
                expectedOwner = expectedOwner,
                shouldProceed = { sessions.current()?.fence() == expectedOwner },
            ) {
                apiCalls.execute { api.unregisterPushToken(expectedOwner) }
            }
        }
    }

    internal suspend fun registerWithRetry(
        tokenProvider: suspend () -> String,
        retryDelaysMillis: List<Long> = RETRY_DELAYS_MILLIS,
    ) {
        val owner = sessions.current()?.fence() ?: return
        registerWithRetry(
            expectedOwner = owner,
            tokenProvider = tokenProvider,
            retryDelaysMillis = retryDelaysMillis,
            isCurrentGeneration = { sessions.current()?.fence() == owner },
        )
    }

    private suspend fun registerWithRetry(
        expectedOwner: SessionFence,
        tokenProvider: suspend () -> String,
        retryDelaysMillis: List<Long> = RETRY_DELAYS_MILLIS,
        isCurrentGeneration: () -> Boolean,
    ) {
        require(retryDelaysMillis.isNotEmpty()) { "Push registration needs a retry schedule" }
        require(retryDelaysMillis.all { it >= 0L }) { "Push registration delays cannot be negative" }
        var retry = 0
        while (isCurrentGeneration()) {
            try {
                registerIfEnabled(expectedOwner, tokenProvider, isCurrentGeneration)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentGeneration()) return
                if (!error.isTransientPushRegistrationFailure()) {
                    throw error
                }
                // The final rung is a cap, not an attempt limit. A device that first opens while
                // offline must eventually register without waiting for another token/session event.
                delay(retryDelaysMillis[retry.coerceAtMost(retryDelaysMillis.lastIndex)])
                retry = (retry + 1).coerceAtMost(retryDelaysMillis.lastIndex)
            }
        }
    }

    private suspend fun cancelRegistration() {
        val inFlight = synchronized(registrationLock) {
            if (registrationJob != null) registrationGeneration++
            registrationAttempt = null
            registrationJob.also { registrationJob = null }
        }
        inFlight?.cancelAndJoin()
    }

    private fun isCurrent(attempt: RegistrationAttempt): Boolean =
        synchronized(registrationLock) {
            registrationAttempt == attempt && registrationSuppressedOwner != attempt.owner
        } && sessions.current()?.fence() == attempt.owner

    /**
     * Once a push mutation starts, let its bounded HTTP exchange settle before a successor runs.
     * Cancelling only the client continuation cannot prove that the server abandoned the request;
     * serial completion makes the newest token (or logout removal) the final remote mutation.
     */
    private suspend fun <T> runFencedMutation(
        expectedOwner: SessionFence,
        shouldProceed: () -> Boolean,
        mutation: suspend () -> T,
    ): T? = withContext(NonCancellable) {
        if (!shouldProceed()) return@withContext null
        try {
            mutation()
        } catch (invalidated: SessionInvalidatedException) {
            if (sessions.current()?.fence() == expectedOwner && shouldProceed()) {
                throw invalidated
            }
            null
        }
    }

    internal suspend fun registerIfEnabled(
        tokenProvider: suspend () -> String = transport::currentToken,
    ) {
        val owner = sessions.current()?.fence() ?: return
        registerIfEnabled(
            expectedOwner = owner,
            tokenProvider = tokenProvider,
            shouldProceed = { sessions.current()?.fence() == owner },
        )
    }

    private suspend fun registerIfEnabled(
        expectedOwner: SessionFence,
        tokenProvider: suspend () -> String,
        shouldProceed: () -> Boolean,
    ) {
        if (!shouldProceed()) return
        val capabilities = apiCalls.execute { api.capabilities() }
        if (!shouldProceed()) return
        if (capabilities.features?.get(KitFeature.NOTIFICATIONS) != true) {
            runFencedMutation(expectedOwner, shouldProceed) {
                apiCalls.execute { api.unregisterPushToken(expectedOwner) }
            }
            return
        }

        // Do not ask the transport for a token until the server enables notifications.
        val token = tokenProvider()
        if (token.isBlank() || !shouldProceed()) return
        val registered = runFencedMutation(expectedOwner, shouldProceed) {
            apiCalls.execute {
                api.registerPushToken(
                    RegisterPushTokenRequest(provider = transport.provider, token = token),
                    expectedOwner,
                )
            }
        } ?: return
        if (!shouldProceed()) return
        check(registered.registered == true && registered.provider == transport.provider) {
            "The server did not confirm the requested ${transport.provider} push registration."
        }
    }

    private companion object {
        // Repeated transient failures settle at one attempt per minute. This keeps eventual
        // background delivery without turning an extended provider outage into a retry storm.
        val RETRY_DELAYS_MILLIS = listOf(1_000L, 5_000L, 15_000L, 60_000L)
    }
}

/** Retry only failures for which the same registration can safely succeed later. */
internal fun Throwable.isTransientPushRegistrationFailure(): Boolean =
    this is IOException || isKitConnectivityError() ||
        (this is KitWalletApiException &&
            (statusCode == 408 || statusCode == 425 || statusCode == 429 ||
                (statusCode != null && statusCode >= 500)))
