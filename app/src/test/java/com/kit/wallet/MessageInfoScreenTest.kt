package com.kit.wallet

import com.kit.wallet.feature.chat.furthestReached
import com.kit.wallet.feature.chat.messageDeliveryMomentLabel
import com.kit.wallet.ui.model.MessageDeliveryInfo
import com.kit.wallet.ui.model.MessageDeliveryPerson
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the message-info screen words what the server witnessed. */
class MessageInfoScreenTest {

    @Test
    fun `a moment from today is said as today`() {
        val label = messageDeliveryMomentLabel(
            epochMillis = moment("2026-07-19T09:15:00Z"),
            now = Instant.parse("2026-07-19T20:00:00Z"),
            zoneId = ZONE,
        )

        assertEquals("Today at 9:15 AM", label)
    }

    @Test
    fun `a moment from the day before is said as yesterday`() {
        val label = messageDeliveryMomentLabel(
            epochMillis = moment("2026-07-18T22:40:00Z"),
            now = Instant.parse("2026-07-19T08:00:00Z"),
            zoneId = ZONE,
        )

        assertEquals("Yesterday at 10:40 PM", label)
    }

    @Test
    fun `an older moment carries its date, because nobody counts back that far`() {
        val label = messageDeliveryMomentLabel(
            epochMillis = moment("2026-07-02T14:05:00Z"),
            now = Instant.parse("2026-07-19T08:00:00Z"),
            zoneId = ZONE,
        )

        // Never "17 days ago": somebody opens this screen precisely to avoid the arithmetic.
        assertTrue(label, label.contains("2026"))
        assertTrue(label, label.endsWith("at 2:05 PM"))
    }

    @Test
    fun `the day is read in the reader's own zone, not the server's`() {
        // 00:30 UTC on the 19th is still the evening of the 18th in Los Angeles, and the person
        // reading this remembers it as last night.
        val label = messageDeliveryMomentLabel(
            epochMillis = moment("2026-07-19T00:30:00Z"),
            now = Instant.parse("2026-07-19T20:00:00Z"),
            zoneId = ZoneId.of("America/Los_Angeles"),
        )

        assertTrue(label, label.startsWith("Yesterday at "))
    }

    @Test
    fun `one line says the furthest a message got with each person`() {
        assertTrue(
            furthestReached(person(deliveredAt = "2026-07-19T09:00:00Z", readAt = null))
                .startsWith("Delivered · "),
        )
        assertTrue(
            furthestReached(
                person(deliveredAt = "2026-07-19T09:00:00Z", readAt = "2026-07-19T09:04:00Z"),
            ).startsWith("Read · "),
        )
        assertEquals(
            "Not delivered yet",
            furthestReached(person(deliveredAt = null, readAt = null)),
        )
    }

    @Test
    fun `a group counts only the people who actually opened it`() {
        val info = MessageDeliveryInfo(
            messageId = MESSAGE_ID,
            sentAtEpochMillis = moment("2026-07-19T08:00:00Z"),
            recipients = listOf(
                person(deliveredAt = "2026-07-19T08:01:00Z", readAt = "2026-07-19T08:05:00Z"),
                person(deliveredAt = "2026-07-19T08:02:00Z", readAt = null),
                person(deliveredAt = null, readAt = null),
            ),
        )

        assertEquals(1, info.readCount)
        assertEquals(2, info.deliveredCount)
    }

    private fun person(deliveredAt: String?, readAt: String?) = MessageDeliveryPerson(
        userId = "11111111-1111-4111-8111-111111111111",
        name = "Grace",
        avatarUrl = null,
        deliveredAtEpochMillis = deliveredAt?.let(::moment) ?: 0L,
        readAtEpochMillis = readAt?.let(::moment) ?: 0L,
    )

    private fun moment(text: String): Long = Instant.parse(text).toEpochMilli()

    private companion object {
        const val MESSAGE_ID = "88888888-8888-4888-8888-888888888888"
        val ZONE: ZoneId = ZoneId.of("UTC")
    }
}
