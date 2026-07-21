package com.kit.wallet.data.remote

import java.time.Instant

/**
 * Protocol-independent validation for the authenticated secure-messaging transport.
 *
 * This boundary validates server identity, routing and pagination metadata only. It does not
 * generate keys, establish a session, decrypt ciphertext or make secure messaging available.
 */
object SecureMessagingTransportValidator {
    fun requireCurrentServerDevice(response: DeviceListDto): DeviceDto {
        requireTransport(response.items.size <= MAX_SERVER_DEVICES, "device list is too large")
        val seenIds = mutableSetOf<String>()
        response.items.forEachIndexed { index, device ->
            requireUuid(device.id, "device $index ID")
            requireTransport(seenIds.add(device.id), "device list contains duplicate IDs")
            requireTransport(device.name.isNotBlank(), "device $index name")
            requireTransport(device.platform.isNotBlank(), "device $index platform")
            val createdAt = device.createdAt?.let {
                requireTimestamp(it, "device $index creation time")
            }
            val lastSeenAt = device.lastSeenAt?.let {
                requireTimestamp(it, "device $index last-seen time")
            }
            val trustExpiresAt = device.trustExpiresAt?.let {
                requireTimestamp(it, "device $index trust expiry")
            }
            if (createdAt != null && lastSeenAt != null) {
                requireTransport(!lastSeenAt.isBefore(createdAt), "device $index last-seen chronology")
            }
            if (createdAt != null && trustExpiresAt != null) {
                requireTransport(!trustExpiresAt.isBefore(createdAt), "device $index trust chronology")
            }
        }

        val current = response.items.filter { it.isCurrent == true }
        requireTransport(current.size == 1, "device list must identify exactly one current device")
        return current.single()
    }

    /**
     * Validates the complete conversation collection and returns only direct conversations.
     * Other recognized conversation types remain server resources but are not treated as secure
     * Android chats because the current wire protocol is direct-message only.
     */
    fun validateDirectConversations(
        response: MessagingConversationListDto,
        currentUserId: String,
    ): List<ValidatedDirectConversation> {
        requireUuid(currentUserId, "current user ID")
        val nullableItems = required(response.items, "conversation list")
        requireTransport(nullableItems.size <= MAX_CONVERSATIONS, "conversation list is too large")
        val seenConversationIds = mutableSetOf<String>()

        return buildList {
            nullableItems.forEachIndexed { index, nullableConversation ->
                val conversation = required(nullableConversation, "conversation $index")
                val id = required(conversation.id, "conversation $index ID")
                requireUuid(id, "conversation $index ID")
                requireTransport(
                    seenConversationIds.add(id),
                    "conversation list contains duplicate IDs",
                )
                val type = required(conversation.type, "conversation $index type")
                requireTransport(type in CONVERSATION_TYPES, "conversation $index type")
                val createdAt = requireTimestamp(
                    conversation.createdAt,
                    "conversation $index creation time",
                )
                val updatedAt = requireTimestamp(
                    conversation.updatedAt,
                    "conversation $index update time",
                )
                requireTransport(!updatedAt.isBefore(createdAt), "conversation $index chronology")

                if (type != DIRECT_CONVERSATION_TYPE) return@forEachIndexed

                requireTransport(conversation.parentId == null, "direct conversation has a parent")
                val createdBy = required(
                    conversation.createdBy,
                    "direct conversation $index creator",
                )
                requireUuid(createdBy, "direct conversation $index creator")
                val role = required(conversation.role, "direct conversation $index current role")
                requireTransport(role in MEMBER_ROLES, "direct conversation $index current role")

                val nullableMembers = required(
                    conversation.members,
                    "direct conversation $index members",
                )
                requireTransport(
                    nullableMembers.size == DIRECT_MEMBER_COUNT,
                    "direct conversation must contain exactly two members",
                )
                val members = nullableMembers.mapIndexed { memberIndex, nullableMember ->
                    val member = required(
                        nullableMember,
                        "direct conversation $index member $memberIndex",
                    )
                    val userId = required(
                        member.userId,
                        "direct conversation $index member $memberIndex user ID",
                    )
                    requireUuid(
                        userId,
                        "direct conversation $index member $memberIndex user ID",
                    )
                    val memberRole = required(
                        member.role,
                        "direct conversation $index member $memberIndex role",
                    )
                    requireTransport(
                        memberRole in MEMBER_ROLES,
                        "direct conversation $index member $memberIndex role",
                    )
                    val joinedAt = requireTimestamp(
                        member.joinedAt,
                        "direct conversation $index member $memberIndex join time",
                    )
                    requireTransport(
                        !joinedAt.isBefore(createdAt),
                        "direct conversation $index member $memberIndex chronology",
                    )
                    ValidatedDirectConversationMember(
                        userId = userId,
                        name = member.name?.trim()?.takeIf(String::isNotEmpty),
                        role = memberRole,
                        joinedAt = joinedAt,
                    )
                }
                requireTransport(
                    members.map(ValidatedDirectConversationMember::userId).distinct().size ==
                        DIRECT_MEMBER_COUNT,
                    "direct conversation contains duplicate members",
                )
                requireTransport(
                    members.count { it.userId == currentUserId } == 1,
                    "direct conversation does not contain the current user exactly once",
                )
                requireTransport(
                    members.any { it.userId == createdBy },
                    "direct conversation creator is not an active member",
                )
                val currentMember = members.single { it.userId == currentUserId }
                requireTransport(
                    currentMember.role == role,
                    "direct conversation current role disagrees with membership",
                )
                val peer = members.single { it.userId != currentUserId }

                add(
                    ValidatedDirectConversation(
                        conversationId = id,
                        peerUserId = peer.userId,
                        peerName = peer.name,
                        currentUserRole = role,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    ),
                )
            }
        }
    }

