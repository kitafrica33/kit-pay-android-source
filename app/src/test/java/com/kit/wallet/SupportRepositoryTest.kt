package com.kit.wallet

import com.kit.wallet.data.local.SUPPORT_OUTBOX_STATUS_FAILED
import com.kit.wallet.data.local.SUPPORT_OUTBOX_STATUS_PENDING
import com.kit.wallet.data.local.SupportOutboxDao
import com.kit.wallet.data.local.SupportOutboxEntity
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SupportPaymentBeneficiaryDtoAdapter
import com.kit.wallet.data.remote.SupportProtocolDtoAdapter
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.data.support.SupportDraftOutcome
import com.kit.wallet.data.support.SupportRepository
import com.kit.wallet.data.support.SupportTicketStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The durable-outbox and payment invariants the UI can never repair
 * (docs/support-client.md O1–O5, P1–P3): one immutable client id per draft,
 * replay-safe retries, definitive rejections only from definitive answers,
 * owner scoping, and a payment lane with no expressible destination.
 */
class SupportRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SupportRepository
    private lateinit var sessions: MutableTestSessionStore
    private lateinit var outbox: FakeSupportOutboxDao
    private lateinit var walletSync: RecordingTestWalletSync

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // Mirrors the production Moshi: strict support adapters first.
        val moshi = Moshi.Builder()
            .add(SupportProtocolDtoAdapter())
            .add(SupportPaymentBeneficiaryDtoAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        val apiCalls = ApiCallExecutor(moshi)
        sessions = MutableTestSessionStore(testSession(ACCOUNT))
        outbox = FakeSupportOutboxDao()
        walletSync = RecordingTestWalletSync()
        repository = SupportRepository(
            api = api,
            apiCalls = apiCalls,
            sessions = sessions,
            outboxDao = outbox,
            paymentAuthorizer = PaymentAuthorizer(api, apiCalls),
            walletSync = walletSync,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    // --- Outbox: id lifecycle and retry policy -------------------------------

    @Test
    fun `a flushed message sends its persisted client id and the row is deleted`() = runTest {
        val clientMessageId = repository.enqueueMessage("t-1", "hello support")
        val queued = outbox.rows().single()
        assertEquals(clientMessageId, queued.clientMessageId)
        assertEquals(SUPPORT_OUTBOX_STATUS_PENDING, queued.status)
        assertEquals(OWNER, queued.ownerScopeId)

        server.enqueue(messageResponse(body = "hello support"))
        val outcomes = repository.flushOutbox()

        assertTrue(outcomes[clientMessageId] is SupportDraftOutcome.MessageSent)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kit-wallet/v1/support/tickets/t-1/messages", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"client_message_id\":\"$clientMessageId\""))
        assertTrue(body.contains("\"body\":\"hello support\""))
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun `a retryable failure re-sends the identical draft under the same key`() = runTest {
        val clientMessageId = repository.enqueueMessage("t-1", "please retry me")

        server.enqueue(errorResponse(500, "UPSTREAM_DOWN"))
        assertEquals(
            SupportDraftOutcome.Deferred,
            repository.flushOutbox()[clientMessageId],
        )
        val afterFailure = outbox.rows().single()
        assertEquals(SUPPORT_OUTBOX_STATUS_PENDING, afterFailure.status)
        assertEquals(clientMessageId, afterFailure.clientMessageId)

        server.enqueue(messageResponse(body = "please retry me"))
        assertTrue(
            repository.flushOutbox()[clientMessageId] is SupportDraftOutcome.MessageSent,
        )

        // Byte-identical replay: the server's idempotency fingerprint must match.
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertEquals(first, second)
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun `an idempotent replay acceptance clears the draft like a first success`() = runTest {
        val clientMessageId = repository.enqueueMessage("t-1", "did you get this?")
        server.enqueue(messageResponse(body = "did you get this?", idempotentReplay = true))

        val outcomes = repository.flushOutbox()

        assertTrue(outcomes[clientMessageId] is SupportDraftOutcome.MessageSent)
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun `a definitive rejection marks the draft failed and stops its retries`() = runTest {
        val clientMessageId = repository.enqueueMessage("t-1", "too late")
        server.enqueue(errorResponse(409, "SUPPORT_TICKET_CLOSED"))

        val outcomes = repository.flushOutbox()

        assertEquals(
            SupportDraftOutcome.Rejected("SUPPORT_TICKET_CLOSED"),
            outcomes[clientMessageId],
        )
        val failed = outbox.rows().single()
        assertEquals(SUPPORT_OUTBOX_STATUS_FAILED, failed.status)
        assertEquals("SUPPORT_TICKET_CLOSED", failed.failureCode)

        // Failed rows are display-only: no more attempts, no more requests.
        assertTrue(repository.flushOutbox().isEmpty())
        assertEquals(1, server.requestCount)

        // And the draft flow renders it as failed with the server's code.
        val draft = repository.draftsForTicket("t-1").first().single()
        assertTrue(draft.failed)
        assertEquals("SUPPORT_TICKET_CLOSED", draft.failureCode)
    }

    @Test
    fun `auth boundaries defer the draft instead of failing it`() = runTest {
        val clientMessageId = repository.enqueueMessage("t-1", "still mine")
        server.enqueue(errorResponse(401, "UNAUTHENTICATED"))

        assertEquals(
            SupportDraftOutcome.Deferred,
            repository.flushOutbox()[clientMessageId],
        )
        assertEquals(SUPPORT_OUTBOX_STATUS_PENDING, outbox.rows().single().status)
    }

    @Test
    fun `a connectivity failure stops the pass leaving every draft pending`() = runTest {
        repository.enqueueMessage("t-1", "first")
        repository.enqueueMessage("t-1", "second")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val outcomes = repository.flushOutbox()

        // Only the first row was attempted; the pass broke before the second.
        assertEquals(1, outcomes.size)
        assertEquals(SupportDraftOutcome.Deferred, outcomes.values.single())
        assertEquals(1, server.requestCount)
        assertEquals(2, outbox.rows().size)
        assertTrue(outbox.rows().all { it.status == SUPPORT_OUTBOX_STATUS_PENDING })
    }

    @Test
    fun `drafts are invisible to and untouched by another account's session`() = runTest {
        repository.enqueueMessage("t-1", "account-private text")

        sessions.save(testSession("intruder"))

        assertTrue(repository.flushOutbox().isEmpty())
        assertEquals(0, server.requestCount)
        assertTrue(repository.draftsForTicket("t-1").first().isEmpty())
        // The row survives, still owned by the original scope.
        assertEquals(OWNER, outbox.rows().single().ownerScopeId)
    }

    @Test
    fun `an open-ticket draft posts the full command and reports the ticket`() = runTest {
        val clientMessageId =
            repository.enqueueOpenTicket("payments", "  Card failed  ", "It happened twice.")
        server.enqueue(envelope(ticketJson(status = "open")))

        val outcome = repository.flushOutbox()[clientMessageId]

        val opened = outcome as SupportDraftOutcome.TicketOpened
        assertEquals("t-1", opened.ticket.id)
        val recorded = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/support/tickets", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"category_key\":\"payments\""))
        assertTrue(body.contains("\"subject\":\"Card failed\""))
        assertTrue(body.contains("\"message\":\"It happened twice.\""))
        assertTrue(body.contains("\"client_message_id\":\"$clientMessageId\""))
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun `enqueue validation mirrors the server and persists nothing on refusal`() = runTest {
        expectRefusal { repository.enqueueOpenTicket("", "Valid subject", "body") }
        expectRefusal { repository.enqueueOpenTicket("payments", "ab", "body") }
        expectRefusal { repository.enqueueOpenTicket("payments", "Valid subject", "") }
        expectRefusal { repository.enqueueMessage("t-1", "x".repeat(4001)) }
        assertTrue(outbox.rows().isEmpty())
    }

    @Test
    fun `discarding a ticket's queued drafts keeps its failed ones for display`() = runTest {
        repository.enqueueMessage("t-1", "queued one")
        repository.enqueueMessage("t-2", "other ticket")
        val failedId = repository.enqueueMessage("t-1", "already rejected")
        outbox.markFailed(OWNER, failedId, "SUPPORT_TICKET_CLOSED")

        repository.discardDraftsForTicket("t-1")

        val remaining = outbox.rows()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.ticketId == "t-2" })
        assertTrue(remaining.any { it.clientMessageId == failedId })

        repository.discardDraft(failedId)
        assertEquals(1, outbox.rows().size)
    }

    // --- Company-beneficiary payment ------------------------------------------

    @Test
    fun `a payment steps up on the exact intent and carries both proof headers`() = runTest {
        enqueuePaymentHandshake()
        server.enqueue(envelope(paymentJson(), meta = """{"idempotent_replay": false}"""))
        val key = repository.mintPaymentIdempotencyKey()

        val receipt = repository.payTicket(
            ticketId = "t-1",
            sourceWalletId = "w-1",
            amount = "25.00",
            note = "Thanks",
            paymentPin = "1234",
            idempotencyKey = key,
        )

        val challenge = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/step-up/challenges", challenge.path)
        val challengeBody = challenge.body.readUtf8()
        assertTrue(challengeBody.contains("\"purpose\":\"support_payment\""))
        assertTrue(challengeBody.contains("\"ticket_id\":\"t-1\""))
        assertTrue(challengeBody.contains("\"source_wallet_id\":\"w-1\""))
        assertTrue(challengeBody.contains("\"amount\":\"25.00\""))
        assertTrue(challengeBody.contains("\"note\":\"Thanks\""))
        assertFalse(challengeBody.contains("destination"))

        val verify = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/auth/step-up/challenges/ch-1/verify", verify.path)
        assertTrue(verify.body.readUtf8().contains("\"pin\":\"1234\""))

        val payment = server.takeRequest()
        assertEquals("/api/kit-wallet/v1/support/tickets/t-1/payments", payment.path)
        assertEquals(key, payment.getHeader("Idempotency-Key"))
        assertEquals("step-token-1", payment.getHeader("X-Kit-Wallet-Step-Up"))
        val paymentBody = payment.body.readUtf8()
        assertTrue(paymentBody.contains("\"source_wallet_id\":\"w-1\""))
        assertTrue(paymentBody.contains("\"amount\":\"25.00\""))
        assertTrue(paymentBody.contains("\"note\":\"Thanks\""))
        assertFalse(paymentBody.contains("destination"))

        assertEquals("tx-1", receipt.transactionId)
        assertEquals("25.00", receipt.amount)
        assertEquals("NGN", receipt.currencyCode)
        assertEquals("Kit Pay", receipt.beneficiaryName)
        assertEquals("tp-1", receipt.ticketPaymentId)
        assertFalse(receipt.idempotentReplay)
        // Money moved: the next balance shown must be the server's.
        assertEquals(1, walletSync.refreshCalls)
    }

    @Test
    fun `a replayed payment reports the original charge instead of a new one`() = runTest {
        enqueuePaymentHandshake()
        server.enqueue(envelope(paymentJson(), meta = """{"idempotent_replay": true}"""))

        val receipt = repository.payTicket(
            ticketId = "t-1",
            sourceWalletId = "w-1",
            amount = "25.00",
            note = null,
            paymentPin = "1234",
            idempotencyKey = repository.mintPaymentIdempotencyKey(),
        )

        assertTrue(receipt.idempotentReplay)
    }

    @Test
    fun `a payment refuses a foreign idempotency key before any request`() = runTest {
        expectRefusal {
            repository.payTicket(
                ticketId = "t-1",
                sourceWalletId = "w-1",
                amount = "25.00",
                note = null,
                paymentPin = "1234",
                idempotencyKey = "android-payment-recycled",
            )
        }
        // An over-long note is also refused before step-up ever starts.
        expectRefusal {
            repository.payTicket(
                ticketId = "t-1",
                sourceWalletId = "w-1",
                amount = "25.00",
                note = "y".repeat(281),
                paymentPin = "1234",
                idempotencyKey = repository.mintPaymentIdempotencyKey(),
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `minted payment keys are unique and carry the support prefix`() {
        val first = repository.mintPaymentIdempotencyKey()
        val second = repository.mintPaymentIdempotencyKey()

        assertTrue(first.startsWith("android-support-payment-"))
        assertTrue(second.startsWith("android-support-payment-"))
        assertFalse(first == second)
    }

    // --- Server-authoritative reads -------------------------------------------

    @Test
    fun `ticket pages echo the server cursor verbatim`() = runTest {
        server.enqueue(
            envelope(
                """{"items": [${ticketJson(status = "open")}]}""",
                meta = """{"next_cursor": "cur-2", "has_more": true}""",
            ),
        )

        val page = repository.tickets()

        assertEquals("/api/kit-wallet/v1/support/tickets?limit=50", server.takeRequest().path)
        assertEquals(1, page.tickets.size)
        assertEquals("cur-2", page.nextCursor)
        assertTrue(page.hasMore)

        server.enqueue(envelope("""{"items": []}"""))
        repository.tickets(cursor = "cur-2")
        assertTrue(server.takeRequest().path!!.contains("cursor=cur-2"))
    }

    @Test
    fun `the message poll returns the refreshed ticket with the new page`() = runTest {
        server.enqueue(
            envelope(
                """{"items": [${messageJson("m-9", position = 8)}],
                    "ticket": ${ticketJson(status = "closed")}}""",
            ),
        )

        val poll = repository.messagesAfter("t-1", afterPosition = 7)

        val path = server.takeRequest().path!!
        assertTrue(path.contains("after_position=7"))
        assertTrue(path.contains("limit=100"))
        assertEquals(1, poll.messages.size)
        // The poll doubles as the status feed: closure arrives with the page.
        assertEquals(SupportTicketStatus.CLOSED, poll.ticket.status)
        assertFalse(poll.ticket.acceptsWrites)
    }

    @Test
    fun `close posts to the close endpoint and returns the server's ticket`() = runTest {
        server.enqueue(envelope(ticketJson(status = "closed")))

        val closed = repository.closeTicket("t-1")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kit-wallet/v1/support/tickets/t-1/close", recorded.path)
        assertEquals(SupportTicketStatus.CLOSED, closed.status)
    }

    @Test
    fun `every unavailable agent-photo state is null, never an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(repository.agentAvatar("t-1"))

        val bytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        assertArrayEquals(bytes, repository.agentAvatar("t-1"))
    }

    // --- Fixtures ---------------------------------------------------------------

    private suspend fun expectRefusal(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The refusal itself is the assertion.
        }
    }

    private fun enqueuePaymentHandshake() {
        server.enqueue(
            envelope(
                """
                {
                  "id": "ch-1",
                  "purpose": "support_payment",
                  "intent_hash": "hash-1",
                  "nonce": "nonce-1",
                  "signing_payload": "payload-1",
                  "methods": ["pin"],
                  "expires_at": "2026-08-28T10:05:00Z"
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            envelope(
                """{"step_up_token": "step-token-1",
                    "expires_at": "2026-08-28T10:05:00Z", "method": "pin"}""",
            ),
        )
    }

    private fun envelope(data: String, meta: String? = null) = MockResponse().setBody(
        buildString {
            append("""{"ok": true, "data": $data""")
            if (meta != null) append(""", "meta": $meta""")
            append("}")
        },
    )

    private fun errorResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setBody("""{"ok": false, "error": {"code": "$code", "message": "refused"}}""")

    private fun messageResponse(body: String, idempotentReplay: Boolean = false) = envelope(
        messageJson("m-1", position = 4, body = body),
        meta = if (idempotentReplay) """{"idempotent_replay": true}""" else null,
    )

    private fun messageJson(id: String, position: Long, body: String = "hello") = """
        {
          "id": "$id",
          "position": $position,
          "sender": {"type": "customer", "display_name": "You", "official": false,
                      "automated": false, "verification": null},
          "body": "$body",
          "attachment": null,
          "created_at": "2026-08-28T10:00:00Z"
        }
    """.trimIndent()

    private fun ticketJson(status: String) = """
        {
          "id": "t-1",
          "reference": "SUP-2026-000123",
          "subject": "Card failed",
          "status": "$status",
          "category": {"key": "payments", "name": "Payments"},
          "support_identity": {"display_name": "Kit Pay Support", "official": true,
                                "verification": {"designation": "official_support"}},
          "agent": null,
          "assistant_active": true,
          "message_count": 1,
          "created_at": "2026-08-28T09:00:00Z",
          "last_message_at": null,
          "closed": null,
          "end_to_end_encrypted": false,
          "content_visibility": "server_readable"
        }
    """.trimIndent()

    private fun paymentJson() = """
        {
          "transaction": {"id": "tx-1", "reference": "TXN-9", "amount": "25.00",
                           "currency": "NGN", "status": "completed",
                           "occurred_at": "2026-08-28T10:02:00Z"},
          "beneficiary": {"kind": "company", "display_name": "Kit Pay"},
          "ticket_payment_id": "tp-1"
        }
    """.trimIndent()

    private companion object {
        const val ACCOUNT = "acct"
        const val OWNER = "scope-$ACCOUNT"
    }
}

/**
 * In-memory stand-in for the Room outbox with the same contract: composite
 * (owner, clientMessageId) identity with INSERT ABORT, status-filtered pending
 * listing in creation order, and owner-scoped observation.
 */
private class FakeSupportOutboxDao : SupportOutboxDao {
    private val state = MutableStateFlow<List<SupportOutboxEntity>>(emptyList())

    fun rows(): List<SupportOutboxEntity> = state.value

    override fun observeForOwner(ownerScopeId: String): Flow<List<SupportOutboxEntity>> =
        state.map { rows ->
            rows.filter { it.ownerScopeId == ownerScopeId }.inCreationOrder()
        }

    override suspend fun listForOwner(
        ownerScopeId: String,
        status: String,
    ): List<SupportOutboxEntity> =
        state.value
            .filter { it.ownerScopeId == ownerScopeId && it.status == status }
            .inCreationOrder()

    override suspend fun enqueue(row: SupportOutboxEntity) {
        check(
            state.value.none {
                it.ownerScopeId == row.ownerScopeId &&
                    it.clientMessageId == row.clientMessageId
            },
        ) { "Duplicate outbox key — enqueued drafts are immutable" }
        state.value = state.value + row
    }

    override suspend fun markAttempted(
        ownerScopeId: String,
        clientMessageId: String,
        attemptedAtEpochMillis: Long,
    ) = update(ownerScopeId, clientMessageId) {
        it.copy(lastAttemptAtEpochMillis = attemptedAtEpochMillis)
    }

    override suspend fun markFailed(
        ownerScopeId: String,
        clientMessageId: String,
        failureCode: String?,
    ) = update(ownerScopeId, clientMessageId) {
        it.copy(status = SUPPORT_OUTBOX_STATUS_FAILED, failureCode = failureCode)
    }

    override suspend fun delete(ownerScopeId: String, clientMessageId: String) {
        state.value = state.value.filterNot {
            it.ownerScopeId == ownerScopeId && it.clientMessageId == clientMessageId
        }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }

    private fun update(
        ownerScopeId: String,
        clientMessageId: String,
        transform: (SupportOutboxEntity) -> SupportOutboxEntity,
    ) {
        state.value = state.value.map {
            if (it.ownerScopeId == ownerScopeId && it.clientMessageId == clientMessageId) {
                transform(it)
            } else {
                it
            }
        }
    }

    private fun List<SupportOutboxEntity>.inCreationOrder(): List<SupportOutboxEntity> =
        sortedWith(compareBy({ it.createdAtEpochMillis }, { it.clientMessageId }))
}
