package com.kit.wallet.data.remote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * Fail-closed validation boundary for dormant secure-messaging wire responses.
 *
 * Moshi models remain nullable so a malformed response can be decoded and rejected here. Nothing
 * in this validator establishes a Signal session, verifies a Signal signature, or makes messaging
 * available. It only prevents malformed routing and key material from reaching a future reviewed
 * cryptographic client.
 */
object SecureMessagingWireValidator {
    fun requireValidatedRoster(
        roster: ValidatedMessagingDeviceRoster,
    ): ValidatedMessagingDeviceRoster {
        requireWire(roster is ValidatedMessagingDeviceRosterValue, "unissued validated roster")
        return roster
    }

    fun requireValidatedKeyBundles(
        bundles: ValidatedConsumedMessagingKeyBundles,
    ): ValidatedConsumedMessagingKeyBundles {
        requireWire(
            bundles is ValidatedConsumedMessagingKeyBundlesValue,
            "unissued validated key bundles",
        )
        return bundles
    }

    fun validateKeyStatus(
        status: MessagingKeyStatusDto,
        currentDeviceId: String,
    ): MessagingKeyStatusDto {
        requireUuid(currentDeviceId, "current device ID")
        requireWire(status.protocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION, "key status protocol")

        val enrolled = required(status.enrolled, "key status enrollment")
        val available = requiredNonNegative(status.availableOneTimePrekeys, "available one-time prekeys")
        val availableEc = requiredNonNegative(
            status.availableEcOneTimePrekeys,
            "available EC one-time prekeys",
        )
        val availablePq = requiredNonNegative(
            status.availablePqOneTimePrekeys,
            "available PQ one-time prekeys",
        )
        val replenishAt = requiredNonNegative(status.replenishAt, "prekey replenishment threshold")
        val needsReplenishment = required(status.needsReplenishment, "prekey replenishment state")

        requireWire(available == availableEc, "one-time prekey counters disagree")
        requireWire(
            needsReplenishment == (availableEc <= replenishAt || availablePq <= replenishAt),
            "prekey replenishment state is inconsistent",
        )

        if (!enrolled) {
            requireWire(available == 0 && availablePq == 0, "unenrolled key status has inventory")
            requireWire(needsReplenishment, "unenrolled key status must require enrollment")
            requireWire(
                status.deviceId == null &&
                    status.signalDeviceId == null &&
                    status.registrationId == null &&
                    status.identityKeySha256 == null &&
                    status.signedPrekeyId == null &&
                    status.signedPrekeySha256 == null &&
                    status.pqLastResortPrekeyId == null &&
                    status.pqLastResortPrekeySha256 == null &&
                    status.bundleVersion == null &&
                    status.publishedAt == null &&
                    status.rotatedAt == null &&
                    status.transparency == null,
                "unenrolled key status contains enrolled-device material",
            )
            return status
        }

        val deviceId = required(status.deviceId, "key status device ID")
        requireUuid(deviceId, "key status device ID")
        requireWire(deviceId == currentDeviceId, "key status is for another device")
        requireSignalDeviceId(status.signalDeviceId, "key status Signal device ID")
        requireRegistrationId(status.registrationId, "key status registration ID")
        val identityHash = requireSha256(status.identityKeySha256, "key status identity-key hash")
        requirePrekeyId(status.signedPrekeyId, "key status signed-prekey ID")
        requireSha256(status.signedPrekeySha256, "key status signed-prekey hash")
        val pqLastResortId = requirePrekeyId(
            status.pqLastResortPrekeyId,
            "key status PQ last-resort prekey ID",
        )
        val pqLastResortHash = requireSha256(
            status.pqLastResortPrekeySha256,
            "key status PQ last-resort prekey hash",
        )
        requireBundleVersion(status.bundleVersion, "key status bundle version")
        val publishedAt = requireTimestamp(status.publishedAt, "key status publication time")
        status.rotatedAt?.let {
            requireWire(
                !requireTimestamp(it, "key status rotation time").isBefore(publishedAt),
                "key status rotation precedes publication",
            )
        }
        validateTransparency(
            required(status.transparency, "key status transparency event"),
            expectedIdentityHash = identityHash,
            expectedPqLastResortId = pqLastResortId,
            expectedPqLastResortHash = pqLastResortHash,
        )

        return status
    }

    fun validateAuthoritativeRoster(
        roster: MessagingDeviceRosterDto,
        expectedConversationId: String,
        currentDeviceId: String,
        currentUserId: String,
        expectedMemberUserIds: Set<String>,
    ): ValidatedMessagingDeviceRoster {
        requireUuid(expectedConversationId, "expected conversation ID")
        requireUuid(currentDeviceId, "current device ID")
        requireUuid(currentUserId, "current user ID")
        requireWire(expectedMemberUserIds.size == 2, "direct roster member count")
        requireWire(currentUserId in expectedMemberUserIds, "current user is not a direct roster member")
        expectedMemberUserIds.forEach { requireUuid(it, "direct roster member user ID") }
        val conversationId = required(roster.conversationId, "roster conversation ID")
        requireUuid(conversationId, "roster conversation ID")
        requireWire(conversationId == expectedConversationId, "roster conversation does not match request")

        val rosterHash = requireSha256(roster.rosterHash, "roster hash")
        requireWire(roster.hashAlgorithm == SHA256_LABEL, "roster hash algorithm")
        val rosterRevision = required(roster.rosterRevision, "roster revision")
        requireWire(
            rosterRevision == "$ROSTER_REVISION_PREFIX$rosterHash",
            "roster revision and hash disagree",
        )

        val nullableDevices = required(roster.devices, "roster devices")
        requireWire(nullableDevices.size in 2..MAX_ROSTER_DEVICES, "roster device count")
        val devices = nullableDevices.mapIndexed { index, device ->
            required(device, "roster device $index")
        }

        val validated = devices.mapIndexed { index, device ->
            validateRosterDevice(device, index)
        }
        requireWire(
            validated.map { it.deviceId }.toSet().size == validated.size,
            "roster contains duplicate device IDs",
        )
        requireWire(
            validated.map { it.userId to it.signalDeviceId }.toSet().size == validated.size,
            "roster reuses a Signal device ID for one user",
        )
        requireWire(
            validated.map { it.userId }.toSet() == expectedMemberUserIds,
            "roster devices do not exactly match the direct conversation members",
        )
        requireWire(
            validated.count { it.deviceId == currentDeviceId } == 1,
            "roster does not contain the current enrolled device exactly once",
        )
        requireWire(
            validated.single { it.deviceId == currentDeviceId }.userId == currentUserId,
            "roster current device belongs to another user",
        )
        requireWire(
            validated == validated.sortedWith(
                compareBy<ValidatedMessagingRosterDevice>(
                    { it.userId },
                    { it.signalDeviceId },
                    { it.deviceId },
                ),
            ),
            "roster devices are not in canonical order",
        )

        val computedHash = sha256Hex(canonicalRosterBytes(conversationId, devices))
        requireWire(computedHash == rosterHash, "roster content does not match its revision")

        return ValidatedMessagingDeviceRosterValue(
            conversationId = conversationId,
            rosterRevision = rosterRevision,
            currentDeviceId = currentDeviceId,
            currentUserId = currentUserId,
            memberUserIds = expectedMemberUserIds,
            devices = validated,
        )
    }

