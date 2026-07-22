package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.AcknowledgeMessageDeliveryRequest
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.ConsumeMessagingKeyBundlesRequest
import com.kit.wallet.data.remote.CreateDirectMessagingConversationRequest
import com.kit.wallet.data.remote.ENCRYPTED_ATTACHMENT_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedAttachmentRequest
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessageDeliveryAcknowledgementDto
import com.kit.wallet.data.remote.MarkMessagingConversationReadRequest
import com.kit.wallet.data.remote.MessagingConversationListDto
import com.kit.wallet.data.remote.MessagingKeyStatusDto
import com.kit.wallet.data.remote.MessagingReadReceiptDto
import com.kit.wallet.data.remote.SECURE_MESSAGING_ROSTER_REVISION
import com.kit.wallet.data.remote.SECURE_MESSAGING_PROTOCOL_VERSION
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.remote.SecureMessagingTransportValidator
import com.kit.wallet.data.remote.SecureMessagingWireValidationException
import com.kit.wallet.data.remote.SecureMessagingWireValidator
import com.kit.wallet.data.remote.ValidatedDirectConversation
import com.kit.wallet.data.remote.ValidatedMessageDeliveryAcknowledgement
import com.kit.wallet.data.remote.ValidatedMessagingDeviceRoster
import com.kit.wallet.data.remote.ValidatedMessagingReadReceipt
import com.kit.wallet.data.remote.ValidatedMessagingSyncEvent
import com.kit.wallet.data.remote.ValidatedMessagingSyncPage
import com.kit.wallet.data.remote.ValidatedOutboundEncryptedMessage
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

internal data class SecureMessagingRemoteContext(
    val currentUserId: String,
    val currentDeviceId: String,
)

class SecureMessagingProtocolUnavailableException(message: String) : IllegalStateException(message)

/**
 * Authenticated, ciphertext-only transport for the reviewed v2 contract.
 *
 * The only entry point is [openSession]. The returned opaque [Session] proves that the exact
 * protocol advertisement was checked for the current authentication activation. Every operation
 * fences immediately before and after its network suspension, so an old handle cannot issue a
 * request or return data after logout, account change, device change or activation replacement.
 */
