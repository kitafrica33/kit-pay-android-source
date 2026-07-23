package com.kit.wallet

import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.local.WalletEntity
import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.messaging.AccountMessageHistoryRetention
import com.kit.wallet.data.messaging.NoOpAccountMessageHistoryRetention
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
import com.kit.wallet.data.session.SessionDiskPayload
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.SecureMessagingResetProofFence
import com.kit.wallet.data.session.decodeSessionPersistingLegacyNonce
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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
    fun `migrated legacy nonce recovers a lost refresh response after process restart`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(SessionDiskPayload::class.java)
        var durableSession = adapter.toJson(
            SessionDiskPayload(
                accessToken = OLD_SESSION.accessToken,
                refreshToken = OLD_SESSION.refreshToken,
                sessionId = OLD_SESSION.sessionId,
                accessTokenExpiresAtEpochSeconds = null,
                accountId = OLD_SESSION.accountId,
                cacheScopeId = OLD_SESSION.cacheScopeId,
            ),
        )

        fun restoreAfterProcessStart(): SessionTokens = decodeSessionPersistingLegacyNonce(
            encryptedSession = durableSession,
            decodePayload = { checkNotNull(adapter.fromJson(it)) },
            encodePayload = adapter::toJson,
            persistEncryptedSession = {
                durableSession = it
                true
            },
        )

        var committedNonce: String? = null
        val api = FakeRefreshApi { _, request ->
            if (committedNonce == null) {
                committedNonce = request.refreshReplayNonce
                throw IOException("refresh committed but its response was lost")
            }
            assertEquals(committedNonce, request.refreshReplayNonce)
            successfulRefresh(REFRESHED_SESSION)
        }

        val firstProcess = restoreAfterProcessStart()
        val firstSessions = FakeSessionStore(firstProcess)
        assertNull(
            authenticator(firstSessions, FakeWalletCache(firstProcess.cacheScopeId), api)
                .authenticate(null, unauthorizedResponse(firstProcess)),
        )
        assertEquals(firstProcess, firstSessions.current())

        val secondProcess = restoreAfterProcessStart()
        assertEquals(firstProcess.refreshReplayNonce, secondProcess.refreshReplayNonce)
        val replayed = authenticator(
            FakeSessionStore(secondProcess),
            FakeWalletCache(secondProcess.cacheScopeId),
            api,
        ).authenticate(null, unauthorizedResponse(secondProcess))

        assertNotNull(replayed)
        assertEquals(2, api.calls)
        assertEquals(firstProcess.refreshReplayNonce, committedNonce)
    }

    @Test
    fun `concurrent 401s reuse the refresh completed by the first request`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val refreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val api = FakeRefreshApi { _, request ->
            assertEquals(OLD_SESSION.refreshReplayNonce, request.refreshReplayNonce)
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
            val persisted = sessions.current()
            assertEquals(
                REFRESHED_SESSION.copy(
                    refreshReplayNonce = checkNotNull(persisted).refreshReplayNonce,
                ),
                persisted,
            )
            assertTrue(persisted.refreshReplayNonce != OLD_SESSION.refreshReplayNonce)
            assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `refresh adoption retains proof metadata written while rotation is in flight`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ ->
            assertTrue(
                sessions.recordMessagingResetPendingIfCurrent(
                    OLD_SESSION.fence(),
                    RESET_PENDING,
                ),
            )
            successfulRefresh(REFRESHED_SESSION)
        }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNotNull(retried)
        assertEquals(REFRESHED_SESSION.accessToken, sessions.current()?.accessToken)
        assertEquals(REFRESHED_SESSION.refreshToken, sessions.current()?.refreshToken)
        assertEquals(RESET_PENDING, sessions.current()?.messagingResetProof)
    }

    @Test
    fun `in flight refresh retains concurrently completed profile setup`() {
        val expected = OLD_SESSION.copy(profileSetupState = ProfileSetupState.REQUIRED)
        val sessions = FakeSessionStore(expected)
        val cache = FakeWalletCache(expected.cacheScopeId)
        val refreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val api = FakeRefreshApi { _, _ ->
            refreshEntered.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS))
            successfulRefresh(REFRESHED_SESSION)
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val refresh = executor.submit<Request?> {
                authenticator(sessions, cache, api)
                    .authenticate(null, unauthorizedResponse(expected))
            }
            assertTrue(refreshEntered.await(5, TimeUnit.SECONDS))
            runBlocking {
                assertTrue(
                    sessions.updateProfileSetupState(
                        expected.fence(),
                        ProfileSetupState.COMPLETED,
                    ),
                )
            }
            releaseRefresh.countDown()

            val retried = refresh.get(5, TimeUnit.SECONDS)

            assertNotNull(retried)
            assertEquals(REFRESHED_SESSION.accessToken, sessions.current()?.accessToken)
            assertEquals(REFRESHED_SESSION.refreshToken, sessions.current()?.refreshToken)
            assertEquals(ProfileSetupState.COMPLETED, sessions.current()?.profileSetupState)
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `default refresh adoption cannot clobber a proof written between its reads`() {
        val sessions = InterleavingDefaultSessionStore(OLD_SESSION, RESET_PENDING)

        val adopted = runBlocking {
            sessions.adoptRefreshedCredentialsIfCurrent(OLD_SESSION, REFRESHED_SESSION)
        }

        assertEquals(false, adopted)
        assertEquals(OLD_SESSION.accessToken, sessions.current()?.accessToken)
        assertEquals(OLD_SESSION.refreshToken, sessions.current()?.refreshToken)
        assertEquals(RESET_PENDING, sessions.current()?.messagingResetProof)
    }

    @Test
    fun `refresh adoption rejects the same token strings with another replay nonce`() {
        val currentGeneration = OLD_SESSION.copy(
            refreshReplayNonce = "11111111-1111-4111-8111-111111111111",
        )
        val staleExpected = currentGeneration.copy(
            refreshReplayNonce = "22222222-2222-4222-8222-222222222222",
        )
        val sessions = FakeSessionStore(currentGeneration)

        val adopted = runBlocking {
            sessions.adoptRefreshedCredentialsIfCurrent(staleExpected, REFRESHED_SESSION)
        }

        assertEquals(false, adopted)
        assertEquals(currentGeneration, sessions.current())
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
    fun `blank rotated credentials are rejected without replacing the session`() {
        listOf(
            REFRESHED_SESSION.copy(accessToken = "  "),
            REFRESHED_SESSION.copy(refreshToken = "\t"),
        ).forEach { invalidRefresh ->
            val sessions = FakeSessionStore(OLD_SESSION)
            val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
            val api = FakeRefreshApi { _, _ -> successfulRefresh(invalidRefresh) }

            val retried = authenticator(sessions, cache, api)
                .authenticate(null, unauthorizedResponse(OLD_SESSION))

            assertNull(retried)
            assertEquals(OLD_SESSION, sessions.current())
            assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
            assertTrue(cache.clearAttempts.isEmpty())
        }
    }

    @Test
    fun `ambiguous 401 refresh response preserves the matching session for retry`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ -> throw httpFailure(401) }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertEquals(OLD_SESSION, sessions.current())
        assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
        assertTrue(cache.clearAttempts.isEmpty())
    }

    @Test
    fun `definitive refresh error code clears the matching session and cache owner`() {
        listOf(
            "REFRESH_TOKEN_INVALID",
            "REFRESH_TOKEN_REUSED",
            "REFRESH_TOKEN_EXPIRED",
            "SESSION_REVOKED",
        ).forEach { rejectionCode ->
            val sessions = FakeSessionStore(OLD_SESSION)
            val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
            val api = FakeRefreshApi { _, _ -> throw httpFailure(401, rejectionCode) }

            val retried = authenticator(sessions, cache, api)
                .authenticate(null, unauthorizedResponse(OLD_SESSION))

            assertNull(retried)
            assertNull(sessions.current())
            assertNull(cache.currentOwner)
        }
    }

    @Test
    fun `definitive authenticator rejection retains session when final history snapshot fails`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ -> throw httpFailure(401, "SESSION_REVOKED") }
        val failure = IllegalStateException("final history snapshot failed")
        val history = object : AccountMessageHistoryRetention {
            override suspend fun snapshotActiveHistory(target: SessionFence) {
                throw failure
            }

            override suspend fun eraseAccount(target: SessionFence) = Unit
        }

        val observed = runCatching {
            authenticator(sessions, cache, api, history)
                .authenticate(null, unauthorizedResponse(OLD_SESSION))
        }.exceptionOrNull()

        assertEquals(failure, observed)
        assertEquals(OLD_SESSION, sessions.current())
        assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
        assertTrue(cache.clearAttempts.isEmpty())
    }

    @Test
    fun `refresh response for a different account preserves the matching owner`() {
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
        assertEquals(OLD_SESSION, sessions.current())
        assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
        assertTrue(cache.clearAttempts.isEmpty())
    }

    @Test
    fun `successful refresh without credentials preserves the matching owner`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ ->
            ApiEnvelope(
                ok = true,
                data = AuthResultDto(state = "authenticated", session = null, user = null),
            )
        }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertEquals(OLD_SESSION, sessions.current())
        assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
        assertTrue(cache.clearAttempts.isEmpty())
    }

    @Test
    fun `terminal code without an HTTP 401 preserves the matching owner`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ ->
            ApiEnvelope(
                ok = false,
                error = ApiErrorDto(
                    code = "SESSION_REVOKED",
                    message = "Malformed success response",
                ),
            )
        }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertEquals(OLD_SESSION, sessions.current())
        assertEquals(OLD_SESSION.cacheScopeId, cache.currentOwner)
        assertTrue(cache.clearAttempts.isEmpty())
    }

    @Test
    fun `late rejection cannot clear a newer session or cache owner`() {
        val sessions = FakeSessionStore(OLD_SESSION)
        val cache = FakeWalletCache(OLD_SESSION.cacheScopeId)
        val api = FakeRefreshApi { _, _ ->
            sessions.save(NEW_ACCOUNT_SESSION)
            cache.claim(NEW_ACCOUNT_SESSION.cacheScopeId)
            throw httpFailure(401, "SESSION_REVOKED")
        }

        val retried = authenticator(sessions, cache, api)
            .authenticate(null, unauthorizedResponse(OLD_SESSION))

        assertNull(retried)
        assertEquals(NEW_ACCOUNT_SESSION, sessions.current())
        assertEquals(NEW_ACCOUNT_SESSION.cacheScopeId, cache.currentOwner)
        assertTrue(cache.clearAttempts.isEmpty())
    }

    private fun authenticator(
        sessions: FakeSessionStore,
        cache: FakeWalletCache,
        api: FakeRefreshApi,
        messageHistory: AccountMessageHistoryRetention = NoOpAccountMessageHistoryRetention,
    ): SessionAuthenticator {
        val apiCalls = ApiCallExecutor(
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        )
        val refresher = AuthTokenRefresher(dagger.Lazy { api }, apiCalls)
        return SessionAuthenticator(
            sessions = sessions,
            tokenRefresher = dagger.Lazy { refresher },
            walletCache = cache,
            messageHistory = messageHistory,
        )
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

    private fun httpFailure(status: Int, code: String? = null): HttpException {
        val body = if (code == null) {
            """{"ok":false}"""
        } else {
            """{"ok":false,"error":{"code":"$code","message":"Rejected"}}"""
        }
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

        override suspend fun recordMessagingResetPendingIfCurrent(
            expected: SessionFence,
            pending: SecureMessagingResetProofFence,
        ): Boolean {
            val current = state.value ?: return false
            if (current.fence() != expected) return false
            val existing = current.messagingResetProof
            if (existing != null) {
                return existing.copy(resultingEnrollmentEpoch = null) == pending
            }
            save(current.copy(messagingResetProof = pending))
            return true
        }

        override suspend fun clear() {
            state.value = null
            revision++
        }
    }

    /** Exercises the interface default without inheriting the production store's mutex. */
    private class InterleavingDefaultSessionStore(
        initial: SessionTokens,
        private val interleavedProof: SecureMessagingResetProofFence,
    ) : SessionStore {
        private val state = MutableStateFlow<SessionTokens?>(initial)
        private var revision = 0L
        private var interleaveNextCurrentRead = true
        override val session: StateFlow<SessionTokens?> = state

        override fun current(): SessionTokens? {
            val observed = state.value
            if (interleaveNextCurrentRead) {
                interleaveNextCurrentRead = false
                state.value = checkNotNull(observed).copy(messagingResetProof = interleavedProof)
                revision++
            }
            return observed
        }

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
        val RESET_PENDING = SecureMessagingResetProofFence(
            serverDeviceId = "device-1",
            previousEnrollmentEpoch = 7,
            previousRegistrationId = 42,
            previousIdentityKeySha256 = "1".repeat(64),
            previousBundleVersion = 3,
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
