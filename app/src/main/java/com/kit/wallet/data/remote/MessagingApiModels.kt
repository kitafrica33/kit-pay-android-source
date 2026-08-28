package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Ciphertext-only wire models for the dormant secure-messaging API.
 *
 * These types do not make messaging available and deliberately contain no plaintext message
 * property. Response fields remain nullable so malformed or rolling-deployment data reaches a
 * future validation boundary instead of crashing Moshi or being mistaken for authoritative state.
 */

@JsonClass(generateAdapter = false)
data class MessagingSignedPrekeyRequest(
    @Json(name = "prekey_id") val prekeyId: Int,
    @Json(name = "public_key") val publicKey: String,
    val signature: String,
)

@JsonClass(generateAdapter = false)
data class MessagingOneTimePrekeyRequest(
    @Json(name = "prekey_id") val prekeyId: Int,
    @Json(name = "public_key") val publicKey: String,
)

@JsonClass(generateAdapter = false)
data class MessagingPqPrekeyRequest(
    @Json(name = "prekey_id") val prekeyId: Int,
    @Json(name = "public_key") val publicKey: String,
    val signature: String,
)

@JsonClass(generateAdapter = false)
data class PublishMessagingKeyBundleRequest(
    @Json(name = "protocol_version") val protocolVersion: String,
    @Json(name = "registration_id") val registrationId: Int,
    @Json(name = "identity_key") val identityKey: String,
    @Json(name = "identity_key_change") val identityKeyChange: Boolean = false,
    @Json(name = "signed_prekey") val signedPrekey: MessagingSignedPrekeyRequest,
    @Json(name = "one_time_prekeys") val oneTimePrekeys: List<MessagingOneTimePrekeyRequest>,
    @Json(name = "pq_prekeys") val pqPrekeys: List<MessagingPqPrekeyRequest>,
    @Json(name = "pq_last_resort_prekey")
    val pqLastResortPrekey: MessagingPqPrekeyRequest,
) {
    init {
        require(protocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION) {
            "Kit Pay may publish only the reviewed secure-messaging protocol version"
        }
    }
}

@JsonClass(generateAdapter = false)
data class MessagingKeyTransparencyDto(
    val revision: String? = null,
    @Json(name = "event_type") val eventType: String? = null,
    @Json(name = "protocol_version") val protocolVersion: String? = null,
    @Json(name = "event_hash") val eventHash: String? = null,
    @Json(name = "previous_event_hash") val previousEventHash: String? = null,
    @Json(name = "identity_key_sha256") val identityKeySha256: String? = null,
    @Json(name = "previous_identity_key_sha256")
    val previousIdentityKeySha256: String? = null,
    @Json(name = "pq_last_resort_prekey_id") val pqLastResortPrekeyId: Int? = null,
    @Json(name = "pq_last_resort_prekey_sha256")
    val pqLastResortPrekeySha256: String? = null,
    @Json(name = "occurred_at") val occurredAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingKeyStatusDto(
    val enrolled: Boolean? = null,
    @Json(name = "enrollment_epoch") val enrollmentEpoch: Long? = null,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "signal_device_id") val signalDeviceId: Int? = null,
    @Json(name = "protocol_version") val protocolVersion: String? = null,
    @Json(name = "registration_id") val registrationId: Int? = null,
    @Json(name = "identity_key_sha256") val identityKeySha256: String? = null,
    @Json(name = "signed_prekey_id") val signedPrekeyId: Int? = null,
    @Json(name = "signed_prekey_sha256") val signedPrekeySha256: String? = null,
    @Json(name = "pq_last_resort_prekey_id") val pqLastResortPrekeyId: Int? = null,
    @Json(name = "pq_last_resort_prekey_sha256")
    val pqLastResortPrekeySha256: String? = null,
    @Json(name = "bundle_version") val bundleVersion: Int? = null,
    @Json(name = "available_one_time_prekeys") val availableOneTimePrekeys: Int? = null,
    @Json(name = "available_ec_one_time_prekeys")
    val availableEcOneTimePrekeys: Int? = null,
    @Json(name = "available_pq_one_time_prekeys")
    val availablePqOneTimePrekeys: Int? = null,
    @Json(name = "replenish_at") val replenishAt: Int? = null,
    @Json(name = "needs_replenishment") val needsReplenishment: Boolean? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "rotated_at") val rotatedAt: String? = null,
    val transparency: MessagingKeyTransparencyDto? = null,
)

@JsonClass(generateAdapter = false)
data class ResetMessagingEnrollmentRequest(
    @Json(name = "expected_enrollment_epoch") val expectedEnrollmentEpoch: Long,
    @Json(name = "expected_registration_id") val expectedRegistrationId: Int,
    @Json(name = "expected_identity_key_sha256") val expectedIdentityKeySha256: String,
    @Json(name = "expected_bundle_version") val expectedBundleVersion: Int,
)