    fun requireAuthoritativeRosterBinding(
        roster: ValidatedMessagingDeviceRoster,
        expectedConversationId: String,
        currentDeviceId: String,
        currentUserId: String,
        expectedMemberUserIds: Set<String>,
    ): ValidatedMessagingDeviceRoster {
        requireUuid(expectedConversationId, "expected conversation ID")
        requireUuid(currentDeviceId, "current device ID")
        requireUuid(currentUserId, "current user ID")
        requireWire(expectedMemberUserIds.size == 2, "direct roster member count")
        requireWire(currentUserId in expectedMemberUserIds, "current user is not a direct roster member")
        expectedMemberUserIds.forEach { requireUuid(it, "direct roster member user ID") }
        val issuedRoster = requireValidatedRoster(roster)
        requireWire(
            issuedRoster.conversationId == expectedConversationId &&
                roster.currentDeviceId == currentDeviceId &&
                roster.currentUserId == currentUserId &&
                roster.memberUserIds() == expectedMemberUserIds,
            "validated roster binding changed",
        )
        return roster
    }

    fun requireKeyBundleTargets(
        roster: ValidatedMessagingDeviceRoster,
        expectedConversationId: String,
        currentDeviceId: String,
        currentUserId: String,
        expectedMemberUserIds: Set<String>,
        requestedDeviceIds: Set<String>,
    ) {
        val boundRoster = requireAuthoritativeRosterBinding(
            roster,
            expectedConversationId,
            currentDeviceId,
            currentUserId,
            expectedMemberUserIds,
        )
        requireWire(requestedDeviceIds.isNotEmpty(), "requested key-bundle device set is empty")
        requestedDeviceIds.forEach { requireUuid(it, "requested key-bundle device ID") }
        val rosterRecipientIds = boundRoster.devices()
            .map(ValidatedMessagingRosterDevice::deviceId)
            .filterNot { it == currentDeviceId }
            .toSet()
        requireWire(currentDeviceId !in requestedDeviceIds, "current device cannot consume its own key bundle")
        requireWire(
            rosterRecipientIds.containsAll(requestedDeviceIds),
            "requested key-bundle device is outside the authoritative roster",
        )
    }

