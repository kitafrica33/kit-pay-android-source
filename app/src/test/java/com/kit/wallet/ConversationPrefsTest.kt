package com.kit.wallet

import com.kit.wallet.data.local.ConversationPrefEntity
import com.kit.wallet.data.repository.applyConversationPrefs
import com.kit.wallet.ui.model.ChatPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrefsTest {
    private val recentFirst = listOf(
        ChatPreview("c1", "Amina", "hi", "10:00"),
        ChatPreview("c2", "Brian", "yo", "09:00"),
        ChatPreview("c3", "Grace", "ok", "08:00"),
    )

    @Test
    fun `pinned chats float to the top preserving recency inside each group`() {
        val decorated = applyConversationPrefs(
            recentFirst,
            listOf(ConversationPrefEntity("c3", pinned = true)),
        )

        assertEquals(listOf("c3", "c1", "c2"), decorated.map(ChatPreview::id))
        assertTrue(decorated.first().pinned)
        assertFalse(decorated[1].pinned)
    }

    @Test
    fun `mute decorates without reordering and no prefs is a no-op`() {
        assertEquals(recentFirst, applyConversationPrefs(recentFirst, emptyList()))

        val decorated = applyConversationPrefs(
            recentFirst,
            listOf(ConversationPrefEntity("c2", muted = true)),
        )
        assertEquals(listOf("c1", "c2", "c3"), decorated.map(ChatPreview::id))
        assertTrue(decorated[1].muted)
    }
}