    fun validateSyncPage(
        response: MessagingSyncDto,
        currentUserId: String,
        currentDeviceId: String,
        requestedCursor: String?,
        requestedLimit: Int,
        previousEventId: Long? = null,
    ): ValidatedMessagingSyncPage {
        requireUuid(currentUserId, "current user ID")
        requireUuid(currentDeviceId, "current device ID")
        validateSyncRequest(requestedCursor, requestedLimit, previousEventId)

        val page = required(response.page, "messaging sync page")
        val nextCursor = required(page.nextCursor, "messaging sync next cursor")
        requireCursor(nextCursor, "messaging sync next cursor")
        val hasMore = required(page.hasMore, "messaging sync has-more state")
        val limit = required(page.limit, "messaging sync page limit")
        requireTransport(limit == requestedLimit, "messaging sync page limit changed")
        if (requestedCursor != null && nextCursor == requestedCursor) {
            requireTransport(
                !hasMore && response.events.orEmpty().isEmpty(),
                "messaging sync cursor did not advance",
            )
        }

        val nullableEvents = required(response.events, "messaging sync events")
        requireTransport(
            nullableEvents.size <= requestedLimit,
            "messaging sync returned more events than requested",
        )
        val validatedEvents = ArrayList<ValidatedMessagingSyncEvent>(nullableEvents.size)
        var lastEventId = previousEventId
        nullableEvents.forEachIndexed { index, nullableEvent ->
            val event = required(nullableEvent, "messaging sync event $index")
            val eventIdText = required(event.id, "messaging sync event $index ID")
            requireTransport(
                POSITIVE_DECIMAL.matches(eventIdText) && eventIdText != "0",
                "messaging sync event $index ID",
            )
            val eventId = eventIdText.toLongOrNull()
                ?: rejectTransport("messaging sync event $index ID is too large")
            lastEventId?.let {
                requireTransport(eventId > it, "messaging sync event IDs are not strictly increasing")
            }
            lastEventId = eventId

            validatedEvents += validateSyncEvent(
                event = event,
                eventId = eventId,
                currentUserId = currentUserId,
                currentDeviceId = currentDeviceId,
            )
        }

        return ValidatedMessagingSyncPage(
            events = validatedEvents,
            nextCursor = nextCursor,
            hasMore = hasMore,
            limit = limit,
            lastEventId = lastEventId,
        )
    }

    /** Validates all caller-controlled sync parameters before an HTTP request is issued. */
    fun validateSyncRequest(
        cursor: String?,
        limit: Int,
        previousEventId: Long?,
    ) {
        requireTransport(limit in 1..MAX_SYNC_PAGE_SIZE, "requested sync limit")
        cursor?.let { requireCursor(it, "requested sync cursor") }
        previousEventId?.let {
            requireTransport(it >= 0, "previous messaging event ID")
        }
    }