@JsonClass(generateAdapter = false)
data class ResetMessagingEnrollmentDto(
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "previous_enrollment_epoch") val previousEnrollmentEpoch: Long? = null,
    @Json(name = "enrollment_epoch") val enrollmentEpoch: Long? = null,
    val enrolled: Boolean? = null,
    @Json(name = "reset_applied") val resetApplied: Boolean? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingConversationMemberDto(
    @Json(name = "user_id") val userId: String? = null,
    val name: String? = null,
    val role: String? = null,
    @Json(name = "joined_at") val joinedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingConversationDto(
    val id: String? = null,
    val type: String? = null,
    val title: String? = null,
    val description: String? = null,
    @Json(name = "photo_url") val photoUrl: String? = null,
    @Json(name = "parent_id") val parentId: String? = null,
    @Json(name = "created_by") val createdBy: String? = null,
    val role: String? = null,
    val members: List<MessagingConversationMemberDto?>? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingConversationListDto(
    val items: List<MessagingConversationDto?>? = null,
)

/**
 * Creates a direct or group conversation.
 *
 * The two are one request because the server treats them as one resource, but they are not
 * interchangeable: a direct conversation is keyed by its pair and carries no title, while a
 * group is titled, membership-mutable and role-bearing. [title] is required for a group and
 * rejected for a direct chat, matching the server rule rather than trusting it.
 */
@JsonClass(generateAdapter = false)
data class CreateMessagingConversationRequest(
    @Json(name = "member_ids") val memberIds: List<String>,
    val type: String = DIRECT_CONVERSATION_TYPE,
    val title: String? = null,
) {
    init {
        require(type == DIRECT_CONVERSATION_TYPE || type == GROUP_CONVERSATION_TYPE) {
            "This Android wire operation creates direct and group conversations only"
        }
        require(memberIds.distinct().size == memberIds.size) {
            "A conversation cannot contain duplicate members"
        }
        memberIds.forEach { requireCanonicalMessagingUuid(it, "conversation member ID") }

        if (type == DIRECT_CONVERSATION_TYPE) {
            require(memberIds.size == 1) {
                "A direct conversation requires exactly one other user"
            }
            require(title == null) {
                "A direct conversation cannot carry a server-visible title"
            }
        } else {
            // The creator is a member too, so the wire list is one short of the group size.
            require(memberIds.size in 1..(MAX_GROUP_MEMBERS - 1)) {
                "A group requires 1 to ${MAX_GROUP_MEMBERS - 1} other members"
            }
            require(
                title != null &&
                    title == normalizeMessagingGroupTitle(title) &&
                    isValidMessagingGroupTitle(title)
            ) {
                "A group requires a title of at most $MAX_GROUP_TITLE_LENGTH Unicode scalars " +
                    "and $MAX_GROUP_TITLE_UTF8_BYTES UTF-8 bytes"
            }
        }
    }
}

@JsonClass(generateAdapter = false)
data class AddMessagingConversationMemberRequest(
    @Json(name = "user_id") val userId: String,
    val role: String = MEMBER_CONVERSATION_ROLE,
) {
    init {
        requireCanonicalMessagingUuid(userId, "conversation member ID")
        require(role in ASSIGNABLE_CONVERSATION_ROLES) { "Invalid conversation member role" }
    }
}

@JsonClass(generateAdapter = false)
data class UpdateMessagingConversationMemberRequest(val role: String) {
    init {
        require(role in ASSIGNABLE_CONVERSATION_ROLES) { "Invalid conversation member role" }
    }
}

/**
 * Edits a group's own description.
 *
 * Android sends this request for no other reason, so there is no "leave it alone" shape here:
 * a string sets the description and null clears it. The null has to reach the server as an
 * explicit JSON null — the server reads an absent field as "unchanged" — which is why this
 * type is written by [UpdateMessagingConversationRequestAdapter] rather than by the reflective
 * adapter that would silently drop it.
 */
@JsonClass(generateAdapter = false)
data class UpdateMessagingConversationRequest(
    val description: String?,
) {
    init {
        if (description != null) {
            require(
                description == normalizeMessagingGroupDescription(description) &&
                    isValidMessagingGroupDescription(description),
            ) {
                "A group description must be canonical and at most " +
                    "$MAX_GROUP_DESCRIPTION_LENGTH Unicode scalars and " +
                    "$MAX_GROUP_DESCRIPTION_UTF8_BYTES UTF-8 bytes"
            }
        }
    }
}

@JsonClass(generateAdapter = false)
data class AttachMessagingConversationPhotoRequest(
    @Json(name = "asset_id") val assetId: String,
) {
    init {
        requireCanonicalMessagingUuid(assetId, "group photo asset ID")
    }
}

@JsonClass(generateAdapter = false)
data class MessagingDeviceRosterEntryDto(
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "enrollment_epoch") val enrollmentEpoch: Long? = null,
    @Json(name = "signal_device_id") val signalDeviceId: Int? = null,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "registration_id") val registrationId: Int? = null,
    @Json(name = "protocol_version") val protocolVersion: String? = null,
    @Json(name = "bundle_version") val bundleVersion: Int? = null,
    @Json(name = "identity_key") val identityKey: String? = null,
    @Json(name = "identity_key_sha256") val identityKeySha256: String? = null,
    @Json(name = "signed_prekey") val signedPrekey: MessagingSignedPrekeyDto? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "rotated_at") val rotatedAt: String? = null,
    @Json(name = "identity_key_changed_at") val identityKeyChangedAt: String? = null,
    @Json(name = "bundle_version_changed_at") val bundleVersionChangedAt: String? = null,
    val client: MessagingDeviceClientDto? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingDeviceClientDto(
    val platform: String? = null,
    val version: String? = null,
    val build: Int? = null,
    val capabilities: Map<String, Boolean?>? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingDeviceRosterDto(
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "roster_revision") val rosterRevision: String? = null,
    @Json(name = "roster_hash") val rosterHash: String? = null,
    @Json(name = "hash_algorithm") val hashAlgorithm: String? = null,
    val devices: List<MessagingDeviceRosterEntryDto?>? = null,
)

