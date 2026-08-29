package com.kit.wallet.data.support

/**
 * Domain projection of the support surface. Every value here is server-authored; the client
 * adds no derived security or identity claims. In particular:
 *
 *  - [SupportSender.verifiedOfficialSupport] is true only when the server attached the exact
 *    `verification.designation == "official_support"` object. Display names, `official`,
 *    `automated`, and sender types never light the badge.
 *  - There is no end-to-end-encryption flag anywhere in this model. Support threads are
 *    server-readable by contract (enforced by the capability handshake); the UI shows a
 *    constant server-readable notice sourced from the negotiated protocol, so a drifted
 *    per-ticket flag cannot cause the client to over-claim privacy.
 */

enum class SupportTicketStatus {
    OPEN,
    CLOSED,

    /** Unrecognized server status: treated exactly like CLOSED for every write affordance. */
    UNKNOWN,
}

enum class SupportSenderType {
    CUSTOMER,
    AGENT,
    ASSISTANT,
    SYSTEM,

    /** Unrecognized sender type: rendered neutrally, never as the customer, never badged. */
    UNKNOWN,
}

data class SupportCategory(
    val id: String,
    val key: String,
    val name: String,
    val description: String?,
)

data class SupportSender(
    val type: SupportSenderType,
    val displayName: String,
    val automated: Boolean,
    /** Sole source: server `verification.designation == "official_support"`. */
    val verifiedOfficialSupport: Boolean,
    /** Ticket-scoped alias; present only on agent messages. Never an account identity. */
    val agentAlias: String?,
)

data class SupportMessage(
    val id: String,
    val position: Long,
    val sender: SupportSender,
    val body: String,
    /**
     * The server attached a media asset this build cannot display. Rendered as an explicit
     * "attachment can't be shown" placeholder — never fetched, never guessed at.
     */
    val hasUndisplayableAttachment: Boolean,
    val createdAt: String,
)

data class SupportTicket(
    val id: String,
    val reference: String,
    val subject: String,
    val status: SupportTicketStatus,
    val categoryKey: String,
    val categoryName: String,
    /** Support identity for the header; badge only from the verification designation. */
    val identityDisplayName: String,
    val identityVerified: Boolean,
    /** Ticket-scoped alias of the assigned human agent, if any. */
    val agentAlias: String?,
    val agentHasAvatar: Boolean,
    val assistantActive: Boolean,
    val messageCount: Long,
    val createdAt: String,
    val lastMessageAt: String?,
    val closedAt: String?,
    val closedReasonCode: String?,
) {
    /** Writes (messages, close, escalate, payment) are offered only while verifiably open. */
    val acceptsWrites: Boolean get() = status == SupportTicketStatus.OPEN
}

data class SupportTicketPage(
    val tickets: List<SupportTicket>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class SupportTicketDetail(
    val ticket: SupportTicket,
    /** Oldest-first window ending at the newest message. */
    val messages: List<SupportMessage>,
    val messagesHasMore: Boolean,
    val messagesNextAfterPosition: Long?,
)

/** Result of a message poll: new messages plus the refreshed ticket the server sent with them. */
data class SupportMessagesPoll(
    val ticket: SupportTicket,
    val messages: List<SupportMessage>,
)

/** A durable queued write, rendered inline until the server accepts or definitively rejects it. */
data class SupportDraft(
    val clientMessageId: String,
    val kind: String,
    val ticketId: String?,
    val categoryKey: String?,
    val subject: String?,
    val body: String,
    val failed: Boolean,
    val failureCode: String?,
    val attempted: Boolean,
    val createdAtEpochMillis: Long,
)

data class SupportPaymentReceipt(
    val transactionId: String,
    val reference: String,
    val amount: String,
    val currencyCode: String,
    val status: String,
    val occurredAt: String,
    /** Server-normalized company beneficiary display name — the only payee ever shown. */
    val beneficiaryName: String,
    val ticketPaymentId: String,
    /** True when the server matched a prior Idempotency-Key and returned the original charge. */
    val idempotentReplay: Boolean,
)

/** Outcome of one outbox row during a flush pass. */
sealed interface SupportDraftOutcome {
    /** The open-ticket draft committed; the row is gone. */
    data class TicketOpened(val ticket: SupportTicket) : SupportDraftOutcome

    /** The message draft committed; the row is gone. */
    data class MessageSent(val message: SupportMessage) : SupportDraftOutcome

    /** The server definitively rejected this exact content; kept locally as failed. */
    data class Rejected(val code: String?) : SupportDraftOutcome

    /** Connectivity, session change, or retryable server state; still pending, same key. */
    data object Deferred : SupportDraftOutcome
}
