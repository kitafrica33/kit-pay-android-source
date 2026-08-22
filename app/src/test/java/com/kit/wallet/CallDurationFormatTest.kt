package com.kit.wallet

import com.kit.wallet.feature.calls.formatCallDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class CallDurationFormatTest {
    @Test fun `formats sub-hour durations as minutes and seconds`() {
        assertEquals("0:05", formatCallDuration(5))
        assertEquals("1:00", formatCallDuration(60))
        assertEquals("59:59", formatCallDuration(3_599))
    }

    @Test fun `formats hour-long durations with an hours component`() {
        assertEquals("1:00:00", formatCallDuration(3_600))
        assertEquals("2:03:04", formatCallDuration(7_384))
    }
}
