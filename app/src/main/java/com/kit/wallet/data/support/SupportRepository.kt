package com.kit.wallet.data.support

import com.kit.wallet.data.local.SUPPORT_OUTBOX_KIND_MESSAGE
import com.kit.wallet.data.local.SUPPORT_OUTBOX_KIND_OPEN_TICKET
import com.kit.wallet.data.local.SUPPORT_OUTBOX_STATUS_PENDING
import com.kit.wallet.data.local.SupportOutboxDao
import com.kit.wallet.data.local.SupportOutboxEntity
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateSupportPaymentRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.OpenSupportTicketRequest
import com.kit.wallet.data.remote.SendSupportMessageRequest
import com.kit.wallet.data.repository.PaymentAuthorizer
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local failure code for an outbox row this build cannot form a request from. */
private const val SUPPORT_DRAFT_INVALID_CODE = "SUPPORT_DRAFT_INVALID"

/**
 * Whether a failed support write must never be retried with the same content.
 *
 * Only a definitive endpoint answer counts: the server received this exact
 * request and rejected it as a matter of validation or state, so re-sending the
 * identical bytes can only fail identically. Everything else — connectivity,
 * auth boundaries (401/403), timeouts (408), 425, the assurance step (428),
 * throttling (429), and every 5xx — keeps the draft pending under the SAME
 * idempotency key, because the server may have committed the write and only the
 * replay-safe retry can find out (docs/support-client.md O3/O4).
 */
internal fun isDefinitiveSupportRejection(error: KitWalletApiException): Boolean {
    if (error.connectivity) return false
    val status = error.statusCode ?: return false
    return status in 400..499 && status !in setOf(401, 403, 408, 425, 428, 429)
}