    fun validateConsumedKeyBundles(
        response: ConsumedMessagingKeyBundlesDto,
        authoritativeRoster: ValidatedMessagingDeviceRoster,
        expectedConversationId: String,
        expectedDeviceIds: Set<String>,
        currentDeviceId: String,
        currentUserId: String,
        expectedMemberUserIds: Set<String>,
    ): ValidatedConsumedMessagingKeyBundles {
        val roster = requireAuthoritativeRosterBinding(
            authoritativeRoster,
            expectedConversationId,
            currentDeviceId,
            currentUserId,
            expectedMemberUserIds,
        )
        val rosterDevices = roster.devices().associateBy(ValidatedMessagingRosterDevice::deviceId)
        requireUuid(currentDeviceId, "current device ID")
        requireWire(expectedDeviceIds.isNotEmpty(), "expected key-bundle device set is empty")
        expectedDeviceIds.forEach { requireUuid(it, "expected key-bundle device ID") }
        requireWire(currentDeviceId !in expectedDeviceIds, "current device cannot consume its own key bundle")

        val nullableBundles = required(response.bundles, "consumed key bundles")
        requireWire(nullableBundles.size in 1..MAX_ROSTER_DEVICES, "consumed key-bundle count")
        val bundles = nullableBundles.mapIndexed { index, bundle ->
            required(bundle, "consumed key bundle $index")
        }
        val actualDeviceIds = bundles.mapIndexed { index, bundle ->
            val deviceId = required(bundle.deviceId, "consumed key bundle $index device ID")
            requireUuid(deviceId, "consumed key bundle $index device ID")
            deviceId
        }
        requireWire(actualDeviceIds.toSet().size == actualDeviceIds.size, "duplicate consumed device bundle")
        requireWire(actualDeviceIds.toSet() == expectedDeviceIds, "consumed key-bundle targets changed")

        val signalAddresses = mutableSetOf<Pair<String, Int>>()
        val validatedBundles = bundles.mapIndexed { index, bundle ->
            val deviceId = actualDeviceIds[index]
            val rosterDevice = required(
                rosterDevices[deviceId],
                "consumed key bundle $index authoritative roster device",
            )
            requireWire(
                bundle.protocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION,
                "consumed key bundle $index protocol",
            )
            val signalDeviceId = requireSignalDeviceId(
                bundle.signalDeviceId,
                "consumed key bundle $index Signal device ID",
            )
            val userId = required(bundle.userId, "consumed key bundle $index user ID")
            requireUuid(userId, "consumed key bundle $index user ID")
            requireWire(userId == rosterDevice.userId, "consumed key bundle $index user changed")
            requireWire(
                signalDeviceId == rosterDevice.signalDeviceId,
                "consumed key bundle $index Signal device ID changed",
            )
            requireWire(
                signalAddresses.add(userId to signalDeviceId),
                "consumed key bundles reuse a per-user Signal device ID",
            )
            val registrationId = requireRegistrationId(
                bundle.registrationId,
                "consumed key bundle $index registration ID",
            )
            requireWire(
                registrationId == rosterDevice.registrationId,
                "consumed key bundle $index registration changed",
            )
            requireWire(
                bundle.protocolVersion == rosterDevice.protocolVersion,
                "consumed key bundle $index protocol changed",
            )
            val identityKey = requireSignalValue(
                bundle.identityKey,
                EC_PUBLIC_KEY_BYTES,
                EC_PUBLIC_KEY_TYPE,
                "consumed key bundle $index identity key",
            )
            val identityHash = requireMatchingSha256(
                bundle.identityKeySha256,
                identityKey,
                "consumed key bundle $index identity-key hash",
            )
            requireWire(
                identityKey.contentEquals(rosterDevice.identityKeyBytes()) &&
                    identityHash == rosterDevice.identityKeySha256,
                "consumed key bundle $index identity key changed",
            )
            val consumedSignedPrekeyWire = required(
                bundle.signedPrekey,
                "consumed key bundle $index signed prekey",
            )
            val consumedSignedPrekey = validateSignedPrekey(
                consumedSignedPrekeyWire,
                "consumed key bundle $index signed prekey",
                hashRequired = false,
            )
            val rosterSignedPrekey = rosterDevice.signedPrekey
            requireWire(
                consumedSignedPrekey.id == rosterSignedPrekey.id &&
                    consumedSignedPrekey.publicKeyBytes()
                        .contentEquals(rosterSignedPrekey.publicKeyBytes()) &&
                    consumedSignedPrekey.signatureBytes()
                        .contentEqualsNullable(rosterSignedPrekey.signatureBytes()) &&
                    (consumedSignedPrekeyWire.publicKeySha256 == null ||
                        consumedSignedPrekeyWire.publicKeySha256 == rosterSignedPrekey.publicKeySha256),
                "consumed key bundle $index signed prekey changed",
            )
            val oneTimePrekey = bundle.oneTimePrekey?.let {
                validateOneTimePrekey(it, "consumed key bundle $index one-time prekey")
            }
            val pqPrekey = validatePqPrekey(
                required(bundle.pqPrekey, "consumed key bundle $index PQ prekey"),
                "consumed key bundle $index PQ prekey",
            )
            val bundleVersion = requireBundleVersion(
                bundle.bundleVersion,
                "consumed key bundle $index bundle version",
            )
            requireWire(
                bundleVersion == rosterDevice.bundleVersion,
                "consumed key bundle $index version changed",
            )
            val available = requiredNonNegative(
                bundle.availableOneTimePrekeys,
                "consumed key bundle $index available one-time prekeys",
            )
            val availableEc = requiredNonNegative(
                bundle.availableEcOneTimePrekeys,
                "consumed key bundle $index available EC one-time prekeys",
            )
            requiredNonNegative(
                bundle.availablePqOneTimePrekeys,
                "consumed key bundle $index available PQ one-time prekeys",
            )
            requireWire(available == availableEc, "consumed key bundle $index EC counters disagree")
            required(bundle.needsReplenishment, "consumed key bundle $index replenishment state")
            requireWire(
                required(bundle.isCurrentDevice, "consumed key bundle $index current-device marker") ==
                    (deviceId == currentDeviceId),
                "consumed key bundle $index current-device marker is inconsistent",
            )
            val publishedAt = requireTimestamp(
                bundle.publishedAt,
                "consumed key bundle $index publication time",
            )
            requireWire(
                bundle.publishedAt == rosterDevice.publishedAt &&
                    bundle.rotatedAt == rosterDevice.rotatedAt,
                "consumed key bundle $index activation changed",
            )
            bundle.rotatedAt?.let {
                requireWire(
                    !requireTimestamp(it, "consumed key bundle $index rotation time").isBefore(publishedAt),
                    "consumed key bundle $index rotation precedes publication",
                )
            }
            val transparency = required(
                bundle.transparency,
                "consumed key bundle $index transparency event",
            )
            validateTransparency(transparency, expectedIdentityHash = identityHash)
            if (pqPrekey.id == transparency.pqLastResortPrekeyId) {
                requireWire(
                    sha256Hex(pqPrekey.publicKeyBytes()) == transparency.pqLastResortPrekeySha256,
                    "consumed key bundle $index PQ last-resort key changed",
                )
            }
            ValidatedConsumedMessagingKeyBundleValue(
                deviceId = deviceId,
                userId = userId,
                signalDeviceId = signalDeviceId,
                registrationId = registrationId,
                identityKey = identityKey,
                signedPrekey = consumedSignedPrekey,
                oneTimePrekey = oneTimePrekey,
                pqPrekey = pqPrekey,
            )
        }

        return ValidatedConsumedMessagingKeyBundlesValue(
            conversationId = roster.conversationId,
            rosterRevision = roster.rosterRevision,
            currentDeviceId = roster.currentDeviceId,
            currentUserId = roster.currentUserId,
            memberUserIds = roster.memberUserIds(),
            requestedDeviceIds = expectedDeviceIds,
            bundles = validatedBundles,
        )
    }

    fun validateIncomingEncryptedMessage(
        message: EncryptedMessageDto,
        expectedConversationId: String,
        currentDeviceId: String,
    ): ValidatedIncomingEncryptedMessage = validateIncoming(
        IncomingMessageWire(
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
            replyToMessageId = message.replyToMessageId,
            envelope = message.envelope,
            attachments = message.attachments,
            reactions = message.reactions,
            sentAt = message.sentAt,
            revokedAt = message.revokedAt,
        ),
        expectedConversationId,
        currentDeviceId,
    )

    fun validateIncomingEncryptedMessageEvent(
        event: MessagingSyncEventDto,
        expectedConversationId: String,
        currentDeviceId: String,
    ): ValidatedIncomingEncryptedMessage {
        requireUuid(expectedConversationId, "expected conversation ID")
        requireUuid(currentDeviceId, "current device ID")
        val eventId = required(event.id, "messaging event ID")
        requireWire(POSITIVE_DECIMAL.matches(eventId) && eventId != "0", "messaging event ID")
        requireWire(event.type == MESSAGE_CREATED_EVENT, "messaging event type")
        requireWire(event.resourceType == MESSAGE_RESOURCE_TYPE, "messaging event resource type")
        val conversationId = required(event.conversationId, "messaging event conversation ID")
        requireUuid(conversationId, "messaging event conversation ID")
        requireWire(conversationId == expectedConversationId, "messaging event conversation changed")
        val data = required(event.data, "messaging event data")
        requireWire(event.resourceId == data.id, "messaging event resource ID changed")
        val occurredAt = requireTimestamp(event.occurredAt, "messaging event occurrence time")

        val validated = validateIncoming(
            IncomingMessageWire(
                id = data.id,
                conversationId = data.conversationId,
                clientMessageId = data.clientMessageId,
                sender = data.sender,
                senderDeviceId = data.senderDeviceId,
                senderSignalDeviceId = data.senderSignalDeviceId,
                senderRegistrationId = data.senderRegistrationId,
                senderProtocolVersion = data.senderProtocolVersion,
                senderBundleVersion = data.senderBundleVersion,
                senderIdentityKeySha256 = data.senderIdentityKeySha256,
                rosterRevision = data.rosterRevision,
                kind = data.kind,
                replyToMessageId = data.replyToMessageId,
                envelope = data.envelope,
                attachments = data.attachments,
                reactions = data.reactions,
                sentAt = data.sentAt,
                revokedAt = data.revokedAt,
            ),
            expectedConversationId,
            currentDeviceId,
        )
        requireWire(!occurredAt.isBefore(validated.sentAt), "messaging event predates its message")
        return validated
    }

