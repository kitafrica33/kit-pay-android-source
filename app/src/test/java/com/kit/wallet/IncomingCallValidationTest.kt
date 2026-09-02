package com.kit.wallet

import com.kit.wallet.data.repository.IncomingCallDetails
import com.kit.wallet.data.repository.requireAnswerable
import com.kit.wallet.data.notifications.callRingWindowMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IncomingCallValidationTest {
    private val incoming = IncomingCallDetails(
        callId = "550e8400-e29b-41d4-a716-446655440000",
        name = "Server caller",
        video = true,
        direction = "incoming",
        state = "ringing",
        ringExpiresAt = "2026-07-17T12:00:30Z",
        serverTime = "2026-07-17T12:00:00Z",
    )

    @Test
    fun `server-returned incoming ringing call is answerable before expiry`() {
        assertEquals(incoming, incoming.requireAnswerable())
        assertEquals(incoming.copy(state = "active"), incoming.copy(state = "active").requireAnswerable())
    }

    @Test
    fun `outgoing ended expired and malformed calls cannot render incoming controls`() {
        assertThrows(IllegalArgumentException::class.java) {
            incoming.copy(direction = "outgoing").requireAnswerable()
        }
        assertThrows(IllegalArgumentException::class.java) {
            incoming.copy(state = "ended").requireAnswerable()
        }
        assertThrows(IllegalArgumentException::class.java) {
            incoming.copy(ringExpiresAt = "2026-07-17T11:59:59Z").requireAnswerable()
        }
        assertThrows(IllegalArgumentException::class.java) {
            incoming.copy(ringExpiresAt = "not-an-instant").requireAnswerable()
        }
        assertThrows(IllegalArgumentException::class.java) {
            incoming.copy(serverTime = null).requireAnswerable()
        }
    }

    @Test
    fun `ring validation uses only paired server timestamps and caps the local lease`() {
        assertEquals(
            60_000L,
            callRingWindowMillis(
                ringExpiresAt = "2036-07-17T12:00:00Z",
                serverTime = "2026-07-17T12:00:00Z",
            ),
        )
    }
}
