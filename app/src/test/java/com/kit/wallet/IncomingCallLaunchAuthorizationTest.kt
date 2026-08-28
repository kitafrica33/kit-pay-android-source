package com.kit.wallet

import com.kit.wallet.data.notifications.IncomingCallLaunchAuthorizer
import com.kit.wallet.data.notifications.IncomingCallLaunchPurpose
import com.kit.wallet.data.session.SessionFence
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallLaunchAuthorizationTest {
    private val clock = MutableClock(Instant.parse("2026-08-28T12:00:00Z"))
    private val authorizer = IncomingCallLaunchAuthorizer(clock)
    private val session = SessionFence("session-a", "cache-a", ACCOUNT_ID)

    @Test
    fun `valid open and answer grants preserve only their bound call and purpose`() {
        val open = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, RING_EXPIRY)
        val answer = authorizer.issue(OTHER_CALL_ID, IncomingCallLaunchPurpose.ANSWER, session, RING_EXPIRY)

        val opened = authorizer.consume(open, session)
        val answered = authorizer.consume(answer, session)

        assertEquals(CALL_ID, opened?.callId)
        assertFalse(requireNotNull(opened).acceptRequested)
        assertEquals(OTHER_CALL_ID, answered?.callId)
        assertTrue(requireNotNull(answered).acceptRequested)
    }

    @Test
    fun `grant is one time and a wrong session consumes it fail closed`() {
        val token = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, RING_EXPIRY)
        assertNull(authorizer.consume(token, SessionFence("session-b", "cache-a", ACCOUNT_ID)))
        assertNull(authorizer.consume(token, session))

        val oneTime = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, RING_EXPIRY)
        assertEquals(CALL_ID, authorizer.consume(oneTime, session)?.callId)
        assertNull(authorizer.consume(oneTime, session))
    }

    @Test
    fun `expired malformed wrong call and invalid ring grants fail closed`() {
        val expired = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, RING_EXPIRY)
        clock.advance(Duration.ofSeconds(61))
        assertNull(authorizer.consume(expired, session))
        assertNull(authorizer.consume("A".repeat(43), session))
        assertNull(authorizer.issue("not-a-call", IncomingCallLaunchPurpose.OPEN, session, RING_EXPIRY))
        assertNull(
            authorizer.issue(
                "00000000-0000-0000-8000-000000000000",
                IncomingCallLaunchPurpose.OPEN,
                session,
                RING_EXPIRY,
            ),
        )
        assertNull(
            authorizer.issue(
                CALL_ID,
                IncomingCallLaunchPurpose.OPEN,
                session,
                "2026-08-28T11:59:59Z",
            ),
        )
    }

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }

    private companion object {
        const val CALL_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_CALL_ID = "22222222-2222-4222-8222-222222222222"
        const val ACCOUNT_ID = "33333333-3333-4333-8333-333333333333"
        const val RING_EXPIRY = "2026-08-28T12:01:30Z"
    }
}
