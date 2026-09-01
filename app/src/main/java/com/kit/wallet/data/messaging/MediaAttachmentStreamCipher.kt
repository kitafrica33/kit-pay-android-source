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
     * Reads [ciphertext] once and decrypts into an unpublished private [destination] scratch.
     *
     * SHA-256 and HMAC are accumulated beside CBC decryption in the same pass. The scratch is
     * deleted unless the digest, authentication tag, CBC padding and [expectedPlaintextBytes] all
     * validate. Callers must publish/rename it only after this function returns; this is the file
     * supplied by [SecureMediaCache.store], never a user-visible or final media path.
     */
    fun decrypt(
        ciphertext: File,
        keyMaterial: ByteArray,
        expectedSha256: ByteArray,
        expectedPlaintextBytes: Int,
        destination: File,
    ): Int {
        require(keyMaterial.size == AES_KEY_BYTES + MAC_KEY_BYTES) {
            "Attachment key material is malformed"
        }
        require(expectedSha256.size == MAC_BYTES) { "Attachment digest is malformed" }
        require(expectedPlaintextBytes > 0) { "Attachment plaintext size is malformed" }
        require(ciphertext.canonicalFile != destination.canonicalFile) {
            "Attachment ciphertext and plaintext scratch must differ"
        }
        val totalBytes = ciphertext.length()
        require(totalBytes >= IV_BYTES + MAC_BYTES) { "Attachment ciphertext is too short" }
        val bodyBytes = totalBytes - IV_BYTES - MAC_BYTES

        val iv = ByteArray(IV_BYTES)
        val tag = ByteArray(MAC_BYTES)
        val buffer = ByteArray(CHUNK_BYTES)
        val mac = macFor(keyMaterial)
        val digest = MessageDigest.getInstance("SHA-256")
        var plaintextBytes = 0
        try {
            destination.outputStream().buffered().use { output ->
                ciphertext.inputStream().buffered().use { input ->
                    input.readExactly(iv)
                    mac.update(iv)
                    digest.update(iv)
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(keyMaterial, 0, AES_KEY_BYTES, "AES"),
                            IvParameterSpec(iv),
                        )
                    }

                    var remaining = bodyBytes
                    while (remaining > 0) {
                        val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                        val count = input.read(buffer, 0, wanted)
                        if (count < 0) {
                            throw IllegalStateException("Attachment ciphertext ended early")
                        }
                        remaining -= count
                        mac.update(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val produced = cipher.update(buffer, 0, count)
                        if (produced != null && produced.isNotEmpty()) {
                            plaintextBytes = Math.addExact(plaintextBytes, produced.size)
                            check(plaintextBytes <= expectedPlaintextBytes) {
                                "The decrypted media exceeds its authenticated size"
                            }
                            output.write(produced)
                            produced.fill(0)
                        }
                    }
                    input.readExactly(tag)
                    check(input.read() < 0) { "Attachment ciphertext changed while being read" }
                    digest.update(tag)
                    require(MessageDigest.isEqual(digest.digest(), expectedSha256)) {
                        "Attachment ciphertext failed its integrity digest"
                    }
                    require(MessageDigest.isEqual(mac.doFinal(), tag)) {
                        "Attachment ciphertext failed its authentication tag"
                    }

                    // doFinal validates CBC padding only after both public integrity anchors pass.
                    val tail = cipher.doFinal()
                    try {
                        plaintextBytes = Math.addExact(plaintextBytes, tail.size)
                        check(plaintextBytes == expectedPlaintextBytes) {
                            "The decrypted media does not match its authenticated size"
                        }
                        output.write(tail)
                    } finally {
                        tail.fill(0)
                    }
                }
                output.flush()
            }
            return plaintextBytes
        } catch (error: Throwable) {
            runCatching { destination.delete() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        } finally {
            buffer.fill(0)
            iv.fill(0)
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

}