@JsonClass(generateAdapter = false)
data class ConsumeMessagingKeyBundlesRequest(
    @Json(name = "device_ids") val deviceIds: List<String>? = null,
) {
    init {
        deviceIds?.let { ids ->
            require(ids.size in 1..MAX_SECURE_MESSAGE_RECIPIENT_DEVICES) {
                "A key-bundle claim requires 1 to $MAX_SECURE_MESSAGE_RECIPIENT_DEVICES devices"
            }
            require(ids.distinct().size == ids.size) {
                "A key-bundle claim cannot contain duplicate device IDs"
            }
            ids.forEach { requireCanonicalMessagingUuid(it, "key-bundle claim device ID") }
        }
    }
}

@JsonClass(generateAdapter = false)
data class MessagingSignedPrekeyDto(
    @Json(name = "prekey_id") val prekeyId: Int? = null,
    @Json(name = "public_key") val publicKey: String? = null,
    @Json(name = "public_key_sha256") val publicKeySha256: String? = null,
    val signature: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingOneTimePrekeyDto(
    @Json(name = "prekey_id") val prekeyId: Int? = null,
    @Json(name = "public_key") val publicKey: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingPqPrekeyDto(
    @Json(name = "prekey_id") val prekeyId: Int? = null,
    @Json(name = "public_key") val publicKey: String? = null,
    val signature: String? = null,
)

@JsonClass(generateAdapter = false)
data class ConsumedMessagingKeyBundleDto(
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "signal_device_id") val signalDeviceId: Int? = null,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "protocol_version") val protocolVersion: String? = null,
    @Json(name = "registration_id") val registrationId: Int? = null,
    @Json(name = "identity_key") val identityKey: String? = null,
    @Json(name = "identity_key_sha256") val identityKeySha256: String? = null,
    @Json(name = "signed_prekey") val signedPrekey: MessagingSignedPrekeyDto? = null,
    @Json(name = "one_time_prekey") val oneTimePrekey: MessagingOneTimePrekeyDto? = null,
    @Json(name = "pq_prekey") val pqPrekey: MessagingPqPrekeyDto? = null,
    @Json(name = "bundle_version") val bundleVersion: Int? = null,
    @Json(name = "available_one_time_prekeys") val availableOneTimePrekeys: Int? = null,
    @Json(name = "available_ec_one_time_prekeys")
    val availableEcOneTimePrekeys: Int? = null,
    @Json(name = "available_pq_one_time_prekeys")
    val availablePqOneTimePrekeys: Int? = null,
    @Json(name = "needs_replenishment") val needsReplenishment: Boolean? = null,
    @Json(name = "is_current_device") val isCurrentDevice: Boolean? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "rotated_at") val rotatedAt: String? = null,
    val transparency: MessagingKeyTransparencyDto? = null,
)

@JsonClass(generateAdapter = false)
data class ConsumedMessagingKeyBundlesDto(
    val bundles: List<ConsumedMessagingKeyBundleDto?>? = null,
)

@JsonClass(generateAdapter = false)
data class EncryptedDeviceEnvelopeRequest(
    @Json(name = "recipient_device_id") val recipientDeviceId: String,
    @Json(name = "envelope_type") val envelopeType: String,
    val ciphertext: String,
) {
    init {
        requireCanonicalMessagingUuid(recipientDeviceId, "encrypted-envelope recipient device ID")
        require(envelopeType in SECURE_MESSAGE_ENVELOPE_TYPES) {
            "Kit Pay may send only v2 secure-message envelopes"
        }
        requireCanonicalCiphertext(ciphertext)
    }
}

@JsonClass(generateAdapter = false)
data class EncryptedAttachmentRequest(
    val id: String,
    @Json(name = "storage_key") val storageKey: String,
    @Json(name = "media_type") val mediaType: String,
    @Json(name = "byte_size") val byteSize: Long,
    @Json(name = "ciphertext_sha256") val ciphertextSha256: String,
    @Json(name = "encryption_metadata_ciphertext")
    val encryptionMetadataCiphertext: String? = null,
)

