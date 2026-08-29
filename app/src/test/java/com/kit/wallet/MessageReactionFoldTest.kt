package com.kit.wallet

import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
import com.kit.wallet.data.repository.AuthenticatedProjectedText
import com.kit.wallet.data.repository.AuthenticatedTextDeliveryState
import com.kit.wallet.data.repository.SELF_REACTOR_NAME
import com.kit.wallet.data.repository.authenticatedProjectionOrder
import com.kit.wallet.data.repository.foldAuthenticatedReactions
import com.kit.wallet.feature.chat.QUICK_REACTIONS
import com.kit.wallet.feature.chat.REACTION_PICKER_GROUPS
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.acceptsReactions
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReactionFoldTest {
    @Test
    fun `a reaction lands on its target and names the reactor`() {
        val folded = fold(
            message(TARGET, "Sent you the deposit", fromMe = true),
            reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = false),
        )

        val reactions = folded.getValue(TARGET)
        assertEquals(1, reactions.size)
        assertEquals("👍", reactions.single().emoji)
        assertEquals(listOf(PEER_NAME), reactions.single().reactorNames)
        assertEquals(1, reactions.single().count)
        assertFalse(reactions.single().fromMe)
    }

    @Test
    fun `add remove and re-add converge on the last descriptor`() {
        val log = listOf(
            message(TARGET, "Sent you the deposit", fromMe = true),
            reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "👍", KitReactionAction.REMOVE, fromMe = false),
        )
        // A removal cancels the add outright rather than leaving a zero-count chip.
        assertTrue(fold(*log.toTypedArray()).isEmpty())

        val reAdded = fold(
            *(log + reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = false)).toTypedArray(),
        )
        assertEquals(1, reAdded.getValue(TARGET).single().count)
    }

    @Test
    fun `the fold is order-driven and therefore idempotent under replay`() {
        val entries = listOf(
            message(TARGET, "Sent you the deposit", fromMe = true),
            reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "👍", KitReactionAction.REMOVE, fromMe = false),
            reaction(TARGET, "❤️", KitReactionAction.ADD, fromMe = true),
        )
        val expected = fold(*entries.toTypedArray())

        // Duplicated delivery and a shuffled arrival order both re-sort into the same projection,
        // so the sync stream needs no separate deduplication for reactions.
        assertEquals(expected, fold(*(entries + entries).toTypedArray()))
        assertEquals(expected, fold(*entries.reversed().toTypedArray()))

        assertEquals("❤️", expected.getValue(TARGET).single().emoji)
        assertTrue(expected.getValue(TARGET).single().fromMe)
        assertEquals(listOf(SELF_REACTOR_NAME), expected.getValue(TARGET).single().reactorNames)
    }

    @Test
    fun `same timestamp reactions use monotonic server IDs on origin and receiver`() {
        val serverTime = BASE.plusSeconds(100)
        val addServerId = "0198e5d5-1000-7000-8000-000000000001"
        val removeServerId = "0198e5d5-1000-7000-8000-000000000002"

        fun projectedReaction(
            recordKey: String,
            serverMessageId: String,
            clientMessageId: String,
            action: KitReactionAction,
            fromMe: Boolean,
        ) = AuthenticatedProjectedText(
            recordKey = recordKey,
            messageId = serverMessageId,
            serverMessageId = serverMessageId,
            clientMessageId = clientMessageId,
            conversationId = CONVERSATION,
            senderUserId = if (fromMe) SELF_USER else PEER_USER,
            fromCurrentUser = fromMe,
            text = KitReactionMessage(TARGET, "👍", action).encode(),
            sentAt = serverTime,
            deliveryState = AuthenticatedTextDeliveryState.SENT,
        )

        // Local client IDs deliberately order opposite to the server UUIDv7s. Once acknowledged,
        // both the originating projection and the recipient projection must use the server order.
        val originAdd = projectedReaction(
            recordKey = "out:add",
            serverMessageId = addServerId,
            clientMessageId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
            action = KitReactionAction.ADD,
            fromMe = true,
        )
        val originRemove = projectedReaction(
            recordKey = "out:remove",
            serverMessageId = removeServerId,
            clientMessageId = "00000000-0000-4000-8000-000000000001",
            action = KitReactionAction.REMOVE,
            fromMe = true,
        )
        val receivedAdd = originAdd.copy(recordKey = "in:$addServerId", fromCurrentUser = false)
        val receivedRemove = originRemove.copy(
            recordKey = "in:$removeServerId",
            fromCurrentUser = false,
        )

        val target = message(TARGET, "Sent you the deposit", fromMe = false)
        assertTrue(fold(target, originRemove, originAdd).isEmpty())
        assertTrue(fold(target, receivedRemove, receivedAdd).isEmpty())
    }

    @Test
    fun `each reactor is counted once per emoji and I am listed first`() {
        val folded = fold(
            message(TARGET, "Sent you the deposit", fromMe = false),
            reaction(TARGET, "❤️", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "❤️", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "❤️", KitReactionAction.ADD, fromMe = true),
        )

        val heart = folded.getValue(TARGET).single()
        assertEquals(2, heart.count)
        assertEquals(listOf(SELF_REACTOR_NAME, PEER_NAME), heart.reactorNames)
        assertTrue(heart.fromMe)
    }

    @Test
    fun `adding another emoji replaces the reactors previous reaction`() {
        val folded = fold(
            message(TARGET, "Sent you the deposit", fromMe = true),
            reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "❤️", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "👍", KitReactionAction.REMOVE, fromMe = false),
        )

        assertEquals(listOf("❤️"), folded.getValue(TARGET).map { it.emoji })
    }

    @Test
    fun `a newer emoji replaces the same reactors earlier choices`() {
        val folded = fold(
            message(TARGET, "Sent you the deposit", fromMe = true),
            reaction(TARGET, "😮", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "🙏", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = false),
            reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = true),
        )

        assertEquals(listOf("👍"), folded.getValue(TARGET).map { it.emoji })
        assertEquals(2, folded.getValue(TARGET).single().count)
    }

    @Test
    fun `a reaction for an unknown target annotates nothing`() {
        // Paging or a partial sync can deliver a reaction before its target authenticates locally.
        val folded = fold(
            message(TARGET, "Sent you the deposit", fromMe = true),
            reaction(OTHER_TARGET, "👍", KitReactionAction.ADD, fromMe = false),
        )

        assertTrue(folded.isEmpty())
    }

    @Test
    fun `a permanently failed reaction never changes the local tally`() {
        val failedAdd = reaction(
            TARGET,
            "👍",
            KitReactionAction.ADD,
            fromMe = true,
            deliveryState = AuthenticatedTextDeliveryState.PERMANENT_FAILURE,
        )
        assertTrue(
            fold(message(TARGET, "Sent you the deposit", fromMe = false), failedAdd).isEmpty(),
        )

        val folded = fold(
            message(TARGET, "Sent you the deposit", fromMe = false),
            reaction(TARGET, "👍", KitReactionAction.ADD, fromMe = true),
            reaction(
                TARGET,
                "👍",
                KitReactionAction.REMOVE,
                fromMe = true,
                deliveryState = AuthenticatedTextDeliveryState.PERMANENT_FAILURE,
            ),
        )
        assertEquals(listOf("👍"), folded.getValue(TARGET).map { it.emoji })
    }

    @Test
    fun `text that is not a well-formed reaction is never folded`() {
        val folded = fold(
            message(TARGET, "Sent you the deposit", fromMe = true),
            message(OTHER_TARGET, "${KitReactionMessage.PREFIX}v=9&mid=$TARGET&e=%F0%9F%91%8D", false),
        )

        assertTrue(folded.isEmpty())
    }

    @Test
    fun `the quick palette is the confirmed order, check mark second`() {
        // A confirmed product decision rather than an implementation default, so it is asserted
        // whole: ✅ is second because Kit Pay conversations carry payment cards and
        // "done / confirmed / paid" earns a first-class slot instead of living behind the picker.
        assertEquals(listOf("👍", "✅", "❤️", "😂", "😮", "🙏"), QUICK_REACTIONS)
        assertEquals(QUICK_REACTIONS.distinct(), QUICK_REACTIONS)
        QUICK_REACTIONS.forEach {
            assertTrue(it, KitReactionMessage.isAcceptableReaction(it))
        }
        // The picker opens on the same six, so the palette and "Frequently used" cannot drift.
        assertEquals(QUICK_REACTIONS, REACTION_PICKER_GROUPS.first().second)
    }

    @Test
    fun `only a settled bubble can carry a reaction, wherever it is shown`() {
        // One rule decides whether the affordance appears at all, in the transcript and in the
        // full-screen gallery alike, so a new message kind has to be classified rather than
        // silently inheriting a bubble's behaviour.
        val centredRecords = setOf(
            MessageKind.CALL,
            MessageKind.PAYMENT_EVENT,
            // A group payment's card spans the thread and belongs to everybody in it; its outcome
            // lines are records the group states about itself. Neither is a bubble.
            MessageKind.GROUP_PAYMENT,
            MessageKind.GROUP_PAYMENT_EVENT,
            MessageKind.GROUP_PAYMENT_REQUEST,
            MessageKind.GROUP_PAYMENT_REQUEST_EVENT,
            MessageKind.SYSTEM,
        )
        MessageKind.entries.forEach { kind ->
            assertEquals(kind.name, kind !in centredRecords, uiMessage(kind = kind).acceptsReactions)
        }

        // A send still on its way is identified by its client ID, which the server ID replaces on
        // acknowledgement — a reaction pinned to it in between would be stranded.
        val settled = setOf(DeliveryState.SENT, DeliveryState.DELIVERED, DeliveryState.READ)
        DeliveryState.entries.forEach { state ->
            assertEquals(state.name, state in settled, uiMessage(state = state).acceptsReactions)
        }
    }

    private fun uiMessage(
        kind: MessageKind = MessageKind.TEXT,
        state: DeliveryState = DeliveryState.READ,
    ) = Message(
        id = TARGET,
        text = "Sent you the deposit",
        time = "09:00",
        fromMe = true,
        state = state,
        kind = kind,
    )

    private fun fold(vararg entries: AuthenticatedProjectedText) = foldAuthenticatedReactions(
        ordered = entries.sortedWith(authenticatedProjectionOrder),
        nameOf = { senderUserId -> NAMES_BY_SENDER[senderUserId] ?: PEER_NAME },
    )

    private fun message(
        messageId: String,
        text: String,
        fromMe: Boolean,
    ): AuthenticatedProjectedText = projected(messageId, text, fromMe)

    private fun reaction(
        targetMessageId: String,
        emoji: String,
        action: KitReactionAction,
        fromMe: Boolean,
        deliveryState: AuthenticatedTextDeliveryState = AuthenticatedTextDeliveryState.SENT,
    ): AuthenticatedProjectedText = projected(
        messageId = nextMessageId(),
        text = KitReactionMessage(targetMessageId, emoji, action).encode(),
        fromMe = fromMe,
        deliveryState = deliveryState,
    )

    private fun projected(
        messageId: String,
        text: String,
        fromMe: Boolean,
        deliveryState: AuthenticatedTextDeliveryState = AuthenticatedTextDeliveryState.SENT,
    ): AuthenticatedProjectedText {
        val sequence = ordinal++
        return AuthenticatedProjectedText(
            recordKey = messageId,
            messageId = messageId,
            serverMessageId = "%08d".format(sequence),
            clientMessageId = messageId,
            conversationId = CONVERSATION,
            senderUserId = if (fromMe) SELF_USER else PEER_USER,
            fromCurrentUser = fromMe,
            text = text,
            sentAt = BASE.plusSeconds(sequence.toLong()),
            deliveryState = deliveryState,
        )
    }

    private fun nextMessageId(): String = "bbbbbbbb-bbbb-4bbb-8bbb-%012d".format(ordinal)

    private var ordinal = 0

    private companion object {
        const val CONVERSATION = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val TARGET = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OTHER_TARGET = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val SELF_USER = "11111111-1111-4111-8111-111111111111"
        const val PEER_USER = "22222222-2222-4222-8222-222222222222"
        const val PEER_NAME = "Aisha"
        const val SECOND_PEER_USER = "33333333-3333-4333-8333-333333333333"
        const val SECOND_PEER_NAME = "Brian"
        val NAMES_BY_SENDER = mapOf(
            PEER_USER to PEER_NAME,
            SECOND_PEER_USER to SECOND_PEER_NAME,
        )
        val BASE: Instant = Instant.parse("2026-08-24T09:00:00Z")
    }
}
