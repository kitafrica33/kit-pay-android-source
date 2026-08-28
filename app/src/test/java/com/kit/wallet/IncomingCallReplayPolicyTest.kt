package com.kit.wallet

import com.kit.wallet.data.notifications.shouldAdmitIncomingRing
import com.kit.wallet.data.notifications.shouldAuthorizeIncomingCallLaunch
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallReplayPolicyTest {
    private val now = Instant.parse("2026-08-28T12:00:00Z")
    private val ringExpiry = now.plusSeconds(45)
    private val recordedUntil = ringExpiry.plus(Duration.ofMinutes(10)).toEpochMilli()

    @Test
    fun `first ring is admitted and duplicate active ring is suppressed`() {
        assertTrue(shouldAdmitIncomingRing(now, ringExpiry, null, null))
        assertFalse(shouldAdmitIncomingRing(now, ringExpiry, recordedUntil, null))
    }

    @Test
    fun `durable terminal before ring suppresses ring and retained pending intent`() {
        val terminalUntil = now.plus(Duration.ofDays(2)).toEpochMilli()
        assertFalse(shouldAdmitIncomingRing(now, ringExpiry, null, terminalUntil))
        assertFalse(
            shouldAuthorizeIncomingCallLaunch(now, ringExpiry, recordedUntil, terminalUntil),
        )
    }

    @Test
    fun `launch requires the exact live ring expiry`() {
        assertTrue(shouldAuthorizeIncomingCallLaunch(now, ringExpiry, recordedUntil, null))
        assertFalse(
            shouldAuthorizeIncomingCallLaunch(now, ringExpiry.plusSeconds(1), recordedUntil, null),
        )
        assertFalse(
            shouldAuthorizeIncomingCallLaunch(ringExpiry, ringExpiry, recordedUntil, null),
        )
    }
}
