package com.kit.wallet

import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.MediaAttachmentStreamCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The streaming cipher exists so a 200 MB attachment never has to fit in heap. It earns that only
 * if it is otherwise indistinguishable from the cipher it replaces — the same bytes on the wire,
 * for iOS and the server alike — so most of what is asserted here is sameness.
 */
class MediaAttachmentStreamCipherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun seededRandom() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }

    private fun plaintext(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun encryptToFile(
        source: ByteArray,
        random: SecureRandom = seededRandom(),
        maximumBytes: Int = 8 * 1024 * 1024,
    ): Pair<File, MediaAttachmentStreamCipher.StreamedAttachment> {
        val destination = temporaryFolder.newFile()
        val streamed = destination.outputStream().buffered().use { output ->
            MediaAttachmentStreamCipher.encrypt(
                source = ByteArrayInputStream(source),
                destination = output,
                maximumPlaintextBytes = maximumBytes,
                random = random,
            )
        }
        return destination to streamed
    }

    /**
     * The whole justification for a second cipher: drive both from one seed and the blobs must be
     * indistinguishable. If this ever fails, an Android send has diverged from every other client.
     */
    @Test
    fun `the streamed blob is the blob the in heap cipher makes`() {
        for (size in listOf(1, 15, 16, 17, 4_096, 65_537, 200_000)) {
            val source = plaintext(size)
            val inHeap = MediaAttachmentCipher.encrypt(source, seededRandom())
            val (file, streamed) = encryptToFile(source)

            assertArrayEquals("size $size ciphertext", inHeap.ciphertext, file.readBytes())
            assertArrayEquals("size $size key", inHeap.keyMaterial, streamed.keyMaterial)
            assertArrayEquals("size $size digest", inHeap.sha256, streamed.sha256)
            assertEquals(size, streamed.plaintextByteSize)
            assertEquals(inHeap.ciphertext.size.toLong(), streamed.ciphertextByteSize)
        }
    }

    @Test
    fun `it round trips across block boundaries`() {
        for (size in listOf(1, 15, 16, 17, 63, 1_024, 65_537, 200_000)) {
            val source = plaintext(size)
            val (file, streamed) = encryptToFile(source)
            val decrypted = ByteArrayOutputStream()
            val plaintextBytes = MediaAttachmentStreamCipher.decrypt(
                ciphertext = file,
                keyMaterial = streamed.keyMaterial,
                expectedSha256 = streamed.sha256,
                destination = decrypted,
            )
            assertEquals(size, plaintextBytes)
            assertArrayEquals(source, decrypted.toByteArray())
        }
    }

    /** Either cipher must be able to open what the other sealed; a device may use both. */
    @Test
    fun `each cipher opens what the other sealed`() {
        val source = plaintext(50_000)
        val (file, streamed) = encryptToFile(source)
        assertArrayEquals(
            source,
            MediaAttachmentCipher.decrypt(
                file.readBytes(),
                streamed.keyMaterial,
                streamed.sha256,
            ),
        )

        val inHeap = MediaAttachmentCipher.encrypt(source, seededRandom())
        val sealed = temporaryFolder.newFile().apply { writeBytes(inHeap.ciphertext) }
        val opened = ByteArrayOutputStream()
        MediaAttachmentStreamCipher.decrypt(
            ciphertext = sealed,
            keyMaterial = inHeap.keyMaterial,
            expectedSha256 = inHeap.sha256,
            destination = opened,
        )
        assertArrayEquals(source, opened.toByteArray())
    }

    @Test
    fun `a tampered blob is refused before anything is written`() {
        val (file, streamed) = encryptToFile(plaintext(50_000))
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        file.writeBytes(bytes)

        val decrypted = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            MediaAttachmentStreamCipher.decrypt(
                ciphertext = file,
                keyMaterial = streamed.keyMaterial,
                expectedSha256 = streamed.sha256,
                destination = decrypted,
            )
        }
        assertEquals(0, decrypted.size())
    }

    @Test
    fun `the wrong key is caught by the tag and not by the padding`() {
        val (file, streamed) = encryptToFile(plaintext(50_000))
        // A different seed on purpose: the encrypt seed's first draw *is* the real key material.
        val wrongKey = ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES).also {
            SecureRandom.getInstance("SHA1PRNG").apply { setSeed(99L) }.nextBytes(it)
        }
        val decrypted = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            MediaAttachmentStreamCipher.decrypt(
                ciphertext = file,
                keyMaterial = wrongKey,
                expectedSha256 = streamed.sha256,
                destination = decrypted,
            )
        }
        assertEquals(0, decrypted.size())
    }

    /** The point of enforcing the cap mid-stream is that an oversized file is never fully copied. */
    @Test
    fun `an oversized source is stopped part way through`() {
        assertThrows(IllegalArgumentException::class.java) {
            encryptToFile(plaintext(300_000), maximumBytes = 128 * 1024)
        }
    }

    @Test
    fun `an empty source is not an attachment`() {
        assertThrows(IllegalArgumentException::class.java) {
            encryptToFile(ByteArray(0))
        }
    }
}
