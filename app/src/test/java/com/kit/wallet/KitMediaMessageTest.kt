package com.kit.wallet

import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitMediaMessageTest {
    private val descriptor = KitMediaMessage(
        attachmentId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        storageKey = "0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213",
        mediaType = "image/jpeg",
        ciphertextByteSize = 4_096,
        ciphertextSha256 = "ab".repeat(32),
        keyMaterialBase64 = Base64.getEncoder()
            .encodeToString(ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES) { it.toByte() }),
        plaintextByteSize = 4_000,
        caption = "Receipt & totals 100%",
    )

    @Test
    fun roundTripsThroughDeterministicEncoding() {
        val encoded = descriptor.encode()
        assertTrue(KitMediaMessage.isMediaText(encoded))
        assertEquals(descriptor, KitMediaMessage.parse(encoded))
        // Deterministic bytes keep retry text equality intact.
        assertEquals(encoded, KitMediaMessage.parse(encoded)?.encode())
    }

    @Test
    fun derivesServerVisibleMetadataWithoutKeyMaterial() {
        val encoded = descriptor.encode()
        val attachments = KitMediaMessage.attachmentsFor(encoded)
        assertEquals(1, attachments.size)
        val request = attachments.single()
        assertEquals(descriptor.attachmentId, request.id)
        assertEquals(descriptor.storageKey, request.storageKey)
        assertEquals(descriptor.ciphertextByteSize, request.byteSize)
        assertEquals(descriptor.ciphertextSha256, request.ciphertextSha256)
        assertNull(request.encryptionMetadataCiphertext)
    }

    @Test
    fun plainTextIsNotMistakenForMedia() {
        assertNull(KitMediaMessage.parse("hello securely"))
        assertTrue(KitMediaMessage.attachmentsFor("hello securely").isEmpty())
    }

    @Test
    fun rejectsMalformedDescriptors() {
        val encoded = descriptor.encode()
        assertNull(KitMediaMessage.parse(encoded.replace("v=1", "v=2")))
        assertNull(KitMediaMessage.parse(KitMediaMessage.PREFIX + "not-fields"))
        assertNull(KitMediaMessage.parse(encoded.replace("sha=", "sha=zz")))
        assertNull(KitMediaMessage.parse("$encoded&future=value"))
        assertNull(
            KitMediaMessage.parse(
                KitMediaMessage.PREFIX + encoded.removePrefix(KitMediaMessage.PREFIX)
                    .split('&')
                    .reversed()
                    .joinToString("&"),
            ),
        )
        assertNull(KitMediaMessage.parse(encoded.replace(descriptor.attachmentId, descriptor.attachmentId.uppercase())))
        assertNull(
            KitMediaMessage.parse(
                encoded.replace(
                    "&bs=${descriptor.ciphertextByteSize}",
                    "&bs=0${descriptor.ciphertextByteSize}",
                ),
            ),
        )
        assertNull(KitMediaMessage.parse(descriptor.copy(storageKey = "not-a-storage-key").encode()))
        assertNull(KitMediaMessage.parse(descriptor.copy(mediaType = "image/svg+xml").encode()))
        assertNull(KitMediaMessage.parse(descriptor.copy(plaintextByteSize = 0).encode()))
        assertNull(
            KitMediaMessage.parse(
                descriptor.copy(ciphertextByteSize = 10L * 1024L * 1024L + 65L).encode(),
            ),
        )
        // Truncated key material must fail closed.
        assertNull(
            KitMediaMessage.parse(
                descriptor.copy(
                    keyMaterialBase64 = Base64.getEncoder().encodeToString(ByteArray(16)),
                ).encode(),
            ),
        )
    }
}
