package com.kit.wallet

import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingActivationCoordinator
import com.kit.wallet.data.messaging.SecureMessagingActiveSessionRegistry
import com.kit.wallet.data.messaging.SecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.SecureMessagingKeyActivation
import com.kit.wallet.data.messaging.SecureMessagingKeyReconciliationException
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SecureMessagingActivationCoordinatorTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: RemoteSecureMessagingTransport
    private lateinit var lifecycle: SecureMessagingLifecycleGuard
    private lateinit var registry: SecureMessagingActiveSessionRegistry

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        transport = RemoteSecureMessagingTransport(
            retrofit.create(KitWalletApi::class.java),
            retrofit.create(SecureMessagingWireApi::class.java),
            ApiCallExecutor(moshi),
        )
        lifecycle = SecureMessagingLifecycleGuard()
        registry = SecureMessagingActiveSessionRegistry(lifecycle)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `activation publishes only after ordered keys and initial sync and is idempotent`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            keys = {
                assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, lifecycle.snapshot().stage)
                assertNull(registry.currentOrNull())
                events += "keys"
            },
            initialSync = {
                assertEquals(SecureMessagingRuntimeStage.SYNCING_ROSTER, lifecycle.snapshot().stage)
                assertNull(registry.currentOrNull())
                events += "sync"
            },
        )
        enqueueRemoteActivation()

        assertNull(coordinator.activeSession.value)
        val first = coordinator.ensureActivated(BINDING)

        assertEquals(listOf("keys", "sync"), events)
        assertEquals(SecureMessagingRuntimeStage.READY, coordinator.activationState.value.stage)
        assertSame(first, coordinator.activeSession.value)
        assertSame(first, registry.requireCurrent())
        assertSame(first, coordinator.ensureActivated(BINDING))
        assertEquals(3, server.requestCount)
        assertActivationRequestPaths()
    }

    @Test
    fun `key failure retains the fenced transport and retries only incomplete stages`() = runTest {
        var keyCalls = 0
        var syncCalls = 0
        val coordinator = coordinator(
            keys = {
                keyCalls++
                if (keyCalls == 1) error("key store temporarily unavailable")
            },
            initialSync = { syncCalls++ },
        )
        enqueueRemoteActivation()

        val firstFailure = runCatching { coordinator.ensureActivated(BINDING) }.exceptionOrNull()

        assertTrue(firstFailure is IllegalStateException)
        assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, lifecycle.snapshot().stage)
        assertNull(registry.currentOrNull())
        assertEquals(3, server.requestCount)

        coordinator.ensureActivated(BINDING)

        assertEquals(2, keyCalls)
        assertEquals(1, syncCalls)
        assertEquals(3, server.requestCount)
        assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
    }

    @Test
    fun `initial sync failure retries sync without republishing or repeating key work`() = runTest {
        var keyCalls = 0
        var syncCalls = 0
        val coordinator = coordinator(
            keys = { keyCalls++ },
            initialSync = {
                syncCalls++
                if (syncCalls == 1) error("cursor persistence temporarily unavailable")
            },
        )
        enqueueRemoteActivation()

        assertTrue(runCatching { coordinator.ensureActivated(BINDING) }.isFailure)
        assertEquals(SecureMessagingRuntimeStage.SYNCING_ROSTER, lifecycle.snapshot().stage)
        assertNull(coordinator.activeSession.value)

        coordinator.ensureActivated(BINDING)

        assertEquals(1, keyCalls)
        assertEquals(2, syncCalls)
        assertEquals(3, server.requestCount)
        assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
    }

    @Test
    fun `key integrity mismatch quarantines activation and cannot be retried`() = runTest {
        val coordinator = coordinator(
            keys = {
                throw SecureMessagingKeyReconciliationException(
                    SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                    "server identity changed",
                )
            },
        )
        enqueueRemoteActivation()

        assertTrue(runCatching { coordinator.ensureActivated(BINDING) }.isFailure)

        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, lifecycle.snapshot().stage)
        assertEquals(
            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
            lifecycle.snapshot().quarantineReason,
        )
        assertNull(registry.currentOrNull())
        assertTrue(runCatching { coordinator.ensureActivated(BINDING) }.isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `concurrent ensure calls coalesce onto one activation and one active handle`() = runTest {
        val keyEntered = CompletableDeferred<Unit>()
        val releaseKey = CompletableDeferred<Unit>()
        var keyCalls = 0
        var syncCalls = 0
        val coordinator = coordinator(
            keys = {
                keyCalls++
                keyEntered.complete(Unit)
                releaseKey.await()
            },
            initialSync = { syncCalls++ },
        )
        enqueueRemoteActivation()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.ensureActivated(BINDING)
        }
        keyEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.ensureActivated(BINDING)
        }
        releaseKey.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, keyCalls)
        assertEquals(1, syncCalls)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `logout fences and removes active handle before a replacement can activate`() = runTest {
        val coordinator = coordinator()
        enqueueRemoteActivation()
        val first = coordinator.ensureActivated(BINDING)
        assertSame(first, registry.currentOrNull())

        lifecycle.beginErasure()

        assertEquals(SecureMessagingRuntimeStage.ERASING, coordinator.activationState.value.stage)
        assertNull(coordinator.activeSession.value)
        assertNull(registry.currentOrNull())
        assertTrue(runCatching { coordinator.ensureActivated(BINDING) }.isFailure)
        assertTrue(runCatching { coordinator.ensureActivated(OTHER_BINDING) }.isFailure)
        assertEquals(3, server.requestCount)

        lifecycle.finishErasure()
        enqueueRemoteActivation()
        val replacement = coordinator.ensureActivated(BINDING)

        assertNotSame(first, replacement)
        assertEquals(6, server.requestCount)
        assertSame(replacement, registry.currentOrNull())
    }

    private fun coordinator(
        keys: suspend (RemoteSecureMessagingTransport.Session) -> Unit = {},
        initialSync: suspend (RemoteSecureMessagingTransport.Session) -> Unit = {},
    ) = SecureMessagingActivationCoordinator(
        transport = transport,
        lifecycle = lifecycle,
        sessions = registry,
        keyActivation = SecureMessagingKeyActivation(keys),
        initialSyncActivation = SecureMessagingInitialSyncActivation(initialSync),
    )

    private fun enqueueRemoteActivation() {
        server.enqueue(jsonResponse(READY_CAPABILITIES))
        server.enqueue(jsonResponse(PROFILE))
        server.enqueue(jsonResponse(DEVICES))
    }

    private fun assertActivationRequestPaths() {
        assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/profile", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/devices", server.takeRequest().path)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setResponseCode(200)
        .setBody(body)

    private companion object {
        const val CURRENT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val CURRENT_DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        val BINDING = SecureMessagingSessionBinding(
            sessionEpoch = "epoch-1",
            userId = CURRENT_USER_ID,
            serverDeviceId = CURRENT_DEVICE_ID,
            installationId = "installation-1",
        )
        val OTHER_BINDING = BINDING.copy(sessionEpoch = "epoch-2")
        const val READY_CAPABILITIES = """
            {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},
            "features":{"messaging":true},"authentication":{},"protocols":{"messaging":{
            "ready":true,"version":"v2","suite":"signal-pqxdh-kyber1024-double-ratchet-v2",
            "post_quantum":true}}}}
        """
        const val PROFILE = """
            {"ok":true,"data":{"id":"$CURRENT_USER_ID","name":"Kit User"}}
        """
        const val DEVICES = """
            {"ok":true,"data":{"items":[{"id":"$CURRENT_DEVICE_ID","name":"Android phone",
            "platform":"android","is_current":true,"created_at":"2026-07-20T08:00:00Z",
            "last_seen_at":"2026-07-20T08:01:00Z"}]}}
        """
    }
}
