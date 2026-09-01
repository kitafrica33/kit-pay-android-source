package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMediaProcessingPlan
import com.kit.wallet.feature.chat.IncomingShareRequestPersistence
import com.kit.wallet.feature.chat.IncomingShareQueueFullException
import com.kit.wallet.feature.chat.IncomingTextShare
import com.kit.wallet.feature.chat.SharedInboxBatch
import com.kit.wallet.feature.chat.SharedInboxOwner
import com.kit.wallet.feature.chat.SharedInboxPolicy
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncomingShareRequestPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `publish claim and process recreation retain the exact account-bound request`() {
        val root = temporary.newFolder("inbox")
        val batch = batch(BATCH_ONE)
        val first = IncomingShareRequestPersistence(root)

        assertEquals(batch.id, first.publish(batch).token)
        assertEquals(batch, accepted(first.claim(batch.id, nowMillis = NOW)))

        val recreated = IncomingShareRequestPersistence(root)
        assertEquals(listOf(batch), recreated.restore(nowMillis = NOW).map(::accepted))
        assertTrue(File(root, batch.id).isDirectory)

        recreated.acknowledge(batch.id)
        assertFalse(File(root, batch.id).exists())
        assertTrue(IncomingShareRequestPersistence(root).restore(nowMillis = NOW).isEmpty())
    }

    @Test
    fun `image original and deferred processing plan survive process recreation`() {
        val root = temporary.newFolder("inbox-local-original")
        val directory = File(root, BATCH_ONE).apply { assertTrue(mkdirs()) }
        val bytes = "raw-heic-original".toByteArray()
        File(directory, ITEM_FILE).writeBytes(bytes)
        val batch = SharedInboxBatch(
            id = BATCH_ONE,
            receivedAtMillis = NOW,
            items = listOf(
                com.kit.wallet.feature.chat.SharedInboxItem(
                    id = ITEM_ONE,
                    fileName = ITEM_FILE,
                    mediaType = "image/jpeg",
                    displayName = "Photo.heic",
                    byteCount = bytes.size,
                    originalMediaType = "image/heic",
                    processingPlan = SecureMediaProcessingPlan.CHAT_IMAGE_JPEG,
                ),
            ),
            owner = SharedInboxOwner("session-a", "scope-a", OWNER),
        )

        IncomingShareRequestPersistence(root).publish(batch)

        assertEquals(
            batch,
            accepted(IncomingShareRequestPersistence(root).claim(BATCH_ONE, nowMillis = NOW)),
        )
    }

    @Test
    fun `shared audio duration survives process recreation`() {
        val root = temporary.newFolder("inbox-audio-duration")
        val directory = File(root, BATCH_ONE).apply { assertTrue(mkdirs()) }
        val bytes = "audio-placeholder".toByteArray()
        File(directory, ITEM_FILE).writeBytes(bytes)
        val batch = SharedInboxBatch(
            id = BATCH_ONE,
            receivedAtMillis = NOW,
            items = listOf(
                com.kit.wallet.feature.chat.SharedInboxItem(
                    id = ITEM_ONE,
                    fileName = ITEM_FILE,
                    mediaType = "audio/mp4",
                    displayName = "Voice note.m4a",
                    byteCount = bytes.size,
                    durationMillis = 121_000,
                ),
            ),
            owner = SharedInboxOwner("session-a", "scope-a", OWNER),
        )

        IncomingShareRequestPersistence(root).publish(batch)

        assertEquals(
            batch,
            accepted(IncomingShareRequestPersistence(root).claim(BATCH_ONE, nowMillis = NOW)),
        )
    }

    @Test
    fun `malformed and expired manifests are rejected and retired`() {
        val root = temporary.newFolder("invalid")
        val malformed = batch(BATCH_ONE)
        val store = IncomingShareRequestPersistence(root)
        store.publish(malformed)
        val manifest = File(root, malformed.id).listFiles().orEmpty()
            .single { it.name.startsWith(".request-") }
        manifest.writeText("not a manifest")

        assertNull(store.claim(malformed.id))
        assertFalse(File(root, malformed.id).exists())

        val expired = batch(BATCH_TWO, receivedAtMillis = NOW - SharedInboxPolicy.RETENTION_MILLIS)
        store.publish(expired)
        assertNull(store.claim(expired.id, nowMillis = NOW))
        assertFalse(File(root, expired.id).exists())
    }

    @Test
    fun `destination pin is atomic durable and cannot be changed after restart`() {
        val root = temporary.newFolder("pin")
        val original = batch(BATCH_ONE)
        val first = IncomingShareRequestPersistence(root)
        first.publish(original)

        val pinned = first.pinDestination(original, DIRECT_ONE, albumDelivery = false, nowMillis = NOW)
        assertEquals(DIRECT_ONE, pinned.pinnedConversationId)
        assertEquals(false, pinned.albumDelivery)

        val recreated = IncomingShareRequestPersistence(root)
        assertEquals(
            DIRECT_ONE,
            accepted(recreated.claim(original.id, nowMillis = NOW)).pinnedConversationId,
        )
        // Repeating the choice is safe, and a flipped shape preference cannot re-shape the batch.
        val repinned = recreated.pinDestination(
            original,
            DIRECT_ONE,
            albumDelivery = true,
            nowMillis = NOW,
        )
        assertEquals(DIRECT_ONE, repinned.pinnedConversationId)
        assertEquals(false, repinned.albumDelivery)
        assertThrows(IllegalStateException::class.java) {
            recreated.pinDestination(original, DIRECT_TWO, albumDelivery = false, nowMillis = NOW)
        }
        assertEquals(
            DIRECT_ONE,
            accepted(recreated.claim(original.id, nowMillis = NOW)).pinnedConversationId,
        )
    }

    @Test
    fun `a newer unclaimed request removes superseded plaintext but never a claimed request`() {
        val root = temporary.newFolder("superseded")
        val store = IncomingShareRequestPersistence(root)
        val first = batch(BATCH_ONE)
        val second = batch(BATCH_TWO, receivedAtMillis = NOW + 1)
        val third = batch(BATCH_THREE, receivedAtMillis = NOW + 2)

        store.publish(first)
        store.publish(second)
        assertFalse(File(root, first.id).exists())
        assertTrue(File(root, second.id).isDirectory)

        assertEquals(second, accepted(store.claim(second.id, nowMillis = NOW + 1)))
        store.publish(third)
        assertTrue(File(root, second.id).isDirectory)
        assertTrue(File(root, third.id).isDirectory)
    }

    @Test
    fun `a fifth claimed request is rejected without deleting retained or pinned shares`() {
        val root = temporary.newFolder("bounded")
        val store = IncomingShareRequestPersistence(root)
        val ids = listOf(BATCH_ONE, BATCH_TWO, BATCH_THREE, BATCH_FOUR)
        ids.forEachIndexed { index, id ->
            val current = batch(id, receivedAtMillis = NOW + index)
            store.publish(current, nowMillis = NOW + index)
            assertEquals(current, accepted(store.claim(id, nowMillis = NOW + index)))
            if (index == 0) {
                store.pinDestination(current, DIRECT_ONE, albumDelivery = false, nowMillis = NOW + index)
            }
        }

        val fifth = batch(BATCH_FIVE, receivedAtMillis = NOW + 10)
        assertThrows(IncomingShareQueueFullException::class.java) {
            store.publish(fifth, nowMillis = NOW + 10)
        }

        assertFalse(File(root, fifth.id).exists())
        assertTrue(ids.all { File(root, it).isDirectory })
        assertEquals(
            DIRECT_ONE,
            accepted(store.claim(BATCH_ONE, nowMillis = NOW + 10)).pinnedConversationId,
        )
        assertEquals(4, store.restore(nowMillis = NOW + 10).size)
    }

    @Test
    fun `the delivery shape pins durably with the destination and outlives restarts`() {
        val root = temporary.newFolder("shape")
        val original = batch(BATCH_ONE)
        val store = IncomingShareRequestPersistence(root)
        store.publish(original)

        val pinned = store.pinDestination(original, DIRECT_ONE, albumDelivery = true, nowMillis = NOW)
        assertEquals(true, pinned.albumDelivery)

        val recreated = IncomingShareRequestPersistence(root)
        val restored = accepted(recreated.claim(original.id, nowMillis = NOW))
        assertEquals(DIRECT_ONE, restored.pinnedConversationId)
        assertEquals(true, restored.albumDelivery)
        // The capability reading false after a restart cannot flip the recorded shape back.
        assertEquals(
            true,
            recreated.pinDestination(
                restored,
                DIRECT_ONE,
                albumDelivery = false,
                nowMillis = NOW,
            ).albumDelivery,
        )
    }

    @Test
    fun `a pinned legacy manifest reads back shapeless and keeps per-item delivery`() {
        val root = temporary.newFolder("legacy-pinned")
        writeVersionThreeManifest(root, BATCH_ONE, pinnedConversationId = DIRECT_ONE)

        val store = IncomingShareRequestPersistence(root)
        val restored = accepted(store.claim(BATCH_ONE, nowMillis = NOW))
        assertEquals(DIRECT_ONE, restored.pinnedConversationId)
        assertNull(restored.albumDelivery)

        // A retry after the update may prefer albums, but a batch that may already have queued
        // per-item components must finish per-item.
        val repinned = store.pinDestination(restored, DIRECT_ONE, albumDelivery = true, nowMillis = NOW)
        assertNull(repinned.albumDelivery)
    }

    @Test
    fun `an unpinned legacy manifest accepts a fresh shape on its first pin`() {
        val root = temporary.newFolder("legacy-unpinned")
        writeVersionThreeManifest(root, BATCH_TWO, pinnedConversationId = null)

        val store = IncomingShareRequestPersistence(root)
        val restored = accepted(store.claim(BATCH_TWO, nowMillis = NOW))
        assertNull(restored.pinnedConversationId)
        assertNull(restored.albumDelivery)

        val pinned = store.pinDestination(restored, DIRECT_ONE, albumDelivery = true, nowMillis = NOW)
        assertEquals(true, pinned.albumDelivery)
        assertEquals(
            true,
            accepted(
                IncomingShareRequestPersistence(root).claim(BATCH_TWO, nowMillis = NOW),
            ).albumDelivery,
        )
    }

    @Test
    fun `version four image manifests remain byte compatible and passthrough`() {
        val root = temporary.newFolder("legacy-v4-image")
        writeVersionFourImageManifest(root, BATCH_ONE)

        val item = accepted(
            IncomingShareRequestPersistence(root).claim(BATCH_ONE, nowMillis = NOW),
        ).items.single()

        assertEquals("image/jpeg", item.localMediaType)
        assertEquals(SecureMediaProcessingPlan.PASSTHROUGH, item.processingPlan)
    }

    /** Byte-for-byte what the store wrote before [SharedInboxBatch.albumDelivery] existed. */
    private fun writeVersionThreeManifest(root: File, id: String, pinnedConversationId: String?) {
        val directory = File(root, id)
        assertTrue(directory.mkdirs())
        File(directory, ".request-v1").outputStream().use { stream ->
            DataOutputStream(stream).use { output ->
                output.writeByte(3)
                output.writeVersionThreeString(id)
                output.writeLong(NOW)
                output.writeVersionThreeString("session-a")
                output.writeVersionThreeString("scope-a")
                output.writeBoolean(true)
                output.writeVersionThreeString(OWNER)
                output.writeBoolean(pinnedConversationId != null)
                pinnedConversationId?.let { output.writeVersionThreeString(it) }
                output.writeInt(0)
                output.writeBoolean(true)
                output.writeVersionThreeString("Private text")
            }
        }
    }

    private fun DataOutputStream.writeVersionThreeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    /** Byte-for-byte v4 shape: album routing exists, local-original fields do not. */
    private fun writeVersionFourImageManifest(root: File, id: String) {
        val directory = File(root, id)
        assertTrue(directory.mkdirs())
        val bytes = "already-prepared-jpeg".toByteArray()
        File(directory, ITEM_FILE).writeBytes(bytes)
        File(directory, ".request-v1").outputStream().use { stream ->
            DataOutputStream(stream).use { output ->
                output.writeByte(4)
                output.writeVersionThreeString(id)
                output.writeLong(NOW)
                output.writeVersionThreeString("session-a")
                output.writeVersionThreeString("scope-a")
                output.writeBoolean(true)
                output.writeVersionThreeString(OWNER)
                output.writeBoolean(false) // pinned conversation
                output.writeBoolean(false) // albumDelivery is absent
                output.writeInt(1)
                output.writeVersionThreeString(ITEM_ONE)
                output.writeVersionThreeString(ITEM_FILE)
                output.writeVersionThreeString("image/jpeg")
                output.writeVersionThreeString("Photo.jpg")
                output.writeInt(bytes.size)
                output.writeBoolean(false) // text
            }
        }
    }

    private fun batch(
        id: String,
        receivedAtMillis: Long = NOW,
    ) = SharedInboxBatch(
        id = id,
        receivedAtMillis = receivedAtMillis,
        items = emptyList(),
        text = "Private text",
        owner = SharedInboxOwner(
            sessionId = "session-a",
            cacheScopeId = "scope-a",
            accountId = OWNER,
        ),
    )

    private fun accepted(request: com.kit.wallet.feature.chat.IncomingTextShareRequest?): SharedInboxBatch =
        ((request?.payload as? IncomingTextShare.Accepted)?.batch)
            ?: error("Expected an accepted share")

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val OWNER = "10000000-0000-4000-8000-000000000001"
        const val BATCH_ONE = "20000000-0000-4000-8000-000000000001"
        const val BATCH_TWO = "20000000-0000-4000-8000-000000000002"
        const val BATCH_THREE = "20000000-0000-4000-8000-000000000003"
        const val BATCH_FOUR = "20000000-0000-4000-8000-000000000004"
        const val BATCH_FIVE = "20000000-0000-4000-8000-000000000005"
        const val ITEM_ONE = "40000000-0000-4000-8000-000000000001"
        const val ITEM_FILE = "$ITEM_ONE.heic"
        const val DIRECT_ONE = "30000000-0000-4000-8000-000000000001"
        const val DIRECT_TWO = "30000000-0000-4000-8000-000000000002"
    }
}
