package com.kit.wallet

import com.kit.wallet.data.remote.DeviceDto
import com.kit.wallet.data.remote.DeviceListDto
import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedMessageDto
import com.kit.wallet.data.remote.EncryptedMessageEnvelopeDto
import com.kit.wallet.data.remote.EncryptedMessageSenderDto
import com.kit.wallet.data.remote.MessageDeliveryAcknowledgementDto
import com.kit.wallet.data.remote.MessageDeliveryReceiptDto
import com.kit.wallet.data.remote.MessagingConversationDto
import com.kit.wallet.data.remote.MessagingConversationListDto
import com.kit.wallet.data.remote.MessagingConversationMemberDto
import com.kit.wallet.data.remote.MessagingReadReceiptDto
import com.kit.wallet.data.remote.MessagingSyncDto
import com.kit.wallet.data.remote.MessagingSyncEventDataDto
import com.kit.wallet.data.remote.MessagingSyncEventDto
import com.kit.wallet.data.remote.SecureMessagingTransportValidator
import com.kit.wallet.data.remote.SecureMessagingWireValidationException
import com.kit.wallet.data.remote.ValidatedMessagingSyncEvent
import com.kit.wallet.data.remote.CursorPageDto
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingTransportValidatorTest {
    @Test
    fun `current server device requires one unambiguous validated current row`() {
        val current = device(CURRENT_DEVICE_ID, isCurrent = true)
        val other = device(OTHER_DEVICE_ID, isCurrent = false)

        assertEquals(
            CURRENT_DEVICE_ID,
            SecureMessagingTransportValidator.requireCurrentServerDevice(
                DeviceListDto(listOf(other, current)),
            ).id,
        )

        assertRejected {
            SecureMessagingTransportValidator.requireCurrentServerDevice(
                DeviceListDto(listOf(current.copy(isCurrent = false), other)),
            )
        }
        assertRejected {
            SecureMessagingTransportValidator.requireCurrentServerDevice(
                DeviceListDto(listOf(current, other.copy(isCurrent = true))),
            )
        }
        assertRejected {
            SecureMessagingTransportValidator.requireCurrentServerDevice(
                DeviceListDto(listOf(current, other.copy(id = CURRENT_DEVICE_ID))),
            )
        }
        assertRejected {
            SecureMessagingTransportValidator.requireCurrentServerDevice(
                DeviceListDto(listOf(current.copy(id = "not-a-device-id"))),
            )
        }
    }

    @Test
    fun `conversation validation filters unencryptable types and binds the peer and current role`() {
        val direct = directConversation()
        // A community/channel resource this build cannot encrypt for is skipped rather than
        // rejected: it is a valid server object, just not one this client speaks.
        val channel = direct.copy(
            id = GROUP_ID,
            type = "channel",
            title = "Existing channel resource",
            role = null,
            members = emptyList(),
            createdBy = null,
        )

        val validated = SecureMessagingTransportValidator.validateConversations(
            MessagingConversationListDto(listOf(channel, direct)),
            CURRENT_USER_ID,
        )

        assertEquals(1, validated.size)
        assertEquals(CONVERSATION_ID, validated.single().conversationId)
        assertEquals(OTHER_USER_ID, validated.single().peerUserId)
        assertEquals("Amina", validated.single().peerName)
        assertEquals("owner", validated.single().currentUserRole)
        assertFalse(validated.single().isGroup)
    }

    @Test
    fun `group validation keeps every member, names no peer, and requires a title`() {
        val validated = SecureMessagingTransportValidator.validateConversations(
            MessagingConversationListDto(listOf(groupConversation())),
            CURRENT_USER_ID,
        ).single()

        assertTrue(validated.isGroup)
        assertEquals(GROUP_ID, validated.conversationId)
        assertEquals("Weekend savings", validated.title)
        assertEquals("owner", validated.currentUserRole)
        assertEquals(CURRENT_USER_ID, validated.viewerUserId)
        assertEquals(
            setOf(CURRENT_USER_ID, OTHER_USER_ID, THIRD_USER_ID),
            validated.memberUserIds(),
        )
        assertEquals(setOf(OTHER_USER_ID, THIRD_USER_ID), validated.others.map { it.userId }.toSet())
        // A group deliberately has no peer: naming one would put a single member's identity on a
        // conversation that belongs to all of them.
        assertNull(validated.peerUserId)
        assertNull(validated.peerName)
    }

    @Test
    fun `group validation accepts and canonicalizes legacy Unicode edge padding`() {
        val validated = SecureMessagingTransportValidator.validateConversations(
            MessagingConversationListDto(
                listOf(groupConversation().copy(title = "\u00a0\u0085Weekend savings\u3000")),
            ),
            CURRENT_USER_ID,
        ).single()

        assertEquals("Weekend savings", validated.title)
    }

    @Test
    fun `malformed groups fail closed`() {
        val valid = groupConversation()
        val members = valid.members.orEmpty()
        val invalidResponses = listOf(
            // A group must be named; the title is the only thing the server may show for it.
            MessagingConversationListDto(listOf(valid.copy(title = null))),
            MessagingConversationListDto(listOf(valid.copy(title = "   "))),
            MessagingConversationListDto(listOf(valid.copy(title = "t".repeat(65)))),
            MessagingConversationListDto(listOf(valid.copy(title = "😀".repeat(31)))),
            MessagingConversationListDto(listOf(valid.copy(title = "Bad\uD800title"))),
            // The viewer must appear exactly once, with the role the server claims for them.
            MessagingConversationListDto(listOf(valid.copy(members = members.drop(1)))),
            MessagingConversationListDto(
                listOf(valid.copy(members = members + members.first())),
            ),
            MessagingConversationListDto(listOf(valid.copy(role = "member"))),
            MessagingConversationListDto(listOf(valid.copy(members = emptyList()))),
        )

        invalidResponses.forEach { response ->
            assertRejected {
                SecureMessagingTransportValidator.validateConversations(
                    response,
                    CURRENT_USER_ID,
                )
            }
        }
    }

    @Test
    fun `direct member join may follow conversation updated time but never creation time`() {
        val memberJoinedAfterInitialUpdate = directConversation().copy(
            updatedAt = CREATED_AT,
            members = directConversation().members?.map {
                it?.copy(joinedAt = JOINED_AT)
            },
        )

        assertEquals(
            1,
            SecureMessagingTransportValidator.validateConversations(
                MessagingConversationListDto(listOf(memberJoinedAfterInitialUpdate)),
                CURRENT_USER_ID,
            ).size,
        )

        val joinedBeforeCreation = memberJoinedAfterInitialUpdate.copy(
            members = memberJoinedAfterInitialUpdate.members?.mapIndexed { index, member ->
                if (index == 0) member?.copy(joinedAt = BEFORE_CREATED_AT) else member
            },
        )
        assertRejected {
            SecureMessagingTransportValidator.validateConversations(
                MessagingConversationListDto(listOf(joinedBeforeCreation)),
                CURRENT_USER_ID,
            )
        }
    }

    @Test
    fun `malformed or ambiguous direct conversations fail closed`() {
        val valid = directConversation()
        val members = valid.members.orEmpty()
        val invalidResponses = listOf(
            MessagingConversationListDto(null),
            MessagingConversationListDto(listOf(null)),
            MessagingConversationListDto(listOf(valid.copy(id = "not-a-uuid"))),
            MessagingConversationListDto(listOf(valid.copy(createdAt = "yesterday"))),
            MessagingConversationListDto(listOf(valid.copy(members = members.take(1)))),
            MessagingConversationListDto(
                listOf(valid.copy(members = listOf(members[0], members[0]))),
            ),
            MessagingConversationListDto(
                listOf(
                    valid.copy(
                        members = listOf(
                            members[1],
                            members[1]?.copy(userId = THIRD_USER_ID),
                        ),
                    ),
                ),
            ),
            MessagingConversationListDto(listOf(valid, valid)),
            MessagingConversationListDto(listOf(valid.copy(role = "member"))),
        )

        invalidResponses.forEach { response ->
            assertRejected {
                SecureMessagingTransportValidator.validateConversations(
                    response,
                    CURRENT_USER_ID,
                )
            }
        }
    }

    @Test
    fun `sync page validates cursor sequence and distinguishes incoming from own outbound events`() {
        val incoming = incomingMessageEvent(id = "10")
        val outgoing = outgoingMessageEvent(id = "11")
        val validated = SecureMessagingTransportValidator.validateSyncPage(
            response = MessagingSyncDto(
                events = listOf(incoming, outgoing),
                page = CursorPageDto(nextCursor = "next_cursor", hasMore = true, limit = 2),
            ),
            currentUserId = CURRENT_USER_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            requestedCursor = "old_cursor",
            requestedLimit = 2,
            previousEventId = 9,
        )

        assertEquals("next_cursor", validated.nextCursor)
        assertTrue(validated.hasMore)
        assertEquals(11L, validated.lastEventId)
        assertTrue(validated.events[0] is ValidatedMessagingSyncEvent.IncomingMessage)
        assertTrue(validated.events[1] is ValidatedMessagingSyncEvent.OutboundMessage)
    }

    @Test
    fun `outbound projection accepts the server six digit message timestamp`() {
        val precise = "2026-07-19T12:02:00.123456Z"
        val eventTime = "2026-07-19T12:02:00.234567Z"
        val event = outgoingMessageEvent(id = "11").let { original ->
            original.copy(
                data = original.data?.copy(sentAt = precise),
                occurredAt = eventTime,
            )
        }
        val validated = SecureMessagingTransportValidator.validateSyncPage(
            response = MessagingSyncDto(
                events = listOf(event),
                page = CursorPageDto(nextCursor = "precise_cursor", hasMore = false, limit = 1),
            ),
            currentUserId = CURRENT_USER_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            requestedCursor = "old_cursor",
            requestedLimit = 1,
            previousEventId = 10,
        )

        val projected = validated.events.single() as ValidatedMessagingSyncEvent.OutboundMessage
        assertEquals(Instant.parse(precise), projected.message.sentAt)
        assertEquals(Instant.parse(eventTime), projected.occurredAt)
    }

    @Test
    fun `sync strictly validates delivery and read receipt events`() {
        val validated = SecureMessagingTransportValidator.validateSyncPage(
            response = MessagingSyncDto(
                events = listOf(
                    deliveryReceiptEvent(id = "12"),
                    readReceiptEvent(id = "13"),
                ),
                page = CursorPageDto(nextCursor = "receipt_cursor", hasMore = false, limit = 2),
            ),
            currentUserId = CURRENT_USER_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            requestedCursor = "old_cursor",
            requestedLimit = 2,
            previousEventId = 11,
        )

        val delivery = validated.events[0] as ValidatedMessagingSyncEvent.DeliveryReceipt
        assertEquals(MESSAGE_ID, delivery.messageId)
        val read = validated.events[1] as ValidatedMessagingSyncEvent.ReadReceipt
        assertEquals(OTHER_USER_ID, read.userId)
        assertEquals(MESSAGE_ID, read.lastReadMessageId)

        listOf(
            deliveryReceiptEvent("12").copy(resourceType = "message"),
            deliveryReceiptEvent("12").copy(resourceId = OTHER_MESSAGE_ID),
            deliveryReceiptEvent("12").copy(
                data = deliveryReceiptEvent("12").data?.copy(deliveryState = "delivered_to_device"),
            ),
            deliveryReceiptEvent("12").copy(occurredAt = SENT_AT),
            readReceiptEvent("12").copy(resourceId = OTHER_USER_ID),
            readReceiptEvent("12").copy(data = readReceiptEvent("12").data?.copy(userId = "bad")),
            readReceiptEvent("12").copy(occurredAt = SENT_AT),
            readReceiptEvent(id = "12").copy(type = "reaction.changed"),
        ).forEach { unsupported ->
            assertRejected {
                SecureMessagingTransportValidator.validateSyncPage(
                    response = MessagingSyncDto(
                        events = listOf(unsupported),
                        page = CursorPageDto(nextCursor = "next_cursor", hasMore = false, limit = 1),
                    ),
                    currentUserId = CURRENT_USER_ID,
                    currentDeviceId = CURRENT_DEVICE_ID,
                    requestedCursor = "old_cursor",
                    requestedLimit = 1,
                    previousEventId = 9,
                )
            }
        }
    }

    @Test
    fun `delivery and read transitions accept seconds or exact six digit precision`() {
        val precise = "2026-07-19T12:04:00.123456Z"
        val events = listOf(
            deliveryReceiptEvent("12").let { event ->
                event.copy(
                    data = event.data?.copy(deliveredAt = precise),
                    occurredAt = precise,
                )
            },
            readReceiptEvent("13").let { event ->
                event.copy(
                    data = event.data?.copy(readAt = precise),
                    occurredAt = precise,
                )
            },
        )
        val validated = SecureMessagingTransportValidator.validateSyncPage(
            response = MessagingSyncDto(
                events = events,
                page = CursorPageDto(nextCursor = "precise_receipts", hasMore = false, limit = 2),
            ),
            currentUserId = CURRENT_USER_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            requestedCursor = "old_cursor",
            requestedLimit = 2,
            previousEventId = 11,
        )

        assertEquals(
            Instant.parse(precise),
            (validated.events[0] as ValidatedMessagingSyncEvent.DeliveryReceipt).deliveredAt,
        )
        assertEquals(
            Instant.parse(precise),
            (validated.events[1] as ValidatedMessagingSyncEvent.ReadReceipt).readAt,
        )
        assertEquals(
            Instant.parse(precise),
            SecureMessagingTransportValidator.validateDeliveryAcknowledgement(
                response = MessageDeliveryAcknowledgementDto(
                    deliveryState = "delivered_to_device",
                    deviceId = CURRENT_DEVICE_ID,
                    acknowledgedCount = 1,
                    newlyAcknowledgedCount = 1,
                    items = listOf(MessageDeliveryReceiptDto(MESSAGE_ID, precise)),
                ),
                expectedCurrentDeviceId = CURRENT_DEVICE_ID,
                expectedMessageIds = listOf(MESSAGE_ID),
            ).items.single().deliveredAt,
        )
        assertEquals(
            Instant.parse(precise),
            SecureMessagingTransportValidator.validateReadReceipt(
                response = MessagingReadReceiptDto(
                    conversationId = CONVERSATION_ID,
                    userId = CURRENT_USER_ID,
                    lastReadMessageId = MESSAGE_ID,
                    readAt = precise,
                ),
                expectedConversationId = CONVERSATION_ID,
                expectedCurrentUserId = CURRENT_USER_ID,
                requestedMessageId = MESSAGE_ID,
            ).readAt,
        )

        listOf(
            deliveryReceiptEvent("12").let { event ->
                event.copy(data = event.data?.copy(deliveredAt = "2026-07-19T12:04:00.123Z"))
            },
            readReceiptEvent("12").let { event ->
                event.copy(data = event.data?.copy(readAt = "2026-07-19T12:04:00.123Z"))
            },
        ).forEach { malformed ->
            assertRejected {
                SecureMessagingTransportValidator.validateSyncPage(
                    response = MessagingSyncDto(
                        events = listOf(malformed),
                        page = CursorPageDto(nextCursor = "bad_precision", hasMore = false, limit = 1),
                    ),
                    currentUserId = CURRENT_USER_ID,
                    currentDeviceId = CURRENT_DEVICE_ID,
                    requestedCursor = "old_cursor",
                    requestedLimit = 1,
                    previousEventId = 11,
                )
            }
        }
        assertRejected {
            SecureMessagingTransportValidator.validateDeliveryAcknowledgement(
                response = MessageDeliveryAcknowledgementDto(
                    deliveryState = "delivered_to_device",
                    deviceId = CURRENT_DEVICE_ID,
                    acknowledgedCount = 1,
                    newlyAcknowledgedCount = 1,
                    items = listOf(MessageDeliveryReceiptDto(MESSAGE_ID, "2026-07-19T12:04:00.123Z")),
                ),
                expectedCurrentDeviceId = CURRENT_DEVICE_ID,
                expectedMessageIds = listOf(MESSAGE_ID),
            )
        }
        assertRejected {
            SecureMessagingTransportValidator.validateReadReceipt(
                response = MessagingReadReceiptDto(
                    conversationId = CONVERSATION_ID,
                    userId = CURRENT_USER_ID,
                    lastReadMessageId = MESSAGE_ID,
                    readAt = "2026-07-19T12:04:00.123Z",
                ),
                expectedConversationId = CONVERSATION_ID,
                expectedCurrentUserId = CURRENT_USER_ID,
                requestedMessageId = MESSAGE_ID,
            )
        }
    }

    @Test
    fun `membership events carry the subject and its new role and nothing else`() {
        val validated = SecureMessagingTransportValidator.validateSyncPage(
            response = MessagingSyncDto(
                events = listOf(
                    membershipEvent(id = "14", type = "membership.added", role = null),
                    membershipEvent(id = "15", type = "membership.role_changed", role = "admin"),
                    membershipEvent(id = "16", type = "membership.removed", role = null),
                ),
                page = CursorPageDto(nextCursor = "membership_cursor", hasMore = false, limit = 3),
            ),
            currentUserId = CURRENT_USER_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            requestedCursor = "old_cursor",
            requestedLimit = 3,
            previousEventId = 13,
        )

        val metadata = validated.events.map { it as ValidatedMessagingSyncEvent.Metadata }
        assertEquals(
            listOf("membership.added", "membership.role_changed", "membership.removed"),
            metadata.map { it.type },
        )
        assertEquals(listOf(THIRD_USER_ID, THIRD_USER_ID, THIRD_USER_ID), metadata.map { it.memberUserId })
        assertEquals(listOf(null, "admin", null), metadata.map { it.memberRole })
    }

    @Test
    fun `conversation updated uses the canonical lifecycle name`() {
        val event = MessagingSyncEventDto(
            id = "14",
            type = "conversation.updated",
            conversationId = GROUP_ID,
            resourceType = "conversation",
            resourceId = GROUP_ID,
            data = MessagingSyncEventDataDto(),
            occurredAt = UPDATED_AT,
        )
        val validated = SecureMessagingTransportValidator.validateSyncPage(
            response = MessagingSyncDto(
                events = listOf(event),
                page = CursorPageDto(nextCursor = "updated_cursor", hasMore = false, limit = 1),
            ),
            currentUserId = CURRENT_USER_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            requestedCursor = "old_cursor",
            requestedLimit = 1,
            previousEventId = 13,
        )

        assertEquals("conversation.updated", (validated.events.single() as ValidatedMessagingSyncEvent.Metadata).type)
    }

    @Test
    fun `membership events without a valid subject or a known role fail closed`() {
        listOf(
            membershipEvent("14").copy(resourceType = "conversation"),
            membershipEvent("14").copy(resourceId = GROUP_ID),
            membershipEvent("14").copy(resourceId = "$CONVERSATION_ID:7"),
            membershipEvent("14").copy(data = null),
            membershipEvent("14", userId = null),
            membershipEvent("14", userId = "not-a-user-id"),
            // An unfamiliar role would otherwise be rendered as a membership line nobody can read.
            membershipEvent("14", role = "superuser"),
        ).forEach { rejected ->
            assertRejected {
                SecureMessagingTransportValidator.validateSyncPage(
                    response = MessagingSyncDto(
                        events = listOf(rejected),
                        page = CursorPageDto(nextCursor = "next_cursor", hasMore = false, limit = 1),
                    ),
                    currentUserId = CURRENT_USER_ID,
                    currentDeviceId = CURRENT_DEVICE_ID,
                    requestedCursor = "old_cursor",
                    requestedLimit = 1,
                    previousEventId = 13,
                )
            }
        }
    }

    @Test
    fun `sync permits an empty filtered page only when its opaque cursor advances`() {
        val page = SecureMessagingTransportValidator.validateSyncPage(
            response = MessagingSyncDto(
                events = emptyList(),
                page = CursorPageDto(nextCursor = "cursor_two", hasMore = true, limit = 50),
            ),
            currentUserId = CURRENT_USER_ID,
            currentDeviceId = CURRENT_DEVICE_ID,
            requestedCursor = "cursor_one",
            requestedLimit = 50,
            previousEventId = 9,
        )

        assertTrue(page.events.isEmpty())
        assertEquals(9L, page.lastEventId)

        assertRejected {
            SecureMessagingTransportValidator.validateSyncPage(
                response = MessagingSyncDto(
                    events = emptyList(),
                    page = CursorPageDto(nextCursor = "cursor_one", hasMore = true, limit = 50),
                ),
                currentUserId = CURRENT_USER_ID,
                currentDeviceId = CURRENT_DEVICE_ID,
                requestedCursor = "cursor_one",
                requestedLimit = 50,
            )
        }
    }

    @Test
    fun `sync rejects missing pagination nonmonotonic IDs unknown events and route substitution`() {
        val validEvent = incomingMessageEvent("10")
        val validPage = CursorPageDto(nextCursor = "next", hasMore = false, limit = 2)
        val malformed = listOf(
            MessagingSyncDto(events = listOf(validEvent), page = null),
            MessagingSyncDto(
                events = listOf(validEvent),
                page = validPage.copy(nextCursor = null),
            ),
            MessagingSyncDto(
                events = listOf(validEvent),
                page = validPage.copy(hasMore = null),
            ),
            MessagingSyncDto(
                events = listOf(validEvent),
                page = validPage.copy(limit = 1),
            ),
            MessagingSyncDto(events = null, page = validPage),
            MessagingSyncDto(events = listOf(null), page = validPage),
            MessagingSyncDto(
                events = listOf(validEvent, validEvent),
                page = validPage,
            ),
            MessagingSyncDto(
                events = listOf(validEvent.copy(type = "future.unreviewed")),
                page = validPage,
            ),
            MessagingSyncDto(
                events = listOf(validEvent.copy(resourceId = OTHER_MESSAGE_ID)),
                page = validPage,
            ),
        )

        malformed.forEach { response ->
            assertRejected {
                SecureMessagingTransportValidator.validateSyncPage(
                    response = response,
                    currentUserId = CURRENT_USER_ID,
                    currentDeviceId = CURRENT_DEVICE_ID,
                    requestedCursor = "old",
                    requestedLimit = 2,
                    previousEventId = 9,
                )
            }
        }
    }

    @Test
    fun `outbound response accepts only the current sender metadata and no sender envelope`() {
        val response = outgoingMessage()
        val validated = SecureMessagingTransportValidator.validateOutboundSendResponse(
            response = response,
            expectedConversationId = CONVERSATION_ID,
            expectedClientMessageId = CLIENT_MESSAGE_ID,
            expectedCurrentUserId = CURRENT_USER_ID,
            expectedCurrentDeviceId = CURRENT_DEVICE_ID,
            expectedRosterRevision = ROSTER_REVISION,
        )

        assertEquals(MESSAGE_ID, validated.messageId)
        assertEquals(CURRENT_DEVICE_ID, validated.senderDeviceId)

        listOf(
            response.copy(clientMessageId = OTHER_CLIENT_MESSAGE_ID),
            response.copy(senderDeviceId = OTHER_DEVICE_ID),
            response.copy(sender = response.sender?.copy(id = OTHER_USER_ID)),
            response.copy(rosterRevision = OTHER_ROSTER_REVISION),
            response.copy(
                envelope = EncryptedMessageEnvelopeDto(
                    recipientDeviceId = CURRENT_DEVICE_ID,
                    envelopeType = "signal-message-v2",
                    ciphertext = "AQ==",
                    ciphertextSha256 = sha256(byteArrayOf(1)),
                ),
            ),
        ).forEach { malformed ->
            assertRejected {
                SecureMessagingTransportValidator.validateOutboundSendResponse(
                    response = malformed,
                    expectedConversationId = CONVERSATION_ID,
                    expectedClientMessageId = CLIENT_MESSAGE_ID,
                    expectedCurrentUserId = CURRENT_USER_ID,
                    expectedCurrentDeviceId = CURRENT_DEVICE_ID,
                    expectedRosterRevision = ROSTER_REVISION,
                )
            }
        }
    }

    @Test
    fun `delivery acknowledgement matches the requested device and ID set independent of item order`() {
        val response = MessageDeliveryAcknowledgementDto(
            deliveryState = "delivered_to_device",
            deviceId = CURRENT_DEVICE_ID,
            acknowledgedCount = 2,
            newlyAcknowledgedCount = 1,
            items = listOf(
                MessageDeliveryReceiptDto(OTHER_MESSAGE_ID, READ_AT),
                MessageDeliveryReceiptDto(MESSAGE_ID, SENT_AT),
            ),
        )
        val validated = SecureMessagingTransportValidator.validateDeliveryAcknowledgement(
            response,
            expectedCurrentDeviceId = CURRENT_DEVICE_ID,
            expectedMessageIds = listOf(MESSAGE_ID, OTHER_MESSAGE_ID),
        )

        assertEquals(setOf(MESSAGE_ID, OTHER_MESSAGE_ID), validated.items.map { it.messageId }.toSet())
        assertEquals(1, validated.newlyAcknowledgedCount)

        listOf(
            response.copy(deviceId = OTHER_DEVICE_ID),
            response.copy(acknowledgedCount = 1),
            response.copy(newlyAcknowledgedCount = 3),
            response.copy(
                items = listOf(
                    MessageDeliveryReceiptDto(MESSAGE_ID, SENT_AT),
                    MessageDeliveryReceiptDto(MESSAGE_ID, READ_AT),
                ),
            ),
            response.copy(
                items = listOf(
                    MessageDeliveryReceiptDto(MESSAGE_ID, SENT_AT),
                    MessageDeliveryReceiptDto(THIRD_MESSAGE_ID, READ_AT),
                ),
            ),
        ).forEach { malformed ->
            assertRejected {
                SecureMessagingTransportValidator.validateDeliveryAcknowledgement(
                    malformed,
                    CURRENT_DEVICE_ID,
                    listOf(MESSAGE_ID, OTHER_MESSAGE_ID),
                )
            }
        }
    }

    @Test
    fun `read receipt validates canonical server marker without requiring request equality`() {
        val response = MessagingReadReceiptDto(
            conversationId = CONVERSATION_ID,
            userId = CURRENT_USER_ID,
            lastReadMessageId = OTHER_MESSAGE_ID,
            readAt = READ_AT,
        )

        val validated = SecureMessagingTransportValidator.validateReadReceipt(
            response = response,
            expectedConversationId = CONVERSATION_ID,
            expectedCurrentUserId = CURRENT_USER_ID,
            requestedMessageId = MESSAGE_ID,
        )
        assertEquals(OTHER_MESSAGE_ID, validated.lastReadMessageId)

        listOf(
            response.copy(conversationId = GROUP_ID),
            response.copy(userId = OTHER_USER_ID),
            response.copy(lastReadMessageId = "not-a-message-id"),
            response.copy(lastReadMessageId = null),
            response.copy(readAt = null),
        ).forEach { malformed ->
            assertRejected {
                SecureMessagingTransportValidator.validateReadReceipt(
                    response = malformed,
                    expectedConversationId = CONVERSATION_ID,
                    expectedCurrentUserId = CURRENT_USER_ID,
                    requestedMessageId = MESSAGE_ID,
                )
            }
        }
    }

    private fun device(id: String, isCurrent: Boolean) = DeviceDto(
        id = id,
        name = "Android phone",
        platform = "android",
        model = "Kit Test",
        isCurrent = isCurrent,
        lastSeenAt = UPDATED_AT,
        createdAt = CREATED_AT,
        isTrusted = true,
        trustExpiresAt = TRUST_EXPIRES_AT,
    )

    private fun directConversation() = MessagingConversationDto(
        id = CONVERSATION_ID,
        type = "direct",
        title = null,
        parentId = null,
        createdBy = CURRENT_USER_ID,
        role = "owner",
        members = listOf(
            MessagingConversationMemberDto(
                userId = CURRENT_USER_ID,
                name = "Current User",
                role = "owner",
                joinedAt = JOINED_AT,
            ),
            MessagingConversationMemberDto(
                userId = OTHER_USER_ID,
                name = "Amina",
                role = "member",
                joinedAt = JOINED_AT,
            ),
        ),
        createdAt = CREATED_AT,
        updatedAt = UPDATED_AT,
    )

    private fun groupConversation() = directConversation().copy(
        id = GROUP_ID,
        type = "group",
        title = "Weekend savings",
        members = directConversation().members.orEmpty() + MessagingConversationMemberDto(
            userId = THIRD_USER_ID,
            name = "Brian",
            role = "member",
            joinedAt = JOINED_AT,
        ),
    )

    private fun incomingMessageEvent(id: String): MessagingSyncEventDto {
        val message = incomingMessage()
        return messageEvent(id, message)
    }

    private fun outgoingMessageEvent(id: String): MessagingSyncEventDto =
        messageEvent(id, outgoingMessage().copy(id = OTHER_MESSAGE_ID, clientMessageId = OTHER_CLIENT_MESSAGE_ID))

    private fun messageEvent(id: String, message: EncryptedMessageDto) = MessagingSyncEventDto(
        id = id,
        type = "message.created",
        conversationId = message.conversationId,
        resourceType = "message",
        resourceId = message.id,
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
            replyToMessageId = message.replyToMessageId,
            envelope = message.envelope,
            attachments = message.attachments,
            reactions = message.reactions,
            sentAt = message.sentAt,
            revokedAt = message.revokedAt,
        ),
        occurredAt = message.sentAt,
    )

    private fun readReceiptEvent(id: String) = MessagingSyncEventDto(
        id = id,
        type = "read_receipt.updated",
        conversationId = CONVERSATION_ID,
        resourceType = "read_receipt",
        resourceId = "$CONVERSATION_ID:42",
        data = MessagingSyncEventDataDto(
            userId = OTHER_USER_ID,
            lastReadMessageId = MESSAGE_ID,
            readAt = READ_AT,
        ),
        occurredAt = READ_AT,
    )

    private fun membershipEvent(
        id: String,
        type: String = "membership.added",
        userId: String? = THIRD_USER_ID,
        role: String? = "member",
    ) = MessagingSyncEventDto(
        id = id,
        type = type,
        conversationId = GROUP_ID,
        resourceType = "conversation_member",
        resourceId = "$GROUP_ID:7",
        data = MessagingSyncEventDataDto(userId = userId, role = role),
        occurredAt = READ_AT,
    )

    private fun deliveryReceiptEvent(id: String) = MessagingSyncEventDto(
        id = id,
        type = "message.delivery.updated",
        conversationId = CONVERSATION_ID,
        resourceType = "message_delivery",
        resourceId = MESSAGE_ID,
        data = MessagingSyncEventDataDto(
            messageId = MESSAGE_ID,
            deliveryState = "delivered_to_peer",
            deliveredAt = READ_AT,
        ),
        occurredAt = READ_AT,
    )

    private fun incomingMessage(): EncryptedMessageDto {
        val ciphertext = "opaque encrypted payload".toByteArray()
        return EncryptedMessageDto(
            id = MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            clientMessageId = CLIENT_MESSAGE_ID,
            sender = EncryptedMessageSenderDto(OTHER_USER_ID, "Amina"),
            senderDeviceId = OTHER_DEVICE_ID,
            senderSignalDeviceId = 2,
            senderRegistrationId = 42,
            senderProtocolVersion = "v2",
            senderBundleVersion = 4,
            senderIdentityKeySha256 = "c".repeat(64),
            rosterRevision = ROSTER_REVISION,
            kind = ENCRYPTED_MESSAGE_KIND,
            replyToMessageId = null,
            envelope = EncryptedMessageEnvelopeDto(
                recipientDeviceId = CURRENT_DEVICE_ID,
                envelopeType = "signal-message-v2",
                ciphertext = Base64.getEncoder().encodeToString(ciphertext),
                ciphertextSha256 = sha256(ciphertext),
            ),
            attachments = emptyList(),
            reactions = emptyList(),
            sentAt = SENT_AT,
            revokedAt = null,
        )
    }

    private fun outgoingMessage() = EncryptedMessageDto(
        id = MESSAGE_ID,
        conversationId = CONVERSATION_ID,
        clientMessageId = CLIENT_MESSAGE_ID,
        sender = EncryptedMessageSenderDto(CURRENT_USER_ID, "Current User"),
        senderDeviceId = CURRENT_DEVICE_ID,
        senderSignalDeviceId = 1,
        senderRegistrationId = 41,
        senderProtocolVersion = "v2",
        senderBundleVersion = 3,
        senderIdentityKeySha256 = "b".repeat(64),
        rosterRevision = ROSTER_REVISION,
        kind = ENCRYPTED_MESSAGE_KIND,
        replyToMessageId = null,
        envelope = null,
        attachments = emptyList(),
        reactions = emptyList(),
        sentAt = SENT_AT,
        revokedAt = null,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertRejected(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is SecureMessagingWireValidationException)
    }

    private companion object {
        const val CURRENT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_USER_ID = "22222222-2222-4222-8222-222222222222"
        const val THIRD_USER_ID = "33333333-3333-4333-8333-333333333333"
        const val CURRENT_DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val OTHER_DEVICE_ID = "55555555-5555-4555-8555-555555555555"
        const val CONVERSATION_ID = "66666666-6666-4666-8666-666666666666"
        const val GROUP_ID = "77777777-7777-4777-8777-777777777777"
        const val MESSAGE_ID = "88888888-8888-4888-8888-888888888888"
        const val OTHER_MESSAGE_ID = "99999999-9999-4999-8999-999999999999"
        const val THIRD_MESSAGE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CLIENT_MESSAGE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OTHER_CLIENT_MESSAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val ROSTER_REVISION =
            "v1:sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_ROSTER_REVISION =
            "v1:sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val BEFORE_CREATED_AT = "2026-07-19T11:59:59Z"
        const val CREATED_AT = "2026-07-19T12:00:00Z"
        const val JOINED_AT = "2026-07-19T12:00:01Z"
        const val UPDATED_AT = "2026-07-19T12:01:00Z"
        const val SENT_AT = "2026-07-19T12:02:00Z"
        const val READ_AT = "2026-07-19T12:03:00Z"
        const val TRUST_EXPIRES_AT = "2026-08-19T12:00:00Z"
    }
}
