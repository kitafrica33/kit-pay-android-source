package com.kit.wallet.data.messaging

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side encryption for Kit Pay message media (phase 1 of encrypted attachments).
 *
 * Follows the Signal attachment scheme: AES-256-CBC for confidentiality with an appended
 * HMAC-SHA256 for integrity, plus a SHA-256 digest over the whole blob. The server only ever
 * stores the opaque ciphertext; the 64-byte key material and the plaintext never leave the device
 * except inside the end-to-end encrypted message envelope. The blob layout is:
 *
 *     iv(16) || AES-256-CBC(plaintext) || HMAC-SHA256(iv || ciphertext)(32)
 *
 * This primitive is intentionally standalone and pure so it is unit-testable off-device; wiring it
 * into the send/receive pipeline, blob upload/download and the conversation UI is later phase work.
 */
internal object MediaAttachmentCipher {
    private const val AES_KEY_BYTES = 32
    private const val MAC_KEY_BYTES = 32
    const val KEY_MATERIAL_BYTES = AES_KEY_BYTES + MAC_KEY_BYTES
    private const val IV_BYTES = 16
    private const val MAC_BYTES = 32

    data class EncryptedAttachment(
        /** iv || AES-CBC(body) || HMAC; uploaded verbatim to private blob storage. */
        val ciphertext: ByteArray,
        /** aesKey(32) || macKey(32); carried only inside the E2E-encrypted message envelope. */
        val keyMaterial: ByteArray,
        /** SHA-256 of [ciphertext]; the integrity anchor recorded in the message contract. */
        val sha256: ByteArray,
        val plaintextSize: Int,
    )

    fun encrypt(plaintext: ByteArray, random: SecureRandom = SecureRandom()): EncryptedAttachment {
        val keyMaterial = ByteArray(KEY_MATERIAL_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val body = aes(Cipher.ENCRYPT_MODE, keyMaterial, iv, plaintext)
        val ciphertext = ByteArray(IV_BYTES + body.size + MAC_BYTES)
        System.arraycopy(iv, 0, ciphertext, 0, IV_BYTES)
        System.arraycopy(body, 0, ciphertext, IV_BYTES, body.size)
        val mac = hmac(keyMaterial, ciphertext, IV_BYTES + body.size)
        System.arraycopy(mac, 0, ciphertext, IV_BYTES + body.size, MAC_BYTES)
        return EncryptedAttachment(ciphertext, keyMaterial, sha256(ciphertext), plaintext.size)
    }

    fun decrypt(ciphertext: ByteArray, keyMaterial: ByteArray, expectedSha256: ByteArray): ByteArray {
        require(keyMaterial.size == KEY_MATERIAL_BYTES) { "Attachment key material is malformed" }
        require(ciphertext.size >= IV_BYTES + MAC_BYTES) { "Attachment ciphertext is too short" }
        require(MessageDigest.isEqual(sha256(ciphertext), expectedSha256)) {
            "Attachment ciphertext failed its integrity digest"
        }
        val bodyLength = ciphertext.size - IV_BYTES - MAC_BYTES
        val expectedMac = hmac(keyMaterial, ciphertext, IV_BYTES + bodyLength)
        val actualMac = ciphertext.copyOfRange(IV_BYTES + bodyLength, ciphertext.size)
        require(MessageDigest.isEqual(expectedMac, actualMac)) {
            "Attachment ciphertext failed its authentication tag"
        }
        val iv = ciphertext.copyOfRange(0, IV_BYTES)
        val body = ciphertext.copyOfRange(IV_BYTES, IV_BYTES + bodyLength)
        return aes(Cipher.DECRYPT_MODE, keyMaterial, iv, body)
    }

    private fun aes(mode: Int, keyMaterial: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            mode,
            SecretKeySpec(keyMaterial, 0, AES_KEY_BYTES, "AES"),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(input)
    }

    /** HMAC-SHA256 with the trailing MAC key over the first [length] bytes of [data]. */
    private fun hmac(keyMaterial: ByteArray, data: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyMaterial, AES_KEY_BYTES, MAC_KEY_BYTES, "HmacSHA256"))
        mac.update(data, 0, length)
        return mac.doFinal()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