@Singleton
class RemoteSecureMessagingTransport @Inject internal constructor(
    private val api: KitWalletApi,
    private val messagingApi: SecureMessagingWireApi,
    private val apiCalls: ApiCallExecutor,
) {
    class Session internal constructor(
        private val owner: RemoteSecureMessagingTransport,
        private val issuanceIdentity: Any,
        private val lifecycle: SecureMessagingLifecycleGuard,
        private val fence: SecureMessagingSessionFence,
        private val activation: SecureMessagingActivationCapability,
        private val context: SecureMessagingRemoteContext,
    ) {
        class DirectConversation internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
            val conversationId: String,
            val peerUserId: String,
            val peerName: String?,
            val currentUserRole: String,
        )

        /** Opaque authority for one exact, server-validated device roster. */
        class AuthoritativeRoster internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
        )

        /** Opaque, single-use position in this session's validated sync stream. */
        class SyncCheckpoint internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
        )

        sealed class SyncEvent protected constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
            val eventId: Long,
            val conversationId: String,
            val occurredAt: Instant,
        )

        /** Opaque incoming ciphertext. Only [decryptionRequest] can unwrap it. */
        class IncomingEnvelope internal constructor(
            owner: Session,
            issuanceIdentity: Any,
            eventId: Long,
            conversationId: String,
            occurredAt: Instant,
            val messageId: String,
            val clientMessageId: String,
            val senderUserId: String,
            val senderDeviceId: String,
            val sentAt: Instant,
            val replyToMessageId: String?,
            val rosterRevision: String = "",
            internal val kind: String,
            attachments: List<EncryptedAttachmentRequest>,
        ) : SyncEvent(owner, issuanceIdentity, eventId, conversationId, occurredAt) {
            internal val attachments = attachments.toList()
        }

        class OutboundEvent internal constructor(
            owner: Session,
            issuanceIdentity: Any,
            eventId: Long,
            conversationId: String,
            occurredAt: Instant,
            val messageId: String,
            val clientMessageId: String,
            val rosterRevision: String,
            val sentAt: Instant,
        ) : SyncEvent(owner, issuanceIdentity, eventId, conversationId, occurredAt)

        class DeliveryReceiptEvent internal constructor(
            owner: Session,
            issuanceIdentity: Any,
            eventId: Long,
            conversationId: String,
            occurredAt: Instant,
            val messageId: String,
            val deliveredAt: Instant,
        ) : SyncEvent(owner, issuanceIdentity, eventId, conversationId, occurredAt)

        class ReadReceiptEvent internal constructor(
            owner: Session,
            issuanceIdentity: Any,
            eventId: Long,
            conversationId: String,
            occurredAt: Instant,
            val readerUserId: String,
            val lastReadMessageId: String,
            val readAt: Instant,
        ) : SyncEvent(owner, issuanceIdentity, eventId, conversationId, occurredAt)

        class RosterRefreshEvent internal constructor(
            owner: Session,
            issuanceIdentity: Any,
            eventId: Long,
            conversationId: String,
            occurredAt: Instant,
            val eventType: String,
            val affectedUserId: String,
            val affectedDeviceId: String?,
            val affectedSignalDeviceId: Int?,
            val affectedRegistrationId: Int?,
            val previousRegistrationId: Int?,
            val protocolVersion: String?,
            val previousProtocolVersion: String?,
            val bundleVersion: Int?,
            val identityKeySha256: String?,
            val previousIdentityKeySha256: String?,
            val revokedDeviceCount: Int?,
            val transitionedAt: Instant,
            val transitionHash: String,
        ) : SyncEvent(owner, issuanceIdentity, eventId, conversationId, occurredAt)

        class MetadataEvent internal constructor(
            owner: Session,
            issuanceIdentity: Any,
            eventId: Long,
            conversationId: String,
            occurredAt: Instant,
            val type: String,
        ) : SyncEvent(owner, issuanceIdentity, eventId, conversationId, occurredAt)

        class SyncBatch internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
            events: List<SyncEvent>,
            val hasMore: Boolean,
        ) {
            private val immutableEvents = events.toList()

            fun events(): List<SyncEvent> = owner.snapshotEvents(this, immutableEvents)

            val size: Int get() = immutableEvents.size
        }

        /** Capability for acknowledging one durably decrypted incoming envelope. */
        class DeliveryToken internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
        )

        class KeyStatus internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
            val enrolled: Boolean,
            val signalDeviceId: Int?,
            val registrationId: Int?,
            val identityKeySha256: String?,
            val signedPrekeyId: Int?,
            val pqLastResortPrekeyId: Int?,
            val availableOneTimePrekeys: Int,
            val availableEcOneTimePrekeys: Int,
            val availablePqOneTimePrekeys: Int,
            val replenishAt: Int,
            val needsReplenishment: Boolean,
        )

        class OutboundReceipt internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
            val messageId: String,
            val clientMessageId: String,
            val conversationId: String,
            val rosterRevision: String,
            val senderBundleVersion: Int,
            val sentAt: Instant,
        )

        class DeliveryAcknowledgement internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
            val acknowledgedCount: Int,
            val newlyAcknowledgedCount: Int,
        )

        class ReadReceipt internal constructor(
            private val owner: Session,
            internal val issuanceIdentity: Any,
            val conversationId: String,
            val lastReadMessageId: String,
            val readAt: Instant,
        )

        private data class IssuedConversation(
            val identity: Any,
            val validated: ValidatedDirectConversation,
        )

        private data class IssuedRoster(
            val identity: Any,
            val conversationIdentity: Any,
            val validated: ValidatedMessagingDeviceRoster,
            val use: RosterUse,
        )

        private data class IssuedPlan(
            val rosterIdentity: Any,
            val conversationIdentity: Any,
            val outboundAllowed: Boolean,
        )

        private enum class RosterUse { CURRENT_OUTBOUND, HISTORICAL_INBOUND }

        private enum class CheckpointState {
            AVAILABLE,
            IN_FLIGHT,
            AWAITING_CONFIRMATION,
            CONSUMED,
        }

        private data class IssuedCheckpoint(
            val identity: Any,
            val cursor: String?,
            val previousEventId: Long?,
            var state: CheckpointState = CheckpointState.AVAILABLE,
            var batchIdentity: Any? = null,
        )

        private enum class BatchState { OPEN, CONFIRMED }

        private data class IssuedBatch(
            val identity: Any,
            val sourceCheckpoint: IssuedCheckpoint,
            val nextCursor: String,
            val lastEventId: Long?,
            val eventIdentities: List<Any>,
            var state: BatchState = BatchState.OPEN,
        )

        private data class IssuedEvent(
            val identity: Any,
            val batchIdentity: Any,
            val validated: ValidatedMessagingSyncEvent,
            var decryptionRequestIssued: Boolean = false,
        )

        private enum class DeliveryState { AVAILABLE, IN_FLIGHT, ACKNOWLEDGED }

        private data class IssuedDeliveryToken(
            val identity: Any,
            val incomingIdentity: Any,
            val messageId: String,
            var state: DeliveryState = DeliveryState.AVAILABLE,
        )

        private data class CheckpointRequest(
            val cursor: String?,
            val previousEventId: Long?,
        )

        private val issuanceLock = Any()
        private val issuedConversations = WeakHashMap<DirectConversation, IssuedConversation>()
        private val issuedRosters = WeakHashMap<AuthoritativeRoster, IssuedRoster>()
        private val issuedPlans = WeakHashMap<SecureMessagingEncryptionPlan, IssuedPlan>()
        private val issuedCheckpoints = WeakHashMap<SyncCheckpoint, IssuedCheckpoint>()
        private val issuedBatches = WeakHashMap<SyncBatch, IssuedBatch>()
        private val issuedEvents = WeakHashMap<SyncEvent, IssuedEvent>()
        private val issuedDeliveryTokens = WeakHashMap<DeliveryToken, IssuedDeliveryToken>()
        private val tokenizedIncomingIdentities = mutableSetOf<Any>()
        private var initialCheckpointIssued = false

        val binding: SecureMessagingSessionBinding
            get() = fence.binding

        internal fun quarantine(error: SecureMessagingCryptographicFailureException) {
            lifecycle.quarantine(fence, error.quarantineReason)
        }

        suspend fun keyStatus(): KeyStatus {
            val response = owner.fencedSessionCall(this, issuanceIdentity, lifecycle, fence) {
                messagingKeyStatus()
            }
            return issueKeyStatus(
                SecureMessagingWireValidator.validateKeyStatus(
                    response,
                    context.currentDeviceId,
                ),
            )
        }

        suspend fun publishKeyBundle(
            publication: SecureMessagingKeyPublication,
        ): KeyStatus {
            val issued = SecureMessagingCryptoWireMapper.requirePublication(publication)
            val current = SecureMessagingActivationProvenance.requireCurrent(activation)
            check(current.isSameActivation(issued.provenance)) {
                "Messaging key publication belongs to another authentication activation"
            }
            val request = issued.request()
            require(request.protocolVersion == SECURE_MESSAGING_PROTOCOL_VERSION)
            val response = owner.fencedSessionCall(this, issuanceIdentity, lifecycle, fence) {
                publishMessagingKeyBundle(request)
            }
            return issueKeyStatus(
                SecureMessagingWireValidator.validateKeyStatus(
                    response,
                    context.currentDeviceId,
                ),
            )
        }

        suspend fun directConversations(): List<DirectConversation> {
            val response = owner.fencedSessionCall(
                this,
                issuanceIdentity,
                lifecycle,
                fence,
            ) {
                messagingConversations()
            }
            return SecureMessagingTransportValidator.validateDirectConversations(
                response,
                context.currentUserId,
            ).map(::issueConversation)
        }

        suspend fun createDirectConversation(
            peerUserId: String,
        ): DirectConversation {
            requireUuid(peerUserId, "direct-message peer user ID")
            require(peerUserId != context.currentUserId) {
                "A direct conversation requires another user"
            }
            val created = owner.fencedSessionCall(
                this,
                issuanceIdentity,
                lifecycle,
                fence,
                readyRequired = true,
            ) {
                createDirectMessagingConversation(
                    CreateDirectMessagingConversationRequest(listOf(peerUserId)),
                )
            }
            val validated = SecureMessagingTransportValidator.validateDirectConversations(
                MessagingConversationListDto(listOf(created)),
                context.currentUserId,
            ).single()
            check(validated.peerUserId == peerUserId) {
                "The server created a direct conversation with another peer"
            }
            return issueConversation(validated)
        }

        suspend fun roster(
            conversation: DirectConversation,
        ): AuthoritativeRoster {
            val validatedConversation = requireConversation(conversation)
            val conversationId = validatedConversation.conversationId
            requireUuid(conversationId, "conversation ID")
            val response = owner.fencedSessionCall(this, issuanceIdentity, lifecycle, fence) {
                messagingDeviceRoster(conversationId)
            }
            return issueRoster(
                conversation = conversation,
                validated = SecureMessagingWireValidator.validateAuthoritativeRoster(
                    roster = response,
                    expectedConversationId = conversationId,
                    currentDeviceId = context.currentDeviceId,
                    currentUserId = context.currentUserId,
                    expectedMemberUserIds = setOf(
                        context.currentUserId,
                        validatedConversation.peerUserId,
                    ),
                ),
                use = RosterUse.CURRENT_OUTBOUND,
            )
        }

        /** Retrieves the immutable roster that authenticated an older offline envelope. */
        suspend fun historicalRoster(
            conversation: DirectConversation,
            rosterRevision: String,
        ): AuthoritativeRoster {
            val validatedConversation = requireConversation(conversation)
            val conversationId = validatedConversation.conversationId
            requireUuid(conversationId, "conversation ID")
            require(SECURE_MESSAGING_ROSTER_REVISION.matches(rosterRevision)) {
                "Invalid historical roster revision"
            }
            val response = owner.fencedSessionCall(this, issuanceIdentity, lifecycle, fence) {
                historicalMessagingDeviceRoster(conversationId, rosterRevision)
            }
            val validated = SecureMessagingWireValidator.validateAuthoritativeRoster(
                roster = response,
                expectedConversationId = conversationId,
                currentDeviceId = context.currentDeviceId,
                currentUserId = context.currentUserId,
                expectedMemberUserIds = setOf(
                    context.currentUserId,
                    validatedConversation.peerUserId,
                ),
            )
            check(validated.rosterRevision == rosterRevision) {
                "Historical roster response changed the requested revision"
            }
            return issueRoster(
                conversation = conversation,
                validated = validated,
                use = RosterUse.HISTORICAL_INBOUND,
            )
        }

        suspend fun consumeKeyBundles(
            conversation: DirectConversation,
            roster: AuthoritativeRoster,
            plan: SecureMessagingEncryptionPlan,
            deviceIds: Set<String>,
        ): SecureMessagingSessionEstablishmentRequest {
            val validatedConversation = requireConversation(conversation)
            val issuedRoster = requireRoster(roster, conversation)
            check(issuedRoster.use == RosterUse.CURRENT_OUTBOUND) {
                "Historical rosters cannot consume outbound key bundles"
            }
            requirePlan(plan, roster, conversation, outboundRequired = true)
            val conversationId = validatedConversation.conversationId
            requireUuid(conversationId, "conversation ID")
            val targetIds = deviceIds.toSet()
            require(targetIds.isNotEmpty()) {
                "At least one missing recipient session is required"
            }
            require(targetIds.size <= MAX_RECIPIENT_DEVICES) {
                "Too many missing recipient sessions"
            }
            targetIds.forEach { requireUuid(it, "missing recipient device ID") }
            val expectedMembers = setOf(context.currentUserId, validatedConversation.peerUserId)
            val issuedPlan = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
            check(issuedPlan.conversationId == conversationId) {
                "Encryption plan belongs to another conversation"
            }
            check(issuedPlan.memberUserIds() == expectedMembers) {
                "Encryption plan belongs to another direct membership"
            }
            SecureMessagingWireValidator.requireKeyBundleTargets(
                roster = issuedRoster.validated,
                expectedConversationId = conversationId,
                currentDeviceId = context.currentDeviceId,
                currentUserId = context.currentUserId,
                expectedMemberUserIds = expectedMembers,
                requestedDeviceIds = targetIds,
            )
            val sortedIds = targetIds.sorted()
            val response = owner.fencedSessionCall(this, issuanceIdentity, lifecycle, fence) {
                consumeMessagingKeyBundles(
                    conversationId,
                    ConsumeMessagingKeyBundlesRequest(sortedIds),
                )
            }
            val validatedClaims = SecureMessagingWireValidator.validateConsumedKeyBundles(
                response = response,
                authoritativeRoster = issuedRoster.validated,
                expectedConversationId = conversationId,
                expectedDeviceIds = targetIds,
                currentDeviceId = context.currentDeviceId,
                currentUserId = context.currentUserId,
                expectedMemberUserIds = expectedMembers,
            )
            return SecureMessagingCryptoWireMapper.sessionEstablishment(
                validatedClaims,
                plan,
                activation,
            )
        }

        fun encryptionPlan(
            conversation: DirectConversation,
            roster: AuthoritativeRoster,
        ): SecureMessagingEncryptionPlan {
            val validatedConversation = requireConversation(conversation)
            val issuedRoster = requireRoster(roster, conversation)
            check(issuedRoster.use == RosterUse.CURRENT_OUTBOUND) {
                "Historical rosters cannot authorize outbound encryption"
            }
            return issuePlan(conversation, roster, validatedConversation, issuedRoster, true)
        }

        /** Issues a receive-only plan; historical rosters can never authorize an outbound send. */
        fun decryptionPlan(
            conversation: DirectConversation,
            roster: AuthoritativeRoster,
        ): SecureMessagingEncryptionPlan {
            val validatedConversation = requireConversation(conversation)
            val issuedRoster = requireRoster(roster, conversation)
            return issuePlan(conversation, roster, validatedConversation, issuedRoster, false)
        }

        private fun issuePlan(
            conversation: DirectConversation,
            roster: AuthoritativeRoster,
            validatedConversation: ValidatedDirectConversation,
            issuedRoster: IssuedRoster,
            outboundAllowed: Boolean,
        ): SecureMessagingEncryptionPlan {
            SecureMessagingWireValidator.requireValidatedRoster(issuedRoster.validated).also { validatedRoster ->
                check(validatedRoster.conversationId == validatedConversation.conversationId) {
                    "Authoritative roster belongs to another conversation"
                }
                check(
                    validatedRoster.memberUserIds() ==
                        setOf(context.currentUserId, validatedConversation.peerUserId),
                ) { "Authoritative roster belongs to another direct membership" }
            }
            val plan = SecureMessagingCryptoWireMapper.encryptionPlan(
                issuedRoster.validated,
                activation,
            )
            synchronized(issuanceLock) {
                issuedPlans[plan] = IssuedPlan(
                    rosterIdentity = roster.issuanceIdentity,
                    conversationIdentity = conversation.issuanceIdentity,
                    outboundAllowed = outboundAllowed,
                )
            }
            return plan
        }

        suspend fun openCryptoTransaction(
            engine: SecureMessagingCryptoEngine,
        ): SecureMessagingCryptoTransaction {
            lifecycle.assertCurrent(activation)
            return engine.openTransaction(activation).also { lifecycle.assertCurrent(activation) }
        }

        internal suspend fun retireRemoteDevices(
            engine: SecureMessagingCryptoEngine,
            affectedUserId: String,
            affectedServerDeviceId: String?,
        ) {
            lifecycle.assertCurrent(activation)
            engine.retireRemoteDevices(
                activation = activation,
                affectedUserId = affectedUserId,
                affectedServerDeviceId = affectedServerDeviceId,
            )
            lifecycle.assertCurrent(activation)
        }

        /** Reads only public enrollment metadata under this session's activation capability. */
        internal suspend fun localEnrollment(
            engine: LibSignalSecureMessagingCryptoEngine,
        ): LibSignalLocalEnrollment? {
            lifecycle.assertCurrent(activation)
            return engine.localEnrollment(activation).also { lifecycle.assertCurrent(activation) }
        }

        fun decryptionRequest(
            envelope: IncomingEnvelope,
            roster: AuthoritativeRoster,
            plan: SecureMessagingEncryptionPlan,
        ): SecureMessagingDecryptionRequest {
            lifecycle.assertCurrent(activation)
            val incoming = requireIncomingEnvelope(envelope)
            val issuedRoster = requireRoster(roster)
            requirePlan(plan, roster, outboundRequired = false)
            check(incoming.conversationId == issuedRoster.validated.conversationId) {
                "Incoming envelope belongs to another authoritative roster"
            }
            check(incoming.message.rosterRevision == issuedRoster.validated.rosterRevision) {
                "Incoming envelope belongs to another roster revision"
            }
            val request = SecureMessagingCryptoWireMapper.decryptionRequest(
                incoming.message,
                plan,
                activation,
            )
            synchronized(issuanceLock) {
                val issued = issuedEvents[envelope]
                    ?: error("Incoming envelope was not issued by this secure-messaging session")
                check(issued.identity === envelope.issuanceIdentity) {
                    "Incoming envelope was not issued by this secure-messaging session"
                }
                check(!issued.decryptionRequestIssued) {
                    "A decryption request was already issued for this incoming envelope"
                }
                issued.decryptionRequestIssued = true
            }
            assertSyncAllowed()
            return request
        }

        suspend fun send(
            conversation: DirectConversation,
            encryptedSend: SecureMessagingEncryptedSend,
            attachments: List<EncryptedAttachmentRequest>,
        ): OutboundReceipt {
            val validatedConversation = requireConversation(conversation)
            val conversationId = validatedConversation.conversationId
            requireUuid(conversationId, "conversation ID")
            val send = SecureMessagingCryptoWireMapper.requireEncryptedSend(encryptedSend)
            val current = SecureMessagingActivationProvenance.requireCurrent(
                activation,
                readyRequired = true,
            )
            check(current.isSameActivation(send.provenance)) {
                "Encrypted send command belongs to another authentication activation"
            }
            check(send.conversationId == conversationId) {
                "Encrypted send command belongs to another conversation"
            }
            check(send.currentUserId == context.currentUserId) {
                "Encrypted send command belongs to another authenticated user"
            }
            check(send.currentDeviceId == context.currentDeviceId) {
                "Encrypted send command belongs to another authenticated device"
            }
            check(
                send.memberUserIds() == setOf(context.currentUserId, validatedConversation.peerUserId),
            ) { "Encrypted send command belongs to another direct membership" }
            // The committed fanout stays byte-identical; attachment metadata only decorates the
            // wire request. The data-class initializer re-validates the kind/metadata pairing.
            val request = if (attachments.isEmpty()) {
                send.request()
            } else {
                send.request().copy(
                    kind = ENCRYPTED_ATTACHMENT_MESSAGE_KIND,
                    attachments = attachments.toList(),
                )
            }
            val response = owner.fencedSessionCall(
                this,
                issuanceIdentity,
                lifecycle,
                fence,
                readyRequired = true,
            ) {
                sendEncryptedMessage(conversationId, request)
            }
            return issueOutboundReceipt(
                SecureMessagingTransportValidator.validateOutboundSendResponse(
                    response = response,
                    expectedConversationId = conversationId,
                    expectedClientMessageId = request.clientMessageId,
                    expectedCurrentUserId = context.currentUserId,
                    expectedCurrentDeviceId = context.currentDeviceId,
                    expectedRosterRevision = request.rosterRevision,
                ),
            )
        }

        /** Uploaded ciphertext identity returned by the blob store. */
        data class UploadedAttachment(
            val storageKey: String,
            val byteSize: Long,
            val ciphertextSha256: String,
        )

        /**
         * Uploads opaque attachment ciphertext and returns its server identity. The response is
         * checked against the exact bytes sent so a corrupted store can never be referenced.
         */
        suspend fun uploadAttachment(mediaType: String, ciphertext: ByteArray): UploadedAttachment {
            require(mediaType.isNotBlank() && mediaType.length <= 160) { "Invalid media type" }
            require(ciphertext.isNotEmpty()) { "Attachment ciphertext is empty" }
            val expectedSha256 = sha256Hex(ciphertext)
            val response = owner.fencedSessionCall(
                this,
                issuanceIdentity,
                lifecycle,
                fence,
                readyRequired = true,
            ) {
                uploadMessagingAttachment(
                    mediaType = mediaType
                        .toRequestBody("text/plain".toMediaType()),
                    ciphertext = MultipartBody.Part.createFormData(
                        "ciphertext",
                        "blob.bin",
                        ciphertext.toRequestBody("application/octet-stream".toMediaType()),
                    ),
                )
            }
            val storageKey = response.storageKey
            check(!storageKey.isNullOrBlank() && storageKey.length <= 512) {
                "The attachment store returned an invalid storage key"
            }
            check(response.byteSize == ciphertext.size.toLong()) {
                "The attachment store recorded a different ciphertext size"
            }
            check(response.ciphertextSha256?.lowercase() == expectedSha256) {
                "The attachment store recorded a different ciphertext digest"
            }
            return UploadedAttachment(
                storageKey = storageKey,
                byteSize = ciphertext.size.toLong(),
                ciphertextSha256 = expectedSha256,
            )
        }

        /**
         * Downloads opaque attachment ciphertext. Integrity is enforced by the caller against the
         * end-to-end descriptor's digest, never against anything the server asserts.
         */
        suspend fun downloadAttachment(storageKey: String, maximumBytes: Long): ByteArray {
            require(storageKey.isNotBlank() && storageKey.length <= 512) { "Invalid storage key" }
            require(maximumBytes in 1..MAX_ATTACHMENT_DOWNLOAD_BYTES) { "Invalid download bound" }
            val body = owner.rawFencedSessionCall(this, issuanceIdentity, lifecycle, fence) {
                downloadMessagingAttachment(storageKey)
            }
            var bytes: ByteArray? = null
            try {
                body.use { responseBody ->
                    val length = responseBody.contentLength()
                    check(length == -1L || length <= maximumBytes) {
                        "The encrypted attachment exceeds its authenticated size"
                    }
                    bytes = responseBody.byteStream().use { input ->
                        input.readBoundedAttachmentCiphertext(maximumBytes)
                    }
                }
                // Retrofit's response suspension ends before a streaming body is consumed. Fence
                // the complete read so bytes from an obsolete activation never reach decryption.
                lifecycle.assertCurrent(activation)
                return checkNotNull(bytes)
            } catch (error: Throwable) {
                bytes?.fill(0)
                throw error
            }
        }

        suspend fun markConversationRead(
            conversation: DirectConversation,
            messageId: String,
        ): ReadReceipt {
            val validatedConversation = requireConversation(conversation)
            requireUuid(messageId, "last-read message ID")
            val response: MessagingReadReceiptDto = owner.fencedSessionCall(
                this,
                issuanceIdentity,
                lifecycle,
                fence,
                readyRequired = true,
            ) {
                markMessagingConversationRead(
                    validatedConversation.conversationId,
                    MarkMessagingConversationReadRequest(messageId),
                )
            }
            val validated = SecureMessagingTransportValidator.validateReadReceipt(
                response = response,
                expectedConversationId = validatedConversation.conversationId,
                expectedCurrentUserId = context.currentUserId,
                requestedMessageId = messageId,
            )
            lifecycle.assertCurrent(activation)
            return issueReadReceipt(validated)
        }

        /**
         * Creates the only root checkpoint for this login activation. A restored position can
         * originate only from the encrypted cursor store; null deliberately starts at genesis.
         */
        internal fun initialSyncCheckpoint(
            restored: SecureMessagingSyncResumePosition? = null,
        ): SyncCheckpoint {
            lifecycle.assertCurrent(activation)
            val restoredSnapshot = restored?.let(::requireSecureMessagingSyncResumePosition)
            return synchronized(issuanceLock) {
                check(!initialCheckpointIssued) {
                    "The initial secure-messaging sync checkpoint was already issued"
                }
                initialCheckpointIssued = true
                issueCheckpointLocked(
                    cursor = restoredSnapshot?.first,
                    previousEventId = restoredSnapshot?.second,
                )
            }
        }

        suspend fun sync(
            checkpoint: SyncCheckpoint,
            limit: Int,
        ): SyncBatch {
            val request = beginSync(checkpoint, limit)
            return try {
                val response = owner.fencedSessionCall(
                    this,
                    issuanceIdentity,
                    lifecycle,
                    fence,
                    readyRequired = false,
                ) {
                    syncEncryptedMessages(request.cursor, limit)
                }
                val validated = SecureMessagingTransportValidator.validateSyncPage(
                    response = response,
                    currentUserId = context.currentUserId,
                    currentDeviceId = context.currentDeviceId,
                    requestedCursor = request.cursor,
                    requestedLimit = limit,
                    previousEventId = request.previousEventId,
                )
                assertSyncAllowed()
                issueSyncBatch(checkpoint, validated)
            } catch (error: SecureMessagingWireValidationException) {
                releaseFailedSync(checkpoint)
                val failure = SecureMessagingCryptographicFailureException(
                    quarantineReason = SecureMessagingQuarantineReason.MALFORMED_WIRE_DATA,
                    message = "The secure-messaging sync page failed authenticated validation",
                    cause = error,
                )
                runCatching {
                    lifecycle.quarantine(fence, failure.quarantineReason)
                }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            } catch (error: Throwable) {
                releaseFailedSync(checkpoint)
                throw error
            }
        }

        /** Returns the exact position that must be encrypted at rest before confirmation. */
        internal fun resumePositionAfter(batch: SyncBatch): SecureMessagingSyncResumePosition {
            assertSyncAllowed()
            return synchronized(issuanceLock) {
                val issuedBatch = requireBatchLocked(batch)
                check(issuedBatch.state == BatchState.OPEN) {
                    "Secure-messaging sync batch was already confirmed"
                }
                val source = issuedBatch.sourceCheckpoint
                check(
                    source.state == CheckpointState.AWAITING_CONFIRMATION &&
                        source.batchIdentity === issuedBatch.identity,
                ) { "Secure-messaging sync batch is not awaiting confirmation" }
                verifiedSecureMessagingSyncResumePosition(
                    cursor = issuedBatch.nextCursor,
                    previousEventId = issuedBatch.lastEventId,
                )
            }
        }

        /**
         * Explicitly confirms that every event in [batch] was durably processed and the exact
         * next position was persisted, then issues the next single-use in-memory checkpoint.
         */
        internal fun confirmProcessed(
            batch: SyncBatch,
            persisted: SecureMessagingSyncResumePosition,
        ): SyncCheckpoint {
            assertSyncAllowed()
            val persistedSnapshot = requireSecureMessagingSyncResumePosition(persisted)
            return synchronized(issuanceLock) {
                val issuedBatch = requireBatchLocked(batch)
                check(issuedBatch.state == BatchState.OPEN) {
                    "Secure-messaging sync batch was already confirmed"
                }
                val source = issuedBatch.sourceCheckpoint
                check(
                    source.state == CheckpointState.AWAITING_CONFIRMATION &&
                        source.batchIdentity === issuedBatch.identity,
                ) { "Secure-messaging sync batch is not awaiting confirmation" }
                check(
                    persistedSnapshot.first == issuedBatch.nextCursor &&
                        persistedSnapshot.second == issuedBatch.lastEventId,
                ) { "Persisted secure-messaging cursor does not match the processed batch" }
                issuedBatch.state = BatchState.CONFIRMED
                source.state = CheckpointState.CONSUMED
                issueCheckpointLocked(
                    cursor = issuedBatch.nextCursor,
                    previousEventId = issuedBatch.lastEventId,
                )
            }
        }

        /** Issues an acknowledgement capability only for matching durable plaintext state. */
        fun deliveryToken(
            envelope: IncomingEnvelope,
            decrypted: SecureMessagingCommittedResult.Decrypted,
        ): DeliveryToken {
            lifecycle.assertCurrent(activation)
            val incoming = requireIncomingEnvelope(envelope)
            synchronized(issuanceLock) {
                val issued = issuedEvents[envelope]
                    ?: error("Incoming envelope was not issued by this secure-messaging session")
                check(
                    issued.identity === envelope.issuanceIdentity &&
                        issued.decryptionRequestIssued,
                ) { "Incoming envelope has no matching issued decryption request" }
            }
            val committed = requireDurablyCommittedDecryption(decrypted)
            val expectedSender = SecureMessagingCryptoAddress(
                userId = incoming.message.senderUserId,
                serverDeviceId = incoming.message.senderDeviceId,
                signalDeviceId = incoming.message.senderSignalDeviceId,
            )
            check(committed.messageId == incoming.message.messageId) {
                "Durably decrypted result belongs to another incoming message"
            }
            check(committed.conversationId == incoming.message.conversationId) {
                "Durably decrypted result belongs to another conversation"
            }
            check(committed.sender == expectedSender) {
                "Durably decrypted result belongs to another sender"
            }
            return issueDeliveryToken(envelope, incoming)
        }

        /**
         * Reissues an acknowledgement capability after process death without advancing the
         * ratchet twice. The supplied projection can only be produced by the encrypted companion
         * reader and must match every authenticated envelope field exactly.
         */
        internal fun deliveryTokenFromDurableState(
            envelope: IncomingEnvelope,
            durableRecord: LibSignalCompanionRecord,
        ): DeliveryToken {
            val incoming = requireIncomingEnvelope(envelope)
            val durable = requireDurableLibSignalCompanionRecord(durableRecord)
            val expectedSender = SecureMessagingCryptoAddress(
                userId = incoming.message.senderUserId,
                serverDeviceId = incoming.message.senderDeviceId,
                signalDeviceId = incoming.message.senderSignalDeviceId,
            )
            check(durable.direction == LibSignalCompanionDirection.INBOUND) {
                "Durable projection is not an incoming secure message"
            }
            check(
                durable.messageId == incoming.message.messageId &&
                    durable.clientMessageId == incoming.message.clientMessageId &&
                    durable.conversationId == incoming.message.conversationId &&
                    durable.rosterRevision == incoming.message.rosterRevision &&
                    durable.sender == expectedSender &&
                    durable.replyToMessageId == incoming.message.replyToMessageId &&
                    durable.ciphertextFanout().isEmpty(),
            ) { "Durable incoming projection does not match the authenticated envelope" }
            return issueDeliveryToken(envelope, incoming)
        }

        private fun issueDeliveryToken(
            envelope: IncomingEnvelope,
            incoming: ValidatedMessagingSyncEvent.IncomingMessage,
        ): DeliveryToken {
            val token = synchronized(issuanceLock) {
                check(tokenizedIncomingIdentities.add(envelope.issuanceIdentity)) {
                    "A delivery token was already issued for this incoming envelope"
                }
                val identity = Any()
                val token = DeliveryToken(this, identity)
                issuedDeliveryTokens[token] = IssuedDeliveryToken(
                    identity = identity,
                    incomingIdentity = envelope.issuanceIdentity,
                    messageId = incoming.message.messageId,
                )
                token
            }
            assertSyncAllowed()
            return token
        }

        suspend fun acknowledgeDelivery(
            tokens: Collection<DeliveryToken>,
        ): DeliveryAcknowledgement {
            val frozenTokens = tokens.toList()
            val targetMessageIds = beginDeliveryAcknowledgement(frozenTokens)
            return try {
                val response: MessageDeliveryAcknowledgementDto = owner.fencedSessionCall(
                    this,
                    issuanceIdentity,
                    lifecycle,
                    fence,
                    // Acknowledgement contains only a token-bound message ID and is required to
                    // finish crash-safe initial sync before READY is released.
                    readyRequired = false,
                ) {
                    acknowledgeMessageDelivery(AcknowledgeMessageDeliveryRequest(targetMessageIds))
                }
                val validated = SecureMessagingTransportValidator.validateDeliveryAcknowledgement(
                    response,
                    context.currentDeviceId,
                    targetMessageIds,
                )
                assertSyncAllowed()
                completeDeliveryAcknowledgement(frozenTokens, validated)
            } catch (error: Throwable) {
                releaseFailedDeliveryAcknowledgement(frozenTokens)
                throw error
            }
        }

        private fun requireUuid(value: String, field: String) = owner.requireUuid(value, field)

        private fun issueConversation(validated: ValidatedDirectConversation): DirectConversation {
            val identity = Any()
            val handle = DirectConversation(
                owner = this,
                issuanceIdentity = identity,
                conversationId = validated.conversationId,
                peerUserId = validated.peerUserId,
                peerName = validated.peerName,
                currentUserRole = validated.currentUserRole,
            )
            synchronized(issuanceLock) {
                issuedConversations[handle] = IssuedConversation(identity, validated)
            }
            return handle
        }

        private fun requireConversation(handle: DirectConversation): ValidatedDirectConversation {
            lifecycle.assertCurrent(activation)
            return synchronized(issuanceLock) {
                val issued = issuedConversations[handle]
                    ?: error("Direct conversation was not issued by this secure-messaging session")
                check(issued.identity === handle.issuanceIdentity) {
                    "Direct conversation was not issued by this secure-messaging session"
                }
                issued.validated
            }
        }

        private fun issueRoster(
            conversation: DirectConversation,
            validated: ValidatedMessagingDeviceRoster,
            use: RosterUse,
        ): AuthoritativeRoster {
            val identity = Any()
            val handle = AuthoritativeRoster(this, identity)
            synchronized(issuanceLock) {
                val issuedConversation = issuedConversations[conversation]
                    ?: error("Direct conversation was not issued by this secure-messaging session")
                check(issuedConversation.identity === conversation.issuanceIdentity) {
                    "Direct conversation was not issued by this secure-messaging session"
                }
                issuedRosters[handle] = IssuedRoster(
                    identity = identity,
                    conversationIdentity = conversation.issuanceIdentity,
                    validated = validated,
                    use = use,
                )
            }
            return handle
        }

        private fun requireRoster(
            handle: AuthoritativeRoster,
            conversation: DirectConversation? = null,
        ): IssuedRoster {
            lifecycle.assertCurrent(activation)
            return synchronized(issuanceLock) {
                val issued = issuedRosters[handle]
                    ?: error("Authoritative roster was not issued by this secure-messaging session")
                check(issued.identity === handle.issuanceIdentity) {
                    "Authoritative roster was not issued by this secure-messaging session"
                }
                if (conversation != null) {
                    val issuedConversation = issuedConversations[conversation]
                        ?: error("Direct conversation was not issued by this secure-messaging session")
                    check(issuedConversation.identity === conversation.issuanceIdentity) {
                        "Direct conversation was not issued by this secure-messaging session"
                    }
                    check(issued.conversationIdentity === conversation.issuanceIdentity) {
                        "Authoritative roster belongs to another conversation handle"
                    }
                }
                issued
            }
        }

        private fun requirePlan(
            plan: SecureMessagingEncryptionPlan,
            roster: AuthoritativeRoster,
            conversation: DirectConversation? = null,
            outboundRequired: Boolean,
        ) {
            lifecycle.assertCurrent(activation)
            synchronized(issuanceLock) {
                val issued = issuedPlans[plan]
                    ?: error("Encryption plan was not issued by this secure-messaging session")
                check(issued.rosterIdentity === roster.issuanceIdentity) {
                    "Encryption plan belongs to another authoritative roster"
                }
                if (outboundRequired) {
                    check(issued.outboundAllowed) {
                        "Receive-only roster plan cannot authorize outbound encryption"
                    }
                }
                conversation?.let {
                    check(issued.conversationIdentity === it.issuanceIdentity) {
                        "Encryption plan belongs to another conversation handle"
                    }
                }
            }
            SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
        }

        private fun issueKeyStatus(validated: MessagingKeyStatusDto): KeyStatus = KeyStatus(
            owner = this,
            issuanceIdentity = Any(),
            enrolled = checkNotNull(validated.enrolled),
            signalDeviceId = validated.signalDeviceId,
            registrationId = validated.registrationId,
            identityKeySha256 = validated.identityKeySha256,
            signedPrekeyId = validated.signedPrekeyId,
            pqLastResortPrekeyId = validated.pqLastResortPrekeyId,
            availableOneTimePrekeys = checkNotNull(validated.availableOneTimePrekeys),
            availableEcOneTimePrekeys = checkNotNull(validated.availableEcOneTimePrekeys),
            availablePqOneTimePrekeys = checkNotNull(validated.availablePqOneTimePrekeys),
            replenishAt = checkNotNull(validated.replenishAt),
            needsReplenishment = checkNotNull(validated.needsReplenishment),
        )

        private fun issueOutboundReceipt(
            validated: ValidatedOutboundEncryptedMessage,
        ): OutboundReceipt = OutboundReceipt(
            owner = this,
            issuanceIdentity = Any(),
            messageId = validated.messageId,
            clientMessageId = validated.clientMessageId,
            conversationId = validated.conversationId,
            rosterRevision = validated.rosterRevision,
            senderBundleVersion = validated.senderBundleVersion,
            sentAt = validated.sentAt,
        )

        private fun issueReadReceipt(
            validated: ValidatedMessagingReadReceipt,
        ): ReadReceipt = ReadReceipt(
            owner = this,
            issuanceIdentity = Any(),
            conversationId = validated.conversationId,
            lastReadMessageId = validated.lastReadMessageId,
            readAt = validated.readAt,
        )

        private fun issueCheckpointLocked(
            cursor: String?,
            previousEventId: Long?,
        ): SyncCheckpoint {
            val identity = Any()
            val checkpoint = SyncCheckpoint(this, identity)
            issuedCheckpoints[checkpoint] = IssuedCheckpoint(
                identity = identity,
                cursor = cursor,
                previousEventId = previousEventId,
            )
            return checkpoint
        }

        private fun requireCheckpointLocked(checkpoint: SyncCheckpoint): IssuedCheckpoint {
            val issued = issuedCheckpoints[checkpoint]
                ?: error("Sync checkpoint was not issued by this secure-messaging session")
            check(issued.identity === checkpoint.issuanceIdentity) {
                "Sync checkpoint was not issued by this secure-messaging session"
            }
            return issued
        }

        private fun beginSync(checkpoint: SyncCheckpoint, limit: Int): CheckpointRequest {
            assertSyncAllowed()
            return synchronized(issuanceLock) {
                val issued = requireCheckpointLocked(checkpoint)
                check(issued.state == CheckpointState.AVAILABLE) {
                    "Sync checkpoint is already in use or consumed"
                }
                SecureMessagingTransportValidator.validateSyncRequest(
                    issued.cursor,
                    limit,
                    issued.previousEventId,
                )
                issued.state = CheckpointState.IN_FLIGHT
                CheckpointRequest(issued.cursor, issued.previousEventId)
            }
        }

        private fun assertSyncAllowed() {
            lifecycle.assertCurrent(fence)
            check(
                lifecycle.snapshot().stage in setOf(
                    SecureMessagingRuntimeStage.SYNCING_ROSTER,
                    SecureMessagingRuntimeStage.READY,
                ),
            ) { "Encrypted sync is allowed only during roster activation or message exchange" }
            lifecycle.assertCurrent(fence)
        }

        private fun releaseFailedSync(checkpoint: SyncCheckpoint) {
            synchronized(issuanceLock) {
                val issued = issuedCheckpoints[checkpoint]
                if (
                    issued != null &&
                    issued.identity === checkpoint.issuanceIdentity &&
                    issued.state == CheckpointState.IN_FLIGHT
                ) {
                    issued.state = CheckpointState.AVAILABLE
                }
            }
        }

        private fun issueSyncBatch(
            checkpoint: SyncCheckpoint,
            page: ValidatedMessagingSyncPage,
        ): SyncBatch {
            val batchIdentity = Any()
            val pairs = page.events.map { validated ->
                val eventIdentity = Any()
                createSyncEvent(validated, eventIdentity) to IssuedEvent(
                    identity = eventIdentity,
                    batchIdentity = batchIdentity,
                    validated = validated,
                )
            }
            val handles = pairs.map { it.first }.toList()
            val batch = SyncBatch(
                owner = this,
                issuanceIdentity = batchIdentity,
                events = handles,
                hasMore = page.hasMore,
            )
            synchronized(issuanceLock) {
                val issuedCheckpoint = requireCheckpointLocked(checkpoint)
                check(issuedCheckpoint.state == CheckpointState.IN_FLIGHT) {
                    "Sync checkpoint is no longer awaiting its response"
                }
                pairs.forEach { (handle, issued) -> issuedEvents[handle] = issued }
                issuedBatches[batch] = IssuedBatch(
                    identity = batchIdentity,
                    sourceCheckpoint = issuedCheckpoint,
                    nextCursor = page.nextCursor,
                    lastEventId = page.lastEventId,
                    eventIdentities = pairs.map { it.second.identity }.toList(),
                )
                issuedCheckpoint.state = CheckpointState.AWAITING_CONFIRMATION
                issuedCheckpoint.batchIdentity = batchIdentity
            }
            return batch
        }

        private fun createSyncEvent(
            validated: ValidatedMessagingSyncEvent,
            identity: Any,
        ): SyncEvent = when (validated) {
            is ValidatedMessagingSyncEvent.IncomingMessage -> IncomingEnvelope(
                owner = this,
                issuanceIdentity = identity,
                eventId = validated.eventId,
                conversationId = validated.conversationId,
                occurredAt = validated.occurredAt,
                messageId = validated.message.messageId,
                clientMessageId = validated.message.clientMessageId,
                senderUserId = validated.message.senderUserId,
                senderDeviceId = validated.message.senderDeviceId,
                sentAt = validated.message.sentAt,
                rosterRevision = validated.message.rosterRevision,
                replyToMessageId = validated.message.replyToMessageId,
                kind = validated.message.kind,
                attachments = validated.message.attachments(),
            )
            is ValidatedMessagingSyncEvent.OutboundMessage -> OutboundEvent(
                owner = this,
                issuanceIdentity = identity,
                eventId = validated.eventId,
                conversationId = validated.conversationId,
                occurredAt = validated.occurredAt,
                messageId = validated.message.messageId,
                clientMessageId = validated.message.clientMessageId,
                rosterRevision = validated.message.rosterRevision,
                sentAt = validated.message.sentAt,
            )
            is ValidatedMessagingSyncEvent.DeliveryReceipt -> DeliveryReceiptEvent(
                owner = this,
                issuanceIdentity = identity,
                eventId = validated.eventId,
                conversationId = validated.conversationId,
                occurredAt = validated.occurredAt,
                messageId = validated.messageId,
                deliveredAt = validated.deliveredAt,
            )
            is ValidatedMessagingSyncEvent.ReadReceipt -> ReadReceiptEvent(
                owner = this,
                issuanceIdentity = identity,
                eventId = validated.eventId,
                conversationId = validated.conversationId,
                occurredAt = validated.occurredAt,
                readerUserId = validated.userId,
                lastReadMessageId = validated.lastReadMessageId,
                readAt = validated.readAt,
            )
            is ValidatedMessagingSyncEvent.RosterRefresh -> RosterRefreshEvent(
                owner = this,
                issuanceIdentity = identity,
                eventId = validated.eventId,
                conversationId = validated.conversationId,
                occurredAt = validated.occurredAt,
                eventType = validated.refresh.eventType,
                affectedUserId = validated.refresh.userId,
                affectedDeviceId = validated.refresh.deviceId,
                affectedSignalDeviceId = validated.refresh.signalDeviceId,
                affectedRegistrationId = validated.refresh.registrationId,
                previousRegistrationId = validated.refresh.previousRegistrationId,
                protocolVersion = validated.refresh.protocolVersion,
                previousProtocolVersion = validated.refresh.previousProtocolVersion,
                bundleVersion = validated.refresh.bundleVersion,
                identityKeySha256 = validated.refresh.identityKeySha256,
                previousIdentityKeySha256 = validated.refresh.previousIdentityKeySha256,
                revokedDeviceCount = validated.refresh.revokedDeviceCount,
                transitionedAt = validated.refresh.transitionedAt,
                transitionHash = validated.refresh.transitionHash,
            )
            is ValidatedMessagingSyncEvent.Metadata -> MetadataEvent(
                owner = this,
                issuanceIdentity = identity,
                eventId = validated.eventId,
                conversationId = validated.conversationId,
                occurredAt = validated.occurredAt,
                type = validated.type,
            )
        }

        private fun requireBatchLocked(batch: SyncBatch): IssuedBatch {
            val issued = issuedBatches[batch]
                ?: error("Sync batch was not issued by this secure-messaging session")
            check(issued.identity === batch.issuanceIdentity) {
                "Sync batch was not issued by this secure-messaging session"
            }
            return issued
        }

        private fun snapshotEvents(
            batch: SyncBatch,
            events: List<SyncEvent>,
        ): List<SyncEvent> {
            assertSyncAllowed()
            synchronized(issuanceLock) {
                val issuedBatch = requireBatchLocked(batch)
                check(
                    issuedBatch.eventIdentities.size == events.size &&
                        events.indices.all { index ->
                            val event = events[index]
                            val issued = issuedEvents[event] ?: return@all false
                            issued.identity === event.issuanceIdentity &&
                                issued.identity === issuedBatch.eventIdentities[index] &&
                                issued.batchIdentity === issuedBatch.identity
                        },
                ) { "Sync batch event snapshot changed" }
            }
            return events.toList()
        }

        private fun requireIncomingEnvelope(
            envelope: IncomingEnvelope,
        ): ValidatedMessagingSyncEvent.IncomingMessage {
            assertSyncAllowed()
            return synchronized(issuanceLock) {
                val issued = issuedEvents[envelope]
                    ?: error("Incoming envelope was not issued by this secure-messaging session")
                check(issued.identity === envelope.issuanceIdentity) {
                    "Incoming envelope was not issued by this secure-messaging session"
                }
                issued.validated as? ValidatedMessagingSyncEvent.IncomingMessage
                    ?: error("Sync event is not an incoming envelope")
            }
        }

        private fun beginDeliveryAcknowledgement(
            tokens: List<DeliveryToken>,
        ): List<String> {
            assertSyncAllowed()
            require(tokens.size in 1..MAX_DELIVERY_ACK_BATCH) {
                "Invalid delivery acknowledgement batch size"
            }
            require(tokens.distinct().size == tokens.size) {
                "Duplicate delivery acknowledgement token"
            }
            return synchronized(issuanceLock) {
                val issued = tokens.map { token ->
                    val value = issuedDeliveryTokens[token]
                        ?: error("Delivery token was not issued by this secure-messaging session")
                    check(value.identity === token.issuanceIdentity) {
                        "Delivery token was not issued by this secure-messaging session"
                    }
                    check(value.state == DeliveryState.AVAILABLE) {
                        "Delivery token is already in use or acknowledged"
                    }
                    value
                }
                check(issued.map { it.messageId }.distinct().size == issued.size) {
                    "Delivery tokens contain duplicate message IDs"
                }
                issued.forEach { it.state = DeliveryState.IN_FLIGHT }
                issued.map { it.messageId }.toList()
            }
        }

        private fun releaseFailedDeliveryAcknowledgement(tokens: List<DeliveryToken>) {
            synchronized(issuanceLock) {
                tokens.forEach { token ->
                    val issued = issuedDeliveryTokens[token]
                    if (
                        issued != null &&
                        issued.identity === token.issuanceIdentity &&
                        issued.state == DeliveryState.IN_FLIGHT
                    ) {
                        issued.state = DeliveryState.AVAILABLE
                    }
                }
            }
        }

        private fun completeDeliveryAcknowledgement(
            tokens: List<DeliveryToken>,
            validated: ValidatedMessageDeliveryAcknowledgement,
        ): DeliveryAcknowledgement = synchronized(issuanceLock) {
            val issued = tokens.map { token ->
                val value = issuedDeliveryTokens[token]
                    ?: error("Delivery acknowledgement token state changed")
                check(
                    value.identity === token.issuanceIdentity &&
                        value.state == DeliveryState.IN_FLIGHT,
                ) { "Delivery acknowledgement token state changed" }
                value
            }
            check(validated.items.map { it.messageId }.toSet() == issued.map { it.messageId }.toSet()) {
                "Validated delivery acknowledgement changed token targets"
            }
            issued.forEach { it.state = DeliveryState.ACKNOWLEDGED }
            DeliveryAcknowledgement(
                owner = this,
                issuanceIdentity = Any(),
                acknowledgedCount = issued.size,
                newlyAcknowledgedCount = validated.newlyAcknowledgedCount,
            )
        }

        private companion object {
            const val MAX_RECIPIENT_DEVICES = 99
            const val MAX_DELIVERY_ACK_BATCH = 100
        }
    }

    private val issuedSessions = Collections.synchronizedMap(WeakHashMap<Session, Any>())

    /**
     * Checks the exact v2 readiness advertisement and binds the remote profile/device to [fence].
     * No operation handle is returned unless every fenced check succeeds.
     */
    suspend fun openSession(
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
    ): Session {
        lifecycle.beginCapabilityCheck(fence)

        val capabilities = fencedCall(lifecycle, fence) { api.capabilities() }
        if (capabilities.features?.get(KitFeature.MESSAGING) != true) {
            quarantineCapabilityMismatch(lifecycle, fence)
            throw SecureMessagingProtocolUnavailableException(
                "Secure messaging is not enabled for this Kit Pay environment",
            )
        }
        val protocol = capabilities.protocols?.messaging
        if (!SecureMessagingContract.matchesServerAdvertisement(
                ready = protocol?.ready == true,
                version = protocol?.version,
                suite = protocol?.suite,
                postQuantum = protocol?.postQuantum,
            )
        ) {
            quarantineCapabilityMismatch(lifecycle, fence)
            throw SecureMessagingProtocolUnavailableException(
                "The server has not enabled the reviewed secure-messaging protocol",
            )
        }

        val profile = fencedCall(lifecycle, fence) { api.profile() }
        requireUuid(profile.id, "current user ID")
        if (profile.id != fence.binding.userId) {
            lifecycle.quarantine(fence, SecureMessagingQuarantineReason.MALFORMED_WIRE_DATA)
            error("Authenticated secure-messaging user changed")
        }

        val deviceResponse = fencedCall(lifecycle, fence) { api.devices() }
        val device = SecureMessagingTransportValidator.requireCurrentServerDevice(deviceResponse)
        if (device.id != fence.binding.serverDeviceId) {
            lifecycle.quarantine(fence, SecureMessagingQuarantineReason.CURRENT_DEVICE_REVOKED)
            error("Authenticated secure-messaging device changed")
        }

        val issuanceIdentity = Any()
        val session = Session(
            owner = this,
            issuanceIdentity = issuanceIdentity,
            lifecycle = lifecycle,
            fence = fence,
            activation = lifecycle.activationCapability(fence),
            context = SecureMessagingRemoteContext(profile.id, device.id),
        )
        issuedSessions[session] = issuanceIdentity
        return session
    }

    private suspend fun <T> fencedSessionCall(
        session: Session,
        issuanceIdentity: Any,
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
        readyRequired: Boolean = false,
        call: suspend SecureMessagingWireApi.() -> ApiEnvelope<T>,
    ): T {
        check(issuedSessions[session] === issuanceIdentity) {
            "Secure messaging transport session was not issued by this readiness gate"
        }
        return fencedCall(lifecycle, fence, readyRequired) { messagingApi.call() }
    }

    /**
     * Fenced call for endpoints whose response is raw bytes rather than an API envelope.
     * Activation fencing matches [fencedSessionCall]; error unwrapping is the caller's concern.
     */
    private suspend fun <T> rawFencedSessionCall(
        session: Session,
        issuanceIdentity: Any,
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
        call: suspend SecureMessagingWireApi.() -> T,
    ): T {
        check(issuedSessions[session] === issuanceIdentity) {
            "Secure messaging transport session was not issued by this readiness gate"
        }
        lifecycle.assertCurrent(fence, readyRequired = true)
        val response = try {
            messagingApi.call()
        } catch (error: Throwable) {
            lifecycle.assertCurrent(fence, readyRequired = true)
            throw error
        }
        lifecycle.assertCurrent(fence, readyRequired = true)
        return response
    }

    private suspend fun <T> fencedCall(
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
        readyRequired: Boolean = false,
        call: suspend () -> ApiEnvelope<T>,
    ): T {
        lifecycle.assertCurrent(fence, readyRequired)
        val response = try {
            apiCalls.execute(call)
        } catch (error: Throwable) {
            // If authentication changed while the request was suspended, discard the remote
            // failure too: even an error payload/status belongs to the obsolete activation.
            lifecycle.assertCurrent(fence, readyRequired)
            throw error
        }
        lifecycle.assertCurrent(fence, readyRequired)
        return response
    }

    private fun quarantineCapabilityMismatch(
        lifecycle: SecureMessagingLifecycleGuard,
        fence: SecureMessagingSessionFence,
    ) {
        lifecycle.quarantine(fence, SecureMessagingQuarantineReason.CAPABILITY_MISMATCH)
    }

    private fun requireUuid(value: String, field: String) {
        require(UUID_PATTERN.matches(value)) { "Invalid $field" }
    }

    private companion object {
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        /** Matches the backend's messaging attachment blob size bound. */
        const val MAX_ATTACHMENT_DOWNLOAD_BYTES = 100L * 1024L * 1024L

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
