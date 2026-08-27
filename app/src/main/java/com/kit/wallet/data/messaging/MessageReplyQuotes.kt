package com.kit.wallet.data.messaging

import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.replyPreviewLabel

/**
 * Fills in the quoted line an answer shows above itself.
 *
 * The wire carries only the target's ID — deliberately, since the words themselves are already in
 * the thread and sending them a second time would put a plaintext copy of a message on a path that
 * never needed one. So the quote is resolved here, from what this device has already decrypted.
 *
 * A target that is not in the thread leaves the quote null rather than substituting anything. That
 * happens honestly: an answer to a message from before this installation, or to one whose sender
 * has since revoked it. Drawing a placeholder in its place would attribute words to someone that
 * nobody on this device can actually read.
 */
internal object MessageReplyQuotes {

    fun resolve(messages: List<Message>): List<Message> {
        if (messages.none { it.replyToMessageId != null }) return messages
        val byId = messages.associateBy(Message::id)
        return messages.map { message ->
            val targetId = message.replyToMessageId ?: return@map message
            // An answer to itself is not a thing anyone can write, and resolving one would render
            // a quote of the very bubble it sits inside.
            val target = byId[targetId]?.takeIf { it.id != message.id } ?: return@map message
            message.copy(
                replyToText = target.replyPreviewLabel().takeIf(String::isNotBlank),
                replyToSenderName = target.senderName,
                replyToFromMe = target.fromMe,
            )
        }
    }
}
