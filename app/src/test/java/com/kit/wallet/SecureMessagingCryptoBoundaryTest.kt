package com.kit.wallet

import com.kit.wallet.data.messaging.FailClosedSecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.OpaqueCryptoBytes
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingCommittedResult
import com.kit.wallet.data.messaging.SecureMessagingCompanionStateIntent
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingCryptoOperation
import com.kit.wallet.data.messaging.SecureMessagingCryptoTransactionState
import com.kit.wallet.data.messaging.SecureMessagingCryptoWireMapper
import com.kit.wallet.data.messaging.SecureMessagingDecryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEncryptionPlan
import com.kit.wallet.data.messaging.SecureMessagingEncryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingLocalPublicBundle
import com.kit.wallet.data.messaging.SecureMessagingPreparedEnvelope
import com.kit.wallet.data.messaging.SecureMessagingPreparedFanout
import com.kit.wallet.data.messaging.SecureMessagingProvisioningPlan
import com.kit.wallet.data.messaging.SecureMessagingPublicPrekey
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionEstablishmentRequest
import com.kit.wallet.data.messaging.SensitiveCryptoBytes
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.SecureMessagingWireValidator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingCryptoBoundaryTest {
    @Test
    fun `sensitive bytes copy on input and read then fail closed after close`() {
        val source = byteArrayOf(1, 2, 3)
        val secret = SensitiveCryptoBytes.copyOf(source)
        source.fill(9)

        val firstRead = secret.copyBytes()
        assertArrayEquals(byteArrayOf(1, 2, 3), firstRead)
        firstRead.fill(8)
        val secondRead = secret.copyBytes()
        assertNotSame(firstRead, secondRead)
        assertArrayEquals(byteArrayOf(1, 2, 3), secondRead)

        secret.close()
        secret.close()
        assertTrue(secret.isClosed)
        assertTrue(runCatching { secret.copyBytes() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `same-valued relogin cannot revive an activation capability or durable result`() = runTest {
        val first = activation()
        val transaction = FakeTransaction(
            first.capability,
            PreparedFixture.Provisioning(localBundle()),
        )
        transaction.stageProvisioning(SecureMessagingProvisioningPlan(1, 1))
        val committed = transaction.commit() as SecureMessagingCommittedResult.Provisioned

        first.lifecycle.beginErasure()
        first.lifecycle.finishErasure()
        val secondFence = first.lifecycle.beginSession(BINDING.copy())
        val secondCapability = first.lifecycle.activationCapability(secondFence)

        assertTrue(runCatching { first.lifecycle.assertCurrent(first.capability) }.isFailure)
        assertTrue(runCatching { FakeTransaction(first.capability) }.isFailure)
        assertTrue(runCatching { SecureMessagingCryptoWireMapper.publication(committed) }.isFailure)
        FakeTransaction(secondCapability).abort()
    }

    @Test
    fun `activation stages can retry in place without reviving an older fence`() {
        val active = activation()

        repeat(2) { active.lifecycle.beginCapabilityCheck(active.fence) }
        assertEquals(
            SecureMessagingRuntimeStage.CHECKING_CAPABILITIES,
            active.lifecycle.snapshot().stage,
        )
        repeat(2) { active.lifecycle.beginKeyPreparation(active.fence) }
        assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, active.lifecycle.snapshot().stage)
        repeat(2) { active.lifecycle.beginRosterSync(active.fence) }
        assertEquals(SecureMessagingRuntimeStage.SYNCING_ROSTER, active.lifecycle.snapshot().stage)
        repeat(2) { active.lifecycle.finishActivation(active.fence) }
        assertEquals(SecureMessagingRuntimeStage.READY, active.lifecycle.snapshot().stage)

        active.lifecycle.beginErasure()
        active.lifecycle.finishErasure()
        val replacement = active.lifecycle.beginSession(BINDING.copy())

        assertTrue(runCatching { active.lifecycle.beginCapabilityCheck(active.fence) }.isFailure)
        active.lifecycle.beginCapabilityCheck(replacement)
    }

    @Test
    fun `encryption uses one exact roster plan before lookup and rejects an equivalent second plan`() = runTest {
        val active = activation()
        val roster = validatedRoster()
        val firstPlan = SecureMessagingCryptoWireMapper.encryptionPlan(roster, active.capability)
        val equivalentPlan = SecureMessagingCryptoWireMapper.encryptionPlan(roster, active.capability)
        val transaction = FakeTransaction(active.capability)
        val request = encryptionRequest(equivalentPlan)

        transaction.missingSessions(firstPlan)
        val failure = runCatching {
            transaction.stageEncryption(request, outboundIntent())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(SecureMessagingCryptoTransactionState.FAULTED, transaction.state.value)
        assertEquals(1, transaction.aborts)
        assertEquals(1, transaction.wipes)
        request.close()
    }

    @Test
    fun `encryption releases only an opaque committed handle after atomic destination commit`() = runTest {
        val active = activation()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(validatedRoster(), active.capability)
        val request = encryptionRequest(plan)
        val fanout = preparedFanout(plan)
        val transaction = FakeTransaction(
            active.capability,
            PreparedFixture.Encryption(fanout),
        )

        assertTrue(transaction.missingSessions(plan).isEmpty)
        transaction.stageEncryption(request, outboundIntent())
        request.close()

        assertEquals(SecureMessagingCryptoTransactionState.STAGED, transaction.state.value)
        assertTrue(requireNotNull(transaction.stagedSecret).toString(Charsets.UTF_8).contains(TEXT))
        assertEquals(null, transaction.committedDestination)

        val committed = transaction.commit()

        assertTrue(committed is SecureMessagingCommittedResult.Encrypted)
        assertEquals("outbox:$CONVERSATION_ID" to CLIENT_MESSAGE_ID, transaction.committedDestination)
        assertEquals(1, transaction.commits)
        assertEquals(1, transaction.wipes)
        assertEquals(null, transaction.stagedSecret)
        val send = SecureMessagingCryptoWireMapper.encryption(
            committed as SecureMessagingCommittedResult.Encrypted,
        )
        assertEquals(1, SecureMessagingCryptoWireMapper.requireEncryptedSend(send).request().envelopes.size)
    }

    @Test
    fun `atomic commit failure releases no fanout and zeroizes staged plaintext`() = runTest {
        val active = activation()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(validatedRoster(), active.capability)
        val request = encryptionRequest(plan)
        val failure = IllegalStateException("atomic write failed")
        val transaction = FakeTransaction(
            active.capability,
            PreparedFixture.Encryption(preparedFanout(plan)),
        ).apply { commitFailure = failure }
        transaction.missingSessions(plan)
        transaction.stageEncryption(request, outboundIntent())
        request.close()

        val observed = runCatching { transaction.commit() }.exceptionOrNull()

        assertSame(failure, observed)
        assertEquals(SecureMessagingCryptoTransactionState.FAULTED, transaction.state.value)
        assertEquals(1, transaction.aborts)
        assertEquals(1, transaction.wipes)
        assertEquals(0, transaction.commits)
        assertEquals(null, transaction.stagedSecret)
        assertEquals(null, transaction.committedDestination)
    }

    @Test
    fun `prepared result type and metadata substitutions fail before durable commit`() = runTest {
        val active = activation()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(validatedRoster(), active.capability)
        val request = encryptionRequest(plan)
        val recipients = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan).recipients.addresses()
        val transaction = FakeTransaction(
            active.capability,
            PreparedFixture.Sessions(CONVERSATION_ID, ROSTER_REVISION, recipients),
        )
        transaction.missingSessions(plan)
        transaction.stageEncryption(request, outboundIntent())

        val failure = runCatching { transaction.commit() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, transaction.commits)
        assertEquals(1, transaction.aborts)
        request.close()
    }

    @Test
    fun `companion intent direction is enforced before crypto preparation`() = runTest {
        val active = activation()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(validatedRoster(), active.capability)
        val request = encryptionRequest(plan)
        val transaction = FakeTransaction(active.capability)
        transaction.missingSessions(plan)

        val failure = runCatching {
            transaction.stageEncryption(
                request,
                SecureMessagingCompanionStateIntent.inbound("inbox", MESSAGE_ID),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(SecureMessagingCryptoTransactionState.FAULTED, transaction.state.value)
        request.close()
    }

    @Test
    fun `decrypted plaintext is unavailable to callers until durable commit`() = runTest {
        val active = activation()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(
            validatedRoster(),
            active.capability,
        )
        val request = decryptionRequest(plan, active.capability)
        val framed = framedPlaintext(plan)
        val transaction = FakeTransaction(
            active.capability,
            PreparedFixture.Decryption(
                messageId = MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                sender = RECIPIENT,
                plaintext = framed,
            ),
        )
        transaction.stageDecryption(
            request,
            SecureMessagingCompanionStateIntent.inbound("inbox:$CONVERSATION_ID", MESSAGE_ID),
        )

        assertEquals(null, transaction.committedDestination)
        val committed = transaction.commit() as SecureMessagingCommittedResult.Decrypted

        assertEquals(TEXT, committed.copyText())
        val first = committed.copyPlaintextBytes()
        first.fill(0)
        assertArrayEquals(framed, committed.copyPlaintextBytes())
        committed.close()
        assertTrue(committed.isClosed)
        assertTrue(runCatching { committed.copyText() }.isFailure)
    }

    @Test
    fun `provisioning counts are defensively checked before durable commit`() = runTest {
        val active = activation()
        val transaction = FakeTransaction(
            active.capability,
            PreparedFixture.Provisioning(localBundle()),
        )
        transaction.stageProvisioning(SecureMessagingProvisioningPlan(2, 2))

        assertTrue(runCatching { transaction.commit() }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, transaction.commits)
    }

    @Test
    fun `quarantine and erasure permanently invalidate capabilities and fences`() {
        val active = activation()
        active.lifecycle.quarantine(active.fence, SecureMessagingQuarantineReason.SIGNATURE_FAILURE)

        assertEquals(SecureMessagingRuntimeStage.QUARANTINED, active.lifecycle.snapshot().stage)
        assertTrue(runCatching { active.lifecycle.assertCurrent(active.fence) }.isFailure)
        assertTrue(runCatching { active.lifecycle.assertCurrent(active.capability) }.isFailure)
        assertEquals(BINDING, active.lifecycle.beginErasure())
        active.lifecycle.finishErasure()
    }

    private class FakeTransaction(
        activation: SecureMessagingActivationCapability,
        var fixture: PreparedFixture? = null,
    ) : FailClosedSecureMessagingCryptoTransaction(activation) {
        var stagedSecret: ByteArray? = null
        var committedDestination: Pair<String, String>? = null
        var missingAddresses: List<SecureMessagingCryptoAddress> = emptyList()
        var aborts = 0
        var commits = 0
        var wipes = 0
        var commitFailure: Throwable? = null
        private var preparedDestination: Pair<String, String>? = null

        override suspend fun stageProvisioningMaterial(plan: SecureMessagingProvisioningPlan) = Unit

        override suspend fun stageSessionMaterial(request: SecureMessagingSessionEstablishmentRequest) = Unit

        override suspend fun findMissingSessionAddresses(
            plan: SecureMessagingEncryptionPlan,
            candidates: List<SecureMessagingCryptoAddress>,
        ): Collection<SecureMessagingCryptoAddress> = missingAddresses

        override suspend fun stageEncryptionMaterial(request: SecureMessagingEncryptionRequest) {
            stagedSecret = request.copyPlaintextBytes()
        }

        override suspend fun stageDecryptionMaterial(request: SecureMessagingDecryptionRequest) = Unit

        override suspend fun prepareStaged(
            operation: SecureMessagingCryptoOperation,
            companionStateIntent: SecureMessagingCompanionStateIntent?,
        ): PreparedCommit {
            preparedDestination = companionStateIntent?.let(::companionStateDestination)?.let {
                it.namespace to it.recordKey
            }
            return when (val value = checkNotNull(fixture) { "No prepared fixture" }) {
                is PreparedFixture.Provisioning -> preparedProvisioning(value.bundle)
                is PreparedFixture.Sessions -> preparedSessionsEstablished(
                    value.conversationId,
                    value.rosterRevision,
                    value.addresses,
                )
                is PreparedFixture.Encryption -> preparedEncryption(value.fanout)
                is PreparedFixture.Decryption -> preparedDecryption(
                    value.messageId,
                    value.conversationId,
                    value.sender,
                    value.plaintext,
                )
            }
        }

        override suspend fun commitPrepared(
            operation: SecureMessagingCryptoOperation,
            preparedResult: PreparedCommit,
        ) {
            commitFailure?.let { throw it }
            commits++
            committedDestination = preparedDestination
        }

        override suspend fun abortStaged() {
            aborts++
        }

        override fun wipeStagedSecrets() {
            wipes++
            stagedSecret?.fill(0)
            stagedSecret = null
        }
    }

    private sealed interface PreparedFixture {
        data class Provisioning(val bundle: SecureMessagingLocalPublicBundle) : PreparedFixture
        data class Encryption(val fanout: SecureMessagingPreparedFanout) : PreparedFixture
        data class Sessions(
            val conversationId: String,
            val rosterRevision: String,
            val addresses: List<SecureMessagingCryptoAddress>,
        ) : PreparedFixture
        data class Decryption(
            val messageId: String,
            val conversationId: String,
            val sender: SecureMessagingCryptoAddress,
            val plaintext: ByteArray,
        ) : PreparedFixture
    }

    private data class ActiveFixture(
        val lifecycle: SecureMessagingLifecycleGuard,
        val fence: com.kit.wallet.data.messaging.SecureMessagingSessionFence,
        val capability: SecureMessagingActivationCapability,
    )

    private fun activation(): ActiveFixture {
        val lifecycle = SecureMessagingLifecycleGuard()
        val fence = lifecycle.beginSession(BINDING)
        return ActiveFixture(lifecycle, fence, lifecycle.activationCapability(fence))
    }

    private fun encryptionRequest(plan: SecureMessagingEncryptionPlan) =
        SecureMessagingEncryptionRequest(
            plan = plan,
            clientMessageId = CLIENT_MESSAGE_ID,
            text = TEXT,
        )

    private fun preparedFanout(plan: SecureMessagingEncryptionPlan): SecureMessagingPreparedFanout {
        val issued = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
        val envelopes = issued.recipients.addresses().map { recipient ->
            SecureMessagingPreparedEnvelope(
                recipient = recipient,
                kind = SecureMessagingEnvelopeKind.PREKEY,
                ciphertext = OpaqueCryptoBytes.copyOf(byteArrayOf(10, 11, 12)),
            )
        }
        return SecureMessagingPreparedFanout(
            conversationId = issued.conversationId,
            clientMessageId = CLIENT_MESSAGE_ID,
            rosterRevision = issued.rosterRevision,
            recipients = issued.recipients,
            envelopes = envelopes,
        )
    }

    private fun outboundIntent() = SecureMessagingCompanionStateIntent.outbound(
        namespace = "outbox:$CONVERSATION_ID",
        recordKey = CLIENT_MESSAGE_ID,
    )

    private fun framedPlaintext(plan: SecureMessagingEncryptionPlan): ByteArray {
        val rosterRevision = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan).rosterRevision
        return ("{\"schema\":\"kit.messaging.content.v1\",\"type\":\"text\"," +
            "\"client_message_id\":\"$CLIENT_MESSAGE_ID\"," +
            "\"conversation_id\":\"$CONVERSATION_ID\"," +
            "\"roster_revision\":\"$rosterRevision\"," +
            "\"sender_user_id\":\"${RECIPIENT.userId}\"," +
            "\"sender_device_id\":\"${RECIPIENT.serverDeviceId}\"," +
            "\"sender_signal_device_id\":${RECIPIENT.signalDeviceId}," +
            "\"reply_to_message_id\":null,\"text\":\"$TEXT\"}")
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun decryptionRequest(
        plan: SecureMessagingEncryptionPlan,
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingDecryptionRequest {
        val ciphertext = byteArrayOf(1, 2, 3)
        val planSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
        val senderIdentityKeySha256 = checkNotNull(
            rosterDevices().single { it.deviceId == RECIPIENT.serverDeviceId }.identityKeySha256,
        )
        val message = EncryptedMessageDto(
            id = MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            clientMessageId = CLIENT_MESSAGE_ID,
            sender = EncryptedMessageSenderDto(RECIPIENT.userId, "Peer"),
            senderDeviceId = RECIPIENT.serverDeviceId,
            senderSignalDeviceId = RECIPIENT.signalDeviceId,
            senderRegistrationId = 601,
            senderProtocolVersion = "v2",
            senderBundleVersion = 1,
            senderIdentityKeySha256 = senderIdentityKeySha256,
            rosterRevision = planSnapshot.rosterRevision,
            kind = "encrypted",
            replyToMessageId = null,
            envelope = EncryptedMessageEnvelopeDto(
                recipientDeviceId = BINDING.serverDeviceId,
                envelopeType = "signal-prekey-v2",
                ciphertext = Base64.getEncoder().encodeToString(ciphertext),
                ciphertextSha256 = sha256(ciphertext),
            ),
            attachments = emptyList(),
            reactions = emptyList(),
            sentAt = TIMESTAMP,
            revokedAt = null,
        )
        val validated = SecureMessagingWireValidator.validateIncomingEncryptedMessage(
            message,
            CONVERSATION_ID,
            BINDING.serverDeviceId,
        )
        return SecureMessagingCryptoWireMapper.decryptionRequest(validated, plan, activation)
    }

    private fun validatedRoster() = rosterDevices().let { devices ->
        val hash = sha256(canonicalRosterBytes(devices))
        SecureMessagingWireValidator.validateAuthoritativeRoster(
            roster = MessagingDeviceRosterDto(
                conversationId = CONVERSATION_ID,
                rosterRevision = "v1:sha256:$hash",
                rosterHash = hash,
                hashAlgorithm = "sha256",
                devices = devices,
            ),
            expectedConversationId = CONVERSATION_ID,
            currentDeviceId = BINDING.serverDeviceId,
            currentUserId = BINDING.userId,
            expectedMemberUserIds = setOf(BINDING.userId, RECIPIENT.userId),
        )
    }

    private fun rosterDevices() = listOf(
        rosterDevice(BINDING.serverDeviceId, BINDING.userId, 1, 501, 0x10),
        rosterDevice(RECIPIENT.serverDeviceId, RECIPIENT.userId, 2, 601, 0x20),
    )

    private fun rosterDevice(
        deviceId: String,
        userId: String,
        signalDeviceId: Int,
        registrationId: Int,
        seed: Int,
    ): MessagingDeviceRosterEntryDto {
        val identity = encodedSignalValue(5, seed, 33)
        val signed = encodedSignalValue(5, seed + 1, 33)
        return MessagingDeviceRosterEntryDto(
            deviceId = deviceId,
            signalDeviceId = signalDeviceId,
            userId = userId,
            registrationId = registrationId,
            protocolVersion = "v2",
            bundleVersion = seed,
            identityKey = identity,
            identityKeySha256 = digestBase64(identity),
            signedPrekey = MessagingSignedPrekeyDto(
                prekeyId = 1_000 + seed,
                publicKey = signed,
                publicKeySha256 = digestBase64(signed),
                signature = Base64.getEncoder().encodeToString(ByteArray(64) { seed.toByte() }),
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

    private fun localBundle(): SecureMessagingLocalPublicBundle {
        val signature = OpaqueCryptoBytes.copyOf(ByteArray(64) { 1 })
        return SecureMessagingLocalPublicBundle(
            registrationId = 42,
            identityKey = typedOpaque(5, 33),
            signedPrekey = SecureMessagingPublicPrekey(1, typedOpaque(5, 33), signature),
            oneTimePrekeys = listOf(SecureMessagingPublicPrekey(2, typedOpaque(5, 33))),
            pqPrekeys = listOf(SecureMessagingPublicPrekey(3, typedOpaque(8, 1_569), signature)),
            pqLastResortPrekey = SecureMessagingPublicPrekey(4, typedOpaque(8, 1_569), signature),
        )
    }

    private fun typedOpaque(type: Int, size: Int): OpaqueCryptoBytes =
        OpaqueCryptoBytes.copyOf(ByteArray(size).also { it[0] = type.toByte() })

    private fun encodedSignalValue(type: Int, fill: Int, size: Int): String =
        Base64.getEncoder().encodeToString(
            ByteArray(size) { fill.toByte() }.also { it[0] = type.toByte() },
        )

    private fun digestBase64(value: String): String = sha256(Base64.getDecoder().decode(value))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val TEXT = "hello securely"
        const val CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
        const val CLIENT_MESSAGE_ID = "22222222-2222-4222-8222-222222222222"
        const val MESSAGE_ID = "33333333-3333-4333-8333-333333333333"
        const val ROSTER_REVISION =
            "v1:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TIMESTAMP = "2026-07-20T10:00:00Z"
        val BINDING = SecureMessagingSessionBinding(
            sessionEpoch = "epoch-1",
            userId = "44444444-4444-4444-8444-444444444444",
            serverDeviceId = "55555555-5555-4555-8555-555555555555",
            installationId = "66666666-6666-4666-8666-666666666666",
        )
        val RECIPIENT = SecureMessagingCryptoAddress(
            userId = "77777777-7777-4777-8777-777777777777",
            serverDeviceId = "88888888-8888-4888-8888-888888888888",
            signalDeviceId = 2,
        )
    }
}
