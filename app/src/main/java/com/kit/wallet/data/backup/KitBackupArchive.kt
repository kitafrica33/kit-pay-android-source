package com.kit.wallet.data.backup

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The container a Kit Pay backup travels in.
 *
 * Google Drive stores the file; it must learn nothing from it. So the archive is a chain of
 * AES-256-GCM chunks under a key derived from the user's [KitBackupKey], compressed first because
 * a conversation is mostly text and encrypting compresses nothing afterwards.
 *
 * Chunked rather than sealed whole for one practical reason: a history of years does not fit in
 * the heap of the phones this app is built for. Nothing larger than one chunk is ever held at
 * once, in either direction.
 *
 * Three things the format takes care to get right:
 *  - Every chunk authenticates the header, so a file cannot be re-labelled or its parameters
 *    edited without every chunk failing.
 *  - Every chunk authenticates its own index, so chunks cannot be reordered or replayed from
 *    another backup made with the same key.
 *  - The last chunk says so, inside the authenticated data. A truncated upload therefore fails to
 *    open rather than restoring a plausible-looking half of somebody's history.
 *
 * Pure Java crypto and streams, no Android: the format is exercised off-device.
 */
object KitBackupArchive {
    private val MAGIC = "KITBAK".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val CIPHER_AES_256_GCM_HKDF_SHA256 = 1
    private const val SALT_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val HEADER_BYTES = 6 + 1 + 1 + SALT_BYTES + 4
    private const val FINAL_CHUNK_FLAG = 1L shl 31

    /** 256 KiB of plaintext per chunk: large enough to compress well, small enough to hold. */
    const val DEFAULT_CHUNK_BYTES = 256 * 1024
    private const val MAX_CHUNK_BYTES = 8 * 1024 * 1024

    private const val ARCHIVE_KEY_INFO = "kit-pay/backup/v1/archive"

    /**
     * Wraps [sink] so everything written to the result is compressed, encrypted and framed.
     *
     * The caller must close the returned stream — that is what writes the final chunk, and a
     * stream that was never closed produces a file that deliberately will not open.
     */
    fun encryptingStream(
        sink: OutputStream,
        key: KitBackupKey,
        random: SecureRandom = SecureRandom(),
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    ): OutputStream {
        require(chunkBytes in 1024..MAX_CHUNK_BYTES) { "Unsupported backup chunk size" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val header = header(salt, chunkBytes)
        sink.write(header)
        val archiveKey = key.derive(salt, ARCHIVE_KEY_INFO)
        return GZIPOutputStream(ChunkedEncryptingStream(sink, archiveKey, header, chunkBytes))
    }

    /** Wraps [source] so reading it yields exactly what was written, or fails. */
    fun decryptingStream(source: InputStream, key: KitBackupKey): InputStream {
        val header = ByteArray(HEADER_BYTES)
        source.readFullyOrThrow(header, "header")
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!MessageDigest.isEqual(magic, MAGIC)) {
            throw KitBackupFormatException("This file is not a Kit Pay backup")
        }
        val version = buffer.get().toInt()
        if (version != FORMAT_VERSION) {
            throw KitBackupFormatException("This backup was written by a newer version of Kit Pay")
        }
        if (buffer.get().toInt() != CIPHER_AES_256_GCM_HKDF_SHA256) {
            throw KitBackupFormatException("This backup uses an unsupported cipher")
        }
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val chunkBytes = buffer.int
        if (chunkBytes !in 1024..MAX_CHUNK_BYTES) {
            throw KitBackupFormatException("This backup declares an unusable chunk size")
        }
        val archiveKey = key.derive(salt, ARCHIVE_KEY_INFO)
        return GZIPInputStream(ChunkedDecryptingStream(source, archiveKey, header, chunkBytes))
    }

    private fun header(salt: ByteArray, chunkBytes: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .put(FORMAT_VERSION.toByte())
            .put(CIPHER_AES_256_GCM_HKDF_SHA256.toByte())
            .put(salt)
            .putInt(chunkBytes)
            .array()

    private fun nonce(index: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).putInt(0).putLong(index).array()

    private fun chunkAad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 8 + 1)
            .put(header)
            .putLong(index)
            .put(if (last) 1 else 0)
            .array()

