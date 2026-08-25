package com.kit.wallet

import com.kit.wallet.data.backup.KitBackupArchive
import com.kit.wallet.data.backup.KitBackupFormatException
import com.kit.wallet.data.backup.KitBackupIntegrityException
import com.kit.wallet.data.backup.KitBackupKey
import com.kit.wallet.data.backup.KitBackupKeyEnvelope
import com.kit.wallet.data.backup.KitBackupManifest
import com.kit.wallet.data.backup.KitBackupPayload
import com.kit.wallet.data.messaging.AccountArchivedMessage
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import java.util.Random
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup format is the one part of this feature nobody can inspect after the fact: once an
 * archive is in Drive it is opaque, and a mistake in it surfaces years later as a restore that
 * quietly returns half a conversation. So the tests here care less about the happy path than about
 * every way a file can arrive wrong.
 */
class KitBackupFormatTest {
    private val key = KitBackupKey.random(SecureRandom())

    @Test fun `a recovery code survives being written down and typed back`() {
        val code = key.formattedRecoveryCode()

        assertEquals(KitBackupKey.RECOVERY_CODE_CHARS, key.recoveryCode().length)
        assertArrayEquals(key.bytes(), KitBackupKey.fromRecoveryCode(code)?.bytes())
    }

    @Test fun `the letters people write instead of digits still open the backup`() {
        // Crockford leaves out I, L, O and U precisely because a person writing 0 and 1 will
        // reproduce them as O and l. Reading a code back has to forgive that, not punish it.
        val code = key.recoveryCode()
        val handwritten = code.map {
            when (it) {
                '0' -> 'O'
                '1' -> 'l'
                else -> it
            }
        }.joinToString("").lowercase().chunked(4).joinToString("-")

        assertArrayEquals(key.bytes(), KitBackupKey.fromRecoveryCode(handwritten)?.bytes())
    }

    @Test fun `a mistyped recovery code is rejected rather than tried`() {
        // A four-bit checksum should catch fifteen single-character slips in sixteen. Measured
        // across every possible slip rather than asserted about one, so the number is real.
        val code = key.recoveryCode()
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var slips = 0
        var caught = 0
        code.indices.forEach { position ->
            alphabet.filterNot { it == code[position] }.forEach { replacement ->
                slips++
                val mistyped = code.substring(0, position) + replacement + code.substring(position + 1)
                if (KitBackupKey.fromRecoveryCode(mistyped) == null) caught++
            }
        }

        assertTrue("caught $caught of $slips single-character slips", caught >= slips * 9 / 10)
    }

    @Test fun `something that is not a recovery code at all is refused`() {
        assertNull(KitBackupKey.fromRecoveryCode(""))
        assertNull(KitBackupKey.fromRecoveryCode(key.recoveryCode().dropLast(1)))
        assertNull(KitBackupKey.fromRecoveryCode(key.recoveryCode() + "7"))
        // U is not in the alphabet and, unlike O and I, is not a plausible mistake for a digit.
        assertNull(KitBackupKey.fromRecoveryCode("U".repeat(KitBackupKey.RECOVERY_CODE_CHARS)))
    }

    @Test fun `a key never prints itself`() {
        assertEquals("KitBackupKey(redacted)", key.toString())
        assertTrue(key.recoveryCode() !in key.toString())
    }

    @Test fun `derived subkeys are bound to what they are for`() {
        val salt = ByteArray(32) { it.toByte() }

        assertArrayEquals(key.derive(salt, "a"), key.derive(salt, "a"))
        assertNotEquals(
            key.derive(salt, "a").toList(),
            key.derive(salt, "b").toList(),
        )
        assertNotEquals(
            key.derive(salt, "a").toList(),
            key.derive(ByteArray(32), "a").toList(),
        )
    }

    @Test fun `a password envelope gives the key back to whoever knows the password`() {
        val envelope = KitBackupKeyEnvelope.seal(key, "correct horse battery".toCharArray())

        assertArrayEquals(
            key.bytes(),
            KitBackupKeyEnvelope.open(envelope, "correct horse battery".toCharArray())?.bytes(),
        )
    }

    @Test fun `a wrong password and a damaged envelope look identical`() {
        val envelope = KitBackupKeyEnvelope.seal(key, "correct horse battery".toCharArray())
        val damaged = envelope.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertNull(KitBackupKeyEnvelope.open(envelope, "correct horse batery".toCharArray()))
        assertNull(KitBackupKeyEnvelope.open(damaged, "correct horse battery".toCharArray()))
        assertNull(KitBackupKeyEnvelope.open(ByteArray(80), "correct horse battery".toCharArray()))
    }