/**
 * Authenticated support client: server-authoritative reads, a durable
 * owner-scoped outbox for the two idempotent writes (open ticket, send
 * message), and the company-beneficiary payment lane.
 *
 * The repository trusts its callers for capability gating (the ViewModels only
 * exist behind `supportUsable`); it owns the idempotency and session-fencing
 * invariants that must hold no matter what the UI does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SupportRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    private val outboxDao: SupportOutboxDao,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val walletSync: WalletSyncRepository,
) {
    /** One flush at a time: parallel flushes would race the same rows. */
    private val flushMutex = Mutex()

    // --- Server-authoritative reads -----------------------------------------

    suspend fun categories(): List<SupportCategory> {
        val fence = requireSession().fence()
        return apiCalls.execute { api.supportCategories(fence) }.items.map { it.toDomain() }
    }

    /**
     * One page of the account's tickets, newest-first. [cursor] must be a
     * `nextCursor` received verbatim from a prior page, or null for the first.
     */
    suspend fun tickets(
        status: String? = null,
        cursor: String? = null,
    ): SupportTicketPage {
        val fence = requireSession().fence()
        val result = apiCalls.executeWithMeta {
            api.supportTickets(status, TICKET_PAGE_LIMIT, cursor, fence)
        }
        return SupportTicketPage(
            tickets = result.data.items.map { it.toDomain() },
            nextCursor = result.meta?.nextCursor,
            hasMore = result.meta?.hasMore == true,
        )
    }

    suspend fun ticketDetail(ticketId: String): SupportTicketDetail {
        val fence = requireSession().fence()
        val detail = apiCalls.execute {
            api.supportTicket(ticketId, DETAIL_MESSAGE_LIMIT, fence)
        }
        return SupportTicketDetail(
            ticket = detail.ticket.toDomain(),
            messages = detail.messages.map { it.toDomain() },
            messagesHasMore = detail.messagesHasMore,
            messagesNextAfterPosition = detail.messagesNextAfterPosition,
        )
    }

    /**
     * Messages strictly after [afterPosition] plus the refreshed ticket the
     * server returns with every page — the poll doubles as the status feed
     * (agent assignment, assistant handoff, closure).
     */
    suspend fun messagesAfter(ticketId: String, afterPosition: Long): SupportMessagesPoll {
        val fence = requireSession().fence()
        val page = apiCalls.execute {
            api.supportTicketMessages(ticketId, afterPosition, MESSAGE_PAGE_LIMIT, fence)
        }
        return SupportMessagesPoll(
            ticket = page.ticket.toDomain(),
            messages = page.items.map { it.toDomain() },
        )
    }

    suspend fun closeTicket(ticketId: String): SupportTicket {
        val fence = requireSession().fence()
        return apiCalls.execute { api.closeSupportTicket(ticketId, fence) }.toDomain()
    }

    suspend fun escalateTicket(ticketId: String): SupportTicket {
        val fence = requireSession().fence()
        return apiCalls.execute { api.escalateSupportTicket(ticketId, fence) }.toDomain()
    }

    /**
     * The assigned agent's ticket-scoped photo, or null for every unavailable
     * state (no agent, no photo, offline, error). The response is marked
     * `no-store`; callers decode in memory and must never persist the bytes.
     */
    suspend fun agentAvatar(ticketId: String): ByteArray? {
        val session = sessions.current() ?: return null
        return try {
            val response = api.supportAgentAvatar(ticketId, session.fence())
            if (response.isSuccessful) response.body()?.use { it.bytes() } else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    // --- Durable outbox ------------------------------------------------------

    /** Queued and failed drafts addressed to [ticketId], oldest first. */
    fun draftsForTicket(ticketId: String): Flow<List<SupportDraft>> =
        ownerDrafts().map { drafts ->
            drafts.filter { it.kind == SUPPORT_OUTBOX_KIND_MESSAGE && it.ticketId == ticketId }
        }

    /** Queued and failed open-ticket drafts (tickets that do not exist server-side yet). */
    fun openTicketDrafts(): Flow<List<SupportDraft>> =
        ownerDrafts().map { drafts ->
            drafts.filter { it.kind == SUPPORT_OUTBOX_KIND_OPEN_TICKET }
        }

    private fun ownerDrafts(): Flow<List<SupportDraft>> =
        sessions.session
            .map { it?.cacheScopeId }
            .distinctUntilChanged()
            .flatMapLatest { owner ->
                if (owner == null) {
                    flowOf(emptyList())
                } else {
                    outboxDao.observeForOwner(owner).map { rows -> rows.map { it.toDraft() } }
                }
            }

    /**
     * Durably queues an open-ticket command and returns its client message id.
     * Validation mirrors the server exactly and never widens it (T1).
     */
    suspend fun enqueueOpenTicket(categoryKey: String, subject: String, body: String): String {
        val session = requireSession()
        val trimmedSubject = subject.trim()
        require(categoryKey.isNotBlank() && categoryKey.length <= 48) {
            "Choose a support category"
        }
        require(trimmedSubject.length in 3..140) {
            "The subject must be between 3 and 140 characters"
        }
        require(body.isNotEmpty() && body.length <= 4000) {
            "The message must be between 1 and 4000 characters"
        }
        return enqueue(
            session.cacheScopeId,
            kind = SUPPORT_OUTBOX_KIND_OPEN_TICKET,
            ticketId = null,
            categoryKey = categoryKey,
            subject = trimmedSubject,
            body = body,
        )
    }

    /** Durably queues a message to an existing ticket and returns its client message id. */
    suspend fun enqueueMessage(ticketId: String, body: String): String {
        val session = requireSession()
        require(ticketId.isNotBlank()) { "The ticket no longer exists" }
        require(body.isNotEmpty() && body.length <= 4000) {
            "The message must be between 1 and 4000 characters"
        }
        return enqueue(
            session.cacheScopeId,
            kind = SUPPORT_OUTBOX_KIND_MESSAGE,
            ticketId = ticketId,
            categoryKey = null,
            subject = null,
            body = body,
        )
    }

    private suspend fun enqueue(
        ownerScopeId: String,
        kind: String,
        ticketId: String?,
        categoryKey: String?,
        subject: String?,
        body: String,
    ): String {
        // Minted once, persisted before the first network attempt, immutable for
        // the row's whole life: this is the idempotency fingerprint the server
        // binds, so replays after a lost response are recognized (O1/O2).
        val clientMessageId = UUID.randomUUID().toString()
        outboxDao.enqueue(
            SupportOutboxEntity(
                ownerScopeId = ownerScopeId,
                clientMessageId = clientMessageId,
                kind = kind,
                ticketId = ticketId,
                categoryKey = categoryKey,
                subject = subject,
                body = body,
                status = SUPPORT_OUTBOX_STATUS_PENDING,
                failureCode = null,
                createdAtEpochMillis = System.currentTimeMillis(),
                lastAttemptAtEpochMillis = null,
            ),
        )
        return clientMessageId
    }

    /** Drops one draft. Only the user's explicit choice reaches this (O5/T3). */
    suspend fun discardDraft(clientMessageId: String) {
        val session = sessions.current() ?: return
        outboxDao.delete(session.cacheScopeId, clientMessageId)
    }

    /** Drops every queued draft for [ticketId] — the "discard and close" branch of T3. */
    suspend fun discardDraftsForTicket(ticketId: String) {
        val session = sessions.current() ?: return
        val owner = session.cacheScopeId
        outboxDao.listForOwner(owner, SUPPORT_OUTBOX_STATUS_PENDING)
            .filter { it.kind == SUPPORT_OUTBOX_KIND_MESSAGE && it.ticketId == ticketId }
            .forEach { outboxDao.delete(owner, it.clientMessageId) }
    }

    /**
     * Attempts every pending draft in creation order and reports per-draft
     * outcomes keyed by client message id.
     *
     * Retry policy: a success or idempotent replay deletes the row; a
     * definitive endpoint rejection (see [isDefinitiveSupportRejection]) marks
     * it failed and moves on; anything else — connectivity, a session change,
     * a retryable server state — stops the pass with every remaining row
     * untouched and still pending under its original key.
     */
    suspend fun flushOutbox(): Map<String, SupportDraftOutcome> = flushMutex.withLock {
        val session = sessions.current() ?: return@withLock emptyMap()
        val owner = session.cacheScopeId
        val fence = session.fence()
        val outcomes = linkedMapOf<String, SupportDraftOutcome>()
        for (row in outboxDao.listForOwner(owner, SUPPORT_OUTBOX_STATUS_PENDING)) {
            // The fence tag aborts a crossed-session send in flight; checking here
            // just avoids starting attempts that can only abort.
            if (sessions.current()?.cacheScopeId != owner) break
            outboxDao.markAttempted(owner, row.clientMessageId, System.currentTimeMillis())
            val outcome = attempt(row, fence)
            outcomes[row.clientMessageId] = outcome
            if (outcome == SupportDraftOutcome.Deferred) break
        }
        outcomes
    }

    private suspend fun attempt(
        row: SupportOutboxEntity,
        fence: SessionFence,
    ): SupportDraftOutcome = try {
        when (row.kind) {
            SUPPORT_OUTBOX_KIND_OPEN_TICKET -> {
                val categoryKey = row.categoryKey
                val subject = row.subject
                if (categoryKey.isNullOrBlank() || subject.isNullOrBlank()) {
                    outboxDao.markFailed(
                        row.ownerScopeId, row.clientMessageId, SUPPORT_DRAFT_INVALID_CODE,
                    )
                    SupportDraftOutcome.Rejected(SUPPORT_DRAFT_INVALID_CODE)
                } else {
                    val ticket = apiCalls.execute {
                        api.openSupportTicket(
                            OpenSupportTicketRequest(
                                categoryKey = categoryKey,
                                subject = subject,
                                message = row.body,
                                clientMessageId = row.clientMessageId,
                            ),
                            fence,
                        )
                    }.toDomain()
                    outboxDao.delete(row.ownerScopeId, row.clientMessageId)
                    SupportDraftOutcome.TicketOpened(ticket)
                }
            }
            SUPPORT_OUTBOX_KIND_MESSAGE -> {
                val ticketId = row.ticketId
                if (ticketId.isNullOrBlank()) {
                    outboxDao.markFailed(
                        row.ownerScopeId, row.clientMessageId, SUPPORT_DRAFT_INVALID_CODE,
                    )
                    SupportDraftOutcome.Rejected(SUPPORT_DRAFT_INVALID_CODE)
                } else {
                    val message = apiCalls.execute {
                        api.sendSupportMessage(
                            ticketId,
                            SendSupportMessageRequest(
                                body = row.body,
                                clientMessageId = row.clientMessageId,
                            ),
                            fence,
                        )
                    }.toDomain()
                    outboxDao.delete(row.ownerScopeId, row.clientMessageId)
                    SupportDraftOutcome.MessageSent(message)
                }
            }
            // A kind this build does not know (row written by a newer build):
            // leave it exactly as it is for the build that understands it.
            else -> SupportDraftOutcome.Deferred
        }
    } catch (_: SessionInvalidatedException) {
        // The signed-in account changed under the flush. Nothing sent; the
        // owner-scoped row stays pending for its own session (S1).
        SupportDraftOutcome.Deferred
    } catch (error: KitWalletApiException) {
        if (isDefinitiveSupportRejection(error)) {
            outboxDao.markFailed(row.ownerScopeId, row.clientMessageId, error.code)
            SupportDraftOutcome.Rejected(error.code)
        } else {
            SupportDraftOutcome.Deferred
        }
    }

    // --- Company-beneficiary payment -----------------------------------------

    /** Mints the idempotency key for one reviewed payment confirmation (P2). */
    fun mintPaymentIdempotencyKey(): String = "android-support-payment-${UUID.randomUUID()}"

    /**
     * Pays the reviewed amount into the ticket's company beneficiary.
     *
     * [idempotencyKey] must come from [mintPaymentIdempotencyKey] at the moment
     * the confirmation sheet was shown, and must be reused verbatim for every
     * retry of that same confirmation — it may rotate only when the reviewed
     * intent changes or after a definitive rejection. Step-up runs against the
     * exact intent the server will execute; there is no destination anywhere
     * because the contract forbids expressing one (P1).
     */
    suspend fun payTicket(
        ticketId: String,
        sourceWalletId: String,
        amount: String,
        note: String?,
        paymentPin: String,
        idempotencyKey: String,
    ): SupportPaymentReceipt {
        val session = requireSession()
        require(idempotencyKey.startsWith("android-support-payment-")) {
            "Payment confirmation is stale — review the payment again"
        }
        val normalizedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        require((normalizedNote?.length ?: 0) <= 280) {
            "The note must be at most 280 characters"
        }
        val intent = linkedMapOf<String, Any?>(
            "ticket_id" to ticketId,
            "source_wallet_id" to sourceWalletId,
            "amount" to amount,
            "note" to normalizedNote,
        )
        val stepUpToken = paymentAuthorizer.authorize(
            purpose = "support_payment",
            intent = intent,
            paymentPin = paymentPin,
        )
        val result = apiCalls.executeWithMeta {
            api.createSupportPayment(
                ticketId = ticketId,
                idempotencyKey = idempotencyKey,
                stepUpToken = stepUpToken,
                request = CreateSupportPaymentRequest(
                    sourceWalletId = sourceWalletId,
                    amount = amount,
                    note = normalizedNote,
                ),
                expectedOwner = session.fence(),
            )
        }
        // Money moved; the balance shown next must be the server's. A failed
        // refresh must not turn a committed payment into an error.
        runCatching { walletSync.refresh() }
        return result.data.toReceipt(
            idempotentReplay = result.meta?.idempotentReplay == true,
        )
    }

    private fun requireSession() = sessions.current() ?: throw SessionInvalidatedException()

    private companion object {
        /** Contract maxima; one round trip per screenful. */
        const val TICKET_PAGE_LIMIT = 50
        const val DETAIL_MESSAGE_LIMIT = 200
        const val MESSAGE_PAGE_LIMIT = 100
    }
}
