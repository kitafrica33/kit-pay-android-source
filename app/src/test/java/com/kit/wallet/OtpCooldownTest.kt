package com.kit.wallet

import com.kit.wallet.feature.auth.formatResendCountdown
import com.kit.wallet.feature.auth.resendSecondsRemaining
import org.junit.Assert.assertEquals
import org.junit.Test

class OtpCooldownTest {
    @Test
    fun `cooldown rounds partial seconds up and reaches zero only after the deadline`() {
        assertEquals(60L, resendSecondsRemaining(60_000L, 1L))
        assertEquals(1L, resendSecondsRemaining(60_000L, 59_999L))
        assertEquals(0L, resendSecondsRemaining(60_000L, 60_000L))
        assertEquals(0L, resendSecondsRemaining(null, 60_000L))
    }

    @Test
    fun `cooldown presentation is stable at minute and second boundaries`() {
        assertEquals("1:00", formatResendCountdown(60L))
        assertEquals("0:09", formatResendCountdown(9L))
        assertEquals("0:00", formatResendCountdown(-1L))
    }
}