    /**
     * Validates a lifecycle hint only far enough to require an authoritative roster refresh.
     * The event never authorizes a local key/session mutation by itself.
     */
    fun validateDeviceLifecycleEvent(
        event: MessagingSyncEventDto,
        expectedConversationId: String,
    ): ValidatedMessagingRosterRefresh {
        requireUuid(expectedConversationId, "expected conversation ID")
        val eventId = required(event.id, "messaging lifecycle event ID")
        requireWire(POSITIVE_DECIMAL.matches(eventId) && eventId != "0", "messaging lifecycle event ID")
        val eventType = required(event.type, "messaging lifecycle event type")
        requireWire(eventType in DEVICE_LIFECYCLE_EVENT_TYPES, "messaging lifecycle event type")
        val conversationId = required(event.conversationId, "messaging lifecycle conversation ID")
        requireUuid(conversationId, "messaging lifecycle conversation ID")
        requireWire(conversationId == expectedConversationId, "messaging lifecycle conversation changed")
        val data = required(event.data, "messaging lifecycle event data")
        val userId = required(data.userId, "messaging lifecycle user ID")
        requireUuid(userId, "messaging lifecycle user ID")
        requireWire(data.rosterRefreshRequired == true, "messaging lifecycle omitted roster refresh")
        val transitionedAt = requireTimestamp(data.transitionedAt, "messaging lifecycle transition time")
        val occurredAt = requireTimestamp(event.occurredAt, "messaging lifecycle occurrence time")
        requireWire(!occurredAt.isBefore(transitionedAt), "messaging lifecycle event predates transition")
        val transitionHash = requireSha256(data.transitionHash, "messaging lifecycle transition hash")

        if (eventType == ALL_DEVICES_REVOKED_EVENT) {
            requireWire(event.resourceType == MESSAGING_USER_RESOURCE, "messaging lifecycle resource type")
            requireWire(event.resourceId == userId, "messaging lifecycle user resource changed")
            requireWire(
                required(data.revokedDeviceCount, "messaging lifecycle revoked-device count") in
                    1..MAX_ROSTER_DEVICES,
                "messaging lifecycle revoked-device count",
            )
            requireWire(
                data.deviceId == null &&
                    data.signalDeviceId == null &&
                    data.registrationId == null &&
                    data.previousRegistrationId == null &&
                    data.protocolVersion == null &&
                    data.previousProtocolVersion == null &&
                    data.bundleVersion == null &&
                    data.identityKeySha256 == null &&
                    data.previousIdentityKeySha256 == null,
                "all-device lifecycle event contains a device snapshot",
            )
        } else {
            requireWire(event.resourceType == MESSAGING_DEVICE_RESOURCE, "messaging lifecycle resource type")
            val deviceId = required(data.deviceId, "messaging lifecycle device ID")
            requireUuid(deviceId, "messaging lifecycle device ID")
            requireWire(event.resourceId == deviceId, "messaging lifecycle device resource changed")
            requireSignalDeviceId(data.signalDeviceId, "messaging lifecycle Signal device ID")
            requireRegistrationId(data.registrationId, "messaging lifecycle registration ID")
            data.previousRegistrationId?.let {
                requireRegistrationId(it, "messaging lifecycle previous registration ID")
            }
            requireWire(
                data.protocolVersion in setOf("v1", SECURE_MESSAGING_PROTOCOL_VERSION),
                "messaging lifecycle protocol",
            )
            data.previousProtocolVersion?.let {
                requireWire(it in setOf("v1", SECURE_MESSAGING_PROTOCOL_VERSION), "previous lifecycle protocol")
            }
            requireBundleVersion(data.bundleVersion, "messaging lifecycle bundle version")
            val identityHash = requireSha256(
                data.identityKeySha256,
                "messaging lifecycle identity-key hash",
            )
            data.previousIdentityKeySha256?.let {
                requireSha256(it, "previous messaging lifecycle identity-key hash")
            }
            requireWire(data.revokedDeviceCount == null, "device lifecycle event has aggregate count")
            if (eventType == IDENTITY_CHANGED_EVENT) {
                val previous = required(
                    data.previousIdentityKeySha256,
                    "previous messaging lifecycle identity-key hash",
                )
                requireWire(previous != identityHash, "identity-change event retained the same identity")
            }
        }

        return ValidatedMessagingRosterRefresh(
            conversationId = conversationId,
            eventType = eventType,
            userId = userId,
            deviceId = data.deviceId,
            signalDeviceId = data.signalDeviceId,
            registrationId = data.registrationId,
            previousRegistrationId = data.previousRegistrationId,
            protocolVersion = data.protocolVersion,
            previousProtocolVersion = data.previousProtocolVersion,
            bundleVersion = data.bundleVersion,
            identityKeySha256 = data.identityKeySha256,
            previousIdentityKeySha256 = data.previousIdentityKeySha256,
            revokedDeviceCount = data.revokedDeviceCount,
            transitionedAt = transitionedAt,
            transitionHash = transitionHash,
        )
    }