    private fun cipher(mode: Int, key: ByteArray, index: Long, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce(index)))
            updateAAD(aad)
        }

    private class ChunkedEncryptingStream(
        private val sink: OutputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        chunkBytes: Int,
    ) : OutputStream() {
        private val pending = ByteArray(chunkBytes)
        private var pendingSize = 0
        private var index = 0L
        private var closed = false

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(source: ByteArray, offset: Int, length: Int) {
            check(!closed) { "This backup archive is already closed" }
            var position = offset
            var remaining = length
            while (remaining > 0) {
                val take = minOf(remaining, pending.size - pendingSize)
                System.arraycopy(source, position, pending, pendingSize, take)
                pendingSize += take
                position += take
                remaining -= take
                if (pendingSize == pending.size) emit(last = false)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            // Always emitted, even for an empty archive: the final chunk is what says the file is
            // whole, so there is always exactly one of them.
            emit(last = true)
            key.fill(0)
            sink.flush()
            sink.close()
        }

        private fun emit(last: Boolean) {
            val cipher = cipher(Cipher.ENCRYPT_MODE, key, index, chunkAad(header, index, last))
            val sealed = cipher.doFinal(pending, 0, pendingSize)
            val framed = sealed.size.toLong() or (if (last) FINAL_CHUNK_FLAG else 0L)
            sink.write(ByteBuffer.allocate(4).putInt(framed.toInt()).array())
            sink.write(sealed)
            pending.fill(0, 0, pendingSize)
            pendingSize = 0
            index++
        }
    }

    private class ChunkedDecryptingStream(
        private val source: InputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        private val chunkBytes: Int,
    ) : InputStream() {
        private var plain: ByteArray = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= plain.size) {
                if (finished) return -1
                if (!readChunk()) return -1
            }
            val take = minOf(length, plain.size - position)
            System.arraycopy(plain, position, destination, offset, take)
            position += take
            return take
        }

        override fun close() {
            key.fill(0)
            plain.fill(0)
            source.close()
        }

        private fun readChunk(): Boolean {
            val frame = ByteArray(4)
            source.readFullyOrThrow(frame, "chunk header")
            val framed = ByteBuffer.wrap(frame).int.toLong() and 0xffff_ffffL
            val last = (framed and FINAL_CHUNK_FLAG) != 0L
            val sealedSize = (framed and (FINAL_CHUNK_FLAG - 1)).toInt()
            if (sealedSize < TAG_BYTES || sealedSize > chunkBytes + TAG_BYTES) {
                throw KitBackupFormatException("This backup declares an impossible chunk")
            }
            val sealed = ByteArray(sealedSize)
            source.readFullyOrThrow(sealed, "chunk body")
            plain.fill(0)
            plain = try {
                cipher(Cipher.DECRYPT_MODE, key, index, chunkAad(header, index, last))
                    .doFinal(sealed)
            } catch (invalid: GeneralSecurityException) {
                // Wrong key, edited bytes, reordered chunks and a file that stops early all land
                // here, and none of them may produce a partial restore.
                throw KitBackupIntegrityException(
                    "This backup could not be opened with that recovery key, or it is damaged",
                    invalid,
                )
            }
            position = 0
            index++
            finished = last
            return true
        }
    }
}

/** The bytes are not a Kit Pay backup, or are a version this build cannot read. */
class KitBackupFormatException(message: String) : IOException(message)

/** The bytes are a Kit Pay backup, but the wrong key opened it or something edited it. */
class KitBackupIntegrityException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private fun InputStream.readFullyOrThrow(destination: ByteArray, what: String) {
    var read = 0
    while (read < destination.size) {
        val count = read(destination, read, destination.size - read)
        if (count < 0) {
            throw if (read == 0 && what == "chunk header") {
                KitBackupIntegrityException("This backup ends before it says it does")
            } else {
                EOFException("This backup ends part-way through its $what")
            }
        }
        read += count
    }
}
