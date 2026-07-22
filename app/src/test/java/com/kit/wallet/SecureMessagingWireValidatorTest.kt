package com.kit.wallet

import com.kit.wallet.data.remote.ConsumedMessagingKeyBundleDto
import com.kit.wallet.data.remote.ConsumedMessagingKeyBundlesDto
import com.kit.wallet.data.remote.ENCRYPTED_ATTACHMENT_MESSAGE_KIND
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedAttachmentDto
import com.kit.wallet.data.remote.EncryptedAttachmentRequest
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.MessagingDeviceRosterDto
import com.kit.wallet.data.remote.MessagingDeviceRosterEntryDto
import com.kit.wallet.data.remote.MessagingKeyStatusDto
import com.kit.wallet.data.remote.MessagingKeyTransparencyDto
import com.kit.wallet.data.remote.MessagingOneTimePrekeyDto
import com.kit.wallet.data.remote.MessagingPqPrekeyDto
import com.kit.wallet.data.remote.MessagingSignedPrekeyDto
import com.kit.wallet.data.remote.MessagingSyncEventDataDto
import com.kit.wallet.data.remote.MessagingSyncEventDto
import com.kit.wallet.data.remote.SecureMessagingWireValidationException
import com.kit.wallet.data.remote.SecureMessagingWireValidator
import com.kit.wallet.data.remote.ValidatedConsumedMessagingKeyBundles
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.Assert.assertThrows

class SecureMessagingWireValidatorTest {
    @Test
    fun `exact enrolled and unenrolled v2 key status shapes are accepted`() {
        assertEquals(
            enrolledStatus(),
            SecureMessagingWireValidator.validateKeyStatus(enrolledStatus(), CURRENT_DEVICE_ID),
        )
        val unenrolled = MessagingKeyStatusDto(
            enrolled = false,
            enrollmentEpoch = 1,
            protocolVersion = "v2",
            availableOneTimePrekeys = 0,
            availableEcOneTimePrekeys = 0,
            availablePqOneTimePrekeys = 0,
            replenishAt = 20,
            needsReplenishment = true,
        )
        assertEquals(
            unenrolled,
            SecureMessagingWireValidator.validateKeyStatus(unenrolled, CURRENT_DEVICE_ID),
        )
    }

