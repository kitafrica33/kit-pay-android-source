package com.kit.wallet

import com.kit.wallet.data.messaging.ScheduledSend
import com.kit.wallet.feature.chat.formatScheduledFor
import com.kit.wallet.feature.chat.scheduleSendPresets
import com.kit.wallet.feature.chat.scheduledSendEpochMillis
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleSendDialogTest {
    @Test fun `a tapped date and a tapped clock face become one instant in the device's zone`() {
        val date = LocalDate.of(2026, 8, 26)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        val at = scheduledSendEpochMillis(date, hour = 8, minute = 30, zone = NAIROBI)

        // 08:30 on the phone's clock, which is 05:30 UTC in Nairobi.
        assertEquals(Instant.parse("2026-08-26T05:30:00Z").toEpochMilli(), at)
    }

    @Test fun `the date cell is read as a date rather than an instant`() {
        // Material answers in UTC midnight; a zone behind UTC must still get the day that was tapped.
        val date = LocalDate.of(2026, 8, 26)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        val at = scheduledSendEpochMillis(date, hour = 9, minute = 0, zone = NEW_YORK)

        assertEquals(
            LocalDate.of(2026, 8, 26),
            Instant.ofEpochMilli(at).atZone(NEW_YORK).toLocalDate(),
        )
        assertEquals(9, Instant.ofEpochMilli(at).atZone(NEW_YORK).hour)
    }

    @Test fun `every preset is offered early in the day`() {
        val presets = scheduleSendPresets(local("2026-08-25T06:00"), NAIROBI)

        assertEquals(
            listOf("In an hour", "This evening", "Tomorrow morning", "Next Monday morning"),
            presets.map { it.label },
        )
        assertEquals(local("2026-08-25T07:00"), presets[0].atEpochMillis)
        assertEquals(local("2026-08-25T18:00"), presets[1].atEpochMillis)
        assertEquals(local("2026-08-26T08:00"), presets[2].atEpochMillis)
        // Today is a Tuesday, so the next Monday is the 31st.
        assertEquals(local("2026-08-31T08:00"), presets[3].atEpochMillis)
    }

    @Test fun `a preset that has already passed is not offered`() {
        val presets = scheduleSendPresets(local("2026-08-25T19:00"), NAIROBI)

        assertEquals(
            listOf("In an hour", "Tomorrow morning", "Next Monday morning"),
            presets.map { it.label },
        )
    }

    @Test fun `a preset too close to now is not offered either`() {
        // "This evening" at half a minute to six is a send, not a schedule.
        val presets = scheduleSendPresets(local("2026-08-25T17:59:30"), NAIROBI)

        assertTrue(presets.none { it.label == "This evening" })
    }

    @Test fun `on a Monday the next Monday is a week away`() {
        val presets = scheduleSendPresets(local("2026-08-31T06:00"), NAIROBI)

        val monday = presets.single { it.label == "Next Monday morning" }
        assertEquals(local("2026-09-07T08:00"), monday.atEpochMillis)
    }

    @Test fun `no preset is ever a time the queue would refuse`() {
        listOf(
            "2026-08-25T00:01",
            "2026-08-25T07:59",
            "2026-08-25T17:59",
            "2026-08-25T23:59",
        ).forEach { moment ->
            val now = local(moment)
            scheduleSendPresets(now, NAIROBI).forEach { preset ->
                assertNull(
                    "$moment offered ${preset.label}",
                    ScheduledSend.schedulingError(preset.atEpochMillis, now),
                )
            }
        }
    }

    @Test fun `near days are named and far ones are dated`() {
        val now = local("2026-08-25T09:00")

        assertEquals("Today 18:00", scheduledFor("2026-08-25T18:00", now))
        assertEquals("Tomorrow 08:00", scheduledFor("2026-08-26T08:00", now))
        assertEquals(
            "${weekday("2026-08-27T08:00")} 08:00",
            scheduledFor("2026-08-27T08:00", now),
        )
        assertEquals(
            "${weekday("2026-08-31T08:00")} 08:00",
            scheduledFor("2026-08-31T08:00", now),
        )
        assertEquals(
            "${dayMonth("2026-09-10T08:00")} 08:00",
            scheduledFor("2026-09-10T08:00", now),
        )
        assertEquals(
            "${dayMonthYear("2027-01-05T08:00")} 08:00",
            scheduledFor("2027-01-05T08:00", now),
        )
    }

    @Test fun `a minute before midnight is still today and a minute after is tomorrow`() {
        val now = local("2026-08-25T23:59")

        assertEquals("Today 23:59", scheduledFor("2026-08-25T23:59", now))
        assertEquals("Tomorrow 00:01", scheduledFor("2026-08-26T00:01", now))
    }

    private fun scheduledFor(moment: String, nowEpochMillis: Long): String =
        formatScheduledFor(local(moment), nowEpochMillis, NAIROBI)

    private fun local(moment: String): Long =
        LocalDateTime.parse(moment).atZone(NAIROBI).toInstant().toEpochMilli()

    private fun weekday(moment: String): String = format("EEE", moment)

    private fun dayMonth(moment: String): String = format("d MMM", moment)

    private fun dayMonthYear(moment: String): String = format("d MMM yyyy", moment)

    /** Locale is the device's, so the expectation is built the same way the label is. */
    private fun format(pattern: String, moment: String): String = DateTimeFormatter
        .ofPattern(pattern)
        .format(LocalDateTime.parse(moment))

    private companion object {
        val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }
}
