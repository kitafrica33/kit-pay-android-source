package com.kit.wallet

import com.kit.wallet.data.messaging.AccountArchivedMessage
import com.kit.wallet.data.messaging.AccountMessageHistoryAccess
import com.kit.wallet.data.messaging.CapturedAccountMessageHistory
import com.kit.wallet.data.messaging.FailClosedSecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.KitMediaMessage
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.MediaAttachmentCipher
import com.kit.wallet.data.messaging.OpaqueCryptoBytes
import com.kit.wallet.data.messaging.RealSecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingActivationCoordinator
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
import com.kit.wallet.data.messaging.SecureMessagingHistoryBackfillCodec
import com.kit.wallet.data.messaging.SecureMessagingHistoryContinuationScheduler
import com.kit.wallet.data.messaging.SecureMessagingKeyActivation
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGate
import com.kit.wallet.data.messaging.SecureMessagingNotificationPublicationException
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingProjectedMessage
import com.kit.wallet.data.messaging.SecureMessagingPreparedEnvelope
import com.kit.wallet.data.messaging.SecureMessagingPreparedFanout
import com.kit.wallet.data.messaging.SecureMessagingProvisioningPlan
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.messaging.SecureMessagingRecordPage
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingReauthenticationRequiredException
import com.kit.wallet.data.messaging.SecureMessagingRevalidationRetryException
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
import com.kit.wallet.data.messaging.SecureMessagingSyncCompletionSignal
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.SecureMessagingTextContentBinding
import com.kit.wallet.data.messaging.encodeSecureMessagingTextContent
import com.kit.wallet.data.messaging.requireSecureMessagingSyncResumePosition
import com.kit.wallet.data.repository.DefaultSecureMessagingChatRuntime
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.ENCRYPTED_ATTACHMENT_MESSAGE_KIND
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedAttachmentDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageCryptoSenderDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessageDeliveryAcknowledgementDto
import com.kit.wallet.data.remote.MessageDeliveryReceiptDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingHistoryBackfillCandidatesDto
import com.kit.wallet.data.remote.MessagingHistoryTargetCryptoBundleDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.MessagingSyncDto
import com.kit.wallet.data.remote.MessagingSyncEventDataDto
import com.kit.wallet.data.remote.MessagingSyncEventDto
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `sync completion waits for its exact exposed active identity`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        val active = registry.publish(session, fence)
        val stateStore = TestSecureMessagingStateStore()
        val completions = SecureMessagingSyncCompletionSignal()
        val runtime = DefaultSecureMessagingChatRuntime(
            sessions = registry,
            authenticationSessions = MutableTestSessionStore(authenticatedSession()),
            engine = PersistingDecryptionEngine(stateStore),
            projections = projectionStore(stateStore),
            syncEngine = object : SecureMessagingSyncEngine {
                override val isReady = true
                override suspend fun synchronize() = Unit
            },
            scope = backgroundScope,
            clock = Clock.fixed(Instant.parse(TIMESTAMP), ZoneOffset.UTC),
            syncCompletions = completions,
        )
        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.baselineRetrySessions.first()
        }

        completions.completed(active)
        assertFalse(retry.isCompleted)
        runCurrent()

        val exposed = retry.await()
        assertTrue(runtime.isCurrent(exposed))
        assertEquals(BINDING.sessionEpoch, exposed.sessionEpoch)
    }

    @Test
    fun `runtime reports exact durable client ID before transport and never before commit`() = runTest {
        val (transportSession, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        registry.publish(transportSession, fence)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val runtime = DefaultSecureMessagingChatRuntime(
            sessions = registry,
            authenticationSessions = MutableTestSessionStore(authenticatedSession()),
            engine = OutboundCommitTestEngine(
                stateStore = stateStore,
                authenticatedText = "operation-owned callback",
                failuresBeforeCommit = 1,
            ),
            projections = projections,
            syncEngine = object : SecureMessagingSyncEngine {
                override val isReady = true
                override suspend fun synchronize() = Unit
            },
            scope = backgroundScope,
            clock = Clock.fixed(Instant.parse(TIMESTAMP), ZoneOffset.UTC),
        )
        runCurrent()
        val active = checkNotNull(runtime.activeSession.value)
        val roster = authoritativeRoster()

        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        val preCommitCallbacks = mutableListOf<String>()
        val preCommitFailure = runCatching {
            runtime.sendText(
                session = active,
                conversationId = CONVERSATION_ID,
                text = "operation-owned callback",
                onDurablyCommitted = preCommitCallbacks::add,
            )
        }.exceptionOrNull()

        assertTrue(preCommitFailure is SecureMessagingStateConflictException)
        assertTrue(preCommitCallbacks.isEmpty())
        assertTrue(projections.readPage(limit = 10).messages().isEmpty())

        enqueueRoster(roster)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val committedClientId = CompletableDeferred<String>()
        val send = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.sendText(
                session = active,
                conversationId = CONVERSATION_ID,
                text = "operation-owned callback",
                onDurablyCommitted = { committedClientId.complete(it) },
            )
        }

        val callbackId = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { committedClientId.await() }
        }
        val pending = projections.readPage(limit = 10).messages().single()
        assertEquals(pending.durableRecord.clientMessageId, callbackId)
        assertEquals(SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING, pending.deliveryState)
        assertFalse(send.isCompleted)

        send.cancelAndJoin()
    }

    @Test
    fun `quarantined failed recovery retries immediately under the same authentication fence`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            lifecycle.finishActivation(fence)
            val registry = SecureMessagingActiveSessionRegistry(lifecycle)
            registry.publish(session, fence)
            val authentication = MutableTestSessionStore(authenticatedSession())
            val stateStore = TestSecureMessagingStateStore()
            var continuationCalls = 0
            val continuationFences = mutableListOf<SessionFence>()
            val sync = object : SecureMessagingSyncEngine {
                override val isReady = true
                override suspend fun synchronize() = Unit

                override suspend fun synchronize(expectedSession: SessionFence) {
                    continuationFences += expectedSession
                    continuationCalls++
                    if (continuationCalls == 1) {
                        throw IOException("provider is still opening")
                    }
                }

                override suspend fun recoverPermanentlyUnavailableState(
                    expectedActivation: SecureMessagingSessionFence,
                ) {
                    session.quarantine(
                        SecureMessagingCryptographicFailureException(
                            quarantineReason = SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                            message = "proved local record key loss",
                        ),
                    )
                    throw IOException("recovery transport unavailable")
                }
            }
            val runtime = DefaultSecureMessagingChatRuntime(
                sessions = registry,
                authenticationSessions = authentication,
                engine = PersistingDecryptionEngine(stateStore),
                projections = projectionStore(stateStore),
                syncEngine = sync,
                scope = backgroundScope,
                clock = Clock.fixed(Instant.parse(TIMESTAMP), ZoneOffset.UTC),
            )
            runCurrent()

            assertFalse(
                runtime.recoverPermanentlyUnavailableState(
                    IOException("temporary baseline provider failure"),
                ),
            )
            assertEquals(0, continuationCalls)

            val failure = runCatching {
                runtime.recoverPermanentlyUnavailableState(
                    IOException(
                        "projection baseline key is permanently unavailable",
                        SecureMessagingRecordKeyPermanentlyMissingException(),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            runCurrent()

            val expected = checkNotNull(authentication.current()).fence()
            assertEquals(1, continuationCalls)
            assertEquals(listOf(expected), continuationFences)

            advanceTimeBy(4_999L)
            runCurrent()
            assertEquals(1, continuationCalls)
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(2, continuationCalls)
            assertEquals(listOf(expected, expected), continuationFences)
        }

    @Test
    fun `authentication replacement cancels quarantined recovery continuation`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        lifecycle.finishActivation(fence)
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        registry.publish(session, fence)
        val authentication = MutableTestSessionStore(authenticatedSession())
        val stateStore = TestSecureMessagingStateStore()
        var continuationCalls = 0
        val sync = object : SecureMessagingSyncEngine {
            override val isReady = true
            override suspend fun synchronize() = Unit

            override suspend fun synchronize(expectedSession: SessionFence) {
                continuationCalls++
                throw IOException("provider is still unavailable")
            }

            override suspend fun recoverPermanentlyUnavailableState(
                expectedActivation: SecureMessagingSessionFence,
            ) {
                session.quarantine(
                    SecureMessagingCryptographicFailureException(
                        quarantineReason = SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                        message = "proved local record key loss",
                    ),
                )
                throw IOException("recovery transport unavailable")
            }
        }
        val runtime = DefaultSecureMessagingChatRuntime(
            sessions = registry,
            authenticationSessions = authentication,
            engine = PersistingDecryptionEngine(stateStore),
            projections = projectionStore(stateStore),
            syncEngine = sync,
            scope = backgroundScope,
            clock = Clock.fixed(Instant.parse(TIMESTAMP), ZoneOffset.UTC),
        )
        runCurrent()

        val failure = runCatching {
            runtime.recoverPermanentlyUnavailableState(
                IOException(
                    "projection baseline key is permanently unavailable",
                    SecureMessagingRecordKeyPermanentlyMissingException(),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        runCurrent()
        assertEquals(1, continuationCalls)

        authentication.replace(authenticatedSession(sessionId = "replacement-epoch"))
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(1, continuationCalls)
    }

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
    fun `ciphertext history wrapper materializes only the donor authenticated original`() = runTest {
        val (session, _, _) = openSyncingSession()
        recordCurrentIdentity(session)
        val roster = historyTransferRoster()
        val transferId = SecureMessagingHistoryBackfillCodec.deterministicTransferId(
            messageId = INCOMING_MESSAGE_ID,
            targetDeviceId = CURRENT_DEVICE_ID,
            targetEnrollmentEpoch = 1,
            donorDeviceId = OWN_DONOR_DEVICE_ID,
            donorEnrollmentEpoch = 5,
            transferRosterRevision = checkNotNull(roster.rosterRevision),
        )
        val descriptor = historyDescriptor(transferId, checkNotNull(roster.rosterRevision))
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(stateStore, authenticatedText = descriptor),
            projections,
        )
        enqueueSync(
            events = listOf(historyIncomingEvent(roster, transferId)),
            nextCursor = "history_sync_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()

        processor.synchronize(session)

        val recovered = checkNotNull(projections.readInbound(INCOMING_MESSAGE_ID))
        assertEquals("restored only from authenticated ciphertext", recovered.authenticatedText)
        assertEquals(PEER, recovered.sender)
        assertEquals(ORIGINAL_HISTORY_ROSTER, recovered.rosterRevision)
        assertNotNull(projections.readHistoryInbound(transferId))
        val visible = projections.readPage(limit = 10).messages()
        assertEquals(listOf(INCOMING_MESSAGE_ID), visible.map { it.durableRecord.messageId })
        assertTrue(server.takeRequest().path?.contains("/messaging/sync") == true)
        assertEquals("/api/kit-wallet/v1/messaging/conversations", server.takeRequest().path)
        assertTrue(server.takeRequest().path?.contains("/device-roster/v1:sha256:") == true)
        val acknowledgement = server.takeRequest()
        assertEquals(
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
            acknowledgement.path,
        )
        assertTrue(acknowledgement.body.readUtf8().contains(INCOMING_MESSAGE_ID))
    }

    @Test
    fun `history wrapper reuses self-authored outbound projection without inbound duplicate`() =
        runTest {
            val (session, _, _) = openSyncingSession()
            recordCurrentIdentity(session)
            val roster = historyTransferRoster()
            val transferId = SecureMessagingHistoryBackfillCodec.deterministicTransferId(
                messageId = OUTBOUND_SERVER_ID,
                targetDeviceId = CURRENT_DEVICE_ID,
                targetEnrollmentEpoch = 1,
                donorDeviceId = OWN_DONOR_DEVICE_ID,
                donorEnrollmentEpoch = 5,
                transferRosterRevision = checkNotNull(roster.rosterRevision),
            )
            val descriptor = outboundHistoryDescriptor(
                transferId = transferId,
                rosterRevision = checkNotNull(roster.rosterRevision),
            )
            val stateStore = TestSecureMessagingStateStore()
            val projections = projectionStore(stateStore)
            val outbound = stateStore.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
                direction = LibSignalCompanionDirection.OUTBOUND,
                messageId = OUTBOUND_CLIENT_ID,
                clientMessageId = OUTBOUND_CLIENT_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = checkNotNull(roster.rosterRevision),
                sender = CURRENT,
                text = SELF_AUTHORED_HISTORY_TEXT,
                envelopes = listOf(
                    PersistedCompanionEnvelopeFixture(
                        PEER,
                        SecureMessagingEnvelopeKind.SESSION,
                        byteArrayOf(7, 8, 9),
                    ),
                ),
            )
            projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
            val continuationDelays = mutableListOf<Long>()
            val processor = processor(
                stateStore = stateStore,
                crypto = PersistingDecryptionEngine(stateStore, authenticatedText = descriptor),
                projections = projections,
                historyContinuationScheduler = SecureMessagingHistoryContinuationScheduler(
                    continuationDelays::add,
                ),
            )
            enqueueSync(
                events = listOf(
                    outboundEvent(roster, eventId = 10),
                    outboundHistoryIncomingEvent(roster, transferId, eventId = 11),
                ),
                nextCursor = "outbound_history_collision_cursor",
            )
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            enqueueRoster(roster)
            enqueueDeliveryAcknowledgement(OUTBOUND_SERVER_ID)

            processor.synchronize(session)

            assertNull(projections.readInbound(OUTBOUND_SERVER_ID))
            assertNotNull(projections.readHistoryInbound(transferId))
            val visible = projections.readPage(limit = 10).messages().single()
            assertEquals(LibSignalCompanionDirection.OUTBOUND, visible.durableRecord.direction)
            assertEquals(OUTBOUND_CLIENT_ID, visible.durableRecord.clientMessageId)
            assertEquals(OUTBOUND_SERVER_ID, visible.serverMessageId)
            assertEquals(
                SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
                visible.deliveryState,
            )
            assertTrue(continuationDelays.isEmpty())
            assertTrue(server.takeRequest().path?.contains("/messaging/sync") == true)
            assertEquals(
                "/api/kit-wallet/v1/messaging/conversations",
                server.takeRequest().path,
            )
            assertTrue(server.takeRequest().path?.contains("/device-roster/v1:sha256:") == true)
            val acknowledgement = server.takeRequest()
            assertEquals(
                "/api/kit-wallet/v1/messaging/messages/delivery-acks",
                acknowledgement.path,
            )
            assertTrue(acknowledgement.body.readUtf8().contains(OUTBOUND_SERVER_ID))
        }

    @Test
    fun `retained inbound history replay completes pending notification publication`() = runTest {
        val (session, _, _) = openSyncingSession()
        recordCurrentIdentity(session)
        val roster = historyTransferRoster()
        val transferId = SecureMessagingHistoryBackfillCodec.deterministicTransferId(
            messageId = INCOMING_MESSAGE_ID,
            targetDeviceId = CURRENT_DEVICE_ID,
            targetEnrollmentEpoch = 1,
            donorDeviceId = OWN_DONOR_DEVICE_ID,
            donorEnrollmentEpoch = 5,
            transferRosterRevision = checkNotNull(roster.rosterRevision),
        )
        val descriptor = historyDescriptor(transferId, checkNotNull(roster.rosterRevision))
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val notifications = FailOnceIdempotentNotificationSink(failAfterPublish = false)
        val crypto = PersistingDecryptionEngine(stateStore, authenticatedText = descriptor)
        val processor = processor(
            stateStore = stateStore,
            crypto = crypto,
            projections = projections,
            notifications = notifications,
        )
        enqueueSync(
            events = listOf(historyIncomingEvent(roster, transferId)),
            nextCursor = "history_notification_retry_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)

        val failure = runCatching { processor.synchronize(session) }.exceptionOrNull()

        assertTrue(failure is SecureMessagingNotificationPublicationException)
        assertEquals("injected notification failure", failure?.cause?.message)
        assertEquals(1, crypto.openedTransactions)
        assertNotNull(projections.readInbound(INCOMING_MESSAGE_ID))
        assertNull(SecureMessagingSyncCursorStore(stateStore).load())
        assertEquals(1, notifications.publishAttempts)
        assertTrue(notifications.visibleNotifications.isEmpty())
        assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations",
            server.takeRequest().path,
        )
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
        assertEquals(
            listOf(INCOMING_MESSAGE_ID),
            projections.readPage(limit = 10).messages().map { it.serverMessageId },
        )
        assertEquals(
            "history_notification_retry_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
    }

    @Test
    fun `newly recovered history reopens completed propagation work exactly once`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        recordCurrentIdentity(session)
        lifecycle.finishActivation(fence)
        val roster = historyTransferRoster()
        val transferId = SecureMessagingHistoryBackfillCodec.deterministicTransferId(
            messageId = INCOMING_MESSAGE_ID,
            targetDeviceId = CURRENT_DEVICE_ID,
            targetEnrollmentEpoch = 1,
            donorDeviceId = OWN_DONOR_DEVICE_ID,
            donorEnrollmentEpoch = 5,
            transferRosterRevision = checkNotNull(roster.rosterRevision),
        )
        val descriptor = historyDescriptor(transferId, checkNotNull(roster.rosterRevision))
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val continuationDelays = mutableListOf<Long>()
        val processor = processor(
            stateStore = stateStore,
            crypto = PersistingDecryptionEngine(stateStore, authenticatedText = descriptor),
            projections = projections,
            historyContinuationScheduler = SecureMessagingHistoryContinuationScheduler(
                continuationDelays::add,
            ),
        )

        // Establish the one-shot activation reconciliation and complete its currently empty task.
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueEmptyHistoryCandidates(roster, OWN_DONOR_DEVICE_ID, 5)
        processor.recoverPendingHistory(session)
        assertTrue(projections.pendingHistoryBackfills(limit = 1).isEmpty())
        assertTrue(continuationDelays.isEmpty())
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations",
            server.takeRequest().path,
        )
        assertTrue(server.takeRequest().path?.endsWith("/device-roster") == true)
        assertTrue(server.takeRequest().path?.contains("/history-backfill/candidates") == true)

        // This authenticated wrapper is a new retained source. It must invalidate the completed
        // reconciliation and persist an immediate continuation without waiting for another login.
        enqueueSync(
            events = listOf(historyIncomingEvent(roster, transferId)),
            nextCursor = "history_propagation_cursor",
        )
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueDeliveryAcknowledgement()
        processor.synchronize(session)
        assertEquals(listOf(0L), continuationDelays)
        assertTrue(server.takeRequest().path?.contains("/messaging/sync") == true)
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations",
            server.takeRequest().path,
        )
        assertTrue(server.takeRequest().path?.contains("/device-roster/v1:sha256:") == true)
        assertEquals(
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
            server.takeRequest().path,
        )

        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueEmptyHistoryCandidates(roster, OWN_DONOR_DEVICE_ID, 5)
        processor.recoverPendingHistory(session)
        assertTrue(projections.pendingHistoryBackfills(limit = 1).isEmpty())
        assertEquals(listOf(0L), continuationDelays)
        assertEquals(
            "/api/kit-wallet/v1/messaging/conversations",
            server.takeRequest().path,
        )
        assertTrue(server.takeRequest().path?.endsWith("/device-roster") == true)
        assertTrue(server.takeRequest().path?.contains("/history-backfill/candidates") == true)

        // An exact event replay finds the retained source and must not create an endless chain of
        // zero-delay propagation continuations.
        enqueueSync(
            events = listOf(historyIncomingEvent(roster, transferId, eventId = 11)),
            nextCursor = "history_propagation_replay_cursor",
        )
        enqueueDeliveryAcknowledgement()
        processor.synchronize(session)
        assertEquals(listOf(0L), continuationDelays)
        assertTrue(server.takeRequest().path?.contains("/messaging/sync") == true)
        assertEquals(
            "/api/kit-wallet/v1/messaging/messages/delivery-acks",
            server.takeRequest().path,
        )
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
        val projections = projectionStore(stateStore)
        val notifications = FailOnceIdempotentNotificationSink(failAfterPublish = false)
        val processor = processor(
            stateStore = stateStore,
            crypto = crypto,
            projections = projections,
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
        val firstProjectionRevision = projections.changes.value
        assertTrue(firstProjectionRevision > 0L)
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
        assertTrue(projections.changes.value > firstProjectionRevision)
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
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            enqueueDeliveryAcknowledgement()
            processor(
                stateStore = stateStore,
                crypto = crypto,
                notifications = notifications,
            ).synchronize(restartedSession)

            assertEquals("/api/kit-wallet/v1/messaging/sync?limit=50", server.takeRequest().path)
            assertEquals(
                "/api/kit-wallet/v1/messaging/conversations",
                server.takeRequest().path,
            )
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
    fun `legacy projected inbound replay repairs UI signal without duplicate notification`() =
        runTest {
            val (session, _, _) = openSyncingSession()
            val stateStore = TestSecureMessagingStateStore()
            val projections = projectionStore(stateStore)
            val roster = authoritativeRoster()
            val durable = stateStore.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.inboundRecordKey(INCOMING_MESSAGE_ID),
                direction = LibSignalCompanionDirection.INBOUND,
                messageId = INCOMING_MESSAGE_ID,
                clientMessageId = INCOMING_CLIENT_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = checkNotNull(roster.rosterRevision),
                sender = PEER,
                text = "legacy projected plaintext",
            )
            // Code 22 and earlier used projection metadata itself as the notification marker.
            // This creates that upgrade state: accepted projection, no new publication marker.
            projections.recordInbound(durable, Instant.parse(TIMESTAMP))
            val revisionBeforeReplay = projections.changes.value
            val notifications = mutableListOf<SecureMessagingIncomingNotification>()
            val crypto = PersistingDecryptionEngine(stateStore)
            val processor = processor(
                stateStore = stateStore,
                crypto = crypto,
                projections = projections,
                notifications = SecureMessagingIncomingNotificationSink(notifications::add),
            )
            enqueueSync(listOf(incomingEvent(roster, 10)), "legacy_projection_cursor")
            enqueueDeliveryAcknowledgement()

            processor.synchronize(session)

            assertEquals(0, crypto.openedTransactions)
            assertTrue(notifications.isEmpty())
            assertTrue(projections.changes.value > revisionBeforeReplay)
            assertEquals(
                INCOMING_MESSAGE_ID,
                projections.readPage(limit = 10).messages().single().serverMessageId,
            )
            assertEquals(
                "legacy_projection_cursor" to 10L,
                requireSecureMessagingSyncResumePosition(
                    checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
                ),
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
    fun `stale event processor cannot commit projection metadata into replacement activation`() =
        runTest {
            val (session, lifecycle, _) = openSyncingSession()
            val delegate = TestSecureMessagingStateStore()
            val stateStore = LifecycleLeasedStateStore(delegate)
            stateStore.allowForActiveSession()
            val projections = projectionStore(stateStore)
            val roster = authoritativeRoster()
            val outbound = delegate.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.outboundRecordKey(OUTBOUND_CLIENT_ID),
                direction = LibSignalCompanionDirection.OUTBOUND,
                messageId = OUTBOUND_CLIENT_ID,
                clientMessageId = OUTBOUND_CLIENT_ID,
                conversationId = CONVERSATION_ID,
                rosterRevision = checkNotNull(roster.rosterRevision),
                sender = CURRENT,
                text = "stale outbound",
                envelopes = listOf(
                    PersistedCompanionEnvelopeFixture(
                        recipient = PEER,
                        kind = SecureMessagingEnvelopeKind.SESSION,
                        ciphertext = byteArrayOf(1, 2, 3),
                    ),
                ),
            )
            projections.recordOutboundPending(outbound, Instant.parse(TIMESTAMP))
            val responseRelease = CountDownLatch(1)
            val requestEntered = CountDownLatch(1)
            server.dispatcher = blockingResponseDispatcher(
                syncResponse(
                    events = listOf(outboundEvent(roster, eventId = 10)),
                    nextCursor = "stale_projection_cursor",
                ),
                requestEntered,
                responseRelease,
            )
            val processor = processor(
                stateStore,
                PersistingDecryptionEngine(delegate),
                projections,
            )
            val staleSync = async(Dispatchers.IO) {
                runCatching { processor.synchronize(session) }.exceptionOrNull()
            }

            assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
            val leaseBlock = stateStore.blockBeforeNextActivationLease()
            responseRelease.countDown()
            leaseBlock.awaitEntered()
            try {
                replaceWithFreshActivation(lifecycle, stateStore, "projection-replacement")
            } finally {
                leaseBlock.release()
            }

            assertTrue(staleSync.await() is IllegalStateException)
            assertTrue(projections.readPage(limit = 10).messages().isEmpty())
            assertNull(SecureMessagingSyncCursorStore(stateStore).load())
        }

    @Test
    fun `stale event processor cannot commit cursor into replacement activation`() = runTest {
        val (session, lifecycle, _) = openSyncingSession()
        val delegate = TestSecureMessagingStateStore()
        val stateStore = LifecycleLeasedStateStore(delegate)
        stateStore.allowForActiveSession()
        val responseRelease = CountDownLatch(1)
        val requestEntered = CountDownLatch(1)
        server.dispatcher = blockingResponseDispatcher(
            syncResponse(events = emptyList(), nextCursor = "stale_cursor"),
            requestEntered,
            responseRelease,
        )
        val processor = processor(stateStore, PersistingDecryptionEngine(delegate))
        val staleSync = async(Dispatchers.IO) {
            runCatching { processor.synchronize(session) }.exceptionOrNull()
        }

        assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
        val leaseBlock = stateStore.blockBeforeNextActivationLease()
        responseRelease.countDown()
        leaseBlock.awaitEntered()
        try {
            replaceWithFreshActivation(lifecycle, stateStore, "cursor-replacement")
        } finally {
            leaseBlock.release()
        }

        assertTrue(staleSync.await() is IllegalStateException)
        assertNull(SecureMessagingSyncCursorStore(stateStore).load())
    }

    @Test
    fun `authenticated durable plaintext drives the local notification sink`() = runTest {
        val (session, _, _) = openSyncingSession()
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val notifications = mutableListOf<SecureMessagingIncomingNotification>()
        val projectionRevisionsAtPublish = mutableListOf<Long>()
        val processor = SecureMessagingEventProcessor(
            PersistingDecryptionEngine(stateStore),
            projections,
            SecureMessagingSyncCursorStore(stateStore),
            SecureMessagingIncomingNotificationSink { notification ->
                projectionRevisionsAtPublish += projections.changes.value
                notifications += notification
            },
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
        assertEquals("Peer", notifications.single().senderName)
        assertEquals("processor plaintext", notifications.single().authenticatedText)
        assertEquals(Instant.parse(TIMESTAMP), notifications.single().sentAt)
        assertTrue(projectionRevisionsAtPublish.single() > 0L)
        assertEquals(
            INCOMING_MESSAGE_ID,
            projections.readPage(limit = 10).messages().single().serverMessageId,
        )
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
    fun `current device revocation requires pinned reset without direct erasure`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            recordCurrentIdentity(session)
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
            server.enqueue(unenrolledKeyStatusResponse())

            val failure = runCatching { processor.synchronize(session) }.exceptionOrNull()

            assertTrue(failure is SecureMessagingReauthenticationRequiredException)
            val cryptographicFailure = failure?.cause as? SecureMessagingCryptographicFailureException
            assertEquals(
                SecureMessagingQuarantineReason.CURRENT_DEVICE_REVOKED,
                cryptographicFailure?.quarantineReason,
            )
            assertEquals(SecureMessagingRuntimeStage.QUARANTINED, lifecycle.snapshot().stage)
            assertEquals(1, notifications.cancellations)
            assertEquals(0, erasures)
            assertTrue(crypto.retiredDevices.isEmpty())
            assertNull(SecureMessagingSyncCursorStore(stateStore).load())
        }

    @Test
    fun `historical self event revalidates in place during initial activation`() = runTest {
        val lifecycle = SecureMessagingLifecycleGuard()
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        val stateStore = TestSecureMessagingStateStore()
        val processor = processor(
            stateStore = stateStore,
            crypto = PersistingDecryptionEngine(stateStore),
        )
        val coordinator = SecureMessagingActivationCoordinator(
            transport = transport,
            lifecycle = lifecycle,
            sessions = registry,
            keyActivation = SecureMessagingKeyActivation(::recordCurrentIdentity),
            initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
        )
        enqueueActivation()
        enqueueSync(
            listOf(
                deviceLifecycleEvent(
                    eventType = "device.revoked",
                    userId = CURRENT.userId,
                    deviceId = CURRENT.serverDeviceId,
                ),
            ),
            "initial_historical_self_cursor",
        )
        enqueueCurrentKeyStatus()

        val active = coordinator.ensureActivated(BINDING)

        assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
        assertEquals(BINDING, active.binding)
        assertNotNull(registry.currentOrNull())
        assertEquals(
            "initial_historical_self_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
    }

    @Test
    fun `historical current-device revocation is ignored after fresh identity reconciliation`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            recordCurrentIdentity(session)
            lifecycle.finishActivation(fence)
            val registry = SecureMessagingActiveSessionRegistry(lifecycle)
            registry.publish(session, fence)
            val stateStore = TestSecureMessagingStateStore()
            var erasures = 0
            val processor = processor(
                stateStore = stateStore,
                crypto = PersistingDecryptionEngine(stateStore),
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
                "historical_revocation_cursor",
            )
            enqueueCurrentKeyStatus()

            processor.synchronize(session)

            assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
            assertNotNull(registry.currentOrNull())
            assertEquals(0, erasures)
            assertEquals(
                "historical_revocation_cursor" to 10L,
                requireSecureMessagingSyncResumePosition(
                    checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
                ),
            )
        }

    @Test
    fun `self lifecycle revalidation withdraws readiness until exact status matches`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        recordCurrentIdentity(session)
        lifecycle.finishActivation(fence)
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        registry.publish(session, fence)
        val stateStore = TestSecureMessagingStateStore()
        val processor = processor(stateStore, PersistingDecryptionEngine(stateStore))
        val statusEntered = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val syncResponse = syncResponse(
            listOf(
                deviceLifecycleEvent(
                    eventType = "device.revoked",
                    userId = CURRENT.userId,
                    deviceId = CURRENT.serverDeviceId,
                ),
            ),
            "suspended_revalidation_cursor",
        )
        val keyStatusResponse = currentKeyStatusResponse()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/kit-wallet/v1/messaging/sync") == true -> syncResponse
                request.path == "/api/kit-wallet/v1/messaging/keys/status" -> {
                    statusEntered.countDown()
                    check(releaseStatus.await(5, TimeUnit.SECONDS))
                    keyStatusResponse
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val synchronization = backgroundScope.async(Dispatchers.IO) {
            processor.synchronize(session)
        }
        try {
            assertTrue(statusEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                SecureMessagingRuntimeStage.PREPARING_KEYS,
                lifecycle.snapshot().stage,
            )
            assertNull(registry.currentOrNull())
        } finally {
            releaseStatus.countDown()
        }
        synchronization.await()

        assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
        assertNotNull(registry.currentOrNull())
    }

    @Test
    fun `unresolved self lifecycle status fails closed for server and transport failures`() =
        runTest {
            assertSelfLifecycleRevalidationRetries(
                eventType = "device.revoked",
                statusResponse = MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"ok":false,"error":{"code":"TEMPORARY_FAILURE","message":"retry"}}""",
                    ),
            )
            assertSelfLifecycleRevalidationRetries(
                eventType = "identity.changed",
                statusResponse = currentKeyStatusResponse().setSocketPolicy(
                    SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY,
                ),
            )
        }

    @Test
    fun `authoritative mismatch rejects current device revocation`() = runTest {
        assertSelfLifecycleEventRejected(
            eventType = "device.revoked",
            statusResponse = currentKeyStatusResponse(identityKeySha256 = "9".repeat(64)),
            expectedReason = SecureMessagingQuarantineReason.CURRENT_DEVICE_REVOKED,
        )
    }

    @Test
    fun `authoritative unenrollment rejects current identity change`() = runTest {
        assertSelfLifecycleEventRejected(
            eventType = "identity.changed",
            statusResponse = unenrolledKeyStatusResponse(),
            expectedReason = SecureMessagingQuarantineReason.IDENTITY_CHANGED,
        )
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
    fun `history transfer failure remains durable without holding current sync`() = runTest {
        val (session, lifecycle, fence) = openSyncingSession()
        recordCurrentIdentity(session)
        val stateStore = TestSecureMessagingStateStore()
        val projections = projectionStore(stateStore)
        val processor = processor(
            stateStore,
            PersistingDecryptionEngine(stateStore),
            projections,
        )
        enqueueSync(
            listOf(
                deviceLifecycleEvent(
                    eventType = "device.enrolled",
                    userId = CURRENT_USER_ID,
                    deviceId = OWN_DONOR_DEVICE_ID,
                ),
            ),
            "history_job_cursor",
        )

        processor.synchronize(session)

        assertEquals(
            "history_job_cursor" to 10L,
            requireSecureMessagingSyncResumePosition(
                checkNotNull(SecureMessagingSyncCursorStore(stateStore).load()).position,
            ),
        )
        assertEquals(1, projections.pendingHistoryBackfills(limit = 4).size)

        lifecycle.finishActivation(fence)
        val roster = historyTransferRoster(donorEnrollmentEpoch = 2)
        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        server.enqueue(apiErrorResponse(503, "TEMPORARY_FAILURE"))
        processor.recoverPendingHistory(session)
        assertEquals(1, projections.pendingHistoryBackfills(limit = 4).size)

        server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
        enqueueRoster(roster)
        enqueueEmptyHistoryCandidates(
            roster = roster,
            targetDeviceId = OWN_DONOR_DEVICE_ID,
            targetEnrollmentEpoch = 2,
        )
        processor.recoverPendingHistory(session)
        assertTrue(projections.pendingHistoryBackfills(limit = 4).isEmpty())
    }

    @Test
    fun `current roster creates history work after an old client already consumed enrollment`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            recordCurrentIdentity(session)
            lifecycle.finishActivation(fence)
            val stateStore = TestSecureMessagingStateStore()
            val projections = projectionStore(stateStore)
            val continuationDelays = mutableListOf<Long>()
            val processor = processor(
                stateStore = stateStore,
                crypto = PersistingDecryptionEngine(stateStore),
                projections = projections,
                historyContinuationScheduler = SecureMessagingHistoryContinuationScheduler(
                    continuationDelays::add,
                ),
            )
            val roster = historyTransferRoster()
            server.enqueue(jsonResponse(DIRECT_CONVERSATIONS))
            enqueueRoster(roster)
            server.enqueue(apiErrorResponse(503, "TEMPORARY_FAILURE"))

            // No sync event is supplied: this is the upgrade case after the old client persisted
            // a cursor beyond device.enrolled.
            processor.recoverPendingHistory(session)

            val task = projections.pendingHistoryBackfills(limit = 1).single()
            assertEquals(CONVERSATION_ID, task.conversationId)
            assertEquals(OWN_DONOR_DEVICE_ID, task.targetDeviceId)
            assertEquals(5L, task.targetEnrollmentEpoch)
            assertEquals(listOf(30_000L), continuationDelays)
            assertEquals(
                "/api/kit-wallet/v1/messaging/conversations",
                server.takeRequest().path,
            )
            assertTrue(server.takeRequest().path?.endsWith("/device-roster") == true)
            val candidates = server.takeRequest().path.orEmpty()
            assertTrue(candidates.contains("/history-backfill/candidates"))
            assertTrue(candidates.contains("target_enrollment_epoch=5"))
        }

    @Test
    fun `archive restoration precedes roster reconciliation and reopens a completed task`() =
        runTest {
            val (session, lifecycle, fence) = openSyncingSession()
            recordCurrentIdentity(session)
            lifecycle.finishActivation(fence)
            val archiveRestored = AtomicBoolean(false)
            val rosterObservedAfterRestore = AtomicBoolean(false)
            val historyArchive = object : AccountMessageHistoryAccess {
                override fun capture(
                    expectedOwnerAccountId: String,
                    expectedSessionFence: SessionFence?,
                ): CapturedAccountMessageHistory = object : CapturedAccountMessageHistory {
                    override suspend fun archive(projected: SecureMessagingProjectedMessage) = Unit

                    override suspend fun readAll(): List<AccountArchivedMessage> {
                        archiveRestored.set(true)
                        return emptyList()
                    }

                    override suspend fun readAllAndMaterialize(
                        materialize: suspend (List<AccountArchivedMessage>) -> Unit,
                    ) {
                        archiveRestored.set(true)
                        materialize(emptyList())
                    }
                }
            }
            val stateStore = TestSecureMessagingStateStore()
            val projections = SecureMessagingProjectionStore(
                stateStore,
                LibSignalCompanionStateReader(stateStore),
                historyArchive,
            )
            projections.enqueueHistoryBackfill(CONVERSATION_ID, OWN_DONOR_DEVICE_ID, 5)
            projections.updateHistoryBackfill(
                projections.pendingHistoryBackfills(limit = 1).single(),
                nextCursor = null,
                completed = true,
            )
            assertTrue(projections.pendingHistoryBackfills(limit = 1).isEmpty())
            val processor = processor(
                stateStore,
                PersistingDecryptionEngine(stateStore),
                projections,
            )
            val roster = historyTransferRoster()
            val encodedRoster = moshi.adapter(MessagingDeviceRosterDto::class.java).toJson(roster)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/kit-wallet/v1/messaging/conversations" ->
                        jsonResponse(DIRECT_CONVERSATIONS)
                    request.path?.endsWith("/device-roster") == true -> {
                        rosterObservedAfterRestore.set(archiveRestored.get())
                        jsonResponse("""{"ok":true,"data":$encodedRoster}""")
                    }
                    request.path?.contains("/history-backfill/candidates") == true ->
                        apiErrorResponse(503, "TEMPORARY_FAILURE")
                    else -> MockResponse().setResponseCode(404)
                }
            }

            processor.recoverPendingHistory(session)

            assertTrue(archiveRestored.get())
            assertTrue(rosterObservedAfterRestore.get())
            val reopened = projections.pendingHistoryBackfills(limit = 1).single()
            assertEquals(OWN_DONOR_DEVICE_ID, reopened.targetDeviceId)
            assertEquals(5L, reopened.targetEnrollmentEpoch)
            assertEquals(null, reopened.nextCursor)
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
            authenticationSessions = MutableTestSessionStore(authenticatedSession()),
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
        runCurrent()
        val activeChatSession = checkNotNull(runtime.activeSession.value)

        runtime.sendText(
            activeChatSession,
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

    private fun authenticatedSession(sessionId: String = BINDING.sessionEpoch) = SessionTokens(
        accessToken = "test-access",
        refreshToken = "test-refresh",
        sessionId = sessionId,
        cacheScopeId = "scope:$sessionId",
        accountId = CURRENT_USER_ID,
    )

    private fun blockingResponseDispatcher(
        response: MockResponse,
        requestEntered: CountDownLatch,
        responseRelease: CountDownLatch,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requestEntered.countDown()
            return if (responseRelease.await(5, TimeUnit.SECONDS)) {
                response
            } else {
                MockResponse().setResponseCode(503)
            }
        }
    }

    private suspend fun replaceWithFreshActivation(
        lifecycle: SecureMessagingLifecycleGuard,
        stateStore: LifecycleLeasedStateStore,
        sessionEpoch: String,
    ) {
        lifecycle.beginErasure()
        stateStore.eraseAll()
        lifecycle.finishErasure()
        lifecycle.beginSession(
            BINDING.copy(
                sessionEpoch = sessionEpoch,
                installationId = "$sessionEpoch-installation",
            ),
        )
        stateStore.allowForActiveSession()
    }

    private fun processor(
        stateStore: SecureMessagingStateStore,
        crypto: SecureMessagingCryptoEngine,
        projections: SecureMessagingProjectionStore = projectionStore(stateStore),
        notifications: SecureMessagingIncomingNotificationSink =
            com.kit.wallet.data.messaging.NoOpSecureMessagingIncomingNotificationSink,
        currentActivationRevocation: SecureMessagingCurrentActivationRevocation =
            com.kit.wallet.data.messaging.NoOpSecureMessagingCurrentActivationRevocation,
        historyContinuationScheduler: SecureMessagingHistoryContinuationScheduler =
            SecureMessagingHistoryContinuationScheduler { },
    ) = SecureMessagingEventProcessor(
        crypto,
        projections,
        SecureMessagingSyncCursorStore(stateStore),
        notifications,
        currentActivationRevocation,
        historyContinuationScheduler,
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

    private fun enqueueEmptyHistoryCandidates(
        roster: MessagingDeviceRosterDto,
        targetDeviceId: String,
        targetEnrollmentEpoch: Long,
    ) {
        val target = roster.devices.orEmpty().filterNotNull()
            .single { it.deviceId == targetDeviceId }
        val response = MessagingHistoryBackfillCandidatesDto(
            conversationId = CONVERSATION_ID,
            rosterRevision = roster.rosterRevision,
            targetCryptoBundle = MessagingHistoryTargetCryptoBundleDto(
                deviceId = targetDeviceId,
                userId = target.userId,
                enrollmentEpoch = targetEnrollmentEpoch,
                signalDeviceId = target.signalDeviceId,
                registrationId = target.registrationId,
                protocolVersion = target.protocolVersion,
                bundleVersion = target.bundleVersion,
                identityKeySha256 = target.identityKeySha256,
            ),
            messages = emptyList(),
            page = CursorPageDto(nextCursor = null, hasMore = false, limit = 50),
        )
        val encoded = moshi.adapter(MessagingHistoryBackfillCandidatesDto::class.java)
            .toJson(response)
        server.enqueue(jsonResponse("""{"ok":true,"data":$encoded}"""))
    }

    private fun enqueueSync(events: List<MessagingSyncEventDto>, nextCursor: String) {
        server.enqueue(syncResponse(events, nextCursor))
    }

    private fun syncResponse(
        events: List<MessagingSyncEventDto>,
        nextCursor: String,
    ): MockResponse {
        val response = MessagingSyncDto(
            events = events,
            page = CursorPageDto(nextCursor = nextCursor, hasMore = false, limit = 50),
        )
        val encoded = moshi.adapter(MessagingSyncDto::class.java).toJson(response)
        return jsonResponse("""{"ok":true,"data":$encoded}""")
    }

    private fun enqueueCurrentKeyStatus() {
        server.enqueue(currentKeyStatusResponse())
    }

    private fun currentKeyStatusResponse(
        identityKeySha256: String = CURRENT_IDENTITY_HASH,
    ): MockResponse = jsonResponse(
                """{"ok":true,"data":{
                    "enrolled":true,"enrollment_epoch":1,
                    "device_id":"$CURRENT_DEVICE_ID","signal_device_id":1,
                    "protocol_version":"v2","registration_id":42,
                    "identity_key_sha256":"$identityKeySha256","signed_prekey_id":1001,
                    "signed_prekey_sha256":"$SIGNED_PREKEY_HASH",
                    "pq_last_resort_prekey_id":2001,
                    "pq_last_resort_prekey_sha256":"$PQ_PREKEY_HASH","bundle_version":2,
                    "available_one_time_prekeys":100,"available_ec_one_time_prekeys":100,
                    "available_pq_one_time_prekeys":100,"replenish_at":20,
                    "needs_replenishment":false,"published_at":"$TIMESTAMP","rotated_at":null,
                    "transparency":{"revision":"2","event_type":"device.enrolled",
                    "protocol_version":"v2","event_hash":"$EVENT_HASH",
                    "identity_key_sha256":"$identityKeySha256",
                    "pq_last_resort_prekey_id":2001,
                    "pq_last_resort_prekey_sha256":"$PQ_PREKEY_HASH",
                    "occurred_at":"$TIMESTAMP"}}}
                """.trimIndent(),
            )

    private fun unenrolledKeyStatusResponse(): MockResponse = jsonResponse(
        """{"ok":true,"data":{"enrolled":false,"enrollment_epoch":2,"protocol_version":"v2",
            "available_one_time_prekeys":0,"available_ec_one_time_prekeys":0,
            "available_pq_one_time_prekeys":0,"replenish_at":20,
            "needs_replenishment":true}}
        """.trimIndent(),
    )

    private fun recordCurrentIdentity(session: RemoteSecureMessagingTransport.Session) {
        session.recordReconciledKeyIdentity(
            RemoteSecureMessagingTransport.Session.KeyStatus(
                session,
                Any(),
                true,
                1,
                1,
                42,
                CURRENT_IDENTITY_HASH,
                1_001,
                2_001,
                2,
                100,
                100,
                100,
                20,
                false,
            ),
        )
    }

    private suspend fun assertSelfLifecycleEventRejected(
        eventType: String,
        statusResponse: MockResponse,
        expectedReason: SecureMessagingQuarantineReason,
    ) {
        val (session, lifecycle, fence) = openSyncingSession()
        recordCurrentIdentity(session)
        lifecycle.finishActivation(fence)
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        registry.publish(session, fence)
        val stateStore = TestSecureMessagingStateStore()
        var erasures = 0
        val processor = processor(
            stateStore = stateStore,
            crypto = PersistingDecryptionEngine(stateStore),
            currentActivationRevocation = SecureMessagingCurrentActivationRevocation {
                erasures++
            },
        )
        enqueueSync(
            listOf(
                deviceLifecycleEvent(
                    eventType = eventType,
                    userId = CURRENT.userId,
                    deviceId = CURRENT.serverDeviceId,
                ),
            ),
            "rejected_self_event_cursor",
        )
        server.enqueue(statusResponse)

        val failure = runCatching { processor.synchronize(session) }.exceptionOrNull()

        val recovery = failure as? SecureMessagingReauthenticationRequiredException
        assertNotNull("Unexpected failure: $failure; cause=${failure?.cause}", recovery)
        assertEquals(
            com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget(
                serverDeviceId = CURRENT_DEVICE_ID,
                enrollmentEpoch = 1,
                registrationId = 42,
                identityKeySha256 = CURRENT_IDENTITY_HASH,
                bundleVersion = 2,
            ),
            recovery?.target,
        )
        val cryptographicFailure = recovery?.cause as? SecureMessagingCryptographicFailureException
        assertEquals(
            expectedReason,
            cryptographicFailure?.quarantineReason,
        )
        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, lifecycle.snapshot().stage)
        assertNull(registry.currentOrNull())
        assertEquals(0, erasures)
    }

    private suspend fun assertSelfLifecycleRevalidationRetries(
        eventType: String,
        statusResponse: MockResponse,
    ) {
        val (session, lifecycle, fence) = openSyncingSession()
        recordCurrentIdentity(session)
        lifecycle.finishActivation(fence)
        val registry = SecureMessagingActiveSessionRegistry(lifecycle)
        registry.publish(session, fence)
        val stateStore = TestSecureMessagingStateStore()
        var erasures = 0
        val processor = processor(
            stateStore = stateStore,
            crypto = PersistingDecryptionEngine(stateStore),
            currentActivationRevocation = SecureMessagingCurrentActivationRevocation {
                erasures++
            },
        )
        enqueueSync(
            listOf(
                deviceLifecycleEvent(
                    eventType = eventType,
                    userId = CURRENT.userId,
                    deviceId = CURRENT.serverDeviceId,
                ),
            ),
            "retry_self_event_cursor",
        )
        server.enqueue(statusResponse)

        val failure = runCatching { processor.synchronize(session) }.exceptionOrNull()

        assertTrue(
            "Unexpected revalidation failure for $eventType: $failure; cause=${failure?.cause}",
            failure is SecureMessagingRevalidationRetryException,
        )
        assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, lifecycle.snapshot().stage)
        assertNull(registry.currentOrNull())
        assertEquals(0, erasures)
        assertNull(SecureMessagingSyncCursorStore(stateStore).load())

        server.enqueue(currentKeyStatusResponse())
        processor.synchronize(session)

        assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
        assertNotNull(registry.currentOrNull())
        assertEquals(0, erasures)
    }

    private fun enqueueDeliveryAcknowledgement(messageId: String = INCOMING_MESSAGE_ID) {
        val response = MessageDeliveryAcknowledgementDto(
            deliveryState = "delivered_to_device",
            deviceId = CURRENT_DEVICE_ID,
            acknowledgedCount = 1,
            newlyAcknowledgedCount = 1,
            items = listOf(MessageDeliveryReceiptDto(messageId, TIMESTAMP)),
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
            enrollmentEpoch = if (eventType == "device.enrolled") 2 else null,
            signalDeviceId = 2,
            registrationId = 43,
            protocolVersion = "v2",
            bundleVersion = 2,
            identityKeySha256 = "d".repeat(64),
            previousIdentityKeySha256 = if (eventType == "identity.changed") {
                "c".repeat(64)
            } else {
                null
            },
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

    private fun historyIncomingEvent(
        roster: MessagingDeviceRosterDto,
        transferId: String,
        eventId: Long = 10,
    ): MessagingSyncEventDto {
        val peer = roster.devices.orEmpty().filterNotNull()
            .single { it.deviceId == PEER_DEVICE_ID }
        val donor = roster.devices.orEmpty().filterNotNull()
            .single { it.deviceId == OWN_DONOR_DEVICE_ID }
        val ciphertext = "opaque donor history ciphertext".toByteArray(StandardCharsets.UTF_8)
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
                senderEnrollmentEpoch = 3,
                senderSignalDeviceId = peer.signalDeviceId,
                senderRegistrationId = peer.registrationId,
                senderProtocolVersion = "v2",
                senderBundleVersion = peer.bundleVersion,
                senderIdentityKeySha256 = peer.identityKeySha256,
                rosterRevision = ORIGINAL_HISTORY_ROSTER,
                kind = ENCRYPTED_MESSAGE_KIND,
                replyToMessageId = null,
                envelope = EncryptedMessageEnvelopeDto(
                    recipientDeviceId = CURRENT_DEVICE_ID,
                    recipientEnrollmentEpoch = 1,
                    envelopeType = "signal-message-v2",
                    ciphertext = Base64.getEncoder().encodeToString(ciphertext),
                    ciphertextSha256 = sha256(ciphertext),
                    isHistoryBackfill = true,
                    transferClientMessageId = transferId,
                    transferRosterRevision = roster.rosterRevision,
                    cryptoSender = EncryptedMessageCryptoSenderDto(
                        userId = CURRENT_USER_ID,
                        deviceId = OWN_DONOR_DEVICE_ID,
                        enrollmentEpoch = 5,
                        signalDeviceId = donor.signalDeviceId,
                        registrationId = donor.registrationId,
                        protocolVersion = "v2",
                        bundleVersion = donor.bundleVersion,
                        identityKeySha256 = donor.identityKeySha256,
                    ),
                ),
                attachments = emptyList(),
                reactions = emptyList(),
                sentAt = TIMESTAMP,
                revokedAt = null,
            ),
            occurredAt = TIMESTAMP,
        )
    }

    private fun outboundHistoryIncomingEvent(
        roster: MessagingDeviceRosterDto,
        transferId: String,
        eventId: Long,
    ): MessagingSyncEventDto {
        val current = roster.devices.orEmpty().filterNotNull()
            .single { it.deviceId == CURRENT_DEVICE_ID }
        val original = historyIncomingEvent(roster, transferId, eventId)
        val data = checkNotNull(original.data)
        return original.copy(
            resourceId = OUTBOUND_SERVER_ID,
            data = data.copy(
                id = OUTBOUND_SERVER_ID,
                clientMessageId = OUTBOUND_CLIENT_ID,
                sender = EncryptedMessageSenderDto(CURRENT_USER_ID, "Current User"),
                senderDeviceId = CURRENT_DEVICE_ID,
                senderEnrollmentEpoch = 1,
                senderSignalDeviceId = current.signalDeviceId,
                senderRegistrationId = current.registrationId,
                senderBundleVersion = current.bundleVersion,
                senderIdentityKeySha256 = current.identityKeySha256,
                rosterRevision = roster.rosterRevision,
            ),
        )
    }

    private fun historyDescriptor(transferId: String, transferRoster: String): String =
        """{"schema":"kit.messaging.history.v1","type":"history_backfill",""" +
            "\"transfer_client_message_id\":\"$transferId\"," +
            "\"target_device_id\":\"$CURRENT_DEVICE_ID\"," +
            "\"target_enrollment_epoch\":1," +
            "\"transfer_roster_revision\":\"$transferRoster\"," +
            "\"message_id\":\"$INCOMING_MESSAGE_ID\"," +
            "\"client_message_id\":\"$INCOMING_CLIENT_ID\"," +
            "\"conversation_id\":\"$CONVERSATION_ID\"," +
            "\"sender_user_id\":\"$PEER_USER_ID\"," +
            "\"sender_device_id\":\"$PEER_DEVICE_ID\"," +
            "\"sender_enrollment_epoch\":3," +
            "\"sender_signal_device_id\":2," +
            "\"original_roster_revision\":\"$ORIGINAL_HISTORY_ROSTER\"," +
            "\"kind\":\"$ENCRYPTED_MESSAGE_KIND\"," +
            "\"reply_to_message_id\":null," +
            "\"sent_at\":\"$TIMESTAMP\"," +
            "\"text\":\"restored only from authenticated ciphertext\"}"

    private fun outboundHistoryDescriptor(transferId: String, rosterRevision: String): String =
        """{"schema":"kit.messaging.history.v1","type":"history_backfill",""" +
            "\"transfer_client_message_id\":\"$transferId\"," +
            "\"target_device_id\":\"$CURRENT_DEVICE_ID\"," +
            "\"target_enrollment_epoch\":1," +
            "\"transfer_roster_revision\":\"$rosterRevision\"," +
            "\"message_id\":\"$OUTBOUND_SERVER_ID\"," +
            "\"client_message_id\":\"$OUTBOUND_CLIENT_ID\"," +
            "\"conversation_id\":\"$CONVERSATION_ID\"," +
            "\"sender_user_id\":\"$CURRENT_USER_ID\"," +
            "\"sender_device_id\":\"$CURRENT_DEVICE_ID\"," +
            "\"sender_enrollment_epoch\":1," +
            "\"sender_signal_device_id\":1," +
            "\"original_roster_revision\":\"$rosterRevision\"," +
            "\"kind\":\"$ENCRYPTED_MESSAGE_KIND\"," +
            "\"reply_to_message_id\":null," +
            "\"sent_at\":\"$TIMESTAMP\"," +
            "\"text\":\"$SELF_AUTHORED_HISTORY_TEXT\"}"

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
            rosterDevice(
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                1,
                42,
                0x11,
                enrollmentEpoch = 1,
            ),
            rosterDevice(
                PEER_DEVICE_ID,
                PEER_USER_ID,
                2,
                43,
                0x21,
                enrollmentEpoch = 3,
            ),
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

    private fun historyTransferRoster(donorEnrollmentEpoch: Long = 5): MessagingDeviceRosterDto {
        val devices = listOf(
            rosterDevice(
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                1,
                42,
                0x11,
                enrollmentEpoch = 1,
            ),
            rosterDevice(
                OWN_DONOR_DEVICE_ID,
                CURRENT_USER_ID,
                3,
                44,
                0x31,
                enrollmentEpoch = donorEnrollmentEpoch,
            ),
            rosterDevice(
                PEER_DEVICE_ID,
                PEER_USER_ID,
                2,
                43,
                0x21,
                enrollmentEpoch = 3,
            ),
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
        enrollmentEpoch: Long? = null,
    ): MessagingDeviceRosterEntryDto {
        val identityKey = signalValue(5, seed, 33)
        val signedKey = signalValue(5, seed + 1, 33)
        return MessagingDeviceRosterEntryDto(
            deviceId = deviceId,
            enrollmentEpoch = enrollmentEpoch,
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

    private class OutboundCommitTestEngine(
        private val stateStore: TestSecureMessagingStateStore,
        private val authenticatedText: String,
        failuresBeforeCommit: Int,
    ) : SecureMessagingCryptoEngine {
        private var remainingFailures = failuresBeforeCommit

        override suspend fun openTransaction(
            activation: SecureMessagingActivationCapability,
        ): SecureMessagingCryptoTransaction = OutboundCommitTestTransaction(
            activation = activation,
            stateStore = stateStore,
            authenticatedText = authenticatedText,
            failCommit = {
                if (remainingFailures > 0) {
                    remainingFailures--
                    true
                } else {
                    false
                }
            },
        )

        override suspend fun eraseAll() = stateStore.eraseAll()

        override suspend fun retireRemoteDevices(
            activation: SecureMessagingActivationCapability,
            affectedUserId: String,
            affectedServerDeviceId: String?,
        ) = Unit
    }

    private class OutboundCommitTestTransaction(
        activation: SecureMessagingActivationCapability,
        private val stateStore: TestSecureMessagingStateStore,
        private val authenticatedText: String,
        private val failCommit: () -> Boolean,
    ) : FailClosedSecureMessagingCryptoTransaction(activation) {
        private var stagedFanout: SecureMessagingPreparedFanout? = null
        private var stagedPlan: com.kit.wallet.data.messaging.SecureMessagingEncryptionPlanSnapshot? = null
        private var destination: Pair<String, String>? = null

        override suspend fun stageProvisioningMaterial(plan: SecureMessagingProvisioningPlan) =
            error("unexpected provisioning")

        override suspend fun stageSessionMaterial(
            request: SecureMessagingSessionEstablishmentRequest,
        ) = error("unexpected session establishment")

        override suspend fun findMissingSessionAddresses(
            plan: SecureMessagingEncryptionPlan,
            candidates: List<SecureMessagingCryptoAddress>,
        ): Collection<SecureMessagingCryptoAddress> = emptyList()

        override suspend fun stageEncryptionMaterial(request: SecureMessagingEncryptionRequest) {
            val plan = request.planSnapshot.snapshot()
            stagedPlan = plan
            stagedFanout = SecureMessagingPreparedFanout(
                conversationId = plan.conversationId,
                clientMessageId = request.clientMessageId,
                rosterRevision = plan.rosterRevision,
                recipients = plan.recipients,
                envelopes = plan.recipients.addresses().mapIndexed { index, recipient ->
                    SecureMessagingPreparedEnvelope(
                        recipient = recipient,
                        kind = SecureMessagingEnvelopeKind.SESSION,
                        ciphertext = OpaqueCryptoBytes.copyOf(
                            byteArrayOf((index + 1).toByte(), 0x5a),
                        ),
                    )
                },
                replyToMessageId = request.replyToMessageId,
            )
        }

        override suspend fun stageDecryptionMaterial(request: SecureMessagingDecryptionRequest) =
            error("unexpected decryption")

        override suspend fun prepareStaged(
            operation: SecureMessagingCryptoOperation,
            companionStateIntent: SecureMessagingCompanionStateIntent?,
        ): PreparedCommit {
            check(operation == SecureMessagingCryptoOperation.ENCRYPT)
            val resolved = companionStateDestination(checkNotNull(companionStateIntent))
            destination = resolved.namespace to resolved.recordKey
            return preparedEncryption(checkNotNull(stagedFanout))
        }

        override suspend fun commitPrepared(
            operation: SecureMessagingCryptoOperation,
            preparedResult: PreparedCommit,
        ) {
            check(operation == SecureMessagingCryptoOperation.ENCRYPT)
            if (failCommit()) {
                throw SecureMessagingStateConflictException("injected failure before durable commit")
            }
            val fanout = checkNotNull(stagedFanout)
            val plan = checkNotNull(stagedPlan)
            val target = checkNotNull(destination)
            stateStore.persistCompanionRecordForTest(
                namespace = target.first,
                recordKey = target.second,
                direction = LibSignalCompanionDirection.OUTBOUND,
                messageId = fanout.clientMessageId,
                clientMessageId = fanout.clientMessageId,
                conversationId = fanout.conversationId,
                rosterRevision = fanout.rosterRevision,
                sender = plan.sender,
                replyToMessageId = fanout.replyToMessageId,
                text = authenticatedText,
                envelopes = fanout.envelopes.map { envelope ->
                    PersistedCompanionEnvelopeFixture(
                        recipient = envelope.recipient,
                        kind = envelope.kind,
                        ciphertext = envelope.ciphertext.copyBytes(),
                    )
                },
            )
        }

        override suspend fun abortStaged() = Unit

        override fun wipeStagedSecrets() {
            stagedFanout = null
            stagedPlan = null
            destination = null
        }
    }

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
                    snapshot.isHistoryBackfill,
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
            return encodeSecureMessagingTextContent(
                SecureMessagingTextContentBinding(
                    clientMessageId = snapshot.clientMessageId,
                    conversationId = snapshot.conversationId,
                    rosterRevision = snapshot.rosterRevision,
                    sender = snapshot.sender,
                    replyToMessageId = snapshot.replyToMessageId,
                ),
                authenticatedText,
            )
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

    private class MutableTestSessionStore(initial: SessionTokens?) : SessionStore {
        private val mutableSession = MutableStateFlow(initial)
        private var revision = 0L
        override val session = mutableSession

        override fun current(): SessionTokens? = mutableSession.value

        override fun snapshot() = SessionSnapshot(revision, current()?.fence())

        override suspend fun save(tokens: SessionTokens) {
            mutableSession.value = tokens
            revision++
        }

        override suspend fun saveIfUnchanged(
            expected: SessionSnapshot,
            tokens: SessionTokens,
        ): Boolean {
            if (snapshot() != expected) return false
            save(tokens)
            return true
        }

        override suspend fun updateProfileSetupState(
            expected: SessionFence,
            state: ProfileSetupState,
        ): Boolean {
            val current = current() ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = state))
            return true
        }

        override suspend fun <T> withCurrentSession(
            expected: SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = checkNotNull(current())
            check(current.fence() == expected)
            return block(current)
        }

        override suspend fun clearIfCurrent(expected: SessionFence): Boolean {
            if (current()?.fence() != expected) return false
            clear()
            return true
        }

        override suspend fun clear() {
            mutableSession.value = null
            revision++
        }

        fun replace(tokens: SessionTokens?) {
            mutableSession.value = tokens
            revision++
        }
    }

    private class LifecycleLeasedStateStore(
        private val delegate: TestSecureMessagingStateStore,
    ) : SecureMessagingStateStore {
        private val lifecycleGate = SecureMessagingLifecycleGate()
        private val hookLock = Any()
        private var nextActivationLeaseBlock: ActivationLeaseBlock? = null

        override suspend fun read(
            namespace: String,
            recordKey: String,
        ): SecureMessagingRecord? = lifecycleGate.withOperation {
            delegate.read(namespace, recordKey)
        }

        override suspend fun readNamespacePage(
            namespace: String,
            afterRecordKey: String?,
            limit: Int,
        ): SecureMessagingRecordPage = lifecycleGate.withOperation {
            delegate.readNamespacePage(namespace, afterRecordKey, limit)
        }

        override suspend fun write(
            namespace: String,
            recordKey: String,
            expectedVersion: Long?,
            bytes: ByteArray,
        ): SecureMessagingRecordVersion = lifecycleGate.withOperation {
            delegate.write(namespace, recordKey, expectedVersion, bytes)
        }

        override suspend fun writeBatch(
            writes: List<SecureMessagingStateWrite>,
        ): List<SecureMessagingRecordVersion> = lifecycleGate.withOperation {
            delegate.writeBatch(writes)
        }

        override suspend fun <T> withActivationLease(
            activation: SecureMessagingActivationCapability,
            readyRequired: Boolean,
            operation: suspend () -> T,
        ): T {
            val block = synchronized(hookLock) {
                nextActivationLeaseBlock.also { nextActivationLeaseBlock = null }
            }
            block?.pause()
            return lifecycleGate.withActivationOperation(
                activation = activation,
                readyRequired = readyRequired,
                operation = operation,
            )
        }

        override suspend fun deleteNamespace(namespace: String) =
            lifecycleGate.withOperation { delegate.deleteNamespace(namespace) }

        override suspend fun eraseAll() = lifecycleGate.erase { delegate.eraseAll() }

        override suspend fun allowForActiveSession() = lifecycleGate.open()

        fun blockBeforeNextActivationLease(): ActivationLeaseBlock = synchronized(hookLock) {
            check(nextActivationLeaseBlock == null) {
                "An activation-lease block is already installed"
            }
            ActivationLeaseBlock().also { nextActivationLeaseBlock = it }
        }

        class ActivationLeaseBlock internal constructor() {
            private val entered = CompletableDeferred<Unit>()
            private val released = CompletableDeferred<Unit>()

            suspend fun awaitEntered() = entered.await()

            fun release() {
                released.complete(Unit)
            }

            suspend fun pause() {
                entered.complete(Unit)
                released.await()
            }
        }
    }

    private companion object {
        const val CURRENT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val PEER_USER_ID = "22222222-2222-4222-8222-222222222222"
        const val CURRENT_DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val OWN_DONOR_DEVICE_ID = "45454545-4545-4545-8545-454545454545"
        const val PEER_DEVICE_ID = "55555555-5555-4555-8555-555555555555"
        const val CONVERSATION_ID = "66666666-6666-4666-8666-666666666666"
        const val INCOMING_CLIENT_ID = "77777777-7777-4777-8777-777777777777"
        const val OUTBOUND_CLIENT_ID = "88888888-8888-4888-8888-888888888888"
        const val SECOND_OUTBOUND_CLIENT_ID = "99999999-9999-4999-8999-999999999999"
        const val THIRD_OUTBOUND_CLIENT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"
        const val INCOMING_MESSAGE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val OUTBOUND_SERVER_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SECOND_OUTBOUND_SERVER_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val SELF_AUTHORED_HISTORY_TEXT = "self-authored retained history"
        const val TIMESTAMP = "2026-07-20T12:00:00Z"
        val ORIGINAL_HISTORY_ROSTER = "v1:sha256:${"f".repeat(64)}"
        val CURRENT_IDENTITY_HASH = "1".repeat(64)
        val SIGNED_PREKEY_HASH = "2".repeat(64)
        val PQ_PREKEY_HASH = "3".repeat(64)
        val EVENT_HASH = "4".repeat(64)
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