    @Test
    fun `key status rejects nullable downgraded and internally inconsistent state`() {
        assertRejected { SecureMessagingWireValidator.validateKeyStatus(MessagingKeyStatusDto(), CURRENT_DEVICE_ID) }
        assertRejected {
            SecureMessagingWireValidator.validateKeyStatus(
                enrolledStatus().copy(protocolVersion = "v1"),
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateKeyStatus(
                enrolledStatus().copy(signalDeviceId = 128),
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateKeyStatus(
                enrolledStatus().copy(availableOneTimePrekeys = 4),
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateKeyStatus(
                enrolledStatus().copy(identityKeySha256 = "A".repeat(64)),
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateKeyStatus(
                MessagingKeyStatusDto(
                    enrolled = false,
                    enrollmentEpoch = 1,
                    protocolVersion = "v2",
                    deviceId = CURRENT_DEVICE_ID,
                    availableOneTimePrekeys = 0,
                    availableEcOneTimePrekeys = 0,
                    availablePqOneTimePrekeys = 0,
                    replenishAt = 20,
                    needsReplenishment = true,
                ),
                CURRENT_DEVICE_ID,
            )
        }
    }

    @Test
    fun `canonical two-user v2 roster is accepted`() {
        val roster = authoritativeRoster()
        val validated = SecureMessagingWireValidator.validateAuthoritativeRoster(
            roster,
            CONVERSATION_ID,
            CURRENT_DEVICE_ID,
            CURRENT_USER_ID,
            DIRECT_MEMBER_IDS,
        )

        assertEquals(CONVERSATION_ID, validated.conversationId)
        assertEquals("v1:sha256:$CANONICAL_ROSTER_HASH", validated.rosterRevision)
        assertEquals(CURRENT_DEVICE_ID, validated.currentDeviceId)
        assertEquals(DIRECT_MEMBER_IDS, validated.memberUserIds())
        assertEquals(
            listOf(CURRENT_DEVICE_ID, OTHER_DEVICE_ID),
            validated.devices().map { it.deviceId },
        )
    }

    @Test
    fun `roster rejects null entries duplicate addresses missing current device and content tampering`() {
        val roster = authoritativeRoster()
        assertRejected {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster.copy(devices = listOf(roster.devices?.first(), null)),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                DIRECT_MEMBER_IDS,
            )
        }
        val devices = requireNotNull(roster.devices).map(::requireNotNull)
        assertRejected {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster.copy(devices = listOf(devices[0], devices[1].copy(deviceId = devices[0].deviceId))),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                DIRECT_MEMBER_IDS,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster.copy(
                    devices = listOf(
                        devices[0],
                        devices[1].copy(userId = devices[0].userId, signalDeviceId = devices[0].signalDeviceId),
                    ),
                ),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                DIRECT_MEMBER_IDS,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster.copy(devices = listOf(devices[1], devices[1].copy(deviceId = THIRD_DEVICE_ID))),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                DIRECT_MEMBER_IDS,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster.copy(devices = devices.map { it.copy(protocolVersion = "v1") }),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                DIRECT_MEMBER_IDS,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster.copy(devices = listOf(devices[0], devices[1].copy(bundleVersion = 3))),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                DIRECT_MEMBER_IDS,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster.copy(rosterHash = "f".repeat(64)),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
                CURRENT_USER_ID,
                DIRECT_MEMBER_IDS,
            )
        }
    }

    @Test
    fun `consumed v2 bundle accepts exact EC and Kyber1024 wire values`() {
        val response = consumedBundles()
        val validated = validateConsumed(response)

        assertEquals(listOf(OTHER_DEVICE_ID), validated.bundles().map { it.deviceId })
        assertEquals(OTHER_USER_ID, validated.bundles().single().userId)
        assertEquals(2, validated.bundles().single().signalDeviceId)
        assertEquals(43, validated.bundles().single().registrationId)
    }

    @Test
    fun `consumed bundles reject nulls target injection and hostile PQ material`() {
        assertRejected {
            validateConsumed(
                ConsumedMessagingKeyBundlesDto(bundles = null),
            )
        }
        val bundle = requireNotNull(consumedBundles().bundles?.single())
        assertRejected {
            validateConsumed(
                ConsumedMessagingKeyBundlesDto(listOf(bundle.copy(deviceId = THIRD_DEVICE_ID))),
            )
        }
        assertRejected {
            validateConsumed(
                ConsumedMessagingKeyBundlesDto(listOf(bundle.copy(pqPrekey = null))),
            )
        }
        assertRejected {
            validateConsumed(
                ConsumedMessagingKeyBundlesDto(
                    listOf(
                        bundle.copy(
                            pqPrekey = bundle.pqPrekey?.copy(publicKey = signalValue(7, 0x31, 1569)),
                        ),
                    ),
                ),
            )
        }
        assertRejected {
            validateConsumed(
                ConsumedMessagingKeyBundlesDto(
                    listOf(
                        bundle.copy(
                            pqPrekey = bundle.pqPrekey?.copy(publicKey = signalValue(8, 0x31, 1568)),
                        ),
                    ),
                ),
            )
        }
        assertRejected {
            validateConsumed(
                ConsumedMessagingKeyBundlesDto(
                    listOf(bundle.copy(pqPrekey = bundle.pqPrekey?.copy(signature = null))),
                ),
            )
        }
    }

    @Test
    fun `consumed bundle rejects a key rotation after the authoritative roster snapshot`() {
        val bundle = requireNotNull(consumedBundles().bundles?.single())

        assertRejected {
            validateConsumed(
                ConsumedMessagingKeyBundlesDto(
                    listOf(
                        bundle.copy(
                            bundleVersion = 3,
                            identityKey = signalValue(5, 0x41, 33),
                            identityKeySha256 = digestBase64(signalValue(5, 0x41, 33)),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `incoming envelope is released only after route snapshot and ciphertext digest validation`() {
        val message = incomingMessage()

        val validated = SecureMessagingWireValidator.validateIncomingEncryptedMessage(
            message,
            CONVERSATION_ID,
            CURRENT_DEVICE_ID,
        )

        assertEquals(MESSAGE_ID, validated.messageId)
        assertEquals(CLIENT_MESSAGE_ID, validated.clientMessageId)
        assertEquals(OTHER_USER_ID, validated.senderUserId)
        assertEquals(ENCRYPTED_MESSAGE_KIND, validated.kind)
        assertEquals(null, validated.replyToMessageId)
        assertEquals("signal-prekey-v2", validated.envelopeType)
        assertEquals("c".repeat(64), validated.senderIdentityKeySha256)
        assertArrayEquals(CIPHERTEXT, validated.ciphertextBytes())
    }

    @Test
    fun `incoming attachment metadata is validated and preserved for plaintext binding`() {
        val attachment = EncryptedAttachmentDto(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            storageKey = "0f0e0d0c-0b0a-4a0b-8c0d-0e0f10111213",
            mediaType = "image/jpeg",
            byteSize = 4_096,
            ciphertextSha256 = "ab".repeat(32),
        )
        val message = incomingMessage().copy(
            kind = ENCRYPTED_ATTACHMENT_MESSAGE_KIND,
            attachments = listOf(attachment),
        )

        val validated = SecureMessagingWireValidator.validateIncomingEncryptedMessage(
            message,
            CONVERSATION_ID,
            CURRENT_DEVICE_ID,
        )

        assertEquals(
            listOf(
                EncryptedAttachmentRequest(
                    id = checkNotNull(attachment.id),
                    storageKey = checkNotNull(attachment.storageKey),
                    mediaType = checkNotNull(attachment.mediaType),
                    byteSize = checkNotNull(attachment.byteSize),
                    ciphertextSha256 = checkNotNull(attachment.ciphertextSha256),
                ),
            ),
            validated.attachments(),
        )
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(
                    attachments = listOf(attachment.copy(storageKey = "not-a-storage-key")),
                ),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
    }

    @Test
    fun `incoming message rejects null envelope route substitution downgrade and digest tampering`() {
        val message = incomingMessage()
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(envelope = null),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(senderProtocolVersion = "v1"),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(envelope = message.envelope?.copy(recipientDeviceId = OTHER_DEVICE_ID)),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(envelope = message.envelope?.copy(envelopeType = "signal-message-v1")),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(envelope = message.envelope?.copy(ciphertextSha256 = "0".repeat(64))),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(envelope = message.envelope?.copy(ciphertext = "not base64")),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(rosterRevision = null),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                message.copy(senderIdentityKeySha256 = null),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
    }

    @Test
    fun `sync event validates outer routing before exposing encrypted bytes`() {
        val event = incomingEvent()
        assertArrayEquals(
            CIPHERTEXT,
            SecureMessagingWireValidator.validateIncomingEncryptedMessageEvent(
                event,
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            ).ciphertextBytes(),
        )

        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessageEvent(
                event.copy(resourceId = THIRD_MESSAGE_ID),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateIncomingEncryptedMessageEvent(
                event.copy(data = null),
                CONVERSATION_ID,
                CURRENT_DEVICE_ID,
            )
        }
    }

    @Test
    fun `device lifecycle event can only force an authoritative roster refresh`() {
        val event = MessagingSyncEventDto(
            id = "9",
            type = "device.enrolled",
            conversationId = CONVERSATION_ID,
            resourceType = "messaging_device",
            resourceId = OTHER_DEVICE_ID,
            data = MessagingSyncEventDataDto(
                deviceId = OTHER_DEVICE_ID,
                userId = OTHER_USER_ID,
                enrollmentEpoch = 2,
                signalDeviceId = 2,
                registrationId = 43,
                previousRegistrationId = 42,
                protocolVersion = "v2",
                previousProtocolVersion = "v1",
                bundleVersion = 2,
                identityKeySha256 = digestBase64(signalValue(5, 0x21, 33)),
                previousIdentityKeySha256 = digestBase64(signalValue(5, 0x22, 33)),
                rosterRefreshRequired = true,
                transitionedAt = TIMESTAMP,
                transitionHash = "d".repeat(64),
            ),
            occurredAt = TIMESTAMP,
        )

        val validated = SecureMessagingWireValidator.validateDeviceLifecycleEvent(
            event,
            CONVERSATION_ID,
        )

        assertEquals(CONVERSATION_ID, validated.conversationId)
        assertEquals("device.enrolled", validated.eventType)
        assertEquals(OTHER_USER_ID, validated.userId)
        assertEquals(OTHER_DEVICE_ID, validated.deviceId)
        assertEquals(2L, validated.enrollmentEpoch)
        assertEquals(2, validated.signalDeviceId)
        assertEquals(43, validated.registrationId)
        assertEquals(42, validated.previousRegistrationId)
        assertEquals("v2", validated.protocolVersion)
        assertEquals("v1", validated.previousProtocolVersion)
        assertEquals(2, validated.bundleVersion)
        assertEquals(event.data?.identityKeySha256, validated.identityKeySha256)
        assertEquals(
            event.data?.previousIdentityKeySha256,
            validated.previousIdentityKeySha256,
        )
        assertEquals(Instant.parse(TIMESTAMP), validated.transitionedAt)
        assertEquals("d".repeat(64), validated.transitionHash)
        assertEquals(
            "device.enrolled",
            SecureMessagingWireValidator.validateDeviceLifecycleEvent(
                event.copy(data = event.data?.copy(protocolVersion = "v1")),
                CONVERSATION_ID,
            ).eventType,
        )
        assertRejected {
            SecureMessagingWireValidator.validateDeviceLifecycleEvent(
                event.copy(data = event.data?.copy(rosterRefreshRequired = false)),
                CONVERSATION_ID,
            )
        }
    }

    @Test
    fun `all-device revocation preserves affected account and rejects hidden device epochs`() {
        val event = MessagingSyncEventDto(
            id = "10",
            type = "devices.revoked",
            conversationId = CONVERSATION_ID,
            resourceType = "messaging_user",
            resourceId = OTHER_USER_ID,
            data = MessagingSyncEventDataDto(
                userId = OTHER_USER_ID,
                revokedDeviceCount = 2,
                rosterRefreshRequired = true,
                transitionedAt = TIMESTAMP,
                transitionHash = "e".repeat(64),
            ),
            occurredAt = TIMESTAMP,
        )

        val validated = SecureMessagingWireValidator.validateDeviceLifecycleEvent(
            event,
            CONVERSATION_ID,
        )

        assertEquals(OTHER_USER_ID, validated.userId)
        assertEquals(2, validated.revokedDeviceCount)
        assertEquals(null, validated.deviceId)
        assertRejected {
            SecureMessagingWireValidator.validateDeviceLifecycleEvent(
                event.copy(data = event.data?.copy(previousRegistrationId = 42)),
                CONVERSATION_ID,
            )
        }
        assertRejected {
            SecureMessagingWireValidator.validateDeviceLifecycleEvent(
                event.copy(data = event.data?.copy(revokedDeviceCount = 101)),
                CONVERSATION_ID,
            )
        }
    }

    private fun enrolledStatus(): MessagingKeyStatusDto {
        val identityKey = signalValue(5, 0x11, 33)
        val pqLastResort = signalValue(8, 0x31, 1569)
        return MessagingKeyStatusDto(
            enrolled = true,
            enrollmentEpoch = 1,
            deviceId = CURRENT_DEVICE_ID,
            signalDeviceId = 1,
            protocolVersion = "v2",
            registrationId = 42,
            identityKeySha256 = digestBase64(identityKey),
            signedPrekeyId = 8,
            signedPrekeySha256 = digestBase64(signalValue(5, 0x12, 33)),
            pqLastResortPrekeyId = 100,
            pqLastResortPrekeySha256 = digestBase64(pqLastResort),
            bundleVersion = 1,
            availableOneTimePrekeys = 5,
            availableEcOneTimePrekeys = 5,
            availablePqOneTimePrekeys = 7,
            replenishAt = 6,
            needsReplenishment = true,
            publishedAt = TIMESTAMP,
            transparency = transparency(
                identityHash = digestBase64(identityKey),
                pqId = 100,
                pqHash = digestBase64(pqLastResort),
            ),
        )
    }

    private fun validateConsumed(
        response: ConsumedMessagingKeyBundlesDto,
    ): ValidatedConsumedMessagingKeyBundles {
        val roster = SecureMessagingWireValidator.validateAuthoritativeRoster(
            authoritativeRoster(),
            CONVERSATION_ID,
            CURRENT_DEVICE_ID,
            CURRENT_USER_ID,
            DIRECT_MEMBER_IDS,
        )
        return SecureMessagingWireValidator.validateConsumedKeyBundles(
            response = response,
            authoritativeRoster = roster,
            expectedConversationId = CONVERSATION_ID,
            expectedDeviceIds = setOf(OTHER_DEVICE_ID),
            currentDeviceId = CURRENT_DEVICE_ID,
            currentUserId = CURRENT_USER_ID,
            expectedMemberUserIds = DIRECT_MEMBER_IDS,
        )
    }

    private fun authoritativeRoster(): MessagingDeviceRosterDto = MessagingDeviceRosterDto(
        conversationId = CONVERSATION_ID,
        rosterRevision = "v1:sha256:$CANONICAL_ROSTER_HASH",
        rosterHash = CANONICAL_ROSTER_HASH,
        hashAlgorithm = "sha256",
        devices = listOf(
            rosterDevice(
                deviceId = CURRENT_DEVICE_ID,
                userId = CURRENT_USER_ID,
                signalDeviceId = 1,
                registrationId = 42,
                bundleVersion = 1,
                identitySeed = 0x11,
                signedSeed = 0x12,
                signatureSeed = 0x13,
            ),
            rosterDevice(
                deviceId = OTHER_DEVICE_ID,
                userId = OTHER_USER_ID,
                signalDeviceId = 2,
                registrationId = 43,
                bundleVersion = 2,
                identitySeed = 0x21,
                signedSeed = 0x22,
                signatureSeed = 0x23,
            ),
        ),
    )

    private fun rosterDevice(
        deviceId: String,
        userId: String,
        signalDeviceId: Int,
        registrationId: Int,
        bundleVersion: Int,
        identitySeed: Int,
        signedSeed: Int,
        signatureSeed: Int,
    ): MessagingDeviceRosterEntryDto {
        val identityKey = signalValue(5, identitySeed, 33)
        val signedKey = signalValue(5, signedSeed, 33)
        return MessagingDeviceRosterEntryDto(
            deviceId = deviceId,
            signalDeviceId = signalDeviceId,
            userId = userId,
            registrationId = registrationId,
            protocolVersion = "v2",
            bundleVersion = bundleVersion,
            identityKey = identityKey,
            identityKeySha256 = digestBase64(identityKey),
            signedPrekey = MessagingSignedPrekeyDto(
                prekeyId = 7 + signalDeviceId,
                publicKey = signedKey,
                publicKeySha256 = digestBase64(signedKey),
                signature = repeatedValue(signatureSeed, 64),
            ),
            publishedAt = TIMESTAMP,
            identityKeyChangedAt = TIMESTAMP,
            bundleVersionChangedAt = TIMESTAMP,
        )
    }

    private fun consumedBundles(): ConsumedMessagingKeyBundlesDto {
        val identityKey = signalValue(5, 0x21, 33)
        val signedKey = signalValue(5, 0x22, 33)
        val pqKey = signalValue(8, 0x31, 1569)
        return ConsumedMessagingKeyBundlesDto(
            bundles = listOf(
                ConsumedMessagingKeyBundleDto(
                    deviceId = OTHER_DEVICE_ID,
                    signalDeviceId = 2,
                    userId = OTHER_USER_ID,
                    protocolVersion = "v2",
                    registrationId = 43,
                    identityKey = identityKey,
                    identityKeySha256 = digestBase64(identityKey),
                    signedPrekey = MessagingSignedPrekeyDto(
                        prekeyId = 9,
                        publicKey = signedKey,
                        signature = repeatedValue(0x23, 64),
                    ),
                    oneTimePrekey = MessagingOneTimePrekeyDto(
                        prekeyId = 10,
                        publicKey = signalValue(5, 0x24, 33),
                    ),
                    pqPrekey = MessagingPqPrekeyDto(
                        prekeyId = 100,
                        publicKey = pqKey,
                        signature = repeatedValue(0x32, 64),
                    ),
                    bundleVersion = 2,
                    availableOneTimePrekeys = 3,
                    availableEcOneTimePrekeys = 3,
                    availablePqOneTimePrekeys = 4,
                    needsReplenishment = false,
                    isCurrentDevice = false,
                    publishedAt = TIMESTAMP,
                    transparency = transparency(
                        identityHash = digestBase64(identityKey),
                        pqId = 100,
                        pqHash = digestBase64(pqKey),
                    ),
                ),
            ),
        )
    }

    private fun incomingMessage(): EncryptedMessageDto = EncryptedMessageDto(
        id = MESSAGE_ID,
        conversationId = CONVERSATION_ID,
        clientMessageId = CLIENT_MESSAGE_ID,
        sender = EncryptedMessageSenderDto(id = OTHER_USER_ID, name = "Amina"),
        senderDeviceId = OTHER_DEVICE_ID,
        senderSignalDeviceId = 2,
        senderRegistrationId = 43,
        senderProtocolVersion = "v2",
        senderBundleVersion = 2,
        senderIdentityKeySha256 = "c".repeat(64),
        rosterRevision = "v1:sha256:$CANONICAL_ROSTER_HASH",
        kind = ENCRYPTED_MESSAGE_KIND,
        envelope = EncryptedMessageEnvelopeDto(
            recipientDeviceId = CURRENT_DEVICE_ID,
            envelopeType = "signal-prekey-v2",
            ciphertext = Base64.getEncoder().encodeToString(CIPHERTEXT),
            ciphertextSha256 = sha256(CIPHERTEXT),
        ),
        attachments = emptyList(),
        reactions = emptyList(),
        sentAt = TIMESTAMP,
    )

    private fun incomingEvent(): MessagingSyncEventDto {
        val message = incomingMessage()
        return MessagingSyncEventDto(
            id = "1",
            type = "message.created",
            conversationId = CONVERSATION_ID,
            resourceType = "message",
            resourceId = MESSAGE_ID,
            data = MessagingSyncEventDataDto(
                id = message.id,
                conversationId = message.conversationId,
                clientMessageId = message.clientMessageId,
                sender = message.sender,
                senderDeviceId = message.senderDeviceId,
                senderSignalDeviceId = message.senderSignalDeviceId,
                senderRegistrationId = message.senderRegistrationId,
                senderProtocolVersion = message.senderProtocolVersion,
                senderBundleVersion = message.senderBundleVersion,
                senderIdentityKeySha256 = message.senderIdentityKeySha256,
                rosterRevision = message.rosterRevision,
                kind = message.kind,
                envelope = message.envelope,
                attachments = message.attachments,
                reactions = message.reactions,
                sentAt = message.sentAt,
            ),
            occurredAt = TIMESTAMP,
        )
    }

    private fun transparency(
        identityHash: String,
        pqId: Int,
        pqHash: String,
    ) = MessagingKeyTransparencyDto(
        revision = "1",
        eventType = "device.enrolled",
        protocolVersion = "v2",
        eventHash = "a".repeat(64),
        identityKeySha256 = identityHash,
        pqLastResortPrekeyId = pqId,
        pqLastResortPrekeySha256 = pqHash,
        occurredAt = TIMESTAMP,
    )

    private fun signalValue(type: Int, fill: Int, length: Int): String {
        val bytes = ByteArray(length) { fill.toByte() }
        bytes[0] = type.toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun repeatedValue(fill: Int, length: Int): String = Base64.getEncoder()
        .encodeToString(ByteArray(length) { fill.toByte() })

    private fun digestBase64(value: String): String = sha256(Base64.getDecoder().decode(value))

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertRejected(block: () -> Unit) {
        assertThrows(
            SecureMessagingWireValidationException::class.java,
            ThrowingRunnable(block),
        )
    }

    private companion object {
        const val CONVERSATION_ID = "10000000-0000-4000-8000-000000000000"
        const val CURRENT_DEVICE_ID = "20000000-0000-4000-8000-000000000000"
        const val OTHER_DEVICE_ID = "30000000-0000-4000-8000-000000000000"
        const val THIRD_DEVICE_ID = "31000000-0000-4000-8000-000000000000"
        const val CURRENT_USER_ID = "40000000-0000-4000-8000-000000000000"
        const val OTHER_USER_ID = "50000000-0000-4000-8000-000000000000"
        const val MESSAGE_ID = "60000000-0000-4000-8000-000000000000"
        const val THIRD_MESSAGE_ID = "61000000-0000-4000-8000-000000000000"
        const val CLIENT_MESSAGE_ID = "70000000-0000-4000-8000-000000000000"
        const val TIMESTAMP = "2026-07-19T19:00:00Z"
        const val CANONICAL_ROSTER_HASH =
            "05f060ddb28c04ade89df54f116dc1ea2736666faddd3cb2fc0f2ef5d748b11e"
        val DIRECT_MEMBER_IDS = setOf(CURRENT_USER_ID, OTHER_USER_ID)
        val CIPHERTEXT: ByteArray = "sealed message".toByteArray(StandardCharsets.UTF_8)
    }
}
