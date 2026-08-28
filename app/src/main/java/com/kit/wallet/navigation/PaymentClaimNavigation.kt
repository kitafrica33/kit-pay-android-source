package com.kit.wallet.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.notifications.PaymentClaimAlert
import com.kit.wallet.data.notifications.PaymentClaimLink
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.TransferClaim
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a validated claim link may land. Nothing here settles, accepts or reverses anything. */
internal sealed interface PaymentClaimNavigationTarget {
    /** The one group conversation the claim provably belongs to, focused on its one card. */
    data class Conversation(
        val chatId: String,
        val focusMessageId: String? = null,
    ) : PaymentClaimNavigationTarget

    /**
     * The signed-in account's own wallet activity, named by the tapped claim exactly — the
     * mirror of iOS `WalletClaimNavigationRequest(claimID:)`. This is the floor for every
     * offline, missing or contradictory outcome: the claim id is the request's one-shot
     * identity, never a payload URL and never the claim's transaction id.
     */
    data class WalletHistory(val claimId: String) : PaymentClaimNavigationTarget
}

/**
 * Decides where a claim alert tap lands, given the authoritative claim just re-fetched.
 *
 * Mirror of iOS `ClaimablePaymentNotificationRoutingPolicy`: a conversation opens only when the
 * claim's canonical parties check out **and** the push's group hint names exactly one local
 * group conversation whose fully canonical roster holds current user, sender and recipient.
 * There is deliberately no direct-chat routing — a claim without a provable group binding opens
 * wallet activity for its own claim id. The push never adds reach: its hints can only narrow
 * where an already-validated claim goes, never open something the claim itself does not support.
 */
internal fun paymentClaimDestination(
    link: PaymentClaimLink,
    claim: TransferClaim,
    currentUserId: String?,
    chats: List<ChatPreview>,
    rosterFor: (String) -> List<ChatMember>,
    messagesFor: (String) -> List<Message>,
): PaymentClaimNavigationTarget {
    val walletActivity = PaymentClaimNavigationTarget.WalletHistory(link.claimId)
    // The refetch echoing anything but the exact canonical claim the tap asked about is
    // contradictory, full stop. (An unknown status never reaches here: the wallet mapper
    // refuses to build a TransferClaim it cannot type.)
    if (PaymentClaimAlert.canonicalUuid(claim.id) != link.claimId) return walletActivity
    val sender = PaymentClaimAlert.canonicalUuid(claim.senderUserId) ?: return walletActivity
    val recipient = PaymentClaimAlert.canonicalUuid(claim.recipientUserId)
        ?: return walletActivity
    if (sender == recipient) return walletActivity
    val current = PaymentClaimAlert.canonicalUuid(currentUserId) ?: return walletActivity
    if (current != sender && current != recipient) return walletActivity

    val conversationId = link.conversationId ?: return walletActivity
    val group = chats.filter { chat ->
        PaymentClaimAlert.canonicalUuid(chat.id) == conversationId
    }.singleOrNull()?.takeIf(ChatPreview::isGroup) ?: return walletActivity
    val roster = canonicalRoster(rosterFor(group.id)) ?: return walletActivity
    if (current !in roster || sender !in roster || recipient !in roster) {
        return walletActivity
    }
    return PaymentClaimNavigationTarget.Conversation(
        chatId = group.id,
        focusMessageId = focusMessageId(link, messagesFor(group.id)),
    )
}

/**
 * Every member canonical and no member twice, or no roster at all — iOS `canonicalRoster`.
 * A roster this policy cannot read exactly is a roster it must not authorize a chat with.
 */
private fun canonicalRoster(members: List<ChatMember>): Set<String>? {
    val roster = HashSet<String>(members.size * 2)
    for (member in members) {
        val userId = PaymentClaimAlert.canonicalUuid(member.userId) ?: return null
        if (!roster.add(userId)) return null
    }
    return roster
}

/**
 * The claim's one card in the conversation — a transfer card naming this exact claim, or (when
 * the push hinted one) the group-payment announcement naming that payment. Exactly one match
 * across both shapes, or no focus at all rather than a guessed scroll.
 *
 * iOS additionally verifies each candidate's own `conversationId` against the chosen group
 * because it filters one flat message list. This model carries no per-message conversation
 * field; the same guarantee is structural — [messages] is the chosen group's own store, read
 * by its exact local id, so a candidate from another conversation cannot appear here.
 */
