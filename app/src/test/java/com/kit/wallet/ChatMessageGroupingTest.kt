package com.kit.wallet

import com.kit.wallet.data.repository.AbuseReportContext
import com.kit.wallet.data.repository.AbuseReportSelectionPolicy
import com.kit.wallet.feature.chat.ConversationRow
import com.kit.wallet.feature.chat.IMAGE_GROUP_WINDOW_MILLIS
import com.kit.wallet.feature.chat.groupConversationRows
import com.kit.wallet.feature.chat.reportCurrentGalleryMessage
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageGroupingTest {
    private var counter = 0

    private fun image(
        fromMe: Boolean = true,
        at: Long = 0,
        caption: String = "Photo",
        senderName: String? = null,
    ) = Message(
        id = "m${counter++}",
        text = caption,
        time = "10:0$counter",
        fromMe = fromMe,
        senderName = senderName,
        kind = MessageKind.IMAGE,
        sortEpochMillis = at,
    )

    private fun text(fromMe: Boolean = true, at: Long = 0) = Message(
        id = "m${counter++}",
        text = "hello",
        time = "10:0$counter",
        fromMe = fromMe,
        kind = MessageKind.TEXT,
        sortEpochMillis = at,
    )

    @Test
    fun `consecutive captionless photos from one sender group together`() {
        val rows = groupConversationRows(
            listOf(text(at = 0), image(at = 1_000), image(at = 2_000), image(at = 3_000), text(at = 4_000)),
        )

        assertEquals(3, rows.size)
        assertTrue(rows[0] is ConversationRow.Single)
        assertEquals(3, (rows[1] as ConversationRow.ImageGroup).messages.size)
        assertTrue(rows[2] is ConversationRow.Single)
    }

    @Test
    fun `sender changes captions and time gaps break groups`() {
        val rows = groupConversationRows(
            listOf(
                image(fromMe = true, at = 0),
                image(fromMe = true, at = 1_000),
                image(fromMe = false, at = 2_000),
                image(fromMe = false, at = 2_000 + IMAGE_GROUP_WINDOW_MILLIS + 1),
                image(fromMe = false, at = 2_000 + IMAGE_GROUP_WINDOW_MILLIS + 2, caption = "look!"),
            ),
        )

        // pair from me; two singles from the peer split by the time gap; a captioned single.
        assertEquals(4, rows.size)
        assertEquals(2, (rows[0] as ConversationRow.ImageGroup).messages.size)
        assertTrue(rows[1] is ConversationRow.Single)
        assertTrue(rows[2] is ConversationRow.Single)
        assertEquals("look!", (rows[3] as ConversationRow.Single).message.text)
    }

    @Test
    fun `two group members' photos never share one grid`() {
        // Both are incoming, both are captionless, both are seconds apart: only the author name
        // tells them apart, and a grid carries exactly one author label.
        val rows = groupConversationRows(
            listOf(
                image(fromMe = false, at = 0, senderName = "Brian"),
                image(fromMe = false, at = 1_000, senderName = "Brian"),
                image(fromMe = false, at = 2_000, senderName = "Grace"),
                image(fromMe = false, at = 3_000, senderName = "Grace"),
            ),
        )

        assertEquals(2, rows.size)
        val first = rows[0] as ConversationRow.ImageGroup
        val second = rows[1] as ConversationRow.ImageGroup
        assertEquals("Brian", first.messages.first().senderName)
        assertEquals(2, first.messages.size)
        assertEquals("Grace", second.messages.first().senderName)
        assertEquals(2, second.messages.size)
    }

    @Test
    fun `single photos and non-image kinds never group and order is preserved`() {
        val input = listOf(image(at = 0), text(at = 1), image(at = 2), text(at = 3))
        val rows = groupConversationRows(input)

        assertEquals(4, rows.size)
        assertEquals(
            input.map(Message::id),
            rows.map { (it as ConversationRow.Single).message.id },
        )
    }

    @Test
    fun `a hidden grouped photo reports the exact gallery page`() {
        val context = checkNotNull(
            AbuseReportContext.create(
                CURRENT,
                ChatPreview(CONVERSATION, "Grace", "", "", peerUserId = REPORTED),
            ),
        )
        val group = (0 until 6).map { index ->
            image(fromMe = false, at = index.toLong()).copy(
                id = uuid(index),
                senderUserId = REPORTED,
            )
        }
        assertEquals(6, (groupConversationRows(group).single() as ConversationRow.ImageGroup).messages.size)
        val hidden = group[5]
        val reportableIds = group.mapNotNull {
            AbuseReportSelectionPolicy.messageTarget(it, context)?.messageId
        }.toSet()
        var reported: Message? = null

        assertTrue(
            reportCurrentGalleryMessage(
                mediaMessages = group,
                currentPage = 5,
                reportableMessageIds = reportableIds,
                onReportMessage = { reported = it },
            ),
        )
        assertEquals(hidden, reported)
        assertFalse(
            reportCurrentGalleryMessage(group, 5, emptySet()) { reported = it },
        )
        assertFalse(
            reportCurrentGalleryMessage(group, group.size, reportableIds) { reported = it },
        )
    }

    private fun uuid(value: Int): String =
        "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private companion object {
        const val CURRENT = "11111111-1111-4111-8111-111111111111"
        const val REPORTED = "22222222-2222-4222-8222-222222222222"
        const val CONVERSATION = "55555555-5555-4555-8555-555555555555"
    }
}