    private fun validateIncoming(
        message: IncomingMessageWire,
        expectedConversationId: String,
        currentDeviceId: String,
    ): ValidatedIncomingEncryptedMessage {
        requireUuid(expectedConversationId, "expected conversation ID")
        requireUuid(currentDeviceId, "current device ID")
        val id = required(message.id, "encrypted message ID")
        requireUuid(id, "encrypted message ID")
        val conversationId = required(message.conversationId, "encrypted message conversation ID")
        requireUuid(conversationId, "encrypted message conversation ID")
        requireWire(conversationId == expectedConversationId, "encrypted message conversation changed")
        val clientMessageId = required(message.clientMessageId, "encrypted client message ID")
        requireUuid(clientMessageId, "encrypted client message ID")

        val sender = required(message.sender, "encrypted message sender")
        val senderUserId = required(sender.id, "encrypted message sender ID")
        requireUuid(senderUserId, "encrypted message sender ID")
        requireWire(!sender.name.isNullOrBlank(), "encrypted message sender name")
        val senderDeviceId = required(message.senderDeviceId, "encrypted message sender device ID")
        requireUuid(senderDeviceId, "encrypted message sender device ID")
        requireWire(senderDeviceId != currentDeviceId, "encrypted message is routed from its recipient device")
        val senderSignalDeviceId = requireSignalDeviceId(
            message.senderSignalDeviceId,
            "encrypted message sender Signal device ID",
        )
        val senderRegistrationId = requireRegistrationId(
            message.senderRegistrationId,
            "encrypted message sender registration ID",
        )
        requireWire(
            message.senderProtocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION,
            "encrypted message sender protocol",
        )
        val senderBundleVersion = requireBundleVersion(
            message.senderBundleVersion,
            "encrypted message sender bundle version",
        )
        val senderIdentityKeySha256 = requireSha256(
            message.senderIdentityKeySha256,
            "encrypted message sender identity-key hash",
        )
        val rosterRevision = required(message.rosterRevision, "encrypted message roster revision")
        requireWire(SECURE_MESSAGING_ROSTER_REVISION.matches(rosterRevision), "encrypted message roster revision")

        val attachments = required(message.attachments, "encrypted message attachments").mapIndexed { index, item ->
            required(item, "encrypted message attachment $index")
        }
        validateAttachments(attachments)
        val kind = required(message.kind, "encrypted message kind")
        requireWire(
            kind == ENCRYPTED_MESSAGE_KIND && attachments.isEmpty(),
            "v2 encrypted messages must contain text ciphertext only",
        )
        val replyToMessageId = message.replyToMessageId
        replyToMessageId?.let { requireUuid(it, "encrypted message reply target") }
        requireWire(
            required(message.reactions, "encrypted message reactions").isEmpty(),
            "v2 encrypted text messages cannot contain reactions",
        )
        val sentAt = requireTimestamp(message.sentAt, "encrypted message send time")
        requireWire(message.revokedAt == null, "revoked encrypted message is not decryptable")

        val envelope = required(message.envelope, "encrypted message device envelope")
        val recipientDeviceId = required(envelope.recipientDeviceId, "encrypted envelope recipient device ID")
        requireUuid(recipientDeviceId, "encrypted envelope recipient device ID")
        requireWire(recipientDeviceId == currentDeviceId, "encrypted envelope is routed to another device")
        val envelopeType = required(envelope.envelopeType, "encrypted envelope type")
        requireWire(envelopeType in SECURE_MESSAGE_ENVELOPE_TYPES, "encrypted envelope protocol")
        val ciphertext = requireCanonicalBase64(
            envelope.ciphertext,
            "encrypted envelope ciphertext",
            minimumBytes = 1,
            maximumEncodedLength = MAX_CIPHERTEXT_ENCODED_LENGTH,
        )
        requireMatchingSha256(envelope.ciphertextSha256, ciphertext, "encrypted envelope ciphertext hash")

        return ValidatedIncomingEncryptedMessage(
            messageId = id,
            conversationId = conversationId,
            clientMessageId = clientMessageId,
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            senderSignalDeviceId = senderSignalDeviceId,
            senderRegistrationId = senderRegistrationId,
            senderBundleVersion = senderBundleVersion,
            senderIdentityKeySha256 = senderIdentityKeySha256,
            rosterRevision = rosterRevision,
            kind = kind,
            replyToMessageId = replyToMessageId,
            envelopeType = envelopeType,
            sentAt = sentAt,
            ciphertext = ciphertext,
        )
    }

    private fun validateRosterDevice(
        device: MessagingDeviceRosterEntryDto,
        index: Int,
    ): ValidatedMessagingRosterDevice {
        val prefix = "roster device $index"
        val deviceId = required(device.deviceId, "$prefix device ID")
        requireUuid(deviceId, "$prefix device ID")
        val signalDeviceId = requireSignalDeviceId(device.signalDeviceId, "$prefix Signal device ID")
        val userId = required(device.userId, "$prefix user ID")
        requireUuid(userId, "$prefix user ID")
        val registrationId = requireRegistrationId(device.registrationId, "$prefix registration ID")
        requireWire(device.protocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION, "$prefix protocol")
        val bundleVersion = requireBundleVersion(device.bundleVersion, "$prefix bundle version")
        val identityKey = requireSignalValue(
            device.identityKey,
            EC_PUBLIC_KEY_BYTES,
            EC_PUBLIC_KEY_TYPE,
            "$prefix identity key",
        )
        val identityKeySha256 = requireMatchingSha256(
            device.identityKeySha256,
            identityKey,
            "$prefix identity-key hash",
        )
        val signedPrekey = validateSignedPrekey(
            required(device.signedPrekey, "$prefix signed prekey"),
            "$prefix signed prekey",
        )
        val publishedAtValue = required(device.publishedAt, "$prefix publication time")
        val publishedAt = requireTimestamp(publishedAtValue, "$prefix publication time")
        device.rotatedAt?.let {
            requireWire(
                !requireTimestamp(it, "$prefix rotation time").isBefore(publishedAt),
                "$prefix rotation precedes publication",
            )
        }
        requireTimestamp(device.identityKeyChangedAt, "$prefix identity-key activation time")
        requireTimestamp(device.bundleVersionChangedAt, "$prefix bundle activation time")
        return ValidatedMessagingRosterDeviceValue(
            deviceId = deviceId,
            userId = userId,
            signalDeviceId = signalDeviceId,
            registrationId = registrationId,
            protocolVersion = SECURE_MESSAGING_PROTOCOL_VERSION,
            bundleVersion = bundleVersion,
            identityKey = identityKey,
            identityKeySha256 = identityKeySha256,
            signedPrekey = signedPrekey,
            publishedAt = publishedAtValue,
            rotatedAt = device.rotatedAt,
        )
    }

    private fun validateSignedPrekey(
        prekey: MessagingSignedPrekeyDto,
        field: String,
        hashRequired: Boolean = true,
    ): ValidatedMessagingPrekey {
        val id = requirePrekeyId(prekey.prekeyId, "$field ID")
        val publicKey = requireSignalValue(
            prekey.publicKey,
            EC_PUBLIC_KEY_BYTES,
            EC_PUBLIC_KEY_TYPE,
            "$field public key",
        )
        val publicKeySha256 = if (hashRequired || prekey.publicKeySha256 != null) {
            requireMatchingSha256(prekey.publicKeySha256, publicKey, "$field public-key hash")
        } else {
            null
        }
        val signature = requireSignalValue(
            prekey.signature,
            SIGNATURE_BYTES,
            expectedTypeByte = null,
            "$field signature",
        )
        return ValidatedMessagingPrekeyValue(id, publicKey, signature, publicKeySha256)
    }

