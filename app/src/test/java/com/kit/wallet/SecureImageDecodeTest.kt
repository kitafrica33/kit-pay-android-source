package com.kit.wallet

import com.kit.wallet.feature.chat.decodeBoundedSecureImage
import com.kit.wallet.feature.chat.withOwnedSecureMediaSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureImageDecodeTest {
    @Test
    fun `malformed image decoder input fails closed without throwing`() {
        assertNull(decodeBoundedSecureImage("not-an-image".toByteArray()))
    }

    @Test
    fun `decoder snapshot survives cache eviction and is zeroized after decode`() = runTest {
        val cached = byteArrayOf(1, 2, 3, 4)
        val decoderStarted = CompletableDeferred<ByteArray>()
        val releaseDecoder = CompletableDeferred<Unit>()
        val decode = async {
            withOwnedSecureMediaSnapshot(cached) { owned ->
                decoderStarted.complete(owned)
                releaseDecoder.await()
                owned.copyOf()
            }
        }
        val owned = decoderStarted.await()

        // Simulates cache eviction/onCleared while a decoder still owns its input snapshot.
        cached.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), owned)
        releaseDecoder.complete(Unit)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decode.await())
        assertTrue(owned.all { it == 0.toByte() })
    }
}
