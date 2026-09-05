package com.kit.wallet

import com.kit.wallet.feature.chat.ConversationScrollAction
import com.kit.wallet.feature.chat.conversationScrollDecision
import com.kit.wallet.feature.chat.isConversationNearBottom
import com.kit.wallet.feature.chat.shouldRepinAfterGroupPaymentHydration
import com.kit.wallet.feature.chat.shouldReleaseOpeningBottomAnchor
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationScrollPolicyTest {
    @Test
    fun `seeing the top of a tall final message does not mean the reader is at the bottom`() {
        assertEquals(false, isConversationNearBottom(10, 8, 2000, 700, 80))
        assertEquals(false, isConversationNearBottom(10, 9, 2000, 700, 80))
        assertEquals(true, isConversationNearBottom(10, 9, 750, 700, 80))
        assertEquals(true, isConversationNearBottom(10, 9, 700, 700, 80))
        assertEquals(false, isConversationNearBottom(10, null, null, 700, 80))
    }

    @Test
    fun `an outgoing delivery during focus navigation cannot override its target`() {
        assertEquals(
            ConversationScrollAction.KEEP_POSITION,
            conversationScrollDecision(
                previousMessageIds = setOf("old"),
                messages = listOf(message("old"), message("sent", fromMe = true)),
                nearBottom = true,
                focusPending = true,
                openingBottomAnchorActive = true,
            ).action,
        )
    }

    @Test
    fun `first vertical user delta releases opening anchor before list consumption`() {
        assertEquals(true, shouldReleaseOpeningBottomAnchor(userInput = true, verticalDelta = -1f))
        assertEquals(true, shouldReleaseOpeningBottomAnchor(userInput = true, verticalDelta = 1f))
        assertEquals(false, shouldReleaseOpeningBottomAnchor(userInput = true, verticalDelta = 0f))
        assertEquals(false, shouldReleaseOpeningBottomAnchor(userInput = false, verticalDelta = -20f))
    }

    @Test
    fun `opening anchor follows async history even after first render moved away from bottom`() {
        val initial = listOf(message("one"))
        val hydrated = listOf(message("older"), message("one"))

        assertEquals(
            ConversationScrollAction.FOLLOW_NEWEST,
            conversationScrollDecision(
                previousMessageIds = initial.map(Message::id).toSet(),
                messages = hydrated,
                nearBottom = false,
                openingBottomAnchorActive = true,
            ).action,
        )
        assertEquals(
            ConversationScrollAction.KEEP_POSITION,
            conversationScrollDecision(
                previousMessageIds = initial.map(Message::id).toSet(),
                messages = hydrated,
                nearBottom = false,
                openingBottomAnchorActive = false,
            ).action,
        )
    }
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
    fun `a pending focus jump owns the thread's first positioning`() {
        assertEquals(
            ConversationScrollAction.KEEP_POSITION,
            conversationScrollDecision(
                null,
                listOf(message("target")),
                nearBottom = false,
                focusPending = true,
            ).action,
        )
    }

    @Test
    fun `a pending focus leaves an already positioned thread's behavior unchanged`() {
        val old = message("old")
        val incoming = message("incoming")

        val decision = conversationScrollDecision(
            previousMessageIds = setOf(old.id),
            messages = listOf(old, incoming),
            nearBottom = false,
            focusPending = true,
        )

        assertEquals(ConversationScrollAction.KEEP_POSITION, decision.action)
        assertEquals(1, decision.unseenMessages)
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
