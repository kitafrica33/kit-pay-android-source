package com.kit.wallet

import com.kit.wallet.data.messaging.ConversationSystemEvent
import com.kit.wallet.data.messaging.ConversationSystemEventStore
import com.kit.wallet.data.messaging.MEMBERSHIP_ADDED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_REMOVED_EVENT
import com.kit.wallet.data.messaging.MEMBERSHIP_ROLE_CHANGED_EVENT
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordPage
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable half of a group's system messages.
 *
 * A membership change is unrecoverable once the sync cursor passes it, so what this store keeps is
 * the only copy — but it is a presentation record, so no failure here may ever be louder than a
 * missing line in a transcript.
 */
class ConversationSystemEventStoreTest {
    @Test fun `membership changes survive a restart in the order they happened`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)

        // Recorded out of order, as a page of sync events caught up after a spell offline can be.
        store.record(CONVERSATION_ID, event(id = 3, type = MEMBERSHIP_REMOVED_EVENT, at = 3_000))
        store.record(CONVERSATION_ID, event(id = 1, type = MEMBERSHIP_ADDED_EVENT, at = 1_000))
        store.record(
            CONVERSATION_ID,
            event(id = 2, type = MEMBERSHIP_ROLE_CHANGED_EVENT, role = "admin", at = 2_000),
        )

        val restarted = ConversationSystemEventStore(state)
        restarted.load(listOf(CONVERSATION_ID))

        val history = restarted.events.value.getValue(CONVERSATION_ID)
        assertEquals(listOf(1L, 2L, 3L), history.map(ConversationSystemEvent::eventId))
        assertEquals(
            listOf(MEMBERSHIP_ADDED_EVENT, MEMBERSHIP_ROLE_CHANGED_EVENT, MEMBERSHIP_REMOVED_EVENT),
            history.map(ConversationSystemEvent::type),
        )
        assertEquals(listOf(null, "admin", null), history.map(ConversationSystemEvent::role))
        assertEquals(MEMBER_ID, history.first().userId)
        assertEquals(Instant.ofEpochMilli(2_000), history[1].occurredAt)
    }

    @Test fun `a replayed event is recorded once`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)

        // A fresh login syncs from the start of the log, so the same event arrives again.
        store.record(CONVERSATION_ID, event(id = 7, at = 1_000))
        store.record(CONVERSATION_ID, event(id = 7, at = 1_000))
        store.record(CONVERSATION_ID, event(id = 7, type = MEMBERSHIP_REMOVED_EVENT, at = 9_000))

        assertEquals(listOf(7L), store.events.value.getValue(CONVERSATION_ID).map { it.eventId })
        assertEquals(
            MEMBERSHIP_ADDED_EVENT,
            store.events.value.getValue(CONVERSATION_ID).single().type,
        )
    }

    @Test fun `exact group request contribution metadata survives restart`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)
        val requestId = "00000000-0000-4000-8000-000000000003"
        val contributionId = "00000000-0000-4000-8000-000000000006"

        store.record(
            CONVERSATION_ID,
            ConversationSystemEvent(
                eventId = 8,
                type = "group_payment_request.completed",
                userId = MEMBER_ID,
                role = null,
                occurredAt = Instant.ofEpochMilli(8_000),
                paymentId = requestId,
                contributionId = contributionId,
                contributorUserId = MEMBER_ID,
                contributionAmountMinor = "25000000",
            ),
        )

        val restarted = ConversationSystemEventStore(state)
        restarted.load(listOf(CONVERSATION_ID))
        val event = restarted.events.value.getValue(CONVERSATION_ID).single()
        assertEquals(requestId, event.paymentId)
        assertEquals(contributionId, event.contributionId)
        assertEquals(MEMBER_ID, event.contributorUserId)
        assertEquals("25000000", event.contributionAmountMinor)
    }

    @Test fun `exact scheduled projection survives restart`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)
        val projection = "KITSGRP1:v=1&a=cancelled&id=00000000-0000-4000-8000-000000000003&at=42"
        store.record(
            CONVERSATION_ID,
            ConversationSystemEvent(
                eventId = 9,
                type = "scheduled_group_payment.cancelled",
                userId = MEMBER_ID,
                role = null,
                occurredAt = Instant.ofEpochMilli(9_000),
                paymentId = "00000000-0000-4000-8000-000000000003",
                projectionText = projection,
            ),
        )

        val restarted = ConversationSystemEventStore(state)
        restarted.load(listOf(CONVERSATION_ID))
        assertEquals(projection, restarted.events.value.getValue(CONVERSATION_ID).single().projectionText)
    }

    @Test fun `history stays bounded, keeping the most recent changes`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)

        (1L..220L).forEach { id ->
            store.record(CONVERSATION_ID, event(id = id, at = id * 1_000))
        }

        val history = store.events.value.getValue(CONVERSATION_ID)
        assertEquals(200, history.size)
        assertEquals(21L, history.first().eventId)
        assertEquals(220L, history.last().eventId)

        val restarted = ConversationSystemEventStore(state)
        restarted.load(listOf(CONVERSATION_ID))
        assertEquals(
            history.map(ConversationSystemEvent::eventId),
            restarted.events.value.getValue(CONVERSATION_ID).map(ConversationSystemEvent::eventId),
        )
    }

    @Test fun `each conversation keeps its own history and only loads what is asked for`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)
        store.record(CONVERSATION_ID, event(id = 1, at = 1_000))
        store.record(OTHER_CONVERSATION_ID, event(id = 2, type = MEMBERSHIP_REMOVED_EVENT, at = 2_000))

        val restarted = ConversationSystemEventStore(state)
        restarted.load(listOf(CONVERSATION_ID))
        assertEquals(setOf(CONVERSATION_ID), restarted.events.value.keys)

        restarted.load(listOf(CONVERSATION_ID, OTHER_CONVERSATION_ID))
        assertEquals(setOf(CONVERSATION_ID, OTHER_CONVERSATION_ID), restarted.events.value.keys)
        assertEquals(2L, restarted.events.value.getValue(OTHER_CONVERSATION_ID).single().eventId)
    }

    @Test fun `a record this device can no longer read shows no lines rather than failing`() = runTest {
        val state = TestSecureMessagingStateStore()
        state.write(
            namespace = "conversation-system-events",
            recordKey = "events:$CONVERSATION_ID",
            expectedVersion = null,
            bytes = byteArrayOf(0x09, 0x7f, 0x7f, 0x7f, 0x7f),
        )

        val store = ConversationSystemEventStore(state)
        store.load(listOf(CONVERSATION_ID))
        assertTrue(store.events.value.isEmpty())

        // And the next real change still lands: an unreadable record is replaced, not respected.
        store.record(CONVERSATION_ID, event(id = 4, at = 4_000))
        assertEquals(listOf(4L), store.events.value.getValue(CONVERSATION_ID).map { it.eventId })
    }

    @Test fun `nothing that is not a membership change is stored`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)

        store.record(CONVERSATION_ID, event(id = 1, type = "conversation.created", at = 1_000))
        store.record("../escape", event(id = 2, at = 2_000))
        store.record("", event(id = 3, at = 3_000))

        assertTrue(store.events.value.isEmpty())
    }

    @Test fun `forgetting drops one account's timeline without deleting it`() = runTest {
        val state = TestSecureMessagingStateStore()
        val store = ConversationSystemEventStore(state)
        store.record(CONVERSATION_ID, event(id = 1, at = 1_000))

        store.forget()
        assertTrue(store.events.value.isEmpty())

        // The record itself belongs to the state store's lifecycle: forgetting is a memory
        // operation, so the same session reading again gets its history back.
        store.load(listOf(CONVERSATION_ID))
        assertEquals(listOf(1L), store.events.value.getValue(CONVERSATION_ID).map { it.eventId })
    }

    @Test fun `a state store that cannot be read costs lines, never the publication`() = runTest {
        val store = ConversationSystemEventStore(UnreadableStateStore())

        store.load(listOf(CONVERSATION_ID))

        assertTrue(store.events.value.isEmpty())
    }

    private fun event(
        id: Long,
        type: String = MEMBERSHIP_ADDED_EVENT,
        role: String? = null,
        at: Long,
    ) = ConversationSystemEvent(
        eventId = id,
        type = type,
        userId = MEMBER_ID,
        role = role,
        occurredAt = Instant.ofEpochMilli(at),
    )

    /** Every read fails, the way a state store closed under an erasure does. */
    private class UnreadableStateStore : SecureMessagingStateStore {
        override suspend fun read(namespace: String, recordKey: String): SecureMessagingRecord? =
            throw IllegalStateException("state is closed")

        override suspend fun readNamespacePage(
            namespace: String,
            afterRecordKey: String?,
            limit: Int,
        ): SecureMessagingRecordPage = throw IllegalStateException("state is closed")

        override suspend fun write(
            namespace: String,
            recordKey: String,
            expectedVersion: Long?,
            bytes: ByteArray,
        ): SecureMessagingRecordVersion = throw IllegalStateException("state is closed")

        override suspend fun writeBatch(
            writes: List<SecureMessagingStateWrite>,
        ): List<SecureMessagingRecordVersion> = throw IllegalStateException("state is closed")

        override suspend fun deleteNamespace(namespace: String) = Unit

        override suspend fun eraseAll() = Unit
    }

    private companion object {
        const val CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0001"
        const val OTHER_CONVERSATION_ID = "0199aaaa-bbbb-4ccc-8ddd-eeeeffff0002"
        const val MEMBER_ID = "33333333-3333-4333-8333-333333333333"
    }
}
