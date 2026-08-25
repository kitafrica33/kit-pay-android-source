package com.kit.wallet

import com.kit.wallet.data.repository.applyRealtimeSignals
import com.kit.wallet.ui.model.ChatPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presence and typing folded onto the chat list: the decoration must never reorder rows, must
 * clear itself when a signal stops, and must only name typists for the conversation they are in.
 */
class RealtimeSignalDecorationTest {
    private val chats = listOf(
        ChatPreview("c1", "Amina", "hi", "10:00"),
        ChatPreview("c2", "Site team", "yo", "09:00", isGroup = true),
    )

    @Test
    fun `no signals at all leaves the list untouched`() {
        assertSame(
            chats,
            applyRealtimeSignals(chats, online = emptySet(), typing = emptySet()),
        )
    }

    @Test
    fun `presence and typing decorate in place without reordering`() {
        val decorated = applyRealtimeSignals(
            chats,
            online = setOf("c2"),
            typing = setOf("c2"),
        )

        assertEquals(listOf("c1", "c2"), decorated.map(ChatPreview::id))
        assertFalse(decorated[0].online)
        assertTrue(decorated[1].online)
        assertTrue(decorated[1].typing)
    }

    @Test
    fun `a group names its typists and a direct chat never does`() {
        val decorated = applyRealtimeSignals(
            chats,
            online = setOf("c1", "c2"),
            typing = setOf("c1", "c2"),
            typingNames = mapOf("c2" to listOf("Brian", "Grace")),
        )

        assertEquals(emptyList<String>(), decorated[0].typingNames)
        assertEquals(listOf("Brian", "Grace"), decorated[1].typingNames)
    }

    @Test
    fun `names go away with the typing they belonged to`() {
        val typing = applyRealtimeSignals(
            chats,
            online = setOf("c2"),
            typing = setOf("c2"),
            typingNames = mapOf("c2" to listOf("Brian")),
        )
        val stopped = applyRealtimeSignals(
            typing,
            online = setOf("c2"),
            typing = emptySet(),
            // A stale name for a conversation nobody is typing in is ignored rather than shown.
            typingNames = mapOf("c2" to listOf("Brian")),
        )

        assertFalse(stopped[1].typing)
        assertEquals(emptyList<String>(), stopped[1].typingNames)
        assertTrue(stopped[1].online)
    }

    @Test
    fun `losing every signal clears rows that were decorated before`() {
        val decorated = applyRealtimeSignals(
            chats,
            online = setOf("c1"),
            typing = setOf("c1"),
            typingNames = mapOf("c1" to listOf("Amina")),
        )
        val cleared = applyRealtimeSignals(decorated, online = emptySet(), typing = emptySet())

        assertFalse(cleared[0].online)
        assertFalse(cleared[0].typing)
        assertEquals(emptyList<String>(), cleared[0].typingNames)
    }
}
