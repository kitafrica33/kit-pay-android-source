package com.kit.wallet

import com.kit.wallet.feature.calls.CallInviteLink
import com.kit.wallet.feature.calls.ScheduledCallPageCursor
import com.kit.wallet.feature.calls.ScheduledCallPolicy
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScheduledCallPolicyTest {
    private val self = "550e8400-e29b-41d4-a716-446655440000"
    private val other = "550e8400-e29b-41d4-a716-446655440001"
    private val token = "Ab9".repeat(21) + "Z"

    @Test fun `invitation parses only exact private link with full unencoded token`() {
        val raw = "kitpay://call-invites/$token"
        assertEquals(token, CallInviteLink.fromDeepLink(raw)?.token)
        assertEquals(raw, CallInviteLink.fromToken(token)?.deepLinkUri())
        listOf(
            raw + "?accept=true", raw + "#join", raw + "/", " " + raw,
            raw.replace("kitpay:", "https:"), raw.replace("call-invites", "CALL-INVITES"),
            raw.replace("call-invites", "call-invites:443"), raw.replace("call-invites", "user@call-invites"),
            raw.replace("/$token", "/%41" + token.drop(1)), raw.dropLast(1), raw + "a",
        ).forEach { assertNull(it, CallInviteLink.fromDeepLink(it)) }
        assertFalse(CallInviteLink.fromToken(token).toString().contains(token))
    }

    @Test fun `group recipients are unique valid accounts excluding the current account`() {
        assertEquals(listOf(other), ScheduledCallPolicy.recipients(listOf(other.uppercase()), self))
        listOf(emptyList(), listOf(self.uppercase()), listOf(other, other.uppercase()), listOf("1-1-1-1-1"), listOf(" $other")).forEach {
            assertTrue(runCatching { ScheduledCallPolicy.recipients(it, self) }.isFailure)
        }
        val twenty = (1..20).map { "550e8400-e29b-41d4-a716-${it.toString().padStart(12, '0')}" }
        assertEquals(20, ScheduledCallPolicy.recipients(twenty, null).size)
        assertTrue(runCatching { ScheduledCallPolicy.recipients(twenty + self, null) }.isFailure)
    }

    @Test fun `scheduled time carries explicit local offset and seconds`() {
        val now = Instant.parse("2026-09-07T00:00:00Z")
        val local = LocalDateTime.parse("2026-09-08T14:05:00")
        assertEquals("2026-09-08T14:05:00+05:30", ScheduledCallPolicy.startsAt(local, ZoneId.of("Asia/Kolkata"), now))
        assertEquals("2026-09-08T14:05:00Z", ScheduledCallPolicy.startsAt(local, ZoneId.of("UTC"), now))
    }

    @Test fun `daylight saving gaps and ambiguous times require another explicit selection`() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-01-01T00:00:00Z")
        listOf("2026-03-08T02:30:00", "2026-11-01T01:30:00").forEach {
            assertTrue(runCatching { ScheduledCallPolicy.startsAt(LocalDateTime.parse(it), zone, now) }.isFailure)
        }
    }

    @Test fun `fresh scheduling enforces future and calendar year horizon`() {
        val now = Instant.parse("2027-03-01T12:00:00Z")
        ScheduledCallPolicy.validateStart(Instant.parse("2028-03-01T12:00:00Z"), now)
        listOf(now, now.minusSeconds(1), Instant.parse("2028-03-01T12:00:01Z")).forEach {
            assertTrue(runCatching { ScheduledCallPolicy.validateStart(it, now) }.isFailure)
        }
    }

    @Test fun `room transport rejects credentialed insecure or ambiguous URLs`() {
        assertTrue(ScheduledCallPolicy.secureRtcUrl("wss://rtc.kit.africa"))
        assertTrue(ScheduledCallPolicy.secureRtcUrl("wss://rtc.kit.africa:443/livekit"))
        listOf("ws://rtc.kit.africa", "https://rtc.kit.africa", "wss:///room", "wss://token@rtc.kit.africa", "wss://rtc.kit.africa?token=secret", "wss://rtc.kit.africa#room", "wss://rtc.kit.africa:0", "wss://rtc.kit.africa:65536").forEach {
            assertFalse(it, ScheduledCallPolicy.secureRtcUrl(it))
        }
    }

    @Test fun `expired credentials and absent clocks cannot authorize a join`() {
        val now = "2026-09-07T12:00:00Z"
        ScheduledCallPolicy.requireFreshExpiry("2026-09-07T12:00:01Z", now)
        listOf(now, "2026-09-07T11:59:59Z", "").forEach {
            assertTrue(runCatching { ScheduledCallPolicy.requireFreshExpiry(it, now) }.isFailure)
        }
    }

    @Test fun `pagination refuses loops missing continuation and oversized cursors`() {
        val cursor = ScheduledCallPageCursor()
        assertEquals("next", cursor.accept("next", true))
        assertTrue(runCatching { cursor.accept("next", true) }.isFailure)
        assertTrue(runCatching { cursor.accept(null, true) }.isFailure)
        assertTrue(runCatching { cursor.accept("x".repeat(4097), true) }.isFailure)
        assertNull(cursor.accept("ignored", false))
        cursor.reset()
        assertEquals("next", cursor.accept("next", true))
    }
}
