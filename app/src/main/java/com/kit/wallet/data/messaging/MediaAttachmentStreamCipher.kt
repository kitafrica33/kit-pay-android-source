package com.kit.wallet.data.messaging

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The [MediaAttachmentCipher] scheme applied a chunk at a time instead of all at once.
 *
 * The blob it produces is byte-for-byte the blob the in-heap cipher produces from the same key
 * material and IV — `MediaAttachmentStreamCipherTest` proves it by driving both from one seeded
 * [SecureRandom] — so nothing on the wire, on the server, or on iOS can tell which one ran. The
 * difference is only where the bytes live: CBC, HMAC-SHA256 and SHA-256 are all streaming
 * constructions, so a file of any size passes through a fixed [CHUNK_BYTES] buffer rather than
 * being held in three heap copies at once. That is what makes a 200 MB attachment possible on a
 * device whose whole app heap may be smaller than that.
 */
internal object MediaAttachmentStreamCipher {
    private const val AES_KEY_BYTES = 32
    private const val MAC_KEY_BYTES = 32
    private const val IV_BYTES = 16
    private const val MAC_BYTES = 32
    private const val CHUNK_BYTES = 64 * 1024

    /** What the send path needs to describe the blob it just wrote, and nothing more. */
    data class StreamedAttachment(
        /** aesKey(32) || macKey(32); carried only inside the E2E-encrypted message envelope. */
        val keyMaterial: ByteArray,
        /** SHA-256 of the whole ciphertext; the integrity anchor recorded in the contract. */
        val sha256: ByteArray,
        val ciphertextByteSize: Long,
        val plaintextByteSize: Int,
    )

