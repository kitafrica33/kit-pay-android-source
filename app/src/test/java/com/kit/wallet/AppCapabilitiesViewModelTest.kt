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
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.navigation.AppCapabilitiesViewModel
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val viewModel = AppCapabilitiesViewModel(
            api = api.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            chatRepository = FakeChatRepository(),
            pushMessagingTransport = FakePushMessagingTransport,
        )

        assertEquals(1, api.calls)
        assertFalse(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.pushMessagingConfigured)

        // Login must cancel the still-running anonymous request and load the cohort response.
        viewModel.onSessionChanged()

        assertEquals(2, api.calls)
        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.messagingUsable)

        // Even a transport that completes after cancellation cannot overwrite the new session.
        api.completeInitialAnonymousRequest()

        assertTrue(viewModel.state.value.messagingUsable)

        // Logout is also a session transition. It clears personalized readiness synchronously
        // while the replacement anonymous discovery request is still outstanding.
        viewModel.onSessionChanged()

        assertEquals(3, api.calls)
        assertFalse(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.messagingProtocolReady)

        api.completeLogoutAnonymousRequest()

        assertTrue(viewModel.state.value.loaded)
        assertFalse(viewModel.state.value.loadFailed)
        assertFalse(viewModel.state.value.messagingEntryVisible)
        assertFalse(viewModel.state.value.messagingUsable)
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

    private class FakeChatRepository : ChatRepository {
        override val readiness: StateFlow<Boolean> = MutableStateFlow(true)
        override val chats: StateFlow<List<ChatPreview>> = MutableStateFlow(emptyList())

        override fun chat(chatId: String): ChatPreview? = null

        override fun conversation(chatId: String): StateFlow<List<Message>> =
            MutableStateFlow(emptyList())

        override suspend fun openDirectConversation(contact: Contact): String = error("Not used")

        override suspend fun sendMessage(chatId: String, text: String) = error("Not used")
    }

    private object FakePushMessagingTransport : PushMessagingTransport {
        override val provider = "test-push"
        override val configured = true
        override fun initialize() = Unit
        override suspend fun currentToken() = "test-push-token"
    }

    private companion object {
        fun envelope(enabled: Boolean) = ApiEnvelope(
            ok = true,
            data = CapabilitiesDto(
                currency = CurrencyDto(code = "UGX", scale = "2"),
                features = mapOf(KitFeature.MESSAGING to enabled),
                protocols = ProtocolsDto(
                    messaging = MessagingProtocolDto(
                        ready = enabled,
                        version = "v2",
                        suite = "signal-pqxdh-kyber1024-double-ratchet-v2",
                        postQuantum = true,
                    ),
                ),
            ),
        )
    }
}
