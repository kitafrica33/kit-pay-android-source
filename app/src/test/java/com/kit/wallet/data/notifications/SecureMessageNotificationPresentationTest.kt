package com.kit.wallet.data.notifications

import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.KitMediaMessageV2
import com.kit.wallet.data.messaging.KitMediaMessageV2Item
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
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
            senderName = "  Amina\nExample  ",
            text = " First line\n\tsecond line ${"word ".repeat(40)}",
        )

        assertEquals("Amina Example", presentation.sender)
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
    fun `album notification uses plural kind and caption without descriptor secrets`() {
        val key = Base64.getEncoder().encodeToString(
            ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES) { 9 },
        )
        val descriptor = KitMediaMessageV2(
            items = listOf(
                KitMediaMessageV2Item(
                    attachmentId = "11111111-1111-4111-8111-111111111111",
                    storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    mediaType = "image/jpeg",
                    ciphertextByteSize = 80,
                    ciphertextSha256 = "1".repeat(64),
                    keyMaterialBase64 = key,
                    plaintextByteSize = 16,
                ),
                KitMediaMessageV2Item(
                    attachmentId = "22222222-2222-4222-8222-222222222222",
                    storageKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    mediaType = "image/png",
                    ciphertextByteSize = 80,
                    ciphertextSha256 = "2".repeat(64),
                    keyMaterialBase64 = key,
                    plaintextByteSize = 16,
                ),
            ),
            caption = "Family photos",
        )

        val presentation = presentation(text = descriptor.encode())

        assertEquals("2 Photos · Family photos", presentation.preview)
        assertFalse(presentation.preview.contains(key))
        assertFalse(presentation.preview.contains(descriptor.items.first().storageKey))
    }

    @Test
    fun `malformed album descriptor fails closed to the generic attachment label`() {
        val presentation = presentation(
            text = "${KitMediaMessageV2.PREFIX}v=2&n=2&key0=private-material",
        )

        assertEquals("Attachment", presentation.preview)
        assertFalse(presentation.preview.contains("private-material"))
    }

    @Test
    fun `reaction descriptor is summarized rather than surfaced raw`() {
        // Reactions are suppressed before the shade; this is the second line of defence.
        val descriptor = KitReactionMessage(
            targetMessageId = MESSAGE_ID,
            emoji = "👍",
            action = KitReactionAction.ADD,
        )
        val presentation = presentation(text = descriptor.encode())

        assertEquals("Reacted to your message 👍", presentation.preview)
        assertFalse(presentation.preview.contains(MESSAGE_ID))
        assertFalse(presentation.preview.contains(KitReactionMessage.PREFIX))
    }

    @Test
    fun `malformed reaction descriptor fails closed`() {
        val presentation = presentation(text = "${KitReactionMessage.PREFIX}v=9&mid=$MESSAGE_ID")

        assertEquals("Reacted to your message", presentation.preview)
        assertFalse(presentation.preview.contains(MESSAGE_ID))
    }

    @Test
    fun `payment request notification includes amount and note without internal id`() {
        val descriptor = KitPaymentMessage(
            action = KitPaymentAction.REQUEST,
            referenceId = PAYMENT_REQUEST_ID,
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
