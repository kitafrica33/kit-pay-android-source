package com.kit.wallet

import com.kit.wallet.data.notifications.CallRingLease
import com.kit.wallet.data.notifications.IncomingCallLaunchAuthorizer
import com.kit.wallet.data.notifications.IncomingCallLaunchPurpose
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallLaunchAuthorizationTest {
    private var elapsedRealtimeMillis = 10_000L
    private var bootId: Long? = 7L
    private val authorizer = IncomingCallLaunchAuthorizer(
        ElapsedRealtimeClock { elapsedRealtimeMillis },
        BootSessionIdProvider { bootId },
    )
    private val session = SessionFence("session-a", "cache-a", ACCOUNT_ID)

    @Test
    fun `valid open and answer grants preserve only their bound call purpose and lease`() {
        val open = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, lease())
        val answer = authorizer.issue(
            OTHER_CALL_ID,
            IncomingCallLaunchPurpose.ANSWER,
            session,
            lease(),
        )

        val opened = authorizer.consume(open, session)
        val answered = authorizer.consume(answer, session)

        assertEquals(CALL_ID, opened?.callId)
        assertEquals(lease(), opened?.ringLease)
        assertFalse(requireNotNull(opened).acceptRequested)
        assertEquals(OTHER_CALL_ID, answered?.callId)
        assertTrue(requireNotNull(answered).acceptRequested)
    }

    @Test
    fun `grant is one time and a wrong session consumes it fail closed`() {
        val token = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, lease())
        assertNull(authorizer.consume(token, SessionFence("session-b", "cache-a", ACCOUNT_ID)))
        assertNull(authorizer.consume(token, session))

        val oneTime = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, lease())
        assertEquals(CALL_ID, authorizer.consume(oneTime, session)?.callId)
        assertNull(authorizer.consume(oneTime, session))
    }

    @Test
    fun `expired rebooted rewound and malformed grants fail closed`() {
        val expired = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, lease())
        elapsedRealtimeMillis = 70_000L
        assertNull(authorizer.consume(expired, session))

        elapsedRealtimeMillis = 10_000L
        val rebooted = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, lease())
        bootId = 8L
        assertNull(authorizer.consume(rebooted, session))

        bootId = 7L
        val rewound = authorizer.issue(CALL_ID, IncomingCallLaunchPurpose.OPEN, session, lease())
        elapsedRealtimeMillis = 9_000L
        assertNull(authorizer.consume(rewound, session))
        assertNull(authorizer.consume("A".repeat(43), session))
        assertNull(authorizer.issue("not-a-call", IncomingCallLaunchPurpose.OPEN, session, lease()))
        assertNull(
            authorizer.issue(
                "00000000-0000-0000-8000-000000000000",
                IncomingCallLaunchPurpose.OPEN,
                session,
                lease(),
            ),
        )
    }

    private fun lease() = CallRingLease(
        sourceRingExpiresAt = RING_EXPIRY,
        bootSessionId = 7L,
        receivedElapsedRealtimeMillis = 10_000L,
        deadlineElapsedRealtimeMillis = 70_000L,
    )

    private companion object {
        const val CALL_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_CALL_ID = "22222222-2222-4222-8222-222222222222"
        const val ACCOUNT_ID = "33333333-3333-4333-8333-333333333333"
        const val RING_EXPIRY = "2026-08-28T12:01:30Z"
    }
}
