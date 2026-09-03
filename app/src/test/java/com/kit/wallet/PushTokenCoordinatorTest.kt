package com.kit.wallet

import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.notifications.isTransientPushRegistrationFailure
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.SessionHeaderInterceptor
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class PushTokenCoordinatorTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var apiCalls: ApiCallExecutor
    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
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

    @Test
    fun `wrapped connectivity failure remains retryable`() {
        val error = KitWalletApiException(
            code = "NETWORK_UNAVAILABLE",
            message = "No internet connection",
            statusCode = null,
            connectivity = true,
        )

        assertTrue(error.isTransientPushRegistrationFailure())
    }

    @Test
    fun `transient failures keep retrying after the initial backoff ladder`() = runTest {
        repeat(4) {
            server.enqueue(
                MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                    .setBody(API_UNAVAILABLE_JSON),
            )
        }
        server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
        server.enqueue(jsonResponse(PUSH_REGISTERED_JSON))
        val coordinator = coordinator(FakeSessionStore.signedIn(), backgroundScope)

        coordinator.registerWithRetry(
            tokenProvider = { "eventual-fcm-token" },
            retryDelaysMillis = listOf(0L),
        )

        assertEquals(6, server.requestCount)
        repeat(4) {
            assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        }
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/devices/current/push-token", server.takeRequest().path)
    }

    @Test
    fun `foreground capability change replays push registration`() = runTest {
        server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
        server.enqueue(jsonResponse(PUSH_REGISTERED_JSON))
        val transport = FakePushMessagingTransport(token = "replayed-token")
        val coordinator = coordinator(FakeSessionStore.signedIn(), backgroundScope, transport)

        coordinator.capabilityPolicyChanged()?.join()

        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/devices/current/push-token", server.takeRequest().path)
        assertEquals(1, transport.tokenReads)
    }

    @Test
    fun `account replacement cannot send an old token through the successor session`() =
        runBlocking {
            val ownerA = FakeSessionStore.tokens("a")
            val ownerB = FakeSessionStore.tokens("b")
            val sessions = FakeSessionStore.signedIn(ownerA)
            val gate = BlockingRequestInterceptor { request ->
                request.method == "PUT" && request.utf8Body().contains("old-token")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = coordinator(
                sessions = sessions,
                scope = scope,
                remoteApi = fencedApi(sessions, gate),
            )
            server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
            server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
            server.enqueue(jsonResponse(PUSH_REGISTERED_JSON))

            try {
                val stale = checkNotNull(coordinator.tokenChanged("fcm", "old-token"))
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
                sessions.save(ownerB)
                val current = checkNotNull(coordinator.tokenChanged("fcm", "new-token"))

                gate.release.countDown()
                withTimeout(10_000L) {
                    stale.join()
                    current.join()
                }

                assertEquals(3, server.requestCount)
                val firstCapabilities = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val secondCapabilities = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val registration = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("Bearer ${ownerA.accessToken}", firstCapabilities.getHeader("Authorization"))
                assertEquals("Bearer ${ownerB.accessToken}", secondCapabilities.getHeader("Authorization"))
                assertEquals("Bearer ${ownerB.accessToken}", registration.getHeader("Authorization"))
                assertTrue(registration.utf8Body().contains("new-token"))
                assertFalse(registration.utf8Body().contains("old-token"))
            } finally {
                gate.release.countDown()
                scope.cancel()
            }
        }

    @Test
    fun `a newer token waits for an in flight mutation and remains the final registration`() =
        runBlocking {
            val sessions = FakeSessionStore.signedIn()
            val firstMutationEntered = CountDownLatch(1)
            val releaseFirstMutation = CountDownLatch(1)
            val secondMutationEntered = CountDownLatch(1)
            val appliedTokens = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/kit-wallet/v1/capabilities" ->
                        jsonResponse(capabilitiesJson(notifications = true))
                    request.method == "PUT" -> {
                        val token = request.pushToken()
                        if (token == "old-token") {
                            firstMutationEntered.countDown()
                            check(releaseFirstMutation.await(5, TimeUnit.SECONDS))
                        } else {
                            secondMutationEntered.countDown()
                        }
                        appliedTokens += token
                        jsonResponse(PUSH_REGISTERED_JSON)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = coordinator(
                sessions = sessions,
                scope = scope,
                remoteApi = fencedApi(sessions),
            )

            try {
                val stale = checkNotNull(coordinator.tokenChanged("fcm", "old-token"))
                assertTrue(firstMutationEntered.await(5, TimeUnit.SECONDS))
                val current = checkNotNull(coordinator.tokenChanged("fcm", "new-token"))

                assertFalse(secondMutationEntered.await(250, TimeUnit.MILLISECONDS))
                releaseFirstMutation.countDown()
                withTimeout(10_000L) {
                    stale.join()
                    current.join()
                }

                assertEquals(listOf("old-token", "new-token"), appliedTokens.toList())
            } finally {
                releaseFirstMutation.countDown()
                scope.cancel()
            }
        }

    @Test
    fun `an old logout cannot unregister the successor account token`() = runBlocking {
        val ownerA = FakeSessionStore.tokens("a")
        val ownerB = FakeSessionStore.tokens("b")
        val sessions = FakeSessionStore.signedIn(ownerA)
        val gate = BlockingRequestInterceptor { it.method == "DELETE" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = coordinator(
            sessions = sessions,
            scope = scope,
            remoteApi = fencedApi(sessions, gate),
        )
        server.enqueue(jsonResponse(capabilitiesJson(notifications = true)))
        server.enqueue(jsonResponse(PUSH_REGISTERED_JSON))

        try {
            val staleLogout = async(Dispatchers.IO) {
                coordinator.unregisterBeforeLogout(ownerA.fence())
            }
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            assertNull(coordinator.tokenChanged("fcm", "late-old-token"))
            sessions.save(ownerB)
            val successorRegistration = checkNotNull(
                coordinator.tokenChanged("fcm", "successor-token"),
            )

            gate.release.countDown()
            withTimeout(10_000L) {
                staleLogout.await()
                successorRegistration.join()
            }

            assertEquals(2, server.requestCount)
            val capabilities = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val registration = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("GET", capabilities.method)
            assertEquals("PUT", registration.method)
            assertEquals("Bearer ${ownerB.accessToken}", registration.getHeader("Authorization"))
            assertTrue(registration.utf8Body().contains("successor-token"))
        } finally {
            gate.release.countDown()
            scope.cancel()
        }
    }

    private fun coordinator(
        sessions: SessionStore,
        scope: CoroutineScope,
        transport: PushMessagingTransport = FakePushMessagingTransport(),
        remoteApi: KitWalletApi = api,
    ) = PushTokenCoordinator(remoteApi, apiCalls, sessions, transport, scope)

    private fun fencedApi(
        sessions: SessionStore,
        beforeSessionFence: Interceptor? = null,
    ): KitWalletApi {
        val client = OkHttpClient.Builder().apply {
            beforeSessionFence?.let(::addInterceptor)
            addInterceptor(SessionHeaderInterceptor(sessions))
        }.build()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun RecordedRequest.utf8Body(): String = body.clone().readUtf8()

    private fun RecordedRequest.pushToken(): String = checkNotNull(
        Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"").find(utf8Body())?.groupValues?.get(1),
    )

    private fun okhttp3.Request.utf8Body(): String {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

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
            fun signedIn(tokens: SessionTokens = tokens("default")) = FakeSessionStore(tokens)

            fun tokens(label: String) = SessionTokens(
                accessToken = "access-token-$label",
                refreshToken = "refresh-token-$label",
                sessionId = "session-$label",
                accountId = "account-$label",
                cacheScopeId = "scope-$label",
            )
        }
    }

    private class BlockingRequestInterceptor(
        private val shouldBlock: (okhttp3.Request) -> Boolean,
    ) : Interceptor {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val available = AtomicBoolean(true)

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            if (shouldBlock(chain.request()) && available.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            return chain.proceed(chain.request())
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