    private fun validateOneTimePrekey(
        prekey: MessagingOneTimePrekeyDto,
        field: String,
    ): ValidatedMessagingPrekey {
        val id = requirePrekeyId(prekey.prekeyId, "$field ID")
        val publicKey = requireSignalValue(
            prekey.publicKey,
            EC_PUBLIC_KEY_BYTES,
            EC_PUBLIC_KEY_TYPE,
            "$field public key",
        )
        return ValidatedMessagingPrekeyValue(id, publicKey, signature = null, publicKeySha256 = null)
    }

    private fun validatePqPrekey(
        prekey: MessagingPqPrekeyDto,
        field: String,
    ): ValidatedMessagingPrekey {
        val id = requirePrekeyId(prekey.prekeyId, "$field ID")
        val publicKey = requireSignalValue(
            prekey.publicKey,
            PQ_PUBLIC_KEY_BYTES,
            PQ_PUBLIC_KEY_TYPE,
            "$field public key",
        )
        val signature = requireSignalValue(
            prekey.signature,
            SIGNATURE_BYTES,
            expectedTypeByte = null,
            "$field signature",
        )
        return ValidatedMessagingPrekeyValue(id, publicKey, signature, publicKeySha256 = null)
    }

    private fun validateTransparency(
        transparency: MessagingKeyTransparencyDto,
        expectedIdentityHash: String? = null,
        expectedPqLastResortId: Int? = null,
        expectedPqLastResortHash: String? = null,
    ) {
        val revision = required(transparency.revision, "key-transparency revision")
        requireWire(POSITIVE_DECIMAL.matches(revision) && revision != "0", "key-transparency revision")
        requireWire(transparency.eventType in TRANSPARENCY_EVENT_TYPES, "key-transparency event type")
        requireWire(
            transparency.protocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION,
            "key-transparency protocol",
        )
        requireSha256(transparency.eventHash, "key-transparency event hash")
        transparency.previousEventHash?.let { requireSha256(it, "previous key-transparency event hash") }
        val identityHash = requireSha256(
            transparency.identityKeySha256,
            "key-transparency identity-key hash",
        )
        transparency.previousIdentityKeySha256?.let {
            requireSha256(it, "previous key-transparency identity-key hash")
        }
        val pqLastResortId = requirePrekeyId(
            transparency.pqLastResortPrekeyId,
            "key-transparency PQ last-resort prekey ID",
        )
        val pqLastResortHash = requireSha256(
            transparency.pqLastResortPrekeySha256,
            "key-transparency PQ last-resort prekey hash",
        )
        requireTimestamp(transparency.occurredAt, "key-transparency occurrence time")
        expectedIdentityHash?.let {
            requireWire(identityHash == it, "key-transparency identity key changed")
        }
        expectedPqLastResortId?.let {
            requireWire(pqLastResortId == it, "key-transparency PQ last-resort key changed")
        }
        expectedPqLastResortHash?.let {
            requireWire(pqLastResortHash == it, "key-transparency PQ last-resort hash changed")
        }
    }

    private fun validateAttachments(attachments: List<EncryptedAttachmentDto>) {
        requireWire(attachments.size <= MAX_ATTACHMENTS, "encrypted attachment count")
        val ids = mutableSetOf<String>()
        val storageKeys = mutableSetOf<String>()
        attachments.forEachIndexed { index, attachment ->
            val prefix = "encrypted attachment $index"
            val id = required(attachment.id, "$prefix ID")
            requireUuid(id, "$prefix ID")
            requireWire(ids.add(id), "duplicate encrypted attachment ID")
            val storageKey = required(attachment.storageKey, "$prefix storage key")
            requireWire(storageKey.isNotBlank() && storageKey.length <= 512, "$prefix storage key")
            requireWire(storageKeys.add(storageKey), "duplicate encrypted attachment storage key")
            val mediaType = required(attachment.mediaType, "$prefix media type")
            requireWire(mediaType.isNotBlank() && mediaType.length <= 160, "$prefix media type")
            val byteSize = required(attachment.byteSize, "$prefix byte size")
            requireWire(byteSize in 1..MAX_ATTACHMENT_BYTES, "$prefix byte size")
            requireSha256(attachment.ciphertextSha256, "$prefix ciphertext hash")
            attachment.encryptionMetadataCiphertext?.let {
                requireCanonicalBase64(
                    it,
                    "$prefix encryption metadata",
                    minimumBytes = 1,
                    maximumEncodedLength = MAX_ATTACHMENT_METADATA_ENCODED_LENGTH,
                )
            }
        }
    }

