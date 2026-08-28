package com.kit.wallet

import com.kit.wallet.feature.chat.ConversationFocusAction
import com.kit.wallet.feature.chat.ConversationFocusRequests
import com.kit.wallet.feature.chat.conversationFocusAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationFocusRequestsTest {

    @Before
    fun clearBefore() = ConversationFocusRequests.reset()

    @After
    fun clearAfter() = ConversationFocusRequests.reset()

    @Test
    fun `a request names its conversation and message, trimmed`() {
        assertTrue(ConversationFocusRequests.request(" chat-1 ", " msg-9 "))

        val request = ConversationFocusRequests.current.value
        assertEquals("chat-1", request?.conversationId)
        assertEquals("msg-9", request?.messageId)
    }

    @Test
    fun `blank ids publish nothing`() {
        assertFalse(ConversationFocusRequests.request("  ", "msg-9"))
        assertFalse(ConversationFocusRequests.request("chat-1", "  "))
        assertNull(ConversationFocusRequests.current.value)
    }

    @Test
    fun `the newest tap wins and repeat taps stay distinguishable`() {
        ConversationFocusRequests.request("chat-1", "msg-1")
        val first = ConversationFocusRequests.current.value
        ConversationFocusRequests.request("chat-1", "msg-1")
        val second = ConversationFocusRequests.current.value

        assertEquals(first?.messageId, second?.messageId)
        assertTrue((second?.token ?: 0L) > (first?.token ?: Long.MAX_VALUE))
    }

    @Test
    fun `consuming is exact, so a newer request survives a stale consumer`() {
        ConversationFocusRequests.request("chat-1", "msg-1")
        val stale = ConversationFocusRequests.current.value ?: error("missing request")
        ConversationFocusRequests.request("chat-2", "msg-2")
        val newer = ConversationFocusRequests.current.value ?: error("missing request")

        ConversationFocusRequests.consume(stale)
        assertEquals(newer, ConversationFocusRequests.current.value)

        ConversationFocusRequests.consume(newer)
        assertNull(ConversationFocusRequests.current.value)
    }

    @Test
    fun `only the named conversation may act on a request`() {
        ConversationFocusRequests.request("chat-1", "msg-1")
        val request = ConversationFocusRequests.current.value
        val rows = listOf(listOf("msg-1"))

        assertEquals(
            ConversationFocusAction.Ignore,
            conversationFocusAction(request, conversationId = "chat-2", rowMessageIds = rows),
        )
        assertEquals(
            ConversationFocusAction.Ignore,
            conversationFocusAction(request, conversationId = "", rowMessageIds = rows),
        )
        assertEquals(
            ConversationFocusAction.Ignore,
            conversationFocusAction(null, conversationId = "chat-1", rowMessageIds = rows),
        )
    }

    @Test
    fun `an owner without rows waits for its thread to load`() {
        ConversationFocusRequests.request("chat-1", "msg-1")

        assertEquals(
            ConversationFocusAction.Wait,
            conversationFocusAction(
                ConversationFocusRequests.current.value,
                conversationId = "chat-1",
                rowMessageIds = emptyList(),
            ),
        )
    }

    @Test
    fun `the jump lands on the row holding the message, even inside an album row`() {
        ConversationFocusRequests.request("chat-1", "msg-3")
        val rows = listOf(listOf("msg-1"), listOf("msg-2", "msg-3"), listOf("msg-4"))

        assertEquals(
            ConversationFocusAction.Jump(rowIndex = 1),
            conversationFocusAction(
                ConversationFocusRequests.current.value,
                conversationId = "chat-1",
                rowMessageIds = rows,
            ),
        )
    }

    @Test
    fun `a loaded thread without the message drops the request rather than guessing`() {
        ConversationFocusRequests.request("chat-1", "msg-9")

        assertEquals(
            ConversationFocusAction.Drop,
            conversationFocusAction(
                ConversationFocusRequests.current.value,
                conversationId = "chat-1",
                rowMessageIds = listOf(listOf("msg-1")),
            ),
        )
    }
}
