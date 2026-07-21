package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalCompanionDirection
import com.kit.wallet.data.messaging.LibSignalCompanionRecord
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.LibSignalPersistedEnvelope
import com.kit.wallet.data.messaging.LibSignalSecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingCommittedResult
import com.kit.wallet.data.messaging.SecureMessagingCompanionStateIntent
import com.kit.wallet.data.messaging.SecureMessagingCryptoTransactionState
import com.kit.wallet.data.messaging.SecureMessagingCryptographicFailureException
import com.kit.wallet.data.messaging.SecureMessagingCryptoWireMapper
import com.kit.wallet.data.messaging.SecureMessagingEncryptionPlan
import com.kit.wallet.data.messaging.SecureMessagingEncryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingProvisioningPlan
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordPage
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.requireDurableLibSignalCompanionRecord
import com.kit.wallet.data.messaging.validateSecureMessagingNamespacePageRequest
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundleDto
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundlesDto
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingKeyTransparencyDto
import com.kit.wallet.data.remote.MessagingOneTimePrekeyDto
import com.kit.wallet.data.remote.MessagingPqPrekeyDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.SecureMessagingWireValidator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibSignalSecureMessagingCryptoEngineTest {
    @Test
    fun `authenticated peer revocation atomically retires its persisted ratchet`() = runTest {
        val fixture = provisionedPair()
        establish(fixture.alice, fixture.bob)
        val before = fixture.alice.engine.openTransaction(fixture.alice.activation)
        assertTrue(before.missingSessions(fixture.alice.plan).isEmpty)
        before.abort()

        fixture.alice.engine.retireRemoteDevices(
            activation = fixture.alice.activation,
            affectedUserId = fixture.bob.userId,
            affectedServerDeviceId = fixture.bob.deviceId,
        )

        fixture.alice.engine = LibSignalSecureMessagingCryptoEngine(fixture.alice.stateStore)
        val after = fixture.alice.engine.openTransaction(fixture.alice.activation)
        assertEquals(
            listOf(fixture.bob.deviceId),
            after.missingSessions(fixture.alice.plan).addresses().map { it.serverDeviceId },
        )
        after.abort()
    }

    @Test
    fun `durable companion pages are ordered bounded timestamped and clean corrupt plaintext`() = runTest {
        val fixture = provisionedPair()
        establish(fixture.alice, fixture.bob)
        encrypt(fixture.alice, CLIENT_MESSAGE_FOUR, "four", replyTo = null)
        encrypt(fixture.alice, CLIENT_MESSAGE_ONE, "one", replyTo = null)
        encrypt(fixture.alice, CLIENT_MESSAGE_THREE, "three", replyTo = null)
        val reader = LibSignalCompanionStateReader(fixture.alice.stateStore)

        val first = reader.readPage("outbox", afterRecordKey = null, limit = 2)
        assertEquals(
            listOf(CLIENT_MESSAGE_ONE, CLIENT_MESSAGE_THREE),
            first.records().map(LibSignalCompanionRecord::recordKey),
        )
        assertEquals(CLIENT_MESSAGE_THREE, first.nextAfterRecordKey)
        assertTrue(first.records().all { it.updatedAtEpochMillis > 0 })
        first.records().forEach { record ->
            val verified = requireDurableLibSignalCompanionRecord(record)
            assertEquals(record.recordKey, verified.recordKey)
            assertEquals(record.authenticatedText, verified.authenticatedText)
        }

        val second = reader.readPage(
            "outbox",
            afterRecordKey = checkNotNull(first.nextAfterRecordKey),
            limit = 2,
        )
        assertEquals(listOf(CLIENT_MESSAGE_FOUR), second.records().map { it.recordKey })
        assertNull(second.nextAfterRecordKey)

        listOf(
            Triple("bad namespace", null, 1),
            Triple("outbox", "bad cursor", 1),
            Triple("outbox", null, 0),
            Triple("outbox", null, 101),
        ).forEach { (namespace, cursor, limit) ->
            assertTrue(runCatching { reader.readPage(namespace, cursor, limit) }.isFailure)
        }

        val forged = object : LibSignalCompanionRecord {
            override val recordNamespace = "outbox"
            override val recordKey = CLIENT_MESSAGE_ONE
            override val recordVersion = 1L
            override val updatedAtEpochMillis = 1L
            override val direction = LibSignalCompanionDirection.OUTBOUND
            override val messageId = CLIENT_MESSAGE_ONE
            override val clientMessageId = CLIENT_MESSAGE_ONE
            override val conversationId = CONVERSATION_ID
            override val rosterRevision = "v1:sha256:${"a".repeat(64)}"
            override val sender = SecureMessagingCryptoWireMapper
                .requireEncryptionPlan(fixture.alice.plan)
                .sender
            override val replyToMessageId: String? = null
            override val authenticatedText = "forged"
            override fun ciphertextFanout(): List<LibSignalPersistedEnvelope> = emptyList()
        }
        assertTrue(runCatching { requireDurableLibSignalCompanionRecord(forged) }.isFailure)

        fixture.alice.stateStore.putRaw("outbox", CORRUPT_MESSAGE, byteArrayOf(1, 2, 3))
        assertTrue(
            runCatching {
                reader.readPage("outbox", afterRecordKey = CLIENT_MESSAGE_ONE, limit = 10)
            }.isFailure,
        )
        assertEquals(3, fixture.alice.stateStore.lastPagePlaintextBuffers.size)
        assertTrue(
            fixture.alice.stateStore.lastPagePlaintextBuffers
                .all { buffer -> buffer.all { it == 0.toByte() } },
        )
    }

    @Test
    fun `failed publication can reprovision same identity without deleting inbound prekeys`() = runTest {
        val active = activeDevice(
            userId = ALICE_USER_ID,
            deviceId = ALICE_DEVICE_ID,
            signalDeviceId = 1,
            epoch = "publication-recovery-epoch",
        )
        val first = publish(active)
        val before = checkNotNull(first.engine.localEnrollment(first.activation))

        val recovery = first.engine.openTransaction(first.activation)
        recovery.stageProvisioning(before.replenishmentPlan(2, 2))
        val secondCommitted = recovery.commit() as SecureMessagingCommittedResult.Provisioned
        val second = SecureMessagingCryptoWireMapper.publication(secondCommitted)
        val after = checkNotNull(first.engine.localEnrollment(first.activation))

        assertEquals(first.publication.registrationId, second.registrationId)
        assertEquals(first.publication.identityKey, second.identityKey)
        assertTrue(after.ecOneTimePrekeyIds().containsAll(before.ecOneTimePrekeyIds()))
        assertTrue(after.signedPrekeyIds().containsAll(before.signedPrekeyIds()))
        assertTrue(after.pqPrekeyIds().containsAll(before.pqPrekeyIds()))
        assertTrue(after.pqLastResortPrekeyIds().containsAll(before.pqLastResortPrekeyIds()))
    }

    @Test
    fun `replenishment rotates signed and PQ last resort IDs monotonically for backend acceptance`() =
        runTest {
            val active = activeDevice(
                userId = ALICE_USER_ID,
                deviceId = ALICE_DEVICE_ID,
                signalDeviceId = 1,
                epoch = "monotonic-rotation-epoch",
            )
            val published = publish(active)
            var previous = published.publication

            repeat(3) {
                val enrollment = checkNotNull(
                    published.engine.localEnrollment(published.activation),
                )
                val transaction = published.engine.openTransaction(published.activation)
                transaction.stageProvisioning(enrollment.replenishmentPlan(1, 1))
                val committed = transaction.commit() as SecureMessagingCommittedResult.Provisioned
                val rotated = SecureMessagingCryptoWireMapper.publication(committed)

                assertEquals(previous.registrationId, rotated.registrationId)
                assertEquals(previous.identityKey, rotated.identityKey)
                assertTrue(rotated.signedPrekey.prekeyId > previous.signedPrekey.prekeyId)
                assertTrue(
                    rotated.pqLastResortPrekey.prekeyId >
                        previous.pqLastResortPrekey.prekeyId,
                )
                previous = rotated
            }
        }

    @Test
    fun `two durable stores interoperate across PQXDH reply duplicate rejection and process restart`() = runTest {
        val fixture = provisionedPair()
        establish(fixture.alice, fixture.bob)

        fixture.alice.engine = LibSignalSecureMessagingCryptoEngine(fixture.alice.stateStore)
        val first = encrypt(
            fixture.alice,
            CLIENT_MESSAGE_ONE,
            "hello from Alice",
            replyTo = null,
        )
        assertEquals(SecureMessagingEnvelopeKind.PREKEY, envelopeKind(first))
        val persistedOutbox = checkNotNull(
            LibSignalCompanionStateReader(fixture.alice.stateStore).read("outbox", CLIENT_MESSAGE_ONE),
        )
        assertEquals(LibSignalCompanionDirection.OUTBOUND, persistedOutbox.direction)
        assertEquals("outbox", persistedOutbox.recordNamespace)
        assertEquals(CLIENT_MESSAGE_ONE, persistedOutbox.recordKey)
        assertTrue(persistedOutbox.recordVersion > 0)
        assertEquals("hello from Alice", persistedOutbox.authenticatedText)
        assertEquals(1, persistedOutbox.ciphertextFanout().size)
        val originalWire = SecureMessagingCryptoWireMapper.requireEncryptedSend(
            SecureMessagingCryptoWireMapper.encryption(first),
        ).request()
        val retriedWire = SecureMessagingCryptoWireMapper.requireEncryptedSend(
            SecureMessagingCryptoWireMapper.retryEncryption(
                persistedOutbox,
                fixture.alice.plan,
            ),
        ).request()
        assertEquals(originalWire, retriedWire)

        val firstIncoming = decryptionRequest(
            sender = fixture.alice,
            recipient = fixture.bob,
            committed = first,
            messageId = SERVER_MESSAGE_ONE,
        )
        val firstPlaintext = decrypt(
            fixture.bob,
            firstIncoming,
            SERVER_MESSAGE_ONE,
        )
        assertEquals("hello from Alice", firstPlaintext.copyText())
        firstPlaintext.close()

        val persistedInbox = checkNotNull(
            LibSignalCompanionStateReader(fixture.bob.stateStore).read("inbox", SERVER_MESSAGE_ONE),
        )
        assertEquals(LibSignalCompanionDirection.INBOUND, persistedInbox.direction)
        assertEquals("hello from Alice", persistedInbox.authenticatedText)
        assertTrue(persistedInbox.ciphertextFanout().isEmpty())

        fixture.bob.engine = LibSignalSecureMessagingCryptoEngine(fixture.bob.stateStore)
        val reply = encrypt(
            fixture.bob,
            CLIENT_MESSAGE_TWO,
            "hello from Bob",
            replyTo = SERVER_MESSAGE_ONE,
        )
        assertEquals(SecureMessagingEnvelopeKind.SESSION, envelopeKind(reply))

        fixture.alice.engine = LibSignalSecureMessagingCryptoEngine(fixture.alice.stateStore)
        val replyIncoming = decryptionRequest(
            sender = fixture.bob,
            recipient = fixture.alice,
            committed = reply,
            messageId = SERVER_MESSAGE_TWO,
        )
        val replyPlaintext = decrypt(
            fixture.alice,
            replyIncoming,
            SERVER_MESSAGE_TWO,
        )
        assertEquals("hello from Bob", replyPlaintext.copyText())
        replyPlaintext.close()

        val enrollment = checkNotNull(
            fixture.alice.engine.localEnrollment(fixture.alice.activation),
        )
        assertEquals(fixture.alice.publication.registrationId, enrollment.registrationId)
        assertEquals(identityDigest(fixture.alice.publication), enrollment.identityKeySha256)
        assertTrue(enrollment.signedPrekeyIds().isNotEmpty())
        assertTrue(enrollment.pqLastResortPrekeyIds().isNotEmpty())

        val duplicate = fixture.alice.engine.openTransaction(fixture.alice.activation)
        duplicate.stageDecryption(
            replyIncoming,
            SecureMessagingCompanionStateIntent.inbound("inbox-duplicate", SERVER_MESSAGE_TWO),
        )
        val duplicateFailure = runCatching { duplicate.commit() }.exceptionOrNull()
        assertTrue(duplicateFailure is SecureMessagingCryptographicFailureException)
        assertEquals(
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
            (duplicateFailure as SecureMessagingCryptographicFailureException).quarantineReason,
        )
        assertEquals(SecureMessagingCryptoTransactionState.FAULTED, duplicate.state.value)
        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, fixture.alice.lifecycle.snapshot().stage)
        assertEquals(
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
            fixture.alice.lifecycle.snapshot().quarantineReason,
        )
    }

    @Test
    fun `atomic state failure publishes neither ratchet nor companion record`() = runTest {
        val fixture = provisionedPair()
        establish(fixture.alice, fixture.bob)
        val beforeVersion = fixture.alice.stateStore.version(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY)
        fixture.alice.stateStore.failNextBatch = true

        val transaction = fixture.alice.engine.openTransaction(fixture.alice.activation)
        assertTrue(transaction.missingSessions(fixture.alice.plan).isEmpty)
        val request = SecureMessagingEncryptionRequest(
            fixture.alice.plan,
            CLIENT_MESSAGE_THREE,
            "must remain atomic",
        )
        try {
            transaction.stageEncryption(
                request,
                SecureMessagingCompanionStateIntent.outbound("outbox", CLIENT_MESSAGE_THREE),
            )
        } finally {
            request.close()
        }

        assertTrue(runCatching { transaction.commit() }.isFailure)
        assertEquals(beforeVersion, fixture.alice.stateStore.version(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY))
        assertNull(fixture.alice.stateStore.read("outbox", CLIENT_MESSAGE_THREE))
        assertEquals(SecureMessagingCryptoTransactionState.FAULTED, transaction.state.value)

        fixture.alice.engine = LibSignalSecureMessagingCryptoEngine(fixture.alice.stateStore)
        val retry = encrypt(
            fixture.alice,
            CLIENT_MESSAGE_THREE,
            "must remain atomic",
            replyTo = null,
        )
        assertEquals(SecureMessagingEnvelopeKind.PREKEY, envelopeKind(retry))
    }

    @Test
    fun `prekey decryption rejects authoritative registration or identity that differs from ciphertext`() = runTest {
        val fixture = provisionedPair()
        establish(fixture.alice, fixture.bob)
        val committed = encrypt(
            fixture.alice,
            CLIENT_MESSAGE_FOUR,
            "reject substituted identity",
            replyTo = null,
        )

        val wrongRegistration = if (fixture.alice.publication.registrationId == 16_380) {
            16_379
        } else {
            fixture.alice.publication.registrationId + 1
        }
        val registrationRoster = fixture.rosterEntries.map { entry ->
            if (entry.deviceId == fixture.alice.deviceId) {
                entry.copy(registrationId = wrongRegistration)
            } else {
                entry
            }
        }
        assertEngineRejects(
            sender = fixture.alice,
            recipient = fixture.bob,
            committed = committed,
            recipientPlan = planFor(fixture.bob, registrationRoster),
            senderRegistrationId = wrongRegistration,
            senderIdentityDigest = identityDigest(fixture.alice.publication),
            messageId = WRONG_REGISTRATION_MESSAGE,
        )

        val identityFixture = provisionedPair()
        establish(identityFixture.alice, identityFixture.bob)
        val identityCommitted = encrypt(
            identityFixture.alice,
            CLIENT_MESSAGE_FOUR,
            "reject substituted identity",
            replyTo = null,
        )
        val wrongIdentity = identityFixture.bob.publication.identityKey
        val wrongIdentityDigest = sha256(Base64.getDecoder().decode(wrongIdentity))
        val identityRoster = identityFixture.rosterEntries.map { entry ->
            if (entry.deviceId == identityFixture.alice.deviceId) {
                entry.copy(identityKey = wrongIdentity, identityKeySha256 = wrongIdentityDigest)
            } else {
                entry
            }
        }
        assertEngineRejects(
            sender = identityFixture.alice,
            recipient = identityFixture.bob,
            committed = identityCommitted,
            recipientPlan = planFor(identityFixture.bob, identityRoster),
            senderRegistrationId = identityFixture.alice.publication.registrationId,
            senderIdentityDigest = wrongIdentityDigest,
            messageId = WRONG_IDENTITY_MESSAGE,
        )
    }

    @Test
    fun `same identity registration rotation retires an existing session for fresh PQXDH`() = runTest {
        val fixture = provisionedPair()
        establish(fixture.alice, fixture.bob)
        val replacementRegistration = if (fixture.bob.publication.registrationId == 16_380) {
            16_379
        } else {
            fixture.bob.publication.registrationId + 1
        }
        val rotatedRoster = fixture.rosterEntries.map { entry ->
            if (entry.deviceId == fixture.bob.deviceId) {
                entry.copy(registrationId = replacementRegistration)
            } else {
                entry
            }
        }
        val rotatedPlan = planFor(fixture.alice, rotatedRoster)

        val transaction = fixture.alice.engine.openTransaction(fixture.alice.activation)
        val missing = transaction.missingSessions(rotatedPlan)

        assertEquals(listOf(fixture.bob.deviceId), missing.addresses().map { it.serverDeviceId })
        assertTrue(fixture.alice.lifecycle.snapshot().stage != SecureMessagingRuntimeStage.QUARANTINED)
        transaction.abort()
    }

    @Test
    fun `identity change on an existing roster address quarantines before key consumption`() = runTest {
        val fixture = provisionedPair()
        establish(fixture.alice, fixture.bob)
        val replacementIdentity = fixture.alice.publication.identityKey
        val replacementDigest = sha256(Base64.getDecoder().decode(replacementIdentity))
        val changedRoster = fixture.rosterEntries.map { entry ->
            if (entry.deviceId == fixture.bob.deviceId) {
                entry.copy(
                    identityKey = replacementIdentity,
                    identityKeySha256 = replacementDigest,
                )
            } else {
                entry
            }
        }
        val changedPlan = planFor(fixture.alice, changedRoster)
        val transaction = fixture.alice.engine.openTransaction(fixture.alice.activation)

        val failure = runCatching { transaction.missingSessions(changedPlan) }.exceptionOrNull()

        assertTrue(failure is SecureMessagingCryptographicFailureException)
        assertEquals(
            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
            (failure as SecureMessagingCryptographicFailureException).quarantineReason,
        )
        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, fixture.alice.lifecycle.snapshot().stage)
    }

    @Test
    fun `invalid authoritative prekey signature is typed and quarantines before session commit`() =
        runTest {
            val fixture = provisionedPair()
            val invalidSignature = Base64.getEncoder().encodeToString(ByteArray(64) { 0x55 })
            val changedEntries = fixture.rosterEntries.map { entry ->
                if (entry.deviceId == fixture.bob.deviceId) {
                    entry.copy(
                        signedPrekey = checkNotNull(entry.signedPrekey).copy(
                            signature = invalidSignature,
                        ),
                    )
                } else {
                    entry
                }
            }
            val plan = planFor(fixture.alice, changedEntries)
            val transaction = fixture.alice.engine.openTransaction(fixture.alice.activation)
            assertEquals(
                listOf(fixture.bob.deviceId),
                transaction.missingSessions(plan).addresses().map { it.serverDeviceId },
            )
            val roster = SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster = rawRoster(changedEntries),
                expectedConversationId = CONVERSATION_ID,
                currentDeviceId = fixture.alice.deviceId,
                currentUserId = fixture.alice.userId,
                expectedMemberUserIds = setOf(ALICE_USER_ID, BOB_USER_ID),
            )
            val badBundle = consumedBundle(fixture.bob).let { bundle ->
                bundle.copy(
                    signedPrekey = checkNotNull(bundle.signedPrekey).copy(
                        signature = invalidSignature,
                    ),
                )
            }
            val claims = SecureMessagingWireValidator.validateConsumedKeyBundles(
                response = ConsumedMessagingKeyBundlesDto(listOf(badBundle)),
                authoritativeRoster = roster,
                expectedConversationId = CONVERSATION_ID,
                expectedDeviceIds = setOf(fixture.bob.deviceId),
                currentDeviceId = fixture.alice.deviceId,
                currentUserId = fixture.alice.userId,
                expectedMemberUserIds = setOf(ALICE_USER_ID, BOB_USER_ID),
            )
            transaction.stageSessionEstablishment(
                SecureMessagingCryptoWireMapper.sessionEstablishment(
                    claims,
                    plan,
                    fixture.alice.activation,
                ),
            )

            val failure = runCatching { transaction.commit() }.exceptionOrNull()

            assertTrue(failure is SecureMessagingCryptographicFailureException)
            assertEquals(
                SecureMessagingQuarantineReason.SIGNATURE_FAILURE,
                (failure as SecureMessagingCryptographicFailureException).quarantineReason,
            )
            assertEquals(
                SecureMessagingRuntimeStage.QUARANTINED,
                fixture.alice.lifecycle.snapshot().stage,
            )
        }

    @Test
    fun `corrupt authenticated protocol state quarantines before a transaction opens`() = runTest {
        val active = activeDevice(
            userId = ALICE_USER_ID,
            deviceId = ALICE_DEVICE_ID,
            signalDeviceId = 1,
            epoch = "corrupt-state-epoch",
        )
        val published = publish(active)
        published.stateStore.putRaw(
            PROTOCOL_NAMESPACE,
            PROTOCOL_RECORD_KEY,
            byteArrayOf(1, 2, 3),
        )

        val failure = runCatching {
            published.engine.openTransaction(published.activation)
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingCryptographicFailureException)
        assertEquals(
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
            (failure as SecureMessagingCryptographicFailureException).quarantineReason,
        )
        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, published.lifecycle.snapshot().stage)
    }

    private suspend fun assertEngineRejects(
        sender: PublishedDevice,
        recipient: PublishedDevice,
        committed: SecureMessagingCommittedResult.Encrypted,
        recipientPlan: SecureMessagingEncryptionPlan,
        senderRegistrationId: Int,
        senderIdentityDigest: String,
        messageId: String,
    ) {
        val request = decryptionRequest(
            sender = sender,
            recipient = recipient,
            committed = committed,
            messageId = messageId,
            recipientPlan = recipientPlan,
            senderRegistrationId = senderRegistrationId,
            senderIdentityDigest = senderIdentityDigest,
        )
        val transaction = recipient.engine.openTransaction(recipient.activation)
        transaction.stageDecryption(
            request,
            SecureMessagingCompanionStateIntent.inbound("rejected", messageId),
        )
        val failure = runCatching { transaction.commit() }.exceptionOrNull()
        assertTrue(failure is SecureMessagingCryptographicFailureException)
        assertEquals(
            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
            (failure as SecureMessagingCryptographicFailureException).quarantineReason,
        )
        assertEquals(SecureMessagingCryptoTransactionState.FAULTED, transaction.state.value)
        assertNull(recipient.stateStore.read("rejected", messageId))
        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, recipient.lifecycle.snapshot().stage)
    }

    private suspend fun provisionedPair(): PairFixture {
        val alice = activeDevice(
            userId = ALICE_USER_ID,
            deviceId = ALICE_DEVICE_ID,
            signalDeviceId = 1,
            epoch = "alice-epoch",
        )
        val bob = activeDevice(
            userId = BOB_USER_ID,
            deviceId = BOB_DEVICE_ID,
            signalDeviceId = 1,
            epoch = "bob-epoch",
        )
        val publishedAlice = publish(alice)
        val publishedBob = publish(bob)
        val entries = listOf(rosterEntry(publishedAlice), rosterEntry(publishedBob))
            .sortedWith(compareBy({ it.userId }, { it.signalDeviceId }, { it.deviceId }))
        publishedAlice.plan = planFor(publishedAlice, entries)
        publishedBob.plan = planFor(publishedBob, entries)
        return PairFixture(publishedAlice, publishedBob, entries)
    }

    private fun activeDevice(
        userId: String,
        deviceId: String,
        signalDeviceId: Int,
        epoch: String,
    ): ActiveDevice {
        val stateStore = InMemoryStateStore()
        val lifecycle = SecureMessagingLifecycleGuard()
        val binding = SecureMessagingSessionBinding(
            sessionEpoch = epoch,
            userId = userId,
            serverDeviceId = deviceId,
            installationId = "$epoch-installation",
        )
        val fence = lifecycle.beginSession(binding)
        return ActiveDevice(
            userId,
            deviceId,
            signalDeviceId,
            stateStore,
            LibSignalSecureMessagingCryptoEngine(stateStore),
            lifecycle.activationCapability(fence),
            lifecycle,
        )
    }

    private suspend fun publish(device: ActiveDevice): PublishedDevice {
        val transaction = device.engine.openTransaction(device.activation)
        transaction.stageProvisioning(
            SecureMessagingProvisioningPlan(
                ecOneTimePrekeyCount = 1,
                pqOneTimePrekeyCount = 1,
            ),
        )
        val committed = transaction.commit() as SecureMessagingCommittedResult.Provisioned
        return PublishedDevice(
            userId = device.userId,
            deviceId = device.deviceId,
            signalDeviceId = device.signalDeviceId,
            stateStore = device.stateStore,
            engine = device.engine,
            activation = device.activation,
            lifecycle = device.lifecycle,
            publication = SecureMessagingCryptoWireMapper.publication(committed),
        )
    }

    private fun planFor(
        device: PublishedDevice,
        entries: List<MessagingDeviceRosterEntryDto>,
    ): SecureMessagingEncryptionPlan {
        val raw = rawRoster(entries)
        val validated = SecureMessagingWireValidator.validateAuthoritativeRoster(
            roster = raw,
            expectedConversationId = CONVERSATION_ID,
            currentDeviceId = device.deviceId,
            currentUserId = device.userId,
            expectedMemberUserIds = setOf(ALICE_USER_ID, BOB_USER_ID),
        )
        return SecureMessagingCryptoWireMapper.encryptionPlan(validated, device.activation)
    }

    private suspend fun establish(sender: PublishedDevice, recipient: PublishedDevice) {
        val transaction = sender.engine.openTransaction(sender.activation)
        val missing = transaction.missingSessions(sender.plan)
        assertEquals(listOf(recipient.deviceId), missing.addresses().map { it.serverDeviceId })
        val roster = SecureMessagingWireValidator.validateAuthoritativeRoster(
            roster = rawRoster(listOf(rosterEntry(sender), rosterEntry(recipient))),
            expectedConversationId = CONVERSATION_ID,
            currentDeviceId = sender.deviceId,
            currentUserId = sender.userId,
            expectedMemberUserIds = setOf(ALICE_USER_ID, BOB_USER_ID),
        )
        val claims = SecureMessagingWireValidator.validateConsumedKeyBundles(
            response = ConsumedMessagingKeyBundlesDto(listOf(consumedBundle(recipient))),
            authoritativeRoster = roster,
            expectedConversationId = CONVERSATION_ID,
            expectedDeviceIds = setOf(recipient.deviceId),
            currentDeviceId = sender.deviceId,
            currentUserId = sender.userId,
            expectedMemberUserIds = setOf(ALICE_USER_ID, BOB_USER_ID),
        )
        transaction.stageSessionEstablishment(
            SecureMessagingCryptoWireMapper.sessionEstablishment(
                claims,
                sender.plan,
                sender.activation,
            ),
        )
        val result = transaction.commit() as SecureMessagingCommittedResult.SessionsEstablished
        assertEquals(listOf(recipient.deviceId), result.addresses().map { it.serverDeviceId })
    }

    private suspend fun encrypt(
        sender: PublishedDevice,
        clientMessageId: String,
        text: String,
        replyTo: String?,
    ): SecureMessagingCommittedResult.Encrypted {
        val transaction = sender.engine.openTransaction(sender.activation)
        assertTrue(transaction.missingSessions(sender.plan).isEmpty)
        val request = SecureMessagingEncryptionRequest(
            sender.plan,
            clientMessageId,
            text,
            replyTo,
        )
        try {
            transaction.stageEncryption(
                request,
                SecureMessagingCompanionStateIntent.outbound("outbox", clientMessageId),
            )
        } finally {
            request.close()
        }
        return transaction.commit() as SecureMessagingCommittedResult.Encrypted
    }

    private suspend fun decrypt(
        recipient: PublishedDevice,
        request: com.kit.wallet.data.messaging.SecureMessagingDecryptionRequest,
        messageId: String,
    ): SecureMessagingCommittedResult.Decrypted {
        val transaction = recipient.engine.openTransaction(recipient.activation)
        transaction.stageDecryption(
            request,
            SecureMessagingCompanionStateIntent.inbound("inbox", messageId),
        )
        return transaction.commit() as SecureMessagingCommittedResult.Decrypted
    }

    private fun decryptionRequest(
        sender: PublishedDevice,
        recipient: PublishedDevice,
        committed: SecureMessagingCommittedResult.Encrypted,
        messageId: String,
        recipientPlan: SecureMessagingEncryptionPlan = recipient.plan,
        senderRegistrationId: Int = sender.publication.registrationId,
        senderIdentityDigest: String = identityDigest(sender.publication),
    ): com.kit.wallet.data.messaging.SecureMessagingDecryptionRequest {
        val send = SecureMessagingCryptoWireMapper.requireEncryptedSend(
            SecureMessagingCryptoWireMapper.encryption(committed),
        ).request()
        val envelope = send.envelopes.single { it.recipientDeviceId == recipient.deviceId }
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
        val recipientPlanSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(recipientPlan)
        val dto = EncryptedMessageDto(
            id = messageId,
            conversationId = CONVERSATION_ID,
            clientMessageId = send.clientMessageId,
            sender = EncryptedMessageSenderDto(sender.userId, "Sender"),
            senderDeviceId = sender.deviceId,
            senderSignalDeviceId = sender.signalDeviceId,
            senderRegistrationId = senderRegistrationId,
            senderProtocolVersion = "v2",
            senderBundleVersion = 1,
            senderIdentityKeySha256 = senderIdentityDigest,
            rosterRevision = recipientPlanSnapshot.rosterRevision,
            kind = ENCRYPTED_MESSAGE_KIND,
            replyToMessageId = send.replyToMessageId,
            envelope = EncryptedMessageEnvelopeDto(
                recipientDeviceId = recipient.deviceId,
                envelopeType = envelope.envelopeType,
                ciphertext = envelope.ciphertext,
                ciphertextSha256 = sha256(ciphertext),
            ),
            attachments = emptyList(),
            reactions = emptyList(),
            sentAt = TIMESTAMP,
            revokedAt = null,
        )
        val validated = SecureMessagingWireValidator.validateIncomingEncryptedMessage(
            dto,
            CONVERSATION_ID,
            recipient.deviceId,
        )
        return SecureMessagingCryptoWireMapper.decryptionRequest(
            validated,
            recipientPlan,
            recipient.activation,
        )
    }

    private fun envelopeKind(committed: SecureMessagingCommittedResult.Encrypted): SecureMessagingEnvelopeKind {
        val envelope = SecureMessagingCryptoWireMapper.requireEncryptedSend(
            SecureMessagingCryptoWireMapper.encryption(committed),
        ).request().envelopes.single()
        return when (envelope.envelopeType) {
            "signal-prekey-v2" -> SecureMessagingEnvelopeKind.PREKEY
            "signal-message-v2" -> SecureMessagingEnvelopeKind.SESSION
            else -> error("Unexpected test envelope type")
        }
    }

    private fun rosterEntry(device: PublishedDevice): MessagingDeviceRosterEntryDto {
        val publication = device.publication
        return MessagingDeviceRosterEntryDto(
            deviceId = device.deviceId,
            signalDeviceId = device.signalDeviceId,
            userId = device.userId,
            registrationId = publication.registrationId,
            protocolVersion = "v2",
            bundleVersion = 1,
            identityKey = publication.identityKey,
            identityKeySha256 = identityDigest(publication),
            signedPrekey = MessagingSignedPrekeyDto(
                prekeyId = publication.signedPrekey.prekeyId,
                publicKey = publication.signedPrekey.publicKey,
                publicKeySha256 = sha256(Base64.getDecoder().decode(publication.signedPrekey.publicKey)),
                signature = publication.signedPrekey.signature,
            ),
            publishedAt = TIMESTAMP,
            rotatedAt = null,
            identityKeyChangedAt = TIMESTAMP,
            bundleVersionChangedAt = TIMESTAMP,
        )
    }

    private fun consumedBundle(device: PublishedDevice): ConsumedMessagingKeyBundleDto {
        val publication = device.publication
        val ec = publication.oneTimePrekeys.first()
        val pq = publication.pqPrekeys.first()
        return ConsumedMessagingKeyBundleDto(
            deviceId = device.deviceId,
            signalDeviceId = device.signalDeviceId,
            userId = device.userId,
            protocolVersion = "v2",
            registrationId = publication.registrationId,
            identityKey = publication.identityKey,
            identityKeySha256 = identityDigest(publication),
            signedPrekey = MessagingSignedPrekeyDto(
                prekeyId = publication.signedPrekey.prekeyId,
                publicKey = publication.signedPrekey.publicKey,
                publicKeySha256 = null,
                signature = publication.signedPrekey.signature,
            ),
            oneTimePrekey = MessagingOneTimePrekeyDto(ec.prekeyId, ec.publicKey),
            pqPrekey = MessagingPqPrekeyDto(pq.prekeyId, pq.publicKey, pq.signature),
            bundleVersion = 1,
            availableOneTimePrekeys = 1,
            availableEcOneTimePrekeys = 1,
            availablePqOneTimePrekeys = 1,
            needsReplenishment = false,
            isCurrentDevice = false,
            publishedAt = TIMESTAMP,
            rotatedAt = null,
            transparency = MessagingKeyTransparencyDto(
                revision = "1",
                eventType = "device.enrolled",
                protocolVersion = "v2",
                eventHash = "a".repeat(64),
                identityKeySha256 = identityDigest(publication),
                pqLastResortPrekeyId = publication.pqLastResortPrekey.prekeyId,
                pqLastResortPrekeySha256 = sha256(
                    Base64.getDecoder().decode(publication.pqLastResortPrekey.publicKey),
                ),
                occurredAt = TIMESTAMP,
            ),
        )
    }

    private fun rawRoster(entries: List<MessagingDeviceRosterEntryDto>): MessagingDeviceRosterDto {
        val sorted = entries.sortedWith(compareBy({ it.userId }, { it.signalDeviceId }, { it.deviceId }))
        val hash = sha256(canonicalRosterBytes(sorted))
        return MessagingDeviceRosterDto(
            conversationId = CONVERSATION_ID,
            rosterRevision = "v1:sha256:$hash",
            rosterHash = hash,
            hashAlgorithm = "sha256",
            devices = sorted,
        )
    }

    private fun canonicalRosterBytes(devices: List<MessagingDeviceRosterEntryDto>): ByteArray = buildString {
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

    private fun identityDigest(publication: com.kit.wallet.data.messaging.SecureMessagingKeyPublication): String =
        sha256(Base64.getDecoder().decode(publication.identityKey))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class ActiveDevice(
        val userId: String,
        val deviceId: String,
        val signalDeviceId: Int,
        val stateStore: InMemoryStateStore,
        val engine: LibSignalSecureMessagingCryptoEngine,
        val activation: SecureMessagingActivationCapability,
        val lifecycle: SecureMessagingLifecycleGuard,
    )

    private class PublishedDevice(
        val userId: String,
        val deviceId: String,
        val signalDeviceId: Int,
        val stateStore: InMemoryStateStore,
        var engine: LibSignalSecureMessagingCryptoEngine,
        val activation: SecureMessagingActivationCapability,
        val lifecycle: SecureMessagingLifecycleGuard,
        val publication: com.kit.wallet.data.messaging.SecureMessagingKeyPublication,
    ) {
        lateinit var plan: SecureMessagingEncryptionPlan
    }

    private data class PairFixture(
        val alice: PublishedDevice,
        val bob: PublishedDevice,
        val rosterEntries: List<MessagingDeviceRosterEntryDto>,
    )

    private class InMemoryStateStore : SecureMessagingStateStore {
        private data class Stored(
            val version: Long,
            val bytes: ByteArray,
            val updatedAtEpochMillis: Long,
        )

        private val records = mutableMapOf<Pair<String, String>, Stored>()
        private var clock = 1_000L
        var failNextBatch = false
        var lastPagePlaintextBuffers: List<ByteArray> = emptyList()
            private set

        override suspend fun read(namespace: String, recordKey: String): SecureMessagingRecord? =
            records[namespace to recordKey]?.let {
                SecureMessagingRecord(
                    namespace,
                    recordKey,
                    it.version,
                    it.bytes.copyOf(),
                    it.updatedAtEpochMillis,
                )
            }

        override suspend fun readNamespacePage(
            namespace: String,
            afterRecordKey: String?,
            limit: Int,
        ): SecureMessagingRecordPage {
            validateSecureMessagingNamespacePageRequest(namespace, afterRecordKey, limit)
            val pageRecords = records.entries.asSequence()
                .filter { it.key.first == namespace }
                .filter { afterRecordKey == null || it.key.second > afterRecordKey }
                .sortedBy { it.key.second }
                .take(limit + 1)
                .toList()
            val selected = pageRecords.take(limit).map { (address, stored) ->
                SecureMessagingRecord(
                    namespace = address.first,
                    recordKey = address.second,
                    version = stored.version,
                    bytes = stored.bytes.copyOf(),
                    updatedAtEpochMillis = stored.updatedAtEpochMillis,
                )
            }
            lastPagePlaintextBuffers = selected.map(SecureMessagingRecord::bytes)
            return SecureMessagingRecordPage(
                records = selected,
                nextAfterRecordKey = if (pageRecords.size > limit) {
                    selected.last().recordKey
                } else {
                    null
                },
            )
        }

        override suspend fun write(
            namespace: String,
            recordKey: String,
            expectedVersion: Long?,
            bytes: ByteArray,
        ): SecureMessagingRecordVersion = writeBatch(
            listOf(SecureMessagingStateWrite(namespace, recordKey, expectedVersion, bytes)),
        ).single()

        override suspend fun writeBatch(
            writes: List<SecureMessagingStateWrite>,
        ): List<SecureMessagingRecordVersion> {
            if (failNextBatch) {
                failNextBatch = false
                throw IllegalStateException("injected atomic write failure")
            }
            require(writes.isNotEmpty())
            require(writes.map { it.namespace to it.recordKey }.distinct().size == writes.size)
            val versions = writes.map { write ->
                val current = records[write.namespace to write.recordKey]
                when {
                    current == null && write.expectedVersion == null -> 1L
                    current == null || current.version != write.expectedVersion ->
                        throw SecureMessagingStateConflictException("version mismatch")
                    else -> current.version + 1
                }
            }
            writes.zip(versions).forEach { (write, version) ->
                records.put(
                    write.namespace to write.recordKey,
                    Stored(version, write.copyBytes(), clock++),
                )?.bytes?.fill(0)
            }
            return writes.zip(versions).map { (write, version) ->
                SecureMessagingRecordVersion(write.namespace, write.recordKey, version)
            }
        }

        override suspend fun deleteNamespace(namespace: String) {
            records.keys.removeAll { it.first == namespace }
        }

        override suspend fun eraseAll() {
            records.values.forEach { it.bytes.fill(0) }
            records.clear()
        }

        fun version(namespace: String, recordKey: String): Long? =
            records[namespace to recordKey]?.version

        fun putRaw(namespace: String, recordKey: String, bytes: ByteArray) {
            records.put(
                namespace to recordKey,
                Stored(version = 1, bytes = bytes.copyOf(), updatedAtEpochMillis = clock++),
            )?.bytes?.fill(0)
        }
    }

    private companion object {
        const val ALICE_USER_ID = "10000000-0000-4000-8000-000000000001"
        const val BOB_USER_ID = "20000000-0000-4000-8000-000000000002"
        const val ALICE_DEVICE_ID = "30000000-0000-4000-8000-000000000003"
        const val BOB_DEVICE_ID = "40000000-0000-4000-8000-000000000004"
        const val CONVERSATION_ID = "50000000-0000-4000-8000-000000000005"
        const val CLIENT_MESSAGE_ONE = "60000000-0000-4000-8000-000000000006"
        const val CLIENT_MESSAGE_TWO = "70000000-0000-4000-8000-000000000007"
        const val CLIENT_MESSAGE_THREE = "80000000-0000-4000-8000-000000000008"
        const val CORRUPT_MESSAGE = "88000000-0000-4000-8000-000000000088"
        const val CLIENT_MESSAGE_FOUR = "90000000-0000-4000-8000-000000000009"
        const val SERVER_MESSAGE_ONE = "a0000000-0000-4000-8000-00000000000a"
        const val SERVER_MESSAGE_TWO = "b0000000-0000-4000-8000-00000000000b"
        const val WRONG_REGISTRATION_MESSAGE = "c0000000-0000-4000-8000-00000000000c"
        const val WRONG_IDENTITY_MESSAGE = "d0000000-0000-4000-8000-00000000000d"
        const val TIMESTAMP = "2026-07-20T12:00:00Z"
        const val PROTOCOL_NAMESPACE = "libsignal-v2"
        const val PROTOCOL_RECORD_KEY = "active-protocol-state"
    }
}
