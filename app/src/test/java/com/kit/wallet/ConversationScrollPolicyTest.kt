package com.kit.wallet

import com.kit.wallet.feature.chat.ConversationScrollAction
import com.kit.wallet.feature.chat.conversationScrollDecision
import com.kit.wallet.feature.chat.shouldRepinAfterGroupPaymentHydration
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationScrollPolicyTest {
    @Test
    fun `a direct or group thread opens at its newest message`() {
        for (messages in listOf(
            listOf(message("direct")),
            listOf(message("group-payment", kind = MessageKind.GROUP_PAYMENT)),
        )) {
            assertEquals(
                ConversationScrollAction.JUMP_TO_NEWEST,
                conversationScrollDecision(null, messages, nearBottom = false).action,
            )
        }
    }

    @Test
    fun `an outgoing group payment never pulls a reader away from older messages`() {
        val old = message("old")
        val payment = message("payment", fromMe = true, kind = MessageKind.GROUP_PAYMENT)

        val decision = conversationScrollDecision(
            previousMessageIds = setOf(old.id),
            messages = listOf(old, payment),
            nearBottom = false,
        )

        assertEquals(ConversationScrollAction.KEEP_POSITION, decision.action)
        assertEquals(1, decision.unseenMessages)
    }

    @Test
    fun `a group payment follows when the reader is already at the bottom`() {
        val old = message("old")
        val payment = message("payment", fromMe = true, kind = MessageKind.GROUP_PAYMENT)

        assertEquals(
            ConversationScrollAction.FOLLOW_NEWEST,
            conversationScrollDecision(
                previousMessageIds = setOf(old.id),
                messages = listOf(old, payment),
                nearBottom = true,
            ).action,
        )
    }

    @Test
    fun `an ordinary outgoing message still follows its sender`() {
        val old = message("old")
        val sent = message("sent", fromMe = true)

        assertEquals(
            ConversationScrollAction.FOLLOW_NEWEST,
            conversationScrollDecision(
                previousMessageIds = setOf(old.id),
                messages = listOf(old, sent),
                nearBottom = false,
            ).action,
        )
    }

    @Test
    fun `a projection-only update keeps the exact viewport`() {
        val messages = listOf(message("one"), message("two"))

        val decision = conversationScrollDecision(
            previousMessageIds = messages.mapTo(mutableSetOf(), Message::id),
            messages = messages,
            nearBottom = false,
        )

        assertEquals(ConversationScrollAction.KEEP_POSITION, decision.action)
        assertEquals(0, decision.unseenMessages)
    }

    @Test
    fun `payment hydration only repins an idle reader already at the newest rows`() {
        assertEquals(
            true,
            shouldRepinAfterGroupPaymentHydration(
                conversationPositioned = true,
                nearBottom = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(
            false,
            shouldRepinAfterGroupPaymentHydration(
                conversationPositioned = false,
                nearBottom = true,
                scrollInProgress = false,
            ),
        )
        assertEquals(
            false,
            shouldRepinAfterGroupPaymentHydration(
                conversationPositioned = true,
                nearBottom = false,
                scrollInProgress = false,
            ),
        )
        assertEquals(
            false,
            shouldRepinAfterGroupPaymentHydration(
                conversationPositioned = true,
                nearBottom = true,
                scrollInProgress = true,
            ),
        )
    }

    private fun message(
        id: String,
        fromMe: Boolean = false,
        kind: MessageKind = MessageKind.TEXT,
    ) = Message(id = id, text = id, time = "10:00", fromMe = fromMe, kind = kind)
}
