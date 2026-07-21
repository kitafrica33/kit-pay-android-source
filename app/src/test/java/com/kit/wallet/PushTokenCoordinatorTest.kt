package com.kit.wallet

import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class PushTokenCoordinatorTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var apiCalls: ApiCallExecutor

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        apiCalls = ApiCallExecutor(moshi)
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `disabled notifications do not read or register a Firebase token`() = runTest {
        server.enqueue(jsonResponse(capabilitiesJson(notifications = false)))
        server.enqueue(jsonResponse(PUSH_REMOVED_JSON))
        var tokenRead = false
        val coordinator = coordinator(FakeSessionStore.signedIn(), backgroundScope)

        coordinator.registerIfEnabled {
            tokenRead = true
            "test-fcm-token"
        }

        assertFalse(tokenRead)
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        val removal = server.takeRequest()
        assertEquals("DELETE", removal.method)
        assertEquals("/api/kit-wallet/v1/devices/current/push-token", removal.path)
    }

    @Test
    fun `enabled notifications register the token after capability discovery`() = runTest {
        server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
        server.enqueue(jsonResponse(pushRegisteredJson("test-push")))
        val transport = FakePushMessagingTransport(
            token = "test-push-token",
            provider = "test-push",
        )
        val coordinator = coordinator(FakeSessionStore.signedIn(), backgroundScope, transport)

        coordinator.registerIfEnabled()

        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        val registration = server.takeRequest()
        assertEquals("PUT", registration.method)
        assertEquals("/api/kit-wallet/v1/devices/current/push-token", registration.path)
        val body = registration.body.readUtf8()
        assertTrue(body.contains("test-push-token"))
        assertTrue(body.contains("\"provider\":\"test-push\""))
        assertEquals(1, transport.tokenReads)
    }

    @Test
    fun `mismatched registration provider response fails closed`() = runTest {
        server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
        server.enqueue(jsonResponse(pushRegisteredJson("different-provider")))
        val transport = FakePushMessagingTransport(provider = "test-push")
        val coordinator = coordinator(FakeSessionStore.signedIn(), backgroundScope, transport)

        var rejected = false
        try {
            coordinator.registerIfEnabled()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `token callback from an inactive provider is ignored`() = runTest {
        val transport = FakePushMessagingTransport(provider = "test-push")
        val coordinator = coordinator(FakeSessionStore.signedIn(), backgroundScope, transport)

        coordinator.tokenChanged(provider = "different-provider", token = "foreign-token")

        assertEquals(0, server.requestCount)
        assertEquals(0, transport.tokenReads)
    }

    @Test
    fun `session change between discovery and registration fails closed`() = runTest {
        server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
        val sessions = FakeSessionStore.signedIn()
        val coordinator = coordinator(sessions, backgroundScope)

        coordinator.registerIfEnabled {
            sessions.clear()
            "test-fcm-token"
        }

        assertEquals(1, server.requestCount)
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
    }

    @Test
    fun `transient registration failure is retried with bounded backoff`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody(API_UNAVAILABLE_JSON),
        )
        server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
        server.enqueue(jsonResponse(PUSH_REGISTERED_JSON))
        val coordinator = coordinator(FakeSessionStore.signedIn(), backgroundScope)

        coordinator.registerWithRetry(
            tokenProvider = { "retry-fcm-token" },
            retryDelaysMillis = listOf(0L),
        )

        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/devices/current/push-token", server.takeRequest().path)
    }

    private fun coordinator(
        sessions: SessionStore,
        scope: kotlinx.coroutines.CoroutineScope,
        transport: PushMessagingTransport = FakePushMessagingTransport(),
    ) = PushTokenCoordinator(api, apiCalls, sessions, transport, scope)

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun capabilitiesJson(notifications: Boolean) = """
        {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},"features":{"notifications":$notifications},"authentication":{}},"meta":{"request_id":"request-capabilities","api_version":"v1","server_time":"2026-07-17T12:00:00Z"}}
    """.trimIndent()

    private class FakeSessionStore private constructor(
        initial: SessionTokens?,
    ) : SessionStore {
        private val state = MutableStateFlow(initial)
        private var revision = 0L
        override val session: StateFlow<SessionTokens?> = state
        override fun current(): SessionTokens? = state.value
        override fun snapshot() = com.kit.wallet.data.session.SessionSnapshot(
            revision,
            state.value?.fence(),
        )
        override suspend fun save(tokens: SessionTokens) {
            state.value = tokens
            revision++
        }
        override suspend fun saveIfUnchanged(
            expected: com.kit.wallet.data.session.SessionSnapshot,
            tokens: SessionTokens,
        ): Boolean {
            if (snapshot() != expected) return false
            save(tokens)
            return true
        }
        override suspend fun updateProfileSetupState(
            expected: com.kit.wallet.data.session.SessionFence,
            setupState: com.kit.wallet.data.session.ProfileSetupState,
        ): Boolean {
            val current = state.value ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = setupState))
            return true
        }
        override suspend fun <T> withCurrentSession(
            expected: com.kit.wallet.data.session.SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = requireNotNull(state.value)
            check(current.fence() == expected)
            return block(current)
        }
        override suspend fun clearIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
        ): Boolean {
            if (state.value?.fence() != expected) return false
            clear()
            return true
        }
        override suspend fun clear() {
            state.value = null
            revision++
        }

        companion object {
            fun signedIn() = FakeSessionStore(
                SessionTokens(
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    sessionId = "session-id",
                ),
            )
        }
    }

    private class FakePushMessagingTransport(
        private val token: String = "unused-push-token",
        override val provider: String = "fcm",
        override val configured: Boolean = true,
    ) : PushMessagingTransport {
        var tokenReads: Int = 0
            private set

        override fun initialize() = Unit

        override suspend fun currentToken(): String {
            tokenReads++
            return token
        }
    }

    private companion object {
        val API_UNAVAILABLE_JSON = """
            {"ok":false,"error":{"code":"TEMPORARILY_UNAVAILABLE","message":"Try again"},"meta":{"request_id":"request-failed"}}
        """.trimIndent()

        val PUSH_REGISTERED_JSON = """
            {"ok":true,"data":{"registered":true,"provider":"fcm","updated_at":"2026-07-17T12:00:00Z"},"meta":{"request_id":"request-push","api_version":"v1","server_time":"2026-07-17T12:00:00Z"}}
        """.trimIndent()

        fun pushRegisteredJson(provider: String) = """
            {"ok":true,"data":{"registered":true,"provider":"$provider","updated_at":"2026-07-17T12:00:00Z"},"meta":{"request_id":"request-push","api_version":"v1","server_time":"2026-07-17T12:00:00Z"}}
        """.trimIndent()

        val PUSH_REMOVED_JSON = """
            {"ok":true,"data":{"registered":false,"provider":null,"updated_at":null},"meta":{"request_id":"request-push-remove","api_version":"v1","server_time":"2026-07-17T12:00:00Z"}}
        """.trimIndent()
    }
}
