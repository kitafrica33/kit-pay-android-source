package com.kit.wallet

import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.toCachedSessionAssurance
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.AuthTokenRefresher
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.LoginBiometricAssertionRequest
import com.kit.wallet.data.remote.SessionAssuranceDto
import com.kit.wallet.data.remote.SessionAuthenticator
import com.kit.wallet.data.remote.SessionHeaderInterceptor
import com.kit.wallet.data.remote.SessionRefreshApi
import com.kit.wallet.data.repository.KycVerificationState
import com.kit.wallet.data.session.CachedSessionAssurance
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.feature.auth.SessionAssuranceUiState
import com.kit.wallet.feature.auth.SessionAssuranceViewModel
import com.kit.wallet.feature.auth.shouldPresentSessionUnlockGate
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Real Retrofit/OkHttp unlock and refresh exchanges; no device biometric or live server calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionUnlockRefreshRegressionTest {
    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val executor = ApiCallExecutor(moshi)
    private val clients = mutableListOf<OkHttpClient>()
    private val viewModels = mutableListOf<SessionAssuranceViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `correct review PIN after bearer refresh opens the app and retains read only scope`() = runTest {
        val sessions = sessions()
        val before = sessions.snapshot()
        val refreshedScope = AtomicReference<CachedSessionAssurance?>()
        server.dispatcher = responding { request ->
            when (request.path) {
                REFRESH -> refreshSuccess()
                PIN -> if (request.getHeader("Authorization") == "Bearer refreshed-access") {
                    refreshedScope.set(sessions.current()?.cachedAssurance)
                    unlocked()
                } else {
                    rejection("AUTHENTICATION_REQUIRED")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val viewModel = viewModel(sessions)

        viewModel.unlockWithPin("2947")

        val state = settled(viewModel)
        assertFalse(state.required)
        assertFalse(shouldPresentSessionUnlockGate(state, KycVerificationState.VERIFIED))
        assertTrue(state.financialReadOnly)
        assertEquals(true, state.financialAccessAllowed)
        assertEquals("app_review", state.financialAccessBasis)
        assertEquals(before.fence, sessions.snapshot().fence)
        assertTrue(sessions.snapshot().revision > before.revision)
        assertEquals("refreshed-access", sessions.current()?.accessToken)
        assertEquals(true, refreshedScope.get()?.financialReadOnly)
        assertEquals("app_review", refreshedScope.get()?.financialAccessBasis)
        assertEquals("unlock_session", refreshedScope.get()?.communicationRequiredAction)
        assertEquals(listOf(PIN, REFRESH, PIN), requests(3).map { it.path })
    }

    @Test
    fun `incorrect review PIN is sent once and leaves an editable locked gate`() = runTest {
        val sessions = sessions()
        val before = sessions.snapshot()
        server.dispatcher = responding { request ->
            if (request.path == REFRESH) refreshSuccess() else rejection("INVALID_LOGIN_PIN")
        }
        val viewModel = viewModel(sessions)

        viewModel.unlockWithPin("1111")

        val state = settled(viewModel)
        assertTrue(state.required)
        assertTrue(state.loginUnlockRequired)
        assertTrue(state.financialReadOnly)
        assertEquals("Proof rejected", state.error)
        assertEquals(before, sessions.snapshot())
        assertEquals(1, server.requestCount)
        assertEquals(PIN, requests(1).single().path)
    }

    @Test
    fun `biometric proof rejections preserve the original error without refreshing or replaying`() = runTest {
        val sessions = sessions()
        val before = sessions.snapshot()
        val code = AtomicReference("")
        server.dispatcher = responding { request ->
            if (request.path == REFRESH) refreshSuccess() else rejection(code.get())
        }
        val api = api(sessions)
        listOf("BIOMETRIC_CHALLENGE_INVALID", "BIOMETRIC_CHALLENGE_EXPIRED", "BIOMETRIC_ASSERTION_INVALID")
            .forEachIndexed { index, errorCode ->
                code.set(errorCode)
                val error = runCatching {
                    executor.execute {
                        api.assertLoginBiometricChallenge(
                            LoginBiometricAssertionRequest("challenge", "nonce", "signature"),
                        )
                    }
                }.exceptionOrNull() as? KitWalletApiException
                assertNotNull(error)
                assertEquals(errorCode, error?.code)
                assertEquals("Proof rejected", error?.message)
                assertEquals(401, error?.statusCode)
                assertEquals(before, sessions.snapshot())
                assertEquals(index + 1, server.requestCount)
                assertEquals(BIOMETRIC_ASSERT, requests(1).single().path)
            }
    }

    @Test
    fun `same owner metadata write during PIN unlock does not discard success`() = runTest {
        val sessions = sessions()
        server.dispatcher = responding {
            runBlocking {
                sessions.updateProfileSetupState(sessions.current()!!.fence(), ProfileSetupState.COMPLETED)
            }
            unlocked()
        }
        val viewModel = viewModel(sessions)

        viewModel.unlockWithPin("2947")

        assertFalse(settled(viewModel).required)
        assertEquals(ProfileSetupState.COMPLETED, sessions.current()?.profileSetupState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `replacement login for the same account cannot inherit an in flight PIN success`() = runTest {
        replaceSessionDuringUnlock(changeAccount = false)
    }

    @Test
    fun `replacement account cannot inherit an in flight PIN success`() = runTest {
        replaceSessionDuringUnlock(changeAccount = true)
    }

    @Test
    fun `account switch before dispatch never sends the previous PIN using successor credentials`() = runTest {
        val sessions = sessions()
        val successor = sessions.current()!!.copy(
            accessToken = "successor-access", refreshToken = "successor-refresh",
            sessionId = "successor-session", accountId = "successor-account", cacheScopeId = "successor-cache",
        )
        val switched = CountDownLatch(1)
        val once = AtomicBoolean()
        server.dispatcher = responding { ok("""{"session_assurance":${assuranceBody(false)}}""") }
        val viewModel = viewModel(sessions) { request ->
            if (request.url.encodedPath == PIN && once.compareAndSet(false, true)) {
                runBlocking { sessions.save(successor) }
                switched.countDown()
            }
        }

        viewModel.unlockWithPin("2947")
        await(switched)
        viewModel.reconcile(signedIn = true, supported = true)

        assertTrue(settled(viewModel).loginUnlockRequired)
        assertEquals(1, server.requestCount)
        val request = requests(1).single()
        assertEquals(ASSURANCE, request.path)
        assertEquals("Bearer successor-access", request.getHeader("Authorization"))
        assertEquals(successor.fence(), sessions.current()?.fence())
    }

    private suspend fun replaceSessionDuringUnlock(changeAccount: Boolean) {
        val sessions = sessions()
        val successor = sessions.current()!!.copy(
            accessToken = "successor-access", refreshToken = "successor-refresh", sessionId = "successor-session",
            accountId = if (changeAccount) "successor-account" else "account-A",
            cacheScopeId = if (changeAccount) "successor-cache" else "cache-A",
        )
        val pinArrived = CountDownLatch(1)
        val releasePin = CountDownLatch(1)
        server.dispatcher = responding { request ->
            if (request.path == PIN) {
                pinArrived.countDown()
                check(releasePin.await(5, TimeUnit.SECONDS))
                unlocked()
            } else {
                ok("""{"session_assurance":${assuranceBody(false)}}""")
            }
        }
        val viewModel = viewModel(sessions)
        try {
            viewModel.unlockWithPin("2947")
            await(pinArrived)
            sessions.save(successor)
            viewModel.reconcile(signedIn = true, supported = true)
            val state = settled(viewModel)
            assertTrue(state.required)
            assertTrue(state.loginUnlockRequired)
            assertEquals(successor.fence(), sessions.current()?.fence())
            assertEquals("locked", sessions.current()?.cachedAssurance?.loginUnlockStatus)
            assertEquals(listOf(PIN, ASSURANCE), requests(2).map { it.path })
        } finally {
            releasePin.countDown()
        }
    }

    private fun sessions() = MutableTestSessionStore(
        SessionTokens(
            accessToken = "old-access", refreshToken = "old-refresh", sessionId = "session-A",
            accountId = "account-A", cacheScopeId = "cache-A",
            cachedAssurance = moshi.adapter(SessionAssuranceDto::class.java)
                .fromJson(assuranceBody(false))!!.toCachedSessionAssurance(),
        ),
    )

    private fun viewModel(
        sessions: MutableTestSessionStore,
        beforeHeaders: ((Request) -> Unit)? = null,
    ) = SessionAssuranceViewModel(api(sessions, beforeHeaders), executor, sessions).also(viewModels::add)

    private fun api(
        sessions: MutableTestSessionStore,
        beforeHeaders: ((Request) -> Unit)? = null,
    ): KitWalletApi {
        val refreshClient = OkHttpClient().also(clients::add)
        val refreshApi = retrofit(refreshClient).create(SessionRefreshApi::class.java)
        val refresher = AuthTokenRefresher(dagger.Lazy { refreshApi }, executor)
        // None of these successful refreshes/proof rejections may read or erase the wallet cache.
        val untouchedCache = Proxy.newProxyInstance(
            WalletCache::class.java.classLoader, arrayOf(WalletCache::class.java),
        ) { _, method, _ -> error("Unexpected wallet-cache access: ${method.name}") } as WalletCache
        val client = OkHttpClient.Builder()
            .apply {
                if (beforeHeaders != null) addInterceptor { chain ->
                    beforeHeaders(chain.request())
                    chain.proceed(chain.request())
                }
            }
            .addInterceptor(SessionHeaderInterceptor(sessions))
            .authenticator(SessionAuthenticator(sessions, dagger.Lazy { refresher }, untouchedCache))
            .build().also(clients::add)
        return retrofit(client).create(KitWalletApi::class.java)
    }

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private suspend fun settled(viewModel: SessionAssuranceViewModel): SessionAssuranceUiState =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { viewModel.state.first { !it.checking && !it.unlocking } }
        }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.Default) {
        assertTrue("The expected network boundary was not reached", latch.await(5, TimeUnit.SECONDS))
    }

    private fun requests(count: Int) = List(count) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }

    private fun responding(block: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = block(request)
    }

    private fun rejection(code: String) = MockResponse().setResponseCode(401)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"ok":false,"error":{"code":"$code","message":"Proof rejected"}}""")

    private fun ok(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"ok":true,"data":$data}""")

    private fun unlocked() = ok("""{"session_assurance":${assuranceBody(true)},"method":"pin"}""")

    private fun refreshSuccess() = ok(
        """{"state":"authenticated","session":{"access_token":"refreshed-access",
        "refresh_token":"refreshed-refresh","session_id":"session-A"},
        "session_assurance":${assuranceBody(false)}}""".trimIndent(),
    )

    private fun assuranceBody(unlocked: Boolean): String {
        val action = if (unlocked) "null" else "\"unlock_session\""
        return """{"device_identity":{"status":"verified","required":true,"epoch":1},
            "login_unlock":{"status":"${if (unlocked) "unlocked" else "locked"}","required":true,"methods":["pin"]},
            "access":"${if (unlocked) "full" else "restricted"}",
            "communication_access":{"allowed":$unlocked,"basis":"app_review","required_action":$action},
            "financial_access":{"allowed":$unlocked,"read_only":true,"basis":"app_review","required_action":$action}}
        """.trimIndent()
    }

    private companion object {
        const val PIN = "/api/kit-wallet/v1/auth/session-unlock/pin"
        const val BIOMETRIC_ASSERT = "/api/kit-wallet/v1/auth/session-unlock/biometric/assert"
        const val REFRESH = "/api/kit-wallet/v1/auth/refresh"
        const val ASSURANCE = "/api/kit-wallet/v1/auth/session-assurance"
    }
}