@JsonClass(generateAdapter = false)
data class SendEncryptedMessageRequest(
    @Json(name = "client_message_id") val clientMessageId: String,
    @Json(name = "roster_revision") val rosterRevision: String,
    val kind: String,
    @Json(name = "reply_to_message_id") val replyToMessageId: String? = null,
    val envelopes: List<EncryptedDeviceEnvelopeRequest>,
    val attachments: List<EncryptedAttachmentRequest> = emptyList(),
) {
    init {
        requireCanonicalMessagingUuid(clientMessageId, "encrypted-message client ID")
        replyToMessageId?.let {
            requireCanonicalMessagingUuid(it, "encrypted-message reply target")
        }
        require(SECURE_MESSAGING_ROSTER_REVISION.matches(rosterRevision)) {
            "An encrypted message requires a valid authoritative roster revision"
        }
        require(envelopes.isNotEmpty()) { "An encrypted message requires a device envelope" }
        require(envelopes.size <= MAX_SECURE_MESSAGE_RECIPIENT_DEVICES) {
            "An encrypted message supports at most $MAX_SECURE_MESSAGE_RECIPIENT_DEVICES recipient devices"
        }
        require(envelopes.map(EncryptedDeviceEnvelopeRequest::recipientDeviceId).distinct().size == envelopes.size) {
            "An encrypted message requires exactly one envelope per recipient device"
        }
        // Mirrors the server contract: encrypted text carries no attachment metadata, while an
        // encrypted_attachment message must carry at least one row. Key material never appears
        // here; it rides end-to-end inside the per-device envelopes.
        require(kind in SECURE_MESSAGE_KINDS) {
            "Unsupported secure-message kind"
        }
        require((kind == ENCRYPTED_ATTACHMENT_MESSAGE_KIND) == attachments.isNotEmpty()) {
            "Encrypted attachment metadata must accompany exactly the encrypted_attachment kind"
        }
        require(kind != ENCRYPTED_REACTION_MESSAGE_KIND || replyToMessageId != null) {
            "An encrypted reaction requires a reply target"
        }
        require(kind != ENCRYPTED_EDIT_MESSAGE_KIND || replyToMessageId != null) {
            "An encrypted edit requires the message it replaces"
        }
        require(attachments.size <= MAX_SECURE_MESSAGE_ATTACHMENTS) {
            "An encrypted message supports at most $MAX_SECURE_MESSAGE_ATTACHMENTS attachments"
        }
        require(attachments.map(EncryptedAttachmentRequest::id).distinct().size == attachments.size) {
            "Encrypted attachment IDs must be unique"
        }
        require(
            attachments.map(EncryptedAttachmentRequest::storageKey).distinct().size ==
                attachments.size,
        ) { "Encrypted attachment storage keys must be unique" }
        attachments.forEach { attachment ->
            requireCanonicalMessagingUuid(attachment.id, "encrypted-attachment ID")
            require(attachment.storageKey.isNotBlank() && attachment.storageKey.length <= 512) {
                "Invalid encrypted-attachment storage key"
            }
            require(attachment.mediaType.isNotBlank() && attachment.mediaType.length <= 160) {
                "Invalid encrypted-attachment media type"
            }
            require(attachment.byteSize >= 1) { "Invalid encrypted-attachment byte size" }
            require(SECURE_MESSAGE_ATTACHMENT_SHA256.matches(attachment.ciphertextSha256)) {
                "Invalid encrypted-attachment ciphertext digest"
            }
        }
    }
}

@JsonClass(generateAdapter = false)
data class EncryptedMessageSenderDto(
    val id: String? = null,
    val name: String? = null,
)

@JsonClass(generateAdapter = false)
data class EncryptedMessageCryptoSenderDto(
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "enrollment_epoch") val enrollmentEpoch: Long? = null,
    @Json(name = "signal_device_id") val signalDeviceId: Int? = null,
    @Json(name = "registration_id") val registrationId: Int? = null,
    @Json(name = "protocol_version") val protocolVersion: String? = null,
    @Json(name = "bundle_version") val bundleVersion: Int? = null,
    @Json(name = "identity_key_sha256") val identityKeySha256: String? = null,
)

@JsonClass(generateAdapter = false)
data class EncryptedMessageEnvelopeDto(
    @Json(name = "recipient_device_id") val recipientDeviceId: String? = null,
    @Json(name = "recipient_enrollment_epoch") val recipientEnrollmentEpoch: Long? = null,
    @Json(name = "envelope_type") val envelopeType: String? = null,
    val ciphertext: String? = null,
    @Json(name = "ciphertext_sha256") val ciphertextSha256: String? = null,
    @Json(name = "is_history_backfill") val isHistoryBackfill: Boolean? = null,
    @Json(name = "transfer_client_message_id") val transferClientMessageId: String? = null,
    @Json(name = "transfer_roster_revision") val transferRosterRevision: String? = null,
    @Json(name = "crypto_sender") val cryptoSender: EncryptedMessageCryptoSenderDto? = null,
)

