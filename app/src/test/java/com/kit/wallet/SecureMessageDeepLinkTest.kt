package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessageNavigationAuthorizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageDeepLinkTest {
    private val clock = MutableClock(Instant.parse("2026-07-20T12:00:00Z"))
    private val authorizer = SecureMessageNavigationAuthorizer(clock)

    @Test
    fun `notification authority is one time and bound to the current session epoch`() {
        val token = authorizer.issue(CONVERSATION_ID, "session-one")

        assertEquals(CONVERSATION_ID, authorizer.consume(token, "session-one"))
        assertNull(authorizer.consume(token, "session-one"))

        val wrongEpoch = authorizer.issue(CONVERSATION_ID, "session-one")
        assertNull(authorizer.consume(wrongEpoch, "session-two"))
        assertNull(authorizer.consume(wrongEpoch, "session-one"))
    }

    @Test
    fun `expired revoked and attacker supplied authorities fail closed`() {
        val expired = authorizer.issue(CONVERSATION_ID, "session-one")
        clock.advance(Duration.ofMinutes(10))
        assertNull(authorizer.consume(expired, "session-one"))

        val revoked = authorizer.issue(CONVERSATION_ID, "session-one")
        authorizer.revokeAll()
        assertNull(authorizer.consume(revoked, "session-one"))
        assertNull(authorizer.consume("kitwallet://messages/$CONVERSATION_ID", "session-one"))
        assertNull(authorizer.consume("A".repeat(43), "session-one"))
        assertNull(authorizer.consume(null, "session-one"))
    }

    @Test
    fun `authority inputs are canonical and bounded`() {
        assertTrue(runCatching { authorizer.issue("not-a-uuid", "session-one") }.isFailure)
        assertTrue(runCatching { authorizer.issue(CONVERSATION_ID, "") }.isFailure)
        assertTrue(
            runCatching { authorizer.issue(CONVERSATION_ID, "x".repeat(257)) }.isFailure,
        )
    }

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private companion object {
        const val CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
    }
}
