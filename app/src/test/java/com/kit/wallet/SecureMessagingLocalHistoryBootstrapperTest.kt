package com.kit.wallet

import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingActivationCoordinator
import com.kit.wallet.data.messaging.SecureMessagingActiveSessionRegistry
import com.kit.wallet.data.messaging.SecureMessagingAuthBindingResolver
import com.kit.wallet.data.messaging.SecureMessagingAuthBindingStore
import com.kit.wallet.data.messaging.SecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.SecureMessagingKeyActivation
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingLocalHistoryBootstrapper
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SecureMessagingLocalHistoryBootstrapperTest {
    @Test
    fun `production restoration opens exact local history in airplane mode and retains attempt`() =
        runTest {
            val server = MockWebServer()
            val online = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!online.get()) return MockResponse().setResponseCode(503)
                    return when (request.path) {
                        "/api/kit-wallet/v1/capabilities" -> jsonResponse(READY_CAPABILITIES)
                        "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
                        "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            try {
                val tokens = testSession(
                    accountId = USER_ID,
                    sessionId = SESSION_ID,
                    cacheScopeId = CACHE_SCOPE_ID,
                )
                val owner = tokens.fence()
                val binding = SecureMessagingSessionBinding(
                    sessionEpoch = SESSION_ID,
                    userId = USER_ID,
                    serverDeviceId = DEVICE_ID,
                    installationId = INSTALLATION_ID,
                )
                val stateStore = TestSecureMessagingStateStore()
                val persistence = SecureMessagingAuthBindingStore(stateStore)
                persistence.persist(owner, binding)

                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                val api = retrofit.create(KitWalletApi::class.java)
                val sessions = MutableTestSessionStore(tokens)
                val guard = SecureMessagingLifecycleGuard()
                val registry = SecureMessagingActiveSessionRegistry(guard)
                val sessionLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                val resolver = SecureMessagingAuthBindingResolver(
                    sessions = sessions,
                    api = api,
                    apiCalls = ApiCallExecutor(moshi),
                    deviceIdentity = object : DeviceIdentityProvider {
                        override fun registration() = DeviceRegistrationDto(
                            installationId = INSTALLATION_ID,
                            name = "Test phone",
                            appVersion = "1",
                            osVersion = "1",
                            model = "Test",
                        )
                    },
                    persistence = persistence,
                )
                val coordinator = SecureMessagingActivationCoordinator(
                    transport = RemoteSecureMessagingTransport(
                        api,
                        retrofit.create(SecureMessagingWireApi::class.java),
                        ApiCallExecutor(moshi),
                    ),
                    lifecycle = guard,
                    sessions = registry,
                    keyActivation = SecureMessagingKeyActivation { },
                    initialSyncActivation = SecureMessagingInitialSyncActivation { },
                )
                val bootstrapper = SecureMessagingLocalHistoryBootstrapper(
                    sessions = sessions,
                    sessionLifecycle = sessionLifecycle,
                    bindingResolver = resolver,
                    activation = coordinator,
                    applicationScope = backgroundScope,
                )

                bootstrapper.start()
                runCurrent()
                assertNull(guard.localReadActivation.value)

                // This is the same state-open edge used by KeystoreSessionStore restoration.
                // Airplane mode remains active and WorkManager has not participated.
                sessionLifecycle.afterSessionSave()
                runCurrent()

                val localAuthority = guard.localReadActivation.value
                assertNotNull(localAuthority)
                assertEquals(binding, localAuthority?.binding)
                assertEquals(owner, localAuthority?.owner)
                assertEquals(SecureMessagingRuntimeStage.ACTIVATING, guard.snapshot().stage)
                assertNull(registry.currentOrNull())
                assertEquals(0, server.requestCount)

                online.set(true)
                val active = coordinator.advancePreparedActivation(owner, binding)

                assertSame(localAuthority, active.activation)
                assertEquals(SecureMessagingRuntimeStage.READY, guard.snapshot().stage)
                assertSame(active, registry.currentOrNull())
                assertEquals(3, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `replacement between metadata read and generation creation cannot revive old owner`() =
        runTest {
            val ownerA = testSession(
                accountId = USER_ID,
                sessionId = SESSION_ID,
                cacheScopeId = CACHE_SCOPE_ID,
            )
            val ownerB = ownerA.copy(accountId = OTHER_USER_ID)
            val sessions = ReplacingOnSecondLeaseSessionStore(ownerA, ownerB)
            val binding = SecureMessagingSessionBinding(
                sessionEpoch = SESSION_ID,
                userId = USER_ID,
                serverDeviceId = DEVICE_ID,
                installationId = INSTALLATION_ID,
            )
            val stateStore = TestSecureMessagingStateStore()
            val persistence = SecureMessagingAuthBindingStore(stateStore)
            persistence.persist(ownerA.fence(), binding)
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val server = MockWebServer().apply { start() }
            try {
                val api = Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(KitWalletApi::class.java)
                val guard = SecureMessagingLifecycleGuard()
                val registry = SecureMessagingActiveSessionRegistry(guard)
                val sessionLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                val bootstrapper = SecureMessagingLocalHistoryBootstrapper(
                    sessions = sessions,
                    sessionLifecycle = sessionLifecycle,
                    bindingResolver = SecureMessagingAuthBindingResolver(
                        sessions = sessions,
                        api = api,
                        apiCalls = ApiCallExecutor(moshi),
                        deviceIdentity = testDeviceIdentity(),
                        persistence = persistence,
                    ),
                    activation = SecureMessagingActivationCoordinator(
                        transport = RemoteSecureMessagingTransport(
                            api,
                            Retrofit.Builder()
                                .baseUrl(server.url("/"))
                                .addConverterFactory(MoshiConverterFactory.create(moshi))
                                .build()
                                .create(SecureMessagingWireApi::class.java),
                            ApiCallExecutor(moshi),
                        ),
                        lifecycle = guard,
                        sessions = registry,
                        keyActivation = SecureMessagingKeyActivation { },
                        initialSyncActivation = SecureMessagingInitialSyncActivation { },
                    ),
                    applicationScope = backgroundScope,
                )

                bootstrapper.start()
                sessionLifecycle.afterSessionSave()
                runCurrent()

                assertEquals(ownerB.fence(), sessions.current()?.fence())
                assertEquals(SecureMessagingRuntimeStage.NO_SESSION, guard.snapshot().stage)
                assertNull(guard.localReadActivation.value)
                assertEquals(0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `bootstrap retries contention until replacement owner has its own generation`() = runTest {
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestStarted.countDown()
                    check(releaseRequest.await(5, TimeUnit.SECONDS))
                    return jsonResponse(READY_CAPABILITIES)
                }
            }
            start()
        }
        try {
            val ownerA = testSession(
                accountId = USER_ID,
                sessionId = SESSION_ID,
                cacheScopeId = CACHE_SCOPE_ID,
            )
            val ownerB = ownerA.copy(accountId = OTHER_USER_ID)
            val bindingA = SecureMessagingSessionBinding(
                sessionEpoch = SESSION_ID,
                userId = USER_ID,
                serverDeviceId = DEVICE_ID,
                installationId = INSTALLATION_ID,
            )
            val bindingB = bindingA.copy(userId = OTHER_USER_ID)
            val sessions = MutableTestSessionStore(ownerA)
            val stateStore = TestSecureMessagingStateStore()
            val persistence = SecureMessagingAuthBindingStore(stateStore)
            val guard = SecureMessagingLifecycleGuard()
            val registry = SecureMessagingActiveSessionRegistry(guard)
            val lifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
            lifecycle.afterSessionSave()
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            val api = retrofit.create(KitWalletApi::class.java)
            val resolver = SecureMessagingAuthBindingResolver(
                sessions = sessions,
                api = api,
                apiCalls = ApiCallExecutor(moshi),
                deviceIdentity = testDeviceIdentity(),
                persistence = persistence,
            )
            val coordinator = SecureMessagingActivationCoordinator(
                transport = RemoteSecureMessagingTransport(
                    api,
                    retrofit.create(SecureMessagingWireApi::class.java),
                    ApiCallExecutor(moshi),
                ),
                lifecycle = guard,
                sessions = registry,
                keyActivation = SecureMessagingKeyActivation { },
                initialSyncActivation = SecureMessagingInitialSyncActivation { },
            )
            assertNotNull(coordinator.prepareActivationIfIdle(ownerA.fence(), bindingA))
            val staleAdvance = async {
                runCatching {
                    coordinator.advancePreparedActivation(ownerA.fence(), bindingA)
                }.exceptionOrNull()
            }
            runCurrent()
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

            // Replace A while its network activation still owns the coordinator mutex. B's first
            // local prepare observes contention and must retry rather than treating it as success.
            lifecycle.beforeSessionSave(isSameSession = false)
            sessions.save(ownerB)
            lifecycle.afterSessionSave()
            persistence.persist(ownerB.fence(), bindingB)
            val bootstrapper = SecureMessagingLocalHistoryBootstrapper(
                sessions = sessions,
                sessionLifecycle = lifecycle,
                bindingResolver = resolver,
                activation = coordinator,
                applicationScope = backgroundScope,
            )
            bootstrapper.start()
            runCurrent()
            assertNull(guard.localReadActivation.value)

            releaseRequest.countDown()
            assertNotNull(staleAdvance.await())
            // The collector runs in backgroundScope, whose delayed work advanceUntilIdle skips.
            // The old real-network callback may allow several virtual retries before its mutex is
            // released, so advance through the bootstrapper's maximum bounded backoff.
            advanceTimeBy(30_000L)
            runCurrent()

            assertEquals(ownerB.fence(), guard.localReadActivation.value?.owner)
            assertEquals(SecureMessagingRuntimeStage.ACTIVATING, guard.snapshot().stage)
            assertEquals(1, server.requestCount)
        } finally {
            releaseRequest.countDown()
            server.shutdown()
        }
    }

    private class ReplacingOnSecondLeaseSessionStore(
        initial: SessionTokens,
        private val replacement: SessionTokens,
        private val delegate: MutableTestSessionStore = MutableTestSessionStore(initial),
    ) : SessionStore by delegate {
        private var leases = 0

        override suspend fun <T> withCurrentSession(
            expected: SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            leases++
            if (leases == 2) {
                delegate.save(replacement)
                throw SessionInvalidatedException()
            }
            return delegate.withCurrentSession(expected, block)
        }
    }

    private fun testDeviceIdentity() = object : DeviceIdentityProvider {
        override fun registration() = DeviceRegistrationDto(
            installationId = INSTALLATION_ID,
            name = "Test phone",
            appVersion = "1",
            osVersion = "1",
            model = "Test",
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_USER_ID = "44444444-4444-4444-8444-444444444444"
        const val DEVICE_ID = "22222222-2222-4222-8222-222222222222"
        const val INSTALLATION_ID = "33333333-3333-4333-8333-333333333333"
        const val SESSION_ID = "restored-session"
        const val CACHE_SCOPE_ID = "restored-scope"
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
            "features":{"messaging":true,"messaging_groups":true,"messaging_reactions_e2ee_v1":true},"authentication":{},"protocols":{"messaging":{
            "ready":true,"version":"v2","suite":"signal-pqxdh-kyber1024-double-ratchet-v2",
            "post_quantum":true}}}}
        """
    }
}
