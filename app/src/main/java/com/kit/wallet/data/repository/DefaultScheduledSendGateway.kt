package com.kit.wallet.data.repository

import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.ScheduledSendGateway
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a scheduled item through the ordinary send paths, at the moment it is actually due.
 *
 * Nothing here is a parallel implementation of sending: a scheduled message goes out through
 * [ChatRepository.sendMessage] and a scheduled request through the same create-then-share pair the
 * composer uses. That is what makes a scheduled send inherit the encryption, the roster of the
 * moment, the durable outbox and the offline replay that an ordinary send already has.
 */
@Singleton
internal class DefaultScheduledSendGateway @Inject constructor(
    private val chats: ChatRepository,
    private val wallet: WalletRepository,
    private val sessions: SessionStore,
) : ScheduledSendGateway {
    override fun readyFor(owner: SessionFence): Boolean =
        sessions.current()?.fence() == owner && chats.readiness.value

    override suspend fun sendText(
        owner: SessionFence,
        conversationId: String,
        text: String,
        onDurablyCommitted: () -> Unit,
    ) {
        requireOwner(owner)
        chats.sendMessageForOwner(owner, conversationId, text) { onDurablyCommitted() }
    }

    override suspend fun sendPaymentRequest(
        owner: SessionFence,
        conversationId: String,
        idempotencyKey: String,
        amountMinor: Long,
        note: String?,
        onDurablyCommitted: () -> Unit,
    ) {
        requireOwner(owner)
        val peerUserId = requireNotNull(chats.chat(conversationId)?.peerUserId) {
            "This conversation is not linked to a Kit Pay account"
        }
        requireOwner(owner)
        // The scheduled item's own identity is the idempotency key, so a dispatch that created the
        // request and then failed to share it reuses that exact request on its next attempt rather
        // than asking the peer for the same money twice.
        val created = wallet.createChatPaymentRequestForOwner(
            owner = owner,
            peerUserId = peerUserId,
            amountMinor = amountMinor,
            note = note,
            idempotencyKey = "android-scheduled-request-$idempotencyKey",
        )
        requireOwner(owner)
        val descriptor = KitPaymentMessage(
            action = KitPaymentAction.REQUEST,
            referenceId = created.id,
            amountMinor = created.amountMinor,
            currencyCode = created.currencyCode,
            currencyScale = created.currencyScale,
            note = created.note?.takeIf(String::isNotBlank),
        ).encode()
        requireOwner(owner)
        chats.sendPaymentEventForOwner(owner, conversationId, descriptor) {
            onDurablyCommitted()
        }
    }

    private fun requireOwner(expected: SessionFence) {
        if (sessions.current()?.fence() != expected) throw SessionInvalidatedException()
    }
}
