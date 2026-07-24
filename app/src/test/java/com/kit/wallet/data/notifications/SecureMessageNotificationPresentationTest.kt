package com.kit.wallet.data.notifications

import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotification
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageNotificationPresentationTest {
    @Test
    fun `sender and plaintext preview are single-line and sensibly truncated`() {
        val presentation = presentation(
            senderName = "  Arnold\nPaul  ",
            text = " First line\n\tsecond line ${"word ".repeat(40)}",
        )

        assertEquals("Arnold Paul", presentation.sender)
        assertTrue(presentation.preview.startsWith("First line second line word word"))
        assertTrue(presentation.preview.endsWith("…"))
        assertFalse(presentation.preview.contains('\n'))
        assertTrue(presentation.preview.codePointCount(0, presentation.preview.length) <= 96)
    }

    @Test
    fun `unicode preview truncation never splits a surrogate pair`() {
        val presentation = presentation(text = "🐻".repeat(120))

        assertTrue(presentation.preview.endsWith("…"))
        assertEquals(96, presentation.preview.codePointCount(0, presentation.preview.length))
        assertFalse(Character.isHighSurrogate(presentation.preview[presentation.preview.length - 2]))
    }

    @Test
    fun `photo notification shows caption without descriptor key material`() {
        val descriptor = mediaDescriptor(caption = "Holiday\nphoto")
        val presentation = presentation(text = descriptor.encode())

        assertEquals("📷 Photo · Holiday photo", presentation.preview)
        assertFalse(presentation.preview.contains(descriptor.keyMaterialBase64))
        assertFalse(presentation.preview.contains(descriptor.storageKey))
    }

    @Test
    fun `malformed photo descriptor fails closed`() {
        val presentation = presentation(text = "${KitMediaMessage.PREFIX}key=private-material")

        assertEquals("📷 Photo", presentation.preview)
        assertFalse(presentation.preview.contains("private-material"))
    }

    @Test
    fun `payment request notification includes amount and note without internal id`() {
        val descriptor = KitPaymentMessage(
            action = KitPaymentMessage.ACTION_REQUEST,
            paymentRequestId = PAYMENT_REQUEST_ID,
            amountMinor = 123_456,
            currencyCode = "UGX",
            currencyScale = 2,
            note = "Lunch",
        )
        val presentation = presentation(text = descriptor.encode())

        assertEquals("💰 Payment request · UGX 1,234.56 · Lunch", presentation.preview)
        assertFalse(presentation.preview.contains(PAYMENT_REQUEST_ID))
    }

    @Test
    fun `unsafe or absent sender gets a private neutral label`() {
        assertEquals(
            "Kit Pay contact",
            presentation(senderName = CONVERSATION_ID, text = "Hello").sender,
        )
        assertEquals(
            "Kit Pay contact",
            presentation(senderName = "\u202e  ", text = "Hello").sender,
        )
    }

    private fun presentation(
        senderName: String? = "Peer",
        text: String,
    ): SecureMessageNotificationPresentation =
        SecureMessageNotificationPresentationFactory.create(
            SecureMessagingIncomingNotification(
                messageId = MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                sessionEpoch = "epoch-1",
                senderName = senderName,
                authenticatedText = text,
                sentAt = Instant.parse("2026-07-24T10:00:00Z"),
            ),
        )

    private fun mediaDescriptor(caption: String?): KitMediaMessage = KitMediaMessage(
        attachmentId = ATTACHMENT_ID,
        storageKey = STORAGE_KEY,
        mediaType = "image/jpeg",
        ciphertextByteSize = 128,
        ciphertextSha256 = "a".repeat(64),
        keyMaterialBase64 = Base64.getEncoder().encodeToString(
            ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES) { 7 },
        ),
        plaintextByteSize = 64,
        caption = caption,
    )

    private companion object {
        const val MESSAGE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CONVERSATION_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val ATTACHMENT_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val STORAGE_KEY = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val PAYMENT_REQUEST_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    }
}
