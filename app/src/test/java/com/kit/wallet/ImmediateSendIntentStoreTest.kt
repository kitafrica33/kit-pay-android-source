package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMediaSource
import com.kit.wallet.data.messaging.ImmediateMediaSpool
import com.kit.wallet.data.messaging.ImmediateSendIntent
import com.kit.wallet.data.messaging.ImmediateSendIntentCodec
import com.kit.wallet.data.messaging.ImmediateSendIntentStore
import com.kit.wallet.data.messaging.ImmediateSendKind
import com.kit.wallet.data.messaging.ImmediateSendMediaItem
import com.kit.wallet.data.messaging.ImmediateSendState
import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.messaging.KitReactionAction
import com.kit.wallet.data.messaging.KitReactionMessage
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.session.SessionInvalidatedException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateSendIntentStoreTest {
    private val disk = TestSecureMessagingStateStore()
    private val sessions = MutableTestSessionStore(testSession(OWNER_A))

    @Test fun `text payment and reaction intents round trip canonically`() {
        val payment = KitPaymentMessage(
            action = KitPaymentAction.TRANSFER,
            referenceId = REFERENCE_ID,
            amountMinor = 500,
            currencyCode = "UGX",
            currencyScale = 0,
            note = "Lunch",
        ).encode()
        val reaction = KitReactionMessage(
            targetMessageId = TARGET_ID,
            emoji = "👍",
            action = KitReactionAction.ADD,
        ).encode()
        val intents = listOf(
            textIntent(),
            textIntent(ID_TWO).copy(kind = ImmediateSendKind.PAYMENT_EVENT, text = payment),
            textIntent(ID_THREE).copy(kind = ImmediateSendKind.REACTION, text = reaction),
        )

        intents.forEach { intent ->
            val encoded = ImmediateSendIntentCodec.encode(intent)
            assertEquals(intent, ImmediateSendIntentCodec.decode(encoded))
            encoded.fill(0)
        }
        assertEquals(null, ImmediateSendIntentCodec.decode(byteArrayOf(99)))
    }

    @Test fun `a queued album round trips every field including its terminal state`() {
        val key = Base64.getEncoder().encodeToString(
            ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES),
        )
        // The mid-upload shape process death must restore exactly: the first item's storage key
        // is already confirmed, the second still has none.
        val partial = ImmediateSendIntent(
            id = ID_ONE,
            conversationId = CONVERSATION_ID,
            kind = ImmediateSendKind.MEDIA_V2,
            createdAtEpochMillis = NOW,
            caption = "Two for you",
            replyToMessageId = TARGET_ID,
            mediaItems = listOf(
                ImmediateSendMediaItem(
                    attachmentId = ATTACHMENT_ONE,
                    mediaType = "image/jpeg",
                    plaintextBytes = 1_024,
                    ciphertextBytes = 1_088,
                    keyBase64 = key,
                    ciphertextSha256Hex = "1".repeat(64),
                    storageKey = STORAGE_ONE,
                ),
                ImmediateSendMediaItem(
                    attachmentId = ATTACHMENT_TWO,
                    mediaType = "video/mp4",
                    plaintextBytes = 2_048,
                    ciphertextBytes = 2_112,
                    keyBase64 = key,
                    ciphertextSha256Hex = "2".repeat(64),
                ),
            ),
        )
        val uploaded = partial.copy(
            mediaItems = partial.mediaItems.map {
                it.copy(storageKey = it.storageKey ?: STORAGE_TWO)
            },
        )
        val sealed = uploaded.copy(preparedMediaDescriptor = uploaded.buildAlbumDescriptor())

        listOf(
            partial,
            partial.copy(state = ImmediateSendState.RETRY_REQUIRED),
            partial.copy(state = ImmediateSendState.FAILED),
            sealed,
        ).forEach { intent ->
            val encoded = ImmediateSendIntentCodec.encode(intent)
            assertEquals(intent, ImmediateSendIntentCodec.decode(encoded))
            encoded.fill(0)
        }
    }

    @Test fun `pre-album queue records are still read exactly as written`() {
        val withReply = textIntent().copy(replyToMessageId = TARGET_ID)
        assertEquals(withReply, ImmediateSendIntentCodec.decode(legacyRecord(2, TARGET_ID)))
        assertEquals(textIntent(), ImmediateSendIntentCodec.decode(legacyRecord(1, null)))
    }

    @Test fun `queued text enforces the exact encrypted wire scalar policy`() {
        assertEquals(8_000, textIntent().copy(text = "a".repeat(8_000)).text.length)
        assertThrows(IllegalArgumentException::class.java) {
            textIntent().copy(text = "a".repeat(8_001))
        }
        assertThrows(IllegalArgumentException::class.java) {
            textIntent().copy(text = "contains\u0000nul")
        }
        assertThrows(IllegalArgumentException::class.java) {
            textIntent().copy(text = "unpaired \uD800 surrogate")
        }
    }

    @Test fun `a committed intent publishes immediately and survives restart`() = runTest {
        val first = ImmediateSendIntentStore(disk, sessions)
        val intent = textIntent()

        val owner = first.enqueue(intent)

        assertEquals(listOf(intent), first.items.value)
        assertEquals(intent, first.itemsForOwner(owner).single())
        val restarted = ImmediateSendIntentStore(disk, sessions)
        restarted.loadForCurrentOwner()
        assertEquals(listOf(intent), restarted.items.value)
    }

    @Test fun `retry state and removal are durable across restart`() = runTest {
        val first = ImmediateSendIntentStore(disk, sessions)
        val owner = first.enqueue(textIntent())
        val original = first.items.value.single()
        assertTrue(first.markRetryRequiredForOwner(owner, original))
        assertEquals(ImmediateSendState.RETRY_REQUIRED, first.items.value.single().state)

        val restarted = ImmediateSendIntentStore(disk, sessions)
        restarted.loadForCurrentOwner()
        assertEquals(ImmediateSendState.RETRY_REQUIRED, restarted.items.value.single().state)
        assertTrue(restarted.rearmForOwner(owner, ID_ONE))
        restarted.removeForOwner(owner, ID_ONE)

        val emptyRestart = ImmediateSendIntentStore(disk, sessions)
        emptyRestart.loadForCurrentOwner()
        assertTrue(emptyRestart.items.value.isEmpty())
    }

    @Test fun `idempotent enqueue acknowledges exact replay and rejects identity reuse`() = runTest {
        val store = ImmediateSendIntentStore(disk, sessions)
        val owner = checkNotNull(sessions.current()).fence()
        val intent = textIntent()

        store.enqueueIdempotentForOwner(owner, intent)
        store.enqueueIdempotentForOwner(
            owner,
            intent.copy(createdAtEpochMillis = intent.createdAtEpochMillis + 1),
        )
        assertEquals(listOf(intent), store.items.value)

        assertTrue(store.markRetryRequiredForOwner(owner, intent))
        store.enqueueIdempotentForOwner(owner, intent)
        assertEquals(ImmediateSendState.WAITING, store.items.value.single().state)

        assertTrue(
            runCatching {
                store.enqueueIdempotentForOwner(owner, intent.copy(text = "different content"))
            }.isFailure,
        )
        assertEquals("queued before roster work", store.items.value.single().text)
    }

    @Test fun `account switch hides plaintext and rejects an obsolete writer`() = runTest {
        val store = ImmediateSendIntentStore(disk, sessions)
        val oldOwner = store.enqueue(textIntent())
        disk.eraseAll()
        sessions.save(testSession(OWNER_B))

        assertTrue(store.items.value.isEmpty())
        assertTrue(
            runCatching { store.enqueueForOwner(oldOwner, textIntent(ID_TWO)) }
                .exceptionOrNull() is SessionInvalidatedException,
        )
        store.enqueue(textIntent(ID_ONE).copy(text = "new owner"))
        assertEquals("new owner", store.items.value.single().text)
    }

    @Test fun `media spool writes only ciphertext and restores the exact plaintext`() = runTest {
        val directory = Files.createTempDirectory("kit-immediate-media-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val plaintext = "private photo bytes that must never be written".toByteArray()
            val material = spool.stage(ID_ONE, SecureMediaSource.ofBytes(plaintext))
            val intent = ImmediateSendIntent(
                id = ID_ONE,
                conversationId = CONVERSATION_ID,
                kind = ImmediateSendKind.MEDIA,
                createdAtEpochMillis = NOW,
                mediaType = "image/jpeg",
                mediaPlaintextBytes = material.plaintextBytes,
                mediaCiphertextBytes = material.ciphertextBytes,
                mediaKeyBase64 = material.keyBase64,
                mediaSha256Base64 = material.sha256Base64,
            )

            val ciphertext = spool.ciphertextFile(intent).readBytes()
            assertFalse(ciphertext.contentEquals(plaintext))
            assertFalse(String(ciphertext, Charsets.UTF_8).contains(String(plaintext)))
            val key = Base64.getDecoder().decode(material.keyBase64)
            val digest = Base64.getDecoder().decode(material.sha256Base64)
            val restored = MediaAttachmentCipher.decrypt(ciphertext, key, digest)
            assertArrayEquals(plaintext, restored)

            ciphertext.fill(0)
            key.fill(0)
            digest.fill(0)
            restored.fill(0)
            plaintext.fill(0)
            spool.discard(ID_ONE)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `staged media survives a stale prune until its durable intent is observed`() = runTest {
        val directory = Files.createTempDirectory("kit-immediate-media-prune-test").toFile()
        try {
            val spool = ImmediateMediaSpool(directory)
            val plaintext = "ciphertext awaiting its durable queue record".toByteArray()
            val material = spool.stage(ID_ONE, SecureMediaSource.ofBytes(plaintext))
            val intent = ImmediateSendIntent(
                id = ID_ONE,
                conversationId = CONVERSATION_ID,
                kind = ImmediateSendKind.MEDIA,
                createdAtEpochMillis = NOW,
                mediaType = "image/jpeg",
                mediaPlaintextBytes = material.plaintextBytes,
                mediaCiphertextBytes = material.ciphertextBytes,
                mediaKeyBase64 = material.keyBase64,
                mediaSha256Base64 = material.sha256Base64,
            )

            // This snapshot may have been taken immediately before stage() committed the file.
            spool.prune(emptySet())
            val retainedCiphertext = spool.ciphertextFile(intent).readBytes()
            assertEquals(material.ciphertextBytes, retainedCiphertext.size)
            retainedCiphertext.fill(0)

            // Once prune observes the durable intent, the one-pass reservation is discharged.
            spool.prune(setOf(ID_ONE))
            spool.prune(emptySet())
            assertTrue(directory.listFiles().orEmpty().isEmpty())

            plaintext.fill(0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `queued media caption enforces the wire utf8 byte limit`() {
        val key = Base64.getEncoder().encodeToString(
            ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES),
        )
        val digest = Base64.getEncoder().encodeToString(ByteArray(32))
        val atLimit = "é".repeat(1_024)

        val accepted = ImmediateSendIntent(
            id = ID_ONE,
            conversationId = CONVERSATION_ID,
            kind = ImmediateSendKind.MEDIA,
            createdAtEpochMillis = NOW,
            mediaType = "image/jpeg",
            caption = atLimit,
            mediaPlaintextBytes = 1,
            mediaCiphertextBytes = 17,
            mediaKeyBase64 = key,
            mediaSha256Base64 = digest,
        )

        assertEquals(atLimit, accepted.caption)
        assertThrows(IllegalArgumentException::class.java) {
            accepted.copy(id = ID_TWO, caption = atLimit + "é")
        }
    }

    private fun textIntent(id: String = ID_ONE) = ImmediateSendIntent(
        id = id,
        conversationId = CONVERSATION_ID,
        kind = ImmediateSendKind.TEXT,
        createdAtEpochMillis = NOW,
        text = "queued before roster work",
    )

    /** Byte-for-byte what the codec wrote for a text send before the album (and reply) fields. */
    private fun legacyRecord(version: Int, replyToMessageId: String?): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(version)
            data.writeByte(ImmediateSendKind.TEXT.ordinal)
            data.writeByte(ImmediateSendState.WAITING.ordinal)
            data.writeLegacyString(ID_ONE)
            data.writeLegacyString(CONVERSATION_ID)
            data.writeLong(NOW)
            data.writeLegacyString("queued before roster work")
            data.writeBoolean(false) // mediaType
            data.writeBoolean(false) // caption
            data.writeInt(0)
            data.writeInt(0)
            data.writeBoolean(false) // mediaKeyBase64
            data.writeBoolean(false) // mediaSha256Base64
            data.writeBoolean(false) // preparedMediaDescriptor
            if (version >= 2) {
                data.writeBoolean(replyToMessageId != null)
                replyToMessageId?.let { data.writeLegacyString(it) }
            }
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeLegacyString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        const val OWNER_A = "owner-a"
        const val OWNER_B = "owner-b"
        const val NOW = 1_777_777_777_000L
        const val ID_ONE = "10000000-0000-4000-8000-000000000001"
        const val ID_TWO = "10000000-0000-4000-8000-000000000002"
        const val ID_THREE = "10000000-0000-4000-8000-000000000003"
        const val CONVERSATION_ID = "20000000-0000-4000-8000-000000000001"
        const val TARGET_ID = "30000000-0000-4000-8000-000000000001"
        const val REFERENCE_ID = "40000000-0000-4000-8000-000000000001"
        const val ATTACHMENT_ONE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val ATTACHMENT_TWO = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val STORAGE_ONE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val STORAGE_TWO = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    }
}
