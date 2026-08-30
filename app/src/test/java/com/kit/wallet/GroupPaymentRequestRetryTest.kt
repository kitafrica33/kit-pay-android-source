package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.repository.GroupPaymentRequestContributionResolution
import com.kit.wallet.data.repository.GroupPaymentRequestRepository
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.feature.chat.GroupPaymentContributionRetryStore
import com.kit.wallet.feature.chat.GroupPaymentRequestCreationRetryStore
import com.kit.wallet.feature.chat.executeGroupPaymentRequestCreation
import com.kit.wallet.feature.chat.executeGroupPaymentRequestContribution
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class GroupPaymentRequestRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var calls: ApiCallExecutor

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
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `validated contribution survives wallet refresh failure and retires retry key`() = runTest {
        val retryKeys = GroupPaymentContributionRetryStore(SavedStateHandle())
        val originalKey = retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, AMOUNT_MINOR)
        enqueueSuccessfulContribution()
        val walletSync = RecordingTestWalletSync(
            onRefresh = { throw IOException("refresh unavailable") },
        )
        val repository = repository(walletSync)

        val resolution = executeGroupPaymentRequestContribution(
            repository,
            retryKeys,
            REQUEST_ID,
            SOURCE_WALLET_ID,
            AMOUNT_MINOR,
            "25.00",
            "1234",
        )

        assertTrue(resolution is GroupPaymentRequestContributionResolution.Confirmed)
        assertEquals(1, walletSync.refreshCalls)
        assertTrue(retryKeys.snapshot().isEmpty())
        assertNotEquals(
            originalKey,
            retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, AMOUNT_MINOR),
        )
        repeat(3) { server.takeRequest() }
        assertEquals(originalKey, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `ambiguous contribution transport failure keeps one key for retry`() = runTest {
        val retryKeys = GroupPaymentContributionRetryStore(SavedStateHandle())
        val originalKey = retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, AMOUNT_MINOR)
        server.enqueue(jsonResponse(OPEN_REQUEST))
        server.enqueue(jsonResponse(STEP_UP_CHALLENGE))
        server.enqueue(jsonResponse(STEP_UP_VERIFICATION))
        server.enqueue(
            jsonResponse(CONTRIBUTION_RESULT)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val failure = runCatching {
            executeGroupPaymentRequestContribution(
                repository(NoOpTestWalletSync),
                retryKeys,
                REQUEST_ID,
                SOURCE_WALLET_ID,
                AMOUNT_MINOR,
                "25.00",
                "1234",
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(originalKey, retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, AMOUNT_MINOR))
        assertEquals(1, retryKeys.snapshot().size)
        repeat(3) { server.takeRequest() }
        assertEquals(originalKey, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `concurrent remainder reduction reconciles old intent and allows corrected amount`() = runTest {
        val retryKeys = GroupPaymentContributionRetryStore(SavedStateHandle())
        val oldKey = retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, AMOUNT_MINOR)
        server.enqueue(jsonResponse(REDUCED_REMAINDER_REQUEST))

        val resolution = executeGroupPaymentRequestContribution(
            repository(NoOpTestWalletSync),
            retryKeys,
            REQUEST_ID,
            SOURCE_WALLET_ID,
            AMOUNT_MINOR,
            "25.00",
            "1234",
        )

        assertTrue(resolution is GroupPaymentRequestContributionResolution.Reconciled)
        assertTrue(retryKeys.snapshot().isEmpty())
        assertNotEquals(oldKey, retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, 1_000L))
        assertEquals(1, server.requestCount)
        assertEquals(
            "/api/kit-wallet/v1/group-payment-requests/$REQUEST_ID",
            server.takeRequest().path,
        )
    }

    @Test
    fun `changed contribution intent cannot erase an ambiguous retry key`() = runTest {
        val retryKeys = GroupPaymentContributionRetryStore(SavedStateHandle())
        val originalKey = retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, AMOUNT_MINOR)
        server.enqueue(jsonResponse(REDUCED_REMAINDER_REQUEST))

        val failure = runCatching {
            executeGroupPaymentRequestContribution(
                repository(NoOpTestWalletSync),
                retryKeys,
                REQUEST_ID,
                SOURCE_WALLET_ID,
                1_000L,
                "10.00",
                "1234",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, server.requestCount)
        assertEquals(originalKey, retryKeys.keyFor(REQUEST_ID, SOURCE_WALLET_ID, AMOUNT_MINOR))
        assertEquals(1, retryKeys.snapshot().size)
    }

    @Test
    fun `ambiguous request creation reuses one key after process restoration`() = runTest {
        val firstState = SavedStateHandle()
        val firstStore = GroupPaymentRequestCreationRetryStore(firstState)
        val originalKey = firstStore.keyFor(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            null,
        )
        server.enqueue(
            jsonResponse(OPEN_REQUEST)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val failure = runCatching {
            executeGroupPaymentRequestCreation(
                repository(NoOpTestWalletSync),
                firstStore,
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                null,
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(
            originalKey,
            firstStore.keyFor(
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                null,
            ),
        )
        val restoredStore = GroupPaymentRequestCreationRetryStore(
            SavedStateHandle(
                mapOf(
                    "pendingGroupPaymentRequestCreation" to
                        ArrayList(checkNotNull(firstStore.snapshot())),
                ),
            ),
        )
        server.enqueue(jsonResponse(OPEN_REQUEST))

        val confirmed = executeGroupPaymentRequestCreation(
            repository(NoOpTestWalletSync),
            restoredStore,
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            null,
        )

        assertEquals(REQUEST_ID, confirmed.id)
        // A validated API response is not enough: a process death before durable chat sharing must
        // still replay the same server request instead of minting a duplicate.
        assertTrue(restoredStore.snapshot() != null)
        val firstRequest = server.takeRequest()
        val replay = server.takeRequest()
        assertEquals(originalKey, firstRequest.getHeader("Idempotency-Key"))
        assertEquals(originalKey, replay.getHeader("Idempotency-Key"))
        restoredStore.complete(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            null,
        )
        assertTrue(restoredStore.snapshot() == null)
    }

    @Test
    fun `unresolved request creation rejects changed amount note or wallet`() {
        val store = GroupPaymentRequestCreationRetryStore(SavedStateHandle())
        val key = store.keyFor(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            "School trip",
        )

        assertTrue(runCatching {
            store.keyFor(
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR + 1,
                REQUEST_CURRENCY_SCALE,
                "School trip",
            )
        }.isFailure)
        assertTrue(runCatching {
            store.keyFor(
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                "Another purpose",
            )
        }.isFailure)
        assertTrue(runCatching {
            store.keyFor(
                CONVERSATION_ID,
                SOURCE_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                "School trip",
            )
        }.isFailure)
        assertEquals(
            key,
            store.keyFor(
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                "School trip",
            ),
        )
    }

    @Test
    fun `changed creation response is rejected without retiring its retry identity`() = runTest {
        val store = GroupPaymentRequestCreationRetryStore(SavedStateHandle())
        val originalKey = store.keyFor(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            null,
        )
        server.enqueue(
            jsonResponse(OPEN_REQUEST.replace(DESTINATION_WALLET_ID, SOURCE_WALLET_ID)),
        )

        val failure = runCatching {
            executeGroupPaymentRequestCreation(
                repository(NoOpTestWalletSync),
                store,
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                null,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            originalKey,
            store.keyFor(
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                null,
            ),
        )
        assertEquals(originalKey, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `definitive creation rejection retires key and permits changed intent`() = runTest {
        val store = GroupPaymentRequestCreationRetryStore(SavedStateHandle())
        val rejectedKey = store.keyFor(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            null,
        )
        server.enqueue(
            MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":{"code":"INVALID_AMOUNT","message":"Rejected"}}"""),
        )

        val failure = runCatching {
            executeGroupPaymentRequestCreation(
                repository(NoOpTestWalletSync),
                store,
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                null,
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(store.snapshot() == null)
        val changedKey = store.keyFor(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR + 1L,
            REQUEST_CURRENCY_SCALE,
            "Changed",
        )
        assertNotEquals(rejectedKey, changedKey)
        assertEquals(rejectedKey, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `server creation failure retains key for exact retry`() = runTest {
        val store = GroupPaymentRequestCreationRetryStore(SavedStateHandle())
        val originalKey = store.keyFor(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            null,
        )
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":{"code":"TEMPORARY","message":"Retry"}}"""),
        )

        assertTrue(runCatching {
            executeGroupPaymentRequestCreation(
                repository(NoOpTestWalletSync),
                store,
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                null,
            )
        }.isFailure)

        assertEquals(
            originalKey,
            store.keyFor(
                CONVERSATION_ID,
                DESTINATION_WALLET_ID,
                REQUEST_AMOUNT_MINOR,
                REQUEST_CURRENCY_SCALE,
                null,
            ),
        )
        assertEquals(originalKey, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `retryable client responses retain creation key`() = runTest {
        val store = GroupPaymentRequestCreationRetryStore(SavedStateHandle())
        val originalKey = store.keyFor(
            CONVERSATION_ID,
            DESTINATION_WALLET_ID,
            REQUEST_AMOUNT_MINOR,
            REQUEST_CURRENCY_SCALE,
            null,
        )
        val responses = listOf(
            401 to "UNAUTHENTICATED",
            403 to "FORBIDDEN",
            409 to "IDEMPOTENCY_REQUEST_IN_PROGRESS",
            408 to "REQUEST_TIMEOUT",
            425 to "TOO_EARLY",
            428 to "STEP_UP_REQUIRED",
            429 to "RATE_LIMITED",
        )

        responses.forEach { (status, code) ->
            server.enqueue(
                MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                    .setBody("""{"ok":false,"error":{"code":"$code","message":"Retry"}}"""),
            )
            assertTrue(runCatching {
                executeGroupPaymentRequestCreation(
                    repository(NoOpTestWalletSync),
                    store,
                    CONVERSATION_ID,
                    DESTINATION_WALLET_ID,
                    REQUEST_AMOUNT_MINOR,
                    REQUEST_CURRENCY_SCALE,
                    null,
                )
            }.isFailure)
            assertEquals(
                originalKey,
                store.keyFor(
                    CONVERSATION_ID,
                    DESTINATION_WALLET_ID,
                    REQUEST_AMOUNT_MINOR,
                    REQUEST_CURRENCY_SCALE,
                    null,
                ),
            )
            assertEquals(originalKey, server.takeRequest().getHeader("Idempotency-Key"))
        }
    }

    private fun repository(walletSync: com.kit.wallet.data.repository.WalletSyncRepository) =
        GroupPaymentRequestRepository(
            api = api,
            apiCalls = calls,
            paymentAuthorizer = PaymentAuthorizer(api, calls),
            walletSync = walletSync,
        )

    private fun enqueueSuccessfulContribution() {
        server.enqueue(jsonResponse(OPEN_REQUEST))
        server.enqueue(jsonResponse(STEP_UP_CHALLENGE))
        server.enqueue(jsonResponse(STEP_UP_VERIFICATION))
        server.enqueue(jsonResponse(CONTRIBUTION_RESULT))
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val REQUEST_ID = "10000000-0000-4000-8000-000000000001"
        const val CONVERSATION_ID = "20000000-0000-4000-8000-000000000002"
        const val REQUESTER_ID = "30000000-0000-4000-8000-000000000003"
        const val DESTINATION_WALLET_ID = "40000000-0000-4000-8000-000000000004"
        const val SOURCE_WALLET_ID = "50000000-0000-4000-8000-000000000005"
        const val CONTRIBUTION_ID = "60000000-0000-4000-8000-000000000006"
        const val TRANSACTION_ID = "70000000-0000-4000-8000-000000000007"
        const val AMOUNT_MINOR = 2_500L
        const val REQUEST_AMOUNT_MINOR = 10_000L
        const val REQUEST_CURRENCY_SCALE = 2
        const val TIMESTAMP = "2099-08-29T12:00:00Z"

        const val OPEN_REQUEST = """
            {"ok":true,"data":{"id":"$REQUEST_ID","type":"group_payment_request",
            "conversation_id":"$CONVERSATION_ID","requester_user_id":"$REQUESTER_ID",
            "status":"open","destination_wallet_id":"$DESTINATION_WALLET_ID",
            "target_amount":"100.00","target_amount_minor":"10000",
            "contributed_amount":"0.00","contributed_amount_minor":"0",
            "remaining_amount":"100.00","remaining_amount_minor":"10000",
            "progress_basis_points":0,"contribution_count":0,"contributor_count":0,
            "your_contributed_amount":"0.00","your_contributed_amount_minor":"0",
            "currency":{"code":"UGX","scale":"2"},"note":null,
            "can_contribute":true,"can_cancel":false,"contributions_has_more":false,
            "contributions_next_before":null,"contributions":[]}}
        """

        const val REDUCED_REMAINDER_REQUEST = """
            {"ok":true,"data":{"id":"$REQUEST_ID","type":"group_payment_request",
            "conversation_id":"$CONVERSATION_ID","requester_user_id":"$REQUESTER_ID",
            "status":"open","destination_wallet_id":"$DESTINATION_WALLET_ID",
            "target_amount":"100.00","target_amount_minor":"10000",
            "contributed_amount":"90.00","contributed_amount_minor":"9000",
            "remaining_amount":"10.00","remaining_amount_minor":"1000",
            "progress_basis_points":9000,"contribution_count":1,"contributor_count":1,
            "your_contributed_amount":"0.00","your_contributed_amount_minor":"0",
            "currency":{"code":"UGX","scale":"2"},"note":null,
            "can_contribute":true,"can_cancel":false,"contributions_has_more":false,
            "contributions_next_before":null,"contributions":[{"id":"$CONTRIBUTION_ID",
            "contributor_user_id":"$REQUESTER_ID","amount":"90.00","amount_minor":"9000",
            "wallet_transaction_id":"$TRANSACTION_ID","created_at":"$TIMESTAMP",
            "is_yours":false}]}}
        """

        const val STEP_UP_CHALLENGE = """
            {"ok":true,"data":{"id":"challenge-id","purpose":"group_payment_request_contribution",
            "intent_hash":"hash","nonce":"nonce","signing_payload":"payload","methods":["pin"],
            "expires_at":"$TIMESTAMP"}}
        """

        const val STEP_UP_VERIFICATION = """
            {"ok":true,"data":{"step_up_token":"step-up-token","expires_at":"$TIMESTAMP",
            "method":"pin"}}
        """

        const val CONTRIBUTION_RESULT = """
            {"ok":true,"data":{"request":{"id":"$REQUEST_ID","type":"group_payment_request",
            "conversation_id":"$CONVERSATION_ID","requester_user_id":"$REQUESTER_ID",
            "status":"open","destination_wallet_id":"$DESTINATION_WALLET_ID",
            "target_amount":"100.00","target_amount_minor":"10000",
            "contributed_amount":"25.00","contributed_amount_minor":"2500",
            "remaining_amount":"75.00","remaining_amount_minor":"7500",
            "progress_basis_points":2500,"contribution_count":1,"contributor_count":1,
            "your_contributed_amount":"25.00","your_contributed_amount_minor":"2500",
            "currency":{"code":"UGX","scale":"2"},"note":null,
            "can_contribute":true,"can_cancel":false,"contributions_has_more":false,
            "contributions_next_before":null,"contributions":[{"id":"$CONTRIBUTION_ID",
            "contributor_user_id":"$REQUESTER_ID","amount":"25.00","amount_minor":"2500",
            "wallet_transaction_id":"$TRANSACTION_ID","created_at":"$TIMESTAMP",
            "is_yours":true}]},"contribution":{"id":"$CONTRIBUTION_ID",
            "contributor_user_id":"$REQUESTER_ID","amount":"25.00","amount_minor":"2500",
            "wallet_transaction_id":"$TRANSACTION_ID","created_at":"$TIMESTAMP",
            "is_yours":true}}}
        """
    }
}
