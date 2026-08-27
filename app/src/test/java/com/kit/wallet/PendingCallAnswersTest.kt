package com.kit.wallet

import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleKind
import com.kit.wallet.feature.calls.PendingCallAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer that keeps an answer which arrived before `POST /calls` said which call it was.
 *
 * The event bus replays nothing on purpose, so without this the fastest possible answer —
 * one that beats its own start response back — is the one that gets dropped.
 */
class PendingCallAnswersTest {
    @Test
    fun `an answer that beats the start response is claimed by it`() {
        val pending = PendingCallAnswers()

        assertTrue(pending.remember(answered(CALL_ID)))
        val claimed = pending.claim(CALL_ID)

        assertEquals(CALL_ID, claimed?.callId)
        assertEquals("2026-08-27T09:15:04Z", claimed?.answeredAt)
        assertEquals("2026-08-27T09:15:05Z", claimed?.serverTime)
    }

    @Test
    fun `an answer is applied once and never again`() {
        val pending = PendingCallAnswers()
        pending.remember(answered(CALL_ID))

        assertEquals(CALL_ID, pending.claim(CALL_ID)?.callId)
        assertNull(pending.claim(CALL_ID))
        assertEquals(0, pending.size)
    }

    @Test
    fun `the response for one call never claims another call's answer`() {
        val pending = PendingCallAnswers()
        pending.remember(answered(CALL_ID))

        assertNull(pending.claim(OTHER_CALL_ID))
        assertEquals(1, pending.size)
        assertEquals(CALL_ID, pending.claim(CALL_ID)?.callId)
    }

    @Test
    fun `only answers are held`() {
        // Ends, declines and misses all name a call this screen could not identify, so
        // there is nothing for a later response to do with them. Holding them would mean
        // a call could be torn down by an event that was never about it.
        val pending = PendingCallAnswers()

        assertFalse(pending.remember(answered(CALL_ID).copy(kind = CallLifecycleKind.ENDED)))
        assertFalse(pending.remember(answered(CALL_ID).copy(kind = CallLifecycleKind.DECLINED)))
        assertFalse(pending.remember(answered(CALL_ID).copy(kind = CallLifecycleKind.MISSED)))
        assertEquals(0, pending.size)
        assertNull(pending.claim(CALL_ID))
    }

    @Test
    fun `an answer without a call id is not held`() {
        val pending = PendingCallAnswers()

        assertFalse(pending.remember(answered("")))
        assertFalse(pending.remember(answered("   ")))
        assertEquals(0, pending.size)
    }

    @Test
    fun `a new call forgets everything the previous one left behind`() {
        val pending = PendingCallAnswers()
        pending.remember(answered(CALL_ID))

        pending.clear()

        assertEquals(0, pending.size)
        assertNull(pending.claim(CALL_ID))
    }

    @Test
    fun `the buffer is bounded and discards the oldest unclaimed answer`() {
        // It is fed by the network, so it cannot grow without limit while a screen sits
        // waiting for a response that may never come.
        val pending = PendingCallAnswers()
        val ids = (1..9).map { id(it) }

        ids.forEach { pending.remember(answered(it)) }

        assertEquals(8, pending.size)
        assertNull(pending.claim(ids.first()))
        ids.drop(1).forEach { assertEquals(it, pending.claim(it)?.callId) }
    }

    @Test
    fun `a repeated answer keeps its place rather than ageing out behind newer ones`() {
        // Both routes carry the same answer, so a duplicate is the normal case, not a bug.
        // Treating it as fresh is what stops the socket frame and the push between them
        // pushing the answer this screen is actually waiting for out of the buffer.
        val pending = PendingCallAnswers()
        val ids = (1..8).map { id(it) }
        ids.forEach { pending.remember(answered(it)) }

        pending.remember(answered(ids.first()))
        pending.remember(answered(id(9)))

        assertEquals(8, pending.size)
        assertEquals(ids.first(), pending.claim(ids.first())?.callId)
        assertNull(pending.claim(ids[1]))
    }

    @Test
    fun `the last answer for a call is the one claimed`() {
        val pending = PendingCallAnswers()
        pending.remember(answered(CALL_ID, serverTime = "2026-08-27T09:15:05Z"))
        pending.remember(answered(CALL_ID, serverTime = "2026-08-27T09:15:07Z"))

        assertEquals(1, pending.size)
        assertEquals("2026-08-27T09:15:07Z", pending.claim(CALL_ID)?.serverTime)
    }

    @Test
    fun `a claim matches its answer whatever case either id arrived in`() {
        // The claim carries the id verbatim from the start response, while the buffered
        // event's id has been through canonical validation. They are the same call, and a
        // case difference between the two forms must never cost the caller its answer.
        val pending = PendingCallAnswers()
        pending.remember(answered(LETTERED_CALL_ID))

        assertEquals(LETTERED_CALL_ID, pending.claim(LETTERED_CALL_ID.uppercase())?.callId)
        assertEquals(0, pending.size)

        pending.remember(answered(LETTERED_CALL_ID.uppercase()))

        assertEquals(1, pending.size)
        assertEquals(
            LETTERED_CALL_ID.uppercase(),
            pending.claim(LETTERED_CALL_ID)?.callId,
        )
    }

    private fun answered(
        callId: String,
        serverTime: String? = "2026-08-27T09:15:05Z",
    ) = CallLifecycleEvent(
        callId = callId,
        kind = CallLifecycleKind.ANSWERED,
        state = "active",
        answeredAt = "2026-08-27T09:15:04Z",
        serverTime = serverTime,
    )

    private fun id(sequence: Int): String =
        "33333333-3333-4333-8333-${sequence.toString().padStart(12, '0')}"

    private companion object {
        const val CALL_ID = "33333333-3333-4333-8333-333333333333"
        const val OTHER_CALL_ID = "44444444-4444-4444-8444-444444444444"
        // Alphabetic hex digits on purpose, so uppercasing it actually changes the string.
        const val LETTERED_CALL_ID = "a3b3c3d3-e3f3-4333-8333-333333333333"
    }
}
