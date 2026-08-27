package com.kit.wallet

import com.kit.wallet.feature.chat.IncomingShareRequestPersistence
import com.kit.wallet.feature.chat.IncomingShareQueueFullException
import com.kit.wallet.feature.chat.IncomingTextShare
import com.kit.wallet.feature.chat.SharedInboxBatch
import com.kit.wallet.feature.chat.SharedInboxOwner
import com.kit.wallet.feature.chat.SharedInboxPolicy
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

        val pinned = first.pinDestination(original, DIRECT_ONE, nowMillis = NOW)
        assertEquals(DIRECT_ONE, pinned.pinnedConversationId)

        val recreated = IncomingShareRequestPersistence(root)
        assertEquals(
            DIRECT_ONE,
            accepted(recreated.claim(original.id, nowMillis = NOW)).pinnedConversationId,
        )
        assertEquals(
            DIRECT_ONE,
            recreated.pinDestination(original, DIRECT_ONE, nowMillis = NOW).pinnedConversationId,
        )
        assertThrows(IllegalStateException::class.java) {
            recreated.pinDestination(original, DIRECT_TWO, nowMillis = NOW)
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
            if (index == 0) store.pinDestination(current, DIRECT_ONE, nowMillis = NOW + index)
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
        const val DIRECT_ONE = "30000000-0000-4000-8000-000000000001"
        const val DIRECT_TWO = "30000000-0000-4000-8000-000000000002"
    }
}