    private fun canonicalRosterBytes(
        conversationId: String,
        devices: List<MessagingDeviceRosterEntryDto>,
    ): ByteArray {
        val json = buildString {
            append("{\"schema\":\"kit.messaging.device-roster.v1\",\"conversation_id\":")
            appendJsonString(conversationId)
            append(",\"devices\":[")
            devices.forEachIndexed { index, device ->
                if (index > 0) append(',')
                val signedPrekey = checkNotNull(device.signedPrekey)
                append("{\"device_id\":")
                appendJsonString(checkNotNull(device.deviceId))
                append(",\"user_id\":")
                appendJsonString(checkNotNull(device.userId))
                append(",\"signal_device_id\":${checkNotNull(device.signalDeviceId)}")
                append(",\"registration_id\":${checkNotNull(device.registrationId)}")
                append(",\"protocol_version\":")
                appendJsonString(checkNotNull(device.protocolVersion))
                append(",\"bundle_version\":${checkNotNull(device.bundleVersion)}")
                append(",\"identity_key\":")
                appendJsonString(checkNotNull(device.identityKey))
                append(",\"identity_key_sha256\":")
                appendJsonString(checkNotNull(device.identityKeySha256))
                append(",\"signed_prekey\":{\"prekey_id\":${checkNotNull(signedPrekey.prekeyId)}")
                append(",\"public_key\":")
                appendJsonString(checkNotNull(signedPrekey.publicKey))
                append(",\"public_key_sha256\":")
                appendJsonString(checkNotNull(signedPrekey.publicKeySha256))
                append(",\"signature\":")
                appendJsonString(checkNotNull(signedPrekey.signature))
                append("},\"published_at\":")
                appendJsonString(checkNotNull(device.publishedAt))
                append(",\"rotated_at\":")
                if (device.rotatedAt == null) {
                    append("null")
                } else {
                    appendJsonString(device.rotatedAt)
                }
                append(",\"identity_key_changed_at\":")
                appendJsonString(checkNotNull(device.identityKeyChangedAt))
                append(",\"bundle_version_changed_at\":")
                appendJsonString(checkNotNull(device.bundleVersionChangedAt))
                append('}')
            }
            append("]}")
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun requireSignalValue(
        encoded: String?,
        expectedBytes: Int,
        expectedTypeByte: Int?,
        field: String,
    ): ByteArray {
        val decoded = requireCanonicalBase64(encoded, field)
        requireWire(decoded.size == expectedBytes, "$field length")
        expectedTypeByte?.let {
            requireWire(decoded.first().toInt() and 0xff == it, "$field type")
        }
        return decoded
    }

    private fun requireCanonicalBase64(
        encoded: String?,
        field: String,
        minimumBytes: Int = 0,
        maximumEncodedLength: Int = Int.MAX_VALUE,
    ): ByteArray {
        val value = required(encoded, field)
        requireWire(value.length <= maximumEncodedLength, "$field length")
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            failWire("$field base64")
        }
        requireWire(decoded.size >= minimumBytes, "$field is empty")
        requireWire(Base64.getEncoder().encodeToString(decoded) == value, "$field is not canonical base64")
        return decoded
    }

    private fun requireMatchingSha256(hash: String?, bytes: ByteArray, field: String): String {
        val expected = requireSha256(hash, field)
        requireWire(sha256Hex(bytes) == expected, "$field does not match content")
        return expected
    }

    private fun requireSha256(value: String?, field: String): String {
        val hash = required(value, field)
        requireWire(SHA256_HEX.matches(hash), field)
        return hash
    }

    private fun requireUuid(value: String, field: String) {
        requireWire(UUID_PATTERN.matches(value), field)
    }

    private fun requireTimestamp(value: String?, field: String): Instant {
        val timestamp = required(value, field)
        requireWire(UTC_TIMESTAMP.matches(timestamp), field)
        return try {
            Instant.parse(timestamp)
        } catch (_: RuntimeException) {
            failWire(field)
        }
    }

    private fun requireSignalDeviceId(value: Int?, field: String): Int {
        val id = required(value, field)
        requireWire(id in MIN_SIGNAL_DEVICE_ID..MAX_SIGNAL_DEVICE_ID, field)
        return id
    }

    private fun requireRegistrationId(value: Int?, field: String): Int {
        val id = required(value, field)
        requireWire(id in MIN_REGISTRATION_ID..MAX_REGISTRATION_ID, field)
        return id
    }

    private fun requirePrekeyId(value: Int?, field: String): Int {
        val id = required(value, field)
        requireWire(id in MIN_PREKEY_ID..MAX_PREKEY_ID, field)
        return id
    }

    private fun requireBundleVersion(value: Int?, field: String): Int {
        val version = required(value, field)
        requireWire(version > 0, field)
        return version
    }

    private fun requiredNonNegative(value: Int?, field: String): Int {
        val result = required(value, field)
        requireWire(result >= 0, field)
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun requireWire(condition: Boolean, field: String) {
        if (!condition) failWire(field)
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }

    private fun failWire(field: String): Nothing = throw SecureMessagingWireValidationException(
        "Rejected secure-messaging wire data: $field",
    )

    private fun <T : Any> required(value: T?, field: String): T = value ?: failWire("$field is missing")

    private data class IncomingMessageWire(
        val id: String?,
        val conversationId: String?,
        val clientMessageId: String?,
        val sender: EncryptedMessageSenderDto?,
        val senderDeviceId: String?,
        val senderSignalDeviceId: Int?,
        val senderRegistrationId: Int?,
        val senderProtocolVersion: String?,
        val senderBundleVersion: Int?,
        val senderIdentityKeySha256: String?,
        val rosterRevision: String?,
        val kind: String?,
        val replyToMessageId: String?,
        val envelope: EncryptedMessageEnvelopeDto?,
        val attachments: List<EncryptedAttachmentDto?>?,
        val reactions: List<EncryptedMessageReactionDto?>?,
        val sentAt: String?,
        val revokedAt: String?,
    )

    private const val SHA256_LABEL = "sha256"
    private const val ROSTER_REVISION_PREFIX = "v1:sha256:"
    private const val EC_PUBLIC_KEY_BYTES = 33
    private const val EC_PUBLIC_KEY_TYPE = 5
    private const val SIGNATURE_BYTES = 64
    private const val PQ_PUBLIC_KEY_BYTES = 1569
    private const val PQ_PUBLIC_KEY_TYPE = 8
    private const val MIN_SIGNAL_DEVICE_ID = 1
    private const val MAX_SIGNAL_DEVICE_ID = 127
    private const val MIN_REGISTRATION_ID = 1
    private const val MAX_REGISTRATION_ID = 16380
    private const val MIN_PREKEY_ID = 0
    private const val MAX_PREKEY_ID = 16777215
    private const val MAX_ROSTER_DEVICES = 100
    private const val MAX_CIPHERTEXT_ENCODED_LENGTH = 2_000_000
    private const val MAX_ATTACHMENTS = 20
    private const val MAX_ATTACHMENT_BYTES = 5_368_709_120L
    private const val MAX_ATTACHMENT_METADATA_ENCODED_LENGTH = 16_384
    private const val MESSAGE_CREATED_EVENT = "message.created"
    private const val MESSAGE_RESOURCE_TYPE = "message"
    private const val MESSAGING_DEVICE_RESOURCE = "messaging_device"
    private const val MESSAGING_USER_RESOURCE = "messaging_user"
    private const val ALL_DEVICES_REVOKED_EVENT = "devices.revoked"
    private const val IDENTITY_CHANGED_EVENT = "identity.changed"

    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
    private val POSITIVE_DECIMAL = Regex("^[0-9]+$")
    private val UTC_TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
    private val TRANSPARENCY_EVENT_TYPES = setOf(
        "device.enrolled",
        "identity.changed",
        "protocol.upgraded",
        "pq_last_resort_prekey.rotated",
        "signed_prekey.rotated",
        "one_time_prekeys.replenished",
        "pq_one_time_prekeys.replenished",
    )
    private val DEVICE_LIFECYCLE_EVENT_TYPES = setOf(
        "device.enrolled",
        IDENTITY_CHANGED_EVENT,
        "protocol.upgraded",
        "bundle.rotated",
        "device.revoked",
        ALL_DEVICES_REVOKED_EVENT,
    )
}

class SecureMessagingWireValidationException internal constructor(message: String) :
    IllegalArgumentException(message)

/** An authoritative direct roster bound to one conversation, login user and current device. */
sealed interface ValidatedMessagingDeviceRoster {
    val conversationId: String
    val rosterRevision: String
    val currentDeviceId: String
    val currentUserId: String

    fun memberUserIds(): Set<String>

    fun devices(): List<ValidatedMessagingRosterDevice>
}

/** Public device material that passed roster membership, ordering, hash and wire validation. */
sealed interface ValidatedMessagingRosterDevice {
    val deviceId: String
    val userId: String
    val signalDeviceId: Int
    val registrationId: Int
    val protocolVersion: String
    val bundleVersion: Int
    val identityKeySha256: String
    val signedPrekey: ValidatedMessagingPrekey
    val publishedAt: String
    val rotatedAt: String?

    fun identityKeyBytes(): ByteArray
}

/** A validated EC or PQ prekey. Byte arrays are copied on construction and every read. */
sealed interface ValidatedMessagingPrekey {
    val id: Int
    val publicKeySha256: String?

    fun publicKeyBytes(): ByteArray

    fun signatureBytes(): ByteArray?
}

/** Key-claim result bound to a previously validated authoritative roster. */
sealed interface ValidatedConsumedMessagingKeyBundles {
    val conversationId: String
    val rosterRevision: String
    val currentDeviceId: String
    val currentUserId: String

    fun memberUserIds(): Set<String>

    fun requestedDeviceIds(): Set<String>

    fun bundles(): List<ValidatedConsumedMessagingKeyBundle>
}

/** Remote key material that passed target, roster, activation and cryptographic-shape checks. */
sealed interface ValidatedConsumedMessagingKeyBundle {
    val deviceId: String
    val userId: String
    val signalDeviceId: Int
    val registrationId: Int
    val signedPrekey: ValidatedMessagingPrekey
    val oneTimePrekey: ValidatedMessagingPrekey?
    val pqPrekey: ValidatedMessagingPrekey

    fun identityKeyBytes(): ByteArray
}

private class ValidatedMessagingDeviceRosterValue(
    override val conversationId: String,
    override val rosterRevision: String,
    override val currentDeviceId: String,
    override val currentUserId: String,
    memberUserIds: Set<String>,
    devices: List<ValidatedMessagingRosterDevice>,
) : ValidatedMessagingDeviceRoster {
    private val immutableMemberUserIds = memberUserIds.toSet()
    private val immutableDevices = devices.toList()

    override fun memberUserIds(): Set<String> = immutableMemberUserIds.toSet()

    override fun devices(): List<ValidatedMessagingRosterDevice> = immutableDevices.toList()
}

private class ValidatedMessagingRosterDeviceValue(
    override val deviceId: String,
    override val userId: String,
    override val signalDeviceId: Int,
    override val registrationId: Int,
    override val protocolVersion: String,
    override val bundleVersion: Int,
    identityKey: ByteArray,
    override val identityKeySha256: String,
    override val signedPrekey: ValidatedMessagingPrekey,
    override val publishedAt: String,
    override val rotatedAt: String?,
) : ValidatedMessagingRosterDevice {
    private val immutableIdentityKey = identityKey.copyOf()

    override fun identityKeyBytes(): ByteArray = immutableIdentityKey.copyOf()
}

private class ValidatedMessagingPrekeyValue(
    override val id: Int,
    publicKey: ByteArray,
    signature: ByteArray?,
    override val publicKeySha256: String?,
) : ValidatedMessagingPrekey {
    private val immutablePublicKey = publicKey.copyOf()
    private val immutableSignature = signature?.copyOf()

    override fun publicKeyBytes(): ByteArray = immutablePublicKey.copyOf()

    override fun signatureBytes(): ByteArray? = immutableSignature?.copyOf()
}

private class ValidatedConsumedMessagingKeyBundlesValue(
    override val conversationId: String,
    override val rosterRevision: String,
    override val currentDeviceId: String,
    override val currentUserId: String,
    memberUserIds: Set<String>,
    requestedDeviceIds: Set<String>,
    bundles: List<ValidatedConsumedMessagingKeyBundle>,
) : ValidatedConsumedMessagingKeyBundles {
    private val immutableMemberUserIds = memberUserIds.toSet()
    private val immutableRequestedDeviceIds = requestedDeviceIds.toSet()
    private val immutableBundles = bundles.toList()

    override fun memberUserIds(): Set<String> = immutableMemberUserIds.toSet()

    override fun requestedDeviceIds(): Set<String> = immutableRequestedDeviceIds.toSet()

    override fun bundles(): List<ValidatedConsumedMessagingKeyBundle> = immutableBundles.toList()
}

private class ValidatedConsumedMessagingKeyBundleValue(
    override val deviceId: String,
    override val userId: String,
    override val signalDeviceId: Int,
    override val registrationId: Int,
    identityKey: ByteArray,
    override val signedPrekey: ValidatedMessagingPrekey,
    override val oneTimePrekey: ValidatedMessagingPrekey?,
    override val pqPrekey: ValidatedMessagingPrekey,
) : ValidatedConsumedMessagingKeyBundle {
    private val immutableIdentityKey = identityKey.copyOf()

    override fun identityKeyBytes(): ByteArray = immutableIdentityKey.copyOf()
}

/** Ciphertext bytes that passed route, envelope and digest validation; this is not decrypted data. */
class ValidatedIncomingEncryptedMessage internal constructor(
    val messageId: String,
    val conversationId: String,
    val clientMessageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val senderSignalDeviceId: Int,
    val senderRegistrationId: Int,
    val senderBundleVersion: Int,
    val senderIdentityKeySha256: String,
    val rosterRevision: String,
    val kind: String,
    val replyToMessageId: String?,
    val envelopeType: String,
    val sentAt: Instant,
    private val ciphertext: ByteArray,
) {
    fun ciphertextBytes(): ByteArray = ciphertext.copyOf()
}

class ValidatedMessagingRosterRefresh internal constructor(
    val conversationId: String,
    val eventType: String,
    val userId: String,
    val deviceId: String?,
    val signalDeviceId: Int?,
    val registrationId: Int?,
    val previousRegistrationId: Int?,
    val protocolVersion: String?,
    val previousProtocolVersion: String?,
    val bundleVersion: Int?,
    val identityKeySha256: String?,
    val previousIdentityKeySha256: String?,
    val revokedDeviceCount: Int?,
    val transitionedAt: Instant,
    val transitionHash: String,
)