@JsonClass(generateAdapter = false)
data class EncryptedAttachmentDto(
    val id: String? = null,
    @Json(name = "storage_key") val storageKey: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "byte_size") val byteSize: Long? = null,
    @Json(name = "ciphertext_sha256") val ciphertextSha256: String? = null,
    @Json(name = "encryption_metadata_ciphertext")
    val encryptionMetadataCiphertext: String? = null,
)

@JsonClass(generateAdapter = false)
data class EncryptedMessageReactionDto(
    @Json(name = "user_id") val userId: String? = null,
    val reaction: String? = null,
    @Json(name = "reacted_at") val reactedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class EncryptedMessageDto(
    val id: String? = null,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "client_message_id") val clientMessageId: String? = null,
    val sender: EncryptedMessageSenderDto? = null,
    @Json(name = "sender_device_id") val senderDeviceId: String? = null,
    @Json(name = "sender_enrollment_epoch") val senderEnrollmentEpoch: Long? = null,
    @Json(name = "sender_signal_device_id") val senderSignalDeviceId: Int? = null,
    @Json(name = "sender_registration_id") val senderRegistrationId: Int? = null,
    @Json(name = "sender_protocol_version") val senderProtocolVersion: String? = null,
    @Json(name = "sender_bundle_version") val senderBundleVersion: Int? = null,
    @Json(name = "sender_identity_key_sha256") val senderIdentityKeySha256: String? = null,
    @Json(name = "roster_revision") val rosterRevision: String? = null,
    val kind: String? = null,
    @Json(name = "reply_to_message_id") val replyToMessageId: String? = null,
    val envelope: EncryptedMessageEnvelopeDto? = null,
    val attachments: List<EncryptedAttachmentDto?>? = null,
    val reactions: List<EncryptedMessageReactionDto?>? = null,
    @Json(name = "sent_at") val sentAt: String? = null,
    @Json(name = "revoked_at") val revokedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingSyncEventDataDto(
    val id: String? = null,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "client_message_id") val clientMessageId: String? = null,
    val sender: EncryptedMessageSenderDto? = null,
    @Json(name = "sender_device_id") val senderDeviceId: String? = null,
    @Json(name = "sender_enrollment_epoch") val senderEnrollmentEpoch: Long? = null,
    @Json(name = "sender_signal_device_id") val senderSignalDeviceId: Int? = null,
    @Json(name = "sender_registration_id") val senderRegistrationId: Int? = null,
    @Json(name = "sender_protocol_version") val senderProtocolVersion: String? = null,
    @Json(name = "sender_bundle_version") val senderBundleVersion: Int? = null,
    @Json(name = "sender_identity_key_sha256") val senderIdentityKeySha256: String? = null,
    @Json(name = "roster_revision") val rosterRevision: String? = null,
    val kind: String? = null,
    @Json(name = "reply_to_message_id") val replyToMessageId: String? = null,
    val envelope: EncryptedMessageEnvelopeDto? = null,
    val attachments: List<EncryptedAttachmentDto?>? = null,
    val reactions: List<EncryptedMessageReactionDto?>? = null,
    @Json(name = "sent_at") val sentAt: String? = null,
    @Json(name = "revoked_at") val revokedAt: String? = null,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "user_id") val userId: String? = null,
    /** Present on `membership.*`: the subject's role after the change, never the actor's. */
    val role: String? = null,
    @Json(name = "enrollment_epoch") val enrollmentEpoch: Long? = null,
    @Json(name = "signal_device_id") val signalDeviceId: Int? = null,
    @Json(name = "registration_id") val registrationId: Int? = null,
    @Json(name = "previous_registration_id") val previousRegistrationId: Int? = null,
    @Json(name = "protocol_version") val protocolVersion: String? = null,
    @Json(name = "previous_protocol_version") val previousProtocolVersion: String? = null,
    @Json(name = "bundle_version") val bundleVersion: Int? = null,
    @Json(name = "identity_key_sha256") val identityKeySha256: String? = null,
    @Json(name = "previous_identity_key_sha256")
    val previousIdentityKeySha256: String? = null,
    @Json(name = "revoked_device_count") val revokedDeviceCount: Int? = null,
    @Json(name = "roster_refresh_required") val rosterRefreshRequired: Boolean? = null,
    @Json(name = "transitioned_at") val transitionedAt: String? = null,
    @Json(name = "transition_hash") val transitionHash: String? = null,
    @Json(name = "last_read_message_id") val lastReadMessageId: String? = null,
    @Json(name = "read_at") val readAt: String? = null,
    @Json(name = "message_id") val messageId: String? = null,
    @Json(name = "delivery_state") val deliveryState: String? = null,
    @Json(name = "delivered_at") val deliveredAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingSyncEventDto(
    val id: String? = null,
    val type: String? = null,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "resource_type") val resourceType: String? = null,
    @Json(name = "resource_id") val resourceId: String? = null,
    val data: MessagingSyncEventDataDto? = null,
    @Json(name = "occurred_at") val occurredAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingSyncDto(
    val events: List<MessagingSyncEventDto?>? = null,
    val page: CursorPageDto? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingHistoryTargetCryptoBundleDto(
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "enrollment_epoch") val enrollmentEpoch: Long? = null,
    @Json(name = "signal_device_id") val signalDeviceId: Int? = null,
    @Json(name = "registration_id") val registrationId: Int? = null,
    @Json(name = "protocol_version") val protocolVersion: String? = null,
    @Json(name = "bundle_version") val bundleVersion: Int? = null,
    @Json(name = "identity_key_sha256") val identityKeySha256: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingHistoryBackfillCandidatesDto(
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "roster_revision") val rosterRevision: String? = null,
    @Json(name = "target_crypto_bundle")
    val targetCryptoBundle: MessagingHistoryTargetCryptoBundleDto? = null,
    val messages: List<EncryptedMessageDto?>? = null,
    val page: CursorPageDto? = null,
)

