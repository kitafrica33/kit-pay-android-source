package com.kit.wallet

import com.kit.wallet.data.messaging.ImmediateSendIntentStore
import com.kit.wallet.data.messaging.ImmediateSendState
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.PendingFinancialEventCoordinator
import com.kit.wallet.data.messaging.PendingFinancialEventRecoveryOutcome
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.session.SessionInvalidatedException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
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

class PendingFinancialEventRecoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var calls: ApiCallExecutor
    private lateinit var disk: TestSecureMessagingStateStore
    private lateinit var sessions: MutableTestSessionStore
    private lateinit var store: ImmediateSendIntentStore
    private lateinit var coordinator: PendingFinancialEventCoordinator

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        calls = ApiCallExecutor(moshi)
        disk = TestSecureMessagingStateStore()
        sessions = MutableTestSessionStore(testSession(OWNER_A))
        store = ImmediateSendIntentStore(disk, sessions)
        coordinator = PendingFinancialEventCoordinator(
            store,
            sessions,
            api,
            calls,
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `staged event stays hidden from dispatch state until exact GET confirms it`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val event = paymentEvent(KitPaymentAction.CANCELLED)
        val id = coordinator.stagePaymentEvent(owner, CONVERSATION_ID, event)

        assertEquals(ImmediateSendState.FINANCIAL_PENDING, store.findForOwner(owner, id)?.state)
        server.enqueue(paymentRequest("cancelled"))
        assertEquals(PendingFinancialEventRecoveryOutcome.RETRY, coordinator.recover())
        assertEquals(0, server.requestCount)
        assertEquals(ImmediateSendState.FINANCIAL_PENDING, store.findForOwner(owner, id)?.state)

        val restartedStore = ImmediateSendIntentStore(disk, sessions)
        val restarted = PendingFinancialEventCoordinator(
            restartedStore,
            sessions,
            api,
            calls,
            Clock.fixed(Instant.parse("2026-09-01T12:01:00Z"), ZoneOffset.UTC),
        )

        assertEquals(PendingFinancialEventRecoveryOutcome.COMMITTED, restarted.recover())
        assertEquals(ImmediateSendState.WAITING, restartedStore.findForOwner(owner, id)?.state)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/kit-wallet/v1/payments/requests/$REQUEST_ID", request.path)
    }

    @Test
    fun `pending authoritative state is retained and terminal contradiction is removed`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val event = paymentEvent(KitPaymentAction.PAID)
        val id = coordinator.stagePaymentEvent(owner, CONVERSATION_ID, event)
        coordinator.releaseForRecovery(owner, id)
        server.enqueue(paymentRequest("pending"))

        assertEquals(PendingFinancialEventRecoveryOutcome.RETRY, coordinator.recover())
        assertEquals(ImmediateSendState.FINANCIAL_PENDING, store.findForOwner(owner, id)?.state)

        server.enqueue(paymentRequest("cancelled"))
        assertEquals(PendingFinancialEventRecoveryOutcome.COMMITTED, coordinator.recover())
        assertNull(store.findForOwner(owner, id))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `opaque not found never authorizes a staged event and remains recoverable`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val id = coordinator.stagePaymentEvent(
            owner,
            CONVERSATION_ID,
            paymentEvent(KitPaymentAction.CANCELLED),
        )
        coordinator.releaseForRecovery(owner, id)
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":{"code":"PAYMENT_REQUEST_NOT_FOUND","message":"Not found"}}""",
                ),
        )

        assertEquals(PendingFinancialEventRecoveryOutcome.RETRY, coordinator.recover())
        assertEquals(ImmediateSendState.FINANCIAL_PENDING, store.findForOwner(owner, id)?.state)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `commit reports false when the exact staged row has vanished`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        val id = coordinator.stagePaymentEvent(
            owner,
            CONVERSATION_ID,
            paymentEvent(KitPaymentAction.CANCELLED),
        )
        store.removeForOwner(owner, id)

        assertFalse(coordinator.commit(owner, id))
        assertEquals(PendingFinancialEventRecoveryOutcome.IDLE, coordinator.recover())
    }

    @Test
    fun `account replacement rejects an obsolete staged event writer`() = runTest {
        val owner = checkNotNull(sessions.current()).fence()
        coordinator.stagePaymentEvent(
            owner,
            CONVERSATION_ID,
            paymentEvent(KitPaymentAction.CANCELLED),
        )
        disk.eraseAll()
        sessions.save(testSession(OWNER_B))

        assertTrue(
            runCatching {
                coordinator.stagePaymentEvent(
                    owner,
                    CONVERSATION_ID,
                    paymentEvent(KitPaymentAction.PAID),
                )
            }.exceptionOrNull() is SessionInvalidatedException,
        )
        assertTrue(store.items.value.isEmpty())
    }

    private fun paymentEvent(action: KitPaymentAction) = KitPaymentMessage(
        action = action,
        referenceId = REQUEST_ID,
        amountMinor = 2_500,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Lunch",
    )

    private fun paymentRequest(status: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "ok":true,
              "data":{
                "id":"$REQUEST_ID",
                "type":"payment_request",
                "status":"$status",
                "destination_wallet_id":"$WALLET_ID",
                "requested_from_user_id":"$OWNER_A",
                "amount":"25.00",
                "currency":{"code":"UGX","scale":"2"},
                "note":"Lunch",
                "wallet_transaction_id":${if (status == "paid") "\"$TRANSACTION_ID\"" else "null"}
              }
            }
            """.trimIndent(),
        )

    private companion object {
        const val OWNER_A = "10000000-0000-4000-8000-000000000001"
        const val OWNER_B = "10000000-0000-4000-8000-000000000002"
        const val CONVERSATION_ID = "20000000-0000-4000-8000-000000000001"
        const val REQUEST_ID = "30000000-0000-4000-8000-000000000001"
        const val WALLET_ID = "40000000-0000-4000-8000-000000000001"
        const val TRANSACTION_ID = "50000000-0000-4000-8000-000000000001"
    }
}
