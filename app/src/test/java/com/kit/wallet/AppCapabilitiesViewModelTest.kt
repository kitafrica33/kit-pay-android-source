package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessagingProtocolDto
import com.kit.wallet.data.remote.ProtocolsDto
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.realtime.KitNetworkEvent
import com.kit.wallet.data.realtime.KitNetworkSource
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.session.CachedSessionCapabilities
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.navigation.AppCapabilitiesViewModel
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppCapabilitiesViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `session transitions fence stale discovery and refresh both login and logout`() = runTest {
        val api = ScriptedCapabilitiesApi()
        val sessions = MutableTestSessionStore(null)
        val viewModel = AppCapabilitiesViewModel(
            api = api.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = FakeChatRepository(),
            pushMessagingTransport = FakePushMessagingTransport,
            networkSource = FakeNetworkSource(),
            sessions = sessions,
        )

        assertEquals(1, api.calls)
        assertFalse(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.pushMessagingConfigured)

        // Login must cancel the still-running anonymous request and load the cohort response.
        sessions.save(testSession(accountId = "account-1"))

        assertEquals(2, api.calls)
        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.messagingUsable)
        assertTrue(viewModel.state.value.biometricTokensAvailable)

        // Even a transport that completes after cancellation cannot overwrite the new session.
        api.completeInitialAnonymousRequest()

        assertTrue(viewModel.state.value.messagingUsable)

        // Logout is also a session transition. It clears personalized readiness synchronously
        // while the replacement anonymous discovery request is still outstanding.
        sessions.clear()

        assertEquals(3, api.calls)
        assertFalse(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.messagingProtocolReady)
        assertFalse(viewModel.state.value.biometricTokensAvailable)

        api.completeLogoutAnonymousRequest()

        assertTrue(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.loadFailed)
        assertFalse(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.messagingUsable)
    }

    @Test
    fun `local activation refreshes a stale pre-rollout capability snapshot`() = runTest {
        val api = RolloutCapabilitiesApi()
        val chatRepository = FakeChatRepository(initiallyReady = false)
        val viewModel = AppCapabilitiesViewModel(
            api = api.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = chatRepository,
            pushMessagingTransport = FakePushMessagingTransport,
            networkSource = FakeNetworkSource(),
            sessions = MutableTestSessionStore(null),
        )

        assertEquals(1, api.calls)
        assertTrue(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.messagingProtocolReady)
        assertFalse(viewModel.state.value.messagingUsable)

        chatRepository.readiness.value = true

        assertEquals(2, api.calls)
        assertTrue(viewModel.state.value.messagingProtocolReady)
        assertTrue(viewModel.state.value.messagingUsable)
    }

    @Test
    fun `offline cold start restores only read surfaces from the encrypted session`() = runTest {
        val sessions = MutableTestSessionStore(
            testSession(accountId = "account-1").copy(
                cachedCapabilities = CachedSessionCapabilities(
                    messaging = true,
                    calls = true,
                    messagingGroups = true,
                ),
            ),
        )
        val viewModel = AppCapabilitiesViewModel(
            api = OfflineCapabilitiesApi.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = FakeChatRepository(initiallyReady = false),
            pushMessagingTransport = FakePushMessagingTransport,
            networkSource = FakeNetworkSource(),
            sessions = sessions,
        )

        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.loadFailed)
        assertTrue(viewModel.state.value.messagingEntryVisible)
        assertTrue(viewModel.state.value.routeUsable(com.kit.wallet.navigation.Dest.CALLS))
        assertTrue(viewModel.state.value.lastKnownEnabled("messaging_groups"))
        assertFalse(viewModel.state.value.enabled(KitFeature.MESSAGING))

        // Logout removes the encrypted credential and its owner-scoped display snapshot.
        sessions.clear()
        assertFalse(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.routeUsable(com.kit.wallet.navigation.Dest.CALLS))
    }

    @Test
    fun `legacy offline session discovers messaging from owner fenced local history`() = runTest {
        val sessions = MutableTestSessionStore(testSession(accountId = "account-1"))
        val viewModel = AppCapabilitiesViewModel(
            api = OfflineCapabilitiesApi.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = FakeChatRepository(
                initiallyReady = false,
                initiallyLocalHistoryReady = true,
                initiallyLocalHistoryOwner = sessions.current()?.fence(),
            ),
            pushMessagingTransport = FakePushMessagingTransport,
            networkSource = FakeNetworkSource(),
            sessions = sessions,
        )

        assertTrue(viewModel.state.value.loadFailed)
        assertTrue(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.messagingUsable)
    }

    @Test
    fun `authenticated account replacement invalidates capability and local history owners`() =
        runTest {
            val ownerA = testSession(accountId = "account-a")
            // Keep the epoch and cache scope byte-for-byte identical so only the account component
            // of SessionFence can invalidate A's state.
            val ownerB = ownerA.copy(accountId = "account-b")
            val sessions = MutableTestSessionStore(ownerA)
            val chatRepository = FakeChatRepository(
                initiallyReady = false,
                initiallyLocalHistoryReady = true,
                initiallyLocalHistoryOwner = ownerA.fence(),
            )
            val api = ReplacementCapabilitiesApi()
            val viewModel = AppCapabilitiesViewModel(
                api = api.proxy,
                apiCalls = ApiCallExecutor(
                    Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
                ),
                chatRepository = chatRepository,
                pushMessagingTransport = FakePushMessagingTransport,
                networkSource = FakeNetworkSource(),
                sessions = sessions,
            )

            assertTrue(viewModel.state.value.loaded)
            assertTrue(viewModel.state.value.messagingProtocolReady)
            assertTrue(viewModel.state.value.secureMessagingLocalHistoryReady)

            // A and B are both signed in. The exact owner transition must still clear all of A's
            // server-scoped state, and A's still-true local history cannot be relabelled as B's.
            sessions.save(ownerB)

            assertEquals(2, api.calls)
            assertFalse(viewModel.state.value.loaded)
            assertFalse(viewModel.state.value.messagingProtocolReady)
            assertFalse(viewModel.state.value.secureMessagingLocalHistoryReady)
            assertTrue(viewModel.state.value.communicationAccess == null)
            assertTrue(viewModel.state.value.financialAccess == null)

            chatRepository.localHistoryOwner.value = ownerB.fence()
            assertTrue(viewModel.state.value.secureMessagingLocalHistoryReady)

            api.completeReplacementRequest()
            assertTrue(viewModel.state.value.loaded)
        }

    @Test
    fun `local history requires the complete session fence`() = runTest {
        val sessionA = testSession(
            accountId = "account-a",
            sessionId = "shared-session",
            cacheScopeId = "scope-a",
        )
        val sessionB = sessionA.copy(cacheScopeId = "scope-b")
        val sessions = MutableTestSessionStore(sessionA)
        val chatRepository = FakeChatRepository(
            initiallyReady = false,
            initiallyLocalHistoryReady = true,
            initiallyLocalHistoryOwner = sessionA.fence(),
        )
        val viewModel = AppCapabilitiesViewModel(
            api = OfflineCapabilitiesApi.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = chatRepository,
            pushMessagingTransport = FakePushMessagingTransport,
            networkSource = FakeNetworkSource(),
            sessions = sessions,
        )

        assertTrue(viewModel.state.value.secureMessagingLocalHistoryReady)

        sessions.save(sessionB)

        assertFalse(viewModel.state.value.secureMessagingLocalHistoryReady)
        chatRepository.localHistoryOwner.value = sessionB.fence()
        assertTrue(viewModel.state.value.secureMessagingLocalHistoryReady)
    }

    @Test
    fun `constructor replacement cannot retain the previous owners offline flags`() = runTest {
        val ownerA = testSession(accountId = "account-a").copy(
            cachedCapabilities = CachedSessionCapabilities(
                messaging = true,
                calls = true,
                messagingGroups = true,
            ),
        )
        val ownerB = ownerA.copy(
            accountId = "account-b",
            cachedCapabilities = null,
        )
        val sessions = ReplacingDuringInitialReadSessionStore(ownerA, ownerB)
        val viewModel = AppCapabilitiesViewModel(
            api = OfflineCapabilitiesApi.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = FakeChatRepository(
                initiallyReady = false,
                initiallyLocalHistoryReady = true,
                initiallyLocalHistoryOwner = ownerA.fence(),
            ),
            pushMessagingTransport = FakePushMessagingTransport,
            networkSource = FakeNetworkSource(),
            sessions = sessions,
        )

        assertEquals(ownerB.fence(), sessions.current()?.fence())
        assertTrue(viewModel.state.value.loadFailed)
        assertFalse(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.routeUsable(com.kit.wallet.navigation.Dest.CALLS))
        assertFalse(viewModel.state.value.secureMessagingLocalHistoryReady)
    }

    @Test
    fun `successful authenticated discovery durably replaces retained read flags`() = runTest {
        val sessions = MutableTestSessionStore(testSession(accountId = "account-1"))
        AppCapabilitiesViewModel(
            api = RolloutCapabilitiesApi().proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = FakeChatRepository(),
            pushMessagingTransport = FakePushMessagingTransport,
            networkSource = FakeNetworkSource(),
            sessions = sessions,
        )

        assertEquals(
            CachedSessionCapabilities(messaging = true),
            sessions.current()?.cachedCapabilities,
        )
    }

    @Test
    fun `foreground loop refreshes immediately and periodically until lifecycle cancellation`() =
        runTest {
            val api = RolloutCapabilitiesApi()
            val viewModel = AppCapabilitiesViewModel(
                api = api.proxy,
                apiCalls = ApiCallExecutor(
                    Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
                ),
                chatRepository = FakeChatRepository(),
                pushMessagingTransport = FakePushMessagingTransport,
                networkSource = FakeNetworkSource(),
                sessions = MutableTestSessionStore(null),
            )
            assertEquals(1, api.calls)

            val foregroundJob = backgroundScope.launch {
                viewModel.refreshWhileForeground(intervalMillis = 1_000)
            }
            runCurrent()
            assertEquals(2, api.calls)

            advanceTimeBy(999)
            runCurrent()
            assertEquals(2, api.calls)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, api.calls)

            foregroundJob.cancel()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(3, api.calls)
        }

    private class ScriptedCapabilitiesApi {
        var calls: Int = 0
            private set

        private lateinit var initialContinuation: Continuation<ApiEnvelope<CapabilitiesDto>>
        private lateinit var logoutContinuation: Continuation<ApiEnvelope<CapabilitiesDto>>

        val proxy: KitWalletApi = Proxy.newProxyInstance(
            KitWalletApi::class.java.classLoader,
            arrayOf(KitWalletApi::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "capabilities" -> capabilities(arguments.orEmpty())
                "toString" -> "ScriptedCapabilitiesApi"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        } as KitWalletApi

        fun completeInitialAnonymousRequest() {
            initialContinuation.resume(envelope(enabled = false))
        }

        fun completeLogoutAnonymousRequest() {
            logoutContinuation.resume(envelope(enabled = false))
        }

        @Suppress("UNCHECKED_CAST")
        private fun capabilities(arguments: Array<out Any?>): Any {
            val continuation = arguments.last() as Continuation<ApiEnvelope<CapabilitiesDto>>

            return when (++calls) {
                1 -> {
                    initialContinuation = continuation
                    COROUTINE_SUSPENDED
                }
                2 -> envelope(enabled = true)
                3 -> {
                    logoutContinuation = continuation
                    COROUTINE_SUSPENDED
                }
                else -> error("Unexpected capabilities request")
            }
        }
    }

    private class RolloutCapabilitiesApi {
        var calls: Int = 0
            private set

        val proxy: KitWalletApi = Proxy.newProxyInstance(
            KitWalletApi::class.java.classLoader,
            arrayOf(KitWalletApi::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "capabilities" -> envelope(
                    enabled = true,
                    protocolReady = ++calls > 1,
                )
                "toString" -> "RolloutCapabilitiesApi"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        } as KitWalletApi
    }

    private class ReplacementCapabilitiesApi {
        var calls: Int = 0
            private set

        private lateinit var replacementContinuation: Continuation<ApiEnvelope<CapabilitiesDto>>

        val proxy: KitWalletApi = Proxy.newProxyInstance(
            KitWalletApi::class.java.classLoader,
            arrayOf(KitWalletApi::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "capabilities" -> capabilities(arguments.orEmpty())
                "toString" -> "ReplacementCapabilitiesApi"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        } as KitWalletApi

        fun completeReplacementRequest() {
            replacementContinuation.resume(envelope(enabled = false))
        }

        @Suppress("UNCHECKED_CAST")
        private fun capabilities(arguments: Array<out Any?>): Any = when (++calls) {
            1 -> envelope(enabled = true)
            2 -> {
                replacementContinuation =
                    arguments.last() as Continuation<ApiEnvelope<CapabilitiesDto>>
                COROUTINE_SUSPENDED
            }
            else -> error("Unexpected capabilities request")
        }
    }

    private class ReplacingDuringInitialReadSessionStore(
        private val initialRead: SessionTokens,
        replacement: SessionTokens,
        private val delegate: MutableTestSessionStore = MutableTestSessionStore(replacement),
    ) : SessionStore by delegate {
        private var initialReadPending = true

        override fun current(): SessionTokens? = if (initialReadPending) {
            initialReadPending = false
            initialRead
        } else {
            delegate.current()
        }
    }

    private object OfflineCapabilitiesApi {
        val proxy: KitWalletApi = Proxy.newProxyInstance(
            KitWalletApi::class.java.classLoader,
            arrayOf(KitWalletApi::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "capabilities" -> throw IOException("offline")
                "toString" -> "OfflineCapabilitiesApi"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        } as KitWalletApi
    }

    private class FakeChatRepository(
        initiallyReady: Boolean = true,
        initiallyLocalHistoryReady: Boolean = initiallyReady,
        initiallyLocalHistoryOwner: SessionFence? = null,
    ) : ChatRepository {
        override val readiness = MutableStateFlow(initiallyReady)
        override val localHistoryReady = MutableStateFlow(initiallyLocalHistoryReady)
        override val localHistoryOwner = MutableStateFlow(initiallyLocalHistoryOwner)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(emptyList())

        override fun chat(chatId: String): ChatPreview? = null

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(
            chatId: String,
            text: String,
            replyToMessageId: String?,
            onDurablyCommitted: (clientMessageId: String) -> Unit,
        ) = error("Not used")
    }

    private class FakeNetworkSource : KitNetworkSource {
        val changes = MutableSharedFlow<KitNetworkEvent>(extraBufferCapacity = 8)
        var started: Boolean = false
            private set

        override val events: SharedFlow<KitNetworkEvent> = changes

        override fun start() {
            started = true
        }
    }

    private object FakePushMessagingTransport : PushMessagingTransport {
        override val provider = "test-push"
        override val configured = true
        override fun initialize() = Unit
        override suspend fun currentToken() = "test-push-token"
    }

    private companion object {
        fun envelope(
            enabled: Boolean,
            protocolReady: Boolean = enabled,
        ) = ApiEnvelope(
            ok = true,
            data = CapabilitiesDto(
                currency = CurrencyDto(code = "UGX", scale = "2"),
                features = mapOf(KitFeature.MESSAGING to enabled),
                authentication = mapOf("biometric_tokens" to enabled),
                protocols = ProtocolsDto(
                    messaging = MessagingProtocolDto(
                        ready = protocolReady,
                        version = "v2",
                        suite = "signal-pqxdh-kyber1024-double-ratchet-v2",
                        postQuantum = true,
                    ),
                ),
            ),
        )
    }
}
