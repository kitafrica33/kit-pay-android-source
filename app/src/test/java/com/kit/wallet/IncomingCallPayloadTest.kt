package com.kit.wallet

import com.kit.wallet.data.notifications.IncomingCallPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallPayloadTest {
    private val callId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `backend ringing payload round trips through validated navigation URI`() {
        val payload = IncomingCallPayload.fromData(
            mapOf(
                "type" to "call.ringing",
                "call_id" to callId.uppercase(),
                "call_type" to "video",
                "video" to "true",
                "initiator_name" to "  Grace & Amina  ",
                "ring_expires_at" to "2026-07-17T18:00:00Z",
            ),
        )

        requireNotNull(payload)
        assertEquals(callId, payload.callId)
        assertEquals("Grace & Amina", payload.callerName)
        assertTrue(payload.video)
        assertEquals("2026-07-17T18:00:00Z", payload.ringExpiresAt)

        val navigated = IncomingCallPayload.fromDeepLink(
            payload.deepLinkUri() + "&name=Spoofed%20Caller&video=true",
        )
        assertEquals(callId, navigated?.callId)
        assertEquals("Kit Pay contact", navigated?.callerName)
        assertFalse(navigated?.video ?: true)
    }

    @Test
    fun `voice payload uses safe caller fallback and remains voice`() {
        val payload = IncomingCallPayload.fromData(
            mapOf(
                "type" to "call.ringing",
                "call_id" to callId,
                "call_type" to "voice",
                "video" to "false",
                "initiator_name" to "\u0000\n",
            ),
        )

        requireNotNull(payload)
        assertEquals("Kit Pay contact", payload.callerName)
        assertFalse(payload.video)
    }

    @Test
    fun `untrusted notification data cannot create an incoming call route`() {
        assertNull(
            IncomingCallPayload.fromData(
                mapOf("type" to "wallet.transfer", "call_id" to callId),
            ),
        )
        assertNull(
            IncomingCallPayload.fromData(
                mapOf("type" to "call.ringing", "call_id" to "not-a-uuid"),
            ),
        )
        assertNull(
            IncomingCallPayload.fromDeepLink(
                "https://example.invalid/call/incoming?call_id=$callId&video=true",
            ),
        )
    }
}
