package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.KitMediaMessageV2
import com.kit.wallet.data.messaging.KitMediaMessageV2Item
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingMediaDisposition
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingSyncCursorStore
import com.kit.wallet.data.messaging.requireSecureMessagingSyncResumePosition
import com.kit.wallet.data.messaging.verifiedSecureMessagingSyncResumePosition
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(
            INCOMING_MESSAGE_ID,
            restarted.newestUnreadInboundMessageId(CONVERSATION_ID, setOf(PEER_USER_ID)),
        )
        restarted.markInboundReadThrough(
            conversationId = CONVERSATION_ID,
            senderUserIds = setOf(PEER_USER_ID),
            requestedLastReadMessageId = INCOMING_MESSAGE_ID,
            canonicalLastReadMessageId = INCOMING_MESSAGE_ID,
            canonicalReadAt = Instant.parse("2026-07-20T14:00:00Z"),
        )
        assertTrue(restarted.changes.value > revisionBeforeRead)
        val readAfterRestart = SecureMessagingProjectionStore(
            durableState,
            LibSignalCompanionStateReader(durableState),
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_READ,
            readAfterRestart.readPage(limit = 2).messages().first().deliveryState,
        )
        assertNull(
            readAfterRestart.newestUnreadInboundMessageId(CONVERSATION_ID, setOf(PEER_USER_ID)),
        )
        // An idempotent sync replay must not turn a locally read message unread again.
        readAfterRestart.recordInbound(inbound, inboundSentAt)
        readAfterRestart.markInboundReadThrough(
            conversationId = CONVERSATION_ID,
            senderUserIds = setOf(PEER_USER_ID),
            requestedLastReadMessageId = INCOMING_MESSAGE_ID,
            canonicalLastReadMessageId = INCOMING_MESSAGE_ID,
            canonicalReadAt = Instant.parse("2026-07-20T14:00:00Z"),
        )
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
        restarted.markOutboundPermanentFailure(outboundOne)
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
            projectionStore(durableState).readPage(limit = 2).messages()[1].deliveryState,
        )
    }

    @Test
    fun `suppressed inbound stays hidden across restart and pagination reaches later valid message`() =
        runTest {
            val durableState = TestSecureMessagingStateStore()
            val projection = projectionStore(durableState)
            val suppressed = durableState.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
                direction = LibSignalCompanionDirection.INBOUND,
                messageId = INCOMING_MESSAGE_ID,
                clientMessageId = INCOMING_CLIENT_MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = ROSTER_REVISION,
                sender = PEER,
                text = "invalid authenticated attachment descriptor",
            )
            projection.recordInboundSuppressed(
                suppressed,
                Instant.parse("2026-07-20T13:00:00Z"),
            )
            recordInbound(durableState, projection, OLD_MESSAGE_ID, OLD_CLIENT_ID, 1)

            val restarted = projectionStore(durableState)
            val firstPage = restarted.readPage(limit = 1)
            assertTrue(firstPage.messages().isEmpty())
            assertEquals(suppressed.recordKey, firstPage.nextAfterRecordKey)
            val secondPage = restarted.readPage(
                afterRecordKey = checkNotNull(firstPage.nextAfterRecordKey),
                limit = 1,
            )
            assertEquals(OLD_MESSAGE_ID, secondPage.messages().single().serverMessageId)
            assertEquals(
                OLD_MESSAGE_ID,
                restarted.newestUnreadInboundMessageId(CONVERSATION_ID, setOf(PEER_USER_ID)),
            )

            // Replayed suppression is idempotent and cannot make the record visible again.
            restarted.recordInboundSuppressed(
                suppressed,
                Instant.parse("2026-07-20T13:00:00Z"),
            )
            assertEquals(
                listOf(OLD_MESSAGE_ID),
                restarted.readPage(limit = 10).messages().map { it.serverMessageId },
            )
        }

    @Test
    fun `replayed inbound deliveries re-signal projections committed before a crash`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = projectionStore(durableState)
        val sentAt = Instant.parse("2026-07-20T13:00:00Z")
        val visible = durableState.persistCompanionRecordForTest(
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
        projection.recordInbound(visible, sentAt)
        val suppressed = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(OLD_MESSAGE_ID),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = OLD_MESSAGE_ID,
            clientMessageId = OLD_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = PEER,
            text = "invalid authenticated attachment descriptor",
        )
        projection.recordInboundSuppressed(suppressed, sentAt)
        val unsupported = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(REQUESTED_MESSAGE_ID),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = REQUESTED_MESSAGE_ID,
            clientMessageId = REQUESTED_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = PEER,
            text = "KITMEDIA2:v=2&n=2&binding-failed",
        )
        projection.recordInboundUnsupportedMedia(unsupported, sentAt)

        // A crash can land between a metadata commit and its StateFlow revision. The duplicate
        // delivery that follows is each path's only chance to repair observer visibility, so an
        // idempotent replay must advance the revision even though it changes no stored byte.
        val beforeInboundReplay = projection.changes.value
        projection.recordInbound(visible, sentAt)
        assertTrue(projection.changes.value > beforeInboundReplay)

        val beforeSuppressedReplay = projection.changes.value
        projection.recordInboundSuppressed(suppressed, sentAt)
        assertTrue(projection.changes.value > beforeSuppressedReplay)

        val beforeUnsupportedReplay = projection.changes.value
        projection.recordInboundUnsupportedMedia(unsupported, sentAt)
        assertTrue(projection.changes.value > beforeUnsupportedReplay)
    }

    @Test
    fun `a media verdict never resurrects a suppressed row`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = projectionStore(durableState)
        val sentAt = Instant.parse("2026-07-20T13:00:00Z")
        val durable = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = INCOMING_MESSAGE_ID,
            clientMessageId = INCOMING_CLIENT_MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = PEER,
            text = "KITMEDIA2:v=2&n=2&binding-failed",
        )
        projection.recordInboundSuppressed(durable, sentAt)
        assertTrue(projection.readPage(limit = 10).messages().isEmpty())

        // Suppression is an authenticated rejection and remains terminal even if a later replay
        // classifies the same durable bytes as unsupported media.
        projection.recordInboundUnsupportedMedia(durable, sentAt)
        projection.recordInbound(
            durable,
            sentAt,
            disposition = SecureMessagingMediaDisposition.VALIDATED,
        )
        assertTrue(projectionStore(durableState).readPage(limit = 10).messages().isEmpty())
    }

    @Test
    fun `media disposition promotes none to validated but unsupported is absorbing`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = projectionStore(durableState)
        val sentAt = Instant.parse("2026-07-20T13:00:00Z")
        val durable = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = INCOMING_MESSAGE_ID,
            clientMessageId = INCOMING_CLIENT_MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = PEER,
            text = MEDIA_V2_TEXT,
        )

        projection.recordInbound(durable, sentAt)
        var projected = projectionStore(durableState).readPage(limit = 10).messages().single()
        assertEquals(SecureMessagingMediaDisposition.NONE, projected.mediaDisposition)
        assertTrue(projected.unsupportedMedia)

        projection.recordInbound(
            durable,
            sentAt,
            disposition = SecureMessagingMediaDisposition.VALIDATED,
        )
        projected = projectionStore(durableState).readPage(limit = 10).messages().single()
        assertEquals(SecureMessagingMediaDisposition.VALIDATED, projected.mediaDisposition)
        assertFalse(projected.unsupportedMedia)

        projection.recordInboundUnsupportedMedia(durable, sentAt)
        projection.recordInbound(
            durable,
            sentAt,
            disposition = SecureMessagingMediaDisposition.VALIDATED,
        )
        projected = projectionStore(durableState).readPage(limit = 10).messages().single()
        assertEquals(SecureMessagingMediaDisposition.UNSUPPORTED, projected.mediaDisposition)
        assertTrue(projected.unsupportedMedia)
    }

    @Test
    fun `only a newly recorded strict v2 outbound row receives positive provenance`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = projectionStore(durableState)
        val legacySpoof = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ONE),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ONE,
            clientMessageId = OUTBOUND_CLIENT_ONE,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = CURRENT,
            text = MEDIA_V2_TEXT,
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(1),
                ),
            ),
        )
        val genuine = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_TWO),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_TWO,
            clientMessageId = OUTBOUND_CLIENT_TWO,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = CURRENT,
            text = MEDIA_V2_TEXT,
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(2),
                ),
            ),
        )

        // No metadata models a row written by the pre-v2 build, when this was user-authored text.
        val beforePin = projection.readPage(limit = 10).messages()
            .single { it.durableRecord.recordKey == legacySpoof.recordKey }
        assertEquals(SecureMessagingMediaDisposition.NONE, beforePin.mediaDisposition)
        assertTrue(beforePin.unsupportedMedia)

        projection.recordOutboundPending(genuine, Instant.parse("2026-07-20T13:00:00Z"))
        val pinned = projectionStore(durableState).readPage(limit = 10).messages()
            .single { it.durableRecord.recordKey == genuine.recordKey }
        assertEquals(SecureMessagingMediaDisposition.VALIDATED, pinned.mediaDisposition)
        assertFalse(pinned.unsupportedMedia)
    }

    @Test
    fun `read application stops at the posted marker when a later message arrives`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = projectionStore(durableState)
        recordInbound(durableState, projection, OLD_MESSAGE_ID, OLD_CLIENT_ID, 0)
        recordInbound(durableState, projection, REQUESTED_MESSAGE_ID, REQUESTED_CLIENT_ID, 1)
        assertEquals(
            REQUESTED_MESSAGE_ID,
            projection.newestUnreadInboundMessageId(CONVERSATION_ID, setOf(PEER_USER_ID)),
        )

        // This projection appears while the selected marker's POST is in flight.
        recordInbound(durableState, projection, LATER_MESSAGE_ID, LATER_CLIENT_ID, 2)
        projection.markInboundReadThrough(
            conversationId = CONVERSATION_ID,
            senderUserIds = setOf(PEER_USER_ID),
            requestedLastReadMessageId = REQUESTED_MESSAGE_ID,
            canonicalLastReadMessageId = REQUESTED_MESSAGE_ID,
            canonicalReadAt = Instant.parse("2026-07-20T14:00:00Z"),
        )

        val states = projection.readPage(limit = 10).messages()
            .associate { it.serverMessageId to it.deliveryState }
        assertEquals(SecureMessagingProjectionDeliveryState.INBOUND_READ, states[OLD_MESSAGE_ID])
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_READ,
            states[REQUESTED_MESSAGE_ID],
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
            states[LATER_MESSAGE_ID],
        )
    }

    @Test
    fun `unknown canonical marker keeps the posted projection retryable`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = projectionStore(durableState)
        recordInbound(durableState, projection, OLD_MESSAGE_ID, OLD_CLIENT_ID, 0)
        recordInbound(durableState, projection, REQUESTED_MESSAGE_ID, REQUESTED_CLIENT_ID, 1)
        val revision = projection.changes.value

        projection.markInboundReadThrough(
            conversationId = CONVERSATION_ID,
            senderUserIds = setOf(PEER_USER_ID),
            requestedLastReadMessageId = REQUESTED_MESSAGE_ID,
            canonicalLastReadMessageId = UNKNOWN_CANONICAL_MESSAGE_ID,
            canonicalReadAt = Instant.parse("2026-07-20T14:00:00Z"),
        )

        assertEquals(revision, projection.changes.value)
        assertTrue(
            projection.readPage(limit = 10).messages().all {
                it.deliveryState == SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
            },
        )
        assertEquals(
            REQUESTED_MESSAGE_ID,
            projection.newestUnreadInboundMessageId(CONVERSATION_ID, setOf(PEER_USER_ID)),
        )
    }

    @Test
    fun `canonical equal-second UUID order advances only through the authenticated marker`() =
        runTest {
            val durableState = TestSecureMessagingStateStore()
            val projection = projectionStore(durableState)
            recordInbound(durableState, projection, OLD_MESSAGE_ID, OLD_CLIENT_ID, 0)
            recordInbound(durableState, projection, REQUESTED_MESSAGE_ID, REQUESTED_CLIENT_ID, 0)
            recordInbound(durableState, projection, CANONICAL_MESSAGE_ID, CANONICAL_CLIENT_ID, 0)
            recordInbound(durableState, projection, LATER_MESSAGE_ID, LATER_CLIENT_ID, 0)
            assertEquals(
                LATER_MESSAGE_ID,
                projection.newestUnreadInboundMessageId(CONVERSATION_ID, setOf(PEER_USER_ID)),
            )

            projection.markInboundReadThrough(
                conversationId = CONVERSATION_ID,
                senderUserIds = setOf(PEER_USER_ID),
                requestedLastReadMessageId = REQUESTED_MESSAGE_ID,
                canonicalLastReadMessageId = CANONICAL_MESSAGE_ID,
                canonicalReadAt = Instant.parse("2026-07-20T14:00:00Z"),
            )

            val states = projection.readPage(limit = 10).messages()
                .associate { it.serverMessageId to it.deliveryState }
            listOf(OLD_MESSAGE_ID, REQUESTED_MESSAGE_ID, CANONICAL_MESSAGE_ID).forEach { id ->
                assertEquals(SecureMessagingProjectionDeliveryState.INBOUND_READ, states[id])
            }
            assertEquals(
                SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
                states[LATER_MESSAGE_ID],
            )
        }

    @Test
    fun `read chronology failure leaves every inbound projection unchanged`() = runTest {
        val durableState = TestSecureMessagingStateStore()
        val projection = projectionStore(durableState)
        recordInbound(durableState, projection, REQUESTED_MESSAGE_ID, REQUESTED_CLIENT_ID, 0)
        val revision = projection.changes.value

        assertTrue(
            runCatching {
                projection.markInboundReadThrough(
                    conversationId = CONVERSATION_ID,
                    senderUserIds = setOf(PEER_USER_ID),
                    requestedLastReadMessageId = REQUESTED_MESSAGE_ID,
                    canonicalLastReadMessageId = REQUESTED_MESSAGE_ID,
                    canonicalReadAt = Instant.parse("2026-07-20T12:59:59Z"),
                )
            }.isFailure,
        )
        assertEquals(revision, projection.changes.value)
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
            projection.readPage(limit = 10).messages().single().deliveryState,
        )
    }

    @Test
    fun `self-authored inbound copy advances monotonically across delivery read and replay`() =
        runTest {
            val durableState = TestSecureMessagingStateStore()
            val projection = projectionStore(durableState)
            val selfAuthored = durableState.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
                direction = LibSignalCompanionDirection.INBOUND,
                messageId = INCOMING_MESSAGE_ID,
                clientMessageId = INCOMING_CLIENT_MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = ROSTER_REVISION,
                sender = SELF_OTHER_DEVICE,
                text = "sent from my other device",
            )
            projection.recordInbound(selfAuthored, Instant.parse("2026-07-20T13:00:00Z"))

            projection.markAuthoredDelivered(
                CONVERSATION_ID,
                INCOMING_MESSAGE_ID,
                CURRENT_USER_ID,
                Instant.parse("2026-07-20T13:00:01Z"),
            )
            assertEquals(
                SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
                projection.readPage(limit = 10).messages().single().deliveryState,
            )
            projection.markAuthoredReadThrough(
                CONVERSATION_ID,
                INCOMING_MESSAGE_ID,
                CURRENT_USER_ID,
                Instant.parse("2026-07-20T13:00:02Z"),
            )
            val revision = projection.changes.value
            projection.markAuthoredDelivered(
                CONVERSATION_ID,
                INCOMING_MESSAGE_ID,
                CURRENT_USER_ID,
                Instant.parse("2026-07-20T13:00:03Z"),
            )
            projection.markAuthoredReadThrough(
                CONVERSATION_ID,
                INCOMING_MESSAGE_ID,
                CURRENT_USER_ID,
                Instant.parse("2026-07-20T13:00:04Z"),
            )

            assertEquals(revision, projection.changes.value)
            assertEquals(
                SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ,
                projection.readPage(limit = 10).messages().single().deliveryState,
            )
        }

    @Test
    fun `known newer canonical marker advances locally but a known older marker fails closed`() =
        runTest {
            val durableState = TestSecureMessagingStateStore()
            val projection = projectionStore(durableState)
            recordInbound(durableState, projection, OLD_MESSAGE_ID, OLD_CLIENT_ID, 0)
            recordInbound(durableState, projection, REQUESTED_MESSAGE_ID, REQUESTED_CLIENT_ID, 1)
            recordInbound(durableState, projection, CANONICAL_MESSAGE_ID, CANONICAL_CLIENT_ID, 2)
            recordInbound(durableState, projection, LATER_MESSAGE_ID, LATER_CLIENT_ID, 3)

            projection.markInboundReadThrough(
                conversationId = CONVERSATION_ID,
                senderUserIds = setOf(PEER_USER_ID),
                requestedLastReadMessageId = REQUESTED_MESSAGE_ID,
                canonicalLastReadMessageId = CANONICAL_MESSAGE_ID,
                canonicalReadAt = Instant.parse("2026-07-20T14:00:00Z"),
            )
            val advanced = projection.readPage(limit = 10).messages()
                .associate { it.serverMessageId to it.deliveryState }
            assertEquals(
                SecureMessagingProjectionDeliveryState.INBOUND_READ,
                advanced[CANONICAL_MESSAGE_ID],
            )
            assertEquals(
                SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
                advanced[LATER_MESSAGE_ID],
            )

            val separateState = TestSecureMessagingStateStore()
            val separateProjection = projectionStore(separateState)
            recordInbound(separateState, separateProjection, OLD_MESSAGE_ID, OLD_CLIENT_ID, 0)
            recordInbound(
                separateState,
                separateProjection,
                REQUESTED_MESSAGE_ID,
                REQUESTED_CLIENT_ID,
                1,
            )
            assertTrue(
                runCatching {
                    separateProjection.markInboundReadThrough(
                        conversationId = CONVERSATION_ID,
                        senderUserIds = setOf(PEER_USER_ID),
                        requestedLastReadMessageId = REQUESTED_MESSAGE_ID,
                        canonicalLastReadMessageId = OLD_MESSAGE_ID,
                        canonicalReadAt = Instant.parse("2026-07-20T14:00:00Z"),
                    )
                }.isFailure,
            )
            assertTrue(
                separateProjection.readPage(limit = 10).messages().all {
                    it.deliveryState == SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED
                },
            )
        }

    private fun projectionStore(
        state: TestSecureMessagingStateStore,
    ) = SecureMessagingProjectionStore(state, LibSignalCompanionStateReader(state))

    private suspend fun recordInbound(
        state: TestSecureMessagingStateStore,
        projection: SecureMessagingProjectionStore,
        messageId: String,
        clientMessageId: String,
        sentAtOffsetSeconds: Long,
    ) {
        val durable = state.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(messageId),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = messageId,
            clientMessageId = clientMessageId,
            conversationId = CONVERSATION_ID,
            rosterRevision = ROSTER_REVISION,
            sender = PEER,
            text = "read marker $messageId",
        )
        projection.recordInbound(
            durable,
            Instant.parse("2026-07-20T13:00:00Z").plusSeconds(sentAtOffsetSeconds),
        )
    }

    private companion object {
        const val CURRENT_USER_ID = "10000000-0000-4000-8000-000000000001"
        const val PEER_USER_ID = "20000000-0000-4000-8000-000000000002"
        const val CURRENT_DEVICE_ID = "30000000-0000-4000-8000-000000000003"
        const val SELF_OTHER_DEVICE_ID = "31000000-0000-4000-8000-000000000003"
        const val PEER_DEVICE_ID = "40000000-0000-4000-8000-000000000004"
        const val CONVERSATION_ID = "50000000-0000-4000-8000-000000000005"
        const val INCOMING_MESSAGE_ID = "60000000-0000-4000-8000-000000000006"
        const val INCOMING_CLIENT_MESSAGE_ID = "70000000-0000-4000-8000-000000000007"
        const val OUTBOUND_CLIENT_ONE = "80000000-0000-4000-8000-000000000008"
        const val OUTBOUND_CLIENT_TWO = "90000000-0000-4000-8000-000000000009"
        const val OLD_MESSAGE_ID = "61000000-0000-4000-8000-000000000001"
        const val REQUESTED_MESSAGE_ID = "62000000-0000-4000-8000-000000000002"
        const val CANONICAL_MESSAGE_ID = "63000000-0000-4000-8000-000000000003"
        const val LATER_MESSAGE_ID = "64000000-0000-4000-8000-000000000004"
        const val UNKNOWN_CANONICAL_MESSAGE_ID = "65000000-0000-4000-8000-000000000005"
        const val OLD_CLIENT_ID = "71000000-0000-4000-8000-000000000001"
        const val REQUESTED_CLIENT_ID = "72000000-0000-4000-8000-000000000002"
        const val CANONICAL_CLIENT_ID = "73000000-0000-4000-8000-000000000003"
        const val LATER_CLIENT_ID = "74000000-0000-4000-8000-000000000004"
        const val ROSTER_REVISION =
            "v1:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val CURRENT = SecureMessagingCryptoAddress(CURRENT_USER_ID, CURRENT_DEVICE_ID, 1)
        val SELF_OTHER_DEVICE =
            SecureMessagingCryptoAddress(CURRENT_USER_ID, SELF_OTHER_DEVICE_ID, 3)
        val PEER = SecureMessagingCryptoAddress(PEER_USER_ID, PEER_DEVICE_ID, 2)
        val MEDIA_KEY_MATERIAL: String = Base64.getEncoder()
            .encodeToString(ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES))
        val MEDIA_V2_TEXT: String = KitMediaMessageV2(
            items = listOf(
                KitMediaMessageV2Item(
                    attachmentId = "11111111-1111-4111-8111-111111111111",
                    storageKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    mediaType = "image/jpeg",
                    ciphertextByteSize = 1_088,
                    ciphertextSha256 = "1".repeat(64),
                    keyMaterialBase64 = MEDIA_KEY_MATERIAL,
                    plaintextByteSize = 1_024,
                ),
                KitMediaMessageV2Item(
                    attachmentId = "22222222-2222-4222-8222-222222222222",
                    storageKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    mediaType = "video/mp4",
                    ciphertextByteSize = 5_242_944,
                    ciphertextSha256 = "2".repeat(64),
                    keyMaterialBase64 = MEDIA_KEY_MATERIAL,
                    plaintextByteSize = 5_242_880,
                ),
            ),
            caption = "Family photos",
        ).encode()
    }
}
