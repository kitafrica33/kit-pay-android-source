package com.kit.wallet

import com.kit.wallet.data.messaging.readBoundedMedia
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMediaIoTest {
    @Test
    fun readsExactlyTheAllowedNumberOfBytes() {
        val expected = ByteArray(32) { it.toByte() }

        val actual = ByteArrayInputStream(expected).use { it.readBoundedMedia(expected.size) }

        assertArrayEquals(expected, actual)
    }

    @Test
    fun rejectsAStreamAsSoonAsItExceedsTheLimit() {
        val failure = runCatching {
            ByteArrayInputStream(ByteArray(33)).use { it.readBoundedMedia(32) }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsAnEmptyMediaStream() {
        val failure = runCatching {
            ByteArrayInputStream(byteArrayOf()).use { it.readBoundedMedia(32) }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `scratch input buffer is zeroized on success and rejection`() {
        val successful = RecordingInputStream(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), successful.readBoundedMedia(3))
        assertNotNull(successful.observedBuffer)
        assertTrue(checkNotNull(successful.observedBuffer).all { it == 0.toByte() })

        val oversized = RecordingInputStream(byteArrayOf(4, 5, 6))
        assertTrue(runCatching { oversized.readBoundedMedia(2) }.isFailure)
        assertNotNull(oversized.observedBuffer)
        assertTrue(checkNotNull(oversized.observedBuffer).all { it == 0.toByte() })
    }

    private class RecordingInputStream(private val bytes: ByteArray) : InputStream() {
        var observedBuffer: ByteArray? = null
            private set
        private var offset = 0

        override fun read(): Int = if (offset >= bytes.size) -1 else bytes[offset++].toInt() and 0xff

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            observedBuffer = buffer
            if (offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }
    }
}