    fun validateOutboundSendResponse(
        response: EncryptedMessageDto,
        expectedConversationId: String,
        expectedClientMessageId: String,
        expectedCurrentUserId: String,
        expectedCurrentDeviceId: String,
        expectedRosterRevision: String,
    ): ValidatedOutboundEncryptedMessage {
        requireUuid(expectedConversationId, "expected conversation ID")
        requireUuid(expectedClientMessageId, "expected client message ID")
        requireUuid(expectedCurrentUserId, "expected current user ID")
        requireUuid(expectedCurrentDeviceId, "expected current device ID")
        requireTransport(
            SECURE_MESSAGING_ROSTER_REVISION.matches(expectedRosterRevision),
            "expected roster revision",
        )
        return validateOwnOutboundMessage(
            message = response,
            expectedConversationId = expectedConversationId,
            expectedClientMessageId = expectedClientMessageId,
            expectedCurrentUserId = expectedCurrentUserId,
            expectedCurrentDeviceId = expectedCurrentDeviceId,
            expectedRosterRevision = expectedRosterRevision,
        )
    }

    fun validateDeliveryAcknowledgement(
        response: MessageDeliveryAcknowledgementDto,
        expectedCurrentDeviceId: String,
        expectedMessageIds: List<String>,
    ): ValidatedMessageDeliveryAcknowledgement {
        requireUuid(expectedCurrentDeviceId, "expected current device ID")
        requireTransport(
            expectedMessageIds.size in 1..MAX_DELIVERY_ACKNOWLEDGEMENT_BATCH,
            "expected delivery acknowledgement batch size",
        )
        expectedMessageIds.forEachIndexed { index, id ->
            requireUuid(id, "expected delivery message $index ID")
        }
        requireTransport(
            expectedMessageIds.distinct().size == expectedMessageIds.size,
            "expected delivery acknowledgement contains duplicate IDs",
        )
        requireTransport(
            response.deliveryState == DELIVERY_STATE,
            "delivery acknowledgement state",
        )
        requireTransport(
            response.deviceId == expectedCurrentDeviceId,
            "delivery acknowledgement device changed",
        )
        val acknowledgedCount = required(
            response.acknowledgedCount,
            "delivery acknowledgement count",
        )
        val newlyAcknowledgedCount = required(
            response.newlyAcknowledgedCount,
            "new delivery acknowledgement count",
        )
        requireTransport(
            acknowledgedCount == expectedMessageIds.size,
            "delivery acknowledgement count changed",
        )
        requireTransport(
            newlyAcknowledgedCount in 0..acknowledgedCount,
            "new delivery acknowledgement count",
        )
        val nullableItems = required(response.items, "delivery acknowledgement items")
        requireTransport(
            nullableItems.size == expectedMessageIds.size,
            "delivery acknowledgement item count changed",
        )
        val seenMessageIds = mutableSetOf<String>()
        val items = nullableItems.mapIndexed { index, nullableItem ->
            val item = required(nullableItem, "delivery acknowledgement item $index")
            val messageId = required(
                item.messageId,
                "delivery acknowledgement item $index message ID",
            )
            requireUuid(messageId, "delivery acknowledgement item $index message ID")
            requireTransport(seenMessageIds.add(messageId), "delivery acknowledgement contains duplicate IDs")
            val deliveredAt = requireTimestamp(
                item.deliveredToDeviceAt,
                "delivery acknowledgement item $index time",
            )
            ValidatedMessageDeliveryReceipt(messageId, deliveredAt)
        }
        requireTransport(
            seenMessageIds == expectedMessageIds.toSet(),
            "delivery acknowledgement message IDs changed",
        )
        return ValidatedMessageDeliveryAcknowledgement(
            deviceId = expectedCurrentDeviceId,
            newlyAcknowledgedCount = newlyAcknowledgedCount,
            items = items,
        )
    }

