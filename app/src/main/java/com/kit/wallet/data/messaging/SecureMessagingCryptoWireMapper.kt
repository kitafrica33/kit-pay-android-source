package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ENCRYPTED_MESSAGE_KIND
import com.kit.wallet.data.remote.EncryptedDeviceEnvelopeRequest
import com.kit.wallet.data.remote.MessagingOneTimePrekeyRequest
import com.kit.wallet.data.remote.MessagingPqPrekeyRequest
import com.kit.wallet.data.remote.MessagingSignedPrekeyRequest
import com.kit.wallet.data.remote.PublishMessagingKeyBundleRequest
import com.kit.wallet.data.remote.SECURE_MESSAGING_PROTOCOL_VERSION
import com.kit.wallet.data.remote.SecureMessagingWireValidator
import com.kit.wallet.data.remote.SendEncryptedMessageRequest
import com.kit.wallet.data.remote.ValidatedConsumedMessagingKeyBundles
import com.kit.wallet.data.remote.ValidatedIncomingEncryptedMessage
import com.kit.wallet.data.remote.ValidatedMessagingDeviceRoster
import com.kit.wallet.data.remote.ValidatedMessagingPrekey
import java.util.Base64

sealed interface SecureMessagingKeyPublication {
    val protocolVersion: String
    val registrationId: Int
    val identityKey: String
    val identityKeyChange: Boolean
    val signedPrekey: MessagingSignedPrekeyRequest
    val oneTimePrekeys: List<MessagingOneTimePrekeyRequest>
    val pqPrekeys: List<MessagingPqPrekeyRequest>
    val pqLastResortPrekey: MessagingPqPrekeyRequest
}

/** Opaque send command issued only after committed fanout and roster bindings agree exactly. */
sealed interface SecureMessagingEncryptedSend

internal class SecureMessagingEncryptedSendWire(
    request: SendEncryptedMessageRequest,
    val provenance: SecureMessagingActivationProvenance,
    val conversationId: String,
    val currentUserId: String,
    val currentDeviceId: String,
    memberUserIds: Set<String>,
) {
    private val immutableRequest = request.copy(
        envelopes = request.envelopes.toList(),
        attachments = request.attachments.toList(),
    )
    private val immutableMemberUserIds = memberUserIds.toSet()

    fun request(): SendEncryptedMessageRequest = immutableRequest.copy(
        envelopes = immutableRequest.envelopes.toList(),
        attachments = immutableRequest.attachments.toList(),
    )

    fun memberUserIds(): Set<String> = immutableMemberUserIds.toSet()
}

private class SecureMessagingEncryptedSendValue(
    internal val wire: SecureMessagingEncryptedSendWire,
) : SecureMessagingEncryptedSend

private class SecureMessagingSessionEstablishmentValue(
    override val conversationId: String,
    override val rosterRevision: String,
    bundles: List<SecureMessagingRemoteKeyBundle>,
    val plan: SecureMessagingEncryptionPlanSnapshot,
    val provenance: SecureMessagingActivationProvenance,
) : SecureMessagingSessionEstablishmentRequest {
    private val immutableBundles = bundles.toList()

    override fun bundles(): List<SecureMessagingRemoteKeyBundle> = immutableBundles.toList()
}

internal class SecureMessagingKeyPublicationWire(
    request: PublishMessagingKeyBundleRequest,
    val provenance: SecureMessagingActivationProvenance,
) {
    private val immutableRequest = request.copy(
        oneTimePrekeys = request.oneTimePrekeys.toList(),
        pqPrekeys = request.pqPrekeys.toList(),
    )

    fun request(): PublishMessagingKeyBundleRequest = immutableRequest.copy(
        oneTimePrekeys = immutableRequest.oneTimePrekeys.toList(),
        pqPrekeys = immutableRequest.pqPrekeys.toList(),
    )
}