@JsonClass(generateAdapter = false)
data class StoreMessagingHistoryEnvelopeRequest(
    @Json(name = "target_device_id") val targetDeviceId: String,
    @Json(name = "target_enrollment_epoch") val targetEnrollmentEpoch: Long,
    @Json(name = "transfer_client_message_id") val transferClientMessageId: String,
    @Json(name = "roster_revision") val rosterRevision: String,
    @Json(name = "envelope_type") val envelopeType: String,
    val ciphertext: String,
) {
    init {
        requireCanonicalMessagingUuid(targetDeviceId, "history target device ID")
        require(targetEnrollmentEpoch > 0) { "Invalid history target enrollment epoch" }
        requireCanonicalMessagingUuid(transferClientMessageId, "history transfer client message ID")
        require(SECURE_MESSAGING_ROSTER_REVISION.matches(rosterRevision)) {
            "Invalid history transfer roster revision"
        }
        require(envelopeType in SECURE_MESSAGE_ENVELOPE_TYPES) {
            "Kit Pay may send only v2 history envelopes"
        }
        requireCanonicalCiphertext(ciphertext)
    }
}

@JsonClass(generateAdapter = false)
data class MessagingHistoryEnvelopeResultDto(
    @Json(name = "message_id") val messageId: String? = null,
    @Json(name = "target_device_id") val targetDeviceId: String? = null,
    @Json(name = "target_enrollment_epoch") val targetEnrollmentEpoch: Long? = null,
    @Json(name = "transfer_client_message_id") val transferClientMessageId: String? = null,
    val created: Boolean? = null,
)

@JsonClass(generateAdapter = false)
data class AcknowledgeMessageDeliveryRequest(
    @Json(name = "message_ids") val messageIds: List<String>,
) {
    init {
        require(messageIds.size in 1..MAX_DELIVERY_ACKNOWLEDGEMENT_BATCH) {
            "A delivery acknowledgement requires 1 to $MAX_DELIVERY_ACKNOWLEDGEMENT_BATCH message IDs"
        }
        require(messageIds.distinct().size == messageIds.size) {
            "A delivery acknowledgement cannot contain duplicate message IDs"
        }
        messageIds.forEach {
            requireCanonicalMessagingUuid(it, "delivery acknowledgement message ID")
        }
    }
}

@JsonClass(generateAdapter = false)
data class MessageDeliveryReceiptDto(
    @Json(name = "message_id") val messageId: String? = null,
    @Json(name = "delivered_to_device_at") val deliveredToDeviceAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessageDeliveryAcknowledgementDto(
    @Json(name = "delivery_state") val deliveryState: String? = null,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "acknowledged_count") val acknowledgedCount: Int? = null,
    @Json(name = "newly_acknowledged_count") val newlyAcknowledgedCount: Int? = null,
    val items: List<MessageDeliveryReceiptDto?>? = null,
)

@JsonClass(generateAdapter = false)
data class MarkMessagingConversationReadRequest(
    @Json(name = "message_id") val messageId: String,
)

@JsonClass(generateAdapter = false)
data class MessagingReadReceiptDto(
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "last_read_message_id") val lastReadMessageId: String? = null,
    @Json(name = "read_at") val readAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingMessageInfoRecipientDto(
    @Json(name = "user_id") val userId: String? = null,
    val name: String? = null,
    @Json(name = "delivered_at") val deliveredAt: String? = null,
    @Json(name = "read_at") val readAt: String? = null,
)

/** Sent, delivered and read moments for one message, answered only to the person who sent it. */
@JsonClass(generateAdapter = false)
data class MessagingMessageInfoDto(
    @Json(name = "message_id") val messageId: String? = null,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "sent_at") val sentAt: String? = null,
    val recipients: List<MessagingMessageInfoRecipientDto?>? = null,
)

