package com.kit.wallet.data.notifications

import com.kit.wallet.data.messaging.deterministicUuid
import com.kit.wallet.data.session.SessionFence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One validated notification reply, pinned to the session and idempotent outbox identity. */
internal data class MessageReplyRequest(
    val conversationId: String,
    val expectedSessionEpoch: String,
    val clientMessageId: String,
    val text: String,
)

internal object MessageReplyPolicy {
    /** Leaves headroom inside Android's roughly ten-second asynchronous broadcast allowance. */
    const val DELIVERY_TIMEOUT_MILLIS = 8_000L

    fun request(
        conversationId: String?,
        expectedSessionEpoch: String?,
        clientMessageId: String?,
        text: String?,
    ): MessageReplyRequest? {
        val normalizedText = text?.trim().orEmpty()
        if (
            conversationId == null || !CANONICAL_UUID.matches(conversationId) ||
            expectedSessionEpoch.isNullOrBlank() ||
            expectedSessionEpoch.length > MAX_SESSION_EPOCH_LENGTH ||
            clientMessageId == null || !CANONICAL_UUID.matches(clientMessageId) ||
            normalizedText.isEmpty()
        ) {
            return null
        }
        return MessageReplyRequest(
            conversationId = conversationId,
            expectedSessionEpoch = expectedSessionEpoch,
            clientMessageId = clientMessageId,
            text = normalizedText,
        )
    }

    /**
     * A replay of one notification action must address the same durable outbox record. A newer
     * incoming message, another conversation, or another authenticated epoch gets another ID.
     */
    fun deliveryMessageId(
        conversationId: String,
        sourceMessageId: String,
        sessionEpoch: String,
    ): String {
        require(CANONICAL_UUID.matches(conversationId))
        require(CANONICAL_UUID.matches(sourceMessageId))
        require(sessionEpoch.isNotBlank() && sessionEpoch.length <= MAX_SESSION_EPOCH_LENGTH)
        val seed = "kit-notification-reply-v1\u0000$sessionEpoch\u0000$conversationId\u0000$sourceMessageId"
        return deterministicUuid(seed)
    }

    private const val MAX_SESSION_EPOCH_LENGTH = 256
    private val CANONICAL_UUID = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    )
}

/**
 * Waits only for this account's encrypted state store, never for chat hydration or the network.
 * Returning true means [capture] returned after its durable idempotent enqueue, so the
 * notification may be cancelled. Timeout, session replacement, and capture failure all leave it
 * visible for retry.
 */
internal suspend fun deliverMessageReply(
    request: MessageReplyRequest,
    sessionFences: Flow<SessionFence?>,
    stateAvailable: Flow<Boolean>,
    currentSession: () -> SessionFence?,
    timeoutMillis: Long = MessageReplyPolicy.DELIVERY_TIMEOUT_MILLIS,
    capture: suspend (owner: SessionFence, request: MessageReplyRequest) -> Unit,
): Boolean {
    require(timeoutMillis > 0L)
    return try {
        withTimeoutOrNull(timeoutMillis) {
            val (owner, storeAvailable) = combine(
                sessionFences,
                stateAvailable,
            ) { session, ready ->
                session to ready
            }.first { (session, ready) ->
                session?.sessionId?.let { it != request.expectedSessionEpoch || ready } == true
            }
            if (
                owner == null ||
                owner.sessionId != request.expectedSessionEpoch ||
                !storeAvailable ||
                currentSession() != owner
            ) {
                return@withTimeoutOrNull false
            }
            capture(owner, request)
            true
        } == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
