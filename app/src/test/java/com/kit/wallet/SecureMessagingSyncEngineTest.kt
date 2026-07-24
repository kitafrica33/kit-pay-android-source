package com.kit.wallet

import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget
import com.kit.wallet.data.messaging.AccountMessageHistoryRetention
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
import com.kit.wallet.data.messaging.SecureMessagingFreshProvisioningUnreadableException
import com.kit.wallet.data.messaging.SecureMessagingKeyActivation
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingLegacyConfirmedEnrollmentUnreadableException
import com.kit.wallet.data.messaging.SecureMessagingLegacyInitialEnrollmentUnreadableException
import com.kit.wallet.data.messaging.SecureMessagingLocalEnrollmentResetRequiredException
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingReauthenticationRequiredException
import com.kit.wallet.data.messaging.SecureMessagingRecordAuthenticationFailedException
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyTemporarilyUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingRevalidationRetryException
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingStateNotReadyException
import com.kit.wallet.data.messaging.SecureMessagingSyncCursorStore
import com.kit.wallet.data.messaging.SecureMessagingSyncCompletionSignal
import com.kit.wallet.data.messaging.awaitSecureMessagingStateAvailability
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessagingSyncDto
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.session.SecureMessagingResetProofFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import java.security.ProviderException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
            enqueueWakeSync(server, moshi, "wake_cursor")

            assertTrue(engine.isReady)
            assertTrue(registry.currentOrNull() == null)
            engine.synchronize()

            assertEquals(TOKENS.sessionId, registry.requireCurrent().binding.sessionEpoch)
            assertEquals(8, server.requestCount)

            val active = registry.requireCurrent()
            val staleFence = com.kit.wallet.data.messaging.SecureMessagingSessionFence(
                binding = active.binding,
                activationIdentity = Any(),
            )
            val redirected = runCatching { engine.synchronize(staleFence) }.exceptionOrNull()

            assertTrue(redirected is SecureMessagingAuthenticationEpochChangedException)
            assertEquals(8, server.requestCount)

            val expectedSession = TOKENS.fence()
            sessions.replace(TOKENS.copy(sessionId = "replacement-session"))
            val staleSessionContinuation = runCatching {
                engine.synchronize(expectedSession)
            }.exceptionOrNull()

            assertTrue(
                staleSessionContinuation is SecureMessagingAuthenticationEpochChangedException,
            )
            assertEquals(8, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `exact session replacement during binding resolution cannot publish obsolete work`() =
        runTest {
            val server = MockWebServer().apply { start() }
            val profileEntered = CountDownLatch(1)
            val releaseProfile = CountDownLatch(1)
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
                val processor = SecureMessagingEventProcessor(
                    UnusedCryptoEngine,
                    SecureMessagingProjectionStore(
                        stateStore,
                        com.kit.wallet.data.messaging.LibSignalCompanionStateReader(stateStore),
                    ),
                    SecureMessagingSyncCursorStore(stateStore),
                )
                var keyActivationCalls = 0
                val coordinator = SecureMessagingActivationCoordinator(
                    transport = transport,
                    lifecycle = guard,
                    sessions = registry,
                    keyActivation = SecureMessagingKeyActivation { keyActivationCalls++ },
                    initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
                )
                val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                localStateLifecycle.afterSessionSave()
                val completions = SecureMessagingSyncCompletionSignal()
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
                    syncCompletions = completions,
                )
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/api/kit-wallet/v1/profile") {
                            profileEntered.countDown()
                            if (releaseProfile.await(5, TimeUnit.SECONDS)) {
                                return jsonResponse(PROFILE)
                            }
                        }
                        return MockResponse().setResponseCode(503)
                    }
                }

                val expectedSession = TOKENS.fence()
                val staleSynchronization = async {
                    runCatching { engine.synchronize(expectedSession) }.exceptionOrNull()
                }
                runCurrent()
                assertTrue(profileEntered.await(5, TimeUnit.SECONDS))

                // Retain the same session epoch but replace the exact cache/account owner. The
                // epoch-only resolver would otherwise continue into device/key activation work.
                sessions.replace(TOKENS.copy(cacheScopeId = "replacement-cache-scope"))
                releaseProfile.countDown()

                val failure = staleSynchronization.await()
                assertTrue(failure is SecureMessagingAuthenticationEpochChangedException)
                assertTrue(registry.currentOrNull() == null)
                assertEquals(0, keyActivationCalls)
                assertTrue(completions.completions.replayCache.isEmpty())
                assertEquals(1, server.requestCount)
            } finally {
                releaseProfile.countDown()
                server.shutdown()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `one worker performs bounded Android 9 key retries without another wake`() = runTest {
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
            val processor = SecureMessagingEventProcessor(
                UnusedCryptoEngine,
                SecureMessagingProjectionStore(
                    stateStore,
                    com.kit.wallet.data.messaging.LibSignalCompanionStateReader(stateStore),
                ),
                SecureMessagingSyncCursorStore(stateStore),
            )
            var keyAttempts = 0
            val coordinator = SecureMessagingActivationCoordinator(
                transport = transport,
                lifecycle = guard,
                sessions = registry,
                keyActivation = SecureMessagingKeyActivation {
                    keyAttempts++
                    if (keyAttempts <= 3) {
                        throw SecureMessagingRevalidationRetryException(
                            SecureMessagingRecordKeyTemporarilyUnavailableException(),
                        )
                    }
                },
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
            // Each bounded retry revalidates the authenticated profile/device before touching
            // retained key state. The final pair belongs to initial encrypted synchronization.
            repeat(4) {
                server.enqueue(jsonResponse(PROFILE))
                server.enqueue(jsonResponse(DEVICES))
            }
            enqueueEmptySync(server, moshi, "initial_cursor")
            enqueueWakeSync(server, moshi, "wake_cursor")

            engine.synchronize()

            assertEquals(4, keyAttempts)
            assertEquals(15_000L, currentTime)
            assertEquals(SecureMessagingRuntimeStage.READY, guard.snapshot().stage)
            assertEquals(14, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `completed reset reopen failure cannot retain a stale pending activation`() =
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
                val registry = SecureMessagingActiveSessionRegistry(guard)
                val durableState = TestSecureMessagingStateStore()
                var cursorWrites = 0
                var failNextCursorWrite = false
                val stateStore = object : SecureMessagingStateStore by durableState {
                    override suspend fun write(
                        namespace: String,
                        recordKey: String,
                        expectedVersion: Long?,
                        bytes: ByteArray,
                    ): SecureMessagingRecordVersion {
                        cursorWrites++
                        if (failNextCursorWrite) {
                            failNextCursorWrite = false
                            throw SecureMessagingRecordKeyPermanentlyMissingException()
                        }
                        return durableState.write(
                            namespace,
                            recordKey,
                            expectedVersion,
                            bytes,
                        )
                    }
                }
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
                    sessions = registry,
                    keyActivation = SecureMessagingKeyActivation { },
                    initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
                )
                val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                localStateLifecycle.afterSessionSave()
                var localResets = 0
                val reopenFailure = IllegalStateException("reset gate failed to reopen")
                var failNextResetReopen = true
                sessions.messagingReset = { fence ->
                    localStateLifecycle.resetForRecovery(fence)
                    localResets++
                    if (failNextResetReopen) {
                        failNextResetReopen = false
                        sessions.setRestorationPending(true)
                        throw reopenFailure
                    }
                    localStateLifecycle.afterSessionSave()
                }
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
                // Activate A completely before the foreground recovery captures its exact fence.
                server.enqueue(jsonResponse(PROFILE))
                server.enqueue(jsonResponse(DEVICES))
                server.enqueue(jsonResponse(READY_CAPABILITIES))
                server.enqueue(jsonResponse(PROFILE))
                server.enqueue(jsonResponse(DEVICES))
                enqueueEmptySync(server, moshi, "a_initial_cursor")
                enqueueWakeSync(server, moshi, "a_wake_cursor")

                engine.synchronize()
                val initial = registry.requireCurrent()
                failNextCursorWrite = true

                // Recovery first re-enters A and proves the missing key, then resets and performs
                // the complete activation plus wake sync for its one pinned successor B.
                server.enqueue(jsonResponse(PROFILE))
                server.enqueue(jsonResponse(DEVICES))
                enqueueEmptySync(server, moshi, "a_missing_cursor")
                repeat(1) { activationIndex ->
                    server.enqueue(jsonResponse(PROFILE))
                    server.enqueue(jsonResponse(DEVICES))
                    server.enqueue(jsonResponse(READY_CAPABILITIES))
                    server.enqueue(jsonResponse(PROFILE))
                    server.enqueue(jsonResponse(DEVICES))
                    enqueueEmptySync(server, moshi, "initial_cursor_$activationIndex")
                    enqueueWakeSync(server, moshi, "wake_cursor_$activationIndex")
                }

                val failedReopen = runCatching {
                    engine.recoverPermanentlyUnavailableState(initial.fence)
                }.exceptionOrNull()

                assertSame(reopenFailure, failedReopen)
                assertEquals(1, localResets)

                // The exact-session retry owns gate reopening. A subsequent generic sync must
                // proceed to the successor instead of replaying the erased pending reset fence.
                sessions.setRestorationPending(false)
                localStateLifecycle.afterSessionSave()
                engine.synchronize()

                assertEquals(1, localResets)
                assertEquals(5, cursorWrites)
                assertEquals(SecureMessagingRuntimeStage.READY, guard.snapshot().stage)
                val successor = registry.requireCurrent()
                assertTrue(successor.fence !== initial.fence)
                assertEquals(TOKENS.sessionId, successor.binding.sessionEpoch)
                assertEquals(19, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `exact sync may erase recovered A but never enters replacement B`() = runTest {
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
            val durableState = TestSecureMessagingStateStore()
            var failNextCursorWrite = false
            var cursorWrites = 0
            val stateStore = object : SecureMessagingStateStore by durableState {
                override suspend fun write(
                    namespace: String,
                    recordKey: String,
                    expectedVersion: Long?,
                    bytes: ByteArray,
                ): SecureMessagingRecordVersion {
                    cursorWrites++
                    if (failNextCursorWrite) {
                        failNextCursorWrite = false
                        throw SecureMessagingRecordKeyPermanentlyMissingException()
                    }
                    return durableState.write(namespace, recordKey, expectedVersion, bytes)
                }
            }
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
                sessions = registry,
                keyActivation = SecureMessagingKeyActivation { },
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
            enqueueEmptySync(server, moshi, "a_initial_cursor")
            enqueueWakeSync(server, moshi, "a_wake_cursor")
            engine.synchronize()
            val initial = registry.requireCurrent()

            failNextCursorWrite = true
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))
            enqueueEmptySync(server, moshi, "a_missing_cursor")
            // These B responses make accidental redirection deterministic instead of hanging.
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))
            server.enqueue(jsonResponse(READY_CAPABILITIES))
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))
            enqueueEmptySync(server, moshi, "b_initial_cursor")
            enqueueEmptySync(server, moshi, "b_wake_cursor")

            val failure = runCatching {
                engine.synchronize(initial.fence)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingAuthenticationEpochChangedException)
            assertEquals(1, localResets)
            assertEquals(3, cursorWrites)
            assertEquals(SecureMessagingRuntimeStage.NO_SESSION, guard.snapshot().stage)
            assertTrue(registry.currentOrNull() == null)
            assertEquals(11, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `exact sync transient retry cannot redirect A into replacement B`() = runTest {
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
            val durableState = TestSecureMessagingStateStore()
            val transientFailureObserved = CompletableDeferred<Unit>()
            var failNextCursorWrite = false
            val stateStore = object : SecureMessagingStateStore by durableState {
                override suspend fun write(
                    namespace: String,
                    recordKey: String,
                    expectedVersion: Long?,
                    bytes: ByteArray,
                ): SecureMessagingRecordVersion {
                    if (failNextCursorWrite) {
                        failNextCursorWrite = false
                        transientFailureObserved.complete(Unit)
                        throw SecureMessagingRecordKeyTemporarilyUnavailableException()
                    }
                    return durableState.write(namespace, recordKey, expectedVersion, bytes)
                }
            }
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
            enqueueEmptySync(server, moshi, "a_initial_cursor")
            enqueueWakeSync(server, moshi, "a_wake_cursor")
            engine.synchronize()
            val initial = registry.requireCurrent()

            failNextCursorWrite = true
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))
            enqueueEmptySync(server, moshi, "a_retry_cursor")
            val staleExactSync = async {
                runCatching { engine.synchronize(initial.fence) }.exceptionOrNull()
            }
            transientFailureObserved.await()
            runCurrent()

            // Replace A while its internal Android-9 retry is sleeping. B activation is allowed
            // to finish independently, but waking A must fail before another binding/sync request.
            localStateLifecycle.beforeSessionClear()
            localStateLifecycle.afterSessionSave()
            server.enqueue(jsonResponse(READY_CAPABILITIES))
            server.enqueue(jsonResponse(PROFILE))
            server.enqueue(jsonResponse(DEVICES))
            enqueueEmptySync(server, moshi, "b_initial_cursor")
            val replacement = coordinator.ensureActivated(
                SecureMessagingSessionBinding(
                    sessionEpoch = TOKENS.sessionId,
                    userId = USER_ID,
                    serverDeviceId = DEVICE_ID,
                    installationId = INSTALLATION_ID,
                ),
            )
            assertTrue(replacement.fence !== initial.fence)
            val requestsBeforeWake = server.requestCount

            advanceTimeBy(5_000L)
            runCurrent()

            assertTrue(
                staleExactSync.await() is SecureMessagingAuthenticationEpochChangedException,
            )
            assertTrue(registry.requireCurrent().fence === replacement.fence)
            assertEquals(requestsBeforeWake, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `legacy v1 local reset advances remote enrollment before successor activation`() =
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
                val registry = SecureMessagingActiveSessionRegistry(guard)
                val stateStore = TestSecureMessagingStateStore()
                val processor = SecureMessagingEventProcessor(
                    UnusedCryptoEngine,
                    SecureMessagingProjectionStore(
                        stateStore,
                        com.kit.wallet.data.messaging.LibSignalCompanionStateReader(stateStore),
                    ),
                    SecureMessagingSyncCursorStore(stateStore),
                )
                val recoveryOrder = mutableListOf<String>()
                val localResetFences = mutableListOf<
                    com.kit.wallet.data.messaging.SecureMessagingSessionFence,
                    >()
                var activationAttempts = 0
                var legacyFailureFence:
                    com.kit.wallet.data.messaging.SecureMessagingSessionFence? = null
                var remoteResetFence:
                    com.kit.wallet.data.messaging.SecureMessagingSessionFence? = null
                val legacyV1Failure = SecureMessagingFreshProvisioningUnreadableException(
                    SecureMessagingLegacyInitialEnrollmentUnreadableException(
                        ProviderException("Android 9 failed to reopen the legacy v1 record"),
                    ),
                )
                val coordinator = SecureMessagingActivationCoordinator(
                    transport = transport,
                    lifecycle = guard,
                    sessions = registry,
                    keyActivation = SecureMessagingKeyActivation { session ->
                        when (activationAttempts++) {
                            0 -> {
                                legacyFailureFence = session.activationFence()
                                recoveryOrder += "legacy-v1-failure"
                                throw SecureMessagingLocalEnrollmentResetRequiredException(
                                    activationFence = session.activationFence(),
                                    message = "Legacy v1 enrollment must be erased",
                                    cause = legacyV1Failure,
                                )
                            }

                            1 -> {
                                check(localResetFences.size == 1) {
                                    "Remote recovery must observe the completed local reset"
                                }
                                remoteResetFence = session.activationFence()
                                recoveryOrder += "missing-local-enrollment"
                                throw SecureMessagingReauthenticationRequiredException(
                                    target = RESET_TARGET,
                                    activationFence = session.activationFence(),
                                    message = "The enrolled server bundle has no local identity",
                                )
                            }

                            2 -> recoveryOrder += "successor-activated"
                            else -> error("Unexpected secure-messaging activation attempt")
                        }
                    },
                    initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
                )
                val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                localStateLifecycle.afterSessionSave()
                sessions.messagingReset = { fence ->
                    recoveryOrder += "local-reset"
                    localStateLifecycle.resetForRecovery(fence)
                    localStateLifecycle.afterSessionSave()
                    localResetFences += fence
                }
                var remoteResets = 0
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
                        assertEquals(TOKENS.sessionId, epoch)
                        assertEquals(RESET_TARGET, target)
                        assertEquals(1, localResetFences.size)
                        recoveryOrder += "remote-reset"
                        remoteResets++
                        sessions.recordProvedReset(target)
                    },
                )
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
                        request.path == "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
                        request.path == "/api/kit-wallet/v1/capabilities" ->
                            jsonResponse(READY_CAPABILITIES)
                        request.path?.startsWith("/api/kit-wallet/v1/messaging/sync") == true ->
                            emptySyncResponse(moshi, "successor_cursor")
                        else -> MockResponse().setResponseCode(404)
                    }
                }

                engine.synchronize()

                assertEquals(3, activationAttempts)
                assertEquals(1, remoteResets)
                assertEquals(2, localResetFences.size)
                assertTrue(localResetFences[0] === legacyFailureFence)
                assertTrue(localResetFences[1] === remoteResetFence)
                assertEquals(
                    listOf(
                        "legacy-v1-failure",
                        "local-reset",
                        "missing-local-enrollment",
                        "remote-reset",
                        "local-reset",
                        "successor-activated",
                    ),
                    recoveryOrder,
                )
                val successor = registry.requireCurrent()
                assertTrue(successor.fence !== legacyFailureFence)
                assertTrue(successor.fence !== remoteResetFence)
                assertEquals(SecureMessagingRuntimeStage.READY, guard.snapshot().stage)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `legacy unreadable state bypasses snapshot after exact reset and reactivates`() =
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
                val legacyUnreadable = SecureMessagingLegacyInitialEnrollmentUnreadableException(
                    ProviderException("Android 9 cannot reopen the legacy direct record"),
                )
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
                                cause = legacyUnreadable,
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
                var snapshotAttempts = 0
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
                        sessions.recordProvedReset(target)
                    },
                    messageHistory = object : AccountMessageHistoryRetention {
                        override suspend fun snapshotActiveHistory(
                            target: com.kit.wallet.data.session.SessionFence,
                        ) {
                            snapshotAttempts++
                            throw legacyUnreadable
                        }

                        override suspend fun eraseAccount(
                            target: com.kit.wallet.data.session.SessionFence,
                        ) = Unit
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
                assertEquals(1, snapshotAttempts)
                assertEquals(1, localResets)
                assertEquals(com.kit.wallet.data.messaging.SecureMessagingRuntimeStage.READY, guard.snapshot().stage)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `proved reset bypass rejects missing proof corruption cancellation and changed owner`() =
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
                val legacyUnreadable = SecureMessagingLegacyInitialEnrollmentUnreadableException(
                    ProviderException("Android 9 cannot reopen the legacy direct record"),
                )
                val coordinator = SecureMessagingActivationCoordinator(
                    transport = transport,
                    lifecycle = guard,
                    sessions = SecureMessagingActiveSessionRegistry(guard),
                    keyActivation = SecureMessagingKeyActivation { session ->
                        throw SecureMessagingReauthenticationRequiredException(
                            target = RESET_TARGET,
                            activationFence = session.activationFence(),
                            message = "missing private key",
                            cause = legacyUnreadable,
                        )
                    },
                    initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
                )
                val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                localStateLifecycle.afterSessionSave()
                var localResets = 0
                sessions.messagingReset = { localResets++ }
                var recoveryAttempts = 0
                var snapshotAttempts = 0
                var replaceOwnerBeforeSnapshotFailure = false
                val authenticatedCorruption = SecureMessagingRecordAuthenticationFailedException(
                    IllegalStateException("authenticated projection corruption"),
                )
                var snapshotFailure: Throwable = authenticatedCorruption
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
                    authRepository = unusedAuthRepository { _, target ->
                        when (recoveryAttempts++) {
                            0 -> Unit
                            1 -> sessions.recordProvedReset(
                                target.copy(registrationId = target.registrationId + 1),
                            )
                            else -> sessions.recordProvedReset(target)
                        }
                    },
                    messageHistory = object : AccountMessageHistoryRetention {
                        override suspend fun snapshotActiveHistory(
                            target: com.kit.wallet.data.session.SessionFence,
                        ) {
                            snapshotAttempts++
                            assertEquals(TOKENS.fence(), target)
                            if (replaceOwnerBeforeSnapshotFailure) {
                                sessions.replace(
                                    TOKENS.copy(
                                        sessionId = "replacement-session",
                                        cacheScopeId = "replacement-cache",
                                    ),
                                )
                            }
                            throw snapshotFailure
                        }

                        override suspend fun eraseAccount(
                            target: com.kit.wallet.data.session.SessionFence,
                        ) = Unit
                    },
                )
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
                        request.path == "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
                        request.path == "/api/kit-wallet/v1/capabilities" ->
                            jsonResponse(READY_CAPABILITIES)
                        else -> MockResponse().setResponseCode(404)
                    }
                }

                val missingProof = runCatching { engine.synchronize() }.exceptionOrNull()
                assertTrue(missingProof is IllegalStateException)
                assertEquals(
                    "The exact server messaging reset did not leave a durable proof",
                    missingProof?.message,
                )
                assertEquals(0, snapshotAttempts)

                val mismatchedProof = runCatching { engine.synchronize() }.exceptionOrNull()
                assertTrue(mismatchedProof is IllegalStateException)
                assertEquals(
                    "The exact server messaging reset did not leave a durable proof",
                    mismatchedProof?.message,
                )
                assertEquals(0, snapshotAttempts)

                val corruption = runCatching { engine.synchronize() }.exceptionOrNull()
                assertSame(authenticatedCorruption, corruption)
                assertEquals(1, snapshotAttempts)

                val cancelledSnapshot = CancellationException("snapshot cancelled")
                snapshotFailure = cancelledSnapshot
                val cancellation = runCatching { engine.synchronize() }.exceptionOrNull()
                assertSame(cancelledSnapshot, cancellation)
                assertEquals(2, snapshotAttempts)

                snapshotFailure = legacyUnreadable
                replaceOwnerBeforeSnapshotFailure = true
                val replaced = runCatching { engine.synchronize() }.exceptionOrNull()
                assertTrue(replaced is SecureMessagingAuthenticationEpochChangedException)
                assertEquals(3, recoveryAttempts)
                assertEquals(3, snapshotAttempts)
                assertEquals(0, localResets)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `process restart adopts exact reset proof before erasing unreadable legacy state`() =
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
                val proof = provedReset(RESET_TARGET)
                val retainedLogin = TOKENS.copy(messagingResetProof = proof)
                val sessions = FakeSessionStore(retainedLogin)
                val guard = SecureMessagingLifecycleGuard()
                val registry = SecureMessagingActiveSessionRegistry(guard)
                val stateStore = TestSecureMessagingStateStore()
                val processor = SecureMessagingEventProcessor(
                    UnusedCryptoEngine,
                    SecureMessagingProjectionStore(
                        stateStore,
                        com.kit.wallet.data.messaging.LibSignalCompanionStateReader(stateStore),
                    ),
                    SecureMessagingSyncCursorStore(stateStore),
                )
                val unreadable = SecureMessagingLegacyConfirmedEnrollmentUnreadableException(
                    IllegalStateException("authenticated API-28 legacy truncation"),
                )
                var activationAttempts = 0
                var resetFence:
                    com.kit.wallet.data.messaging.SecureMessagingSessionFence? = null
                val coordinator = SecureMessagingActivationCoordinator(
                    transport = transport,
                    lifecycle = guard,
                    sessions = registry,
                    keyActivation = SecureMessagingKeyActivation { session ->
                        when (activationAttempts++) {
                            0 -> throw SecureMessagingLocalEnrollmentResetRequiredException(
                                activationFence = session.activationFence(),
                                message = "Adopt the proved reset after process restart",
                                cause = unreadable,
                                provenResetTarget = RESET_TARGET,
                                provenResetProof = proof,
                            )

                            1 -> Unit
                            else -> error("Unexpected post-reset activation attempt")
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
                    resetFence = fence
                    localResets++
                }
                var snapshotAttempts = 0
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
                    messageHistory = object : AccountMessageHistoryRetention {
                        override suspend fun snapshotActiveHistory(
                            target: com.kit.wallet.data.session.SessionFence,
                        ) {
                            snapshotAttempts++
                            assertEquals(retainedLogin.fence(), target)
                            throw unreadable
                        }

                        override suspend fun eraseAccount(
                            target: com.kit.wallet.data.session.SessionFence,
                        ) = Unit
                    },
                )
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
                        request.path == "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
                        request.path == "/api/kit-wallet/v1/capabilities" ->
                            jsonResponse(READY_CAPABILITIES)
                        request.path?.startsWith("/api/kit-wallet/v1/messaging/sync") == true ->
                            emptySyncResponse(moshi, "proved_restart_cursor")
                        else -> MockResponse().setResponseCode(404)
                    }
                }

                engine.synchronize()

                assertEquals(retainedLogin.fence(), sessions.current()?.fence())
                assertEquals(proof, sessions.current()?.messagingResetProof)
                assertEquals(1, snapshotAttempts)
                assertEquals(1, localResets)
                assertEquals(1, sessions.provedMessagingResetCalls)
                assertTrue(resetFence != null)
                assertEquals(2, activationAttempts)
                assertEquals(SecureMessagingRuntimeStage.READY, guard.snapshot().stage)
                assertTrue(registry.requireCurrent().fence !== resetFence)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `process restart rejects removed or replaced reset proof before local erasure`() =
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
                val proof = provedReset(RESET_TARGET)
                val mismatchedProof = provedReset(
                    RESET_TARGET.copy(registrationId = RESET_TARGET.registrationId + 1),
                )
                val replacements = listOf<SecureMessagingResetProofFence?>(
                    null,
                    mismatchedProof,
                )
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
                        request.path == "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
                        request.path == "/api/kit-wallet/v1/capabilities" ->
                            jsonResponse(READY_CAPABILITIES)
                        else -> MockResponse().setResponseCode(404)
                    }
                }

                replacements.forEach { replacement ->
                    val retainedLogin = TOKENS.copy(messagingResetProof = proof)
                    val sessions = FakeSessionStore(retainedLogin)
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
                            throw SecureMessagingLocalEnrollmentResetRequiredException(
                                activationFence = session.activationFence(),
                                message = "Adopt the proved reset after process restart",
                                provenResetTarget = RESET_TARGET,
                                provenResetProof = proof,
                            )
                        },
                        initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
                    )
                    val localStateLifecycle = SecureMessagingSessionLifecycle(stateStore, guard)
                    localStateLifecycle.afterSessionSave()
                    var localResets = 0
                    sessions.messagingReset = { localResets++ }
                    sessions.beforeProvedMessagingReset = {
                        val current = checkNotNull(sessions.current())
                        sessions.replace(current.copy(messagingResetProof = replacement))
                    }
                    var snapshotAttempts = 0
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
                        messageHistory = object : AccountMessageHistoryRetention {
                            override suspend fun snapshotActiveHistory(
                                target: com.kit.wallet.data.session.SessionFence,
                            ) {
                                snapshotAttempts++
                            }

                            override suspend fun eraseAccount(
                                target: com.kit.wallet.data.session.SessionFence,
                            ) = Unit
                        },
                    )

                    val failure = runCatching { engine.synchronize() }.exceptionOrNull()

                    assertTrue(failure is SecureMessagingAuthenticationEpochChangedException)
                    assertEquals(retainedLogin.fence(), sessions.current()?.fence())
                    assertEquals(replacement, sessions.current()?.messagingResetProof)
                    assertEquals(0, snapshotAttempts)
                    assertEquals(0, localResets)
                    assertEquals(1, sessions.provedMessagingResetCalls)
                }
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
                (arguments?.get(0) as com.kit.wallet.data.session.SessionFence).sessionId,
                arguments[2] as SecureMessagingEnrollmentResetTarget,
            ) ?: error("Recovery must not run for an intact enrollment")
            "requireFreshAuthenticationForSecureMessagingRecovery" ->
                freshAuthentication?.invoke(
                    (arguments?.get(0) as com.kit.wallet.data.session.SessionFence).sessionId,
                )
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

    private fun enqueueWakeSync(server: MockWebServer, moshi: Moshi, cursor: String) {
        enqueueEmptySync(server, moshi, cursor)
        server.enqueue(jsonResponse("""{"ok":true,"data":{"items":[]}}"""))
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

    private fun provedReset(
        target: SecureMessagingEnrollmentResetTarget,
    ) = SecureMessagingResetProofFence(
        serverDeviceId = target.serverDeviceId,
        previousEnrollmentEpoch = target.enrollmentEpoch,
        resultingEnrollmentEpoch = target.enrollmentEpoch + 1L,
        previousRegistrationId = target.registrationId,
        previousIdentityKeySha256 = target.identityKeySha256,
        previousBundleVersion = target.bundleVersion,
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private class FakeSessionStore(initial: SessionTokens?) : SessionStore {
        private val mutable = MutableStateFlow(initial)
        private val mutableRestorationPending = MutableStateFlow(false)
        private var revision = 0L
        override val session = mutable
        override val restorationPending = mutableRestorationPending
        var messagingReset: suspend (com.kit.wallet.data.messaging.SecureMessagingSessionFence) -> Unit = {}
        var beforeProvedMessagingReset: () -> Unit = {}
        var provedMessagingResetCalls: Int = 0
            private set

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
            allowPermanentlyUnavailableSnapshot: Boolean,
            finalMessagingSnapshot: suspend () -> Unit,
        ): Boolean {
            if (mutable.value?.fence() != expected) return false
            try {
                finalMessagingSnapshot()
            } catch (error: Throwable) {
                if (!allowPermanentlyUnavailableSnapshot ||
                    !com.kit.wallet.data.messaging
                        .isRecoverableSecureMessagingStateLoss(error)
                ) {
                    throw error
                }
            }
            messagingReset(activationFence)
            return true
        }

        override suspend fun resetSecureMessagingStateAfterProvenRemoteResetIfCurrent(
            expected: com.kit.wallet.data.session.SessionFence,
            activationFence: com.kit.wallet.data.messaging.SecureMessagingSessionFence,
            proof: SecureMessagingResetProofFence,
            allowPermanentlyUnavailableSnapshot: Boolean,
            finalMessagingSnapshot: suspend () -> Unit,
        ): Boolean {
            provedMessagingResetCalls++
            beforeProvedMessagingReset()
            val current = mutable.value ?: return false
            if (!proof.proved ||
                current.fence() != expected ||
                current.messagingResetProof != proof
            ) {
                return false
            }
            return resetSecureMessagingStateIfCurrent(
                expected = expected,
                activationFence = activationFence,
                allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
                finalMessagingSnapshot = finalMessagingSnapshot,
            )
        }

        override suspend fun clear() {
            mutable.value = null
            revision++
        }

        fun replace(tokens: SessionTokens?) {
            mutable.value = tokens
            revision++
        }

        fun setRestorationPending(pending: Boolean) {
            mutableRestorationPending.value = pending
        }

        fun recordProvedReset(target: SecureMessagingEnrollmentResetTarget) {
            val current = checkNotNull(mutable.value)
            replace(
                current.copy(
                    messagingResetProof = SecureMessagingResetProofFence(
                        serverDeviceId = target.serverDeviceId,
                        previousEnrollmentEpoch = target.enrollmentEpoch,
                        resultingEnrollmentEpoch = target.enrollmentEpoch + 1L,
                        previousRegistrationId = target.registrationId,
                        previousIdentityKeySha256 = target.identityKeySha256,
                        previousBundleVersion = target.bundleVersion,
                    ),
                ),
            )
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
