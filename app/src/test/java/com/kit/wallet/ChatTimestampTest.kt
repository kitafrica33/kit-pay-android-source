package com.kit.wallet

import com.kit.wallet.data.repository.AuthenticatedProjectedText
import com.kit.wallet.data.repository.AuthenticatedTextDeliveryState
import com.kit.wallet.data.repository.authenticatedProjectionOrder
import com.kit.wallet.data.repository.formatChatTime
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimestampTest {
    @Test fun `chat display converts the UTC instant into Kampala device time`() {
        val instant = Instant.parse("2026-08-26T10:15:00Z")

        assertEquals("10:15", formatChatTime(instant, ZoneId.of("UTC")))
        assertEquals("13:15", formatChatTime(instant, ZoneId.of("Africa/Kampala")))
    }

    @Test fun `chat display follows New York daylight saving transitions`() {
        val winter = Instant.parse("2026-01-15T12:00:00Z")
        val summer = Instant.parse("2026-07-15T12:00:00Z")
        val newYork = ZoneId.of("America/New_York")

        assertEquals("07:00", formatChatTime(winter, newYork))
        assertEquals("08:00", formatChatTime(summer, newYork))
    }

    @Test fun `projection ordering is identical in every display zone`() {
        val later = projected(ID_TWO, Instant.parse("2026-08-26T10:15:01Z"))
        val earlier = projected(ID_ONE, Instant.parse("2026-08-26T10:15:00Z"))
        val input = listOf(later, earlier)

        val utc = input.sortedWith(authenticatedProjectionOrder)
            .map(AuthenticatedProjectedText::messageId)
        input.forEach { formatChatTime(it.sentAt, ZoneId.of("Africa/Kampala")) }
        val kampala = input.sortedWith(authenticatedProjectionOrder)
            .map(AuthenticatedProjectedText::messageId)
        input.forEach { formatChatTime(it.sentAt, ZoneId.of("America/New_York")) }
        val newYork = input.sortedWith(authenticatedProjectionOrder)
            .map(AuthenticatedProjectedText::messageId)

        assertEquals(listOf(ID_ONE, ID_TWO), utc)
        assertEquals(utc, kampala)
        assertEquals(utc, newYork)
    }

    private fun projected(id: String, sentAt: Instant) = AuthenticatedProjectedText(
        recordKey = "outbox:$id",
        messageId = id,
        serverMessageId = id,
        clientMessageId = id,
        conversationId = CONVERSATION_ID,
        senderUserId = USER_ID,
        fromCurrentUser = true,
        text = "message",
        sentAt = sentAt,
        deliveryState = AuthenticatedTextDeliveryState.SENT,
    )

    private companion object {
        const val ID_ONE = "10000000-0000-4000-8000-000000000001"
        const val ID_TWO = "10000000-0000-4000-8000-000000000002"
        const val CONVERSATION_ID = "20000000-0000-4000-8000-000000000001"
        const val USER_ID = "30000000-0000-4000-8000-000000000001"
    }
}
