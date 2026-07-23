package com.kit.wallet.data.remote

import com.kit.wallet.data.auth.requiresProfileSetup
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.messaging.AccountMessageHistoryRetention
import com.kit.wallet.data.messaging.NoOpAccountMessageHistoryRetention
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Metadata-only client for resetting one snapshotted messaging enrollment without consulting
 * mutable local session state or invoking the normal access-token authenticator.
 */
interface SecureMessagingEnrollmentRecoveryApi {
    @POST("api/kit-wallet/v1/messaging/enrollment/reset")
    suspend fun reset(
        @Header("Authorization") authorization: String,
        @Header(SessionHeaderInterceptor.SESSION_ID_HEADER) sessionId: String,
        @Body request: ResetMessagingEnrollmentRequest,
    ): ApiEnvelope<ResetMessagingEnrollmentDto>
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
                    .refresh(
                        current.sessionId,
                        RefreshSessionRequest(
                            refreshToken = current.refreshToken,
                            refreshReplayNonce = current.refreshReplayNonce,
                        ),
                    )
            }
        } catch (error: KitWalletApiException) {
            if (error.isDefinitiveSessionRejection()) {
                return SessionRefreshResult.Rejected(error)
            }
            throw error
        }
        val session = checkNotNull(result.session) {
            "Successful session refresh omitted credentials"
        }
        val user = result.user
        if (current.accountId != null && user != null && user.id != current.accountId) {
            error("Session refresh returned a different account")
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
            tokens = SessionTokens(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                sessionId = session.sessionId,
                accessTokenExpiresAtEpochSeconds = session.accessExpiresAt?.let {
                    runCatching { Instant.parse(it).epochSecond }.getOrNull()
                },
                accountId = current.accountId ?: user?.id,
                cacheScopeId = current.cacheScopeId,
                profileSetupState = setupState,
                messagingResetProof = current.messagingResetProof,
                refreshReplayNonce = java.util.UUID.randomUUID().toString(),
            ),
            user = user,
        )
    }

}

/** Only an authenticated backend 401 with a documented terminal code may erase local state. */
internal fun KitWalletApiException.isDefinitiveSessionRejection(): Boolean =
    statusCode == 401 && code in DEFINITIVE_SESSION_REJECTION_CODES

private val DEFINITIVE_SESSION_REJECTION_CODES = setOf(
    "REFRESH_TOKEN_INVALID",
    "REFRESH_TOKEN_REUSED",
    "REFRESH_TOKEN_EXPIRED",
    "SESSION_REVOKED",
)

internal sealed interface SessionRefreshResult {
    data class Refreshed(
        val tokens: SessionTokens,
        val user: UserDto? = null,
    ) : SessionRefreshResult

    data class Rejected(val error: KitWalletApiException) : SessionRefreshResult
}

/** Serializes every one-time refresh-token rotation across OkHttp and explicit recovery calls. */
@Singleton
class AuthSessionRefreshCoordinator @Inject constructor() {
    private val mutex = Mutex()

    internal suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock { block() }
}

@Singleton
class SessionAuthenticator @Inject constructor(
    private val sessions: SessionStore,
    private val tokenRefresher: dagger.Lazy<AuthTokenRefresher>,
    private val walletCache: WalletCache,
    private val refreshCoordinator: AuthSessionRefreshCoordinator = AuthSessionRefreshCoordinator(),
    private val messageHistory: AccountMessageHistoryRetention =
        NoOpAccountMessageHistoryRetention,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_AUTH_ATTEMPTS) return null
        val failedAccessToken = response.request.bearerToken() ?: return null
        val failedSessionId = response.request.header(SessionHeaderInterceptor.SESSION_ID_HEADER)
            ?.takeIf(String::isNotBlank)
            ?: return null

        return runBlocking {
            refreshCoordinator.serialized {
                val latest = sessions.current() ?: return@serialized null

                // Never replay a request from a previous login under the current account.
                if (latest.sessionId != failedSessionId) return@serialized null

                // Another request refreshed this session while the response was in flight.
                if (latest.accessToken != failedAccessToken) {
                    return@serialized response.request.withSession(latest)
                }

                val refreshResult = runCatching {
                    tokenRefresher.get().refresh(latest)
                }.getOrElse {
                    // Connectivity, server, and decoding failures do not prove invalidity.
                    return@serialized null
                }

                when (refreshResult) {
                    is SessionRefreshResult.Rejected -> {
                        invalidate(latest)
                        null
                    }
                    is SessionRefreshResult.Refreshed -> {
                        val adopted = runCatching {
                            sessions.adoptRefreshedCredentialsIfCurrent(
                                latest,
                                refreshResult.tokens,
                            )
                        }.getOrElse {
                            // Persistence failure must not erase the last known session.
                            return@serialized null
                        }
                        if (!adopted) return@serialized null
                        response.request.withSession(refreshResult.tokens)
                    }
                }
            }
        }
    }

    private suspend fun invalidate(failedSession: SessionTokens) {
        var failure: Throwable? = null
        var targetCleared = false
        try {
            targetCleared = sessions.clearIfCredentialsCurrentAfterFinalMessagingSnapshot(
                expected = failedSession,
                allowPermanentlyUnavailableSnapshot = true,
            ) {
                messageHistory.snapshotActiveHistory(failedSession.fence())
            }
        } catch (error: Throwable) {
            failure = error
            targetCleared = sessions.current() == null
        }
        if (!targetCleared) {
            failure?.let { throw it }
            return
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