private class SecureMessagingKeyPublicationValue(
    internal val wire: SecureMessagingKeyPublicationWire,
) : SecureMessagingKeyPublication {
    override val protocolVersion: String get() = wire.request().protocolVersion
    override val registrationId: Int get() = wire.request().registrationId
    override val identityKey: String get() = wire.request().identityKey
    override val identityKeyChange: Boolean get() = wire.request().identityKeyChange
    override val signedPrekey: MessagingSignedPrekeyRequest get() = wire.request().signedPrekey
    override val oneTimePrekeys: List<MessagingOneTimePrekeyRequest> get() = wire.request().oneTimePrekeys
    override val pqPrekeys: List<MessagingPqPrekeyRequest> get() = wire.request().pqPrekeys
    override val pqLastResortPrekey: MessagingPqPrekeyRequest get() = wire.request().pqLastResortPrekey
}

internal class SecureMessagingEncryptionPlanSnapshot(
    val conversationId: String,
    val rosterRevision: String,
    val recipients: SecureMessagingExactRecipientSet,
    val sender: SecureMessagingCryptoAddress,
    memberUserIds: Set<String>,
    deviceBindings: Map<SecureMessagingCryptoAddress, SecureMessagingRosterDeviceBinding>,
    val provenance: SecureMessagingActivationProvenance,
    private val planIdentity: Any,
) {
    private val immutableMemberUserIds = memberUserIds.toSet()
    private val immutableDeviceBindings = deviceBindings.toMap()

    fun memberUserIds(): Set<String> = immutableMemberUserIds.toSet()

    fun deviceBinding(address: SecureMessagingCryptoAddress): SecureMessagingRosterDeviceBinding? =
        immutableDeviceBindings[address]

    fun isSamePlan(other: SecureMessagingEncryptionPlanSnapshot): Boolean =
        planIdentity === other.planIdentity

    fun snapshot(): SecureMessagingEncryptionPlanSnapshot = SecureMessagingEncryptionPlanSnapshot(
        conversationId = conversationId,
        rosterRevision = rosterRevision,
        recipients = SecureMessagingExactRecipientSet(recipients.addresses()),
        sender = sender,
        memberUserIds = immutableMemberUserIds,
        deviceBindings = immutableDeviceBindings,
        provenance = provenance,
        planIdentity = planIdentity,
    )
}

internal data class SecureMessagingRosterDeviceBinding(
    val registrationId: Int,
    val identityKeySha256: String,
)

private class SecureMessagingEncryptionPlanValue(
    val snapshot: SecureMessagingEncryptionPlanSnapshot,
) : SecureMessagingEncryptionPlan

internal class SecureMessagingSessionEstablishmentSnapshot(
    val conversationId: String,
    val rosterRevision: String,
    bundles: List<SecureMessagingRemoteKeyBundle>,
    val localSender: SecureMessagingCryptoAddress,
    val plan: SecureMessagingEncryptionPlanSnapshot,
    val provenance: SecureMessagingActivationProvenance,
) {
    private val immutableBundles = bundles.toList()

    fun bundles(): List<SecureMessagingRemoteKeyBundle> = immutableBundles.toList()
}

internal class SecureMessagingDecryptionRequestSnapshot(
    val messageId: String,
    val clientMessageId: String,
    val conversationId: String,
    val rosterRevision: String,
    val sender: SecureMessagingCryptoAddress,
    val localRecipient: SecureMessagingCryptoAddress,
    val senderRegistrationId: Int,
    val senderIdentityKeySha256: String,
    val envelopeKind: SecureMessagingEnvelopeKind,
    ciphertext: ByteArray,
    val replyToMessageId: String?,
    val provenance: SecureMessagingActivationProvenance,
) {
    private val opaqueCiphertext = OpaqueCryptoBytes.copyOf(ciphertext)

    fun copyCiphertextBytes(): ByteArray = opaqueCiphertext.copyBytes()
}

private class SecureMessagingDecryptionRequestValue(
    val snapshot: SecureMessagingDecryptionRequestSnapshot,
) : SecureMessagingDecryptionRequest

object SecureMessagingCryptoWireMapper {
    fun publication(
        committed: SecureMessagingCommittedResult.Provisioned,
    ): SecureMessagingKeyPublication {
        val provisioning = requireDurablyCommittedProvisioning(committed)
        val request = publicationRequest(
            provisioning.publicBundle,
            provisioning.identityKeyChange,
        )
        return SecureMessagingKeyPublicationValue(
            SecureMessagingKeyPublicationWire(
                request = request,
                provenance = provisioning.provenance,
            ),
        )
    }

