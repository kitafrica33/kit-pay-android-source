package com.kit.wallet.data.notifications

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.RegisterPushTokenRequest
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class PushTokenCoordinator @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val transport: PushMessagingTransport,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val registrationLock = Any()
    private var registrationJob: Job? = null

    fun start() {
        if (!transport.configured) return
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collect { sessionId ->
                if (sessionId == null) {
                    cancelRegistration()
                } else {
                    scheduleRegistration(transport::currentToken)
                }
            }
        }
    }

    fun tokenChanged(provider: String, token: String) {
        if (provider != transport.provider || !transport.configured || sessions.current() == null) {
            return
        }
        scheduleRegistration { token }
    }

    private fun scheduleRegistration(token: suspend () -> String) {
        synchronized(registrationLock) {
            registrationJob?.cancel()
            registrationJob = scope.launch {
                try {
                    registerWithRetry(token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Transport and capability discovery retry on the next token/session event.
                }
            }
        }
    }

    suspend fun unregisterBeforeLogout() {
        val inFlight = synchronized(registrationLock) {
            registrationJob.also { registrationJob = null }
        }
        inFlight?.cancelAndJoin()
        if (sessions.current() != null) {
            apiCalls.execute { api.unregisterPushToken() }
        }
    }

    internal suspend fun registerWithRetry(
        tokenProvider: suspend () -> String,
        retryDelaysMillis: List<Long> = RETRY_DELAYS_MILLIS,
    ) {
        var retry = 0
        while (true) {
            try {
                registerIfEnabled(tokenProvider)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!error.isTransientRegistrationFailure() || retry >= retryDelaysMillis.size ||
                    sessions.current() == null
                ) {
                    throw error
                }
                delay(retryDelaysMillis[retry++])
            }
        }
    }

    private fun cancelRegistration() {
        synchronized(registrationLock) {
            registrationJob?.cancel()
            registrationJob = null
        }
    }

    internal suspend fun registerIfEnabled(
        tokenProvider: suspend () -> String = transport::currentToken,
    ) {
        val sessionId = sessions.current()?.sessionId ?: return
        val capabilities = apiCalls.execute { api.capabilities() }
        if (capabilities.features?.get(KitFeature.NOTIFICATIONS) != true) {
            if (sessions.current()?.sessionId == sessionId) {
                apiCalls.execute { api.unregisterPushToken() }
            }
            return
        }
        if (sessions.current()?.sessionId != sessionId) return

        // Do not ask the transport for a token until the server enables notifications.
        val token = tokenProvider()
        if (token.isBlank() || sessions.current()?.sessionId != sessionId) return
        val registered = apiCalls.execute {
            api.registerPushToken(
                RegisterPushTokenRequest(provider = transport.provider, token = token),
            )
        }
        check(registered.registered == true && registered.provider == transport.provider) {
            "The server did not confirm the requested ${transport.provider} push registration."
        }
    }

    private fun Throwable.isTransientRegistrationFailure(): Boolean = when (this) {
        is IOException -> true
        is KitWalletApiException -> statusCode == 408 || statusCode == 425 || statusCode == 429 ||
            (statusCode != null && statusCode >= 500)
        else -> false
    }

    private companion object {
        val RETRY_DELAYS_MILLIS = listOf(1_000L, 5_000L, 15_000L)
    }
}
