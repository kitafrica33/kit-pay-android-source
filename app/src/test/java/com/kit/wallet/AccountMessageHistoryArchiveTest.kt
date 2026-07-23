package com.kit.wallet

import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.messaging.AccountArchivedMessage
import com.kit.wallet.data.messaging.AccountMessageArchiveConflictException
import com.kit.wallet.data.messaging.AccountMessageArchiveOwner
import com.kit.wallet.data.messaging.AccountMessageArchivePage
import com.kit.wallet.data.messaging.AccountMessageArchivePurgeIntents
import com.kit.wallet.data.messaging.AccountMessageArchiveRecord
import com.kit.wallet.data.messaging.AccountMessageArchiveStore
import com.kit.wallet.data.messaging.AccountMessageHistoryArchive
import com.kit.wallet.data.messaging.AccountMessageHistoryCoordinator
import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.PendingAccountMessageArchivePurge
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingProjectedMessage
import com.kit.wallet.data.messaging.SecureMessagingProjectionDeliveryState
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.CoroutineOwnedMutex
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import java.lang.reflect.Modifier
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMessageHistoryArchiveTest {
    @Test
    fun `same account login restores sanitized accepted history without send authority`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val sessions = MutableSessionStore(session(ACCOUNT_A, "session-a-one"))
        val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
        val archive = archiveAccess.capture(ACCOUNT_A)
        val originalState = TestSecureMessagingStateStore()
        val original = originalState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(CLIENT_MESSAGE_A),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = CLIENT_MESSAGE_A,
            clientMessageId = CLIENT_MESSAGE_A,
            conversationId = CONVERSATION_A,
            rosterRevision = ROSTER_REVISION,
            sender = ACCOUNT_A_ADDRESS,
            text = "accepted before logout",
            envelopes = listOf(
                PersistedCompanionEnvelopeFixture(
                    recipient = ACCOUNT_B_ADDRESS,
                    kind = SecureMessagingEnvelopeKind.SESSION,
                    ciphertext = byteArrayOf(9, 8, 7, 6),
                ),
            ),
        )
        archive.archive(
            SecureMessagingProjectedMessage(
                durableRecord = original,
                serverMessageId = SERVER_MESSAGE_A,
                sentAt = SENT_AT,
                deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED,
            ),
        )

        sessions.save(session(ACCOUNT_A, "session-a-two"))
        assertTrue(runCatching { archive.readAll() }.isFailure)
        val restoredState = TestSecureMessagingStateStore()
        val restored = SecureMessagingProjectionStore(
            restoredState,
            LibSignalCompanionStateReader(restoredState),
            archiveAccess,
        )
        val revisionBeforeRestore = restored.changes.value
        restored.restoreArchivedHistory(
            activation = readyActivation(sessions.current()!!),
            currentUserId = ACCOUNT_A,
            allowedConversationIds = setOf(CONVERSATION_A),
        )
        assertEquals(revisionBeforeRestore, restored.changes.value)

        val projected = restored.readPage(limit = 10).messages().single()
        assertEquals(SERVER_MESSAGE_A, projected.serverMessageId)
        assertEquals(SENT_AT, projected.sentAt)
        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_SELF_DELIVERED,
            projected.deliveryState,
        )
        assertEquals(LibSignalCompanionDirection.INBOUND, projected.durableRecord.direction)
        assertEquals(ACCOUNT_A_ADDRESS, projected.durableRecord.sender)
        assertEquals("accepted before logout", projected.durableRecord.authenticatedText)
        assertTrue(projected.durableRecord.ciphertextFanout().isEmpty())
        assertNull(
            restoredState.read(
                SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                SecureMessagingProjectionStore.outboundRecordKey(CLIENT_MESSAGE_A),
            ),
        )
    }

    @Test
    fun `targeted purge cannot erase whichever replacement account happens to be current`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val initial = session(ACCOUNT_A, "session-a")
        val sessions = MutableSessionStore(initial)
        val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
        archiveAccess.capture(ACCOUNT_A).archive(
            acceptedInboundProjection(CONVERSATION_A, SERVER_MESSAGE_A, CLIENT_MESSAGE_A),
        )

        sessions.save(session(ACCOUNT_B, "session-b"))
        archiveAccess.eraseAccount(initial.fence())
        sessions.save(session(ACCOUNT_A, "session-a-new"))

        assertTrue(archiveAccess.capture(ACCOUNT_A).readAll().isEmpty())
    }

    @Test
    fun `old purge is retired before a newer same-account generation writes history`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val oldSession = session(ACCOUNT_A, "session-a-old")
        val sessions = MutableSessionStore(oldSession)
        val purgeIntents = InMemoryAccountMessageArchivePurgeIntents()
        val archiveAccess = AccountMessageHistoryArchive(
            sessions,
            DEVICE_IDENTITY,
            records,
            purgeIntents,
        )
        archiveAccess.capture(ACCOUNT_A).archive(
            acceptedInboundProjection(CONVERSATION_A, SERVER_MESSAGE_A, CLIENT_MESSAGE_A),
        )
        purgeIntents.enqueue(oldSession.fence())
        val oldPurge = PendingAccountMessageArchivePurge.from(oldSession.fence())

        sessions.save(session(ACCOUNT_A, "session-a-new"))
        archiveAccess.capture(ACCOUNT_A).archive(
            acceptedInboundProjection(CONVERSATION_B, SERVER_MESSAGE_B, CLIENT_MESSAGE_B),
        )

        assertFalse(purgeIntents.contains(oldPurge))
        sessions.save(session(ACCOUNT_B, "session-b"))
        // A retry may already hold the old marker from a batch snapshot. Its locked marker
        // recheck must not erase the newer generation after that generation switched away.
        assertFalse(archiveAccess.erasePendingAccount(oldPurge))
        assertTrue(purgeIntents.pending().isEmpty())
        sessions.save(session(ACCOUNT_A, "session-a-restored"))
        assertEquals(
            listOf(SERVER_MESSAGE_B),
            archiveAccess.capture(ACCOUNT_A).readAll().map { it.serverMessageId },
        )
    }

    @Test
    fun `obsolete session cannot enqueue a late purge behind replacement history`() = runTest {
        val oldSession = session(ACCOUNT_A, "session-a-old")
        val sessions = MutableSessionStore(oldSession)
        val purgeIntents = InMemoryAccountMessageArchivePurgeIntents()
        val archiveAccess = AccountMessageHistoryArchive(
            sessions,
            DEVICE_IDENTITY,
            InMemoryAccountMessageArchiveStore(),
            purgeIntents,
        )
        sessions.save(session(ACCOUNT_A, "session-a-new"))

        val failure = runCatching {
            archiveAccess.withCurrentPurgeTarget(oldSession.fence()) {
                purgeIntents.enqueue(oldSession.fence())
            }
        }.exceptionOrNull()

        assertTrue(failure is SessionInvalidatedException)
        assertTrue(purgeIntents.pending().isEmpty())
    }

    @Test
    fun `existing retained projection monotonically merges archived receipt without emitting`() =
        runTest {
            val records = InMemoryAccountMessageArchiveStore()
            val sessions = MutableSessionStore(session(ACCOUNT_A, "session-a"))
            val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
            archiveAccess.capture(ACCOUNT_A).archive(
                acceptedInboundProjection(
                    CONVERSATION_A,
                    SERVER_MESSAGE_A,
                    CLIENT_MESSAGE_A,
                ).copy(deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_READ),
            )

            sessions.save(session(ACCOUNT_A, "session-a-new"))
            val retainedState = TestSecureMessagingStateStore()
            val retained = retainedState.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.inboundRecordKey(SERVER_MESSAGE_A),
                direction = LibSignalCompanionDirection.INBOUND,
                messageId = SERVER_MESSAGE_A,
                clientMessageId = CLIENT_MESSAGE_A,
                conversationId = CONVERSATION_A,
                rosterRevision = ROSTER_REVISION,
                sender = ACCOUNT_B_ADDRESS,
                text = "received before logout",
            )
            val projections = SecureMessagingProjectionStore(
                retainedState,
                LibSignalCompanionStateReader(retainedState),
                archiveAccess,
            )
            projections.recordInbound(retained, SENT_AT)
            val revisionBeforeRestore = projections.changes.value

            projections.restoreArchivedHistory(
                activation = readyActivation(sessions.current()!!),
                currentUserId = ACCOUNT_A,
                allowedConversationIds = setOf(CONVERSATION_A),
            )

            assertEquals(revisionBeforeRestore, projections.changes.value)
            val restored = projections.readPage(limit = 10).messages().single()
            assertEquals(
                SecureMessagingProjectionDeliveryState.INBOUND_READ,
                restored.deliveryState,
            )
            assertEquals(retained.recordKey, restored.durableRecord.recordKey)
        }

    @Test
    fun `live archive is best effort but cancellation and final snapshot failures propagate`() =
        runTest {
            val records = InMemoryAccountMessageArchiveStore()
            val sessions = MutableSessionStore(session(ACCOUNT_A, "session-a"))
            val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
            val stateDelegate = TestSecureMessagingStateStore()
            val state = ActivationLeasedStateStore(stateDelegate)
            val activation = readyActivation(sessions.current()!!)
            val durable = stateDelegate.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.inboundRecordKey(SERVER_MESSAGE_A),
                direction = LibSignalCompanionDirection.INBOUND,
                messageId = SERVER_MESSAGE_A,
                clientMessageId = CLIENT_MESSAGE_A,
                conversationId = CONVERSATION_A,
                rosterRevision = ROSTER_REVISION,
                sender = ACCOUNT_B_ADDRESS,
                text = "received before logout",
            )
            val projections = SecureMessagingProjectionStore(
                state,
                LibSignalCompanionStateReader(state),
                archiveAccess,
            )
            projections.recordInbound(durable, SENT_AT)

            records.beforeWrite = { assertFalse(state.activationLeaseHeld) }
            records.writeFailure = IllegalStateException("archive unavailable")
            val livePage = projections.readPageAndArchive(
                activation = activation,
                expectedOwnerAccountId = ACCOUNT_A,
                limit = 10,
            )
            assertEquals(1, livePage.messages().size)
            assertTrue(
                runCatching {
                    val archive = projections.captureHistoryArchive(
                        ACCOUNT_A,
                        sessions.current()!!.fence(),
                    )
                    projections.withStateLease { archiveAcceptedHistory(archive) }
                }.isFailure,
            )

            records.writeFailure = CancellationException("cancel archive")
            assertTrue(
                runCatching {
                    projections.readPageAndArchive(
                        activation = activation,
                        expectedOwnerAccountId = ACCOUNT_A,
                        limit = 10,
                    )
                }.exceptionOrNull() is CancellationException,
            )
        }

    @Test
    fun `archive is isolated by authenticated account across login changes`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val sessions = MutableSessionStore(session(ACCOUNT_A, "session-a"))
        val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
        val accountAArchive = archiveAccess.capture(ACCOUNT_A)
        accountAArchive.archive(
            acceptedInboundProjection(CONVERSATION_A, SERVER_MESSAGE_A, CLIENT_MESSAGE_A),
        )

        sessions.save(session(ACCOUNT_B, "session-b"))
        assertTrue(archiveAccess.capture(ACCOUNT_B).readAll().isEmpty())
        assertTrue(runCatching { accountAArchive.readAll() }.isFailure)
        val accountBState = TestSecureMessagingStateStore()
        SecureMessagingProjectionStore(
            accountBState,
            LibSignalCompanionStateReader(accountBState),
            archiveAccess,
        ).restoreArchivedHistory(
            activation = readyActivation(sessions.current()!!),
            currentUserId = ACCOUNT_B,
            allowedConversationIds = setOf(CONVERSATION_A),
        )
        assertTrue(
            LibSignalCompanionStateReader(accountBState).readPage(
                SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                afterRecordKey = null,
                limit = 10,
            ).records().isEmpty(),
        )

        sessions.save(session(ACCOUNT_A, "session-a-new"))
        assertEquals(
            listOf(SERVER_MESSAGE_A),
            archiveAccess.capture(ACCOUNT_A).readAll().map { it.serverMessageId },
        )
    }

    @Test
    fun `stale restore cannot materialize archive into a replacement activation`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val sessions = MutableSessionStore(session(ACCOUNT_A, "session-a-old"))
        val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
        archiveAccess.capture(ACCOUNT_A).archive(
            acceptedInboundProjection(CONVERSATION_A, SERVER_MESSAGE_A, CLIENT_MESSAGE_A),
        )
        sessions.save(session(ACCOUNT_A, "session-a-restored"))

        val restoredState = TestSecureMessagingStateStore()
        val gatedState = ActivationLeasedStateStore(restoredState)
        val restored = SecureMessagingProjectionStore(
            gatedState,
            LibSignalCompanionStateReader(gatedState),
            archiveAccess,
        )
        val active = openReadyActivation(sessions.current()!!)
        records.beforeReadPage = { assertFalse(gatedState.activationLeaseHeld) }
        val leaseBlock = gatedState.blockBeforeNextActivationLease()

        val restore = async {
            runCatching {
                restored.restoreArchivedHistory(
                    activation = active.capability,
                    currentUserId = ACCOUNT_A,
                    allowedConversationIds = setOf(CONVERSATION_A),
                )
            }.exceptionOrNull()
        }
        leaseBlock.awaitEntered()

        active.lifecycle.beginErasure()
        gatedState.eraseAll()
        active.lifecycle.finishErasure()
        sessions.save(session(ACCOUNT_B, "session-b"))
        active.lifecycle.beginSession(bindingFor(sessions.current()!!))
        leaseBlock.release()

        assertTrue(restore.await() is IllegalStateException)
        assertTrue(restored.readPage(limit = 10).messages().isEmpty())
    }

    @Test
    fun `final snapshot owns session before entering generic state lease`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val targetSession = session(ACCOUNT_A, "session-a")
        val sessions = MutableSessionStore(targetSession)
        val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
        val stateDelegate = TestSecureMessagingStateStore()
        val state = StateLeaseBlockedStore(stateDelegate)
        val durable = stateDelegate.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(SERVER_MESSAGE_A),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = SERVER_MESSAGE_A,
            clientMessageId = CLIENT_MESSAGE_A,
            conversationId = CONVERSATION_A,
            rosterRevision = ROSTER_REVISION,
            sender = ACCOUNT_B_ADDRESS,
            text = "snapshot before replacement",
        )
        val projections = SecureMessagingProjectionStore(
            state,
            LibSignalCompanionStateReader(state),
            archiveAccess,
        )
        projections.recordInbound(durable, SENT_AT)
        val coordinator = AccountMessageHistoryCoordinator(
            projections = projections,
            archive = archiveAccess,
            purgeQueue = InMemoryAccountMessageArchivePurgeIntents(),
            applicationScope = backgroundScope,
        )
        val stateLease = state.blockBeforeNextStateLease()
        val snapshot = async { coordinator.snapshotActiveHistory(targetSession.fence()) }

        stateLease.awaitEntered()
        val replacement = async {
            sessions.save(session(ACCOUNT_B, "session-b"))
            stateDelegate.eraseAll()
            stateDelegate.persistCompanionRecordForTest(
                namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                recordKey = SecureMessagingProjectionStore.inboundRecordKey(SERVER_MESSAGE_B),
                direction = LibSignalCompanionDirection.INBOUND,
                messageId = SERVER_MESSAGE_B,
                clientMessageId = CLIENT_MESSAGE_B,
                conversationId = CONVERSATION_B,
                rosterRevision = ROSTER_REVISION,
                sender = ACCOUNT_A_ADDRESS,
                text = "replacement state",
            )
        }
        runCurrent()
        assertFalse(replacement.isCompleted)

        stateLease.release()
        snapshot.await()
        replacement.await()
        sessions.save(session(ACCOUNT_A, "session-a-new"))

        assertEquals(
            listOf(SERVER_MESSAGE_A),
            archiveAccess.capture(ACCOUNT_A).readAll().map { it.serverMessageId },
        )
    }

    @Test
    fun `delivery state advances monotonically and cannot rewrite immutable history`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val archive = AccountMessageHistoryArchive(
            MutableSessionStore(session(ACCOUNT_A, "session-a")),
            DEVICE_IDENTITY,
            records,
        ).capture(ACCOUNT_A)
        val original = acceptedOutboundProjection(
            conversationId = CONVERSATION_A,
            serverMessageId = SERVER_MESSAGE_A,
            clientMessageId = CLIENT_MESSAGE_A,
        )

        archive.archive(original.copy(deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_SENT))
        archive.archive(
            original.copy(deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED),
        )
        archive.archive(original.copy(deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_SENT))
        archive.archive(original.copy(deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_READ))
        archive.archive(
            original.copy(deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_DELIVERED),
        )

        assertEquals(
            SecureMessagingProjectionDeliveryState.INBOUND_SELF_READ,
            archive.readAll().single().deliveryState,
        )
        val changedState = TestSecureMessagingStateStore()
        val changedDurable = changedState.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(CLIENT_MESSAGE_A),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = CLIENT_MESSAGE_A,
            clientMessageId = CLIENT_MESSAGE_A,
            conversationId = CONVERSATION_A,
            rosterRevision = ROSTER_REVISION,
            sender = ACCOUNT_A_ADDRESS,
            text = "rewritten plaintext",
            envelopes = outboundFanout(),
        )
        assertTrue(
            runCatching {
                archive.archive(
                    original.copy(
                        durableRecord = changedDurable,
                        deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_READ,
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun `pending failed suppressed and retryable projections never enter archive`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val archive = AccountMessageHistoryArchive(
            MutableSessionStore(session(ACCOUNT_A, "session-a")),
            DEVICE_IDENTITY,
            records,
        ).capture(ACCOUNT_A)
        val outbound = acceptedOutboundProjection(
            conversationId = CONVERSATION_A,
            serverMessageId = SERVER_MESSAGE_A,
            clientMessageId = CLIENT_MESSAGE_A,
        )
        listOf(
            SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING,
            SecureMessagingProjectionDeliveryState.OUTBOUND_RETRY_REQUIRED,
            SecureMessagingProjectionDeliveryState.OUTBOUND_PERMANENT_FAILURE,
        ).forEach { state ->
            archive.archive(
                outbound.copy(
                    serverMessageId = if (state == SecureMessagingProjectionDeliveryState.OUTBOUND_PENDING) {
                        null
                    } else {
                        SERVER_MESSAGE_A
                    },
                    deliveryState = state,
                ),
            )
        }
        val suppressed = acceptedInboundProjection(
            CONVERSATION_A,
            SERVER_MESSAGE_B,
            CLIENT_MESSAGE_B,
        ).copy(deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_SUPPRESSED)
        archive.archive(suppressed)

        assertTrue(archive.readAll().isEmpty())
        archive.archive(outbound.copy(deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_SENT))
        assertEquals(
            listOf(SERVER_MESSAGE_A),
            archive.readAll().map { it.serverMessageId },
        )
    }

    @Test
    fun `restore accepts only server allow listed conversations`() = runTest {
        val records = InMemoryAccountMessageArchiveStore()
        val sessions = MutableSessionStore(session(ACCOUNT_A, "session-a"))
        val archiveAccess = AccountMessageHistoryArchive(sessions, DEVICE_IDENTITY, records)
        val archive = archiveAccess.capture(ACCOUNT_A)
        archive.archive(acceptedInboundProjection(CONVERSATION_A, SERVER_MESSAGE_A, CLIENT_MESSAGE_A))
        archive.archive(acceptedInboundProjection(CONVERSATION_B, SERVER_MESSAGE_B, CLIENT_MESSAGE_B))

        sessions.save(session(ACCOUNT_A, "session-a-new"))
        val restoredState = TestSecureMessagingStateStore()
        val restored = SecureMessagingProjectionStore(
            restoredState,
            LibSignalCompanionStateReader(restoredState),
            archiveAccess,
        )
        restored.restoreArchivedHistory(
            activation = readyActivation(sessions.current()!!),
            currentUserId = ACCOUNT_A,
            allowedConversationIds = setOf(CONVERSATION_B),
        )

        val messages = restored.readPage(limit = 10).messages()
        assertEquals(listOf(SERVER_MESSAGE_B), messages.map { it.serverMessageId })
        assertNull(
            restoredState.read(
                SecureMessagingProjectionStore.COMPANION_NAMESPACE,
                SecureMessagingProjectionStore.inboundRecordKey(SERVER_MESSAGE_A),
            ),
        )
    }

    @Test
    fun `sanitized archive type has no protocol fanout ratchet retry or notification fields`() {
        val fields = AccountArchivedMessage::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name.lowercase() }

        listOf(
            "ciphertext",
            "envelope",
            "fanout",
            "ratchet",
            "prekey",
            "sessionkey",
            "retry",
            "pending",
            "notification",
        ).forEach { forbidden ->
            assertFalse(fields.any { forbidden in it })
        }
    }

    private suspend fun acceptedInboundProjection(
        conversationId: String,
        serverMessageId: String,
        clientMessageId: String,
    ): SecureMessagingProjectedMessage {
        val state = TestSecureMessagingStateStore()
        val durable = state.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.inboundRecordKey(serverMessageId),
            direction = LibSignalCompanionDirection.INBOUND,
            messageId = serverMessageId,
            clientMessageId = clientMessageId,
            conversationId = conversationId,
            rosterRevision = ROSTER_REVISION,
            sender = ACCOUNT_B_ADDRESS,
            text = "received before logout",
        )
        return SecureMessagingProjectedMessage(
            durableRecord = durable,
            serverMessageId = serverMessageId,
            sentAt = SENT_AT,
            deliveryState = SecureMessagingProjectionDeliveryState.INBOUND_RECEIVED,
        )
    }

    private suspend fun acceptedOutboundProjection(
        conversationId: String,
        serverMessageId: String,
        clientMessageId: String,
    ): SecureMessagingProjectedMessage {
        val state = TestSecureMessagingStateStore()
        val durable = state.persistCompanionRecordForTest(
            namespace = SecureMessagingProjectionStore.COMPANION_NAMESPACE,
            recordKey = SecureMessagingProjectionStore.outboundRecordKey(clientMessageId),
            direction = LibSignalCompanionDirection.OUTBOUND,
            messageId = clientMessageId,
            clientMessageId = clientMessageId,
            conversationId = conversationId,
            rosterRevision = ROSTER_REVISION,
            sender = ACCOUNT_A_ADDRESS,
            text = "sent before logout",
            envelopes = outboundFanout(),
        )
        return SecureMessagingProjectedMessage(
            durableRecord = durable,
            serverMessageId = serverMessageId,
            sentAt = SENT_AT,
            deliveryState = SecureMessagingProjectionDeliveryState.OUTBOUND_SENT,
        )
    }

    private fun outboundFanout() = listOf(
        PersistedCompanionEnvelopeFixture(
            recipient = ACCOUNT_B_ADDRESS,
            kind = SecureMessagingEnvelopeKind.SESSION,
            ciphertext = byteArrayOf(1, 2, 3),
        ),
    )

    private data class ReadyActivation(
        val lifecycle: SecureMessagingLifecycleGuard,
        val capability: SecureMessagingActivationCapability,
    )

    private fun bindingFor(session: SessionTokens) = SecureMessagingSessionBinding(
        sessionEpoch = session.sessionId,
        userId = checkNotNull(session.accountId),
        serverDeviceId = if (session.accountId == ACCOUNT_A) DEVICE_A else DEVICE_B,
        installationId = INSTALLATION_ID,
    )

    private fun openReadyActivation(session: SessionTokens): ReadyActivation {
        val lifecycle = SecureMessagingLifecycleGuard()
        val fence = lifecycle.beginSession(bindingFor(session))
        lifecycle.beginCapabilityCheck(fence)
        lifecycle.beginKeyPreparation(fence)
        lifecycle.beginRosterSync(fence)
        lifecycle.finishActivation(fence)
        return ReadyActivation(
            lifecycle = lifecycle,
            capability = lifecycle.activationCapability(fence, readyRequired = true),
        )
    }

    private fun readyActivation(session: SessionTokens): SecureMessagingActivationCapability =
        openReadyActivation(session).capability

    private class MutableSessionStore(initial: SessionTokens) : SessionStore {
        private val mutableSession = MutableStateFlow<SessionTokens?>(initial)
        private val mutex = CoroutineOwnedMutex()
        private var revision = 0L
        override val session: StateFlow<SessionTokens?> = mutableSession

        override fun current(): SessionTokens? = mutableSession.value

        override fun snapshot(): SessionSnapshot = SessionSnapshot(
            revision = revision,
            fence = current()?.fence(),
        )

        override suspend fun save(tokens: SessionTokens) = mutex.withLock {
            saveLocked(tokens)
        }

        private fun saveLocked(tokens: SessionTokens) {
            mutableSession.value = tokens
            revision++
        }

        override suspend fun saveIfUnchanged(
            expected: SessionSnapshot,
            tokens: SessionTokens,
        ): Boolean = mutex.withLock {
            if (snapshot() != expected) return@withLock false
            saveLocked(tokens)
            true
        }

        override suspend fun updateProfileSetupState(
            expected: SessionFence,
            state: ProfileSetupState,
        ): Boolean = mutex.withLock {
            val active = current() ?: return@withLock false
            if (active.fence() != expected) return@withLock false
            saveLocked(active.copy(profileSetupState = state))
            true
        }

        override suspend fun <T> withCurrentSession(
            expected: SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T = mutex.withLock {
            val active = current() ?: throw SessionInvalidatedException()
            if (active.fence() != expected) throw SessionInvalidatedException()
            block(active)
        }

        override suspend fun clearIfCurrent(expected: SessionFence): Boolean = mutex.withLock {
            if (current()?.fence() != expected) return@withLock false
            clearLocked()
            true
        }

        override suspend fun clear() = mutex.withLock {
            clearLocked()
        }

        private fun clearLocked() {
            mutableSession.value = null
            revision++
        }
    }

    private class ActivationLeasedStateStore(
        private val delegate: SecureMessagingStateStore,
    ) : SecureMessagingStateStore by delegate {
        private val hookLock = Any()
        private var nextLeaseBlock: LeaseBlock? = null

        @Volatile
        var activationLeaseHeld = false
            private set

        override suspend fun <T> withActivationLease(
            activation: SecureMessagingActivationCapability,
            readyRequired: Boolean,
            operation: suspend () -> T,
        ): T {
            val block = synchronized(hookLock) {
                nextLeaseBlock.also { nextLeaseBlock = null }
            }
            block?.pause()
            return delegate.withActivationLease(activation, readyRequired) {
                activationLeaseHeld = true
                try {
                    operation()
                } finally {
                    activationLeaseHeld = false
                }
            }
        }

        fun blockBeforeNextActivationLease(): LeaseBlock = synchronized(hookLock) {
            check(nextLeaseBlock == null) { "An activation lease is already blocked" }
            LeaseBlock().also { nextLeaseBlock = it }
        }
    }

    private class StateLeaseBlockedStore(
        private val delegate: SecureMessagingStateStore,
    ) : SecureMessagingStateStore by delegate {
        private val hookLock = Any()
        private var nextLeaseBlock: LeaseBlock? = null

        override suspend fun <T> withStateLease(operation: suspend () -> T): T {
            val block = synchronized(hookLock) {
                nextLeaseBlock.also { nextLeaseBlock = null }
            }
            block?.pause()
            return delegate.withStateLease(operation)
        }

        fun blockBeforeNextStateLease(): LeaseBlock = synchronized(hookLock) {
            check(nextLeaseBlock == null) { "A state lease is already blocked" }
            LeaseBlock().also { nextLeaseBlock = it }
        }
    }

    private class LeaseBlock {
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

    private class InMemoryAccountMessageArchiveStore : AccountMessageArchiveStore {
        private data class Stored(
            val version: Long,
            val bytes: ByteArray,
            val updatedAtEpochMillis: Long,
        )

        private val records = mutableMapOf<Pair<AccountMessageArchiveOwner, String>, Stored>()
        private var clock = 1_000L
        var writeFailure: RuntimeException? = null
        var beforeReadPage: (() -> Unit)? = null
        var beforeWrite: (() -> Unit)? = null

        override suspend fun read(
            owner: AccountMessageArchiveOwner,
            recordKey: String,
        ): AccountMessageArchiveRecord? = records[owner to recordKey]?.toRecord(owner, recordKey)

        override suspend fun readPage(
            owner: AccountMessageArchiveOwner,
            afterRecordKey: String?,
            limit: Int,
        ): AccountMessageArchivePage {
            beforeReadPage?.invoke()
            require(limit > 0)
            val matching = records.entries.asSequence()
                .filter { it.key.first == owner }
                .filter { afterRecordKey == null || it.key.second > afterRecordKey }
                .sortedBy { it.key.second }
                .take(limit + 1)
                .toList()
            val selected = matching.take(limit).map { (address, stored) ->
                stored.toRecord(owner, address.second)
            }
            return AccountMessageArchivePage(
                records = selected,
                nextAfterRecordKey = if (matching.size > limit) {
                    selected.last().recordKey
                } else {
                    null
                },
            )
        }

        override suspend fun write(
            owner: AccountMessageArchiveOwner,
            recordKey: String,
            expectedVersion: Long?,
            bytes: ByteArray,
        ): Long {
            beforeWrite?.invoke()
            writeFailure?.let { throw it }
            val address = owner to recordKey
            val existing = records[address]
            val version = when {
                existing == null && expectedVersion == null -> 1L
                existing == null || existing.version != expectedVersion ->
                    throw AccountMessageArchiveConflictException("version mismatch")
                else -> existing.version + 1L
            }
            records[address] = Stored(version, bytes.copyOf(), clock++)
            existing?.bytes?.fill(0)
            return version
        }

        override suspend fun eraseOwner(owner: AccountMessageArchiveOwner) {
            eraseMatching { it.first == owner }
        }

        override suspend fun eraseAccount(ownerAccountId: String) {
            eraseMatching { it.first.ownerAccountId == ownerAccountId }
        }

        private fun eraseMatching(predicate: (Pair<AccountMessageArchiveOwner, String>) -> Boolean) {
            val removed = records.filterKeys(predicate)
            records.keys.removeAll(predicate)
            removed.values.forEach { it.bytes.fill(0) }
        }

        private fun Stored.toRecord(
            owner: AccountMessageArchiveOwner,
            recordKey: String,
        ) = AccountMessageArchiveRecord(
            owner = owner,
            recordKey = recordKey,
            version = version,
            bytes = bytes.copyOf(),
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    private class InMemoryAccountMessageArchivePurgeIntents :
        AccountMessageArchivePurgeIntents {
        private val queued = linkedSetOf<PendingAccountMessageArchivePurge>()

        override fun enqueue(target: SessionFence) {
            queued += PendingAccountMessageArchivePurge.from(target)
        }

        override fun complete(pending: PendingAccountMessageArchivePurge) {
            queued -= pending
        }

        override fun pending(): Set<PendingAccountMessageArchivePurge> = queued.toSet()

        override fun contains(pending: PendingAccountMessageArchivePurge): Boolean =
            pending in queued
    }

    private companion object {
        const val ACCOUNT_A = "11111111-1111-4111-8111-111111111111"
        const val ACCOUNT_B = "22222222-2222-4222-8222-222222222222"
        const val INSTALLATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DEVICE_A = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"
        const val DEVICE_B = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb"
        const val CONVERSATION_A = "33333333-3333-4333-8333-333333333333"
        const val CONVERSATION_B = "44444444-4444-4444-8444-444444444444"
        const val SERVER_MESSAGE_A = "55555555-5555-4555-8555-555555555555"
        const val SERVER_MESSAGE_B = "66666666-6666-4666-8666-666666666666"
        const val CLIENT_MESSAGE_A = "77777777-7777-4777-8777-777777777777"
        const val CLIENT_MESSAGE_B = "88888888-8888-4888-8888-888888888888"
        const val ROSTER_REVISION =
            "v1:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val SENT_AT: Instant = Instant.parse("2026-07-23T12:00:00Z")
        val ACCOUNT_A_ADDRESS = SecureMessagingCryptoAddress(ACCOUNT_A, DEVICE_A, 1)
        val ACCOUNT_B_ADDRESS = SecureMessagingCryptoAddress(ACCOUNT_B, DEVICE_B, 2)
        val DEVICE_IDENTITY = object : DeviceIdentityProvider {
            override fun registration() = DeviceRegistrationDto(
                installationId = INSTALLATION_ID,
                name = "Archive test device",
                appVersion = "test",
                osVersion = "9",
                model = "API 28",
            )
        }

        fun session(accountId: String, sessionId: String) = SessionTokens(
            accessToken = "access-$sessionId",
            refreshToken = "refresh-$sessionId",
            sessionId = sessionId,
            accountId = accountId,
            cacheScopeId = sessionId,
            profileSetupState = ProfileSetupState.COMPLETED,
        )
    }
}