    internal fun preflightProvisioning(bundle: SecureMessagingLocalPublicBundle) {
        publicationRequest(bundle, identityKeyChange = false)
    }

    /** Reissues only a bundle recovered from the atomically committed encrypted protocol state. */
    internal fun publicationFromDurableBundle(
        bundle: SecureMessagingLocalPublicBundle,
        identityKeyChange: Boolean,
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingKeyPublication {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        return SecureMessagingKeyPublicationValue(
            SecureMessagingKeyPublicationWire(
                request = publicationRequest(bundle, identityKeyChange),
                provenance = provenance,
            ),
        )
    }

    private fun publicationRequest(
        bundle: SecureMessagingLocalPublicBundle,
        identityKeyChange: Boolean,
    ): PublishMessagingKeyBundleRequest {
        val identity = requireTypedBytes(bundle.identityKey, EC_PUBLIC_KEY_BYTES, EC_KEY_TYPE, "identity key")
        val signed = bundle.signedPrekey.toSignedPrekeyRequest()
        val ecOneTime = bundle.oneTimePrekeys.map { prekey ->
            require(prekey.signature == null) { "An EC one-time prekey cannot carry a signature" }
            MessagingOneTimePrekeyRequest(
                prekeyId = prekey.id,
                publicKey = encode(
                    requireTypedBytes(
                        prekey.publicKey,
                        EC_PUBLIC_KEY_BYTES,
                        EC_KEY_TYPE,
                        "EC one-time prekey",
                    ),
                ),
            )
        }
        val pqOneTime = bundle.pqPrekeys.map { it.toPqPrekeyRequest() }
        return PublishMessagingKeyBundleRequest(
            protocolVersion = SECURE_MESSAGING_PROTOCOL_VERSION,
            registrationId = bundle.registrationId,
            identityKey = encode(identity),
            identityKeyChange = identityKeyChange,
            signedPrekey = signed,
            oneTimePrekeys = ecOneTime,
            pqPrekeys = pqOneTime,
            pqLastResortPrekey = bundle.pqLastResortPrekey.toPqPrekeyRequest(),
        )
    }

    internal fun requirePublication(
        publication: SecureMessagingKeyPublication,
    ): SecureMessagingKeyPublicationWire {
        check(publication is SecureMessagingKeyPublicationValue) {
            "Messaging key publication was not issued by the crypto wire mapper"
        }
        return publication.wire
    }

    fun encryptionPlan(
        roster: ValidatedMessagingDeviceRoster,
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingEncryptionPlan {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        val validatedRoster = SecureMessagingWireValidator.requireValidatedRoster(roster)
        check(validatedRoster.currentUserId == provenance.binding.userId) {
            "Validated roster belongs to another authenticated user"
        }
        check(validatedRoster.currentDeviceId == provenance.binding.serverDeviceId) {
            "Validated roster belongs to another authenticated device"
        }
        val currentDevice = validatedRoster.devices().singleOrNull {
            it.deviceId == validatedRoster.currentDeviceId
        } ?: error("Validated roster lost its current device")
        check(currentDevice.userId == validatedRoster.currentUserId) {
            "Validated roster current device belongs to another user"
        }
        val identity = Any()
        return SecureMessagingEncryptionPlanValue(
            SecureMessagingEncryptionPlanSnapshot(
                conversationId = validatedRoster.conversationId,
                rosterRevision = validatedRoster.rosterRevision,
                recipients = rosterRecipients(validatedRoster),
                sender = SecureMessagingCryptoAddress(
                    userId = currentDevice.userId,
                    serverDeviceId = currentDevice.deviceId,
                    signalDeviceId = currentDevice.signalDeviceId,
                ),
                memberUserIds = validatedRoster.memberUserIds(),
                deviceBindings = validatedRoster.devices().associate { device ->
                    SecureMessagingCryptoAddress(
                        userId = device.userId,
                        serverDeviceId = device.deviceId,
                        signalDeviceId = device.signalDeviceId,
                    ) to SecureMessagingRosterDeviceBinding(
                        registrationId = device.registrationId,
                        identityKeySha256 = device.identityKeySha256,
                    )
                },
                provenance = provenance,
                planIdentity = identity,
            ),
        )
    }

    internal fun requireEncryptionPlan(
        plan: SecureMessagingEncryptionPlan,
    ): SecureMessagingEncryptionPlanSnapshot {
        check(plan is SecureMessagingEncryptionPlanValue) {
            "Encryption plan was not issued from an authoritative roster"
        }
        plan.snapshot.provenance.assertCurrent()
        return plan.snapshot.snapshot()
    }

    private fun rosterRecipients(
        validatedRoster: ValidatedMessagingDeviceRoster,
    ): SecureMessagingExactRecipientSet {
        val addresses = validatedRoster.devices().map { device ->
            SecureMessagingCryptoAddress(
                userId = device.userId,
                serverDeviceId = device.deviceId,
                signalDeviceId = device.signalDeviceId,
            )
        }.filterNot { it.serverDeviceId == validatedRoster.currentDeviceId }
        return SecureMessagingExactRecipientSet(addresses)
    }

    internal fun sessionEstablishment(
        response: ValidatedConsumedMessagingKeyBundles,
        plan: SecureMessagingEncryptionPlan,
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingSessionEstablishmentRequest {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        val planSnapshot = requireEncryptionPlan(plan)
        check(provenance.isSameActivation(planSnapshot.provenance)) {
            "Encryption plan belongs to another authentication activation"
        }
        val validated = SecureMessagingWireValidator.requireValidatedKeyBundles(response)
        check(
            validated.currentUserId == provenance.binding.userId &&
                validated.currentDeviceId == provenance.binding.serverDeviceId,
        ) { "Validated key claims belong to another authentication binding" }
        check(
            validated.conversationId == planSnapshot.conversationId &&
                validated.rosterRevision == planSnapshot.rosterRevision,
        ) { "Validated key claims do not match the frozen encryption plan" }
        val bundles = validated.bundles().map { bundle ->
            SecureMessagingRemoteKeyBundle(
                address = SecureMessagingCryptoAddress(
                    userId = bundle.userId,
                    serverDeviceId = bundle.deviceId,
                    signalDeviceId = bundle.signalDeviceId,
                ),
                registrationId = bundle.registrationId,
                identityKey = OpaqueCryptoBytes.copyOf(bundle.identityKeyBytes()),
                signedPrekey = bundle.signedPrekey.toCryptoPrekey(signatureRequired = true),
                oneTimePrekey = bundle.oneTimePrekey?.toCryptoPrekey(signatureRequired = false),
                pqPrekey = bundle.pqPrekey.toCryptoPrekey(signatureRequired = true),
            )
        }
        check(bundles.isNotEmpty()) { "Session establishment requires a consumed key bundle" }
        check(
            bundles.map { it.address.serverDeviceId }.toSet() == validated.requestedDeviceIds(),
        ) { "Session-establishment bundles changed after validated key consumption" }
        return SecureMessagingSessionEstablishmentValue(
            conversationId = validated.conversationId,
            rosterRevision = validated.rosterRevision,
            bundles = bundles,
            plan = planSnapshot,
            provenance = provenance,
        )
    }

    internal fun requireSessionEstablishment(
        request: SecureMessagingSessionEstablishmentRequest,
    ): SecureMessagingSessionEstablishmentSnapshot {
        check(request is SecureMessagingSessionEstablishmentValue) {
            "Session-establishment request was not issued from validated key claims"
        }
        request.provenance.assertCurrent()
        return SecureMessagingSessionEstablishmentSnapshot(
            conversationId = request.conversationId,
            rosterRevision = request.rosterRevision,
            bundles = request.bundles(),
            localSender = request.plan.sender,
            plan = request.plan.snapshot(),
            provenance = request.provenance,
        )
    }

    fun encryption(
        committed: SecureMessagingCommittedResult.Encrypted,
    ): SecureMessagingEncryptedSend {
        val committedFanout = requireDurablyCommittedFanout(committed)
        return encryptedSend(
            fanout = committedFanout.fanout,
            plan = committedFanout.plan,
            provenance = committedFanout.provenance,
        )
    }

    /**
     * Reissues the exact committed ciphertext after process/network failure. No plaintext is
     * re-encrypted and the fresh authoritative plan must still match the persisted fanout exactly.
     */
    internal fun retryEncryption(
        durableRecord: LibSignalCompanionRecord,
        plan: SecureMessagingEncryptionPlan,
    ): SecureMessagingEncryptedSend {
        val durable = requireDurableLibSignalCompanionRecord(durableRecord)
        val planSnapshot = requireEncryptionPlan(plan)
        check(durable.direction == LibSignalCompanionDirection.OUTBOUND) {
            "Durable projection is not an outbound secure message"
        }
        check(durable.messageId == durable.clientMessageId) {
            "Durable outbound projection has inconsistent message identifiers"
        }
        check(
            durable.conversationId == planSnapshot.conversationId &&
                durable.rosterRevision == planSnapshot.rosterRevision &&
                durable.sender == planSnapshot.sender,
        ) { "Durable outbound projection does not match the authoritative roster plan" }
        val persistedEnvelopes = durable.ciphertextFanout()
        val envelopes = persistedEnvelopes.map { persisted ->
            val ciphertext = persisted.copyCiphertextBytes()
            try {
                SecureMessagingPreparedEnvelope(
                    recipient = persisted.recipient,
                    kind = persisted.kind,
                    ciphertext = OpaqueCryptoBytes.copyOf(ciphertext),
                )
            } finally {
                ciphertext.fill(0)
            }
        }
        val fanout = SecureMessagingPreparedFanout(
            conversationId = durable.conversationId,
            clientMessageId = durable.clientMessageId,
            rosterRevision = durable.rosterRevision,
            recipients = SecureMessagingExactRecipientSet(
                persistedEnvelopes.map(LibSignalPersistedEnvelope::recipient),
            ),
            envelopes = envelopes,
            replyToMessageId = durable.replyToMessageId,
        )
        return encryptedSend(fanout, planSnapshot, planSnapshot.provenance)
    }

    private fun encryptedSend(
        fanout: SecureMessagingPreparedFanout,
        plan: SecureMessagingEncryptionPlanSnapshot,
        provenance: SecureMessagingActivationProvenance,
    ): SecureMessagingEncryptedSend {
        check(provenance.isSameActivation(plan.provenance)) {
            "Committed fanout and encryption plan belong to different activations"
        }
        check(fanout.conversationId == plan.conversationId) {
            "Committed fanout conversation does not match its encryption plan"
        }
        check(fanout.rosterRevision == plan.rosterRevision) {
            "Committed fanout revision does not match its encryption plan"
        }
        val expectedRecipients = plan.recipients.addressSet()
        check(fanout.recipients.addressSet() == expectedRecipients) {
            "Committed fanout recipients do not exactly match its encryption plan"
        }
        val envelopes = fanout.envelopes
        check(
            envelopes.size == expectedRecipients.size &&
                envelopes.map(SecureMessagingPreparedEnvelope::recipient).toSet() ==
                expectedRecipients,
        ) { "Committed fanout envelopes do not exactly match the authoritative roster" }
        check(envelopes.map { it.recipient.serverDeviceId }.distinct().size == envelopes.size) {
            "Committed fanout contains duplicate recipient envelopes"
        }
        val request = SendEncryptedMessageRequest(
            clientMessageId = fanout.clientMessageId,
            rosterRevision = fanout.rosterRevision,
            kind = ENCRYPTED_MESSAGE_KIND,
            replyToMessageId = fanout.replyToMessageId,
            envelopes = envelopes.map { envelope ->
                val ciphertext = envelope.ciphertext.copyBytes()
                EncryptedDeviceEnvelopeRequest(
                    recipientDeviceId = envelope.recipient.serverDeviceId,
                    envelopeType = when (envelope.kind) {
                        SecureMessagingEnvelopeKind.PREKEY -> "signal-prekey-v2" // gitleaks:allow
                        SecureMessagingEnvelopeKind.SESSION -> "signal-message-v2"
                    },
                    ciphertext = try {
                        encode(ciphertext)
                    } finally {
                        ciphertext.fill(0)
                    },
                )
            },
        )
        return SecureMessagingEncryptedSendValue(
            SecureMessagingEncryptedSendWire(
                request = request,
                provenance = provenance,
                conversationId = plan.conversationId,
                currentUserId = plan.sender.userId,
                currentDeviceId = plan.sender.serverDeviceId,
                memberUserIds = plan.memberUserIds(),
            ),
        )
    }

    internal fun requireEncryptedSend(
        encryptedSend: SecureMessagingEncryptedSend,
    ): SecureMessagingEncryptedSendWire {
        check(encryptedSend is SecureMessagingEncryptedSendValue) {
            "Encrypted send command was not issued by the crypto wire mapper"
        }
        return encryptedSend.wire
    }

    fun decryptionRequest(
        message: ValidatedIncomingEncryptedMessage,
        plan: SecureMessagingEncryptionPlan,
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingDecryptionRequest {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        val planSnapshot = requireEncryptionPlan(plan)
        check(provenance.isSameActivation(planSnapshot.provenance)) {
            "Decryption plan belongs to another authentication activation"
        }
        val remoteSender = SecureMessagingCryptoAddress(
            userId = message.senderUserId,
            serverDeviceId = message.senderDeviceId,
            signalDeviceId = message.senderSignalDeviceId,
        )
        check(message.conversationId == planSnapshot.conversationId) {
            "Incoming envelope belongs to another conversation"
        }
        check(message.rosterRevision == planSnapshot.rosterRevision) {
            "Incoming envelope belongs to another roster revision"
        }
        val senderBinding = checkNotNull(planSnapshot.deviceBinding(remoteSender)) {
            "Incoming envelope sender is absent from the authoritative roster"
        }
        check(senderBinding.registrationId == message.senderRegistrationId) {
            "Incoming envelope sender registration changed"
        }
        check(senderBinding.identityKeySha256 == message.senderIdentityKeySha256) {
            "Incoming envelope sender identity changed"
        }
        val snapshot = SecureMessagingDecryptionRequestSnapshot(
            messageId = message.messageId,
            clientMessageId = message.clientMessageId,
            conversationId = message.conversationId,
            rosterRevision = message.rosterRevision,
            sender = remoteSender,
            localRecipient = planSnapshot.sender,
            senderRegistrationId = message.senderRegistrationId,
            senderIdentityKeySha256 = message.senderIdentityKeySha256,
            envelopeKind = when (message.envelopeType) {
                "signal-prekey-v2" -> SecureMessagingEnvelopeKind.PREKEY // gitleaks:allow
                "signal-message-v2" -> SecureMessagingEnvelopeKind.SESSION
                else -> error("The validated envelope type is unsupported")
            },
            ciphertext = message.ciphertextBytes(),
            replyToMessageId = message.replyToMessageId,
            provenance = provenance,
        )
        validateDecryptionSnapshot(snapshot)
        return SecureMessagingDecryptionRequestValue(snapshot)
    }

    internal fun requireDecryptionRequest(
        request: SecureMessagingDecryptionRequest,
    ): SecureMessagingDecryptionRequestSnapshot {
        check(request is SecureMessagingDecryptionRequestValue) {
            "Decryption request was not issued from a validated incoming envelope"
        }
        request.snapshot.provenance.assertCurrent()
        validateDecryptionSnapshot(request.snapshot)
        return SecureMessagingDecryptionRequestSnapshot(
            messageId = request.snapshot.messageId,
            clientMessageId = request.snapshot.clientMessageId,
            conversationId = request.snapshot.conversationId,
            rosterRevision = request.snapshot.rosterRevision,
            sender = request.snapshot.sender,
            localRecipient = request.snapshot.localRecipient,
            senderRegistrationId = request.snapshot.senderRegistrationId,
            senderIdentityKeySha256 = request.snapshot.senderIdentityKeySha256,
            envelopeKind = request.snapshot.envelopeKind,
            ciphertext = request.snapshot.copyCiphertextBytes(),
            replyToMessageId = request.snapshot.replyToMessageId,
            provenance = request.snapshot.provenance,
        )
    }

    private fun validateDecryptionSnapshot(snapshot: SecureMessagingDecryptionRequestSnapshot) {
        requireUuid(snapshot.messageId, "message ID")
        requireUuid(snapshot.clientMessageId, "client message ID")
        requireUuid(snapshot.conversationId, "conversation ID")
        require(ROSTER_REVISION.matches(snapshot.rosterRevision)) { "Invalid roster revision" }
        snapshot.replyToMessageId?.let { requireUuid(it, "reply target") }
        require(snapshot.senderRegistrationId in 1..16_380) {
            "Registration ID is outside the v2 contract"
        }
        check(snapshot.localRecipient.userId == snapshot.provenance.binding.userId) {
            "Incoming envelope recipient user does not match the active session"
        }
        check(snapshot.localRecipient.serverDeviceId == snapshot.provenance.binding.serverDeviceId) {
            "Incoming envelope recipient device does not match the active session"
        }
        check(snapshot.sender.serverDeviceId != snapshot.localRecipient.serverDeviceId) {
            "Incoming envelope cannot originate from the current device"
        }
        require(SHA256_HEX.matches(snapshot.senderIdentityKeySha256)) {
            "Invalid sender identity-key digest"
        }
        val ciphertext = snapshot.copyCiphertextBytes()
        require(ciphertext.isNotEmpty()) { "Secure message ciphertext must not be empty" }
        require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "Secure message ciphertext is too large"
        }
    }

    private fun ValidatedMessagingPrekey.toCryptoPrekey(
        signatureRequired: Boolean,
    ): SecureMessagingPublicPrekey {
        val signature = signatureBytes()
        check(signatureRequired == (signature != null)) {
            "Validated remote prekey signature shape changed"
        }
        return SecureMessagingPublicPrekey(
            id = id,
            publicKey = OpaqueCryptoBytes.copyOf(publicKeyBytes()),
            signature = signature?.let(OpaqueCryptoBytes::copyOf),
        )
    }

    private fun SecureMessagingPublicPrekey.toSignedPrekeyRequest(): MessagingSignedPrekeyRequest =
        MessagingSignedPrekeyRequest(
            prekeyId = id,
            publicKey = encode(
                requireTypedBytes(publicKey, EC_PUBLIC_KEY_BYTES, EC_KEY_TYPE, "signed prekey"),
            ),
            signature = encode(
                requireTypedBytes(
                    checkNotNull(signature),
                    SIGNATURE_BYTES,
                    expectedType = null,
                    field = "signed-prekey signature",
                ),
            ),
        )

    private fun SecureMessagingPublicPrekey.toPqPrekeyRequest(): MessagingPqPrekeyRequest =
        MessagingPqPrekeyRequest(
            prekeyId = id,
            publicKey = encode(
                requireTypedBytes(publicKey, PQ_PUBLIC_KEY_BYTES, PQ_KEY_TYPE, "PQ prekey"),
            ),
            signature = encode(
                requireTypedBytes(
                    checkNotNull(signature),
                    SIGNATURE_BYTES,
                    expectedType = null,
                    field = "PQ-prekey signature",
                ),
            ),
        )

    private fun requireTypedBytes(
        value: OpaqueCryptoBytes,
        expectedSize: Int,
        expectedType: Int?,
        field: String,
    ): ByteArray = value.copyBytes().also { requireBytes(it, expectedSize, expectedType, field) }

    private fun requireBytes(bytes: ByteArray, expectedSize: Int, expectedType: Int?, field: String) {
        require(bytes.size == expectedSize) { "Invalid $field length" }
        expectedType?.let { require(bytes.first().toInt() and 0xff == it) { "Invalid $field type" } }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun requireUuid(value: String, field: String) {
        require(UUID_PATTERN.matches(value)) { "Invalid $field" }
    }

    private const val EC_PUBLIC_KEY_BYTES = 33
    private const val EC_KEY_TYPE = 5
    private const val PQ_PUBLIC_KEY_BYTES = 1_569
    private const val PQ_KEY_TYPE = 8
    private const val SIGNATURE_BYTES = 64
    private const val MAX_CIPHERTEXT_BYTES = 1_500_000
    private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
    private val UUID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val ROSTER_REVISION = Regex("^v1:sha256:[a-f0-9]{64}$")
}