@JsonClass(generateAdapter = false)
data class MessagingAttachmentUploadDto(
    @Json(name = "storage_key") val storageKey: String? = null,
    @Json(name = "byte_size") val byteSize: Long? = null,
    @Json(name = "ciphertext_sha256") val ciphertextSha256: String? = null,
)

const val SECURE_MESSAGING_PROTOCOL_VERSION = "v2"
const val DIRECT_CONVERSATION_TYPE = "direct"
const val GROUP_CONVERSATION_TYPE = "group"
const val OWNER_CONVERSATION_ROLE = "owner"
const val ADMIN_CONVERSATION_ROLE = "admin"
const val MEMBER_CONVERSATION_ROLE = "member"

/**
 * The roles this client will ever ask the server to assign.
 *
 * `moderator` is a server-side channel role with no meaning in a group, so it is recognised
 * when read back but never sent.
 */
val ASSIGNABLE_CONVERSATION_ROLES: Set<String> = setOf(
    OWNER_CONVERSATION_ROLE,
    ADMIN_CONVERSATION_ROLE,
    MEMBER_CONVERSATION_ROLE,
)

/**
 * The group member ceiling, mirroring `messaging.maximum_group_members` on the server.
 *
 * Fan-out is pairwise Signal — one ciphertext per recipient *device* — so a group of M
 * members with D devices each costs M*D envelopes in a single request. 32 * 3 = 96 fits both
 * the 100-device roster cap and the 99-envelope request cap, so a member enrolling a third
 * device can never turn a sendable group into an unsendable one. The client enforces it so a
 * group that cannot work is refused before a participant picker lets someone build it.
 */
const val MAX_GROUP_MEMBERS = 32
const val MAX_GROUP_TITLE_LENGTH = 64
const val MAX_GROUP_TITLE_UTF8_BYTES = 120

/** Mirrors the server's ConversationDescription bounds; a paragraph, not a label. */
const val MAX_GROUP_DESCRIPTION_LENGTH = 512
const val MAX_GROUP_DESCRIPTION_UTF8_BYTES = 1_024
const val ENCRYPTED_MESSAGE_KIND = "encrypted"
const val ENCRYPTED_ATTACHMENT_MESSAGE_KIND = "encrypted_attachment"
const val ENCRYPTED_REACTION_MESSAGE_KIND = "encrypted_reaction"
const val ENCRYPTED_EDIT_MESSAGE_KIND = "encrypted_edit"
val SECURE_MESSAGE_KINDS: Set<String> = setOf(
    ENCRYPTED_MESSAGE_KIND,
    ENCRYPTED_ATTACHMENT_MESSAGE_KIND,
    ENCRYPTED_REACTION_MESSAGE_KIND,
    ENCRYPTED_EDIT_MESSAGE_KIND,
)
const val MESSAGING_GROUPS_FEATURE = "messaging_groups"
const val MESSAGING_REACTIONS_FEATURE = "messaging_reactions_e2ee_v1"
const val MESSAGING_MESSAGE_EDITS_FEATURE = "messaging_message_edits_v1"
const val MESSAGING_MEDIA_MESSAGE_V2_FEATURE = "messaging_media_message_v2"
const val MESSAGING_GROUPS_DEVICE_CAPABILITY = "messaging_groups_v1"
const val MESSAGING_REACTIONS_DEVICE_CAPABILITY = "messaging_reactions_e2ee_v1"
const val MESSAGING_MESSAGE_EDITS_DEVICE_CAPABILITY = "messaging_message_edits_v1"
const val MESSAGING_MEDIA_MESSAGE_V2_DEVICE_CAPABILITY = "messaging_media_message_v2"
const val MAX_DELIVERY_ACKNOWLEDGEMENT_BATCH = 100
const val MAX_SECURE_MESSAGE_RECIPIENT_DEVICES = 99
const val MAX_SECURE_MESSAGE_ATTACHMENTS = 20

val SECURE_MESSAGE_ATTACHMENT_SHA256 = Regex("^[a-fA-F0-9]{64}$")

val SECURE_MESSAGE_ENVELOPE_TYPES: Set<String> = setOf(
    "signal-prekey-v2",
    "signal-message-v2",
)

val SECURE_MESSAGING_ROSTER_REVISION = Regex("^v1:sha256:[a-f0-9]{64}$")

private val CANONICAL_MESSAGING_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Trims the exact Unicode White_Space edge set used by iOS and the backend.
 *
 * Kotlin's default [String.trim] omits U+0085 NEXT LINE, which made Android send titles the
 * server normalized differently. Keep this policy explicit so request, response and UI bounds
 * cannot drift apart again.
 */
fun normalizeMessagingGroupTitle(value: String): String {
    var start = 0
    var end = value.length
    while (start < end) {
        val codePoint = value.codePointAt(start)
        if (!isMessagingGroupTitleEdgeWhitespace(codePoint)) break
        start += Character.charCount(codePoint)
    }
    while (end > start) {
        val codePoint = value.codePointBefore(end)
        if (!isMessagingGroupTitleEdgeWhitespace(codePoint)) break
        end -= Character.charCount(codePoint)
    }
    return if (start == 0 && end == value.length) value else value.substring(start, end)
}

