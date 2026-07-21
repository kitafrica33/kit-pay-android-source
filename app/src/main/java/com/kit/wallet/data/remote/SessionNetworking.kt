package com.kit.wallet.data.remote

import com.kit.wallet.data.auth.requiresProfileSetup
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Singleton
class SessionHeaderInterceptor @Inject constructor(
    private val sessions: SessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = sessions.current()
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .apply {
                if (session != null) {
                    header("Authorization", "Bearer ${session.accessToken}")
                    header(SESSION_ID_HEADER, session.sessionId)
                }
            }
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val SESSION_ID_HEADER = "X-Kit-Wallet-Session-ID"
    }
}

interface SessionRefreshApi {
    @POST("api/kit-wallet/v1/auth/refresh")
    suspend fun refresh(
        @Header(SessionHeaderInterceptor.SESSION_ID_HEADER) sessionId: String,
        @Body request: RefreshSessionRequest,
    ): ApiEnvelope<AuthResultDto>
}

@Singleton
class AuthTokenRefresher @Inject constructor(
    private val refreshApi: dagger.Lazy<SessionRefreshApi>,
    private val apiCalls: ApiCallExecutor,
) {
    internal suspend fun refresh(current: SessionTokens): SessionRefreshResult {
        val result = try {
            apiCalls.execute {
                refreshApi.get()
                    .refresh(current.sessionId, RefreshSessionRequest(current.refreshToken))
            }
        } catch (error: KitWalletApiException) {
            if (error.isDefinitiveRefreshRejection()) {
                return SessionRefreshResult.Rejected
            }
            throw error
        }
        val session = result.session
            ?: return SessionRefreshResult.Rejected
        val user = result.user
        if (current.accountId != null && user != null && user.id != current.accountId) {
            return SessionRefreshResult.Rejected
        }
        val setupState = if (user == null) {
            current.profileSetupState
        } else if (
            user.profileSetupRequired == true || requiresProfileSetup(user.name, user.tag)
        ) {
            ProfileSetupState.REQUIRED
        } else {
            ProfileSetupState.COMPLETED
        }

        return SessionRefreshResult.Refreshed(
            SessionTokens(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                sessionId = session.sessionId,
                accessTokenExpiresAtEpochSeconds = session.accessExpiresAt?.let {
                    runCatching { Instant.parse(it).epochSecond }.getOrNull()
                },
                accountId = current.accountId ?: user?.id,
                cacheScopeId = current.cacheScopeId,
                profileSetupState = setupState,
            ),
        )
    }

    private fun KitWalletApiException.isDefinitiveRefreshRejection(): Boolean =
        statusCode == 401 || code in DEFINITIVE_REFRESH_REJECTION_CODES

    private companion object {
        val DEFINITIVE_REFRESH_REJECTION_CODES = setOf(
            "REFRESH_TOKEN_INVALID",
            "REFRESH_TOKEN_REUSED",
            "REFRESH_TOKEN_EXPIRED",
            "SESSION_REVOKED",
        )
    }
}

internal sealed interface SessionRefreshResult {
    data class Refreshed(val tokens: SessionTokens) : SessionRefreshResult
    data object Rejected : SessionRefreshResult
}

@Singleton
class SessionAuthenticator @Inject constructor(
    private val sessions: SessionStore,
    private val tokenRefresher: dagger.Lazy<AuthTokenRefresher>,
    private val walletCache: WalletCache,
) : Authenticator {
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_AUTH_ATTEMPTS) return null
        val failedAccessToken = response.request.bearerToken() ?: return null
        val failedSessionId = response.request.header(SessionHeaderInterceptor.SESSION_ID_HEADER)
            ?.takeIf(String::isNotBlank)
            ?: return null

        synchronized(refreshLock) {
            val latest = sessions.current() ?: return null

            // Never replay a request from a previous login under the current account.
            if (latest.sessionId != failedSessionId) return null

            // Another request refreshed this authenticated session while the response was in flight.
            if (latest.accessToken != failedAccessToken) {
                return response.request.withSession(latest)
            }

            val expected = sessions.snapshot()
            if (expected.fence != latest.fence()) return null

            val refreshResult = runCatching {
                runBlocking { tokenRefresher.get().refresh(latest) }
            }.getOrElse {
                // Connectivity, server, and decoding failures do not prove the session is invalid.
                return null
            }

            return when (refreshResult) {
                SessionRefreshResult.Rejected -> {
                    runBlocking { invalidate(latest) }
                    null
                }
                is SessionRefreshResult.Refreshed -> {
                    val adopted = runCatching {
                        runBlocking {
                            sessions.saveIfUnchanged(expected, refreshResult.tokens)
                        }
                    }.getOrElse {
                        // A malformed refresh result or local persistence failure must not erase
                        // the last known session.
                        return null
                    }
                    if (!adopted) return null
                    response.request.withSession(refreshResult.tokens)
                }
            }
        }
    }

    private suspend fun invalidate(failedSession: SessionTokens) {
        var failure: Throwable? = null
        try {
            sessions.clearIfCurrent(failedSession.fence())
        } catch (error: Throwable) {
            failure = error
        }
        try {
            // The owner check prevents a late failure from deleting a newer account's rows.
            walletCache.clearUserData(failedSession.cacheScopeId)
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun Request.bearerToken(): String? {
        val authorization = header("Authorization") ?: return null
        if (!authorization.startsWith(BEARER_PREFIX)) return null
        return authorization.removePrefix(BEARER_PREFIX).takeIf(String::isNotBlank)
    }

    private fun Request.withSession(session: SessionTokens): Request = newBuilder()
        .header("Authorization", "Bearer ${session.accessToken}")
        .header(SessionHeaderInterceptor.SESSION_ID_HEADER, session.sessionId)
        .build()

    private fun responseCount(response: Response): Int {
        var current: Response? = response
        var count = 1
        while (current?.priorResponse != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        const val MAX_AUTH_ATTEMPTS = 2
    }
}