    @Test fun `an envelope cannot be talked into a cheap key derivation`() {
        // The iteration count travels in the envelope so it can be raised later. That means it can
        // also be lowered by whoever holds the file, and it must not be honoured when it is.
        val envelope = KitBackupKeyEnvelope.seal(key, "password".toCharArray())
        val weakened = envelope.copyOf()
        ByteBuffer.wrap(weakened).putInt(8, 1)

        assertNull(KitBackupKeyEnvelope.open(weakened, "password".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) {
            KitBackupKeyEnvelope.seal(key, "password".toCharArray(), iterations = 1)
        }
    }

    @Test fun `an archive gives back exactly what went into it, across many chunks`() {
        val original = incompressible(bytes = 200_000)

        val sealed = seal(original, chunkBytes = 4096)
        val restored = open(sealed)

        assertArrayEquals(original, restored)
        // Worth stating: this only passes because the archive is chunked. A single-shot format
        // would have held all of it, and a real history is a thousand times this size.
        assertTrue(sealed.size > 4096 * 4)
    }

    @Test fun `an empty archive is still a whole archive`() {
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0))))
    }

    @Test fun `nothing recognisable survives encryption`() {
        val secret = "meet me at the usual place".repeat(200).toByteArray()

        val sealed = seal(secret)

        assertTrue(String(sealed, Charsets.ISO_8859_1).contains("meet me").not())
    }

    @Test fun `another key does not open it`() {
        val sealed = seal(incompressible(50_000))

        assertThrows(KitBackupIntegrityException::class.java) {
            open(sealed, KitBackupKey.random(SecureRandom()))
        }
    }

    @Test fun `an edited byte does not open it`() {
        val sealed = seal(incompressible(50_000))
        sealed[sealed.size / 2] = (sealed[sealed.size / 2] + 1).toByte()

        assertThrows(KitBackupIntegrityException::class.java) { open(sealed) }
    }

    @Test fun `an edited header does not open it`() {
        // The header is the one part not covered by a tag of its own, so every chunk authenticates
        // it instead. Re-labelling the file has to break all of them.
        val sealed = seal(incompressible(50_000))
        sealed[10] = (sealed[10] + 1).toByte()

        assertThrows(KitBackupIntegrityException::class.java) { open(sealed) }
    }

    @Test fun `an upload that stopped early does not open it`() {
        // The failure that actually happens: a phone loses signal mid-upload. Restoring the part
        // that made it would look like success and lose the rest of somebody's history.
        val sealed = seal(incompressible(50_000), chunkBytes = 4096)
        val frames = frameBoundaries(sealed)
        val truncated = sealed.copyOf(frames[frames.size - 2])

        assertThrows(KitBackupIntegrityException::class.java) { open(truncated) }
    }

    @Test fun `chunks cannot be swapped around`() {
        val sealed = seal(incompressible(50_000), chunkBytes = 4096)
        val frames = frameBoundaries(sealed)
        val first = sealed.copyOfRange(frames[0], frames[1])
        val second = sealed.copyOfRange(frames[1], frames[2])
        val reordered = sealed.copyOf()
        System.arraycopy(second, 0, reordered, frames[0], second.size)
        System.arraycopy(first, 0, reordered, frames[0] + second.size, first.size)

        assertThrows(KitBackupIntegrityException::class.java) { open(reordered) }
    }

    @Test fun `a file that is not a backup says so plainly`() {
        assertThrows(KitBackupFormatException::class.java) {
            KitBackupArchive.decryptingStream(ByteArrayInputStream(ByteArray(64)), key).read()
        }
        assertThrows(EOFException::class.java) {
            KitBackupArchive.decryptingStream(ByteArrayInputStream(ByteArray(4)), key).read()
        }
    }

    @Test fun `a payload round-trips a conversation through an archive`() {
        val messages = (1..500).map(::message)
        val manifest = KitBackupManifest(OWNER, Instant.ofEpochMilli(1_724_000_000_000L), "0.2.24")

        val file = ByteArrayOutputStream()
        val written = KitBackupArchive.encryptingStream(file, key, chunkBytes = 4096).use {
            KitBackupPayload.write(it, manifest, messages.asSequence())
        }

        val restored = mutableListOf<AccountArchivedMessage>()
        val source = KitBackupArchive.decryptingStream(ByteArrayInputStream(file.toByteArray()), key)
        val readManifest = source.use {
            val opened = KitBackupPayload.open(it)
            KitBackupPayload.readMessages(it, restored::add)
            opened
        }

        assertEquals(500, written)
        assertEquals(manifest, readManifest)
        assertEquals(messages, restored)
    }

    @Test fun `a payload names its owner before it hands over a single message`() {
        // A restore has to be able to refuse another account's history, and refusing it after
        // importing it is not refusing it.
        val payload = payload(messages = listOf(message(1)))

        assertEquals(OWNER, KitBackupPayload.open(ByteArrayInputStream(payload)).ownerAccountId)
    }

    @Test fun `a payload missing its tail is not a partial restore`() {
        val payload = payload(messages = (1..20).map(::message))
        val restored = mutableListOf<AccountArchivedMessage>()
        val source = ByteArrayInputStream(payload.copyOf(payload.size - 40))
        KitBackupPayload.open(source)

        assertThrows(IOException::class.java) {
            KitBackupPayload.readMessages(source, restored::add)
        }
    }

    @Test fun `a payload whose count disagrees with its records is refused`() {
        val payload = payload(messages = (1..3).map(::message))
        // The terminator is the last four bytes: claim a message that is not there.
        ByteBuffer.wrap(payload).putInt(payload.size - 4, 4)
        val source = ByteArrayInputStream(payload)
        KitBackupPayload.open(source)

        assertThrows(KitBackupIntegrityException::class.java) {
            KitBackupPayload.readMessages(source) { }
        }
    }

    @Test fun `a record kind this build has never heard of is stepped over`() {
        // So a phone on an older build restores the messages from a newer one's backup instead of
        // refusing the whole file over something it did not need.
        val messages = (1..3).map(::message)
        val payload = payload(messages)
        val manifestBytes = manifestLength()
        val unknown = ByteBuffer.allocate(5).put(9.toByte()).putInt(0).array()
        val spliced = payload.copyOfRange(0, manifestBytes) +
            unknown +
            payload.copyOfRange(manifestBytes, payload.size)

        val restored = mutableListOf<AccountArchivedMessage>()
        val source = ByteArrayInputStream(spliced)
        KitBackupPayload.open(source)

        assertEquals(3, KitBackupPayload.readMessages(source, restored::add))
        assertEquals(messages, restored)
    }

    @Test fun `a payload that is not one says so`() {
        assertThrows(KitBackupFormatException::class.java) {
            KitBackupPayload.open(ByteArrayInputStream(ByteArray(64)))
        }
    }

    private fun seal(
        plain: ByteArray,
        chunkBytes: Int = KitBackupArchive.DEFAULT_CHUNK_BYTES,
    ): ByteArray {
        val sink = ByteArrayOutputStream()
        KitBackupArchive.encryptingStream(sink, key, SecureRandom(), chunkBytes).use {
            it.write(plain)
        }
        return sink.toByteArray()
    }

    private fun open(sealed: ByteArray, with: KitBackupKey = key): ByteArray =
        KitBackupArchive.decryptingStream(ByteArrayInputStream(sealed), with)
            .use { it.readBytes() }

    private fun payload(messages: List<AccountArchivedMessage>): ByteArray {
        val sink = ByteArrayOutputStream()
        KitBackupPayload.write(sink, MANIFEST, messages.asSequence())
        return sink.toByteArray()
    }

    /** Where each encrypted chunk starts, plus the end, read the way the decoder reads it. */
    private fun frameBoundaries(sealed: ByteArray): List<Int> {
        val boundaries = mutableListOf<Int>()
        var position = ARCHIVE_HEADER_BYTES
        while (position < sealed.size) {
            boundaries += position
            val framed = ByteBuffer.wrap(sealed, position, 4).int.toLong() and 0xffff_ffffL
            position += 4 + (framed and 0x7fff_ffffL).toInt()
        }
        boundaries += sealed.size
        return boundaries
    }

    private fun incompressible(bytes: Int): ByteArray =
        ByteArray(bytes).also(Random(20260825)::nextBytes)

    private fun message(index: Int) = AccountArchivedMessage(
        serverMessageId = uuid(index),
        clientMessageId = uuid(index + 10_000),
        conversationId = CONVERSATION,
        sender = SecureMessagingCryptoAddress(OWNER, DEVICE, 1),
        rosterRevision = "roster-$index",
        replyToMessageId = if (index % 3 == 0) uuid(index - 1) else null,
        sentAt = Instant.ofEpochMilli(1_724_000_000_000L + index),
        text = "Message $index, and a little more text so a chunk fills honestly.",
        deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
    )

    private fun manifestLength(): Int {
        val sink = ByteArrayOutputStream()
        KitBackupPayload.write(sink, MANIFEST, emptySequence())
        // Everything an empty payload contains apart from its manifest is the terminator record.
        return sink.size() - TERMINATOR_BYTES
    }

    private companion object {
        const val ARCHIVE_HEADER_BYTES = 44
        const val TERMINATOR_BYTES = 9
        val OWNER: String = uuid(1)
        val DEVICE: String = uuid(2)
        val CONVERSATION: String = uuid(3)
        val MANIFEST = KitBackupManifest(OWNER, Instant.ofEpochMilli(1_724_000_000_000L), "0.2.24")

        fun uuid(seed: Int): String = UUID(seed.toLong(), seed.toLong() * 31 + 7).toString()
    }
}