private fun focusMessageId(link: PaymentClaimLink, messages: List<Message>): String? {
    val matches = messages.filter { message ->
        when (message.kind) {
            MessageKind.PAYMENT_TRANSFER ->
                PaymentClaimAlert.canonicalUuid(message.paymentReferenceId) == link.claimId
            MessageKind.GROUP_PAYMENT ->
                link.groupPaymentId != null &&
                    PaymentClaimAlert.canonicalUuid(message.groupPaymentId) ==
                    link.groupPaymentId
            else -> false
        }
    }
    return matches.singleOrNull()?.id
}

/**
 * Resolves a tapped claim alert into a navigation target, strictly from server state.
 *
 * The push named the claim; everything else — status, parties, whether this account is one of
 * them — comes from a fresh authoritative fetch behind a fresh capability check. Any failure on
 * that path (offline, capability off, unknown claim, mismatched echo) resolves to wallet
 * activity for the tapped claim rather than to whatever the push claimed.
 *
 * The account is the resolution's security context, mirroring iOS's authenticated-context
 * rechecks: the canonical account id is fixed before the first await, re-verified after every
 * await and before the target is emitted, and verified once more by the observer through
 * [targetOwnerStillCurrent] before navigating. A sign-out or account switch anywhere in that
 * window suppresses navigation entirely — one account's tap must never steer another's session.
 */
@HiltViewModel
class PaymentClaimNavigationViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val mutableTarget = MutableStateFlow<PaymentClaimNavigationTarget?>(null)
    internal val target: StateFlow<PaymentClaimNavigationTarget?> = mutableTarget.asStateFlow()

    /** The canonical account the current [target] was resolved for; null when no target. */
    private var targetOwner: String? = null

    private var resolving: Job? = null

    internal fun open(link: PaymentClaimLink) {
        // A newer tap names its own claim; an unfinished older resolution must not race it.
        resolving?.cancel()
        resolving = viewModelScope.launch {
            val owner = PaymentClaimAlert.canonicalUuid(walletRepository.currentAccountId)
            val resolved = owner?.let { resolveTarget(link, it) }
            // A suppressed resolution also clears any stale target instead of leaving it live.
            targetOwner = owner.takeIf { resolved != null }
            mutableTarget.value = resolved
        }
    }

    internal fun consumed() {
        targetOwner = null
        mutableTarget.value = null
    }

    /** True only while the account the current target was resolved for is still signed in. */
    internal fun targetOwnerStillCurrent(): Boolean {
        val owner = targetOwner ?: return false
        return PaymentClaimAlert.canonicalUuid(walletRepository.currentAccountId) == owner
    }

    private suspend fun resolveTarget(
        link: PaymentClaimLink,
        owner: String,
    ): PaymentClaimNavigationTarget? {
        // Every failure lands on wallet activity for the tapped claim id — the id the link
        // itself validated — mirroring iOS WalletClaimNavigationRequest(claimID:). Only a
        // broken security context suppresses instead of falling back: null, never a target.
        val walletActivity = PaymentClaimNavigationTarget.WalletHistory(link.claimId)
        val claimable = attempt { walletRepository.refreshClaimableTransfersCapability() }
        if (!ownerStillCurrent(owner)) return null
        if (claimable != true) return walletActivity
        val claim = attempt { walletRepository.transferClaim(link.claimId) }
        if (!ownerStillCurrent(owner)) return null
        if (claim == null) return walletActivity
        return paymentClaimDestination(
            link = link,
            claim = claim,
            currentUserId = owner,
            chats = chatRepository.chats.value,
            rosterFor = { chatId -> chatRepository.groupMembers(chatId).value },
            messagesFor = { chatId -> chatRepository.conversation(chatId).value },
        )
    }

    private fun ownerStillCurrent(owner: String): Boolean =
        PaymentClaimAlert.canonicalUuid(walletRepository.currentAccountId) == owner

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}
