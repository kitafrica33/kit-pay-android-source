package com.kit.wallet.data.messaging

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

// The same cap iOS enforces, and the one the service advertises. It is only safe to state because
// the media pipeline is file-backed end to end: MediaAttachmentStreamCipher encrypts and decrypts
// through a fixed buffer, uploads stream off the spool file, downloads stream into a cache file,
// and the UI holds decrypted attachments as files rather than arrays. Nothing on either path is
// obliged to fit an attachment in heap, so the cap is a product decision rather than a heap-size
// one. Local admission uses this compiled cap without networking; background dispatch still clamps
// to the service's coherent advertisement before transmission.
internal const val MAX_IMAGE_PLAINTEXT_BYTES = 200 * 1024 * 1024
internal const val MAX_IMAGE_CIPHERTEXT_BYTES = 200L * 1024L * 1024L + 64L

/**
 * Copies at most [maximumBytes] from this stream into [destination], returning what it copied.
 *
 * Nothing is accumulated: this is the receive-side counterpart to the streaming cipher, and it
 * exists so a download can be bounded without ever holding the blob it is bounding.
 */
internal fun InputStream.copyBoundedTo(destination: OutputStream, maximumBytes: Long): Long {
    require(maximumBytes > 0) { "A media copy requires a positive byte limit" }
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    try {
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total = Math.addExact(total, count.toLong())
            if (total > maximumBytes) {
                throw IllegalStateException(
                    "The encrypted attachment exceeds its authenticated size",
                )
            }
            destination.write(buffer, 0, count)
        }
        destination.flush()
    } finally {
        buffer.fill(0)
    }
    return total
}

/** SHA-256 of a file's contents without reading the file into one array. */
internal fun File.streamingSha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    try {
        inputStream().buffered().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    } finally {
        buffer.fill(0)
    }
    return digest.digest()
}

/** Reads an untrusted picker/media stream without allocating beyond the authenticated limit. */
internal fun InputStream.readBoundedMedia(maximumBytes: Int): ByteArray =
    readBoundedBytes(
        maximumBytes = maximumBytes,
        overflow = {
            throw IllegalArgumentException(
                "Images up to ${maximumBytes / (1024 * 1024)} MB are supported",
            )
        },
    ).also { bytes ->
        require(bytes.isNotEmpty()) { "The selected photo is empty" }
    }

/** Ciphertext variant preserves transport check/error semantics while sharing wiping storage. */
internal fun InputStream.readBoundedAttachmentCiphertext(maximumBytes: Long): ByteArray {
    check(maximumBytes in 1..Int.MAX_VALUE.toLong()) { "Invalid attachment download bound" }
    return readBoundedBytes(
        maximumBytes = maximumBytes.toInt(),
        overflow = {
            throw IllegalStateException(
                "The encrypted attachment exceeds its authenticated size",
            )
        },
    )
}

private inline fun InputStream.readBoundedBytes(
    maximumBytes: Int,
    overflow: () -> Nothing,
): ByteArray {
    require(maximumBytes > 0) { "A media read requires a positive byte limit" }
    val output = WipingMediaAccumulator(minOf(maximumBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    try {
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total = Math.addExact(total, count)
            if (total > maximumBytes) overflow()
            output.write(buffer, 0, count)
        }
        return output.toOwnedByteArray()
    } finally {
        buffer.fill(0)
        output.close()
    }
}

/** Clones once and erases both replaced growth buffers and the final accumulator. */
private class WipingMediaAccumulator(initialSize: Int) {
    private var buffer = ByteArray(initialSize)
    private var count = 0

    fun write(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length)
        val required = Math.addExact(count, length)
        if (required > buffer.size) {
            val doubled = (buffer.size.toLong() * 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val replacement = ByteArray(maxOf(required, doubled))
            buffer.copyInto(replacement, endIndex = count)
            buffer.fill(0)
            buffer = replacement
        }
        source.copyInto(buffer, destinationOffset = count, startIndex = offset, endIndex = offset + length)
        count = required
    }

    fun toOwnedByteArray(): ByteArray = buffer.copyOf(count)

    fun close() {
        buffer.fill(0)
        count = 0
    }
}