    private fun validateSyncEvent(
        event: MessagingSyncEventDto,
        eventId: Long,
        currentUserId: String,
        currentDeviceId: String,
    ): ValidatedMessagingSyncEvent {
        val type = required(event.type, "messaging sync event type")
        requireTransport(type in SYNC_EVENT_TYPES, "messaging sync event type")
        val conversationId = required(event.conversationId, "messaging sync conversation ID")
        requireUuid(conversationId, "messaging sync conversation ID")
        val occurredAt = requireTimestamp(event.occurredAt, "messaging sync event time")

        return when (type) {
            MESSAGE_CREATED_EVENT -> validateMessageEvent(
                event,
                eventId,
                conversationId,
                occurredAt,
                currentUserId,
                currentDeviceId,
            )
            in DEVICE_LIFECYCLE_EVENT_TYPES -> {
                val refresh = SecureMessagingWireValidator.validateDeviceLifecycleEvent(
                    event,
                    conversationId,
                )
                ValidatedMessagingSyncEvent.RosterRefresh(
                    eventId = eventId,
                    conversationId = conversationId,
                    occurredAt = occurredAt,
                    refresh = refresh,
                )
            }
            CONVERSATION_CREATED_EVENT -> {
                requireTransport(event.resourceType == CONVERSATION_RESOURCE, "conversation event resource type")
                requireTransport(event.resourceId == conversationId, "conversation event resource changed")
                required(event.data, "conversation event data")
                ValidatedMessagingSyncEvent.Metadata(eventId, type, conversationId, occurredAt)
            }
            in MEMBERSHIP_EVENT_TYPES -> {
                requireTransport(
                    event.resourceType == CONVERSATION_MEMBER_RESOURCE,
                    "membership event resource type",
                )
                val resourceId = required(event.resourceId, "membership event resource ID")
                requireTransport(
                    resourceId.matches(Regex("^${Regex.escape(conversationId)}:[1-9][0-9]*$")),
                    "membership event resource ID",
                )
                val data = required(event.data, "membership event data")
                requireUuid(required(data.userId, "membership event user ID"), "membership event user ID")
                ValidatedMessagingSyncEvent.Metadata(eventId, type, conversationId, occurredAt)
            }
            else -> rejectTransport("messaging sync event type")
        }
    }

    private fun validateMessageEvent(
        event: MessagingSyncEventDto,
        eventId: Long,
        conversationId: String,
        occurredAt: Instant,
        currentUserId: String,
        currentDeviceId: String,
    ): ValidatedMessagingSyncEvent {
        requireTransport(event.resourceType == MESSAGE_RESOURCE, "message event resource type")
        val data = required(event.data, "message event data")
        val senderDeviceId = required(data.senderDeviceId, "message event sender device ID")
        val validated = if (senderDeviceId == currentDeviceId) {
            val outbound = validateOwnOutboundMessage(
                message = data.toEncryptedMessageDto(),
                expectedConversationId = conversationId,
                expectedClientMessageId = required(data.clientMessageId, "outbound client message ID"),
                expectedCurrentUserId = currentUserId,
                expectedCurrentDeviceId = currentDeviceId,
                expectedRosterRevision = required(data.rosterRevision, "outbound roster revision"),
            )
            requireTransport(event.resourceId == outbound.messageId, "message event resource changed")
            requireTransport(!occurredAt.isBefore(outbound.sentAt), "message event predates its message")
            return ValidatedMessagingSyncEvent.OutboundMessage(
                eventId = eventId,
                conversationId = conversationId,
                occurredAt = occurredAt,
                message = outbound,
            )
        } else {
            SecureMessagingWireValidator.validateIncomingEncryptedMessageEvent(
                event,
                conversationId,
                currentDeviceId,
            )
        }
        return ValidatedMessagingSyncEvent.IncomingMessage(
            eventId = eventId,
            conversationId = conversationId,
            occurredAt = occurredAt,
            message = validated,
        )
    }

