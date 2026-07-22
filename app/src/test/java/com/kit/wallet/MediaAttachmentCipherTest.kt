package com.kit.wallet

import com.kit.wallet.data.messaging.MediaAttachmentCipher
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaAttachmentCipherTest {
    // Deterministic source so the round-trip is reproducible; the scheme itself uses fresh IVs/keys.
    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }

    @Test
    fun roundTripsPayloadsAcrossBlockBoundaries() {
        for (size in listOf(0, 1, 15, 16, 17, 63, 1024, 65_537)) {
            val plaintext = ByteArray(size).also(random::nextBytes)
            val encrypted = MediaAttachmentCipher.encrypt(plaintext, random)
            assertEquals(size, encrypted.plaintextSize)
            assertEquals(MediaAttachmentCipher.KEY_MATERIAL_BYTES, encrypted.keyMaterial.size)
            val decrypted = MediaAttachmentCipher.decrypt(
                encrypted.ciphertext,
                encrypted.keyMaterial,
                encrypted.sha256,
            )
            assertArrayEquals(plaintext, decrypted)
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val encrypted = MediaAttachmentCipher.encrypt("secret media".toByteArray(), random)
        val tampered = encrypted.ciphertext.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            MediaAttachmentCipher.decrypt(tampered, encrypted.keyMaterial, encrypted.sha256)
        }
    }

    @Test
    fun rejectsWrongKeyMaterialViaMac() {
        val encrypted = MediaAttachmentCipher.encrypt("secret media".toByteArray(), random)
        val wrongKey = ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES).also(random::nextBytes)
        assertThrows(IllegalArgumentException::class.java) {
            MediaAttachmentCipher.decrypt(encrypted.ciphertext, wrongKey, encrypted.sha256)
        }
    }
}
