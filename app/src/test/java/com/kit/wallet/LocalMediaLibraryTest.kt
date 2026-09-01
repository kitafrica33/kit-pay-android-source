package com.kit.wallet

import com.kit.wallet.data.messaging.LocalMediaAvailabilityState
import com.kit.wallet.data.messaging.LocalMediaCollection
import com.kit.wallet.data.messaging.LocalMediaDownloadState
import com.kit.wallet.data.messaging.LocalMediaEncryptionState
import com.kit.wallet.data.messaging.LocalMediaLibrary
import com.kit.wallet.data.messaging.LocalMediaProcessingState
import com.kit.wallet.data.messaging.LocalMediaRecord
import com.kit.wallet.data.messaging.LocalMediaRecordCodec
import com.kit.wallet.data.messaging.LocalMediaUploadState
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaLibraryTest {
    private val sessions = MutableTestSessionStore(testSession(OWNER))
    private val owner = checkNotNull(sessions.current()).fence()

    @Test fun `unknown importing size is durable and becomes exact when original publishes`() = runTest {
        val state = TestSecureMessagingStateStore()
        val first = LocalMediaLibrary(state, sessions)
        val pending = pendingRecord(fileSize = 0)

        first.recordImportPending(owner, pending)
        assertEquals(pending, first.find(owner, MEDIA_ID))

        // Simulate process recreation: identity and the opaque final-file reference survive.
        val restarted = LocalMediaLibrary(state, sessions)
        val restored = checkNotNull(restarted.find(owner, MEDIA_ID))
        assertEquals(MEDIA_ID, restored.mediaId)
        assertEquals(MESSAGE_ID, restored.messageId)
        assertEquals("sent:$MEDIA_ID", restored.localReference)
        assertEquals(0L, restored.fileSize)
        assertNull(restored.localAvailableAtEpochMillis)

        restarted.recordLocalOriginal(
            owner,
            restored.copy(
                fileSize = 4_096,
                processingState = LocalMediaProcessingState.ORIGINAL_READY,
                availabilityState = LocalMediaAvailabilityState.AVAILABLE,
                localAvailableAtEpochMillis = NOW + 20,
                updatedAtEpochMillis = NOW + 20,
            ),
        )
        val ready = checkNotNull(restarted.find(owner, MEDIA_ID))
        assertEquals(4_096L, ready.fileSize)
        assertEquals(LocalMediaAvailabilityState.AVAILABLE, ready.availabilityState)
        assertEquals(LocalMediaProcessingState.ORIGINAL_READY, ready.processingState)
        assertEquals(NOW + 20, ready.localAvailableAtEpochMillis)
    }

    @Test fun `pending local media codec keeps permanent linkage fields`() {
        val record = pendingRecord(fileSize = 123)
        val encoded = LocalMediaRecordCodec.encode(record)
        try {
            assertEquals(record, LocalMediaRecordCodec.decode(encoded))
        } finally {
            encoded.fill(0)
        }
    }

    @Test fun `legacy invalid duration is discarded without orphaning local media`() {
        val record = pendingRecord(fileSize = 123).copy(durationMillis = 1)
        val encoded = LocalMediaRecordCodec.encode(record)
        try {
            overwriteEncodedDuration(encoded, 0)
            assertEquals(record.copy(durationMillis = null), LocalMediaRecordCodec.decode(encoded))
        } finally {
            encoded.fill(0)
        }
    }

    @Test fun `edited source larger than wire cap remains a valid durable local original`() {
        val record = pendingRecord(fileSize = 240_000_000).copy(
            processingState = LocalMediaProcessingState.ORIGINAL_READY,
            availabilityState = LocalMediaAvailabilityState.AVAILABLE,
            localAvailableAtEpochMillis = NOW + 1,
            updatedAtEpochMillis = NOW + 1,
        )
        val encoded = LocalMediaRecordCodec.encode(record)
        try {
            assertEquals(record, LocalMediaRecordCodec.decode(encoded))
        } finally {
            encoded.fill(0)
        }
    }

    private fun pendingRecord(fileSize: Long) = LocalMediaRecord(
        ownerScopeId = owner.cacheScopeId,
        conversationId = CONVERSATION_ID,
        messageId = MESSAGE_ID,
        mediaId = MEDIA_ID,
        collection = LocalMediaCollection.SENT,
        localReference = "sent:$MEDIA_ID",
        mimeType = "video/mp4",
        fileSize = fileSize,
        processingState = LocalMediaProcessingState.PROCESSING,
        uploadState = LocalMediaUploadState.PENDING,
        downloadState = LocalMediaDownloadState.NOT_REQUIRED,
        encryptionState = LocalMediaEncryptionState.PENDING,
        availabilityState = LocalMediaAvailabilityState.MISSING,
        selectedAtEpochMillis = NOW,
        visibleAtEpochMillis = NOW,
        localAvailableAtEpochMillis = null,
        updatedAtEpochMillis = NOW,
    )

    private fun overwriteEncodedDuration(encoded: ByteArray, durationMillis: Long) {
        val bytes = ByteArrayInputStream(encoded)
        DataInputStream(bytes).use { input ->
            input.readUnsignedByte()
            repeat(4) { input.skipEncodedString() }
            input.skipEncodedString() // collection
            input.skipEncodedString() // local reference
            if (input.readBoolean()) input.skipEncodedString() // remote reference
            input.skipEncodedString() // MIME type
            input.readLong() // file size
            check(input.readBoolean())
            val durationOffset = encoded.size - bytes.available()
            ByteBuffer.wrap(encoded, durationOffset, Long.SIZE_BYTES).putLong(durationMillis)
        }
    }

    private fun DataInputStream.skipEncodedString() {
        val byteCount = readInt()
        check(byteCount >= 0)
        check(skipBytes(byteCount) == byteCount)
    }

    private companion object {
        const val OWNER = "11111111-1111-4111-8111-111111111111"
        const val CONVERSATION_ID = "conversation-1"
        const val MESSAGE_ID = "22222222-2222-4222-8222-222222222222"
        const val MEDIA_ID = "33333333-3333-4333-8333-333333333333"
        const val NOW = 1_700_000_000_000L
    }
}
