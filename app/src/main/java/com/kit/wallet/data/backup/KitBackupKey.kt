package com.kit.wallet.data.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec

/**
 * The one secret a Kit Pay backup is worth.
 *
 * A backup that Google could read would not be a backup of an end-to-end encrypted conversation —
 * it would be a copy of it handed to a third party. So the archive is encrypted before it leaves
 * the device, under a key that exists only here and in whatever the user writes down. Drive holds
 * ciphertext and nothing else; losing the key means losing the backup, and that is the honest
 * price of the guarantee.
 *
 * The key is 256 random bits rather than anything the user invents, so no backup is only as strong
 * as a memorable password. It is shown as a [recoveryCode] — 52 Crockford base-32 characters, an
 * alphabet with no I, L, O or U to confuse with 1, 0 and each other, carrying a checksum so a
 * mistyped code is rejected before it is mistaken for a wrong one.
 *
 * Everything here is pure: no Android APIs, no storage, no network, so it is exercised off-device.
 */
@JvmInline
value class KitBackupKey(private val material: ByteArray) {
    init {
        require(material.size == KEY_BYTES) { "A Kit Pay backup key is $KEY_BYTES bytes" }
    }

    /** A copy: callers that zero their buffer must not zero this key. */
    fun bytes(): ByteArray = material.copyOf()

    /**
     * Derives a purpose-bound subkey. Distinct [info] means the archive key and the key-envelope
     * key can never be interchanged, however identical the surrounding bytes look.
     */
    fun derive(salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray =
        hkdf(material, salt, info.toByteArray(Charsets.UTF_8), length)

    /** The 52-character code the user writes down. Grouped for reading, not for the format. */
    fun recoveryCode(): String = encodeRecoveryCode(material)

    fun formattedRecoveryCode(): String =
        recoveryCode().chunked(RECOVERY_GROUP_CHARS).joinToString(separator = " ")

    override fun toString(): String = "KitBackupKey(redacted)"

    companion object {
        const val KEY_BYTES = 32
        const val RECOVERY_CODE_CHARS = 52
        const val RECOVERY_GROUP_CHARS = 4

        fun random(random: SecureRandom = SecureRandom()): KitBackupKey =
            KitBackupKey(ByteArray(KEY_BYTES).also(random::nextBytes))

        /** Rebuilds a key from what the user typed, or null if that was not a Kit Pay code. */
        fun fromRecoveryCode(code: String): KitBackupKey? =
            decodeRecoveryCode(code)?.let(::KitBackupKey)
    }
}

/**
 * A backup key sealed under a password, so a user who remembers one can restore without the code.
 *
 * The password protects only this envelope; the archive itself is always under the full 256-bit
 * key, so a weak password cannot weaken a backup that has already been written. The envelope is a
 * convenience and it is optional — the recovery code alone always restores.
 */
object KitBackupKeyEnvelope {
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(),
        'K'.code.toByte(), 'E'.code.toByte(), 'Y'.code.toByte(), 1)
    private const val KDF_PBKDF2_HMAC_SHA256 = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * Deliberately expensive, and recorded in the envelope so it can be raised later without
     * stranding envelopes written today. Kit Pay runs on inexpensive hardware, so this is a
     * compromise rather than the largest number available.
     */
    const val DEFAULT_ITERATIONS = 210_000
    private const val MINIMUM_ITERATIONS = 100_000

    fun seal(
        key: KitBackupKey,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(iterations >= MINIMUM_ITERATIONS) { "Backup password iterations are too low" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(iterations, salt, nonce)
        val wrappingKey = pbkdf2(password, salt, iterations)
        val secret = key.bytes()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(wrappingKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            return header + cipher.doFinal(secret)
        } finally {
            secret.fill(0)
            wrappingKey.fill(0)
        }
    }

    /** Returns null when the password is wrong or the envelope is not one of ours. */
    fun open(envelope: ByteArray, password: CharArray): KitBackupKey? {
        if (envelope.size < MAGIC.size + 1 + 4 + SALT_BYTES + NONCE_BYTES) return null
        val buffer = ByteBuffer.wrap(envelope)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!MessageDigest.isEqual(magic, MAGIC)) return null
        if (buffer.get().toInt() != KDF_PBKDF2_HMAC_SHA256) return null
        val iterations = buffer.int
        if (iterations < MINIMUM_ITERATIONS) return null
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val sealed = ByteArray(buffer.remaining()).also(buffer::get)
        val header = header(iterations, salt, nonce)
        val wrappingKey = pbkdf2(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(wrappingKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            val secret = cipher.doFinal(sealed)
            if (secret.size != KitBackupKey.KEY_BYTES) null else KitBackupKey(secret)
        } catch (invalid: GeneralSecurityException) {
            // A wrong password and a corrupt envelope are the same event here, and telling them
            // apart would only help somebody guessing.
            null
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun header(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 1 + 4 + salt.size + nonce.size)
            .put(MAGIC)
            .put(KDF_PBKDF2_HMAC_SHA256.toByte())
            .putInt(iterations)
            .put(salt)
            .put(nonce)
            .array()

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KitBackupKey.KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/** HKDF-SHA256 (RFC 5869): extract-then-expand, so a subkey never reveals its parent. */
internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..(255 * 32)) { "Unsupported derived key length" }
    val extract = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
    }
    val prk = extract.doFinal(ikm)
    val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
    val output = ByteArray(length)
    var previous = ByteArray(0)
    var written = 0
    var counter = 1
    while (written < length) {
        expand.reset()
        expand.update(previous)
        expand.update(info)
        expand.update(counter.toByte())
        previous = expand.doFinal()
        val take = minOf(previous.size, length - written)
        System.arraycopy(previous, 0, output, written, take)
        written += take
        counter++
    }
    prk.fill(0)
    previous.fill(0)
    return output
}

// Crockford base 32: no I, L, O or U, so nothing in a written-down code reads as something else.
private const val RECOVERY_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val RECOVERY_CHECKSUM_BITS = 4

/**
 * 256 key bits followed by a 4-bit checksum, which is exactly 52 base-32 characters — no padding,
 * and a transposed or mistyped character is caught fifteen times out of sixteen rather than
 * silently producing a key that decrypts nothing.
 */
private fun encodeRecoveryCode(material: ByteArray): String {
    val checksum = recoveryChecksum(material)
    val builder = StringBuilder(KitBackupKey.RECOVERY_CODE_CHARS)
    var buffer = 0L
    var bits = 0
    material.forEach { byte ->
        buffer = (buffer shl 8) or (byte.toLong() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            builder.append(RECOVERY_ALPHABET[((buffer shr bits) and 0x1f).toInt()])
        }
    }
    // 256 is not a multiple of 5: one bit is left over and the checksum completes the last group.
    buffer = (buffer shl RECOVERY_CHECKSUM_BITS) or checksum.toLong()
    bits += RECOVERY_CHECKSUM_BITS
    while (bits >= 5) {
        bits -= 5
        builder.append(RECOVERY_ALPHABET[((buffer shr bits) and 0x1f).toInt()])
    }
    return builder.toString()
}

private fun decodeRecoveryCode(code: String): ByteArray? {
    val normalized = buildString(KitBackupKey.RECOVERY_CODE_CHARS) {
        code.forEach { raw ->
            when (val character = raw.uppercaseChar()) {
                ' ', '-', '\t', '\n', '\r' -> Unit
                // The characters Crockford leaves out are the ones people write anyway.
                'O' -> append('0')
                'I', 'L' -> append('1')
                else -> append(character)
            }
        }
    }
    if (normalized.length != KitBackupKey.RECOVERY_CODE_CHARS) return null
    var buffer = 0L
    var bits = 0
    val material = ByteArray(KitBackupKey.KEY_BYTES)
    var written = 0
    normalized.forEach { character ->
        val value = RECOVERY_ALPHABET.indexOf(character)
        if (value < 0) return null
        buffer = (buffer shl 5) or value.toLong()
        bits += 5
        if (bits >= 8 && written < material.size) {
            bits -= 8
            material[written++] = ((buffer shr bits) and 0xff).toByte()
        }
    }
    if (written != material.size) return null
    val checksum = (buffer and 0x0f).toInt()
    if (checksum != recoveryChecksum(material)) {
        material.fill(0)
        return null
    }
    return material
}

private fun recoveryChecksum(material: ByteArray): Int =
    MessageDigest.getInstance("SHA-256").digest(material)[0].toInt() and 0x0f
