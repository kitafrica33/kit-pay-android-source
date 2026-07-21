package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingSyncCursorStore
import com.kit.wallet.data.messaging.requireSecureMessagingSyncResumePosition
import com.kit.wallet.data.messaging.verifiedSecureMessagingSyncResumePosition
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingDurabilityStoresTest {
    @Test
    fun `sync cursor and event position survive restart with monotonic record versions`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val firstProcess = SecureMessagingSyncCursorStore(durableState)
        assertNull(firstProcess.load())

        val firstPosition = verifiedSecureMessagingSyncResumePosition("cursor_one", 17L)
        assertEquals(1L, firstProcess.save(firstPosition, expectedVersion = null))

        val restartedProcess = SecureMessagingSyncCursorStore(durableState)
        val restoredFirst = checkNotNull(restartedProcess.load())
        assertEquals("cursor_one" to 17L, requireSecureMessagingSyncResumePosition(restoredFirst.position))
        assertEquals(1L, restoredFirst.recordVersion)

        val secondPosition = verifiedSecureMessagingSyncResumePosition("cursor_two", 29L)
        assertEquals(
            2L,
            restartedProcess.save(secondPosition, expectedVersion = restoredFirst.recordVersion),
        )
        assertTrue(
            runCatching {
                firstProcess.save(firstPosition, expectedVersion = restoredFirst.recordVersion)
            }.isFailure,
        )

        val restoredSecond = checkNotNull(SecureMessagingSyncCursorStore(durableState).load())
        assertEquals("cursor_two" to 29L, requireSecureMessagingSyncResumePosition(restoredSecond.position))
        assertEquals(2L, restoredSecond.recordVersion)
    }

    @Test
    fun `projection metadata and deterministic pagination survive restart`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        )
        val inbound = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = INCOMING_MESSAGE_ID,
            clientMessageId = INCOMING_CLIENT_MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = PEER,
            text = "durable incoming",
        )
        val outboundOne = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ONE),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ONE,
            clientMessageId = OUTBOUND_CLIENT_ONE,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = CURRENT,
            text = "durable outgoing one",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(1, 2, 3),
                ),
            ),
        )
        val outboundTwo = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_TWO),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_TWO,
            clientMessageId = OUTBOUND_CLIENT_TWO,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = CURRENT,
            text = "durable outgoing two",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(4, 5, 6),
                ),
            ),
        )
        val inboundSentAt = Instant.parse("2026-07-20T13:00:00Z")
        val outboundCreatedAt = Instant.parse("2026-07-20T13:01:00Z")
        projection.recordInbound(inbound, inboundSentAt)
        projection.recordOutboundPending(outboundOne, outboundCreatedAt)

        val restarted = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        )
        restarted.recordInbound(inbound, inboundSentAt)
        restarted.recordOutboundPending(outboundOne, outboundCreatedAt)
        assertTrue(
            runCatching {
                restarted.recordOutboundPending(outboundOne, outboundCreatedAt.plusSeconds(1))
            }.isFailure,
        )

        val firstPage = restarted.readPage(limit = 2)
        val firstMessages = firstPage.messages()
        assertTrue(firstMessages !== firstPage.messages())
        assertEquals(
            listOf(
                SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
                SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ONE),
            ),
            firstMessages.map { it.durableRecord.recordKey },
        )
        assertEquals(
            SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ONE),
            firstPage.nextAfterRecordKey,
        )
        assertEquals(INCOMING_MESSAGE_ID, firstMessages[0].serverMessageId)
        assertEquals(inboundSentAt, firstMessages[0].sentAt)
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
            firstMessages[0].deliveryState,
        )
        assertNull(firstMessages[1].serverMessageId)
        assertEquals(outboundCreatedAt, firstMessages[1].sentAt)
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
            firstMessages[1].deliveryState,
        )

        val secondPage = restarted.readPage(
            afterRecordKey = checkNotNull(firstPage.nextAfterRecordKey),
            limit = 2,
        )
        val fallback = secondPage.messages().single()
        assertEquals(outboundTwo.recordKey, fallback.durableRecord.recordKey)
        assertEquals(Instant.ofEpochMilli(outboundTwo.updatedAtEpochMillis), fallback.sentAt)
        assertEquals(SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING, fallback.deliveryState)
        assertNull(secondPage.nextAfterRecordKey)

        val revisionBeforeRead = restarted.changes.value
        restarted.markConversationRead(CONVERSATION_ID)
        assertTrue(restarted.changes.value > revisionBeforeRead)
        val readAfterRestart = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_READ,
            readAfterRestart.readPage(limit = 2).messages().first().deliveryState,
        )
        // An idempotent sync replay must not turn a locally read message unread again.
        readAfterRestart.recordInbound(inbound, inboundSentAt)
        readAfterRestart.markConversationRead(CONVERSATION_ID)
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_READ,
            readAfterRestart.readPage(limit = 2).messages().first().deliveryState,
        )

        val revisionBeforeRetirement = restarted.changes.value
        restarted.markOutboundRetryRequired(outboundOne)
        assertTrue(restarted.changes.value > revisionBeforeRetirement)
        val retired = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        ).readPage(limit = 2).messages()[1]
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
            retired.deliveryState,
        )
        assertNull(retired.serverMessageId)

        // The append-only terminal value is idempotent and cannot regress to pending.
        restarted.markOutboundRetryRequired(outboundOne)
        assertTrue(
            runCatching {
                restarted.recordOutboundPending(outboundOne, outboundCreatedAt)
            }.isFailure,
        )
    }

    private companion object {
        const val CURRENT_USER_ID = "10000000-0000-4000-8000-000000000001"
        const val PEER_USER_ID = "20000000-0000-4000-8000-000000000002"
        const val CURRENT_DEVICE_ID = "30000000-0000-4000-8000-000000000003"
        const val PEER_DEVICE_ID = "40000000-0000-4000-8000-000000000004"
        const val CONVERSATION_ID = "50000000-0000-4000-8000-000000000005"
        const val INCOMING_MESSAGE_ID = "60000000-0000-4000-8000-000000000006"
        const val INCOMING_CLIENT_MESSAGE_ID = "70000000-0000-4000-8000-000000000007"
        const val OUTBOUND_CLIENT_ONE = "80000000-0000-4000-8000-000000000008"
        const val OUTBOUND_CLIENT_TWO = "90000000-0000-4000-8000-000000000009"
        const val ROSTER_REVISION =
            "v1:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val CURRENT = SecureMessagingCryptoAddress(CURRENT_USER_ID, CURRENT_DEVICE_ID, 1)
        val PEER = SecureMessagingCryptoAddress(PEER_USER_ID, PEER_DEVICE_ID, 2)
    }
}
