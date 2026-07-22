package com.kit.wallet

import com.kit.wallet.data.messaging.FailClosedSecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.OpaqueCryptoBytes
import com.kit.wallet.data.messaging.RealSecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingActiveSessionRegistry
import com.kit.wallet.data.messaging.SecureMessagingCommittedResult
import com.kit.wallet.data.messaging.SecureMessagingCompanionStateIntent
import com.kit.wallet.data.messaging.SecureMessagingCurrentActivationRevocation
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.SecureMessagingCryptoOperation
import com.kit.wallet.data.messaging.SecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.SecureMessagingCryptoWireMapper
import com.kit.wallet.data.messaging.SecureMessagingCryptographicFailureException
import com.kit.wallet.data.messaging.SecureMessagingDecryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingDecryptionRequestSnapshot
import com.kit.wallet.data.messaging.SecureMessagingEncryptionPlan
import com.kit.wallet.data.messaging.SecureMessagingEncryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingEventProcessor
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotification
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotificationSink
import com.kit.wallet.data.messaging.SecureMessagingNotificationPublicationException
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingProvisioningPlan
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordPage
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionEstablishmentRequest
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.SecureMessagingSyncCursorStore
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.requireSecureMessagingSyncResumePosition
import com.kit.wallet.data.repository.DefaultSecureMessagingChatRuntime
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.ENCRYPTED_ATTACHMENT_MESSAGE_KIND
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedAttachmentDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessageDeliveryAcknowledgementDto
import com.kit.wallet.data.remote.MessageDeliveryReceiptDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.MessagingSyncDto
import com.kit.wallet.data.remote.MessagingSyncEventDataDto
import com.kit.wallet.data.remote.MessagingSyncEventDto
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SecureMessagingEventProcessorTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: RemoteSecureMessagingTransport
    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        transport = RemoteSecureMessagingTransport(
            retrofit.create(KitWalletApi::class.java),
            retrofit.create(SecureMessagingWireApi::class.java),
            ApiCallExecutor(moshi),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `initial sync durably processes inbound and outbound before advancing cursor`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val crypto = PersistingDecryptionEngine(stateStore)
        val projections = projectionStore(stateStore)
        val processor = processor(stateStore, crypto, projections)
        val roster = authoritativeRoster()
        val outbound = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = requireNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = "pending outbound",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(7, 8, 9),
                ),
            ),
        )
        projections.recordOutboundPending(outbound, Instant.parse("2026-07-20T11:59:00Z"))
        enqueueSync(
            events = listOf(incomingEvent(roster, eventId = 10), outboundEvent(roster, eventId = 11)),
            nextCursor = "cursor_one",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()

        RealSecureMessagingInitialSyncActivation(processor).synchronize(session)

        assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
        assertTrue(server.takeRequest().path?.contains("/device-roster/v1:sha256:") == true)
        assertEquals(
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
            server.takeRequest().path,
        )
        assertEquals(1, crypto.openedTransactions)
        val inbound = checkNotNull(projections.readInbound(INCOMING_MESSAGE_ID))
        assertEquals("processor plaintext", inbound.authenticatedText)
        val projected = projections.readPage(limit = 10).messages()
        val projectedInbound = projected.single { it.durableRecord.messageId == INCOMING_MESSAGE_ID }
        assertEquals(SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED, projectedInbound.deliveryState)
        val projectedOutbound = projected.single { it.durableRecord.clientMessageId == OUTBOUND_CLIENT_ID }
        assertEquals(OUTBOUND_SERVER_ID, projectedOutbound.serverMessageId)
        assertEquals(SecureMessagingProjectionDeliveryState.OUTBOUND_SENT, projectedOutbound.deliveryState)
        val persisted = checkNotNull(SecureMessagingSyncCursorStore(stateStore).load())
        assertEquals(
            "cursor_one" to 11L,
            requireSecureMessagingSyncResumePosition(persisted.position),
        )

        enqueueSync(events = emptyList(), nextCursor = "cursor_two")
        processor.synchronize(session)
        val resumed = server.takeRequest()
        assertTrue(resumed.path?.contains("cursor=cursor_one") == true)
        assertTrue(resumed.path?.contains("limit=50") == true)
        assertEquals(1, crypto.openedTransactions)
    }

    @Test
    fun `receipt events and an exact send replay cannot regress read state`() =
        runTest {
            val (session, _, _) = openSyncingSession()
            val stateStore = TestSecureMessagingStateStore()
            val projections = projectionStore(stateStore)
            val processor = processor(
                stateStore,
                PersistingDecryptionEngine(stateStore),
                projections,
            )
            val roster = authoritativeRoster()
            val outbound = stateStore.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
                direction = LibSignalCompanionDirection.OUTBOUND,
                messageId = OUTBOUND_CLIENT_ID,
                clientMessageId = OUTBOUND_CLIENT_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = checkNotNull(roster.rosterRevision),
                sender = CURRENT,
                text = "receipt projection",
                envelopes = listOf(
                    PersistedCompanionEnvelopeFixture(
                        PEER,
                        SecureMessagingEnvelopeKind.SESSION,
                        byteArrayOf(7, 8, 9),
                    ),
                ),
            )
            projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
            enqueueSync(
                events = listOf(
                    outboundEvent(roster, 10),
                    deliveryReceiptEvent(11),
                    readReceiptEvent(12, PEER_USER_ID),
                    deliveryReceiptEvent(13),
                    outboundEvent(roster, 14),
                ),
                nextCursor = "receipt_cursor",
            )
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))

            processor.synchronize(session)

            assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
            assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
            val restarted = projectionStore(stateStore).readPage(limit = 10).messages().single()
            assertEquals(OUTBOUND_SERVER_ID, restarted.serverMessageId)
            assertEquals(SecureMessagingProjectionDeliveryState.OUTBOUND_READ, restarted.deliveryState)
            assertEquals(
                "receipt_cursor" to 14L,
                requireSecureMessagingSyncResumePosition(
                    checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
                ),
            )
        }

    @Test
    fun `read on another account device clears peer inbound state idempotently`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(stateStore),
            projections,
        )
        val roster = authoritativeRoster()
        enqueueSync(
            events = listOf(
                incomingEvent(roster, 10),
                readReceiptEvent(11, CURRENT_USER_ID, INCOMING_MESSAGE_ID),
                // A replay before cursor persistence must be a monotonic no-op.
                readReceiptEvent(12, CURRENT_USER_ID, INCOMING_MESSAGE_ID),
            ),
            nextCursor = "own_read_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()

        processor.synchronize(session)

        val projected = projections.readPage(limit = 10).messages().single()
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_READ,
            projected.deliveryState,
        )
        assertEquals(2, projections.changes.value)
        assertEquals(
            "own_read_cursor" to 12L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
    }

    @Test
    fun `peer read marker includes lower UUID messages at the same server timestamp`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(stateStore),
            projections,
        )
        val roster = authoritativeRoster()
        suspend fun persistOutbound(clientMessageId: String, text: String) =
            stateStore.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.outboundRecordKey(clientMessageId),
                direction = LibSignalCompanionDirection.OUTBOUND,
                messageId = clientMessageId,
                clientMessageId = clientMessageId,
                conversationId = CONVERSATION_ID,
                rosterRevision = checkNotNull(roster.rosterRevision),
                sender = CURRENT,
                text = text,
                envelopes = listOf(
                    PersistedCompanionEnvelopeFixture(
                        PEER,
                        SecureMessagingEnvelopeKind.SESSION,
                        byteArrayOf(7, 8, 9),
                    ),
                ),
            )
        val first = persistOutbound(OUTBOUND_CLIENT_ID, "first equal-time message")
        val second = persistOutbound(
            SECOND_OUTBOUND_CLIENT_ID,
            "second equal-time message",
        )
        projections.recordOutboundPending(first, Instant.parse(TIMESTAMP))
        projections.recordOutboundPending(second, Instant.parse(TIMESTAMP))
        enqueueSync(
            events = listOf(
                outboundEvent(roster, 10),
                outboundEvent(
                    roster,
                    11,
                    clientMessageId = SECOND_OUTBOUND_CLIENT_ID,
                    serverMessageId = SECOND_OUTBOUND_SERVER_ID,
                ),
                readReceiptEvent(
                    12,
                    PEER_USER_ID,
                    lastReadMessageId = SECOND_OUTBOUND_SERVER_ID,
                ),
            ),
            nextCursor = "equal_timestamp_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))

        processor.synchronize(session)

        val states = projections.readPage(limit = 10).messages()
            .associate { it.serverMessageId to it.deliveryState }
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
            states[OUTBOUND_SERVER_ID],
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
            states[SECOND_OUTBOUND_SERVER_ID],
        )
    }

    @Test
    fun `concurrent send response and sync echo converge on one immutable receipt`() = runTest {
        val (session, _, _) = openSyncingSession()
        val roster = authoritativeRoster()
        enqueueSync(events = listOf(outboundEvent(roster, 10)), nextCursor = "race_cursor")
        val batch = session.sync(session.initialSyncCheckpoint(), limit = 50)
        val event = batch.events().single() as RemoteSecureMessagingTransport.Session.OutboundEvent

        val durableState = TestSecureMessagingStateStore()
        val racingState = TwoWriterProjectionRaceStateStore(durableState)
        val projections = projectionStore(racingState)
        val outbound = durableState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = "racing receipt",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(1, 2, 3),
                ),
            ),
        )
        projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
        racingState.interceptNextTwoMetadataWrites(outbound.recordKey)

        listOf(
            async { projections.markOutboundSent(outbound, event) },
            async { projections.markOutboundSent(outbound, event) },
        ).awaitAll()

        val projected = projections.readPage(limit = 10).messages().single()
        assertEquals(OUTBOUND_SERVER_ID, projected.serverMessageId)
        assertEquals(SecureMessagingProjectionDeliveryState.OUTBOUND_SENT, projected.deliveryState)
        assertEquals(2, racingState.interceptedWrites.get())
    }

    @Test
    fun `concurrent delivery and read receipt converge monotonically and replay is idempotent`() =
        runTest {
            val (session, _, _) = openSyncingSession()
            val roster = authoritativeRoster()
            enqueueSync(events = listOf(outboundEvent(roster, 10)), nextCursor = "race_cursor")
            val batch = session.sync(session.initialSyncCheckpoint(), limit = 50)
            val event = batch.events().single() as RemoteSecureMessagingTransport.Session.OutboundEvent

            val durableState = TestSecureMessagingStateStore()
            val racingState = TwoWriterProjectionRaceStateStore(durableState)
            val projections = projectionStore(racingState)
            val outbound = durableState.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
                direction = LibSignalCompanionDirection.OUTBOUND,
                messageId = OUTBOUND_CLIENT_ID,
                clientMessageId = OUTBOUND_CLIENT_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = checkNotNull(roster.rosterRevision),
                sender = CURRENT,
                text = "racing delivery and read",
                envelopes = listOf(
                    PersistedCompanionEnvelopeFixture(
                        PEER,
                        SecureMessagingEnvelopeKind.SESSION,
                        byteArrayOf(1, 2, 3),
                    ),
                ),
            )
            projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
            projections.markOutboundSent(outbound, event)
            racingState.interceptNextTwoMetadataWrites(outbound.recordKey)

            listOf(
                async {
                    projections.markAuthoredDelivered(
                        CONVERSATION_ID,
                        OUTBOUND_SERVER_ID,
                        CURRENT_USER_ID,
                        Instant.parse(TIMESTAMP),
                    )
                },
                async {
                    projections.markAuthoredReadThrough(
                        CONVERSATION_ID,
                        OUTBOUND_SERVER_ID,
                        CURRENT_USER_ID,
                        Instant.parse(TIMESTAMP),
                    )
                },
            ).awaitAll()

            assertEquals(
                SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
                projections.readPage(limit = 10).messages().single().deliveryState,
            )
            val revision = projections.changes.value
            projections.markAuthoredDelivered(
                CONVERSATION_ID,
                OUTBOUND_SERVER_ID,
                CURRENT_USER_ID,
                Instant.parse(TIMESTAMP),
            )
            projections.markAuthoredReadThrough(
                CONVERSATION_ID,
                OUTBOUND_SERVER_ID,
                CURRENT_USER_ID,
                Instant.parse(TIMESTAMP),
            )
            assertEquals(revision, projections.changes.value)
        }

    @Test
    fun `current user read event never advances a self-authored outbound message`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(stateStore),
            projections,
        )
        val roster = authoritativeRoster()
        val outbound = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = "self receipt",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(7, 8, 9),
                ),
            ),
        )
        projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
        enqueueSync(
            events = listOf(
                outboundEvent(roster, 10),
                readReceiptEvent(11, CURRENT_USER_ID),
            ),
            nextCursor = "self_receipt_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))

        processor.synchronize(session)

        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
            projections.readPage(limit = 10).messages().single().deliveryState,
        )
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `failed decrypt commit retains batch and request for retry without a second sync`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val crypto = PersistingDecryptionEngine(stateStore, failedCommits = 1)
        val processor = processor(stateStore, crypto)
        val roster = authoritativeRoster()
        enqueueSync(listOf(incomingEvent(roster, 10)), "retry_cursor")
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)

        assertTrue(runCatching { processor.synchronize(session) }.isFailure)
        assertEquals(6, server.requestCount)
        repeat(3) { server.takeRequest() }
        assertNull(projectionStore(stateStore).readInbound(INCOMING_MESSAGE_ID))

        enqueueDeliveryAcknowledgement()
        processor.synchronize(session)

        assertEquals(7, server.requestCount)
        assertEquals(
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
            server.takeRequest().path,
        )
        assertEquals(2, crypto.openedTransactions)
        assertNotNull(projectionStore(stateStore).readInbound(INCOMING_MESSAGE_ID))
    }

    @Test
    fun `failed acknowledgement retains token and durable event without ratchet replay`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val crypto = PersistingDecryptionEngine(stateStore)
        val processor = processor(stateStore, crypto)
        val roster = authoritativeRoster()
        enqueueSync(listOf(incomingEvent(roster, 10)), "ack_cursor")
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":{"code":"temporary","message":"retry"}}"""),
        )

        assertTrue(runCatching { processor.synchronize(session) }.isFailure)
        assertEquals(1, crypto.openedTransactions)
        assertNotNull(projectionStore(stateStore).readInbound(INCOMING_MESSAGE_ID))
        assertNull(SecureMessagingSyncCursorStore(stateStore).load())

        enqueueDeliveryAcknowledgement()
        processor.synchronize(session)

        assertEquals(1, crypto.openedTransactions)
        assertEquals(8, server.requestCount)
        val persisted = checkNotNull(SecureMessagingSyncCursorStore(stateStore).load())
        assertEquals("ack_cursor" to 10L, requireSecureMessagingSyncResumePosition(persisted.position))
    }

    @Test
    fun `notification failure leaves committed inbound retryable before cursor advance`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val crypto = PersistingDecryptionEngine(stateStore)
        val notifications = FailOnceIdempotentNotificationSink(failAfterPublish = false)
        val processor = processor(
            stateStore = stateStore,
            crypto = crypto,
            notifications = notifications,
        )
        val roster = authoritativeRoster()
        enqueueSync(listOf(incomingEvent(roster, 10)), "notification_retry_cursor")
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)

        val failure = runCatching { processor.synchronize(session) }.exceptionOrNull()

        assertTrue(failure is SecureMessagingNotificationPublicationException)
        assertEquals("injected notification failure", failure?.cause?.message)
        assertEquals(1, crypto.openedTransactions)
        assertNotNull(projectionStore(stateStore).readInbound(INCOMING_MESSAGE_ID))
        assertNull(SecureMessagingSyncCursorStore(stateStore).load())
        assertEquals(1, notifications.publishAttempts)
        assertTrue(notifications.visibleNotifications.isEmpty())
        assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
        assertTrue(server.takeRequest().path?.contains("/device-roster/v1:sha256:") == true)

        enqueueDeliveryAcknowledgement()
        processor.synchronize(session)

        assertEquals(
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
            server.takeRequest().path,
        )
        assertEquals(1, crypto.openedTransactions)
        assertEquals(2, notifications.publishAttempts)
        assertEquals(setOf(INCOMING_MESSAGE_ID), notifications.visibleNotifications.keys)
        val persisted = checkNotNull(SecureMessagingSyncCursorStore(stateStore).load())
        assertEquals(
            "notification_retry_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(persisted.position),
        )
    }

    @Test
    fun `restart after ambiguous notification publish replaces the same durable message`() =
        runTest {
            val (firstSession, _, _) = openSyncingSession()
            val stateStore = TestSecureMessagingStateStore()
            val crypto = PersistingDecryptionEngine(stateStore)
            val notifications = FailOnceIdempotentNotificationSink(failAfterPublish = true)
            val roster = authoritativeRoster()
            enqueueSync(listOf(incomingEvent(roster, 10)), "ambiguous_notification_cursor")
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            enqueueRoster(roster)

            val failure = runCatching {
                processor(
                    stateStore = stateStore,
                    crypto = crypto,
                    notifications = notifications,
                ).synchronize(firstSession)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingNotificationPublicationException)
            assertEquals("injected notification failure", failure?.cause?.message)
            assertEquals(1, crypto.openedTransactions)
            assertNotNull(projectionStore(stateStore).readInbound(INCOMING_MESSAGE_ID))
            assertNull(SecureMessagingSyncCursorStore(stateStore).load())
            assertEquals(1, notifications.publishAttempts)
            assertEquals(setOf(INCOMING_MESSAGE_ID), notifications.visibleNotifications.keys)
            assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
            assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
            assertTrue(server.takeRequest().path?.contains("/device-roster/v1:sha256:") == true)

            val (restartedSession, _, _) = openSyncingSession()
            enqueueSync(
                listOf(incomingEvent(roster, 10)),
                "ambiguous_notification_cursor",
            )
            enqueueDeliveryAcknowledgement()
            processor(
                stateStore = stateStore,
                crypto = crypto,
                notifications = notifications,
            ).synchronize(restartedSession)

            assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
            assertEquals(
                "/api/kit-wallet/v1/messaging/messages/delivery-acks",
                server.takeRequest().path,
            )
            assertEquals(1, crypto.openedTransactions)
            assertEquals(2, notifications.publishAttempts)
            assertEquals(setOf(INCOMING_MESSAGE_ID), notifications.visibleNotifications.keys)
            assertEquals(
                BINDING.sessionEpoch,
                notifications.visibleNotifications.getValue(INCOMING_MESSAGE_ID).sessionEpoch,
            )
            val persisted = checkNotNull(SecureMessagingSyncCursorStore(stateStore).load())
            assertEquals(
                "ambiguous_notification_cursor" to 10L,
                requireSecureMessagingSyncResumePosition(persisted.position),
            )
        }

    @Test
    fun `process restart replays durable inbound without advancing the ratchet twice`() = runTest {
        val (firstSession, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val crypto = PersistingDecryptionEngine(stateStore)
        val notifications = mutableListOf<SecureMessagingIncomingNotification>()
        val notificationSink = SecureMessagingIncomingNotificationSink(notifications::add)
        val roster = authoritativeRoster()
        enqueueSync(listOf(incomingEvent(roster, 10)), "restart_cursor")
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":{"code":"temporary","message":"retry"}}"""),
        )
        assertTrue(
            runCatching {
                processor(
                    stateStore,
                    crypto,
                    notifications = notificationSink,
                ).synchronize(firstSession)
            }.isFailure,
        )
        repeat(4) { server.takeRequest() }
        assertEquals(1, crypto.openedTransactions)
        assertNotNull(projectionStore(stateStore).readInbound(INCOMING_MESSAGE_ID))
        assertEquals(1, notifications.size)

        val (restartedSession, _, _) = openSyncingSession()
        enqueueSync(listOf(incomingEvent(roster, 10)), "restart_cursor")
        enqueueDeliveryAcknowledgement()
        processor(
            stateStore,
            crypto,
            notifications = notificationSink,
        ).synchronize(restartedSession)

        assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
        assertEquals(
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
            server.takeRequest().path,
        )
        assertEquals(1, crypto.openedTransactions)
        assertEquals(1, notifications.size)
        val persisted = checkNotNull(SecureMessagingSyncCursorStore(stateStore).load())
        assertEquals(
            "restart_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(persisted.position),
        )
    }

    @Test
    fun `cursor commit conflict reconciles an already persisted exact checkpoint`() = runTest {
        val (session, _, _) = openSyncingSession()
        val delegate = TestSecureMessagingStateStore()
        val stateStore = CommitThenConflictStateStore(delegate)
        val processor = SecureMessagingEventProcessor(
            PersistingDecryptionEngine(delegate),
            projectionStore(stateStore),
            SecureMessagingSyncCursorStore(stateStore),
        )
        enqueueSync(events = emptyList(), nextCursor = "committed_cursor")

        processor.synchronize(session)

        assertEquals(1, stateStore.injectedConflicts)
        val loaded = checkNotNull(SecureMessagingSyncCursorStore(stateStore).load())
        assertEquals(
            "committed_cursor" to null,
            requireSecureMessagingSyncResumePosition(loaded.position),
        )
    }

    @Test
    fun `authenticated durable plaintext drives the local notification sink`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val notifications = mutableListOf<SecureMessagingIncomingNotification>()
        val processor = SecureMessagingEventProcessor(
            PersistingDecryptionEngine(stateStore),
            projectionStore(stateStore),
            SecureMessagingSyncCursorStore(stateStore),
            SecureMessagingIncomingNotificationSink(notifications::add),
        )
        val roster = authoritativeRoster()
        enqueueSync(listOf(incomingEvent(roster, 10)), "notification_cursor")
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()

        processor.synchronize(session)

        assertEquals(1, notifications.size)
        assertEquals(INCOMING_MESSAGE_ID, notifications.single().messageId)
        assertEquals(CONVERSATION_ID, notifications.single().conversationId)
        assertEquals(BINDING.sessionEpoch, notifications.single().sessionEpoch)
    }

    @Test
    fun `matching authenticated media metadata is projected and acknowledged`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val media = mediaDescriptor()
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(stateStore, authenticatedText = media.encode()),
            projections,
        )
        val roster = authoritativeRoster()
        enqueueSync(
            listOf(
                incomingEvent(
                    roster = roster,
                    eventId = 10,
                    kind = ENCRYPTED_ATTACHMENT_MESSAGE_KIND,
                    attachments = listOf(mediaAttachment(media)),
                ),
            ),
            "media_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()

        processor.synchronize(session)

        assertEquals(
            media.encode(),
            checkNotNull(projections.readInbound(INCOMING_MESSAGE_ID)).authenticatedText,
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
            projections.readPage(limit = 10).messages().single().deliveryState,
        )
        assertEquals(
            "media_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
    }

    @Test
    fun `forged wire attachment metadata is dropped without quarantining activation`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            lifecycle.finishActivation(fence)
            val stateStore = TestSecureMessagingStateStore()
            val projections = projectionStore(stateStore)
            val notifications = mutableListOf<SecureMessagingIncomingNotification>()
            val media = mediaDescriptor()
            val processor = processor(
                stateStore,
                PersistingDecryptionEngine(stateStore, authenticatedText = media.encode()),
                projections,
                SecureMessagingIncomingNotificationSink(notifications::add),
            )
            val roster = authoritativeRoster()
            enqueueSync(
                listOf(
                    incomingEvent(
                        roster = roster,
                        eventId = 10,
                        kind = ENCRYPTED_ATTACHMENT_MESSAGE_KIND,
                        attachments = listOf(
                            mediaAttachment(media).copy(ciphertextSha256 = "cd".repeat(32)),
                        ),
                    ),
                ),
                "forged_media_cursor",
            )
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            enqueueRoster(roster)
            enqueueDeliveryAcknowledgement()

            processor.synchronize(session)

            assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
            assertNotNull(projections.readInbound(INCOMING_MESSAGE_ID))
            assertTrue(projections.readPage(limit = 10).messages().isEmpty())
            val restarted = projectionStore(stateStore)
            assertTrue(restarted.readPage(limit = 10).messages().isEmpty())
            assertNull(
                restarted.newestUnreadInboundMessageId(CONVERSATION_ID, PEER_USER_ID),
            )
            assertTrue(notifications.isEmpty())
            assertEquals(
                "forged_media_cursor" to 10L,
                requireSecureMessagingSyncResumePosition(
                    checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
                ),
            )
        }

    @Test
    fun `authenticated media without wire attachment metadata is dropped locally`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val media = mediaDescriptor()
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(stateStore, authenticatedText = media.encode()),
            projections,
        )
        val roster = authoritativeRoster()
        enqueueSync(
            listOf(incomingEvent(roster = roster, eventId = 10)),
            "missing_media_metadata_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()

        processor.synchronize(session)

        assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
        assertNotNull(projections.readInbound(INCOMING_MESSAGE_ID))
        assertTrue(projections.readPage(limit = 10).messages().isEmpty())
        assertEquals(
            "missing_media_metadata_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
    }

    @Test
    fun `malicious reserved prefix text remains ordinary and cannot quarantine`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val text = "${KitMediaMessage.PREFIX}v=1&id=invalid"
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(
                stateStore,
                authenticatedText = text,
            ),
            projections,
        )
        val roster = authoritativeRoster()
        enqueueSync(listOf(incomingEvent(roster, 10)), "malformed_media_cursor")
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()

        processor.synchronize(session)

        assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
        assertEquals(text, projections.readPage(limit = 10).messages().single().durableRecord.authenticatedText)
        assertEquals(
            "malformed_media_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
    }

    @Test
    fun `malformed sync page quarantines ready session and cannot be retried in place`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val processor = processor(stateStore, PersistingDecryptionEngine(stateStore))
        enqueueSync(
            events = listOf(
                deviceLifecycleEvent(
                    eventType = "device.revoked",
                    userId = PEER.userId,
                    deviceId = PEER.serverDeviceId,
                ).copy(data = null),
            ),
            nextCursor = "malformed_cursor",
        )

        val failure = runCatching { processor.synchronize(session) }.exceptionOrNull()

        assertTrue(failure is SecureMessagingCryptographicFailureException)
        assertEquals(
            SecureMessagingQuarantineReason.MALFORMED_WIRE_DATA,
            (failure as SecureMessagingCryptographicFailureException).quarantineReason,
        )
        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, lifecycle.snapshot().stage)
        val requestCount = server.requestCount
        assertTrue(runCatching { processor.synchronize(session) }.isFailure)
        assertEquals(requestCount, server.requestCount)
    }

    @Test
    fun `current device revocation leaves ready cancels notifications and starts erasure`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            lifecycle.finishActivation(fence)
            val stateStore = TestSecureMessagingStateStore()
            val crypto = PersistingDecryptionEngine(stateStore)
            val notifications = RecordingNotificationSink()
            var erasures = 0
            val processor = processor(
                stateStore = stateStore,
                crypto = crypto,
                notifications = notifications,
                currentActivationRevocation = SecureMessagingCurrentActivationRevocation {
                    erasures++
                },
            )
            enqueueSync(
                listOf(
                    deviceLifecycleEvent(
                        eventType = "device.revoked",
                        userId = CURRENT.userId,
                        deviceId = CURRENT.serverDeviceId,
                    ),
                ),
                "revoked_cursor",
            )

            val failure = runCatching { processor.synchronize(session) }.exceptionOrNull()

            assertTrue(failure is SecureMessagingCryptographicFailureException)
            assertEquals(
                SecureMessagingQuarantineReason.CURRENT_DEVICE_REVOKED,
                (failure as SecureMessagingCryptographicFailureException).quarantineReason,
            )
            assertEquals(SecureMessagingRuntimeStage.QUARANTINED, lifecycle.snapshot().stage)
            assertEquals(1, notifications.cancellations)
            assertEquals(1, erasures)
            assertTrue(crypto.retiredDevices.isEmpty())
            assertNull(SecureMessagingSyncCursorStore(stateStore).load())
        }

    @Test
    fun `peer revocation retires exact peer state before cursor confirmation`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val crypto = PersistingDecryptionEngine(stateStore)
        val processor = processor(stateStore, crypto)
        enqueueSync(
            listOf(
                deviceLifecycleEvent(
                    eventType = "device.revoked",
                    userId = PEER.userId,
                    deviceId = PEER.serverDeviceId,
                ),
            ),
            "peer_revoked_cursor",
        )

        processor.synchronize(session)

        assertEquals(listOf(PEER.userId to PEER.serverDeviceId), crypto.retiredDevices)
        assertEquals(
            "peer_revoked_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
    }

    @Test
    fun `stale process outbox is resent as exact committed ciphertext after sync reconciliation`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            lifecycle.finishActivation(fence)
            val stateStore = TestSecureMessagingStateStore()
            val projections = projectionStore(stateStore)
            val processor = processor(stateStore, PersistingDecryptionEngine(stateStore), projections)
            val roster = authoritativeRoster()
            val ciphertext = byteArrayOf(7, 8, 9)
            val outbound = stateStore.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
                direction = LibSignalCompanionDirection.OUTBOUND,
                messageId = OUTBOUND_CLIENT_ID,
                clientMessageId = OUTBOUND_CLIENT_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = checkNotNull(roster.rosterRevision),
                sender = CURRENT,
                text = "recover me",
                envelopes = listOf(
                    PersistedCompanionEnvelopeFixture(
                        PEER,
                        SecureMessagingEnvelopeKind.SESSION,
                        ciphertext,
                    ),
                ),
            )
            projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            enqueueRoster(roster)
            enqueueOutboundReceipt(roster, OUTBOUND_CLIENT_ID)

            processor.recoverPendingOutbox(session)

            assertEquals(
                "/api/kit-wallet/v1/messaging/conversations",
                server.takeRequest().path,
            )
            assertTrue(server.takeRequest().path?.endsWith("/device-roster") == true)
            val sent = server.takeRequest()
            assertTrue(sent.path?.endsWith("/messages") == true)
            assertTrue(
                sent.body.readUtf8().contains(Base64.getEncoder().encodeToString(ciphertext)),
            )
            val projected = projections.readPage(limit = 10).messages().single()
            assertEquals(SecureMessagingProjectionDeliveryState.OUTBOUND_SENT, projected.deliveryState)
            assertEquals(OUTBOUND_SERVER_ID, projected.serverMessageId)
        }

    @Test
    fun `stale media outbox retry preserves authenticated attachment metadata`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(stateStore, PersistingDecryptionEngine(stateStore), projections)
        val roster = authoritativeRoster()
        val descriptor = KitMediaMessage(
            attachmentId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            storageKey = "0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213",
            mediaType = "image/jpeg",
            ciphertextByteSize = 4_096,
            ciphertextSha256 = "ab".repeat(32),
            keyMaterialBase64 = Base64.getEncoder().encodeToString(
                ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES) { it.toByte() },
            ),
            plaintextByteSize = 4_000,
            caption = null,
        ).encode()
        val outbound = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = descriptor,
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(7, 8, 9),
                ),
            ),
        )
        projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueOutboundReceipt(roster, OUTBOUND_CLIENT_ID)

        processor.recoverPendingOutbox(session)

        server.takeRequest()
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"kind\":\"encrypted_attachment\""))
        assertTrue(body.contains("\"storage_key\":\"0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213\""))
        assertTrue(body.contains("\"ciphertext_sha256\":\"${"ab".repeat(32)}\""))
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
            projections.readPage(limit = 10).messages().single().deliveryState,
        )
    }

    @Test
    fun `permanent and compatibility media failures do not starve later text`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(stateStore, PersistingDecryptionEngine(stateStore), projections)
        val roster = authoritativeRoster()
        val expiredMedia = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = encodedMediaDescriptor(),
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(7, 8, 9),
                ),
            ),
        )
        val upgradeBlockedMedia = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(
                SECOND_OUTBOUND_CLIENT_ID,
            ),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = SECOND_OUTBOUND_CLIENT_ID,
            clientMessageId = SECOND_OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = encodedMediaDescriptor(),
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(10, 11, 12),
                ),
            ),
        )
        val laterText = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(
                THIRD_OUTBOUND_CLIENT_ID,
            ),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = THIRD_OUTBOUND_CLIENT_ID,
            clientMessageId = THIRD_OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = "send after blocked media",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(13, 14, 15),
                ),
            ),
        )
        projections.recordOutboundPending(expiredMedia, Instant.parse(TIMESTAMP))
        projections.recordOutboundPending(upgradeBlockedMedia, Instant.parse(TIMESTAMP))
        projections.recordOutboundPending(laterText, Instant.parse(TIMESTAMP))
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        server.enqueue(apiErrorResponse(422, "ATTACHMENT_REFERENCE_INVALID"))
        server.enqueue(apiErrorResponse(409, "MESSAGING_ATTACHMENT_CLIENT_UPGRADE_REQUIRED"))
        enqueueOutboundReceipt(roster, THIRD_OUTBOUND_CLIENT_ID)

        processor.recoverPendingOutbox(session)

        server.takeRequest()
        server.takeRequest()
        val expiredRequest = server.takeRequest().body.readUtf8()
        val upgradeBlockedRequest = server.takeRequest().body.readUtf8()
        val textRequest = server.takeRequest().body.readUtf8()
        assertTrue(expiredRequest.contains("\"client_message_id\":\"$OUTBOUND_CLIENT_ID\""))
        assertTrue(
            upgradeBlockedRequest.contains(
                "\"client_message_id\":\"$SECOND_OUTBOUND_CLIENT_ID\"",
            ),
        )
        assertTrue(textRequest.contains("\"client_message_id\":\"$THIRD_OUTBOUND_CLIENT_ID\""))
        val projected = projections.readPage(limit = 10).messages()
            .associateBy { it.durableRecord.clientMessageId }
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
            projected.getValue(OUTBOUND_CLIENT_ID).deliveryState,
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
            projected.getValue(SECOND_OUTBOUND_CLIENT_ID).deliveryState,
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
            projected.getValue(THIRD_OUTBOUND_CLIENT_ID).deliveryState,
        )
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
            projectionStore(stateStore).readPage(limit = 10).messages()
                .single { it.durableRecord.clientMessageId == OUTBOUND_CLIENT_ID }
                .deliveryState,
        )
    }

    @Test
    fun `already attached media outbox is retired as a permanent binding failure`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(stateStore, PersistingDecryptionEngine(stateStore), projections)
        val roster = authoritativeRoster()
        val media = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = encodedMediaDescriptor(),
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(7, 8, 9),
                ),
            ),
        )
        projections.recordOutboundPending(media, Instant.parse(TIMESTAMP))
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        server.enqueue(apiErrorResponse(409, "ATTACHMENT_ALREADY_ATTACHED"))

        processor.recoverPendingOutbox(session)

        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
            projections.readPage(limit = 10).messages().single().deliveryState,
        )

        // Bypass Compose and exercise the runtime boundary directly: a stale descriptor cannot
        // be encrypted under a fresh client ID or issue another message POST.
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        registry.publish(session, fence)
        val runtime = DefaultSecureMessagingChatRuntime(
            sessions = registry,
            engine = PersistingDecryptionEngine(stateStore),
            projections = projections,
            syncEngine = object : SecureMessagingSyncEngine {
                override val isReady = true
                override suspend fun synchronize() = Unit
            },
            scope = backgroundScope,
            clock = Clock.fixed(Instant.parse(TIMESTAMP), ZoneOffset.UTC),
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster) // Sentinel: a broken gate would consume this response.
        val requestsBeforeRetry = server.requestCount

        runtime.sendText(
            CONVERSATION_ID,
            media.authenticatedText,
            retryClientMessageId = OUTBOUND_CLIENT_ID,
        )

        assertEquals(requestsBeforeRetry + 1, server.requestCount)
    }

    @Test
    fun `transient media outbox failure remains pending and stops later recovery`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(stateStore, PersistingDecryptionEngine(stateStore), projections)
        val roster = authoritativeRoster()
        val media = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = encodedMediaDescriptor(),
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(7, 8, 9),
                ),
            ),
        )
        val laterText = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(
                SECOND_OUTBOUND_CLIENT_ID,
            ),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = SECOND_OUTBOUND_CLIENT_ID,
            clientMessageId = SECOND_OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = checkNotNull(roster.rosterRevision),
            sender = CURRENT,
            text = "must wait for transient retry",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(10, 11, 12),
                ),
            ),
        )
        projections.recordOutboundPending(media, Instant.parse(TIMESTAMP))
        projections.recordOutboundPending(laterText, Instant.parse(TIMESTAMP))
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        server.enqueue(apiErrorResponse(503, "TEMPORARY_FAILURE"))

        val failure = runCatching { processor.recoverPendingOutbox(session) }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(6, server.requestCount)
        assertTrue(
            projections.readPage(limit = 10).messages().all {
                it.deliveryState == SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING
            },
        )
    }

    @Test
    fun `outbox fanout for an obsolete roster is retired without a network send`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(stateStore, PersistingDecryptionEngine(stateStore), projections)
        val roster = authoritativeRoster()
        val outbound = stateStore.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = OUTBOUND_CLIENT_ID,
            clientMessageId = OUTBOUND_CLIENT_ID,
            conversationId = CONVERSATION_ID,
            rosterRevision = "v1:sha256:${"f".repeat(64)}",
            sender = CURRENT,
            text = "obsolete fanout",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    PEER,
                    SecureMessagingEnvelopeKind.SESSION,
                    byteArrayOf(1, 2, 3),
                ),
            ),
        )
        projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)

        processor.recoverPendingOutbox(session)

        assertEquals(5, server.requestCount)
        assertEquals(
            SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
            projections.readPage(limit = 10).messages().single().deliveryState,
        )
    }

    private suspend fun openSyncingSession(): Triple<
        RemoteSecureMessagingTransport.Session,
        SecureMessagingLifecycleGuard,
        SecureMessagingSessionFence,
        > {
        val lifecycle = SecureMessagingLifecycleGuard()
        val fence = lifecycle.beginSession(BINDING)
        enqueueActivation()
        val session = transport.openSession(lifecycle, fence)
        repeat(3) { server.takeRequest() }
        lifecycle.beginKeyPreparation(fence)
        lifecycle.beginRosterSync(fence)
        return Triple(session, lifecycle, fence)
    }

    private fun processor(
        stateStore: SecureMessagingStateStore,
        crypto: SecureMessagingCryptoEngine,
        projections: SecureMessagingProjectionStore = projectionStore(stateStore),
        notifications: SecureMessagingIncomingNotificationSink =
            com.kit.wallet.data.messaging.NoOpSecureMessagingIncomingNotificationSink,
        currentActivationRevocation: SecureMessagingCurrentActivationRevocation =
            com.kit.wallet.data.messaging.NoOpSecureMessagingCurrentActivationRevocation,
    ) = SecureMessagingEventProcessor(
        crypto,
        projections,
        SecureMessagingSyncCursorStore(stateStore),
        notifications,
        currentActivationRevocation,
    )

    private fun projectionStore(stateStore: SecureMessagingStateStore) =
        SecureMessagingProjectionStore(stateStore, LibSignalCompanionStateReader(stateStore))

    private fun enqueueActivation() {
        server.enqueue(jsonResponse(READY_CAPABILITIES))
        server.enqueue(jsonResponse(PROFILE))
        server.enqueue(jsonResponse(DEVICES))
    }

    private fun enqueueRoster(roster: MessagingDeviceRosterDto) {
        val encoded = moshi.adapter(MessagingDeviceRosterDto::class.java).toJson(roster)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun enqueueSync(events: List<MessagingSyncEventDto>, nextCursor: String) {
        val response = MessagingSyncDto(
            events = events,
            page = CursorPageDto(nextCursor = nextCursor, hasMore = false, limit = 50),
        )
        val encoded = moshi.adapter(MessagingSyncDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun enqueueDeliveryAcknowledgement() {
        val response = MessageDeliveryAcknowledgementDto(
            deliveryState = "delivered_to_device",
            deviceId = CURRENT_DEVICE_ID,
            acknowledgedCount = 1,
            newlyAcknowledgedCount = 1,
            items = listOf(MessageDeliveryReceiptDto(INCOMING_MESSAGE_ID, TIMESTAMP)),
        )
        val encoded = moshi.adapter(MessageDeliveryAcknowledgementDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun mediaDescriptor() = KitMediaMessage(
        attachmentId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        storageKey = "0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213",
        mediaType = "image/jpeg",
        ciphertextByteSize = 4_096,
        ciphertextSha256 = "ab".repeat(32),
        keyMaterialBase64 = Base64.getEncoder().encodeToString(
            ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES) { it.toByte() },
        ),
        plaintextByteSize = 4_000,
        caption = null,
    )

    private fun mediaAttachment(media: KitMediaMessage) = EncryptedAttachmentDto(
        id = media.attachmentId,
        storageKey = media.storageKey,
        mediaType = media.mediaType,
        byteSize = media.ciphertextByteSize,
        ciphertextSha256 = media.ciphertextSha256,
        encryptionMetadataCiphertext = null,
    )

    private fun deviceLifecycleEvent(
        eventType: String,
        userId: String,
        deviceId: String,
    ) = MessagingSyncEventDto(
        id = "10",
        type = eventType,
        conversationId = CONVERSATION_ID,
        resourceType = "messaging_device",
        resourceId = deviceId,
        data = MessagingSyncEventDataDto(
            userId = userId,
            deviceId = deviceId,
            signalDeviceId = 2,
            registrationId = 43,
            protocolVersion = "v2",
            bundleVersion = 2,
            identityKeySha256 = "d".repeat(64),
            rosterRefreshRequired = true,
            transitionedAt = TIMESTAMP,
            transitionHash = "e".repeat(64),
        ),
        occurredAt = TIMESTAMP,
    )

    private fun enqueueOutboundReceipt(
        roster: MessagingDeviceRosterDto,
        clientMessageId: String,
    ) {
        val current = roster.devices.orEmpty().filterNotNull()
            .single { it.deviceId == CURRENT_DEVICE_ID }
        val response = EncryptedMessageDto(
            id = OUTBOUND_SERVER_ID,
            conversationId = CONVERSATION_ID,
            clientMessageId = clientMessageId,
            sender = EncryptedMessageSenderDto(CURRENT_USER_ID, "Current User"),
            senderDeviceId = CURRENT_DEVICE_ID,
            senderSignalDeviceId = current.signalDeviceId,
            senderRegistrationId = current.registrationId,
            senderProtocolVersion = "v2",
            senderBundleVersion = current.bundleVersion,
            senderIdentityKeySha256 = current.identityKeySha256,
            rosterRevision = roster.rosterRevision,
            kind = "encrypted",
            replyToMessageId = null,
            envelope = null,
            attachments = emptyList(),
            reactions = emptyList(),
            sentAt = TIMESTAMP,
            revokedAt = null,
        )
        val encoded = moshi.adapter(EncryptedMessageDto::class.java).toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun encodedMediaDescriptor(): String = KitMediaMessage(
        attachmentId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        storageKey = "0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213",
        mediaType = "image/jpeg",
        ciphertextByteSize = 4_096,
        ciphertextSha256 = "ab".repeat(32),
        keyMaterialBase64 = Base64.getEncoder().encodeToString(
            ByteArray(MediaAttachmentCipher.KEY_MATERIAL_BYTES) { it.toByte() },
        ),
        plaintextByteSize = 4_000,
        caption = null,
    ).encode()

    private fun incomingEvent(
        roster: MessagingDeviceRosterDto,
        eventId: Long,
        kind: String = ENCRYPTED_MESSAGE_KIND,
        attachments: List<EncryptedAttachmentDto?> = emptyList(),
    ): MessagingSyncEventDto {
        val peer = roster.devices.orEmpty().filterNotNull().single { it.deviceId == PEER_DEVICE_ID }
        val ciphertext = "opaque incoming ciphertext".toByteArray(StandardCharsets.UTF_8)
        return MessagingSyncEventDto(
            id = eventId.toString(),
            type = "message.created",
            conversationId = CONVERSATION_ID,
            resourceType = "message",
            resourceId = INCOMING_MESSAGE_ID,
            data = MessagingSyncEventDataDto(
                id = INCOMING_MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                clientMessageId = INCOMING_CLIENT_ID,
                sender = EncryptedMessageSenderDto(PEER_USER_ID, "Peer"),
                senderDeviceId = PEER_DEVICE_ID,
                senderSignalDeviceId = peer.signalDeviceId,
                senderRegistrationId = peer.registrationId,
                senderProtocolVersion = "v2",
                senderBundleVersion = peer.bundleVersion,
                senderIdentityKeySha256 = peer.identityKeySha256,
                rosterRevision = roster.rosterRevision,
                kind = kind,
                replyToMessageId = null,
                envelope = EncryptedMessageEnvelopeDto(
                    recipientDeviceId = CURRENT_DEVICE_ID,
                    envelopeType = "signal-message-v2",
                    ciphertext = Base64.getEncoder().encodeToString(ciphertext),
                    ciphertextSha256 = sha256(ciphertext),
                ),
                attachments = attachments,
                reactions = emptyList(),
                sentAt = TIMESTAMP,
                revokedAt = null,
            ),
            occurredAt = TIMESTAMP,
        )
    }

    private fun outboundEvent(
        roster: MessagingDeviceRosterDto,
        eventId: Long,
        clientMessageId: String = OUTBOUND_CLIENT_ID,
        serverMessageId: String = OUTBOUND_SERVER_ID,
    ): MessagingSyncEventDto {
        val current = roster.devices.orEmpty().filterNotNull()
            .single { it.deviceId == CURRENT_DEVICE_ID }
        return MessagingSyncEventDto(
            id = eventId.toString(),
            type = "message.created",
            conversationId = CONVERSATION_ID,
            resourceType = "message",
            resourceId = serverMessageId,
            data = MessagingSyncEventDataDto(
                id = serverMessageId,
                conversationId = CONVERSATION_ID,
                clientMessageId = clientMessageId,
                sender = EncryptedMessageSenderDto(CURRENT_USER_ID, "Current User"),
                senderDeviceId = CURRENT_DEVICE_ID,
                senderSignalDeviceId = current.signalDeviceId,
                senderRegistrationId = current.registrationId,
                senderProtocolVersion = "v2",
                senderBundleVersion = current.bundleVersion,
                senderIdentityKeySha256 = current.identityKeySha256,
                rosterRevision = roster.rosterRevision,
                kind = "encrypted",
                replyToMessageId = null,
                envelope = null,
                attachments = emptyList(),
                reactions = emptyList(),
                sentAt = TIMESTAMP,
                revokedAt = null,
            ),
            occurredAt = TIMESTAMP,
        )
    }

    private fun deliveryReceiptEvent(eventId: Long) = MessagingSyncEventDto(
        id = eventId.toString(),
        type = "message.delivery.updated",
        conversationId = CONVERSATION_ID,
        resourceType = "message_delivery",
        resourceId = OUTBOUND_SERVER_ID,
        data = MessagingSyncEventDataDto(
            messageId = OUTBOUND_SERVER_ID,
            deliveryState = "delivered_to_peer",
            deliveredAt = TIMESTAMP,
        ),
        occurredAt = TIMESTAMP,
    )

    private fun readReceiptEvent(
        eventId: Long,
        userId: String,
        lastReadMessageId: String = OUTBOUND_SERVER_ID,
    ) = MessagingSyncEventDto(
        id = eventId.toString(),
        type = "read_receipt.updated",
        conversationId = CONVERSATION_ID,
        resourceType = "read_receipt",
        resourceId = "$CONVERSATION_ID:42",
        data = MessagingSyncEventDataDto(
            userId = userId,
            lastReadMessageId = lastReadMessageId,
            readAt = TIMESTAMP,
        ),
        occurredAt = TIMESTAMP,
    )

    private fun authoritativeRoster(): MessagingDeviceRosterDto {
        val devices = listOf(
            rosterDevice(CURRENT_DEVICE_ID, CURRENT_USER_ID, 1, 42, 0x11),
            rosterDevice(PEER_DEVICE_ID, PEER_USER_ID, 2, 43, 0x21),
        )
        val hash = sha256(canonicalRosterBytes(devices))
        return MessagingDeviceRosterDto(
            conversationId = CONVERSATION_ID,
            rosterRevision = "v1:sha256:$hash",
            rosterHash = hash,
            hashAlgorithm = "sha256",
            devices = devices,
        )
    }

    private fun rosterDevice(
        deviceId: String,
        userId: String,
        signalDeviceId: Int,
        registrationId: Int,
        seed: Int,
    ): MessagingDeviceRosterEntryDto {
        val identityKey = signalValue(5, seed, 33)
        val signedKey = signalValue(5, seed + 1, 33)
        return MessagingDeviceRosterEntryDto(
            deviceId = deviceId,
            signalDeviceId = signalDeviceId,
            userId = userId,
            registrationId = registrationId,
            protocolVersion = "v2",
            bundleVersion = seed,
            identityKey = identityKey,
            identityKeySha256 = digestBase64(identityKey),
            signedPrekey = MessagingSignedPrekeyDto(
                prekeyId = 1_000 + seed,
                publicKey = signedKey,
                publicKeySha256 = digestBase64(signedKey),
                signature = Base64.getEncoder()
                    .encodeToString(ByteArray(64) { (seed + 2).toByte() }),
            ),
            publishedAt = TIMESTAMP,
            rotatedAt = null,
            identityKeyChangedAt = TIMESTAMP,
            bundleVersionChangedAt = TIMESTAMP,
        )
    }

    private fun canonicalRosterBytes(devices: List<MessagingDeviceRosterEntryDto>): ByteArray =
        buildString {
            append("{\"schema\":\"kit.messaging.device-roster.v1\",\"conversation_id\":\"")
            append(CONVERSATION_ID)
            append("\",\"devices\":[")
            devices.forEachIndexed { index, device ->
                if (index > 0) append(',')
                val signed = checkNotNull(device.signedPrekey)
                append("{\"device_id\":\"${device.deviceId}\",\"user_id\":\"${device.userId}\"")
                append(",\"signal_device_id\":${device.signalDeviceId}")
                append(",\"registration_id\":${device.registrationId}")
                append(",\"protocol_version\":\"${device.protocolVersion}\"")
                append(",\"bundle_version\":${device.bundleVersion}")
                append(",\"identity_key\":\"${device.identityKey}\"")
                append(",\"identity_key_sha256\":\"${device.identityKeySha256}\"")
                append(",\"signed_prekey\":{\"prekey_id\":${signed.prekeyId}")
                append(",\"public_key\":\"${signed.publicKey}\"")
                append(",\"public_key_sha256\":\"${signed.publicKeySha256}\"")
                append(",\"signature\":\"${signed.signature}\"}")
                append(",\"published_at\":\"${device.publishedAt}\",\"rotated_at\":null")
                append(",\"identity_key_changed_at\":\"${device.identityKeyChangedAt}\"")
                append(",\"bundle_version_changed_at\":\"${device.bundleVersionChangedAt}\"}")
            }
            append("]}")
        }.toByteArray(StandardCharsets.UTF_8)

    private fun signalValue(type: Int, fill: Int, size: Int): String = Base64.getEncoder()
        .encodeToString(ByteArray(size) { fill.toByte() }.also { it[0] = type.toByte() })

    private fun digestBase64(value: String): String = sha256(Base64.getDecoder().decode(value))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setResponseCode(200)
        .setBody(body)

    private fun apiErrorResponse(status: Int, code: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setResponseCode(status)
        .setBody("""{"ok":false,"error":{"code":"$code","message":"rejected"}}""")

    private class PersistingDecryptionEngine(
        private val stateStore: TestSecureMessagingStateStore,
        failedCommits: Int = 0,
        private val authenticatedText: String = "processor plaintext",
    ) : SecureMessagingCryptoEngine {
        var openedTransactions = 0
            private set
        val retiredDevices = mutableListOf<Pair<String, String?>>()
        private var remainingFailures = failedCommits

        override suspend fun openTransaction(
            activation: SecureMessagingActivationCapability,
        ): SecureMessagingCryptoTransaction {
            openedTransactions++
            return PersistingDecryptionTransaction(
                activation,
                stateStore,
                shouldFail = { remainingFailures > 0 },
                didFail = { remainingFailures-- },
                authenticatedText = authenticatedText,
            )
        }

        override suspend fun eraseAll() = stateStore.eraseAll()

        override suspend fun retireRemoteDevices(
            activation: SecureMessagingActivationCapability,
            affectedUserId: String,
            affectedServerDeviceId: String?,
        ) {
            retiredDevices += affectedUserId to affectedServerDeviceId
        }
    }

    private class RecordingNotificationSink : SecureMessagingIncomingNotificationSink {
        var cancellations = 0
            private set

        override fun publish(notification: SecureMessagingIncomingNotification) = Unit

        override fun cancelAll() {
            cancellations++
        }
    }

    private class FailOnceIdempotentNotificationSink(
        private val failAfterPublish: Boolean,
    ) : SecureMessagingIncomingNotificationSink {
        var publishAttempts = 0
            private set
        val visibleNotifications = linkedMapOf<String, SecureMessagingIncomingNotification>()

        override fun publish(notification: SecureMessagingIncomingNotification) {
            publishAttempts++
            if (publishAttempts == 1 && !failAfterPublish) {
                throw IllegalStateException("injected notification failure")
            }
            visibleNotifications[notification.messageId] = notification
            if (publishAttempts == 1 && failAfterPublish) {
                throw IllegalStateException("injected notification failure")
            }
        }
    }

    private class PersistingDecryptionTransaction(
        activation: SecureMessagingActivationCapability,
        private val stateStore: TestSecureMessagingStateStore,
        private val shouldFail: () -> Boolean,
        private val didFail: () -> Unit,
        private val authenticatedText: String,
    ) : FailClosedSecureMessagingCryptoTransaction(activation) {
        private var request: SecureMessagingDecryptionRequestSnapshot? = null
        private var destination: CompanionStateDestination? = null

        override suspend fun stageProvisioningMaterial(plan: SecureMessagingProvisioningPlan) =
            error("unexpected provisioning")

        override suspend fun stageSessionMaterial(
            request: SecureMessagingSessionEstablishmentRequest,
        ) = error("unexpected session establishment")

        override suspend fun findMissingSessionAddresses(
            plan: SecureMessagingEncryptionPlan,
            candidates: List<SecureMessagingCryptoAddress>,
        ): Collection<SecureMessagingCryptoAddress> = error("unexpected session lookup")

        override suspend fun stageEncryptionMaterial(request: SecureMessagingEncryptionRequest) =
            error("unexpected encryption")

        override suspend fun stageDecryptionMaterial(request: SecureMessagingDecryptionRequest) {
            this.request = SecureMessagingCryptoWireMapper.requireDecryptionRequest(request)
        }

        override suspend fun prepareStaged(
            operation: SecureMessagingCryptoOperation,
            companionStateIntent: SecureMessagingCompanionStateIntent?,
        ): PreparedCommit {
            check(operation == SecureMessagingCryptoOperation.DECRYPT)
            val snapshot = checkNotNull(request)
            destination = companionStateDestination(checkNotNull(companionStateIntent))
            val plaintext = plaintext(snapshot)
            return try {
                preparedDecryption(
                    snapshot.messageId,
                    snapshot.conversationId,
                    snapshot.sender,
                    plaintext,
                )
            } finally {
                plaintext.fill(0)
            }
        }

        override suspend fun commitPrepared(
            operation: SecureMessagingCryptoOperation,
            preparedResult: PreparedCommit,
        ) {
            check(operation == SecureMessagingCryptoOperation.DECRYPT)
            if (shouldFail()) {
                didFail()
                throw SecureMessagingStateConflictException("injected pre-commit failure")
            }
            val snapshot = checkNotNull(request)
            val target = checkNotNull(destination)
            stateStore.persistCompanionRecordForTest(
                namespace = target.namespace,
                recordKey = target.recordKey,
                direction = LibSignalCompanionDirection.INBOUND,
                messageId = snapshot.messageId,
                clientMessageId = snapshot.clientMessageId,
                conversationId = snapshot.conversationId,
                rosterRevision = snapshot.rosterRevision,
                sender = snapshot.sender,
                replyToMessageId = snapshot.replyToMessageId,
                text = authenticatedText,
            )
        }

        override suspend fun abortStaged() = Unit

        override fun wipeStagedSecrets() {
            request = null
            destination = null
        }

        private fun plaintext(snapshot: SecureMessagingDecryptionRequestSnapshot): ByteArray {
            val reply = snapshot.replyToMessageId?.let { "\"$it\"" } ?: "null"
            return (
                "{\"schema\":\"kit.messaging.content.v1\",\"type\":\"text\"," +
                    "\"client_message_id\":\"${snapshot.clientMessageId}\"," +
                    "\"conversation_id\":\"${snapshot.conversationId}\"," +
                    "\"roster_revision\":\"${snapshot.rosterRevision}\"," +
                    "\"sender_user_id\":\"${snapshot.sender.userId}\"," +
                    "\"sender_device_id\":\"${snapshot.sender.serverDeviceId}\"," +
                    "\"sender_signal_device_id\":${snapshot.sender.signalDeviceId}," +
                    "\"reply_to_message_id\":$reply,\"text\":\"$authenticatedText\"}"
                ).toByteArray(StandardCharsets.UTF_8)
        }
    }

    private class TwoWriterProjectionRaceStateStore(
        private val delegate: TestSecureMessagingStateStore,
    ) : SecureMessagingStateStore by delegate {
        val interceptedWrites = AtomicInteger(0)
        private val bothWritersReady = CompletableDeferred<Unit>()

        @Volatile
        private var targetRecordKey: String? = null

        fun interceptNextTwoMetadataWrites(recordKey: String) {
            check(targetRecordKey == null)
            targetRecordKey = recordKey
        }

        override suspend fun write(
            namespace: String,
            recordKey: String,
            expectedVersion: Long?,
            bytes: ByteArray,
        ): SecureMessagingRecordVersion {
            if (
                namespace == "message-metadata-v1" &&
                recordKey == targetRecordKey &&
                expectedVersion != null &&
                interceptedWrites.get() < 2
            ) {
                if (interceptedWrites.incrementAndGet() == 2) bothWritersReady.complete(Unit)
                bothWritersReady.await()
            }
            return delegate.write(namespace, recordKey, expectedVersion, bytes)
        }
    }

    private class CommitThenConflictStateStore(
        private val delegate: TestSecureMessagingStateStore,
    ) : SecureMessagingStateStore by delegate {
        var injectedConflicts = 0
            private set

        override suspend fun write(
            namespace: String,
            recordKey: String,
            expectedVersion: Long?,
            bytes: ByteArray,
        ): SecureMessagingRecordVersion {
            val committed = delegate.write(namespace, recordKey, expectedVersion, bytes)
            if (namespace == "messaging-sync" && injectedConflicts == 0) {
                injectedConflicts++
                throw SecureMessagingStateConflictException("committed before injected conflict")
            }
            return committed
        }
    }

    private companion object {
        const val CURRENT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val PEER_USER_ID = "22222222-2222-4222-8222-222222222222"
        const val CURRENT_DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val PEER_DEVICE_ID = "55555555-5555-4555-8555-555555555555"
        const val CONVERSATION_ID = "66666666-6666-4666-8666-666666666666"
        const val INCOMING_CLIENT_ID = "77777777-7777-4777-8777-777777777777"
        const val OUTBOUND_CLIENT_ID = "88888888-8888-4888-8888-888888888888"
        const val SECOND_OUTBOUND_CLIENT_ID = "99999999-9999-4999-8999-999999999999"
        const val THIRD_OUTBOUND_CLIENT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"
        const val INCOMING_MESSAGE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OUTBOUND_SERVER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SECOND_OUTBOUND_SERVER_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val TIMESTAMP = "2026-07-20T12:00:00Z"
        val CURRENT = SecureMessagingCryptoAddress(CURRENT_USER_ID, CURRENT_DEVICE_ID, 1)
        val PEER = SecureMessagingCryptoAddress(PEER_USER_ID, PEER_DEVICE_ID, 2)
        val BINDING = SecureMessagingSessionBinding(
            sessionEpoch = "epoch-1",
            userId = CURRENT_USER_ID,
            serverDeviceId = CURRENT_DEVICE_ID,
            installationId = "installation-1",
        )
        const val READY_CAPABILITIES = """
            {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},
            "features":{"messaging":true},"authentication":{},"protocols":{"messaging":{
            "ready":true,"version":"v2","suite":"signal-pqxdh-kyber1024-double-ratchet-v2",
            "post_quantum":true}}}}
        """
        const val PROFILE = """
            {"ok":true,"data":{"id":"$CURRENT_USER_ID","name":"Kit User"}}
        """
        const val DEVICES = """
            {"ok":true,"data":{"items":[{"id":"$CURRENT_DEVICE_ID","name":"Android phone",
            "platform":"android","is_current":true,"created_at":"2026-07-20T08:00:00Z",
            "last_seen_at":"2026-07-20T08:01:00Z"}]}}
        """
        const val DIRECT_CONVERSATIONS = """
            {"ok":true,"data":{"items":[
            {"id":"$CONVERSATION_ID","type":"direct","title":null,"parent_id":null,
            "created_by":"$CURRENT_USER_ID","role":"owner","members":[
            {"user_id":"$CURRENT_USER_ID","name":"Current User","role":"owner",
            "joined_at":"$TIMESTAMP"},{"user_id":"$PEER_USER_ID","name":"Peer",
            "role":"member","joined_at":"$TIMESTAMP"}],"created_at":"$TIMESTAMP",
            "updated_at":"$TIMESTAMP"}]}}
        """
    }
}
