package com.kit.wallet

import com.kit.wallet.data.messaging.MessageReplyQuotes
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReplyQuotesTest {

    @Test
    fun `an answer quotes the words and the author of what it points at`() {
        val target = message("target", "the meeting moved to four", sender = "Grace")
        val answer = message("answer", "noted", replyTo = "target")

        val resolved = MessageReplyQuotes.resolve(listOf(target, answer)).last()

        assertEquals("the meeting moved to four", resolved.replyToText)
        assertEquals("Grace", resolved.replyToSenderName)
        assertTrue(!resolved.replyToFromMe)
    }

    @Test
    fun `answering my own message says so, so the quote can read You`() {
        val target = message("target", "on my way", fromMe = true)
        val answer = message("answer", "actually, ten minutes", fromMe = true, replyTo = "target")

        val resolved = MessageReplyQuotes.resolve(listOf(target, answer)).last()

        assertEquals("on my way", resolved.replyToText)
        assertTrue(resolved.replyToFromMe)
    }

    @Test
    fun `a photo quotes as a photo rather than as nothing`() {
        val target = message("target", "", sender = "Grace", kind = MessageKind.IMAGE)
        val answer = message("answer", "lovely", replyTo = "target")

        assertEquals("Photo", MessageReplyQuotes.resolve(listOf(target, answer)).last().replyToText)
    }

    @Test
    fun `a photo's own caption is what it quotes as`() {
        val target = message("target", "the north face", sender = "Grace", kind = MessageKind.IMAGE)
        val answer = message("answer", "lovely", replyTo = "target")

        assertEquals(
            "the north face",
            MessageReplyQuotes.resolve(listOf(target, answer)).last().replyToText,
        )
    }

    @Test
    fun `a target this device cannot read leaves the quote empty rather than inventing one`() {
        // History from before this installation, or a message its sender has since revoked.
        val answer = message("answer", "agreed", replyTo = "not-in-this-thread")

        val resolved = MessageReplyQuotes.resolve(listOf(answer)).single()

        assertNull(resolved.replyToText)
        assertNull(resolved.replyToSenderName)
        // The pointer itself survives, so the answer still knows what it was about if the target
        // arrives on a later page of history.
        assertEquals("not-in-this-thread", resolved.replyToMessageId)
    }

    @Test
    fun `a message that points at itself is not quoted inside itself`() {
        val looping = message("answer", "hello", replyTo = "answer")

        assertNull(MessageReplyQuotes.resolve(listOf(looping)).single().replyToText)
    }

    @Test
    fun `a thread with no answers in it is handed back untouched`() {
        val thread = listOf(message("one", "hello"), message("two", "hello again"))

        assertSame(thread, MessageReplyQuotes.resolve(thread))
    }

    @Test
    fun `every answer in a thread is resolved, not just the first`() {
        val thread = listOf(
            message("first", "one", sender = "Grace"),
            message("second", "two", sender = "Ayo"),
            message("answer-to-first", "re one", replyTo = "first"),
            message("answer-to-second", "re two", replyTo = "second"),
        )

        val resolved = MessageReplyQuotes.resolve(thread)

        assertEquals("one", resolved[2].replyToText)
        assertEquals("Grace", resolved[2].replyToSenderName)
        assertEquals("two", resolved[3].replyToText)
        assertEquals("Ayo", resolved[3].replyToSenderName)
    }

    private fun message(
        id: String,
        text: String,
        sender: String? = null,
        fromMe: Boolean = false,
        kind: MessageKind = MessageKind.TEXT,
        replyTo: String? = null,
    ) = Message(
        id = id,
        text = text,
        time = "12:00",
        fromMe = fromMe,
        state = DeliveryState.DELIVERED,
        kind = kind,
        senderName = sender,
        replyToMessageId = replyTo,
    )
}
