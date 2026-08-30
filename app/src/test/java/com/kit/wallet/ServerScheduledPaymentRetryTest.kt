package com.kit.wallet

import androidx.lifecycle.SavedStateHandle
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateScheduledPaymentRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.ScheduledGroupPaymentPlanDto
import com.kit.wallet.data.remote.ScheduledGroupPlanRecipientDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpIntentDto
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.data.repository.ServerScheduledPaymentRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.feature.chat.PendingServerSchedule
import com.kit.wallet.feature.chat.PendingServerSchedulePhase
import com.kit.wallet.feature.chat.PendingServerScheduleStore
import com.kit.wallet.feature.chat.ServerScheduleCreation
import com.kit.wallet.feature.chat.executePendingServerSchedule
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ServerScheduledPaymentRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: ServerScheduledPaymentRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        val calls = ApiCallExecutor(moshi)
        repository = ServerScheduledPaymentRepository(
            api = api,
            apiCalls = calls,
            paymentAuthorizer = PaymentAuthorizer(api, calls),
            walletSync = NoOpTestWalletSync,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `ambiguous direct create restores identical frozen body and key`() = runTest {
        val firstStore = store()
        val prepared = firstStore.stage(directOperation())
        enqueueStepUp("scheduled_payment")
        server.enqueue(ambiguous(DIRECT_CREATED))

        assertTrue(runCatching {
            executePendingServerSchedule(repository, firstStore, prepared, "1234", NOW)
        }.isFailure)
        val submitted = checkNotNull(firstStore.current(CONVERSATION_ID))
        assertEquals(PendingServerSchedulePhase.SUBMITTED, submitted.phase)
        val restoredStore = restored(firstStore)
        val restored = checkNotNull(restoredStore.restore(CONVERSATION_ID, NOW.toEpochMilli()))
        assertEquals(submitted.preview(), restored.preview())

        enqueueStepUp("scheduled_payment")
        server.enqueue(jsonResponse(DIRECT_CREATED))
        val result = executePendingServerSchedule(repository, restoredStore, restored, "1234", NOW)

        assertTrue(result is ServerScheduleCreation.Direct)
        assertNull(restoredStore.current(CONVERSATION_ID))
        assertIdenticalCreateRequests(
            requests = List(6) { server.takeRequest() },
            path = "/api/kit-wallet/v1/payments/scheduled",
            expectedKey = prepared.idempotencyKey,
        )
    }

    @Test
    fun `ambiguous group create restores identical frozen plan body and key`() = runTest {
        val firstStore = store()
        val prepared = firstStore.stage(groupOperation())
        enqueueStepUp("scheduled_group_payment")
        server.enqueue(ambiguous(GROUP_CREATED))

        assertTrue(runCatching {
            executePendingServerSchedule(repository, firstStore, prepared, "1234", NOW)
        }.isFailure)
        val submitted = checkNotNull(firstStore.current(CONVERSATION_ID))
        assertEquals(PendingServerSchedulePhase.SUBMITTED, submitted.phase)
        val restoredStore = restored(firstStore)
        val restored = checkNotNull(restoredStore.restore(CONVERSATION_ID, NOW.toEpochMilli()))
        assertEquals(submitted.preview(), restored.preview())

        enqueueStepUp("scheduled_group_payment")
        server.enqueue(jsonResponse(GROUP_CREATED))
        val result = executePendingServerSchedule(repository, restoredStore, restored, "1234", NOW)

        assertTrue(result is ServerScheduleCreation.Group)
        assertNull(restoredStore.current(CONVERSATION_ID))
        assertIdenticalCreateRequests(
            requests = List(6) { server.takeRequest() },
            path = "/api/kit-wallet/v1/conversations/$CONVERSATION_ID/scheduled-group-payments",
            expectedKey = prepared.idempotencyKey,
        )
    }

    @Test
    fun `expired prepared direct and group approvals fail closed on restoration`() {
        val expiredDirect = directOperation().copy(
            request = directOperation().request.copy(scheduledFor = "2099-08-28T11:59:00Z"),
        )
        val directStore = store().also { it.stage(expiredDirect) }
        val expiredGroup = groupOperation().let { operation ->
            operation.copy(plan = operation.plan.copy(expiresAt = "2099-08-28T11:59:00Z"))
        }
        val groupStore = store().also { it.stage(expiredGroup) }

        assertNull(directStore.restore(CONVERSATION_ID, NOW.toEpochMilli()))
        assertNull(directStore.snapshot())
        assertNull(groupStore.restore(CONVERSATION_ID, NOW.toEpochMilli()))
        assertNull(groupStore.snapshot())
    }

    @Test
    fun `definitive schedule rejection clears submitted operation`() = runTest {
        val store = store()
        val prepared = store.stage(directOperation())
        enqueueStepUp("scheduled_payment")
        server.enqueue(
            MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":{"code":"INVALID_SCHEDULE","message":"Rejected"}}"""),
        )

        assertTrue(runCatching {
            executePendingServerSchedule(repository, store, prepared, "1234", NOW)
        }.isFailure)

        assertNull(store.current(CONVERSATION_ID))
    }

    @Test
    fun `changed successful response retains submitted operation for exact replay`() = runTest {
        val store = store()
        val prepared = store.stage(directOperation())
        enqueueStepUp("scheduled_payment")
        server.enqueue(jsonResponse(DIRECT_CREATED.replace("\"amount\":\"25.00\"", "\"amount\":\"24.00\"")))

        val failure = runCatching {
            executePendingServerSchedule(repository, store, prepared, "1234", NOW)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(PendingServerSchedulePhase.SUBMITTED, store.current(CONVERSATION_ID)?.phase)
        assertEquals(prepared.idempotencyKey, store.current(CONVERSATION_ID)?.idempotencyKey)
    }

    @Test
    fun `expired submitted direct schedule replays its exact frozen request`() = runTest {
        val firstStore = store()
        val prepared = firstStore.stage(directOperation())
        firstStore.markSubmitted(prepared)
        val restoredStore = restored(firstStore)
        val expiredNow = Instant.parse("2099-08-30T12:00:00Z")
        val restored = checkNotNull(
            restoredStore.restore(CONVERSATION_ID, expiredNow.toEpochMilli()),
        )
        enqueueStepUp("scheduled_payment")
        server.enqueue(jsonResponse(DIRECT_CREATED))

        val result = executePendingServerSchedule(
            repository,
            restoredStore,
            restored,
            "1234",
            expiredNow,
        )

        assertTrue(result is ServerScheduleCreation.Direct)
        assertNull(restoredStore.current(CONVERSATION_ID))
        repeat(2) { server.takeRequest() }
        val replay = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/payments/scheduled", replay.path)
        assertEquals(prepared.idempotencyKey, replay.getHeader("Idempotency-Key"))
    }

    @Test
    fun `legacy expiry rejection retains submitted direct retry identity`() = runTest {
        val firstStore = store()
        val prepared = firstStore.stage(directOperation())
        firstStore.markSubmitted(prepared)
        val restoredStore = restored(firstStore)
        val expiredNow = Instant.parse("2099-08-30T12:00:00Z")
        val restored = checkNotNull(restoredStore.restore(CONVERSATION_ID, expiredNow.toEpochMilli()))
        enqueueStepUp("scheduled_payment")
        server.enqueue(
            MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":{"code":"INVALID_SCHEDULE","message":"Expired"}}"""),
        )

        val failure = runCatching {
            executePendingServerSchedule(repository, restoredStore, restored, "1234", expiredNow)
        }.exceptionOrNull()

        assertEquals(
            "This submitted scheduled payment cannot be confirmed yet. Try again later.",
            failure?.message,
        )
        assertEquals(PendingServerSchedulePhase.SUBMITTED,
            restoredStore.current(CONVERSATION_ID)?.phase)
        assertEquals(prepared.idempotencyKey,
            restoredStore.current(CONVERSATION_ID)?.idempotencyKey)
    }

    @Test
    fun `restoration erases a schedule owned by another account`() {
        val firstStore = store()
        firstStore.stage(directOperation())

        val restoredStore = restored(firstStore, OWNER_B)

        assertNull(restoredStore.current(CONVERSATION_ID))
        assertNull(restoredStore.snapshot())
    }

    @Test
    fun `restoration erases a schedule from an older session of the same account`() {
        val firstStore = store()
        firstStore.stage(directOperation())

        val restoredStore = restored(firstStore, OWNER_A_NEW_SESSION)

        assertNull(restoredStore.current(CONVERSATION_ID))
        assertNull(restoredStore.snapshot())
    }

    @Test
    fun `live account replacement erases a pending schedule before exposure`() {
        var owner: SessionFence? = OWNER_A
        val state = SavedStateHandle()
        val store = PendingServerScheduleStore(state) { owner }
        store.stage(directOperation())

        owner = OWNER_B

        assertNull(store.current(CONVERSATION_ID))
        assertNull(store.snapshot())
        assertNull(state.get<ArrayList<String>>("pendingServerScheduleV1"))
    }

    @Test
    fun `ownerless legacy schedule state fails closed`() {
        val firstStore = store()
        firstStore.stage(directOperation())
        val currentEncoding = checkNotNull(firstStore.snapshot())
        val ownerlessV1 = arrayListOf("1").apply { addAll(currentEncoding.drop(5)) }
        val state = SavedStateHandle(
            mapOf("pendingServerScheduleV1" to ownerlessV1),
        )

        val restoredStore = PendingServerScheduleStore(state) { OWNER_A }

        assertNull(restoredStore.current(CONVERSATION_ID))
        assertNull(state.get<ArrayList<String>>("pendingServerScheduleV1"))
    }

    private fun store(owner: SessionFence = OWNER_A): PendingServerScheduleStore =
        PendingServerScheduleStore(SavedStateHandle()) { owner }

    private fun restored(
        source: PendingServerScheduleStore,
        owner: SessionFence = OWNER_A,
    ): PendingServerScheduleStore =
        PendingServerScheduleStore(
            SavedStateHandle(
                mapOf("pendingServerScheduleV1" to ArrayList(checkNotNull(source.snapshot()))),
            ),
        ) { owner }

    private fun directOperation() = PendingServerSchedule.Direct(
        chatId = CONVERSATION_ID,
        idempotencyKey = DIRECT_KEY,
        phase = PendingServerSchedulePhase.PREPARED,
        request = CreateScheduledPaymentRequest(
            sourceWalletId = SOURCE_WALLET_ID,
            destinationWalletId = DESTINATION_WALLET_ID,
            amount = "25.00",
            note = "School trip",
            scheduledFor = SCHEDULED_FOR,
            conversationId = CONVERSATION_ID,
        ),
        currencyCode = "UGX",
        currencyScale = 2,
        amountMinor = 2_500L,
        recipientName = "Peer",
    )

    private fun groupOperation(): PendingServerSchedule.Group {
        val recipient = ScheduledGroupPlanRecipientDto(
            userId = RECIPIENT_USER_ID,
            destinationWalletId = DESTINATION_WALLET_ID,
            amount = "25.00",
        )
        val frozen = "$RECIPIENT_USER_ID:$DESTINATION_WALLET_ID:2500"
        val intent = ScheduledGroupStepUpIntentDto(
            action = "create",
            planId = PLAN_ID,
            planHash = HASH,
            conversationId = CONVERSATION_ID,
            sourceWalletId = SOURCE_WALLET_ID,
            splitMode = "even",
            audience = "selected",
            totalAmount = "25.00",
            currency = "UGX",
            note = "School trip",
            scheduledFor = SCHEDULED_FOR,
            rosterFingerprint = HASH,
            frozenRecipients = frozen,
        )
        return PendingServerSchedule.Group(
            chatId = CONVERSATION_ID,
            idempotencyKey = GROUP_KEY,
            phase = PendingServerSchedulePhase.PREPARED,
            plan = ScheduledGroupPaymentPlanDto(
                planId = PLAN_ID,
                conversationId = CONVERSATION_ID,
                sourceWalletId = SOURCE_WALLET_ID,
                splitMode = "even",
                audience = "selected",
                totalAmount = "25.00",
                currency = CurrencyDto("UGX", "2"),
                note = "School trip",
                recipientCount = 1,
                recipients = listOf(recipient),
                rosterFingerprint = HASH,
                frozenRecipients = frozen,
                planHash = HASH,
                scheduledFor = SCHEDULED_FOR,
                expiresAt = PLAN_EXPIRES_AT,
                stepUp = ScheduledGroupStepUpDto("scheduled_group_payment", intent),
            ),
            amountMinor = 2_500L,
            recipientNames = listOf("Peer"),
        )
    }

    private fun enqueueStepUp(purpose: String) {
        server.enqueue(jsonResponse(STEP_UP_CHALLENGE.replace("PURPOSE", purpose)))
        server.enqueue(jsonResponse(STEP_UP_VERIFICATION))
    }

    private fun assertIdenticalCreateRequests(
        requests: List<RecordedRequest>,
        path: String,
        expectedKey: String,
    ) {
        val creates = requests.filter { it.path == path }
        assertEquals(2, creates.size)
        assertEquals(expectedKey, creates[0].getHeader("Idempotency-Key"))
        assertEquals(expectedKey, creates[1].getHeader("Idempotency-Key"))
        assertEquals(creates[0].body.readUtf8(), creates[1].body.readUtf8())
    }

    private fun ambiguous(body: String) = jsonResponse(body)
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        val NOW: Instant = Instant.parse("2099-08-28T12:00:00Z")
        val OWNER_A = SessionFence("session-a", "cache-a", RECIPIENT_USER_ID)
        val OWNER_B = SessionFence("session-b", "cache-b", CONVERSATION_ID)
        val OWNER_A_NEW_SESSION = SessionFence("session-a-2", "cache-a-2", RECIPIENT_USER_ID)
        const val SCHEDULED_FOR = "2099-08-29T12:00:00Z"
        const val PLAN_EXPIRES_AT = "2099-08-28T13:00:00Z"
        const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000001"
        const val SOURCE_WALLET_ID = "20000000-0000-4000-8000-000000000002"
        const val DESTINATION_WALLET_ID = "30000000-0000-4000-8000-000000000003"
        const val RECIPIENT_USER_ID = "40000000-0000-4000-8000-000000000004"
        const val SCHEDULE_ID = "50000000-0000-4000-8000-000000000005"
        const val PLAN_ID = "60000000-0000-4000-8000-000000000006"
        const val DIRECT_KEY = "server-schedule:70000000-0000-4000-8000-000000000007"
        const val GROUP_KEY = "android-group-payment-80000000-0000-4000-8000-000000000008"
        val HASH = "a".repeat(64)

        const val STEP_UP_CHALLENGE = """
            {"ok":true,"data":{"id":"challenge-id","purpose":"PURPOSE",
            "intent_hash":"hash","nonce":"nonce","signing_payload":"payload",
            "methods":["pin"],"expires_at":"2099-08-29T12:00:00Z"}}
        """
        const val STEP_UP_VERIFICATION = """
            {"ok":true,"data":{"step_up_token":"step-up-token",
            "expires_at":"2099-08-29T12:00:00Z","method":"pin"}}
        """
        const val DIRECT_CREATED = """
            {"ok":true,"data":{"id":"$SCHEDULE_ID","type":"scheduled_payment",
            "status":"scheduled","conversation_id":"$CONVERSATION_ID",
            "source_wallet_id":"$SOURCE_WALLET_ID",
            "destination_wallet_id":"$DESTINATION_WALLET_ID","amount":"25.00",
            "currency":{"code":"UGX","scale":"2"},"note":"School trip",
            "scheduled_for":"$SCHEDULED_FOR","payment_execution_id":null,
            "wallet_transaction_id":null,"failure":null,"completed_at":null,
            "cancelled_at":null,"created_at":"2099-08-28T12:00:00Z"}}
        """
        const val GROUP_CREATED = """
            {"ok":true,"data":{"id":"$SCHEDULE_ID","type":"scheduled_group_payment",
            "conversation_id":"$CONVERSATION_ID","status":"scheduled",
            "source_wallet_id":"$SOURCE_WALLET_ID","split_mode":"even",
            "audience":"selected","total_amount":"25.00",
            "currency":{"code":"UGX","scale":"2"},"note":"School trip",
            "recipient_count":1,"recipients":[{"user_id":"$RECIPIENT_USER_ID",
            "name":"Peer","amount":"25.00"}],"group_payment_id":null,"failure":null,
            "scheduled_for":"$SCHEDULED_FOR","queued_at":null,"started_at":null,
            "completed_at":null,"cancelled_at":null,"created_at":"2099-08-28T12:00:00Z"}}
        """
    }
}
