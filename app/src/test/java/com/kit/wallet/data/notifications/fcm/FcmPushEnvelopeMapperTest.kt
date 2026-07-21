package com.kit.wallet.data.notifications.fcm

import com.kit.wallet.data.notifications.PushNotificationContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmPushEnvelopeMapperTest {
    private val wakeData = mapOf(
        "type" to "messaging.sync",
        "scope" to "messaging",
        "notification_id" to "opaque-id",
    )

    @Test
    fun `complete data-only analytics-free envelope verifies opaque wake`() {
        val envelope = FcmPushEnvelopeMapper.map(
            data = wakeData,
            rawEnvelopeKeys = wakeData.keys + setOf("google.message_id", "google.sent_time", "from"),
            notification = null,
            messageId = "provider-message-id",
        )

        assertTrue(envelope.opaqueWakeVerified)
        assertEquals(wakeData, envelope.data)
        assertEquals("provider-message-id", envelope.messageId)
        assertNull(envelope.notification)
    }

    @Test
    fun `provider notification payload prevents opaque wake verification`() {
        val envelope = FcmPushEnvelopeMapper.map(
            data = wakeData,
            rawEnvelopeKeys = wakeData.keys,
            notification = PushNotificationContent("Server title", "Server body"),
            messageId = null,
        )

        assertFalse(envelope.opaqueWakeVerified)
    }

    @Test
    fun `analytics metadata prevents opaque wake verification`() {
        listOf("google.c.a.e", "google.c.a.c_id", "google.c.a.m_l").forEach { analyticsKey ->
            val envelope = FcmPushEnvelopeMapper.map(
                data = wakeData,
                rawEnvelopeKeys = wakeData.keys + analyticsKey,
                notification = null,
                messageId = null,
            )

            assertFalse(envelope.opaqueWakeVerified)
        }
    }

    @Test
    fun `missing or incomplete raw envelope fails closed`() {
        assertFalse(
            FcmPushEnvelopeMapper.map(
                data = wakeData,
                rawEnvelopeKeys = null,
                notification = null,
                messageId = null,
            ).opaqueWakeVerified,
        )
        assertFalse(
            FcmPushEnvelopeMapper.map(
                data = wakeData,
                rawEnvelopeKeys = wakeData.keys - "scope",
                notification = null,
                messageId = null,
            ).opaqueWakeVerified,
        )
    }
}