    private fun validateOwnOutboundMessage(
        message: EncryptedMessageDto,
        expectedConversationId: String,
        expectedClientMessageId: String,
        expectedCurrentUserId: String,
        expectedCurrentDeviceId: String,
        expectedRosterRevision: String,
    ): ValidatedOutboundEncryptedMessage {
        val messageId = required(message.id, "outbound message ID")
        requireUuid(messageId, "outbound message ID")
        requireTransport(message.conversationId == expectedConversationId, "outbound conversation changed")
        requireTransport(message.clientMessageId == expectedClientMessageId, "outbound client message ID changed")
        val sender = required(message.sender, "outbound message sender")
        requireTransport(sender.id == expectedCurrentUserId, "outbound sender changed")
        requireTransport(!sender.name.isNullOrBlank(), "outbound sender name")
        requireTransport(message.senderDeviceId == expectedCurrentDeviceId, "outbound sender device changed")
        requireSignalDeviceId(message.senderSignalDeviceId, "outbound sender Signal device ID")
        requireRegistrationId(message.senderRegistrationId, "outbound sender registration ID")
        requireTransport(
            message.senderProtocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION,
            "outbound sender protocol",
        )
        val senderBundleVersion = required(message.senderBundleVersion, "outbound sender bundle version")
        requireTransport(senderBundleVersion > 0, "outbound sender bundle version")
        requireSha256(message.senderIdentityKeySha256, "outbound sender identity-key hash")
        requireTransport(message.rosterRevision == expectedRosterRevision, "outbound roster revision changed")
        requireTransport(
            SECURE_MESSAGING_ROSTER_REVISION.matches(expectedRosterRevision),
            "outbound roster revision",
        )
        requireTransport(message.kind == ENCRYPTED_MESSAGE_KIND, "outbound message kind")
        message.replyToMessageId?.let { requireUuid(it, "outbound reply target") }
        requireTransport(
            required(message.attachments, "outbound attachments").isEmpty(),
            "outbound text message contains attachments",
        )
        requireTransport(
            required(message.reactions, "outbound reactions").isEmpty(),
            "v2 outbound text messages cannot contain reactions",
        )
        val sentAt = requireTimestamp(message.sentAt, "outbound send time")
        requireTransport(message.revokedAt == null, "outbound message is revoked")
        // The backend intentionally excludes the sending device from fan-out. Accepting an
        // envelope here would confuse untrusted echoed bytes with a recipient delivery.
        requireTransport(message.envelope == null, "outbound response contains a sender envelope")
        return ValidatedOutboundEncryptedMessage(
            messageId = messageId,
            conversationId = expectedConversationId,
            clientMessageId = expectedClientMessageId,
            senderDeviceId = expectedCurrentDeviceId,
            rosterRevision = expectedRosterRevision,
            senderBundleVersion = senderBundleVersion,
            sentAt = sentAt,
        )
    }

    private fun MessagingSyncEventDataDto.toEncryptedMessageDto() = EncryptedMessageDto(
        id = id,
        conversationId = conversationId,
        clientMessageId = clientMessageId,
        sender = sender,
        senderDeviceId = senderDeviceId,
        senderSignalDeviceId = senderSignalDeviceId,
        senderRegistrationId = senderRegistrationId,
        senderProtocolVersion = senderProtocolVersion,
        senderBundleVersion = senderBundleVersion,
        senderIdentityKeySha256 = senderIdentityKeySha256,
        rosterRevision = rosterRevision,
        kind = kind,
        replyToMessageId = replyToMessageId,
        envelope = envelope,
        attachments = attachments,
        reactions = reactions,
        sentAt = sentAt,
        revokedAt = revokedAt,
    )

    private fun requireCursor(value: String, field: String) {
        requireTransport(value.length <= MAX_CURSOR_LENGTH && CURSOR.matches(value), field)
    }

    private fun requireUuid(value: String, field: String) {
        requireTransport(UUID_PATTERN.matches(value), field)
    }

    private fun requireSha256(value: String?, field: String) {
        requireTransport(value != null && SHA256_HEX.matches(value), field)
    }

    private fun requireTimestamp(value: String?, field: String): Instant {
        val timestamp = required(value, field)
        requireTransport(UTC_TIMESTAMP.matches(timestamp), field)
        return try {
            Instant.parse(timestamp)
        } catch (_: RuntimeException) {
            rejectTransport(field)
        }
    }

    private fun requireSignalDeviceId(value: Int?, field: String) {
        requireTransport(value != null && value in 1..127, field)
    }

    private fun requireRegistrationId(value: Int?, field: String) {
        requireTransport(value != null && value in 1..16380, field)
    }