fun isValidMessagingGroupTitle(value: String): Boolean {
    val normalized = normalizeMessagingGroupTitle(value)
    return normalized.isNotEmpty() &&
        normalized.hasWellFormedUnicodeScalars() &&
        normalized.codePointCount(0, normalized.length) <= MAX_GROUP_TITLE_LENGTH &&
        normalized.toByteArray(StandardCharsets.UTF_8).size <= MAX_GROUP_TITLE_UTF8_BYTES &&
        '\u0000' !in normalized
}

private fun isMessagingGroupTitleEdgeWhitespace(codePoint: Int): Boolean =
    codePoint in 0x0009..0x000D || codePoint == 0x0085 || Character.isSpaceChar(codePoint)

fun truncateMessagingGroupTitle(value: String): String {
    val result = StringBuilder()
    var index = 0
    var scalars = 0
    var bytes = 0
    while (index < value.length && scalars < MAX_GROUP_TITLE_LENGTH) {
        if (value[index].isSurrogate() &&
            (index + 1 >= value.length || !Character.isSurrogatePair(value[index], value[index + 1]))
        ) {
            break
        }
        val codePoint = value.codePointAt(index)
        val token = String(Character.toChars(codePoint))
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8).size
        if (bytes + tokenBytes > MAX_GROUP_TITLE_UTF8_BYTES) break
        result.append(token)
        index += Character.charCount(codePoint)
        scalars++
        bytes += tokenBytes
    }
    return result.toString()
}

/**
 * Canonicalizes a group description exactly the way the server does.
 *
 * Control characters and the bidirectional-override set are stripped wherever they appear —
 * a description is a paragraph, so U+000A alone survives inside it — and then the same Unicode
 * White_Space edge set the title uses is trimmed away. A value that normalizes to nothing is
 * an absent description.
 */
fun normalizeMessagingGroupDescription(value: String): String {
    val kept = StringBuilder(value.length)
    for (character in value) {
        // Surrogate halves are never in the stripped set; scalar well-formedness is judged
        // separately by the validity check.
        if (character.isSurrogate() ||
            !isDisallowedMessagingGroupDescriptionCodePoint(character.code)
        ) {
            kept.append(character)
        }
    }
    return normalizeMessagingGroupTitle(kept.toString())
}

private fun isDisallowedMessagingGroupDescriptionCodePoint(codePoint: Int): Boolean =
    codePoint in 0x0000..0x0009 || codePoint in 0x000B..0x001F || codePoint == 0x007F ||
        codePoint == 0x200E || codePoint == 0x200F ||
        codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069

fun isValidMessagingGroupDescription(value: String): Boolean {
    val normalized = normalizeMessagingGroupDescription(value)
    return normalized.isNotEmpty() &&
        normalized.hasWellFormedUnicodeScalars() &&
        normalized.codePointCount(0, normalized.length) <= MAX_GROUP_DESCRIPTION_LENGTH &&
        normalized.toByteArray(StandardCharsets.UTF_8).size <= MAX_GROUP_DESCRIPTION_UTF8_BYTES
}

/** Bounds live editor input the way [truncateMessagingGroupTitle] bounds the title field. */
fun truncateMessagingGroupDescription(value: String): String {
    val result = StringBuilder()
    var index = 0
    var scalars = 0
    var bytes = 0
    while (index < value.length && scalars < MAX_GROUP_DESCRIPTION_LENGTH) {
        if (value[index].isSurrogate() &&
            (index + 1 >= value.length || !Character.isSurrogatePair(value[index], value[index + 1]))
        ) {
            break
        }
        val codePoint = value.codePointAt(index)
        val token = String(Character.toChars(codePoint))
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8).size
        if (bytes + tokenBytes > MAX_GROUP_DESCRIPTION_UTF8_BYTES) break
        result.append(token)
        index += Character.charCount(codePoint)
        scalars++
        bytes += tokenBytes
    }
    return result.toString()
}

private fun String.hasWellFormedUnicodeScalars(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(character) -> return false
            else -> index++
        }
    }
    return true
}

private fun requireCanonicalMessagingUuid(value: String, field: String) {
    require(CANONICAL_MESSAGING_UUID.matches(value)) { "Invalid $field" }
}

private fun requireCanonicalCiphertext(value: String) {
    val bytes = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid encrypted-envelope ciphertext")
    }
    require(bytes.size in 1..MAX_SECURE_MESSAGE_CIPHERTEXT_BYTES) {
        "Invalid encrypted-envelope ciphertext length"
    }
    require(Base64.getEncoder().encodeToString(bytes) == value) {
        "Encrypted-envelope ciphertext must use canonical Base64"
    }
}

private const val MAX_SECURE_MESSAGE_CIPHERTEXT_BYTES = 1_500_000
