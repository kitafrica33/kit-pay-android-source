package com.kit.wallet.data.messaging

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Opens local encrypted message history as part of process/session restoration.
 *
 * Unlike the sync worker, this path has no connectivity constraint and performs no remote call.
 * It waits until the messaging state store is open, resolves only exact-owner encrypted binding
 * metadata, then seeds the coordinator's normal activation attempt. A later online sync advances
 * that same attempt to exchange readiness.
 */
@Singleton
internal class SecureMessagingLocalHistoryBootstrapper @Inject constructor(
    private val sessions: SessionStore,
    private val sessionLifecycle: SecureMessagingSessionLifecycle,
    private val bindingResolver: SecureMessagingAuthBindingResolver,
    private val activation: SecureMessagingActivationCoordinator,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            combine(
                sessions.session,
                sessionLifecycle.stateAvailable,
            ) { session, stateAvailable ->
                session?.fence()?.takeIf { stateAvailable }
            }
                .distinctUntilChanged()
                .collectLatest { owner ->
                    owner?.let { prepareWhileCurrent(it) }
                }
        }
    }

    private suspend fun prepareWhileCurrent(owner: SessionFence) {
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
        while (isCurrentAndAvailable(owner)) {
            try {
                val binding = bindingResolver.resolvePersisted(owner) ?: return
                // Serialize the final owner check and lifecycle generation creation with
                // KeystoreSessionStore replacement. Otherwise A could pass a sampled check after
                // its erasure, then recreate an A generation immediately before B is published.
                val prepared = sessions.withCurrentSession(owner) {
                    if (!sessionLifecycle.stateAvailable.value) {
                        return@withCurrentSession null
                    }
                    activation.prepareActivationIfIdle(owner, binding)
                }
                if (prepared != null) return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionInvalidatedException) {
                return
            } catch (changed: SecureMessagingAuthenticationEpochChangedException) {
                return
            } catch (error: Exception) {
                if (!isRetryableSecureMessagingStateFailure(error)) return
            }
            if (!isCurrentAndAvailable(owner)) return
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        }
    }

    private fun isCurrentAndAvailable(owner: SessionFence): Boolean =
        sessionLifecycle.stateAvailable.value && sessions.current()?.fence() == owner

    private companion object {
        const val INITIAL_RETRY_DELAY_MILLIS = 250L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
    }
}