    /**
     * Encrypts [source] into [destination] as `iv || AES-256-CBC(plaintext) || HMAC-SHA256`.
     *
     * [maximumPlaintextBytes] is enforced as the bytes go past, so an oversized source is refused
     * partway rather than after a full copy has already been made.
     */
    fun encrypt(
        source: InputStream,
        destination: OutputStream,
        maximumPlaintextBytes: Int,
        random: SecureRandom = SecureRandom(),
    ): StreamedAttachment {
        require(maximumPlaintextBytes > 0) { "A media encrypt requires a positive byte limit" }
        val keyMaterial = ByteArray(AES_KEY_BYTES + MAC_KEY_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyMaterial, 0, AES_KEY_BYTES, "AES"),
                IvParameterSpec(iv),
            )
        }
        val mac = macFor(keyMaterial)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)

        var plaintextBytes = 0
        var ciphertextBytes = 0L
        try {
            destination.write(iv)
            mac.update(iv)
            digest.update(iv)
            ciphertextBytes += IV_BYTES

            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                plaintextBytes = Math.addExact(plaintextBytes, count)
                if (plaintextBytes > maximumPlaintextBytes) {
                    throw IllegalArgumentException(
                        "Files up to ${maximumPlaintextBytes / (1024 * 1024)} MB are supported",
                    )
                }
                // update() is allowed to buffer a partial block and hand back nothing.
                val produced = cipher.update(buffer, 0, count)
                if (produced != null && produced.isNotEmpty()) {
                    ciphertextBytes += emit(produced, destination, mac, digest)
                }
            }
            require(plaintextBytes > 0) { "Choose a file to send securely" }

            ciphertextBytes += emit(cipher.doFinal(), destination, mac, digest)
            val tag = mac.doFinal()
            destination.write(tag)
            digest.update(tag)
            ciphertextBytes += MAC_BYTES
            destination.flush()
        } finally {
            buffer.fill(0)
        }

        return StreamedAttachment(
            keyMaterial = keyMaterial,
            sha256 = digest.digest(),
            ciphertextByteSize = ciphertextBytes,
            plaintextByteSize = plaintextBytes,
        )
    }

    /**
     * Verifies [ciphertext] whole, then decrypts it into [destination]; returns the plaintext size.
     *
     * The file is read twice on purpose. Nothing is decrypted until both the authenticated SHA-256
     * and the HMAC have been checked over every byte, which is the same order the in-heap
     * [MediaAttachmentCipher.decrypt] enforces — a second sequential read of a local file is a
     * cheap price for never writing out a byte of unauthenticated plaintext.
     */
    fun decrypt(
        ciphertext: File,
        keyMaterial: ByteArray,
        expectedSha256: ByteArray,
        destination: OutputStream,
    ): Int {
        require(keyMaterial.size == AES_KEY_BYTES + MAC_KEY_BYTES) {
            "Attachment key material is malformed"
        }
        val totalBytes = ciphertext.length()
        require(totalBytes >= IV_BYTES + MAC_BYTES) { "Attachment ciphertext is too short" }
        val bodyBytes = totalBytes - IV_BYTES - MAC_BYTES

        val iv = ByteArray(IV_BYTES)
        verify(ciphertext, keyMaterial, expectedSha256, bodyBytes, iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyMaterial, 0, AES_KEY_BYTES, "AES"),
                IvParameterSpec(iv),
            )
        }
        val buffer = ByteArray(CHUNK_BYTES)
        var plaintextBytes = 0
        try {
            ciphertext.inputStream().buffered().use { input ->
                input.skipExactly(IV_BYTES.toLong())
                var remaining = bodyBytes
                while (remaining > 0) {
                    val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                    val count = input.read(buffer, 0, wanted)
                    if (count < 0) throw IllegalStateException("Attachment ciphertext ended early")
                    remaining -= count
                    val produced = cipher.update(buffer, 0, count)
                    if (produced != null && produced.isNotEmpty()) {
                        plaintextBytes = Math.addExact(plaintextBytes, produced.size)
                        destination.write(produced)
                        produced.fill(0)
                    }
                }
            }
            val tail = cipher.doFinal()
            plaintextBytes = Math.addExact(plaintextBytes, tail.size)
            destination.write(tail)
            tail.fill(0)
            destination.flush()
        } finally {
            buffer.fill(0)
            iv.fill(0)
        }
        return plaintextBytes
    }

    /**
     * One pass that both digests the whole blob and MACs everything before the tag, so a tampered
     * attachment is rejected without a second read.
     */
    private fun verify(
        ciphertext: File,
        keyMaterial: ByteArray,
        expectedSha256: ByteArray,
        bodyBytes: Long,
        capturedIv: ByteArray,
    ) {
        val mac = macFor(keyMaterial)
        val digest = MessageDigest.getInstance("SHA-256")
        val tag = ByteArray(MAC_BYTES)
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            ciphertext.inputStream().buffered().use { input ->
                input.readExactly(capturedIv)
                mac.update(capturedIv)
                digest.update(capturedIv)

                var remaining = bodyBytes
                while (remaining > 0) {
                    val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                    val count = input.read(buffer, 0, wanted)
                    if (count < 0) throw IllegalStateException("Attachment ciphertext ended early")
                    remaining -= count
                    mac.update(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                input.readExactly(tag)
            }
            digest.update(tag)
            // `require`, not `check`, so a rejection is the same kind of failure the in-heap
            // cipher raises for the same blob: callers must not have to know which one ran.
            require(MessageDigest.isEqual(digest.digest(), expectedSha256)) {
                "Attachment ciphertext failed its integrity digest"
            }
            require(MessageDigest.isEqual(mac.doFinal(), tag)) {
                "Attachment ciphertext failed its authentication tag"
            }
        } finally {
            buffer.fill(0)
            tag.fill(0)
        }
    }

    private fun emit(
        produced: ByteArray,
        destination: OutputStream,
        mac: Mac,
        digest: MessageDigest,
    ): Int {
        destination.write(produced)
        mac.update(produced)
        digest.update(produced)
        val size = produced.size
        produced.fill(0)
        return size
    }

    private fun macFor(keyMaterial: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(keyMaterial, AES_KEY_BYTES, MAC_KEY_BYTES, "HmacSHA256"))
    }

    private fun InputStream.readExactly(into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val count = read(into, offset, into.size - offset)
            if (count < 0) throw IllegalStateException("Attachment ciphertext ended early")
            offset += count
        }
    }

    private fun InputStream.skipExactly(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) throw IllegalStateException("Attachment ciphertext ended early")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
