package com.kit.wallet

import androidx.work.ExistingWorkPolicy
import com.kit.wallet.data.messaging.ScheduledSend
import com.kit.wallet.data.messaging.ScheduledSendAlarm
import com.kit.wallet.data.messaging.ScheduledSendKind
import com.kit.wallet.data.messaging.ScheduledSendState
import com.kit.wallet.data.messaging.ScheduledSendStore
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.nextWakeAtEpochMillis
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.worker.SCHEDULED_SEND_DISPATCH_WORK_NAME
import com.kit.wallet.worker.SCHEDULED_SEND_DISPATCH_WORK_POLICY
import com.kit.wallet.worker.SCHEDULED_SEND_WAKE_WORK_NAME
import com.kit.wallet.worker.scheduledSendWakeDelayMillis
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledSendStoreTest {
    private val disk = TestSecureMessagingStateStore()
    private val sessions = MutableTestSessionStore(testSession(OWNER_A))
    private val rearms = mutableListOf<Long?>()
    private val alarm = ScheduledSendAlarm { rearms += it }

    @Test fun `items are published soonest first and survive a restart`() = runTest {
        val store = ScheduledSendStore(disk, sessions, alarm)
        val later = textSend(ID_TWO, at = NOW + 600_000L)
        val sooner = textSend(ID_ONE, at = NOW + 60_000L)

        store.put(later)
        store.put(sooner)

        assertEquals(listOf(sooner, later), store.items.value)
        assertEquals(sooner, store.find(ID_ONE))
        assertEquals(NOW + 60_000L, rearms.last())

        // A fresh instance is what a cold start looks like: nothing in RAM, everything on disk.
        val restarted = ScheduledSendStore(disk, sessions)
        restarted.load()
        assertEquals(listOf(sooner, later), restarted.items.value)
    }

    @Test fun `items are filtered by conversation`() = runTest {
        val store = ScheduledSendStore(disk, sessions)
        val mine = textSend(ID_ONE)
        val theirs = textSend(ID_TWO).copy(conversationId = OTHER_CONVERSATION_ID)

        store.put(mine)
        store.put(theirs)

        assertEquals(listOf(mine), store.forConversation(CONVERSATION_ID))
        assertEquals(listOf(theirs), store.forConversation(OTHER_CONVERSATION_ID))
        assertEquals(emptyList<ScheduledSend>(), store.forConversation("nobody"))
    }

    @Test fun `compare and set only replaces the exact item it was given`() = runTest {
        val store = ScheduledSendStore(disk, sessions)
        val original = textSend(ID_ONE)
        store.put(original)

        val claimed = original.copy(
            state = ScheduledSendState.SENDING,
            claimedAtEpochMillis = NOW,
        )
        assertTrue(store.compareAndSet(original, claimed))
        assertEquals(claimed, store.find(ID_ONE))

        // The stale expectation is exactly how a second dispatch learns it lost the race.
        assertFalse(store.compareAndSet(original, original.copy(attempts = 9)))
        assertEquals(claimed, store.find(ID_ONE))
    }

    @Test fun `compare and set fails for an item that is gone`() = runTest {
        val store = ScheduledSendStore(disk, sessions)
        val send = textSend(ID_ONE)
        store.put(send)
        store.remove(ID_ONE)

        assertFalse(store.compareAndSet(send, send.copy(attempts = 1)))
    }

    @Test fun `an item cannot change identity`() = runTest {
        val store = ScheduledSendStore(disk, sessions)
        val send = textSend(ID_ONE)
        store.put(send)

        assertTrue(
            runCatching { store.compareAndSet(send, send.copy(id = ID_TWO)) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test fun `removing the last item compacts the namespace away`() = runTest {
        val store = ScheduledSendStore(disk, sessions, alarm)
        store.put(textSend(ID_ONE))
        store.put(textSend(ID_TWO, at = NOW + 600_000L))

        store.remove(ID_ONE)
        // One live item left, so the removed one is only tombstoned.
        assertEquals(2, disk.namespaceSize())
        assertEquals(NOW + 600_000L, rearms.last())

        store.remove(ID_TWO)
        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertEquals(0, disk.namespaceSize())
        assertNull(rearms.last())

        // And the compacted namespace still accepts a new item afterwards.
        val fresh = textSend(ID_ONE)
        store.put(fresh)
        assertEquals(listOf(fresh), store.items.value)
    }

    @Test fun `removing something already gone is not an error`() = runTest {
        val store = ScheduledSendStore(disk, sessions)
        store.load()

        store.remove(ID_ONE)

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
    }

    @Test fun `forget drops the mirror without touching disk`() = runTest {
        val store = ScheduledSendStore(disk, sessions, alarm)
        val send = textSend(ID_ONE)
        store.put(send)
        val armedBefore = rearms.last()

        store.forget()

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertNull(store.find(ID_ONE))
        // The wake stays armed: a lifecycle blip must not silently drop a send.
        assertEquals(armedBefore, rearms.last())
        assertEquals(1, disk.namespaceSize())

        store.reload()
        assertEquals(listOf(send), store.items.value)
    }

    @Test fun `account switch hides the old mirror and rejects its late write`() = runTest {
        val store = ScheduledSendStore(disk, sessions, alarm)
        val original = textSend(ID_ONE)
        store.put(original)
        val ownerA = checkNotNull(sessions.current()).fence()

        // Session replacement cryptographically erases the old messaging namespace. Deliberately
        // leave the singleton's RAM mirror untouched to exercise the hand-off window itself.
        disk.deleteNamespace(NAMESPACE)
        sessions.save(testSession(OWNER_B))

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertNull(store.find(ID_ONE))
        assertEquals(emptyList<ScheduledSend>(), store.forConversation(CONVERSATION_ID))
        assertTrue(
            runCatching {
                store.compareAndSetForOwner(ownerA, original, original.copy(attempts = 1))
            }.exceptionOrNull() is SessionInvalidatedException,
        )
        assertTrue(
            runCatching { store.putForOwner(ownerA, textSend(ID_TWO)) }
                .exceptionOrNull() is SessionInvalidatedException,
        )
        assertEquals(0, disk.namespaceSize())

        val replacement = textSend(ID_ONE).copy(text = "new owner's message")
        store.put(replacement)
        assertEquals(listOf(replacement), store.items.value)
        assertEquals(1, disk.namespaceSize())

        assertTrue(
            runCatching { store.removeForOwner(ownerA, ID_ONE) }
                .exceptionOrNull() is SessionInvalidatedException,
        )
        assertEquals(listOf(replacement), store.items.value)
        assertEquals(1, disk.namespaceSize())
    }

    @Test fun `sign out hides queued plaintext synchronously`() = runTest {
        val store = ScheduledSendStore(disk, sessions)
        store.put(textSend(ID_ONE))

        sessions.clear()

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertNull(store.find(ID_ONE))
    }

    @Test fun `clear erases the namespace outright`() = runTest {
        val store = ScheduledSendStore(disk, sessions, alarm)
        store.put(textSend(ID_ONE))

        store.clear()

        assertEquals(emptyList<ScheduledSend>(), store.items.value)
        assertEquals(0, disk.namespaceSize())
        assertNull(rearms.last())

        val restarted = ScheduledSendStore(disk, sessions)
        restarted.load()
        assertEquals(emptyList<ScheduledSend>(), restarted.items.value)
    }

    @Test fun `unreadable and misfiled records are ignored rather than trusted`() = runTest {
        val honest = textSend(ID_ONE)
        disk.writeRecord("send:$ID_ONE", byteArrayOf(0x01) + honest.encode().toByteArray(Charsets.UTF_8))
        // A record filed under one identity but claiming another; a corrupted namespace must not be
        // able to resurrect a send under somebody else's ID.
        disk.writeRecord("send:$ID_TWO", byteArrayOf(0x01) + honest.encode().toByteArray(Charsets.UTF_8))
        disk.writeRecord("send:$ID_THREE", byteArrayOf(0x01) + "not a scheduled send".toByteArray(Charsets.UTF_8))
        disk.writeRecord("send:$ID_FOUR", byteArrayOf(0x00))
        disk.writeRecord("send:$ID_FIVE", byteArrayOf(0x02) + honest.encode().toByteArray(Charsets.UTF_8))

        val store = ScheduledSendStore(disk, sessions)
        store.load()

        assertEquals(listOf(honest), store.items.value)
    }

    @Test fun `load is idempotent and reload is not`() = runTest {
        val store = ScheduledSendStore(disk, sessions)
        store.put(textSend(ID_ONE))

        // Another process wrote behind this instance's back; only an explicit reload sees it.
        disk.writeRecord(
            "send:$ID_TWO",
            byteArrayOf(0x01) + textSend(ID_TWO).encode().toByteArray(Charsets.UTF_8),
        )
        store.load()
        assertEquals(1, store.items.value.size)

        store.reload()
        assertEquals(2, store.items.value.size)
    }

    @Test fun `the next wake ignores items waiting on a person`() {
        val waiting = textSend(ID_ONE, at = NOW + 600_000L)
        val unconfirmed = textSend(ID_TWO, at = NOW)
            .copy(state = ScheduledSendState.UNCONFIRMED)

        assertNull(nextWakeAtEpochMillis(emptyList()))
        assertNull(nextWakeAtEpochMillis(listOf(unconfirmed)))
        assertEquals(NOW + 600_000L, nextWakeAtEpochMillis(listOf(unconfirmed, waiting)))
        assertEquals(
            NOW + ScheduledSend.STALE_CLAIM_MILLIS,
            nextWakeAtEpochMillis(
                listOf(
                    waiting,
                    textSend(ID_THREE).copy(
                        state = ScheduledSendState.SENDING,
                        claimedAtEpochMillis = NOW,
                    ),
                ),
            ),
        )
    }

    @Test fun `the wake delay is the time left, never a negative one`() {
        assertEquals(600_000L, scheduledSendWakeDelayMillis(NOW + 600_000L, NOW))
        assertEquals(0L, scheduledSendWakeDelayMillis(NOW, NOW))
        // Already overdue — a device that was off all night sends as soon as it can, not sooner.
        assertEquals(0L, scheduledSendWakeDelayMillis(NOW - 600_000L, NOW))
    }

    @Test fun `rearming the timer cannot replace the worker that is dispatching`() {
        assertTrue(SCHEDULED_SEND_WAKE_WORK_NAME != SCHEDULED_SEND_DISPATCH_WORK_NAME)
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            SCHEDULED_SEND_DISPATCH_WORK_POLICY,
        )
    }

    private suspend fun SecureMessagingStateStore.namespaceSize(): Int =
        readNamespacePage(NAMESPACE, null, 100).records()
            .onEach { it.bytes.fill(0) }
            .size

    private suspend fun SecureMessagingStateStore.writeRecord(recordKey: String, bytes: ByteArray) {
        write(NAMESPACE, recordKey, expectedVersion = null, bytes = bytes)
    }

    private fun textSend(id: String, at: Long = NOW + 60_000L) = ScheduledSend(
        id = id,
        conversationId = CONVERSATION_ID,
        kind = ScheduledSendKind.TEXT,
        scheduledAtEpochMillis = at,
        createdAtEpochMillis = NOW,
        text = "see you at six",
    )

    private companion object {
        const val OWNER_A = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff00a1"
        const val OWNER_B = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff00b2"
        const val NAMESPACE = "scheduled-send"
        const val CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0009"
        const val OTHER_CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff000a"
        const val ID_ONE = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0001"
        const val ID_TWO = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0002"
        const val ID_THREE = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0003"
        const val ID_FOUR = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0004"
        const val ID_FIVE = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0005"
        const val NOW = 1_766_000_000_000L
    }
}
