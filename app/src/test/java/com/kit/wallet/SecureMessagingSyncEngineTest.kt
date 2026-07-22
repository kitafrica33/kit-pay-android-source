package com.kit.wallet

import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget
import com.kit.wallet.data.messaging.SecureMessagingAuthBindingResolver
import com.kit.wallet.data.messaging.SecureMessagingAuthenticationEpochChangedException
import com.kit.wallet.data.messaging.RealSecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.RealSecureMessagingSyncEngine
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingActivationCoordinator
import com.kit.wallet.data.messaging.SecureMessagingActiveSessionRegistry
import com.kit.wallet.data.messaging.SecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.SecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.SecureMessagingEventProcessor
import com.kit.wallet.data.messaging.SecureMessagingFreshAuthenticationRequiredException
import com.kit.wallet.data.messaging.SecureMessagingKeyActivation
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingReauthenticationRequiredException
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.messaging.SecureMessagingStateNotReadyException
import com.kit.wallet.data.messaging.SecureMessagingSyncCursorStore
import com.kit.wallet.data.messaging.awaitSecureMessagingStateAvailability
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessagingSyncDto
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SecureMessagingSyncEngineTest {
    @Test
    fun `restored session waits for encrypted state gate without losing startup work`() = runTest {
        val stateAvailable = MutableStateFlow(false)
        val sessions = MutableStateFlow<SessionTokens?>(TOKENS)

        val waiting = async {
            awaitSecureMessagingStateAvailability(
                expectedSessionEpoch = TOKENS.sessionId,
                stateAvailable = stateAvailable,
                sessions = sessions,
                timeoutMillis = 60_000L,
            )
        }
        runCurrent()
        assertFalse(waiting.isCompleted)

        stateAvailable.value = true

        waiting.await()
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun `state gate wait is bounded and rejects an authentication epoch replacement`() = runTest {
        val unavailable = runCatching {
            awaitSecureMessagingStateAvailability(
                expectedSessionEpoch = TOKENS.sessionId,
                stateAvailable = MutableStateFlow(false),
                sessions = MutableStateFlow(TOKENS),
                timeoutMillis = 1L,
            )
        }.exceptionOrNull()
        assertTrue(unavailable is SecureMessagingStateNotReadyException)

        val sessions = MutableStateFlow<SessionTokens?>(TOKENS)
        sessions.value = TOKENS.copy(sessionId = "replacement-session")
        val replaced = runCatching {
            awaitSecureMessagingStateAvailability(
                expectedSessionEpoch = TOKENS.sessionId,
                stateAvailable = MutableStateFlow(false),
                sessions = sessions,
                timeoutMillis = 60_000L,
            )
        }.exceptionOrNull()
        assertTrue(replaced is SecureMessagingAuthenticationEpochChangedException)
    }

    @Test
    fun `binding resolver validates live profile device and rechecks session epoch`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val sessionStore = FakeSessionStore(TOKENS)
            val resolver = resolver(server, sessionStore)
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))

            val binding = resolver.resolve(TOKENS.sessionId)

            assertEquals(TOKENS.sessionId, binding.sessionEpoch)
            assertEquals(USER_ID, binding.userId)
            assertEquals(DEVICE_ID, binding.serverDeviceId)
            assertEquals(INSTALLATION_ID, binding.installationId)
            assertEquals("/api/kit-wallet/v1/profile", server.takeRequest().path)
            assertEquals("/api/kit-wallet/v1/devices", server.takeRequest().path)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    sessionStore.replace(TOKENS.copy(sessionId = "replacement-session"))
                    return jsonResponse(PROFILE)
                }
            }
            val changed = runCatching { resolver.resolve(TOKENS.sessionId) }.exceptionOrNull()
            assertTrue(changed is SecureMessagingAuthenticationEpochChangedException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `ready implementation activates a fresh login when registry starts empty`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            val api = retrofit.create(KitWalletApi::class.java)
            val transport = RemoteSecureMessagingTransport(
                api,
                retrofit.create(SecureMessagingWireApi::class.java),
                ApiCallExecutor(moshi),
            )
            val sessions = FakeSessionStore(TOKENS)
            val guard = SecureMessagingLifecycleGuard()
            val registry = SecureMessagingActiveSessionRegistry(guard)
            val stateStore = TestSecureMessagingStateStore()
            val projections = SecureMessagingProjectionStore(
                stateStore,
                com.kit.wallet.data.messaging.LibSignalCompanionStateReader(stateStore),
            )
            val processor = SecureMessagingEventProcessor(
                UnusedCryptoEngine,
                projections,
                SecureMessagingSyncCursorStore(stateStore),
            )
            val coordinator = SecureMessagingActivationCoordinator(
                transport = transport,
                lifecycle = guard,
                sessions = registry,
                keyActivation = SecureMessagingKeyActivation { },
                initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
            )
            val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
            localStateLifecycle.afterSessionSave()
            val engine = RealSecureMessagingSyncEngine(
                bindingResolver = SecureMessagingAuthBindingResolver(
                    sessions,
                    api,
                    ApiCallExecutor(moshi),
                    deviceIdentity(),
                ),
                activation = coordinator,
                processor = processor,
                sessions = sessions,
                sessionLifecycle = localStateLifecycle,
                authRepository = unusedAuthRepository(),
            )
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))
            server.enqueue(jsonResponse(READY_CAPABILITIES))
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))
            enqueueEmptySync(server, moshi, "initial_cursor")
            enqueueEmptySync(server, moshi, "wake_cursor")

            assertTrue(engine.isReady)
            assertTrue(registry.currentOrNull() == null)
            engine.synchronize()

            assertEquals(TOKENS.sessionId, registry.requireCurrent().binding.sessionEpoch)
            assertEquals(7, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `missing enrollment retries exact reset then reactivates in the advanced epoch`() =
        runTest {
            val server = MockWebServer().apply { start() }
            try {
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                val api = retrofit.create(KitWalletApi::class.java)
                val transport = RemoteSecureMessagingTransport(
                    api,
                    retrofit.create(SecureMessagingWireApi::class.java),
                    ApiCallExecutor(moshi),
                )
                val sessions = FakeSessionStore(TOKENS)
                val guard = SecureMessagingLifecycleGuard()
                val stateStore = TestSecureMessagingStateStore()
                val processor = SecureMessagingEventProcessor(
                    UnusedCryptoEngine,
                    SecureMessagingProjectionStore(
                        stateStore,
                        com.kit.wallet.data.messaging.LibSignalCompanionStateReader(stateStore),
                    ),
                    SecureMessagingSyncCursorStore(stateStore),
                )
                var activationAttempts = 0
                val coordinator = SecureMessagingActivationCoordinator(
                    transport = transport,
                    lifecycle = guard,
                    sessions = SecureMessagingActiveSessionRegistry(guard),
                    keyActivation = SecureMessagingKeyActivation { session ->
                        if (activationAttempts++ == 0) {
                            throw SecureMessagingReauthenticationRequiredException(
                                target = RESET_TARGET,
                                activationFence = session.activationFence(),
                                message = "missing private key",
                            )
                        }
                    },
                    initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
                )
                val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                localStateLifecycle.afterSessionSave()
                var localResets = 0
                sessions.messagingReset = { fence ->
                    localStateLifecycle.resetForRecovery(fence)
                    localStateLifecycle.afterSessionSave()
                    localResets++
                }
                val recoveredEpochs = mutableListOf<String>()
                var recoveryAttempts = 0
                val retryableResetFailure = IllegalStateException("reset unavailable")
                val engine = RealSecureMessagingSyncEngine(
                    bindingResolver = SecureMessagingAuthBindingResolver(
                        sessions,
                        api,
                        ApiCallExecutor(moshi),
                        deviceIdentity(),
                    ),
                    activation = coordinator,
                    processor = processor,
                    sessions = sessions,
                    sessionLifecycle = localStateLifecycle,
                    authRepository = unusedAuthRepository { epoch, target ->
                        recoveredEpochs += epoch
                        assertEquals(RESET_TARGET, target)
                        if (recoveryAttempts++ == 0) throw retryableResetFailure
                    },
                )
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
                        request.path == "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
                        request.path == "/api/kit-wallet/v1/capabilities" ->
                            jsonResponse(READY_CAPABILITIES)
                        request.path?.startsWith("/api/kit-wallet/v1/messaging/sync") == true ->
                            emptySyncResponse(moshi, "post_reset_cursor")
                        else -> MockResponse().setResponseCode(404)
                    }
                }

                val retryable = runCatching { engine.synchronize() }.exceptionOrNull()

                assertEquals(retryableResetFailure, retryable)
                assertEquals(
                    com.kit.wallet.data.messaging.SecureMessagingRuntimeStage.PREPARING_KEYS,
                    guard.snapshot().stage,
                )

                engine.synchronize()

                assertEquals(listOf(TOKENS.sessionId, TOKENS.sessionId), recoveredEpochs)
                assertEquals(1, localResets)
                assertEquals(com.kit.wallet.data.messaging.SecureMessagingRuntimeStage.READY, guard.snapshot().stage)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `fresh authentication delegates directly to atomic session clear without standalone reset`() =
        runTest {
            val server = MockWebServer().apply { start() }
            try {
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                val api = retrofit.create(KitWalletApi::class.java)
                val transport = RemoteSecureMessagingTransport(
                    api,
                    retrofit.create(SecureMessagingWireApi::class.java),
                    ApiCallExecutor(moshi),
                )
                val sessions = FakeSessionStore(TOKENS)
                val guard = SecureMessagingLifecycleGuard()
                val stateStore = TestSecureMessagingStateStore()
                val processor = SecureMessagingEventProcessor(
                    UnusedCryptoEngine,
                    SecureMessagingProjectionStore(
                        stateStore,
                        com.kit.wallet.data.messaging.LibSignalCompanionStateReader(stateStore),
                    ),
                    SecureMessagingSyncCursorStore(stateStore),
                )
                val coordinator = SecureMessagingActivationCoordinator(
                    transport = transport,
                    lifecycle = guard,
                    sessions = SecureMessagingActiveSessionRegistry(guard),
                    keyActivation = SecureMessagingKeyActivation { session ->
                        throw SecureMessagingFreshAuthenticationRequiredException(
                            activationFence = session.activationFence(),
                            message = "fresh authentication required",
                        )
                    },
                    initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
                )
                val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                localStateLifecycle.afterSessionSave()
                var standaloneMessagingResets = 0
                sessions.messagingReset = {
                    standaloneMessagingResets++
                    error("Fresh authentication must not reopen an erased authenticated session")
                }
                var freshAuthenticationCalls = 0
                val simulatedProcessDeath = IllegalStateException(
                    "simulated process death during atomic authenticated-session clear",
                )
                val engine = RealSecureMessagingSyncEngine(
                    bindingResolver = SecureMessagingAuthBindingResolver(
                        sessions,
                        api,
                        ApiCallExecutor(moshi),
                        deviceIdentity(),
                    ),
                    activation = coordinator,
                    processor = processor,
                    sessions = sessions,
                    sessionLifecycle = localStateLifecycle,
                    authRepository = unusedAuthRepository(
                        freshAuthentication = { epoch ->
                            assertEquals(TOKENS.sessionId, epoch)
                            freshAuthenticationCalls++
                            throw simulatedProcessDeath
                        },
                    ),
                )
                server.enqueue(jsonResponse(PROFILE))
                server.enqueue(jsonResponse(DEVICES))
                server.enqueue(jsonResponse(READY_CAPABILITIES))
                server.enqueue(jsonResponse(PROFILE))
                server.enqueue(jsonResponse(DEVICES))

                val failure = runCatching { engine.synchronize() }.exceptionOrNull()

                assertEquals(simulatedProcessDeath, failure)
                assertEquals(1, freshAuthenticationCalls)
                assertEquals(0, standaloneMessagingResets)
                assertEquals(TOKENS, sessions.current())
                assertEquals(
                    com.kit.wallet.data.messaging.SecureMessagingRuntimeStage.PREPARING_KEYS,
                    guard.snapshot().stage,
                )
            } finally {
                server.shutdown()
            }
        }

    private fun resolver(
        server: MockWebServer,
        sessions: SessionStore,
    ): SecureMessagingAuthBindingResolver {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        return SecureMessagingAuthBindingResolver(
            sessions,
            api,
            ApiCallExecutor(moshi),
            deviceIdentity(),
        )
    }

    private fun deviceIdentity() = object : DeviceIdentityProvider {
        override fun registration() = DeviceRegistrationDto(
            installationId = INSTALLATION_ID,
            name = "Test phone",
            appVersion = "1",
            osVersion = "1",
            model = "Test",
        )
    }

    private fun unusedAuthRepository(
        freshAuthentication: ((String) -> Unit)? = null,
        recover: ((String, SecureMessagingEnrollmentResetTarget) -> Unit)? = null,
    ): AuthRepository = Proxy.newProxyInstance(
        AuthRepository::class.java.classLoader,
        arrayOf(AuthRepository::class.java),
    ) { instance, method, arguments ->
        when (method.name) {
            "recoverMissingSecureMessagingEnrollment" -> recover?.invoke(
                arguments?.get(0) as String,
                arguments[1] as SecureMessagingEnrollmentResetTarget,
            ) ?: error("Recovery must not run for an intact enrollment")
            "requireFreshAuthenticationForSecureMessagingRecovery" ->
                freshAuthentication?.invoke(arguments?.get(0) as String)
                    ?: error("Fresh authentication must not run in this test")
            "toString" -> "UnusedAuthRepository"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === arguments?.firstOrNull()
            else -> error("Unexpected auth repository call: ${method.name}")
        }
    } as AuthRepository

    private fun enqueueEmptySync(server: MockWebServer, moshi: Moshi, cursor: String) {
        server.enqueue(emptySyncResponse(moshi, cursor))
    }

    private fun emptySyncResponse(moshi: Moshi, cursor: String): MockResponse {
        val encoded = moshi.adapter(MessagingSyncDto::class.java).toJson(
            MessagingSyncDto(
                events = emptyList(),
                page = CursorPageDto(nextCursor = cursor, hasMore = false, limit = 50),
            ),
        )
        return jsonResponse("""{"ok":true,"data":$encoded}""")
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeSessionStore(initial: SessionTokens?) : SessionStore {
        private val mutable = MutableStateFlow(initial)
        private var revision = 0L
        override val session = mutable
        var messagingReset: suspend (com.kit.wallet.data.messaging.SecureMessagingSessionFence) -> Unit = {}

        override fun current(): SessionTokens? = mutable.value

        override fun snapshot() = com.kit.wallet.data.session.SessionSnapshot(
            revision,
            mutable.value?.fence(),
        )

        override suspend fun save(tokens: SessionTokens) {
            mutable.value = tokens
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
            val current = mutable.value ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = setupState))
            return true
        }

        override suspend fun <T> withCurrentSession(
            expected: com.kit.wallet.data.session.SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = requireNotNull(mutable.value)
            check(current.fence() == expected)
            return block(current)
        }

        override suspend fun clearIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
        ): Boolean {
            if (mutable.value?.fence() != expected) return false
            clear()
            return true
        }

        override suspend fun resetSecureMessagingStateIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
            activationFence: com.kit.wallet.data.messaging.SecureMessagingSessionFence,
        ): Boolean {
            if (mutable.value?.fence() != expected) return false
            messagingReset(activationFence)
            return true
        }

        override suspend fun clear() {
            mutable.value = null
            revision++
        }

        fun replace(tokens: SessionTokens?) {
            mutable.value = tokens
            revision++
        }
    }

    private object UnusedCryptoEngine : SecureMessagingCryptoEngine {
        override suspend fun openTransaction(
            activation: SecureMessagingActivationCapability,
        ): SecureMessagingCryptoTransaction = error("empty sync must not open crypto")

        override suspend fun eraseAll() = Unit

        override suspend fun retireRemoteDevices(
            activation: SecureMessagingActivationCapability,
            affectedUserId: String,
            affectedServerDeviceId: String?,
        ) = Unit
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val DEVICE_ID = "22222222-2222-4222-8222-222222222222"
        const val INSTALLATION_ID = "33333333-3333-4333-8333-333333333333"
        val RESET_TARGET = SecureMessagingEnrollmentResetTarget(
            serverDeviceId = DEVICE_ID,
            enrollmentEpoch = 1,
            registrationId = 42,
            identityKeySha256 = "1".repeat(64),
            bundleVersion = 1,
        )
        val TOKENS = SessionTokens("access", "refresh", "session-one")
        const val PROFILE = """
            {"ok":true,"data":{"id":"$USER_ID","name":"Kit User"}}
        """
        const val DEVICES = """
            {"ok":true,"data":{"items":[{"id":"$DEVICE_ID","name":"Android phone",
            "platform":"android","is_current":true,"created_at":"2026-07-20T08:00:00Z",
            "last_seen_at":"2026-07-20T08:01:00Z"}]}}
        """
        const val READY_CAPABILITIES = """
            {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},
            "features":{"messaging":true},"authentication":{},"protocols":{"messaging":{
            "ready":true,"version":"v2","suite":"signal-pqxdh-kyber1024-double-ratchet-v2",
            "post_quantum":true}}}}
        """
    }
}
