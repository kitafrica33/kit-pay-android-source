package com.kit.wallet

import com.kit.wallet.data.messaging.OpaqueCryptoBytes
import com.kit.wallet.data.messaging.SecureMessagingCryptoWireMapper
import com.kit.wallet.data.messaging.SecureMessagingEnvelopeKind
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingLocalPublicBundle
import com.kit.wallet.data.messaging.SecureMessagingPreparedEnvelope
import com.kit.wallet.data.messaging.SecureMessagingPreparedFanout
import com.kit.wallet.data.messaging.SecureMessagingPublicPrekey
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundleDto
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundlesDto
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedDeviceEnvelopeRequest
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingKeyTransparencyDto
import com.kit.wallet.data.remote.MessagingOneTimePrekeyDto
import com.kit.wallet.data.remote.MessagingOneTimePrekeyRequest
import com.kit.wallet.data.remote.MessagingPqPrekeyDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.SecureMessagingWireValidationException
import com.kit.wallet.data.remote.SecureMessagingWireValidator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingCryptoWireMapperTest {
    @Test
    fun `durably committed local bundle maps to the exact v2 publication wire shape`() = runTest {
        val bundle = localBundle()
        val activation = activation()
        val committed = commitProvisioningForTest(bundle, activation)
        val request = SecureMessagingCryptoWireMapper.publication(committed)

        assertEquals("v2", request.protocolVersion)
        assertEquals(42, request.registrationId)
        assertEquals(33, Base64.getDecoder().decode(request.identityKey).size)
        assertEquals(1, request.oneTimePrekeys.size)
        assertEquals(1_569, Base64.getDecoder().decode(request.pqPrekeys.single().publicKey).size)
        assertEquals(64, Base64.getDecoder().decode(request.pqLastResortPrekey.signature).size)
        val issued = SecureMessagingCryptoWireMapper.requirePublication(request)
        val mutableCopy = issued.request()
        @Suppress("UNCHECKED_CAST")
        val exposedPrekeys = mutableCopy.oneTimePrekeys as MutableList<MessagingOneTimePrekeyRequest>
        assertThrows(UnsupportedOperationException::class.java) {
            exposedPrekeys.clear()
        }
        assertEquals(MAPPER_BINDING, issued.provenance.binding)
        issued.provenance.assertCurrent()
        assertEquals(1, issued.request().oneTimePrekeys.size)
    }

    @Test
    fun `provisioning preflight rejects malformed engine key material before commit`() = runTest {
        val malformed = localBundle(identityKey = opaque(byteArrayOf(5, 1, 2)))
        val activation = activation()
        val failure = runCatching {
            commitProvisioningForTest(malformed, activation)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `only durably committed fanout maps one canonical envelope per roster recipient`() = runTest {
        val roster = validatedRoster()
        val activation = activation()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(roster, activation)
        val recipients = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan).recipients
        val mutableEnvelopes = recipients.addresses().map { recipient ->
            SecureMessagingPreparedEnvelope(
                recipient,
                SecureMessagingEnvelopeKind.PREKEY,
                opaque(byteArrayOf(1, 2, 3)),
            )
        }.toMutableList()
        val fanout = SecureMessagingPreparedFanout(
            conversationId = SECURE_CONVERSATION_ID,
            clientMessageId = MESSAGE_ID,
            rosterRevision = roster.rosterRevision,
            recipients = recipients,
            envelopes = mutableEnvelopes,
        )
        mutableEnvelopes.clear()

        val committed = commitEncryptedFanoutForTest(fanout, plan, activation)
        val encryptedSend = SecureMessagingCryptoWireMapper.encryption(committed)
        val issued = SecureMessagingCryptoWireMapper.requireEncryptedSend(encryptedSend)
        val request = issued.request()

        assertEquals("encrypted", request.kind)
        assertTrue(request.attachments.isEmpty())
        assertEquals(
            listOf(OWN_SECOND_DEVICE_ID, PEER_DEVICE_ONE_ID, PEER_DEVICE_TWO_ID),
            request.envelopes.map { it.recipientDeviceId },
        )
        assertTrue(request.envelopes.all { it.envelopeType == "signal-prekey-v2" })
        assertTrue(request.envelopes.all { it.ciphertext == "AQID" })

        @Suppress("UNCHECKED_CAST")
        (request.envelopes as MutableList<EncryptedDeviceEnvelopeRequest>).clear()
        assertEquals(3, issued.request().envelopes.size)

        val wrongRevision = SecureMessagingPreparedFanout(
            conversationId = fanout.conversationId,
            clientMessageId = fanout.clientMessageId,
            rosterRevision = ROSTER_REVISION,
            recipients = fanout.recipients,
            envelopes = fanout.envelopes,
        )
        assertTrue(
            runCatching { commitEncryptedFanoutForTest(wrongRevision, plan, activation) }
                .exceptionOrNull() is IllegalStateException,
        )
    }

    @Test
    fun `validated multi device roster maps every recipient except the current device`() {
        val roster = validatedRoster()
        val activation = activation()

        val recipients = SecureMessagingCryptoWireMapper
            .requireEncryptionPlan(SecureMessagingCryptoWireMapper.encryptionPlan(roster, activation))
            .recipients
            .addresses()

        assertEquals(
            listOf(OWN_SECOND_DEVICE_ID, PEER_DEVICE_ONE_ID, PEER_DEVICE_TWO_ID),
            recipients.map { it.serverDeviceId },
        )
        assertEquals(
            listOf(CURRENT_USER_ID, PEER_USER_ID, PEER_USER_ID),
            recipients.map { it.userId },
        )
        assertEquals(listOf(2, 1, 2), recipients.map { it.signalDeviceId })
    }

    @Test
    fun `validated multi device claims preserve every address key and signature byte`() = runTest {
        val roster = validatedRoster()
        val claims = consumedBundles(roster)
        val activation = activation()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(roster, activation)

        val establishment = SecureMessagingCryptoWireMapper
            .sessionEstablishment(claims, plan, activation)
        val mappedBundles = establishment.bundles()
        val mapped = mappedBundles
            .associateBy { it.address.serverDeviceId }

        assertEquals(SECURE_CONVERSATION_ID, establishment.conversationId)
        assertEquals(roster.rosterRevision, establishment.rosterRevision)
        @Suppress("UNCHECKED_CAST")
        (mappedBundles as MutableList<com.kit.wallet.data.messaging.SecureMessagingRemoteKeyBundle>).clear()
        assertEquals(3, establishment.bundles().size)
        assertTrue(
            sessionResultSubstitutionFailsForTest(
                establishment,
                plan,
                activation,
                OTHER_SECURE_CONVERSATION_ID,
            ),
        )

        claimedRosterDevices().forEach { device ->
            val deviceId = checkNotNull(device.deviceId)
            val bundle = checkNotNull(mapped[deviceId])
            val signalId = checkNotNull(device.signalDeviceId)
            val seed = deviceSeed(deviceId)
            assertEquals(device.userId, bundle.address.userId)
            assertEquals(signalId, bundle.address.signalDeviceId)
            assertEquals(device.registrationId, bundle.registrationId)
            assertArrayEquals(signalBytes(5, seed, 33), bundle.identityKey.copyBytes())
            assertEquals(1_000 + seed, bundle.signedPrekey.id)
            assertArrayEquals(signalBytes(5, seed + 1, 33), bundle.signedPrekey.publicKey.copyBytes())
            assertArrayEquals(repeatedBytes(seed + 2, 64), bundle.signedPrekey.signature?.copyBytes())
            assertEquals(2_000 + seed, bundle.oneTimePrekey?.id)
            assertArrayEquals(
                signalBytes(5, seed + 3, 33),
                bundle.oneTimePrekey?.publicKey?.copyBytes(),
            )
            assertEquals(3_000 + seed, bundle.pqPrekey.id)
            assertArrayEquals(signalBytes(8, seed + 4, 1_569), bundle.pqPrekey.publicKey.copyBytes())
            assertArrayEquals(repeatedBytes(seed + 5, 64), bundle.pqPrekey.signature?.copyBytes())
        }
    }

    @Test
    fun `hostile routing or roster key substitutions never produce a mapper input`() {
        val roster = validatedRoster()
        val valid = consumedBundleDtos()
        val first = valid.first()
        val hostileIdentity = encodedSignalValue(5, 0x7a, 33)
        val mutations = listOf(
            first.copy(userId = THIRD_USER_ID),
            first.copy(signalDeviceId = 99),
            first.copy(registrationId = 999),
            first.copy(identityKey = hostileIdentity, identityKeySha256 = digestBase64(hostileIdentity)),
            first.copy(
                signedPrekey = first.signedPrekey?.copy(signature = encodedRepeatedValue(0x7b, 64)),
            ),
            first.copy(
                pqPrekey = first.pqPrekey?.copy(publicKey = encodedSignalValue(7, 0x7c, 1_569)),
            ),
        )

        mutations.forEach { hostile ->
            val response = ConsumedMessagingKeyBundlesDto(listOf(hostile) + valid.drop(1))
            assertThrows(SecureMessagingWireValidationException::class.java) {
                SecureMessagingWireValidator.validateConsumedKeyBundles(
                    response = response,
                    authoritativeRoster = roster,
                    expectedConversationId = SECURE_CONVERSATION_ID,
                    expectedDeviceIds = claimedRosterDevices().map { checkNotNull(it.deviceId) }.toSet(),
                    currentDeviceId = CURRENT_DEVICE_ID,
                    currentUserId = CURRENT_USER_ID,
                    expectedMemberUserIds = setOf(CURRENT_USER_ID, PEER_USER_ID),
                )
            }
        }
    }

    @Test
    fun `validated roster binds the current device to the authenticated user and claim targets`() {
        val wrongOwnerDevices = rosterDeviceDtos()
            .map { device ->
                if (device.deviceId == CURRENT_DEVICE_ID) {
                    device.copy(userId = PEER_USER_ID, signalDeviceId = 3)
                } else {
                    device
                }
            }
            .sortedWith(compareBy({ it.userId }, { it.signalDeviceId }, { it.deviceId }))
        assertThrows(SecureMessagingWireValidationException::class.java) {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                rawRoster(wrongOwnerDevices),
                SECURE_CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                setOf(CURRENT_USER_ID, PEER_USER_ID),
            )
        }

        val roster = validatedRoster()
        listOf(setOf(CURRENT_DEVICE_ID), setOf(OUTSIDER_DEVICE_ID)).forEach { hostileTargets ->
            assertThrows(SecureMessagingWireValidationException::class.java) {
                SecureMessagingWireValidator.requireKeyBundleTargets(
                    roster = roster,
                    expectedConversationId = SECURE_CONVERSATION_ID,
                    currentDeviceId = CURRENT_DEVICE_ID,
                    currentUserId = CURRENT_USER_ID,
                    expectedMemberUserIds = setOf(CURRENT_USER_ID, PEER_USER_ID),
                    requestedDeviceIds = hostileTargets,
                )
            }
        }
    }

    @Test
    fun `incoming prekey and session envelopes map exact ciphertext sender and reply metadata`() {
        val activation = activation()
        val roster = validatedRoster()
        val plan = SecureMessagingCryptoWireMapper.encryptionPlan(roster, activation)
        val peerIdentityKeySha256 = checkNotNull(
            rosterDeviceDtos().single { it.deviceId == PEER_DEVICE_ONE_ID }.identityKeySha256,
        )
        listOf(
            "signal-prekey-v2" to SecureMessagingEnvelopeKind.PREKEY,
            "signal-message-v2" to SecureMessagingEnvelopeKind.SESSION,
        ).forEachIndexed { index, (wireType, expectedKind) ->
            val ciphertext = "ciphertext-$wireType".toByteArray(StandardCharsets.UTF_8)
            val message = incomingMessage(
                envelopeType = wireType,
                ciphertext = ciphertext,
                index = index,
                rosterRevision = roster.rosterRevision,
                senderIdentityKeySha256 = peerIdentityKeySha256,
            )
            val validated = SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message,
                SECURE_CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )

            val request = SecureMessagingCryptoWireMapper.decryptionRequest(validated, plan, activation)
            val issued = SecureMessagingCryptoWireMapper.requireDecryptionRequest(request)

            assertEquals(message.id, issued.messageId)
            assertEquals(message.clientMessageId, issued.clientMessageId)
            assertEquals(SECURE_CONVERSATION_ID, issued.conversationId)
            assertEquals(roster.rosterRevision, issued.rosterRevision)
            assertEquals(PEER_USER_ID, issued.sender.userId)
            assertEquals(PEER_DEVICE_ONE_ID, issued.sender.serverDeviceId)
            assertEquals(1, issued.sender.signalDeviceId)
            assertEquals(601, issued.senderRegistrationId)
            assertEquals(peerIdentityKeySha256, issued.senderIdentityKeySha256)
            assertEquals(REPLY_MESSAGE_ID, issued.replyToMessageId)
            assertEquals(expectedKind, issued.envelopeKind)
            assertArrayEquals(ciphertext, issued.copyCiphertextBytes())
        }
    }

    private fun activation() = SecureMessagingLifecycleGuard().let { lifecycle ->
        lifecycle.activationCapability(lifecycle.beginSession(MAPPER_BINDING))
    }

    private fun validatedRoster() = rosterDeviceDtos().let { devices ->
        val roster = rawRoster(devices)
        SecureMessagingWireValidator.validateAuthoritativeRoster(
            roster = roster,
            expectedConversationId = SECURE_CONVERSATION_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            currentUserId = CURRENT_USER_ID,
            expectedMemberUserIds = setOf(CURRENT_USER_ID, PEER_USER_ID),
        )
    }

    private fun rawRoster(devices: List<MessagingDeviceRosterEntryDto>): MessagingDeviceRosterDto {
        val hash = sha256(canonicalRosterBytes(devices))
        return MessagingDeviceRosterDto(
            conversationId = SECURE_CONVERSATION_ID,
            rosterRevision = "v1:sha256:$hash",
            rosterHash = hash,
            hashAlgorithm = "sha256",
            devices = devices,
        )
    }

    private fun consumedBundles(
        roster: com.kit.wallet.data.remote.ValidatedMessagingDeviceRoster,
    ) = SecureMessagingWireValidator.validateConsumedKeyBundles(
        response = ConsumedMessagingKeyBundlesDto(consumedBundleDtos()),
        authoritativeRoster = roster,
        expectedConversationId = SECURE_CONVERSATION_ID,
        expectedDeviceIds = claimedRosterDevices().map { checkNotNull(it.deviceId) }.toSet(),
        currentDeviceId = CURRENT_DEVICE_ID,
        currentUserId = CURRENT_USER_ID,
        expectedMemberUserIds = setOf(CURRENT_USER_ID, PEER_USER_ID),
    )

    private fun rosterDeviceDtos(): List<MessagingDeviceRosterEntryDto> = listOf(
        rosterDevice(CURRENT_DEVICE_ID, CURRENT_USER_ID, 1, 501, 0x10),
        rosterDevice(OWN_SECOND_DEVICE_ID, CURRENT_USER_ID, 2, 502, 0x20),
        rosterDevice(PEER_DEVICE_ONE_ID, PEER_USER_ID, 1, 601, 0x30),
        rosterDevice(PEER_DEVICE_TWO_ID, PEER_USER_ID, 2, 602, 0x40),
    )

    private fun claimedRosterDevices(): List<MessagingDeviceRosterEntryDto> = rosterDeviceDtos().drop(1)

    private fun rosterDevice(
        deviceId: String,
        userId: String,
        signalDeviceId: Int,
        registrationId: Int,
        seed: Int,
    ): MessagingDeviceRosterEntryDto {
        val identityKey = encodedSignalValue(5, seed, 33)
        val signedKey = encodedSignalValue(5, seed + 1, 33)
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
                signature = encodedRepeatedValue(seed + 2, 64),
            ),
            publishedAt = TIMESTAMP,
            rotatedAt = null,
            identityKeyChangedAt = TIMESTAMP,
            bundleVersionChangedAt = TIMESTAMP,
        )
    }

    private fun consumedBundleDtos(): List<ConsumedMessagingKeyBundleDto> = claimedRosterDevices().map { device ->
        val deviceId = checkNotNull(device.deviceId)
        val seed = deviceSeed(deviceId)
        val pqKey = encodedSignalValue(8, seed + 4, 1_569)
        ConsumedMessagingKeyBundleDto(
            deviceId = deviceId,
            signalDeviceId = device.signalDeviceId,
            userId = device.userId,
            protocolVersion = "v2",
            registrationId = device.registrationId,
            identityKey = device.identityKey,
            identityKeySha256 = device.identityKeySha256,
            signedPrekey = device.signedPrekey?.copy(publicKeySha256 = null),
            oneTimePrekey = MessagingOneTimePrekeyDto(
                prekeyId = 2_000 + seed,
                publicKey = encodedSignalValue(5, seed + 3, 33),
            ),
            pqPrekey = MessagingPqPrekeyDto(
                prekeyId = 3_000 + seed,
                publicKey = pqKey,
                signature = encodedRepeatedValue(seed + 5, 64),
            ),
            bundleVersion = device.bundleVersion,
            availableOneTimePrekeys = 3,
            availableEcOneTimePrekeys = 3,
            availablePqOneTimePrekeys = 4,
            needsReplenishment = false,
            isCurrentDevice = false,
            publishedAt = TIMESTAMP,
            rotatedAt = null,
            transparency = MessagingKeyTransparencyDto(
                revision = "1",
                eventType = "device.enrolled",
                protocolVersion = "v2",
                eventHash = "a".repeat(64),
                identityKeySha256 = device.identityKeySha256,
                pqLastResortPrekeyId = 3_000 + seed,
                pqLastResortPrekeySha256 = digestBase64(pqKey),
                occurredAt = TIMESTAMP,
            ),
        )
    }

    private fun incomingMessage(
        envelopeType: String,
        ciphertext: ByteArray,
        index: Int,
        rosterRevision: String,
        senderIdentityKeySha256: String,
    ) = EncryptedMessageDto(
        id = if (index == 0) INCOMING_PREKEY_MESSAGE_ID else INCOMING_SESSION_MESSAGE_ID,
        conversationId = SECURE_CONVERSATION_ID,
        clientMessageId = if (index == 0) INCOMING_PREKEY_CLIENT_ID else INCOMING_SESSION_CLIENT_ID,
        sender = EncryptedMessageSenderDto(PEER_USER_ID, "Peer"),
        senderDeviceId = PEER_DEVICE_ONE_ID,
        senderSignalDeviceId = 1,
        senderRegistrationId = 601,
        senderProtocolVersion = "v2",
        senderBundleVersion = 2,
        senderIdentityKeySha256 = senderIdentityKeySha256,
        rosterRevision = rosterRevision,
        kind = ENCRYPTED_MESSAGE_KIND,
        replyToMessageId = REPLY_MESSAGE_ID,
        envelope = EncryptedMessageEnvelopeDto(
            recipientDeviceId = CURRENT_DEVICE_ID,
            envelopeType = envelopeType,
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            ciphertextSha256 = sha256(ciphertext),
        ),
        attachments = emptyList(),
        reactions = emptyList(),
        sentAt = TIMESTAMP,
        revokedAt = null,
    )

    private fun canonicalRosterBytes(devices: List<MessagingDeviceRosterEntryDto>): ByteArray = buildString {
        append("{\"schema\":\"kit.messaging.device-roster.v1\",\"conversation_id\":\"")
        append(SECURE_CONVERSATION_ID)
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

    private fun deviceSeed(deviceId: String): Int = when (deviceId) {
        OWN_SECOND_DEVICE_ID -> 0x20
        PEER_DEVICE_ONE_ID -> 0x30
        PEER_DEVICE_TWO_ID -> 0x40
        else -> error("Unknown fixture device")
    }

    private fun signalBytes(type: Int, fill: Int, size: Int): ByteArray =
        ByteArray(size) { fill.toByte() }.also { it[0] = type.toByte() }

    private fun repeatedBytes(fill: Int, size: Int): ByteArray = ByteArray(size) { fill.toByte() }

    private fun encodedSignalValue(type: Int, fill: Int, size: Int): String =
        Base64.getEncoder().encodeToString(signalBytes(type, fill, size))

    private fun encodedRepeatedValue(fill: Int, size: Int): String =
        Base64.getEncoder().encodeToString(repeatedBytes(fill, size))

    private fun digestBase64(value: String): String = sha256(Base64.getDecoder().decode(value))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun localBundle(
        identityKey: OpaqueCryptoBytes = typedOpaque(5, 33),
    ) = SecureMessagingLocalPublicBundle(
        registrationId = 42,
        identityKey = identityKey,
        signedPrekey = SecureMessagingPublicPrekey(
            id = 1,
            publicKey = typedOpaque(5, 33),
            signature = typedOpaque(1, 64),
        ),
        oneTimePrekeys = listOf(
            SecureMessagingPublicPrekey(id = 2, publicKey = typedOpaque(5, 33)),
        ),
        pqPrekeys = listOf(
            SecureMessagingPublicPrekey(
                id = 3,
                publicKey = typedOpaque(8, 1_569),
                signature = typedOpaque(2, 64),
            ),
        ),
        pqLastResortPrekey = SecureMessagingPublicPrekey(
            id = 4,
            publicKey = typedOpaque(8, 1_569),
            signature = typedOpaque(3, 64),
        ),
    )

    private fun typedOpaque(type: Int, size: Int): OpaqueCryptoBytes =
        opaque(ByteArray(size).also { it[0] = type.toByte() })

    private fun opaque(bytes: ByteArray) = OpaqueCryptoBytes.copyOf(bytes)

    private companion object {
        const val MESSAGE_ID = "44444444-4444-4444-8444-444444444444"
        const val ROSTER_REVISION =
            "v1:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SECURE_CONVERSATION_ID = "50000000-0000-4000-8000-000000000000"
        const val OTHER_SECURE_CONVERSATION_ID = "51000000-0000-4000-8000-000000000000"
        const val CURRENT_USER_ID = "10000000-0000-4000-8000-000000000000"
        const val PEER_USER_ID = "20000000-0000-4000-8000-000000000000"
        const val THIRD_USER_ID = "21000000-0000-4000-8000-000000000000"
        const val CURRENT_DEVICE_ID = "30000000-0000-4000-8000-000000000000"
        const val OWN_SECOND_DEVICE_ID = "31000000-0000-4000-8000-000000000000"
        const val PEER_DEVICE_ONE_ID = "40000000-0000-4000-8000-000000000000"
        const val PEER_DEVICE_TWO_ID = "41000000-0000-4000-8000-000000000000"
        const val OUTSIDER_DEVICE_ID = "42000000-0000-4000-8000-000000000000"
        const val REPLY_MESSAGE_ID = "60000000-0000-4000-8000-000000000000"
        const val INCOMING_PREKEY_MESSAGE_ID = "61000000-0000-4000-8000-000000000000"
        const val INCOMING_SESSION_MESSAGE_ID = "62000000-0000-4000-8000-000000000000"
        const val INCOMING_PREKEY_CLIENT_ID = "71000000-0000-4000-8000-000000000000"
        const val INCOMING_SESSION_CLIENT_ID = "72000000-0000-4000-8000-000000000000"
        const val TIMESTAMP = "2026-07-20T10:00:00Z"
        val MAPPER_BINDING = SecureMessagingSessionBinding(
            sessionEpoch = "mapper-test-epoch",
            userId = CURRENT_USER_ID,
            serverDeviceId = CURRENT_DEVICE_ID,
            installationId = "mapper-test-installation",
        )
    }
}
