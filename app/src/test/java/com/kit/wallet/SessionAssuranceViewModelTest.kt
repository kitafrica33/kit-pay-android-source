package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SessionAssuranceSignal
import com.kit.wallet.data.session.CachedSessionAssurance
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.feature.auth.SessionAssuranceViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.kit.wallet.feature.auth.SessionAssuranceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAssuranceViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var executor: ApiCallExecutor

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        executor = ApiCallExecutor(moshi)
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    @Test
    fun `locked session is verified even when capabilities loaded after first reconcile`() = runTest {
        server.enqueue(jsonResponse(assuranceJson(access = "restricted", unlockStatus = "locked")))
        val viewModel = viewModel(FakeSessionStore())

        // Capability discovery has not finished on the first pass; the session must not be
        // remembered as verified by this unsupported call.
        viewModel.reconcile(signedIn = true, supported = false)
        assertFalse(viewModel.state.value.required)

        viewModel.reconcile(signedIn = true, supported = true)

        val settled = awaitSettled(viewModel)
        assertTrue(settled.required)
        assertEquals(setOf("pin"), settled.methods)
        assertNull(settled.error)
        assertEquals(
            "/api/kit-wallet/v1/auth/session-assurance",
            server.takeRequest().path,
        )
    }

    @Test
    fun `service without session assurance endpoints never locks the login`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":{"code":"NOT_FOUND","message":"Not found"}}"""),
        )
        val viewModel = viewModel(FakeSessionStore())

        viewModel.reconcile(signedIn = true, supported = true)

        val settled = awaitSettled(viewModel)
        assertFalse(settled.required)
        assertNull(settled.error)
    }

    @Test
    fun `creating the first wallet PIN unlocks the session from the gate`() = runTest {
        server.enqueue(jsonResponse(assuranceJson(access = "restricted", methods = "[]")))
        server.enqueue(
            jsonResponse(
                """{"payment_pin_set":true,"payment_pin_set_at":"2026-08-23T08:00:00Z",""" +
                    """"session_assurance":${assuranceBody(access = "full", unlockStatus = "unlocked")}}""",
            ),
        )
        val sessions = FakeSessionStore()
        val viewModel = viewModel(sessions)
        viewModel.reconcile(signedIn = true, supported = true)
        val locked = awaitSettled(viewModel)
        assertTrue(locked.required)
        assertTrue(locked.methods.isEmpty())

        viewModel.createPinAndUnlock("2947", "2947")

        val unlocked = awaitState(viewModel) { !it.required && !it.unlocking }
        assertFalse(unlocked.required)
        server.takeRequest()
        val setPin = server.takeRequest()
        assertEquals("PUT", setPin.method)
        assertEquals("/api/kit-wallet/v1/auth/payment-pin", setPin.path)
        assertTrue(setPin.body.readUtf8().contains("\"pin_confirmation\":\"2947\""))
        assertEquals("full", sessions.current()?.cachedAssurance?.access)
    }

    @Test
    fun `mismatched first PIN confirmation fails before any request`() = runTest {
        server.enqueue(jsonResponse(assuranceJson(access = "restricted", methods = "[]")))
        val viewModel = viewModel(FakeSessionStore())
        viewModel.reconcile(signedIn = true, supported = true)
        awaitSettled(viewModel)

        viewModel.createPinAndUnlock("2947", "2948")

        assertTrue(viewModel.state.value.required)
        assertEquals("Enter the same four-digit PIN twice", viewModel.state.value.error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `server lock signal re-verifies a session believed unlocked`() = runTest {
        val signal = SessionAssuranceSignal()
        val sessions = FakeSessionStore(
            cachedAssurance = CachedSessionAssurance(
                access = "full",
                deviceIdentityStatus = "not_required",
                deviceIdentityRequired = false,
                loginUnlockStatus = "unlocked",
                loginUnlockRequired = false,
                loginUnlockMethods = listOf("pin"),
            ),
        )
        server.enqueue(jsonResponse(assuranceJson(access = "restricted", unlockStatus = "locked")))
        val viewModel = viewModel(sessions, signal)
        assertFalse(viewModel.state.value.required)

        signal.notifyLocked()

        val relocked = awaitState(viewModel) { it.required }
        assertTrue(relocked.required)
    }

    @Test
    fun `device identity requirement surfaces instead of a PIN form the server would refuse`() =
        runTest {
            server.enqueue(
                jsonResponse(
                    assuranceJson(
                        access = "restricted",
                        methods = "[]",
                        identityStatus = "required",
                        identityRequired = true,
                    ),
                ),
            )
            val viewModel = viewModel(FakeSessionStore())

            viewModel.reconcile(signedIn = true, supported = true)

            val settled = awaitSettled(viewModel)
            assertTrue(settled.required)
            assertTrue(settled.deviceIdentityRequired)
            assertEquals("required", settled.deviceIdentityStatus)
        }

    @Test
    fun `first PIN saves but the gate pivots to identity when the server stays locked`() = runTest {
        server.enqueue(
            jsonResponse(
                assuranceJson(
                    access = "restricted",
                    methods = "[]",
                    identityStatus = "required",
                    identityRequired = true,
                ),
            ),
        )
        server.enqueue(
            jsonResponse(
                """{"payment_pin_set":true,"payment_pin_set_at":"2026-08-23T08:00:00Z",""" +
                    """"session_assurance":${assuranceBody(
                        access = "restricted",
                        unlockStatus = "locked",
                        methods = """["pin"]""",
                        identityStatus = "required",
                        identityRequired = true,
                    )}}""",
            ),
        )
        val viewModel = viewModel(FakeSessionStore())
        viewModel.reconcile(signedIn = true, supported = true)
        awaitSettled(viewModel)

        viewModel.createPinAndUnlock("2947", "2947")

        val settled = awaitState(viewModel) { !it.unlocking && it.methods.isNotEmpty() }
        assertTrue(settled.required)
        assertTrue(settled.deviceIdentityRequired)
        assertEquals(setOf("pin"), settled.methods)
    }

    private suspend fun awaitSettled(
        viewModel: SessionAssuranceViewModel,
    ): SessionAssuranceUiState = awaitState(viewModel) { !it.checking && !it.unlocking }

    private suspend fun awaitState(
        viewModel: SessionAssuranceViewModel,
        predicate: (SessionAssuranceUiState) -> Boolean,
    ): SessionAssuranceUiState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) { viewModel.state.first(predicate) }
    }

    private fun viewModel(
        sessions: SessionStore,
        signal: SessionAssuranceSignal? = null,
    ) = SessionAssuranceViewModel(
        api = api,
        apiCalls = executor,
        sessions = sessions,
        biometricKey = null,
        lockSignals = signal,
    )

    private fun jsonResponse(data: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"ok":true,"data":$data,"meta":{"request_id":"request-1"}}""")

    private fun assuranceJson(
        access: String,
        unlockStatus: String = "locked",
        methods: String = """["pin"]""",
        identityStatus: String = "not_required",
        identityRequired: Boolean = false,
    ) = """{"session_assurance":${
        assuranceBody(access, unlockStatus, methods, identityStatus, identityRequired)
    }}"""

    private fun assuranceBody(
        access: String,
        unlockStatus: String = "locked",
        methods: String = """["pin"]""",
        identityStatus: String = "not_required",
        identityRequired: Boolean = false,
    ) = """
        {"device_identity":{"status":"$identityStatus","required":$identityRequired,
        "epoch":1,"verified_at":null},
        "login_unlock":{"status":"$unlockStatus","required":${unlockStatus != "unlocked"},
        "methods":$methods,"method":null,"unlocked_at":null},
        "access":"$access"}
    """.trimIndent()

    private class FakeSessionStore(
        cachedAssurance: CachedSessionAssurance? = null,
    ) : SessionStore {
        private val state = MutableStateFlow<SessionTokens?>(
            SessionTokens(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                sessionId = "session-1",
                accountId = "account-1",
                cachedAssurance = cachedAssurance,
            ),
        )
        private var revision = 0L
        override val session: StateFlow<SessionTokens?> = state
        override fun current(): SessionTokens? = state.value
        override fun snapshot() = SessionSnapshot(revision, state.value?.fence())

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
        ): Boolean = true

        override suspend fun <T> withCurrentSession(
            expected: SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = requireNotNull(this.state.value)
            check(current.fence() == expected)
            return block(current)
        }

        override suspend fun clearIfCurrent(expected: SessionFence): Boolean = false

        override suspend fun clear() {
            state.value = null
            revision++
        }
    }
}
