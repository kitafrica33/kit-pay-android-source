package com.kit.wallet

import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.local.WalletEntity
import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.ApiErrorDto
import com.kit.wallet.data.remote.AuthResultDto
import com.kit.wallet.data.remote.AuthTokenRefresher
import com.kit.wallet.data.remote.RefreshSessionRequest
import com.kit.wallet.data.remote.SessionAuthenticator
import com.kit.wallet.data.remote.SessionDto
import com.kit.wallet.data.remote.SessionHeaderInterceptor
import com.kit.wallet.data.remote.SessionRefreshApi
import com.kit.wallet.data.remote.UserDto
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response as RetrofitResponse

class SessionNetworkingTest {
    @Test
    fun `concurrent 401s reuse the refresh completed by the first request`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val refreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val api = FakeRefreshApi { _, _ ->
            refreshEntered.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS))
            successfulRefresh(REFRESHED_SESSION)
        }
        val authenticator = authenticator(sessions, cache, api)
        val failedResponse = unauthorizedResponse(OLD_SESSION)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<Request?> {
                authenticator.authenticate(null, failedResponse)
            }
            assertTrue(refreshEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Request?> {
                authenticator.authenticate(null, failedResponse)
            }
            releaseRefresh.countDown()

            val retriedRequests = listOf(
                first.get(5, TimeUnit.SECONDS),
                second.get(5, TimeUnit.SECONDS),
            )
            retriedRequests.forEach { retried ->
                assertNotNull(retried)
                assertEquals(
                    "Bearer ${REFRESHED_SESSION.accessToken}",
                    retried?.header("Authorization"),
                )
                assertEquals(
                    REFRESHED_SESSION.sessionId,
                    retried?.header(SessionHeaderInterceptor.SESSION_ID_HEADER),
                )
            }
            assertEquals(1, api.calls)
            assertEquals(REFRESHED_SESSION, sessions.current())
            assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `request from an obsolete login is not replayed with a newer account`() {
        val sessions = FakeSessionStore(NEW_ACCOUNT_SESSION)
        val cache = FakeWalletCache(NEW_ACCOUNT_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ -> successfulRefresh(REFRESHED_SESSION) }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertEquals(0, api.calls)
        assertEquals(NEW_ACCOUNT_SESSION, sessions.current())
        assertEquals(NEW_ACCOUNT_SESSION.cacheScopeId, cache.currentOwner)
    }

    @Test
    fun `transient refresh failures preserve the session and owned cache`() {
        val failures = listOf<Throwable>(
            IOException("network unavailable"),
            httpFailure(503),
            IllegalStateException("response could not be decoded"),
        )

        failures.forEach { failure ->
            val sessions = FakeSessionStore(OLD_SESSION)
            val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
            val api = FakeRefreshApi { _, _ -> throw failure }

            val retried = authenticator(sessions, cache, api)
                .authenticate(null, unauthorizedResponse(OLD_SESSION))

            assertNull(retried)
            assertEquals(OLD_SESSION, sessions.current())
            assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
            assertTrue(cache.clearAttempts.isEmpty())
        }
    }

    @Test
    fun `401 refresh rejection clears the matching session and cache owner`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ -> throw httpFailure(401) }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertNull(sessions.current())
        assertNull(cache.currentOwner)
        assertEquals(listOf(OLD_SESSION.cacheScopeId), cache.clearAttempts)
    }

    @Test
    fun `definitive refresh error code clears the matching session and cache owner`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ ->
            ApiEnvelope(
                ok = false,
                error = ApiErrorDto(
                    code = "REFRESH_TOKEN_REUSED",
                    message = "Refresh token was already used",
                ),
            )
        }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertNull(sessions.current())
        assertNull(cache.currentOwner)
    }

    @Test
    fun `refresh response for a different account invalidates the matching owner`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ ->
            successfulRefresh(
                session = REFRESHED_SESSION,
                user = UserDto(id = "account-2", name = "Different User", tag = "different"),
            )
        }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertNull(sessions.current())
        assertNull(cache.currentOwner)
    }

    @Test
    fun `late rejection cannot clear a newer session or cache owner`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ ->
            sessions.save(NEW_ACCOUNT_SESSION)
            cache.claim(NEW_ACCOUNT_SESSION.cacheScopeId)
            ApiEnvelope(
                ok = false,
                error = ApiErrorDto(
                    code = "SESSION_REVOKED",
                    message = "Old session revoked",
                ),
            )
        }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertEquals(NEW_ACCOUNT_SESSION, sessions.current())
        assertEquals(NEW_ACCOUNT_SESSION.cacheScopeId, cache.currentOwner)
        assertEquals(listOf(OLD_SESSION.cacheScopeId), cache.clearAttempts)
    }

    private fun authenticator(
        sessions: FakeSessionStore,
        cache: FakeWalletCache,
        api: FakeRefreshApi,
    ): SessionAuthenticator {
        val apiCalls = ApiCallExecutor(
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        )
        val refresher = AuthTokenRefresher(dagger.Lazy { api }, apiCalls)
        return SessionAuthenticator(sessions, dagger.Lazy { refresher }, cache)
    }

    private fun unauthorizedResponse(session: SessionTokens): Response {
        val request = Request.Builder()
            .url("https://pay.kit.africa/api/kit-wallet/v1/bootstrap")
            .header("Authorization", "Bearer ${session.accessToken}")
            .header(SessionHeaderInterceptor.SESSION_ID_HEADER, session.sessionId)
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(ByteArray(0).toResponseBody(null))
            .build()
    }

    private fun successfulRefresh(
        session: SessionTokens,
        user: UserDto? = null,
    ): ApiEnvelope<AuthResultDto> = ApiEnvelope(
        ok = true,
        data = AuthResultDto(
            state = "authenticated",
            session = SessionDto(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                sessionId = session.sessionId,
            ),
            user = user,
        ),
    )

    private fun httpFailure(status: Int): HttpException {
        val body = """{"ok":false}"""
            .toResponseBody("application/json".toMediaType())
        return HttpException(RetrofitResponse.error<ApiEnvelope<AuthResultDto>>(status, body))
    }

    private class FakeRefreshApi(
        private val result: suspend (String, RefreshSessionRequest) -> ApiEnvelope<AuthResultDto>,
    ) : SessionRefreshApi {
        var calls: Int = 0
            private set

        override suspend fun refresh(
            sessionId: String,
            request: RefreshSessionRequest,
        ): ApiEnvelope<AuthResultDto> {
            calls++
            return result(sessionId, request)
        }
    }

    private class FakeSessionStore(initial: SessionTokens?) : SessionStore {
        private val state = MutableStateFlow(initial)
        private var revision = 0L
        override val session: StateFlow<SessionTokens?> = state

        override fun current(): SessionTokens? = state.value

        override fun snapshot(): SessionSnapshot = SessionSnapshot(
            revision = revision,
            fence = state.value?.fence(),
        )

        override suspend fun save(tokens: SessionTokens) {
            state.value = tokens
            revision++
        }

        override suspend fun saveIfUnchanged(
            expected: SessionSnapshot,
            tokens: SessionTokens,
        ): Boolean {
            if (snapshot() != expected) return false
            save(tokens)
            return true
        }

        override suspend fun updateProfileSetupState(
            expected: SessionFence,
            state: ProfileSetupState,
        ): Boolean {
            val current = this.state.value ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = state))
            return true
        }

        override suspend fun <T> withCurrentSession(
            expected: SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = state.value ?: throw SessionInvalidatedException()
            if (current.fence() != expected) throw SessionInvalidatedException()
            return block(current)
        }

        override suspend fun clearIfCurrent(expected: SessionFence): Boolean {
            if (state.value?.fence() != expected) return false
            clear()
            return true
        }

        override suspend fun clear() {
            state.value = null
            revision++
        }
    }

    private class FakeWalletCache(initialOwner: String?) : WalletCache {
        private val owner = MutableStateFlow(initialOwner)
        override val ownerScope: StateFlow<String?> = owner
        val clearAttempts = mutableListOf<String?>()
        val currentOwner: String?
            get() = owner.value

        fun claim(ownerScopeId: String) {
            owner.value = ownerScopeId
        }

        override suspend fun replaceProfile(ownerScopeId: String, profile: ProfileEntity) = Unit

        override suspend fun replaceProfileAndWallets(
            ownerScopeId: String,
            profile: ProfileEntity,
            wallets: List<WalletEntity>,
        ) = Unit

        override suspend fun replaceWallets(
            ownerScopeId: String,
            wallets: List<WalletEntity>,
        ) = Unit

        override suspend fun selectedWallet(ownerScopeId: String): WalletEntity? = null

        override suspend fun replaceTransactions(
            ownerScopeId: String,
            walletUuid: String,
            transactions: List<WalletTransactionEntity>,
            nextCursor: String?,
        ) = Unit

        override suspend fun clearUserData(ownerScopeId: String?): Boolean {
            clearAttempts += ownerScopeId
            if (ownerScopeId != null && owner.value != null && owner.value != ownerScopeId) {
                return false
            }
            owner.value = null
            return true
        }
    }

    private companion object {
        val OLD_SESSION = SessionTokens(
            accessToken = "access-old",
            refreshToken = "refresh-old",
            sessionId = "session-1",
            accountId = "account-1",
            cacheScopeId = "scope-1",
        )
        val REFRESHED_SESSION = OLD_SESSION.copy(
            accessToken = "access-new",
            refreshToken = "refresh-new",
        )
        val NEW_ACCOUNT_SESSION = SessionTokens(
            accessToken = "access-account-2",
            refreshToken = "refresh-account-2",
            sessionId = "session-2",
            accountId = "account-2",
            cacheScopeId = "scope-2",
        )
    }
}