    private fun requireTransport(condition: Boolean, field: String) {
        if (!condition) rejectTransport(field)
    }

    private fun rejectTransport(field: String): Nothing = throw SecureMessagingWireValidationException(
        "Rejected secure-messaging transport data: $field",
    )

    private fun <T : Any> required(value: T?, field: String): T =
        value ?: rejectTransport("$field is missing")

    private const val MAX_SERVER_DEVICES = 1_000
    private const val MAX_CONVERSATIONS = 10_000
    private const val MAX_SYNC_PAGE_SIZE = 100
    private const val MAX_CURSOR_LENGTH = 2_048
    private const val DIRECT_MEMBER_COUNT = 2
    private const val DELIVERY_STATE = "delivered_to_device"
    private const val MESSAGE_CREATED_EVENT = "message.created"
    private const val CONVERSATION_CREATED_EVENT = "conversation.created"
    private const val MESSAGE_RESOURCE = "message"
    private const val CONVERSATION_RESOURCE = "conversation"
    private const val CONVERSATION_MEMBER_RESOURCE = "conversation_member"

    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
    private val POSITIVE_DECIMAL = Regex("^[0-9]+$")
    private val CURSOR = Regex("^[A-Za-z0-9_-]+$")
    private val UTC_TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
    private val CONVERSATION_TYPES = setOf("direct", "group", "community", "channel")
    private val MEMBER_ROLES = setOf("owner", "admin", "moderator", "member")
    private val MEMBERSHIP_EVENT_TYPES = setOf(
        "membership.added",
        "membership.role_changed",
        "membership.removed",
    )
    private val DEVICE_LIFECYCLE_EVENT_TYPES = setOf(
        "device.enrolled",
        "identity.changed",
        "protocol.upgraded",
        "bundle.rotated",
        "device.revoked",
        "devices.revoked",
    )
    private val SYNC_EVENT_TYPES = setOf(
        MESSAGE_CREATED_EVENT,
        CONVERSATION_CREATED_EVENT,
    ) + MEMBERSHIP_EVENT_TYPES + DEVICE_LIFECYCLE_EVENT_TYPES
}

data class ValidatedDirectConversationMember(
    val userId: String,
    val name: String?,
    val role: String,
    val joinedAt: Instant,
)

data class ValidatedDirectConversation(
    val conversationId: String,
    val peerUserId: String,
    val peerName: String?,
    val currentUserRole: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ValidatedMessagingSyncPage(
    val events: List<ValidatedMessagingSyncEvent>,
    val nextCursor: String,
    val hasMore: Boolean,
    val limit: Int,
    val lastEventId: Long?,
)

sealed interface ValidatedMessagingSyncEvent {
    val eventId: Long
    val conversationId: String
    val occurredAt: Instant

    data class IncomingMessage(
        override val eventId: Long,
        override val conversationId: String,
        override val occurredAt: Instant,
        val message: ValidatedIncomingEncryptedMessage,
    ) : ValidatedMessagingSyncEvent

    data class OutboundMessage(
        override val eventId: Long,
        override val conversationId: String,
        override val occurredAt: Instant,
        val message: ValidatedOutboundEncryptedMessage,
    ) : ValidatedMessagingSyncEvent

    data class RosterRefresh(
        override val eventId: Long,
        override val conversationId: String,
        override val occurredAt: Instant,
        val refresh: ValidatedMessagingRosterRefresh,
    ) : ValidatedMessagingSyncEvent

    data class Metadata(
        override val eventId: Long,
        val type: String,
        override val conversationId: String,
        override val occurredAt: Instant,
    ) : ValidatedMessagingSyncEvent
}

data class ValidatedOutboundEncryptedMessage(
    val messageId: String,
    val conversationId: String,
    val clientMessageId: String,
    val senderDeviceId: String,
    val rosterRevision: String,
    val senderBundleVersion: Int,
    val sentAt: Instant,
)

data class ValidatedMessageDeliveryReceipt(
    val messageId: String,
    val deliveredAt: Instant,
)

data class ValidatedMessageDeliveryAcknowledgement(
    val deviceId: String,
    val newlyAcknowledgedCount: Int,
    val items: List<ValidatedMessageDeliveryReceipt>,
)
