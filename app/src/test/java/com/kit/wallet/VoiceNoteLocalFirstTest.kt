package com.kit.wallet

import com.kit.wallet.data.messaging.ImmediateSendIntent
import com.kit.wallet.data.messaging.ImmediateSendIntentStore
import com.kit.wallet.data.messaging.ImmediateSendKind
import com.kit.wallet.data.messaging.ImmediateSendState
import com.kit.wallet.data.messaging.ImmediateMediaSpool
import com.kit.wallet.data.messaging.LocalMediaAvailabilityState
import com.kit.wallet.data.messaging.LocalMediaCollection
import com.kit.wallet.data.messaging.LocalMediaDownloadState
import com.kit.wallet.data.messaging.LocalMediaEncryptionState
import com.kit.wallet.data.messaging.LocalMediaLibrary
import com.kit.wallet.data.messaging.LocalMediaProcessingState
import com.kit.wallet.data.messaging.LocalMediaRecord
import com.kit.wallet.data.messaging.LocalMediaUploadState
import com.kit.wallet.feature.chat.VoiceNoteRecorder
import java.io.FileInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression coverage for voice notes whose useful lifetime is longer than one UI process. */
class VoiceNoteLocalFirstTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a voice note over two minutes stays file backed and keeps duration across restart`() =
        runTest {
            val original = temporaryFolder.newFile("long-voice-note.m4a")
            val scratch = ByteArray(64 * 1_024) { index -> (index % 251).toByte() }
            original.outputStream().buffered().use { output ->
                repeat(9) { output.write(scratch) }
            }
            scratch.fill(0)
            val durationMillis = 121_000L
            val recording = VoiceNoteRecorder.Recording(listOf(original), durationMillis)

            try {
                val source = recording.source
                assertEquals(original.length(), source.declaredByteCount)
                assertEquals(durationMillis, source.durationMillis)
                assertSame(original, source.localPlaybackFile)
                source.open().use { input ->
                    // The send source is a seekable file stream, not one attachment-sized array.
                    assertTrue(input is FileInputStream)
                    assertEquals(original.inputStream().use { it.read() }, input.read())
                }

                val state = TestSecureMessagingStateStore()
                val sessions = MutableTestSessionStore(testSession(OWNER_ID))
                val owner = checkNotNull(sessions.current()).fence()
                val intent = ImmediateSendIntent(
                    id = MESSAGE_ID,
                    conversationId = CONVERSATION_ID,
                    kind = ImmediateSendKind.MEDIA,
                    createdAtEpochMillis = NOW,
                    state = ImmediateSendState.PREPARING,
                    mediaType = VoiceNoteRecorder.Recording.MEDIA_TYPE,
                    mediaPlaintextBytes = original.length().toInt(),
                    mediaOriginalPlaintextBytes = original.length().toInt(),
                    mediaDurationMillis = durationMillis,
                )
                ImmediateSendIntentStore(state, sessions).enqueueForOwner(owner, intent)

                // The real outbox cipher consumes the file source and produces another file;
                // neither its API nor this path ever asks the caller for attachment-sized bytes.
                val spool = ImmediateMediaSpool(temporaryFolder.newFolder("voice-note-spool"))
                val material = spool.stage(MESSAGE_ID, source)
                val encrypted = intent.copy(
                    state = ImmediateSendState.WAITING,
                    mediaPlaintextBytes = material.plaintextBytes,
                    mediaCiphertextBytes = material.ciphertextBytes,
                    mediaKeyBase64 = material.keyBase64,
                    mediaSha256Base64 = material.sha256Base64,
                )
                assertEquals(original.length(), material.plaintextBytes.toLong())
                assertTrue(spool.ciphertextFile(encrypted).length() > original.length())

                val restartedQueue = ImmediateSendIntentStore(state, sessions)
                restartedQueue.loadForCurrentOwner()
                assertEquals(durationMillis, restartedQueue.items.value.single().mediaDurationMillis)

                val record = LocalMediaRecord(
                    ownerScopeId = owner.cacheScopeId,
                    conversationId = CONVERSATION_ID,
                    messageId = MESSAGE_ID,
                    mediaId = MESSAGE_ID,
                    collection = LocalMediaCollection.SENT,
                    localReference = "sent:$MESSAGE_ID",
                    mimeType = VoiceNoteRecorder.Recording.MEDIA_TYPE,
                    fileSize = original.length(),
                    durationMillis = durationMillis,
                    processingState = LocalMediaProcessingState.ORIGINAL_READY,
                    uploadState = LocalMediaUploadState.PENDING,
                    downloadState = LocalMediaDownloadState.NOT_REQUIRED,
                    encryptionState = LocalMediaEncryptionState.PENDING,
                    availabilityState = LocalMediaAvailabilityState.AVAILABLE,
                    selectedAtEpochMillis = NOW,
                    visibleAtEpochMillis = NOW,
                    localAvailableAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                )
                LocalMediaLibrary(state, sessions).recordLocalOriginal(owner, record)

                val restartedLibrary = LocalMediaLibrary(state, sessions)
                assertEquals(
                    durationMillis,
                    restartedLibrary.find(owner, MESSAGE_ID)?.durationMillis,
                )
            } finally {
                recording.release()
                assertFalse(original.exists())
            }
        }

    private companion object {
        const val OWNER_ID = "11111111-1111-4111-8111-111111111111"
        const val CONVERSATION_ID = "conversation-voice"
        const val MESSAGE_ID = "22222222-2222-4222-8222-222222222222"
        const val NOW = 1_700_000_000_000L
    }
}
