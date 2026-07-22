package com.kit.wallet.data.messaging

import java.io.InputStream

internal const val MAX_IMAGE_PLAINTEXT_BYTES = 10 * 1024 * 1024
internal const val MAX_IMAGE_CIPHERTEXT_BYTES = 10L * 1024L * 1024L + 64L

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
