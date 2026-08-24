package com.kit.wallet

import com.kit.wallet.feature.chat.ConversationRow
import com.kit.wallet.feature.chat.IMAGE_GROUP_WINDOW_MILLIS
import com.kit.wallet.feature.chat.groupConversationRows
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageGroupingTest {
    private var counter = 0

    private fun image(
        fromMe: Boolean = true,
        at: Long = 0,
        caption: String = "Photo",
    ) = Message(
        id = "m${counter++}",
        text = caption,
        time = "10:0$counter",
        fromMe = fromMe,
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
    fun `single photos and non-image kinds never group and order is preserved`() {
        val input = listOf(image(at = 0), text(at = 1), image(at = 2), text(at = 3))
        val rows = groupConversationRows(input)

        assertEquals(4, rows.size)
        assertEquals(
            input.map(Message::id),
            rows.map { (it as ConversationRow.Single).message.id },
        )
    }
}
