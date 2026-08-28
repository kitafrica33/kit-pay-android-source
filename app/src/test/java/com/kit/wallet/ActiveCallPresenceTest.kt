package com.kit.wallet

import com.kit.wallet.data.notifications.ActiveCallPresence
import com.kit.wallet.data.notifications.ActiveCallStateHolder
import com.kit.wallet.feature.calls.CallDurationAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCallPresenceTest {

    @Test
    fun `a call bound to a conversation matches exactly that thread`() {
        val presence = presence(conversationId = "conv-9")

        assertTrue(presence.matchesChat(chatId = "conv-9", isGroup = true, peerUserId = null))
        assertTrue(presence.matchesChat(chatId = "conv-9", isGroup = false, peerUserId = null))
        assertFalse(presence.matchesChat(chatId = "conv-8", isGroup = true, peerUserId = null))
    }

    @Test
    fun `a group chat never matches through the participant roster`() {
        val presence = presence(conversationId = null, participants = listOf("user-a", "user-b"))

        assertFalse(presence.matchesChat(chatId = "group-1", isGroup = true, peerUserId = "user-a"))
    }

    @Test
    fun `a direct chat matches its authenticated peer on the roster ignoring case`() {
        val presence = presence(conversationId = null, participants = listOf("User-A", "user-b"))

        assertTrue(presence.matchesChat(chatId = "direct-1", isGroup = false, peerUserId = "user-a"))
        assertFalse(presence.matchesChat(chatId = "direct-1", isGroup = false, peerUserId = "user-c"))
    }

    @Test
    fun `missing identifiers fail closed to not-this-chat`() {
        val presence = presence(conversationId = null, participants = listOf("user-a"))

        assertFalse(presence.matchesChat(chatId = "", isGroup = false, peerUserId = null))
        assertFalse(presence.matchesChat(chatId = "direct-1", isGroup = false, peerUserId = " "))
        assertFalse(presence.matchesChat(chatId = "direct-1", isGroup = false, peerUserId = null))
    }

    @Test
    fun `publishing presence keeps the active id and presence in step`() {
        val holder = ActiveCallStateHolder()
        val presence = presence(callId = "call-1")

        holder.publishPresence(presence)

        assertEquals("call-1", holder.activeCallId.value)
        assertEquals(presence, holder.presence.value)
    }

    @Test
    fun `clearing the active call drops its presence`() {
        val holder = ActiveCallStateHolder()
        holder.publishPresence(presence(callId = "call-1"))

        holder.setActiveCall(null)

        assertNull(holder.activeCallId.value)
        assertNull(holder.presence.value)
    }

    @Test
    fun `a different active call drops presence describing the old one`() {
        val holder = ActiveCallStateHolder()
        holder.publishPresence(presence(callId = "call-1"))

        holder.setActiveCall("call-2")

        assertEquals("call-2", holder.activeCallId.value)
        assertNull(holder.presence.value)
    }

    @Test
    fun `re-asserting the same call in a different case keeps its presence`() {
        val holder = ActiveCallStateHolder()
        val presence = presence(callId = "call-1")
        holder.publishPresence(presence)

        holder.setActiveCall("CALL-1")

        assertEquals(presence, holder.presence.value)
    }

    private fun presence(
        callId: String = "call-1",
        conversationId: String? = null,
        participants: List<String> = emptyList(),
    ) = ActiveCallPresence(
        callId = callId,
        name = "Ama",
        participantUserIds = participants,
        conversationId = conversationId,
        video = false,
        anchor = CallDurationAnchor(callId = callId, elapsedRealtimeAtAnswerMillis = 0L),
    )
}
