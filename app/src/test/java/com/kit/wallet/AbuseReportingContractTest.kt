package com.kit.wallet

import com.kit.wallet.data.repository.AbuseReportContext
import com.kit.wallet.data.repository.AbuseReportContract
import com.kit.wallet.data.repository.AbuseReportReason
import com.kit.wallet.data.repository.AbuseReportRequest
import com.kit.wallet.data.repository.AbuseReportSelectedMessage
import com.kit.wallet.data.repository.AbuseReportSelectionPolicy
import com.kit.wallet.data.repository.AbuseReportTarget
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AbuseReportingContractTest {
    @Test
    fun `direct context and account target require two distinct canonical participants`() {
        val context = AbuseReportContext.create(
            CURRENT.uppercase(),
            directChat(peerUserId = REPORTED.uppercase()),
        )

        assertNotNull(context)
        assertEquals(setOf(CURRENT, REPORTED), context?.participantUserIds)
        assertEquals(
            AbuseReportTarget.AccountTarget(REPORTED),
            AbuseReportSelectionPolicy.accountTarget(checkNotNull(context), directChat()),
        )
        assertNull(AbuseReportContext.create(CURRENT, directChat(peerUserId = CURRENT)))
        assertNull(AbuseReportContext.create("not-a-user", directChat()))
    }

    @Test
    fun `group message report binds the authenticated sender and requires both roster members`() {
        val group = groupChat()
        val members = listOf(
            ChatMember(CURRENT, "You", isSelf = true),
            ChatMember(REPORTED, "Emma"),
            ChatMember(THIRD, "Asha"),
        )
        val context = checkNotNull(AbuseReportContext.create(CURRENT, group, members))
        val incoming = message(
            id = MESSAGE,
            fromMe = false,
            senderUserId = REPORTED,
            senderName = "Emma",
        )

        assertEquals(
            AbuseReportTarget.MessageTarget(MESSAGE, REPORTED),
            AbuseReportSelectionPolicy.messageTarget(incoming, context),
        )
        assertNull(AbuseReportSelectionPolicy.accountTarget(context, group))
        assertNull(
            AbuseReportSelectionPolicy.messageTarget(
                incoming.copy(senderUserId = OUTSIDER),
                context,
            ),
        )
        assertNull(
            AbuseReportSelectionPolicy.messageTarget(
                incoming.copy(fromMe = true, senderUserId = CURRENT),
                context,
            ),
        )
    }

    @Test
    fun `group context fails closed until the authenticated roster includes the viewer`() {
        assertNull(
            AbuseReportContext.create(
                CURRENT,
                groupChat(),
                listOf(ChatMember(REPORTED, "Emma"), ChatMember(THIRD, "Asha")),
            ),
        )
        assertNull(
            AbuseReportContext.create(
                CURRENT,
                groupChat(),
                listOf(
                    ChatMember(CURRENT, "You", isSelf = true),
                    ChatMember("bad-id", "Unknown"),
                ),
            ),
        )
    }

    @Test
    fun `message targets reject outgoing synthetic and non-message timeline rows`() {
        val context = checkNotNull(AbuseReportContext.create(CURRENT, directChat()))
        val incoming = message(senderUserId = REPORTED)

        assertNotNull(AbuseReportSelectionPolicy.messageTarget(incoming, context))
        assertNull(AbuseReportSelectionPolicy.messageTarget(incoming.copy(fromMe = true), context))
        assertNull(
            AbuseReportSelectionPolicy.messageTarget(
                incoming.copy(state = DeliveryState.SENDING),
                context,
            ),
        )
        assertNull(
            AbuseReportSelectionPolicy.messageTarget(
                incoming.copy(id = "local-pending"),
                context,
            ),
        )
        assertNull(
            AbuseReportSelectionPolicy.messageTarget(
                incoming.copy(kind = MessageKind.SYSTEM),
                context,
            ),
        )
    }

    @Test
    fun `group plaintext candidates are only reporter and reported sender messages`() {
        val context = checkNotNull(
            AbuseReportContext.create(
                CURRENT,
                groupChat(),
                listOf(
                    ChatMember(CURRENT, "You", isSelf = true),
                    ChatMember(REPORTED, "Emma"),
                    ChatMember(THIRD, "Asha"),
                ),
            ),
        )
        val target = AbuseReportTarget.MessageTarget(MESSAGE, REPORTED)
        val oldTarget = message(
            id = MESSAGE,
            text = "old target",
            senderUserId = REPORTED,
            senderName = "Emma",
            sortEpochMillis = 1,
        )
        val newer = (1..55).map { index ->
            message(
                id = uuid(index + 100),
                text = "new $index",
                senderUserId = REPORTED,
                senderName = "Emma",
                sortEpochMillis = index.toLong() + 1,
            )
        }
        val thirdParty = (1..20).map { index ->
            message(
                id = uuid(index + 200),
                text = "third $index",
                senderUserId = THIRD,
                senderName = "Asha",
                sortEpochMillis = index.toLong() + 1,
            )
        }
        val mine = message(
            id = uuid(299),
            text = "my context",
            fromMe = true,
            senderUserId = CURRENT,
            senderName = "You",
            sortEpochMillis = 100,
        )
        val excluded = listOf(
            message(id = uuid(300), text = "pending", state = DeliveryState.SENDING),
            message(id = uuid(301), text = "payment", kind = MessageKind.PAYMENT),
            message(id = uuid(302), text = "   "),
            message(id = "not-a-server-id", text = "synthetic"),
            message(id = uuid(303), text = "missing sender", senderUserId = null),
            message(id = uuid(304), text = "outside", senderUserId = OUTSIDER),
            message(
                id = uuid(305),
                text = "false authorship",
                fromMe = true,
                senderUserId = REPORTED,
            ),
        )
        val forgedTarget = message(
            id = MESSAGE,
            text = "forged outgoing duplicate",
            fromMe = true,
            senderUserId = CURRENT,
            senderName = "You",
            sortEpochMillis = 200,
        )

        val candidates = AbuseReportSelectionPolicy.candidates(
            listOf(forgedTarget, oldTarget, mine) + newer + thirdParty + excluded,
            context = context,
            target = target,
        )

        assertEquals(AbuseReportContract.MAXIMUM_PRESENTED_MESSAGES, candidates.size)
        assertEquals(MESSAGE, candidates.first().messageId)
        assertTrue(candidates.first().isReportTarget)
        assertEquals("Emma", candidates.first().senderName)
        assertTrue(candidates.any { it.messageId == mine.id })
        assertEquals(setOf(CURRENT, REPORTED), candidates.map { it.senderUserId }.toSet())
        assertFalse(candidates.any { it.senderUserId == THIRD })
        assertFalse(
            candidates.any {
                it.plaintext in setOf("pending", "payment", "synthetic", "false authorship")
            },
        )
    }

    @Test
    fun `selection enforces five-message and twelve-thousand-byte ceilings`() {
        val context = checkNotNull(AbuseReportContext.create(CURRENT, directChat()))
        val target = AbuseReportTarget.AccountTarget(REPORTED)
        val candidates = (1..5).map { index ->
            val message = message(
                id = uuid(index),
                text = "x".repeat(3_000),
                senderUserId = REPORTED,
                sortEpochMillis = index.toLong(),
            )
            AbuseReportSelectionPolicy.candidates(
                listOf(message),
                context,
                target,
            ).single()
        }
        val firstFour = candidates.take(4).mapTo(mutableSetOf()) { it.messageId }

        assertFalse(AbuseReportSelectionPolicy.canSelect(candidates.last(), firstFour, candidates))
        assertEquals(
            4,
            AbuseReportSelectionPolicy.selectedPayloads(firstFour, candidates).size,
        )
    }

    @Test
    fun `group request sends target sender and couples plaintext to explicit consent`() {
        val context = checkNotNull(
            AbuseReportContext.create(
                CURRENT,
                groupChat(),
                listOf(
                    ChatMember(CURRENT, "You", isSelf = true),
                    ChatMember(REPORTED, "Emma"),
                ),
            ),
        )
        val target = AbuseReportTarget.MessageTarget(MESSAGE, REPORTED)
        val contextMessageId = uuid(499)
        val selected = AbuseReportSelectionPolicy.selectedPayloads(
            setOf(MESSAGE, contextMessageId),
            AbuseReportSelectionPolicy.candidates(
                listOf(
                    message(id = MESSAGE, text = "exact & unchanged", senderUserId = REPORTED),
                    message(
                        id = contextMessageId,
                        text = "my context",
                        fromMe = true,
                        senderUserId = CURRENT,
                    ),
                ),
                context,
                target,
            ),
        )
        val request = AbuseReportRequest.create(
            context,
            target,
            AbuseReportReason.HARASSMENT_OR_BULLYING,
            "  context  ",
            selected,
            shareSelectedMessagePlaintext = true,
        )

        assertEquals(REPORTED, request.reportedUserId)
        assertEquals("context", request.reporterNote)
        assertEquals(
            "exact & unchanged",
            request.selectedMessages.single { it.messageId == MESSAGE }.plaintext,
        )
        assertEquals(64, request.fingerprint().length)
        assertEquals(request.fingerprint(), request.copy().fingerprint())
        assertNotEquals(request.fingerprint(), request.copy(reporterNote = "changed").fingerprint())
        assertNotEquals(
            request.fingerprint(),
            request.copy(
                selectedMessages = request.selectedMessages.map {
                    if (it.messageId == contextMessageId) it.copy(senderUserId = REPORTED) else it
                },
            ).fingerprint(),
        )

        expectIllegalArgument {
            AbuseReportRequest.create(
                context,
                target,
                AbuseReportReason.SPAM,
                null,
                selected,
                shareSelectedMessagePlaintext = false,
            )
        }
        expectIllegalArgument {
            AbuseReportRequest.create(
                context,
                target,
                AbuseReportReason.SPAM,
                null,
                listOf(AbuseReportSelectedMessage(uuid(500), THIRD, "not theirs")),
                shareSelectedMessagePlaintext = true,
            )
        }
        expectIllegalArgument {
            AbuseReportRequest.create(
                context,
                target,
                AbuseReportReason.SPAM,
                null,
                listOf(AbuseReportSelectedMessage(MESSAGE, CURRENT, "forged target")),
                shareSelectedMessagePlaintext = true,
            )
        }
        expectIllegalArgument {
            AbuseReportRequest.create(
                context,
                AbuseReportTarget.MessageTarget(MESSAGE, OUTSIDER),
                AbuseReportReason.SPAM,
                null,
                emptyList(),
                shareSelectedMessagePlaintext = false,
            )
        }
    }

    private fun directChat(peerUserId: String = REPORTED) = ChatPreview(
        id = CONVERSATION,
        name = "Emma",
        lastMessage = "Hello",
        time = "10:00",
        peerUserId = peerUserId,
    )

    private fun groupChat() = ChatPreview(
        id = CONVERSATION,
        name = "Family",
        lastMessage = "Hello",
        time = "10:00",
        isGroup = true,
    )

    private fun message(
        id: String = MESSAGE,
        text: String = "hello",
        fromMe: Boolean = false,
        senderUserId: String? = REPORTED,
        senderName: String? = null,
        state: DeliveryState = DeliveryState.READ,
        kind: MessageKind = MessageKind.TEXT,
        sortEpochMillis: Long = 10,
    ) = Message(
        id = id,
        text = text,
        time = "10:00",
        fromMe = fromMe,
        senderName = senderName,
        state = state,
        kind = kind,
        sortEpochMillis = sortEpochMillis,
        senderUserId = senderUserId,
    )

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun uuid(value: Int): String = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private companion object {
        const val CURRENT = "11111111-1111-4111-8111-111111111111"
        const val REPORTED = "22222222-2222-4222-8222-222222222222"
        const val THIRD = "33333333-3333-4333-8333-333333333333"
        const val OUTSIDER = "44444444-4444-4444-8444-444444444444"
        const val CONVERSATION = "55555555-5555-4555-8555-555555555555"
        const val MESSAGE = "66666666-6666-4666-8666-666666666666"
    }
}
