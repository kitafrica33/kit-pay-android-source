package com.kit.wallet

import com.kit.wallet.data.messaging.ScheduledSend
import com.kit.wallet.data.messaging.ScheduledSendKind
import com.kit.wallet.data.messaging.ScheduledSendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledSendTest {
    @Test fun `a scheduled message round trips through its record form`() {
        val send = textSend(text = "meet me at 8 & bring the receipt = ok? 100% ☕")

        val parsed = ScheduledSend.parse(send.encode())

        assertEquals(send, parsed)
    }

    @Test fun `a scheduled payment request round trips with and without a note`() {
        val withNote = requestSend(note = "rent for August")
        val withoutNote = requestSend(note = null)

        assertEquals(withNote, ScheduledSend.parse(withNote.encode()))
        assertEquals(withoutNote, ScheduledSend.parse(withoutNote.encode()))
    }

    @Test fun `every attempt field survives the round trip`() {
        val send = textSend().copy(
            state = ScheduledSendState.SENDING,
            attempts = 3,
            claimedAtEpochMillis = 1_700_000_111_000L,
            lastAttemptAtEpochMillis = 1_700_000_222_000L,
        )

        assertEquals(send, ScheduledSend.parse(send.encode()))
    }

    @Test fun `only the canonical encoding parses`() {
        val encoded = textSend(text = "pay day").encode()

        // Prefix, version, unknown fields, duplicates, reordering and alternate escaping are all
        // rejected outright rather than half-understood.
        assertNull(ScheduledSend.parse(encoded.removePrefix(ScheduledSend.PREFIX)))
        assertNull(ScheduledSend.parse(encoded.replace("v=1", "v=2")))
        assertNull(ScheduledSend.parse("$encoded&extra=1"))
        assertNull(ScheduledSend.parse("$encoded&v=1"))
        assertNull(ScheduledSend.parse(ScheduledSend.PREFIX + "id=$ID&v=1"))
        assertNull(ScheduledSend.parse(textSend(text = "pay day").encode().replace("%20", "+")))
        assertNull(ScheduledSend.parse(ScheduledSend.PREFIX + "v=1&id&cid=$CONVERSATION_ID"))
        assertNull(ScheduledSend.parse("KITSCHED9:v=1"))
    }

    @Test fun `an oversized record is refused before it is parsed`() {
        assertNull(ScheduledSend.parse(ScheduledSend.PREFIX + "v=1&x=".repeat(4_000)))
    }

    @Test fun `a text send cannot carry payment fields and a request cannot carry text`() {
        assertThrowsIllegalArgument { textSend(text = "  ") }
        assertThrowsIllegalArgument { textSend(text = "x".repeat(ScheduledSend.MAX_TEXT_LENGTH + 1)) }
        assertThrowsIllegalArgument { textSend().copy(amountMinor = 1) }
        assertThrowsIllegalArgument { textSend().copy(note = "note") }
        assertThrowsIllegalArgument { requestSend().copy(text = "hello") }
        assertThrowsIllegalArgument { requestSend().copy(amountMinor = 0) }
        assertThrowsIllegalArgument { requestSend().copy(amountMinor = -1) }
        assertThrowsIllegalArgument { requestSend(note = " ") }
        assertThrowsIllegalArgument {
            requestSend(note = "n".repeat(ScheduledSend.MAX_NOTE_LENGTH + 1))
        }
    }

    @Test fun `identity and timing inputs are bounded`() {
        assertThrowsIllegalArgument { textSend().copy(id = "not-a-uuid") }
        assertThrowsIllegalArgument { textSend().copy(id = ID.uppercase()) }
        assertThrowsIllegalArgument { textSend().copy(conversationId = "../escape") }
        assertThrowsIllegalArgument { textSend().copy(conversationId = "") }
        assertThrowsIllegalArgument { textSend().copy(scheduledAtEpochMillis = 0) }
        assertThrowsIllegalArgument { textSend().copy(createdAtEpochMillis = 0) }
        assertThrowsIllegalArgument { textSend().copy(attempts = -1) }
        assertThrowsIllegalArgument { textSend().copy(claimedAtEpochMillis = -1) }
        assertThrowsIllegalArgument { textSend().copy(lastAttemptAtEpochMillis = -1) }
    }

    @Test fun `a text send is due only once its time has passed`() {
        val send = textSend().copy(scheduledAtEpochMillis = NOW + 60_000L)

        assertFalse(send.isDue(NOW))
        assertFalse(send.isDue(NOW + 59_999L))
        assertTrue(send.isDue(NOW + 60_000L))
        // A claimed item is never due to anybody else, however long ago its time was.
        assertFalse(send.copy(state = ScheduledSendState.SENDING).isDue(NOW + 600_000L))
        assertFalse(send.copy(state = ScheduledSendState.UNCONFIRMED).isDue(NOW + 600_000L))
    }

    @Test fun `a claim only goes stale after the grace period`() {
        val claimed = textSend().copy(
            state = ScheduledSendState.SENDING,
            claimedAtEpochMillis = NOW,
        )

        assertFalse(claimed.claimIsStale(NOW))
        assertFalse(claimed.claimIsStale(NOW + ScheduledSend.STALE_CLAIM_MILLIS - 1))
        assertTrue(claimed.claimIsStale(NOW + ScheduledSend.STALE_CLAIM_MILLIS))
        assertFalse(textSend().claimIsStale(NOW + 10 * ScheduledSend.STALE_CLAIM_MILLIS))
    }

    @Test fun `backoff grows and then holds at an hour`() {
        assertEquals(0L, ScheduledSend.backoffMillis(0))
        assertEquals(0L, ScheduledSend.backoffMillis(-1))
        assertEquals(60_000L, ScheduledSend.backoffMillis(1))
        assertEquals(240_000L, ScheduledSend.backoffMillis(2))
        assertEquals(900_000L, ScheduledSend.backoffMillis(3))
        assertEquals(3_600_000L, ScheduledSend.backoffMillis(4))
        assertEquals(3_600_000L, ScheduledSend.backoffMillis(40))
    }

    @Test fun `the next eligible time follows the last attempt rather than the send time`() {
        val waiting = textSend().copy(scheduledAtEpochMillis = NOW)

        assertEquals(NOW, waiting.nextEligibleAtEpochMillis())
        assertEquals(
            NOW + 300_000L + 60_000L,
            waiting.attempted(NOW + 300_000L).nextEligibleAtEpochMillis(),
        )
        assertEquals(
            NOW + ScheduledSend.STALE_CLAIM_MILLIS,
            waiting.copy(state = ScheduledSendState.SENDING, claimedAtEpochMillis = NOW)
                .nextEligibleAtEpochMillis(),
        )
        // Nothing wakes for an item that is waiting on a person.
        assertEquals(
            Long.MAX_VALUE,
            waiting.copy(state = ScheduledSendState.UNCONFIRMED).nextEligibleAtEpochMillis(),
        )
    }

    @Test fun `an attempt releases the claim and counts against the backoff`() {
        val claimed = textSend().copy(
            state = ScheduledSendState.SENDING,
            attempts = 1,
            claimedAtEpochMillis = NOW,
        )

        val released = claimed.attempted(NOW + 1_000L)

        assertEquals(ScheduledSendState.WAITING, released.state)
        assertEquals(2, released.attempts)
        assertEquals(0L, released.claimedAtEpochMillis)
        assertEquals(NOW + 1_000L, released.lastAttemptAtEpochMillis)
    }

    @Test fun `the picker and the queue agree on what later means`() {
        assertNull(ScheduledSend.schedulingError(NOW + ScheduledSend.MIN_LEAD_MILLIS, NOW))
        assertNull(ScheduledSend.schedulingError(NOW + ScheduledSend.MAX_HORIZON_MILLIS, NOW))
        assertEquals(
            "Pick a time at least a minute from now.",
            ScheduledSend.schedulingError(NOW + ScheduledSend.MIN_LEAD_MILLIS - 1, NOW),
        )
        assertEquals(
            "Pick a time at least a minute from now.",
            ScheduledSend.schedulingError(NOW - 1, NOW),
        )
        assertEquals(
            "Pick a time within the next year.",
            ScheduledSend.schedulingError(NOW + ScheduledSend.MAX_HORIZON_MILLIS + 1, NOW),
        )
    }

    @Test fun `a note of exactly the maximum length is accepted`() {
        val note = "n".repeat(ScheduledSend.MAX_NOTE_LENGTH)

        assertNotNull(ScheduledSend.parse(requestSend(note = note).encode()))
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    private fun textSend(text: String = "see you at six") = ScheduledSend(
        id = ID,
        conversationId = CONVERSATION_ID,
        kind = ScheduledSendKind.TEXT,
        scheduledAtEpochMillis = NOW,
        createdAtEpochMillis = NOW - 1,
        text = text,
    )

    private fun requestSend(note: String? = null) = ScheduledSend(
        id = ID,
        conversationId = CONVERSATION_ID,
        kind = ScheduledSendKind.PAYMENT_REQUEST,
        scheduledAtEpochMillis = NOW,
        createdAtEpochMillis = NOW - 1,
        amountMinor = 250_000,
        note = note,
    )

    private companion object {
        const val ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0001"
        const val CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0009"
        const val NOW = 1_766_000_000_000L
    }
}
