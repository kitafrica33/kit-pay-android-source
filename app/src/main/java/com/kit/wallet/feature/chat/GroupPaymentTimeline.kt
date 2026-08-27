package com.kit.wallet.feature.chat

import com.kit.wallet.data.messaging.KitGroupPaymentAction
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind

/**
 * Which group-payment wire a thread is allowed to render.
 *
 * Signal authentication proves who wrote a message, not that its claim about money is true. The
 * announcement is the only offline evidence of who sent a payment and who was paid, so everything
 * else is believed only in its company: a "returned" is true from the sender alone, an "accepted"
 * only from somebody the announcement listed, and each member answers once. Anything that fails is
 * dropped rather than shown as raw text — an unrenderable descriptor is not a message.
 *
 * The rules and their order match iOS `ConversationTimelinePolicy.groupPaymentItem`.
 */
private data class GroupPaymentAnnouncement(
    val senderUserId: String,
    /** Recipients named in the descriptor, when they fitted. Empty means "not stated here". */
    val recipientUserIds: Set<String>,
)

private val CANONICAL_UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)

private fun canonicalUserId(value: String?): String? =
    value?.trim()?.lowercase()?.takeIf(CANONICAL_UUID::matches)

/**
 * Indexes the `sent` announcements of a thread by payment, keeping the first of each.
 *
 * The [Message.mediaDescriptor] is the authenticated descriptor the repository projected; parsing
 * it again here keeps this policy honest about what the wire actually said rather than trusting
 * fields a projection could have filled in.
 */
private fun groupPaymentAnnouncements(
    messages: List<Message>,
): Map<String, GroupPaymentAnnouncement> {
    val announcements = mutableMapOf<String, GroupPaymentAnnouncement>()
    for (message in messages) {
        val descriptor = message.groupPaymentDescriptor() ?: continue
        if (descriptor.action != KitGroupPaymentAction.SENT) continue
        val sender = canonicalUserId(message.senderUserId) ?: continue
        if (announcements.containsKey(descriptor.groupPaymentId)) continue
        announcements[descriptor.groupPaymentId] = GroupPaymentAnnouncement(
            senderUserId = sender,
            recipientUserIds = descriptor.recipientUserIds.mapNotNull(::canonicalUserId).toSet(),
        )
    }
    return announcements
}

/** The parsed descriptor behind a group-payment entry, or null when this is not one. */
internal fun Message.groupPaymentDescriptor(): KitGroupPaymentMessage? {
    if (kind != MessageKind.GROUP_PAYMENT && kind != MessageKind.GROUP_PAYMENT_EVENT) return null
    val descriptor = mediaDescriptor ?: return null
    return KitGroupPaymentMessage.parse(descriptor)
}

/**
 * Removes the group-payment entries this thread must not render, leaving everything else exactly
 * as it was and in the same order.
 *
 * A direct conversation drops all of it: money sent to a group is announced, claimed and reversed
 * in that group, so group wire arriving in a one-to-one thread is either corrupt state or a
 * forgery, and there is nothing truthful to draw for it.
 */
internal fun projectedGroupPaymentMessages(
    messages: List<Message>,
    isGroup: Boolean,
): List<Message> {
    val announcements = if (isGroup) groupPaymentAnnouncements(messages) else emptyMap()
    val claimedAnnouncements = mutableSetOf<String>()
    val answeredShares = mutableSetOf<String>()

    return messages.filter { message ->
        val descriptor = message.groupPaymentDescriptor()
            ?: return@filter message.kind != MessageKind.GROUP_PAYMENT &&
                message.kind != MessageKind.GROUP_PAYMENT_EVENT
        val author = canonicalUserId(message.senderUserId) ?: return@filter false
        val announcement = announcements[descriptor.groupPaymentId] ?: return@filter false

        when (descriptor.action) {
            // A second announcement of the same payment is a replay; the first one is the card.
            KitGroupPaymentAction.SENT ->
                author == announcement.senderUserId &&
                    claimedAnnouncements.add(descriptor.groupPaymentId)
            // Only a recipient can answer, only about themselves, and only once.
            KitGroupPaymentAction.ACCEPTED, KitGroupPaymentAction.REJECTED ->
                author != announcement.senderUserId &&
                    (
                        announcement.recipientUserIds.isEmpty() ||
                            author in announcement.recipientUserIds
                        ) &&
                    answeredShares.add("${descriptor.groupPaymentId}:$author")
            // Pulling back what nobody claimed is the sender's move alone.
            KitGroupPaymentAction.RETURNED ->
                author == announcement.senderUserId &&
                    answeredShares.add("${descriptor.groupPaymentId}:$author")
        }
    }
}
