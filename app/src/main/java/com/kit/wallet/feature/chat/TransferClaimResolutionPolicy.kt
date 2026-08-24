package com.kit.wallet.feature.chat

import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus

/** The only two accounts whose transfer may become actionable in a direct conversation. */
internal class TransferClaimPartyBinding private constructor(
    val currentUserId: String,
    val peerUserId: String,
) {
    companion object {
        fun create(currentUserId: String?, peerUserId: String?): TransferClaimPartyBinding? {
            val current = currentUserId?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
                ?: return null
            val peer = peerUserId?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
                ?: return null
            if (current == peer) return null
            return TransferClaimPartyBinding(current, peer)
        }
    }
}

internal enum class TransferClaimResolutionAction { ACCEPT, REJECT, REVERSE }

/**
 * Resolves an encrypted chat descriptor against the wallet API's authoritative claim.
 *
 * A real claim ID is not enough: the amount, currency, parties, chat direction and server action
 * bit must all agree. This keeps a copied or relayed descriptor inert in the wrong conversation.
 */
internal object TransferClaimResolutionPolicy {
    fun resolve(
        message: Message,
        claim: TransferClaim?,
        binding: TransferClaimPartyBinding?,
    ): TransferClaim? {
        if (message.kind != MessageKind.PAYMENT_TRANSFER || claim == null || binding == null) {
            return null
        }
        val descriptor = message.mediaDescriptor?.let(KitPaymentMessage::parse) ?: return null
        if (descriptor.action != KitPaymentAction.TRANSFER) return null
        if (!claim.id.equals(descriptor.referenceId, ignoreCase = true)) return null
        if (claim.amountMinor != descriptor.amountMinor) return null
        if (claim.currencyCode != descriptor.currencyCode) return null
        if (claim.currencyScale != descriptor.currencyScale) return null

        val sender = claim.senderUserId?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            ?: return null
        val recipient = claim.recipientUserId?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            ?: return null
        if (sender == recipient) return null
        if (setOf(sender, recipient) != setOf(binding.currentUserId, binding.peerUserId)) return null
        return claim
    }

    fun allows(
        action: TransferClaimResolutionAction,
        message: Message,
        claim: TransferClaim?,
        binding: TransferClaimPartyBinding?,
    ): Boolean {
        val resolved = resolve(message, claim, binding) ?: return false
        if (resolved.status != TransferClaimStatus.PENDING) return false
        val current = checkNotNull(binding).currentUserId
        val peer = binding.peerUserId
        val sender = checkNotNull(resolved.senderUserId).lowercase()
        val recipient = checkNotNull(resolved.recipientUserId).lowercase()
        return when (action) {
            TransferClaimResolutionAction.ACCEPT ->
                !message.fromMe && recipient == current && sender == peer && resolved.canAccept
            TransferClaimResolutionAction.REJECT ->
                !message.fromMe && recipient == current && sender == peer && resolved.canReject
            TransferClaimResolutionAction.REVERSE ->
                message.fromMe && sender == current && recipient == peer && resolved.canReverse
        }
    }

    /** Returns authoritative display state with every non-authorized action stripped. */
    fun forPresentation(
        message: Message,
        claim: TransferClaim?,
        binding: TransferClaimPartyBinding?,
        capabilityEnabled: Boolean,
    ): TransferClaim? {
        if (!capabilityEnabled) return null
        val resolved = resolve(message, claim, binding) ?: return null
        return resolved.copy(
            canAccept = allows(TransferClaimResolutionAction.ACCEPT, message, resolved, binding),
            canReject = allows(TransferClaimResolutionAction.REJECT, message, resolved, binding),
            canReverse = allows(TransferClaimResolutionAction.REVERSE, message, resolved, binding),
        )
    }
}
