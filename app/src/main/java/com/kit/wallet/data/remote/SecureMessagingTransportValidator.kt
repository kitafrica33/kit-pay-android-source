package com.kit.wallet.data.remote

import com.kit.wallet.data.media.isTrustedProfileAvatarUrl
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
     * Validates the complete conversation collection and returns the encryptable ones.
     *
     * Direct chats and groups are both carried by the pairwise wire protocol and both come
     * back. Communities and channels stay server resources the Android client never opens:
     * their audiences are unbounded, which pairwise fan-out cannot express.
     */
    fun validateConversations(
        response: MessagingConversationListDto,
        currentUserId: String,
    ): List<ValidatedConversation> {
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

                if (type !in ENCRYPTABLE_CONVERSATION_TYPES) return@forEachIndexed

                add(
                    validateEncryptableConversation(
                        conversation = conversation,
                        index = index,
                        id = id,
                        type = type,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        currentUserId = currentUserId,
                    ),
                )
            }
        }
    }

    /** Validates one already-typed direct or group conversation body. */
    fun validateConversation(
        response: MessagingConversationDto,
        currentUserId: String,
    ): ValidatedConversation {
        requireUuid(currentUserId, "current user ID")
        val id = required(response.id, "conversation ID")
        requireUuid(id, "conversation ID")
        val type = required(response.type, "conversation type")
        requireTransport(type in ENCRYPTABLE_CONVERSATION_TYPES, "conversation type")
        val createdAt = requireTimestamp(response.createdAt, "conversation creation time")
        val updatedAt = requireTimestamp(response.updatedAt, "conversation update time")
        requireTransport(!updatedAt.isBefore(createdAt), "conversation chronology")

        return validateEncryptableConversation(
            conversation = response,
            index = 0,
            id = id,
            type = type,
            createdAt = createdAt,
            updatedAt = updatedAt,
            currentUserId = currentUserId,
        )
    }

    private fun validateEncryptableConversation(
        conversation: MessagingConversationDto,
        index: Int,
        id: String,
        type: String,
        createdAt: Instant,
        updatedAt: Instant,
        currentUserId: String,
    ): ValidatedConversation {
        val group = type == GROUP_CONVERSATION_TYPE
        val label = if (group) "group conversation $index" else "direct conversation $index"

        requireTransport(conversation.parentId == null, "$label has a parent")
        val createdBy = required(conversation.createdBy, "$label creator")
        requireUuid(createdBy, "$label creator")
        val role = required(conversation.role, "$label current role")
        requireTransport(role in MEMBER_ROLES, "$label current role")

        // A title is exactly the disclosure a group makes and a direct chat does not. Older
        // servers may still return otherwise-valid titles with Unicode White_Space at an edge,
        // so accept that legacy shape but expose only the same canonical title iOS and current
        // servers use. Bounds and control-character checks still apply to the canonical value.
        val title = conversation.title
            ?.let(::normalizeMessagingGroupTitle)
            ?.takeIf(String::isNotEmpty)
        if (group) {
            val groupTitle = required(title, "$label title")
            requireTransport(
                isValidMessagingGroupTitle(groupTitle),
                "$label title length",
            )
        } else {
            requireTransport(conversation.title == null, "$label carries a title")
        }

        // Identity fields ride the same rule as the title: a group may carry them, a direct
        // chat never does — a server that says otherwise is not describing a conversation this
        // client knows how to trust. The description is normalized the way the server itself
        // normalizes it, so legacy padding cannot make one value read as two.
        val description = conversation.description
            ?.let(::normalizeMessagingGroupDescription)
            ?.takeIf(String::isNotEmpty)
        if (group) {
            description?.let {
                requireTransport(isValidMessagingGroupDescription(it), "$label description bounds")
            }
        } else {
            requireTransport(conversation.description == null, "$label carries a description")
        }

        val photoUrl = conversation.photoUrl?.trim()?.takeIf(String::isNotEmpty)
        if (group) {
            photoUrl?.let {
                requireTransport(isPlausibleGroupPhotoUrl(it), "$label photo URL")
            }
        } else {
            requireTransport(conversation.photoUrl == null, "$label carries a photo")
        }

        val nullableMembers = required(conversation.members, "$label members")
        val memberBounds = if (group) 1..MAX_GROUP_MEMBERS else DIRECT_MEMBER_COUNT..DIRECT_MEMBER_COUNT
        requireTransport(nullableMembers.size in memberBounds, "$label member count")

        val members = nullableMembers.mapIndexed { memberIndex, nullableMember ->
            val member = required(nullableMember, "$label member $memberIndex")
            val userId = required(member.userId, "$label member $memberIndex user ID")
            requireUuid(userId, "$label member $memberIndex user ID")
            val memberRole = required(member.role, "$label member $memberIndex role")
            requireTransport(memberRole in MEMBER_ROLES, "$label member $memberIndex role")
            val joinedAt = requireTimestamp(
                member.joinedAt,
                "$label member $memberIndex join time",
            )
            requireTransport(
                !joinedAt.isBefore(createdAt),
                "$label member $memberIndex chronology",
            )
            // Presentation metadata is optional and never participates in membership authority.
            // Malformed values are discarded individually so they cannot mint a badge, trigger an
            // off-origin image request, or make an otherwise valid encrypted conversation vanish.
            val avatarUrl = member.avatarUrl
                ?.trim()
                ?.takeIf(::isTrustedProfileAvatarUrl)
            val verification = validatedAccountVerification(member.verification)
            ValidatedConversationMember(
                userId = userId,
                name = member.name?.trim()?.takeIf(String::isNotEmpty),
                role = memberRole,
                joinedAt = joinedAt,
                avatarUrl = avatarUrl,
                verification = verification,
            )
        }
        requireTransport(
            members.map(ValidatedConversationMember::userId).distinct().size == members.size,
            "$label contains duplicate members",
        )
        requireTransport(
            members.count { it.userId == currentUserId } == 1,
            "$label does not contain the current user exactly once",
        )
        val currentMember = members.single { it.userId == currentUserId }
        requireTransport(
            currentMember.role == role,
            "$label current role disagrees with membership",
        )

        // Only a direct chat can promise its creator is still a member: a group's founder may
        // have handed it over and left, and the group is no less valid for it.
        if (!group) {
            requireTransport(members.any { it.userId == createdBy }, "$label creator is not an active member")
        }

        return ValidatedConversation(
            conversationId = id,
            type = type,
            title = title,
            description = if (group) description else null,
            photoUrl = if (group) photoUrl else null,
            createdBy = createdBy,
            viewerUserId = currentUserId,
            currentUserRole = role,
            members = members,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    fun validateSyncPage(
        response: MessagingSyncDto,
        currentUserId: String,
        currentDeviceId: String,
        requestedCursor: String?,
        requestedLimit: Int,
        previousEventId: Long? = null,
        currentEnrollmentEpoch: Long? = null,
    ): ValidatedMessagingSyncPage {
        requireUuid(currentUserId, "current user ID")
        requireUuid(currentDeviceId, "current device ID")
        validateSyncRequest(requestedCursor, requestedLimit, previousEventId)
        currentEnrollmentEpoch?.let {
            requireTransport(it > 0, "current messaging enrollment epoch")
        }

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
                currentEnrollmentEpoch = currentEnrollmentEpoch,
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

    fun validateHistoryBackfillCandidates(
        response: MessagingHistoryBackfillCandidatesDto,
        authoritativeRoster: ValidatedMessagingDeviceRoster,
        expectedConversationId: String,
        expectedCurrentUserId: String,
        expectedCurrentDeviceId: String,
        expectedCurrentEnrollmentEpoch: Long,
        expectedTargetDeviceId: String,
        expectedTargetEnrollmentEpoch: Long,
        requestedAfter: String?,
        requestedLimit: Int,
    ): ValidatedMessagingHistoryBackfillPage {
        requireUuid(expectedConversationId, "history conversation ID")
        requireUuid(expectedCurrentUserId, "history current user ID")
        requireUuid(expectedCurrentDeviceId, "history current device ID")
        requireTransport(expectedCurrentEnrollmentEpoch > 0, "history current enrollment epoch")
        requireUuid(expectedTargetDeviceId, "history target device ID")
        requireTransport(
            expectedTargetDeviceId != expectedCurrentDeviceId,
            "history target is the current device",
        )
        requireTransport(expectedTargetEnrollmentEpoch > 0, "history target enrollment epoch")
        requireTransport(requestedLimit in 1..MAX_HISTORY_PAGE_SIZE, "history page limit")
        requestedAfter?.let { requireHistoryCursor(it, "history page cursor") }

        requireTransport(
            authoritativeRoster.conversationId == expectedConversationId &&
                authoritativeRoster.currentUserId == expectedCurrentUserId &&
                authoritativeRoster.currentDeviceId == expectedCurrentDeviceId,
            "history authoritative roster binding",
        )
        requireTransport(
            response.conversationId == expectedConversationId,
            "history response conversation changed",
        )
        requireTransport(
            response.rosterRevision == authoritativeRoster.rosterRevision,
            "history response roster changed",
        )
        val target = required(response.targetCryptoBundle, "history target crypto bundle")
        requireTransport(target.deviceId == expectedTargetDeviceId, "history target device changed")
        requireTransport(target.userId == expectedCurrentUserId, "history target account changed")
        requireTransport(
            target.enrollmentEpoch == expectedTargetEnrollmentEpoch,
            "history target enrollment changed",
        )
        requireSignalDeviceId(target.signalDeviceId, "history target Signal device ID")
        requireRegistrationId(target.registrationId, "history target registration ID")
        requireTransport(
            target.protocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION,
            "history target protocol",
        )
        val targetBundleVersion = required(target.bundleVersion, "history target bundle version")
        requireTransport(targetBundleVersion > 0, "history target bundle version")
        requireSha256(target.identityKeySha256, "history target identity-key hash")
        val rosterTarget = authoritativeRoster.devices().singleOrNull {
            it.deviceId == expectedTargetDeviceId
        } ?: rejectTransport("history target is absent from the authoritative roster")
        requireTransport(
            rosterTarget.userId == expectedCurrentUserId &&
                rosterTarget.signalDeviceId == target.signalDeviceId &&
                rosterTarget.registrationId == target.registrationId &&
                rosterTarget.protocolVersion == target.protocolVersion &&
                rosterTarget.bundleVersion == targetBundleVersion &&
                rosterTarget.identityKeySha256 == target.identityKeySha256,
            "history target claims differ from the authoritative roster",
        )

        val nullableMessages = required(response.messages, "history candidate messages")
        requireTransport(
            nullableMessages.size <= requestedLimit,
            "history response exceeds requested limit",
        )
        val seenMessageIds = mutableSetOf<String>()
        val messages = nullableMessages.mapIndexed { index, nullableMessage ->
            val message = required(nullableMessage, "history candidate $index")
            val senderDeviceId = required(
                message.senderDeviceId,
                "history candidate $index sender device ID",
            )
            val senderEnrollmentEpoch = requireHistorySenderEnrollmentEpoch(
                value = message.senderEnrollmentEpoch,
                field = "history candidate $index sender enrollment epoch",
            )
            val authoredByCurrentInstallation =
                senderDeviceId == expectedCurrentDeviceId &&
                    senderEnrollmentEpoch == expectedCurrentEnrollmentEpoch
            val validatedIncoming = if (authoredByCurrentInstallation) {
                validateOwnOutboundMessage(
                    message = message,
                    expectedConversationId = expectedConversationId,
                    expectedClientMessageId = required(
                        message.clientMessageId,
                        "history candidate $index client message ID",
                    ),
                    expectedCurrentUserId = expectedCurrentUserId,
                    expectedCurrentDeviceId = expectedCurrentDeviceId,
                    expectedCurrentEnrollmentEpoch = expectedCurrentEnrollmentEpoch,
                    expectedRosterRevision = required(
                        message.rosterRevision,
                        "history candidate $index roster revision",
                    ),
                    expectedKind = required(message.kind, "history candidate $index kind"),
                )
                null
            } else {
                SecureMessagingWireValidator.validateIncomingEncryptedMessage(
                    message = message,
                    expectedConversationId = expectedConversationId,
                    currentDeviceId = expectedCurrentDeviceId,
                    currentUserId = expectedCurrentUserId,
                    currentEnrollmentEpoch = expectedCurrentEnrollmentEpoch,
                )
            }
            val messageId = required(message.id, "history candidate $index message ID")
            requireUuid(messageId, "history candidate $index message ID")
            requireTransport(
                seenMessageIds.add(messageId),
                "history candidates contain duplicate message IDs",
            )
            val sender = required(message.sender, "history candidate $index sender")
            val senderUserId = required(sender.id, "history candidate $index sender user ID")
            requireUuid(senderUserId, "history candidate $index sender user ID")
            val senderSignalDeviceId = required(
                message.senderSignalDeviceId,
                "history candidate $index sender Signal device ID",
            )
            requireSignalDeviceId(
                senderSignalDeviceId,
                "history candidate $index sender Signal device ID",
            )
            val clientMessageId = required(
                message.clientMessageId,
                "history candidate $index client message ID",
            )
            requireUuid(clientMessageId, "history candidate $index client message ID")
            val originalRosterRevision = required(
                message.rosterRevision,
                "history candidate $index roster revision",
            )
            requireTransport(
                SECURE_MESSAGING_ROSTER_REVISION.matches(originalRosterRevision),
                "history candidate $index roster revision",
            )
            val kind = required(message.kind, "history candidate $index kind")
            requireTransport(kind in SECURE_MESSAGE_KINDS, "history candidate $index kind")
            val replyToMessageId = message.replyToMessageId
            replyToMessageId?.let { requireUuid(it, "history candidate $index reply target") }
            requireTransport(
                kind != ENCRYPTED_REACTION_MESSAGE_KIND || replyToMessageId != null,
                "history candidate $index reaction reply target",
            )
            requireTransport(
                kind != ENCRYPTED_EDIT_MESSAGE_KIND || replyToMessageId != null,
                "history candidate $index edit reply target",
            )
            val sentAt = validatedIncoming?.sentAt
                ?: requireMessageTimestamp(message.sentAt, "history candidate $index send time")
            requireTransport(message.revokedAt == null, "history candidate $index is revoked")
            ValidatedMessagingHistoryCandidate(
                messageId = messageId,
                conversationId = expectedConversationId,
                clientMessageId = clientMessageId,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                senderEnrollmentEpoch = senderEnrollmentEpoch,
                senderSignalDeviceId = senderSignalDeviceId,
                rosterRevision = originalRosterRevision,
                kind = kind,
                replyToMessageId = replyToMessageId,
                sentAt = sentAt,
            )
        }

        val page = required(response.page, "history candidate page")
        val hasMore = required(page.hasMore, "history candidate has-more state")
        requireTransport(page.limit == requestedLimit, "history candidate page limit changed")
        val nextCursor = page.nextCursor
        nextCursor?.let { requireHistoryCursor(it, "history candidate next cursor") }
        requireTransport(!hasMore || nextCursor != null, "history candidate continuation is missing")
        requireTransport(
            requestedAfter == null ||
                nextCursor == null ||
                (!hasMore && messages.isEmpty()) ||
                nextCursor != requestedAfter,
            "history candidate cursor did not advance",
        )
        return ValidatedMessagingHistoryBackfillPage(
            conversationId = expectedConversationId,
            rosterRevision = authoritativeRoster.rosterRevision,
            target = ValidatedMessagingHistoryTarget(
                deviceId = expectedTargetDeviceId,
                userId = expectedCurrentUserId,
                enrollmentEpoch = expectedTargetEnrollmentEpoch,
                signalDeviceId = checkNotNull(target.signalDeviceId),
                registrationId = checkNotNull(target.registrationId),
                bundleVersion = targetBundleVersion,
                identityKeySha256 = checkNotNull(target.identityKeySha256),
            ),
            messages = messages,
            nextCursor = nextCursor,
            hasMore = hasMore,
        )
    }

    fun validateHistoryEnvelopeResult(
        response: MessagingHistoryEnvelopeResultDto,
        expectedMessageId: String,
        expectedTargetDeviceId: String,
        expectedTargetEnrollmentEpoch: Long,
        expectedTransferClientMessageId: String,
    ): ValidatedMessagingHistoryEnvelopeResult {
        requireUuid(expectedMessageId, "history result message ID")
        requireUuid(expectedTargetDeviceId, "history result target device ID")
        requireTransport(expectedTargetEnrollmentEpoch > 0, "history result target epoch")
        requireUuid(expectedTransferClientMessageId, "history result transfer ID")
        requireTransport(response.messageId == expectedMessageId, "history result message changed")
        requireTransport(
            response.targetDeviceId == expectedTargetDeviceId,
            "history result target changed",
        )
        requireTransport(
            response.targetEnrollmentEpoch == expectedTargetEnrollmentEpoch,
            "history result target enrollment changed",
        )
        requireTransport(
            response.transferClientMessageId == expectedTransferClientMessageId,
            "history result transfer changed",
        )
        return ValidatedMessagingHistoryEnvelopeResult(
            messageId = expectedMessageId,
            targetDeviceId = expectedTargetDeviceId,
            targetEnrollmentEpoch = expectedTargetEnrollmentEpoch,
            transferClientMessageId = expectedTransferClientMessageId,
            created = required(response.created, "history result created state"),
        )
    }

    fun validateOutboundSendResponse(
        response: EncryptedMessageDto,
        expectedConversationId: String,
        expectedClientMessageId: String,
        expectedCurrentUserId: String,
        expectedCurrentDeviceId: String,
        expectedCurrentEnrollmentEpoch: Long? = null,
        expectedRosterRevision: String,
        expectedKind: String = ENCRYPTED_MESSAGE_KIND,
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
            expectedCurrentEnrollmentEpoch = expectedCurrentEnrollmentEpoch,
            expectedRosterRevision = expectedRosterRevision,
            expectedKind = expectedKind,
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
            val deliveredAt = requireMessageTimestamp(
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

    fun validateReadReceipt(
        response: MessagingReadReceiptDto,
        expectedConversationId: String,
        expectedCurrentUserId: String,
        requestedMessageId: String,
    ): ValidatedMessagingReadReceipt {
        requireUuid(expectedConversationId, "expected read-receipt conversation ID")
        requireUuid(expectedCurrentUserId, "expected read-receipt user ID")
        requireUuid(requestedMessageId, "requested last-read message ID")
        requireTransport(
            response.conversationId == expectedConversationId,
            "read-receipt conversation changed",
        )
        requireTransport(response.userId == expectedCurrentUserId, "read-receipt user changed")
        val canonicalMessageId = required(
            response.lastReadMessageId,
            "canonical last-read message ID",
        )
        requireUuid(canonicalMessageId, "canonical last-read message ID")
        val readAt = requireMessageTimestamp(response.readAt, "read-receipt time")
        return ValidatedMessagingReadReceipt(
            conversationId = expectedConversationId,
            userId = expectedCurrentUserId,
            lastReadMessageId = canonicalMessageId,
            readAt = readAt,
        )
    }

    /**
     * Validates one message's delivery record.
     *
     * Refused whole rather than shown in part: a reply that names another message, repeats a
     * person, omits everybody, or carries a moment that predates the message itself is not a
     * record anyone should read off, and half of one reads as fact just as readily as all of it.
     * A moment the server has not witnessed arrives as null, which is an answer rather than an
     * omission.
     *
     * [expectedRecipientIds] pins the answer to a recipient set this device already knows for
     * certain, and is supplied only where certainty exists. A direct conversation qualifies: its
     * counterpart cannot change, so anybody else named in the reply is somebody the server
     * invented. A group does not: people join and leave, and the record is deliberately historical
     * to the message, so a member added after the send is rightly absent and one since removed is
     * rightly present. Checking a group against today's roster would reject exactly the truthful
     * answers this record exists to give.
     */
    fun validateMessageInfo(
        response: MessagingMessageInfoDto,
        expectedConversationId: String,
        expectedMessageId: String,
        expectedRecipientIds: Set<String>? = null,
    ): ValidatedMessageDeliveryInfo {
        requireUuid(expectedConversationId, "expected message-info conversation ID")
        requireUuid(expectedMessageId, "expected message-info message ID")
        requireTransport(
            response.conversationId == expectedConversationId,
            "message-info conversation changed",
        )
        requireTransport(response.messageId == expectedMessageId, "message-info message changed")
        val sentAt = requireMessageTimestamp(response.sentAt, "message-info sent time")
        val nullableRecipients = required(response.recipients, "message-info recipients")
        // A message this account sent went to somebody. An empty list is the server declining to
        // say rather than reporting that nobody was addressed, and a screen headed "Read by 0 of
        // 0" states that absence as though it were a finding.
        requireTransport(
            nullableRecipients.isNotEmpty(),
            "message-info names nobody the message was addressed to",
        )
        requireTransport(
            nullableRecipients.size <= MAX_GROUP_MEMBERS,
            "message-info recipient list is too large",
        )
        val seen = mutableSetOf<String>()
        val recipients = nullableRecipients.mapIndexed { index, nullableRecipient ->
            val recipient = required(nullableRecipient, "message-info recipient $index")
            val userId = required(recipient.userId, "message-info recipient $index user ID")
            requireUuid(userId, "message-info recipient $index user ID")
            requireTransport(seen.add(userId), "message-info repeats a recipient")
            val name = required(recipient.name, "message-info recipient $index name").trim()
            requireTransport(
                name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= MAX_RECIPIENT_NAME_UTF8_BYTES,
                "message-info recipient $index name",
            )
            val deliveredAt = recipient.deliveredAt?.let {
                requireMessageTimestamp(it, "message-info recipient $index delivery time")
            }
            val readAt = recipient.readAt?.let {
                requireMessageTimestamp(it, "message-info recipient $index read time")
            }
            // Nothing can be delivered or read before it was sent.
            if (deliveredAt != null) {
                requireTransport(
                    !deliveredAt.isBefore(sentAt),
                    "message-info recipient $index delivery chronology",
                )
            }
            if (readAt != null) {
                requireTransport(
                    !readAt.isBefore(sentAt),
                    "message-info recipient $index read chronology",
                )
                // Nobody opens a message that never arrived. A read moment without a delivery, or
                // one that precedes it, describes a sequence of events that cannot have happened,
                // and the sensible reading of an impossible record is that it is not a record.
                requireTransport(
                    deliveredAt != null && !readAt.isBefore(deliveredAt),
                    "message-info recipient $index was read before it was delivered",
                )
            }
            ValidatedMessageDeliveryRecipient(
                userId = userId,
                name = name,
                deliveredAt = deliveredAt,
                readAt = readAt,
            )
        }
        if (expectedRecipientIds != null) {
            requireTransport(
                seen == expectedRecipientIds,
                "message-info names people this conversation did not address",
            )
        }
        return ValidatedMessageDeliveryInfo(
            conversationId = expectedConversationId,
            messageId = expectedMessageId,
            sentAt = sentAt,
            recipients = recipients,
        )
    }

    private fun validateSyncEvent(
        event: MessagingSyncEventDto,
        eventId: Long,
        currentUserId: String,
        currentDeviceId: String,
        currentEnrollmentEpoch: Long?,
    ): ValidatedMessagingSyncEvent {
        val type = required(event.type, "messaging sync event type")
        requireTransport(type in SYNC_EVENT_TYPES, "messaging sync event type")
        val conversationId = required(event.conversationId, "messaging sync conversation ID")
        requireUuid(conversationId, "messaging sync conversation ID")
        val occurredAt = requireEventTimestamp(event.occurredAt, "messaging sync event time")

        return when (type) {
            MESSAGE_CREATED_EVENT -> validateMessageEvent(
                event,
                eventId,
                conversationId,
                occurredAt,
                currentUserId,
                currentDeviceId,
                currentEnrollmentEpoch,
            )
            MESSAGE_DELIVERY_UPDATED_EVENT -> validateDeliveryReceiptEvent(
                event = event,
                eventId = eventId,
                conversationId = conversationId,
                occurredAt = occurredAt,
            )
            READ_RECEIPT_UPDATED_EVENT -> validateReadReceiptEvent(
                event = event,
                eventId = eventId,
                conversationId = conversationId,
                occurredAt = occurredAt,
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
            CONVERSATION_CREATED_EVENT, CONVERSATION_UPDATED_EVENT -> {
                requireTransport(event.resourceType == CONVERSATION_RESOURCE, "conversation event resource type")
                requireTransport(event.resourceId == conversationId, "conversation event resource changed")
                required(event.data, "conversation event data")
                ValidatedMessagingSyncEvent.Metadata(eventId, type, conversationId, occurredAt)
            }
            in FINANCIAL_EVENT_TYPES -> validateFinancialEvent(
                event, eventId, type, conversationId, occurredAt,
            )
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
                val memberUserId = required(data.userId, "membership event user ID")
                requireUuid(memberUserId, "membership event user ID")
                // The role is what the change *made* the subject, and it is the only other thing
                // the server sends. It is optional on the wire and bounded here to the roles the
                // rest of the protocol already knows; an unknown one is a rejected event, not a
                // rendered one.
                val memberRole = data.role?.takeIf(String::isNotBlank)?.also {
                    requireTransport(it in MEMBER_ROLES, "membership event role")
                }
                ValidatedMessagingSyncEvent.Metadata(
                    eventId = eventId,
                    type = type,
                    conversationId = conversationId,
                    occurredAt = occurredAt,
                    memberUserId = memberUserId,
                    memberRole = memberRole,
                )
            }
            else -> rejectTransport("messaging sync event type")
        }
    }

    private fun validateFinancialEvent(
        event: MessagingSyncEventDto,
        eventId: Long,
        type: String,
        conversationId: String,
        occurredAt: Instant,
    ): ValidatedMessagingSyncEvent.FinancialMetadata {
        val data = required(event.data, "financial event data")
        requireTransport(data.conversationId == conversationId, "financial event conversation changed")
        val resourceId = required(event.resourceId, "financial event resource ID")
        requireUuid(resourceId, "financial event resource ID")
        val family = type.substringBeforeLast('.')
        val action = type.substringAfterLast('.')
        val expectedResource = when (family) {
            "group_payment_request" -> if (action == "contributed") {
                "group_payment_request_contribution"
            } else "group_payment_request"
            "scheduled_payment" -> "scheduled_payment"
            "scheduled_group_payment" -> "scheduled_group_payment"
            else -> rejectTransport("financial event family")
        }
        requireTransport(event.resourceType == expectedResource, "financial event resource type")
        val primaryId = when (family) {
            "group_payment_request" -> required(data.groupPaymentRequestId, "group request ID")
            "scheduled_payment" -> required(data.scheduledPaymentId, "scheduled payment ID")
            else -> required(data.scheduledGroupPaymentId, "scheduled group payment ID")
        }
        requireUuid(primaryId, "financial event payment ID")
        if (family != "group_payment_request") {
            requireTransport(resourceId == primaryId, "financial event resource changed")
        }
        var requesterUserId: String? = null
        if (family == "group_payment_request") {
            requireTransport(data.schema == "kit.group-payment-request.v1", "group request schema")
            val requester = required(data.requesterUserId, "group request requester")
            requireUuid(requester, "group request requester")
            requesterUserId = requester
            val target = canonicalMinor(required(data.targetAmountMinor, "group request target"))
            val contributed = canonicalMinor(required(data.contributedAmountMinor, "group request contributed"))
            val remaining = canonicalMinor(required(data.remainingAmountMinor, "group request remaining"))
            requireTransport(
                target in 1..GroupPaymentRequestContributionDto.MAX_MINOR &&
                    contributed in 0..target && remaining == target - contributed,
                "group request totals",
            )
            requireTransport(required(data.currency, "group request currency").matches(Regex("^[A-Z]{3}$")),
                "group request currency")
            requireTransport(data.currencyScale in 0..6, "group request currency scale")
            requireTransport(data.progressBasisPoints == if (contributed == target) 10_000 else
                ((contributed * 10_000L) / target).toInt(), "group request progress")
            when (action) {
                "created" -> requireTransport(
                    resourceId == primaryId && data.status == "open" && contributed == 0L &&
                        remaining == target && data.progressBasisPoints == 0 &&
                        data.contributionId == null && data.contributorUserId == null &&
                        data.contributionAmountMinor == null,
                    "created group request",
                )
                "contributed", "completed" -> {
                    val contributionId = required(data.contributionId, "group request contribution ID")
                    requireUuid(contributionId, "group request contribution ID")
                    val expectedResourceId = if (action == "contributed") contributionId else primaryId
                    requireTransport(
                        resourceId == expectedResourceId,
                        "group request contribution resource changed",
                    )
                    val contributor = required(data.contributorUserId, "group request contributor")
                    requireUuid(contributor, "group request contributor")
                    requireTransport(contributor != requester, "group request contributor")
                    val contributionAmount = canonicalMinor(
                        required(data.contributionAmountMinor, "group request contribution amount"),
                    )
                    requireTransport(
                        contributionAmount in 1..contributed,
                        "group request contribution amount",
                    )
                    if (action == "contributed") {
                        requireTransport(
                            data.status == "open" || data.status == "completed",
                            "contributed group request status",
                        )
                    } else {
                        requireTransport(
                            data.status == "completed" && remaining == 0L &&
                                data.progressBasisPoints == 10_000,
                            "completed group request",
                        )
                    }
                }
                "cancelled", "expired" -> requireTransport(
                    resourceId == primaryId && data.status == action &&
                        data.contributionId == null && data.contributorUserId == null &&
                        data.contributionAmountMinor == null,
                    "$action group request",
                )
                else -> rejectTransport("group request event action")
            }
        } else {
            val expectedSchema = if (family == "scheduled_payment") {
                "kit.scheduled-payment.v1"
            } else "kit.scheduled-group-payment.v1"
            requireTransport(data.schema == expectedSchema, "scheduled payment schema")
            requireTransport(required(data.status, "scheduled payment status") == action,
                "scheduled payment terminal status")
            requireTransport(action in setOf("completed", "failed", "cancelled"),
                "scheduled payment event action")
            val scheduledFor = required(data.scheduledFor, "scheduled payment time")
            requireTransport(runCatching { Instant.parse(scheduledFor) }.isSuccess,
                "scheduled payment time")
            if (family == "scheduled_payment") {
                val sender = required(data.senderUserId, "scheduled payment sender")
                val recipient = required(data.recipientUserId, "scheduled payment recipient")
                requireUuid(sender, "scheduled payment sender")
                requireUuid(recipient, "scheduled payment recipient")
                requireTransport(sender != recipient, "scheduled payment participants")
                requireTransport(canonicalMinor(required(data.amountMinor,
                    "scheduled payment amount")) > 0, "scheduled payment amount")
                requireTransport(required(data.currency,
                    "scheduled payment currency").matches(Regex("^[A-Z]{3}$")),
                    "scheduled payment currency")
                requireTransport(data.currencyScale in 0..6, "scheduled payment currency scale")
                requireTransport((data.note?.length ?: 0) <= 280 &&
                    data.note?.any(Char::isISOControl) != true, "scheduled payment note")
                requireTransport(data.groupPaymentId == null, "scheduled payment group result")
                when (action) {
                    "completed" -> {
                        requireUuid(required(data.walletTransactionId,
                            "scheduled wallet transaction ID"), "scheduled wallet transaction ID")
                        requireTransport(data.failureCode == null && data.failureMessage == null &&
                            data.completedAt.isValidInstant() && data.cancelledAt == null,
                            "completed scheduled payment")
                    }
                    "failed" -> requireTransport(
                        data.walletTransactionId == null && data.failureCode.isSafeFailureCode() &&
                            (data.failureMessage == null || data.failureMessage.isSafeFailureMessage()) &&
                            data.completedAt.isValidInstant() && data.cancelledAt == null,
                        "failed scheduled payment",
                    )
                    "cancelled" -> requireTransport(
                        data.walletTransactionId == null && data.failureCode == null &&
                            data.failureMessage == null && data.completedAt == null &&
                            data.cancelledAt.isValidInstant(),
                        "cancelled scheduled payment",
                    )
                }
            } else {
                requireTransport(data.senderUserId == null && data.recipientUserId == null &&
                    data.amountMinor == null && data.currency == null && data.currencyScale == null &&
                    data.walletTransactionId == null, "scheduled group payment fields")
                when (action) {
                    "completed" -> {
                        requireUuid(required(data.groupPaymentId, "completed group payment ID"),
                            "completed group payment ID")
                        requireTransport(data.failureCode == null && data.failureMessage == null &&
                            data.completedAt.isValidInstant() && data.cancelledAt == null,
                            "completed scheduled group payment")
                    }
                    "failed" -> {
                        // The first deployed server emitted only the terminal state;
                        // newer servers may enrich the same wake hint with both failure fields.
                        // Accept those two coherent shapes only. The exact schedule read below the
                        // transport boundary remains the authority for what actually failed.
                        val legacyFailure = data.failureCode == null && data.failureMessage == null
                        val enrichedFailure = data.failureCode.isSafeFailureCode() &&
                            data.failureMessage.isSafeFailureMessage()
                        requireTransport(
                            data.groupPaymentId == null && data.completedAt.isValidInstant() &&
                                data.cancelledAt == null && (legacyFailure || enrichedFailure),
                            "failed scheduled group payment",
                        )
                    }
                    "cancelled" -> requireTransport(
                        data.groupPaymentId == null && data.completedAt == null &&
                            data.cancelledAt.isValidInstant() && data.failureCode == null &&
                            data.failureMessage == null, "cancelled scheduled group payment",
                    )
                }
            }
        }
        return ValidatedMessagingSyncEvent.FinancialMetadata(
            eventId, conversationId, occurredAt, type, primaryId,
            requesterUserId, data.contributionId, data.contributorUserId,
            data.contributionAmountMinor, data.senderUserId, data.recipientUserId,
            data.amountMinor?.let(::canonicalMinor), data.currency, data.currencyScale,
            data.note, data.scheduledFor?.let(Instant::parse), data.walletTransactionId,
            data.failureCode, data.failureMessage, data.completedAt?.let(Instant::parse),
            data.cancelledAt?.let(Instant::parse), data.groupPaymentId,
        )
    }

    private fun String?.isValidInstant(): Boolean =
        this != null && runCatching { Instant.parse(this) }.isSuccess

    private fun String?.isSafeFailureCode(): Boolean =
        this != null && isNotBlank() && length <= 120 && none(Char::isISOControl)

    private fun String?.isSafeFailureMessage(): Boolean =
        this != null && isNotBlank() && length <= 500 && none(Char::isISOControl)

    private fun canonicalMinor(raw: String): Long {
        requireTransport(raw.matches(Regex("^(0|[1-9][0-9]*)$")), "financial minor amount")
        return raw.toLongOrNull()?.also { requireTransport(it >= 0, "financial minor amount") }
            ?: rejectTransport("financial minor amount")
    }

    private fun validateDeliveryReceiptEvent(
        event: MessagingSyncEventDto,
        eventId: Long,
        conversationId: String,
        occurredAt: Instant,
    ): ValidatedMessagingSyncEvent.DeliveryReceipt {
        requireTransport(
            event.resourceType == MESSAGE_DELIVERY_RESOURCE,
            "message-delivery event resource type",
        )
        val data = required(event.data, "message-delivery event data")
        val messageId = required(data.messageId, "message-delivery message ID")
        requireUuid(messageId, "message-delivery message ID")
        requireTransport(event.resourceId == messageId, "message-delivery resource changed")
        requireTransport(
            data.deliveryState == PEER_DELIVERY_STATE,
            "message-delivery state",
        )
        val deliveredAt = requireMessageTimestamp(data.deliveredAt, "message-delivery time")
        requireTransport(!occurredAt.isBefore(deliveredAt), "message-delivery event chronology")
        return ValidatedMessagingSyncEvent.DeliveryReceipt(
            eventId = eventId,
            conversationId = conversationId,
            occurredAt = occurredAt,
            messageId = messageId,
            deliveredAt = deliveredAt,
        )
    }

    private fun validateReadReceiptEvent(
        event: MessagingSyncEventDto,
        eventId: Long,
        conversationId: String,
        occurredAt: Instant,
    ): ValidatedMessagingSyncEvent.ReadReceipt {
        requireTransport(
            event.resourceType == READ_RECEIPT_RESOURCE,
            "read-receipt event resource type",
        )
        val resourceId = required(event.resourceId, "read-receipt resource ID")
        requireTransport(
            resourceId.matches(Regex("^${Regex.escape(conversationId)}:[1-9][0-9]*$")),
            "read-receipt resource ID",
        )
        val data = required(event.data, "read-receipt event data")
        val userId = required(data.userId, "read-receipt user ID")
        requireUuid(userId, "read-receipt user ID")
        val lastReadMessageId = required(
            data.lastReadMessageId,
            "read-receipt last-read message ID",
        )
        requireUuid(lastReadMessageId, "read-receipt last-read message ID")
        val readAt = requireMessageTimestamp(data.readAt, "read-receipt time")
        requireTransport(!occurredAt.isBefore(readAt), "read-receipt event chronology")
        return ValidatedMessagingSyncEvent.ReadReceipt(
            eventId = eventId,
            conversationId = conversationId,
            occurredAt = occurredAt,
            userId = userId,
            lastReadMessageId = lastReadMessageId,
            readAt = readAt,
        )
    }

    private fun validateMessageEvent(
        event: MessagingSyncEventDto,
        eventId: Long,
        conversationId: String,
        occurredAt: Instant,
        currentUserId: String,
        currentDeviceId: String,
        currentEnrollmentEpoch: Long?,
    ): ValidatedMessagingSyncEvent {
        requireTransport(event.resourceType == MESSAGE_RESOURCE, "message event resource type")
        val data = required(event.data, "message event data")
        val senderDeviceId = required(data.senderDeviceId, "message event sender device ID")
        val senderEnrollmentEpoch = data.senderEnrollmentEpoch
        senderEnrollmentEpoch?.let {
            requireTransport(it > 0, "message sender epoch")
        }
        val authoredByCurrentInstallation =
            senderDeviceId == currentDeviceId &&
                (senderEnrollmentEpoch == null ||
                    currentEnrollmentEpoch == null ||
                    senderEnrollmentEpoch == currentEnrollmentEpoch)
        val isHistoryBackfill = data.envelope?.isHistoryBackfill == true
        val validated = if (authoredByCurrentInstallation && !isHistoryBackfill) {
            val outbound = validateOwnOutboundMessage(
                message = data.toEncryptedMessageDto(),
                expectedConversationId = conversationId,
                expectedClientMessageId = required(data.clientMessageId, "outbound client message ID"),
                expectedCurrentUserId = currentUserId,
                expectedCurrentDeviceId = currentDeviceId,
                expectedCurrentEnrollmentEpoch = currentEnrollmentEpoch,
                expectedRosterRevision = required(data.rosterRevision, "outbound roster revision"),
                expectedKind = required(data.kind, "outbound message kind"),
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
                currentUserId,
                currentEnrollmentEpoch,
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
        expectedCurrentEnrollmentEpoch: Long? = null,
        expectedRosterRevision: String,
        expectedKind: String,
    ): ValidatedOutboundEncryptedMessage {
        val messageId = required(message.id, "outbound message ID")
        requireUuid(messageId, "outbound message ID")
        requireTransport(message.conversationId == expectedConversationId, "outbound conversation changed")
        requireTransport(message.clientMessageId == expectedClientMessageId, "outbound client message ID changed")
        val sender = required(message.sender, "outbound message sender")
        requireTransport(sender.id == expectedCurrentUserId, "outbound sender changed")
        requireTransport(!sender.name.isNullOrBlank(), "outbound sender name")
        requireTransport(message.senderDeviceId == expectedCurrentDeviceId, "outbound sender device changed")
        expectedCurrentEnrollmentEpoch?.let { expectedEpoch ->
            requireTransport(
                message.senderEnrollmentEpoch == null ||
                    message.senderEnrollmentEpoch == expectedEpoch,
                "outbound sender enrollment changed",
            )
        }
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
        requireTransport(
            expectedKind in SECURE_MESSAGE_KINDS && message.kind == expectedKind,
            "outbound message kind",
        )
        message.replyToMessageId?.let { requireUuid(it, "outbound reply target") }
        requireTransport(
            message.kind != ENCRYPTED_REACTION_MESSAGE_KIND ||
                message.replyToMessageId != null,
            "outbound reaction reply target",
        )
        requireTransport(
            message.kind != ENCRYPTED_EDIT_MESSAGE_KIND ||
                message.replyToMessageId != null,
            "outbound edit reply target",
        )
        // Attachment metadata rows accompany exactly the encrypted_attachment kind; the media
        // descriptor with its key material still travels only inside the per-device ciphertext.
        requireTransport(
            (message.kind == ENCRYPTED_ATTACHMENT_MESSAGE_KIND) ==
                required(message.attachments, "outbound attachments").isNotEmpty(),
            "outbound attachment metadata must match its kind",
        )
        requireTransport(
            required(message.reactions, "outbound reactions").isEmpty(),
            "v2 outbound text messages cannot contain reactions",
        )
        val sentAt = requireMessageTimestamp(message.sentAt, "outbound send time")
        requireTransport(message.revokedAt == null, "outbound message is revoked")
        // The backend intentionally excludes the sending device from fan-out. Accepting an
        // envelope here would confuse untrusted echoed bytes with a recipient delivery.
        requireTransport(message.envelope == null, "outbound response contains a sender envelope")
        return ValidatedOutboundEncryptedMessage(
            messageId = messageId,
            conversationId = expectedConversationId,
            clientMessageId = expectedClientMessageId,
            senderDeviceId = expectedCurrentDeviceId,
            senderEnrollmentEpoch = message.senderEnrollmentEpoch,
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
        senderEnrollmentEpoch = senderEnrollmentEpoch,
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

    private fun requireHistoryCursor(value: String, field: String) {
        requireTransport(
            value.length <= MAX_CURSOR_LENGTH && HISTORY_CURSOR.matches(value),
            field,
        )
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

    private fun requireMessageTimestamp(value: String?, field: String): Instant {
        val timestamp = required(value, field)
        requireTransport(MESSAGE_TIMESTAMP.matches(timestamp), field)
        return try {
            Instant.parse(timestamp)
        } catch (_: RuntimeException) {
            rejectTransport(field)
        }
    }

    private fun requireEventTimestamp(value: String?, field: String): Instant {
        val timestamp = required(value, field)
        requireTransport(MESSAGE_TIMESTAMP.matches(timestamp), field)
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

    private const val MAX_RECIPIENT_NAME_UTF8_BYTES = 256

    /**
     * Structural sanity for a group photo address: HTTPS with a host, no control or whitespace
     * characters, bounded length. Host trust is deliberately not judged here — the render layer
     * applies the same origin pin profile avatars pass, and an untrusted origin there degrades
     * to the generated group avatar rather than rejecting the whole conversation.
     */
    private fun isPlausibleGroupPhotoUrl(value: String): Boolean =
        value.length <= MAX_GROUP_PHOTO_URL_LENGTH &&
            value.none { it.isWhitespace() || it.isISOControl() } &&
            runCatching { java.net.URI(value) }.getOrNull()?.let { uri ->
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
            } == true

    /** Unknown designations or malformed timestamps carry no authority and become no badge. */
    private fun validatedAccountVerification(
        value: AccountVerificationDto?,
    ): ValidatedAccountVerification? {
        value ?: return null
        val designation = value.designation?.takeIf(VERIFICATION_DESIGNATIONS::contains)
            ?: return null
        val since = value.since?.let { raw ->
            raw.takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return null
        }
        return ValidatedAccountVerification(designation, since?.toString())
    }

    private const val MAX_GROUP_PHOTO_URL_LENGTH = 2_048
    private val VERIFICATION_DESIGNATIONS = setOf("verified", "official", "official_support")
    private const val MAX_SERVER_DEVICES = 1_000
    private const val MAX_CONVERSATIONS = 10_000
    private const val MAX_SYNC_PAGE_SIZE = 100
    private const val MAX_HISTORY_PAGE_SIZE = 50
    private const val MAX_CURSOR_LENGTH = 2_048
    private const val DIRECT_MEMBER_COUNT = 2
    private const val DELIVERY_STATE = "delivered_to_device"
    private const val MESSAGE_CREATED_EVENT = "message.created"
    private const val MESSAGE_DELIVERY_UPDATED_EVENT = "message.delivery.updated"
    private const val READ_RECEIPT_UPDATED_EVENT = "read_receipt.updated"
    private const val CONVERSATION_CREATED_EVENT = "conversation.created"
    private const val CONVERSATION_UPDATED_EVENT = "conversation.updated"
    private const val MESSAGE_RESOURCE = "message"
    private const val MESSAGE_DELIVERY_RESOURCE = "message_delivery"
    private const val READ_RECEIPT_RESOURCE = "read_receipt"
    private const val PEER_DELIVERY_STATE = "delivered_to_peer"
    private const val CONVERSATION_RESOURCE = "conversation"
    private const val CONVERSATION_MEMBER_RESOURCE = "conversation_member"

    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
    private val POSITIVE_DECIMAL = Regex("^[0-9]+$")
    private val CURSOR = Regex("^[A-Za-z0-9_-]+$")
    private val HISTORY_CURSOR = Regex("^[A-Za-z0-9_.-]+$")
    private val UTC_TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
    private val MESSAGE_TIMESTAMP =
        Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{6})?Z$")
    private val CONVERSATION_TYPES = setOf("direct", "group", "community", "channel")

    /** The subset of [CONVERSATION_TYPES] the pairwise wire protocol can actually carry. */
    private val ENCRYPTABLE_CONVERSATION_TYPES =
        setOf(DIRECT_CONVERSATION_TYPE, GROUP_CONVERSATION_TYPE)
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
    private val FINANCIAL_EVENT_TYPES = setOf(
        "group_payment_request.created", "group_payment_request.contributed",
        "group_payment_request.completed", "group_payment_request.cancelled",
        "group_payment_request.expired", "scheduled_payment.completed", "scheduled_payment.failed",
        "scheduled_payment.cancelled", "scheduled_group_payment.completed",
        "scheduled_group_payment.failed", "scheduled_group_payment.cancelled",
    )
    private val SYNC_EVENT_TYPES = setOf(
        MESSAGE_CREATED_EVENT,
        MESSAGE_DELIVERY_UPDATED_EVENT,
        READ_RECEIPT_UPDATED_EVENT,
        CONVERSATION_CREATED_EVENT,
        CONVERSATION_UPDATED_EVENT,
    ) + MEMBERSHIP_EVENT_TYPES + DEVICE_LIFECYCLE_EVENT_TYPES + FINANCIAL_EVENT_TYPES
}

data class ValidatedConversationMember(
    val userId: String,
    val name: String?,
    val role: String,
    val joinedAt: Instant,
    val avatarUrl: String? = null,
    val verification: ValidatedAccountVerification? = null,
)

data class ValidatedAccountVerification(
    val designation: String,
    val since: String?,
)

data class ValidatedConversation(
    val conversationId: String,
    val type: String,
    /** Server-visible group name; always null for a direct chat, which discloses nothing. */
    val title: String?,
    /** Server-visible group description; always null for a direct chat, like the title. */
    val description: String?,
    /**
     * The group photo's public content address, structurally vetted here and host-pinned again
     * at render time by the same trust policy profile avatars pass. Null for a direct chat.
     */
    val photoUrl: String?,
    val createdBy: String,
    /** The account this conversation was validated for; always present in [members]. */
    val viewerUserId: String,
    val currentUserRole: String,
    val members: List<ValidatedConversationMember>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isGroup: Boolean get() = type == GROUP_CONVERSATION_TYPE

    /** Every member but the viewer; a direct chat's single peer, or the rest of the group. */
    val others: List<ValidatedConversationMember>
        get() = members.filterNot { it.userId == viewerUserId }

    /**
     * The other party of a direct chat.
     *
     * Null for a group, where "the peer" is not a thing that exists — callers that need an
     * identity must go through [others] and say which member they mean.
     */
    val peerUserId: String? get() = if (isGroup) null else others.singleOrNull()?.userId

    val peerName: String? get() = if (isGroup) null else others.singleOrNull()?.name

    /** The user IDs the roster must cover for this conversation to be encryptable. */
    fun memberUserIds(): Set<String> = members.mapTo(mutableSetOf(), ValidatedConversationMember::userId)
}

data class ValidatedMessagingSyncPage(
    val events: List<ValidatedMessagingSyncEvent>,
    val nextCursor: String,
    val hasMore: Boolean,
    val limit: Int,
    val lastEventId: Long?,
)

data class ValidatedMessagingHistoryTarget(
    val deviceId: String,
    val userId: String,
    val enrollmentEpoch: Long,
    val signalDeviceId: Int,
    val registrationId: Int,
    val bundleVersion: Int,
    val identityKeySha256: String,
)

data class ValidatedMessagingHistoryCandidate(
    val messageId: String,
    val conversationId: String,
    val clientMessageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val senderEnrollmentEpoch: Long,
    val senderSignalDeviceId: Int,
    val rosterRevision: String,
    val kind: String,
    val replyToMessageId: String?,
    val sentAt: Instant,
)

data class ValidatedMessagingHistoryBackfillPage(
    val conversationId: String,
    val rosterRevision: String,
    val target: ValidatedMessagingHistoryTarget,
    val messages: List<ValidatedMessagingHistoryCandidate>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class ValidatedMessagingHistoryEnvelopeResult(
    val messageId: String,
    val targetDeviceId: String,
    val targetEnrollmentEpoch: Long,
    val transferClientMessageId: String,
    val created: Boolean,
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

    data class DeliveryReceipt(
        override val eventId: Long,
        override val conversationId: String,
        override val occurredAt: Instant,
        val messageId: String,
        val deliveredAt: Instant,
    ) : ValidatedMessagingSyncEvent

    data class ReadReceipt(
        override val eventId: Long,
        override val conversationId: String,
        override val occurredAt: Instant,
        val userId: String,
        val lastReadMessageId: String,
        val readAt: Instant,
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
        /**
         * Who the change was *about*, on a `membership.*` event. The server never says who made
         * it, so neither can anything downstream: a system message names a subject or nobody.
         */
        val memberUserId: String? = null,
        /** The subject's role after a `membership.role_changed`; null on every other event. */
        val memberRole: String? = null,
    ) : ValidatedMessagingSyncEvent

    data class FinancialMetadata(
        override val eventId: Long,
        override val conversationId: String,
        override val occurredAt: Instant,
        val type: String,
        val paymentId: String,
        val requesterUserId: String?,
        val contributionId: String?,
        val contributorUserId: String?,
        val contributionAmountMinor: String?,
        val senderUserId: String?,
        val recipientUserId: String?,
        val amountMinor: Long?,
        val currency: String?,
        val currencyScale: Int?,
        val note: String?,
        val scheduledFor: Instant?,
        val walletTransactionId: String?,
        val failureCode: String?,
        val failureMessage: String?,
        val completedAt: Instant?,
        val cancelledAt: Instant?,
        val groupPaymentId: String?,
    ) : ValidatedMessagingSyncEvent
}

data class ValidatedOutboundEncryptedMessage(
    val messageId: String,
    val conversationId: String,
    val clientMessageId: String,
    val senderDeviceId: String,
    val senderEnrollmentEpoch: Long?,
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

data class ValidatedMessagingReadReceipt(
    val conversationId: String,
    val userId: String,
    val lastReadMessageId: String,
    val readAt: Instant,
)

/**
 * One person a message was addressed to, and how far it got with them.
 *
 * Delivery is the earliest of that person's devices rather than the last, because a phone left in
 * a pocket should not make a laptop's delivery look undelivered. A null moment means the server
 * has not witnessed it, which is not the same as it having failed.
 */
data class ValidatedMessageDeliveryRecipient(
    val userId: String,
    /** The server's name for this person, used only where the local address book has none. */
    val name: String,
    val deliveredAt: Instant?,
    val readAt: Instant?,
)

/** When a message was accepted for sending, and when it reached each of its recipients. */
data class ValidatedMessageDeliveryInfo(
    val conversationId: String,
    val messageId: String,
    val sentAt: Instant,
    val recipients: List<ValidatedMessageDeliveryRecipient>,
)
