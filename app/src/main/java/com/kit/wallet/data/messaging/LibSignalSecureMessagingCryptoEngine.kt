package com.kit.wallet.data.messaging

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMacException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidRegistrationIdException
import org.signal.libsignal.protocol.InvalidSessionException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Official libsignal implementation of Kit Pay's direct-message crypto boundary.
 *
 * Every transaction owns an isolated protocol-state copy. Ratchet, replay, prekey-consumption and
 * the matching encrypted-at-rest message projection become visible through one [writeBatch] call.
 */
@Singleton
class LibSignalSecureMessagingCryptoEngine @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
) : SecureMessagingCryptoEngine {
    private val transactionGate = Mutex()

    override suspend fun openTransaction(
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingCryptoTransaction {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        transactionGate.lock()
        var loaded: LoadedProtocolState? = null
        try {
            stateStore.withActivationLease(activation) {
                loaded = loadProtocolState(provenance.binding)
            }
            val transactionState = checkNotNull(loaded)
            provenance.assertCurrent()
            val transaction = LibSignalTransaction(
                activation = activation,
                stateStore = stateStore,
                loaded = transactionState,
                releaseGate = transactionGate::unlock,
            )
            loaded = null
            return transaction
        } catch (error: Throwable) {
            loaded?.state?.close()
            transactionGate.unlock()
            if (error is SecureMessagingCryptographicFailureException) {
                runCatching { provenance.quarantine(error.quarantineReason) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        }
    }

    override suspend fun eraseAll() = transactionGate.withLock {
        stateStore.eraseAll()
    }

    override suspend fun retireRemoteDevices(
        activation: SecureMessagingActivationCapability,
        affectedUserId: String,
        affectedServerDeviceId: String?,
    ) {
        require(CANONICAL_UUID.matches(affectedUserId)) { "Invalid retired peer user ID" }
        affectedServerDeviceId?.let {
            require(CANONICAL_UUID.matches(it)) { "Invalid retired peer device ID" }
        }
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        try {
            transactionGate.withLock {
                stateStore.withActivationLease(activation) {
                    val record = readProtocolRecord() ?: return@withActivationLease
                    var state: BoundLibSignalState? = null
                    try {
                        val decoded = decodeBoundProtocolState(record.bytes)
                        state = decoded
                        if (decoded.binding != provenance.binding) {
                            throw cryptographicFailure(
                                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                                "Secure messaging protocol state belongs to another authentication epoch",
                            )
                        }
                        if (
                            decoded.retireRemoteDevices(
                                affectedUserId,
                                affectedServerDeviceId,
                            ) == 0
                        ) {
                            return@withActivationLease
                        }
                        val encoded = BoundLibSignalStateCodec.encode(decoded)
                        try {
                            stateStore.write(
                                namespace = PROTOCOL_NAMESPACE,
                                recordKey = PROTOCOL_RECORD_KEY,
                                expectedVersion = record.version,
                                bytes = encoded,
                            )
                        } catch (error: SecureMessagingStateUnavailableException) {
                            throw cryptographicFailure(
                                SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                                "Retired secure-messaging peer state could not be committed",
                                error,
                            )
                        } finally {
                            encoded.fill(0)
                        }
                    } finally {
                        state?.close()
                        record.bytes.fill(0)
                    }
                }
            }
        } catch (error: SecureMessagingCryptographicFailureException) {
            runCatching { provenance.quarantine(error.quarantineReason) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /** Public-key-only enrollment state used to reconcile this installation with the server. */
    internal suspend fun localEnrollment(
        activation: SecureMessagingActivationCapability,
    ): LibSignalLocalEnrollment? {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        return try {
            transactionGate.withLock {
                stateStore.withActivationLease(activation) {
                    val record = readEnrollmentProtocolRecord()
                        ?: return@withActivationLease null
                    var state: BoundLibSignalState? = null
                    try {
                        val decoded = decodeBoundProtocolState(record.bytes)
                        state = decoded
                        if (decoded.binding != provenance.binding) {
                            throw cryptographicFailure(
                                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                                "Secure messaging protocol state belongs to another authentication epoch",
                            )
                        }
                        val identity = decoded.store.identityKeyPair.publicKey.serialize()
                        val snapshot = decoded.store.snapshot()
                        try {
                            val pendingBytes = decoded.copyPendingPublicationBytes()
                            val pendingPublication = try {
                                pendingBytes?.let { encoded ->
                                    val pending = try {
                                        PendingLibSignalPublicationCodec.decode(encoded)
                                    } catch (error: Exception) {
                                        throw cryptographicFailure(
                                            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                                            "Pending key publication state is corrupt",
                                            error,
                                        )
                                    }
                                    SecureMessagingCryptoWireMapper.publicationFromDurableBundle(
                                        bundle = pending.bundle,
                                        identityKeyChange = pending.identityKeyChange,
                                        activation = activation,
                                    )
                                }
                            } finally {
                                pendingBytes?.fill(0)
                            }
                            LibSignalLocalEnrollment(
                                registrationId = decoded.store.localRegistrationId,
                                identityKeySha256 = sha256Hex(identity),
                                ecOneTimePrekeyIds = snapshot.preKeys.keys,
                                signedPrekeyIds = snapshot.signedPreKeys.keys,
                                pqPrekeyIds = snapshot.kyberPreKeys.keys,
                                pqLastResortPrekeyIds = snapshot.lastResortKyberPreKeyIds,
                                pendingPublication = pendingPublication,
                            )
                        } finally {
                            identity.fill(0)
                            snapshot.close()
                        }
                    } finally {
                        state?.close()
                        record.bytes.fill(0)
                    }
                }
            }
        } catch (error: SecureMessagingCryptographicFailureException) {
            runCatching { provenance.quarantine(error.quarantineReason) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /** Clears a crash-retry publication only after the server confirms the same enrollment. */
    internal suspend fun confirmLocalEnrollmentPublication(
        activation: SecureMessagingActivationCapability,
        registrationId: Int,
        identityKeySha256: String,
    ) {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(activation)
        try {
            transactionGate.withLock {
                stateStore.withActivationLease(activation) {
                    val record = readProtocolRecord() ?: throw cryptographicFailure(
                        SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                        "Confirmed server enrollment has no local protocol state",
                    )
                    var state: BoundLibSignalState? = null
                    try {
                        val decoded = decodeBoundProtocolState(record.bytes)
                        state = decoded
                        if (decoded.binding != provenance.binding) {
                            throw cryptographicFailure(
                                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                                "Confirmed enrollment belongs to another authentication epoch",
                            )
                        }
                        check(decoded.store.localRegistrationId == registrationId) {
                            "Confirmed server registration differs from local state"
                        }
                        val identity = decoded.store.identityKeyPair.publicKey.serialize()
                        try {
                            check(sha256Hex(identity) == identityKeySha256) {
                                "Confirmed server identity differs from local state"
                            }
                        } finally {
                            identity.fill(0)
                        }
                        if (!decoded.clearPendingPublication()) {
                            return@withActivationLease
                        }
                        val encoded = BoundLibSignalStateCodec.encode(decoded)
                        try {
                            stateStore.write(
                                namespace = PROTOCOL_NAMESPACE,
                                recordKey = PROTOCOL_RECORD_KEY,
                                expectedVersion = record.version,
                                bytes = encoded,
                            )
                        } finally {
                            encoded.fill(0)
                        }
                    } finally {
                        state?.close()
                        record.bytes.fill(0)
                    }
                }
            }
        } catch (error: SecureMessagingCryptographicFailureException) {
            runCatching { provenance.quarantine(error.quarantineReason) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private suspend fun readEnrollmentProtocolRecord(): SecureMessagingRecord? = try {
        stateStore.read(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY)
    } catch (error: SecureMessagingStateUnavailableException) {
        // This read does not decrypt messages or mutate ratchets. Let key activation revoke the
        // server enrollment safely without permanently quarantining a retryable logout attempt.
        throw SecureMessagingLocalEnrollmentUnavailableException(
            "Secure messaging enrollment state is unavailable",
            error,
        )
    }

    private suspend fun loadProtocolState(binding: SecureMessagingSessionBinding): LoadedProtocolState {
        val record = readProtocolRecord()
            ?: return LoadedProtocolState(
                state = BoundLibSignalState.create(binding),
                expectedVersion = null,
            )
        return try {
            val decoded = decodeBoundProtocolState(record.bytes)
            try {
                if (decoded.binding != binding) {
                    throw cryptographicFailure(
                        SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                        "Secure messaging protocol state belongs to another authentication epoch",
                    )
                }
                LoadedProtocolState(decoded, record.version)
            } catch (error: Throwable) {
                decoded.close()
                throw error
            }
        } finally {
            record.bytes.fill(0)
        }
    }

    private suspend fun readProtocolRecord(): SecureMessagingRecord? = try {
        stateStore.read(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY)
    } catch (error: SecureMessagingStateUnavailableException) {
        throw cryptographicFailure(
            SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
            "Secure messaging protocol state is unavailable",
            error,
        )
    }

    private fun decodeBoundProtocolState(bytes: ByteArray): BoundLibSignalState = try {
        BoundLibSignalStateCodec.decode(bytes)
    } catch (error: SecureMessagingCryptographicFailureException) {
        throw error
    } catch (error: Exception) {
        throw cryptographicFailure(
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
            "Secure messaging protocol state failed authenticated structural validation",
            error,
        )
    }
}

/** A storage availability failure while reading only the local enrollment snapshot. */
internal class SecureMessagingLocalEnrollmentUnavailableException(
    message: String,
    cause: Throwable,
) : java.io.IOException(message, cause)

internal class LibSignalLocalEnrollment(
    val registrationId: Int,
    val identityKeySha256: String,
    ecOneTimePrekeyIds: Collection<Int>,
    signedPrekeyIds: Collection<Int>,
    pqPrekeyIds: Collection<Int>,
    pqLastResortPrekeyIds: Collection<Int>,
    val pendingPublication: SecureMessagingKeyPublication?,
) {
    private val immutableEcOneTimePrekeyIds = ecOneTimePrekeyIds.sorted()
    private val immutableSignedPrekeyIds = signedPrekeyIds.sorted()
    private val immutablePqPrekeyIds = pqPrekeyIds.sorted()
    private val immutablePqLastResortPrekeyIds = pqLastResortPrekeyIds.sorted()

    fun ecOneTimePrekeyIds(): List<Int> = immutableEcOneTimePrekeyIds.toList()

    fun signedPrekeyIds(): List<Int> = immutableSignedPrekeyIds.toList()

    fun pqPrekeyIds(): List<Int> = immutablePqPrekeyIds.toList()

    fun pqLastResortPrekeyIds(): List<Int> = immutablePqLastResortPrekeyIds.toList()

    /**
     * Crash recovery when local provisioning committed but publication did not. Reprovisioning
     * keeps all old inbound prekeys while preparing a fresh bundle for the same identity.
     */
    fun replenishmentPlan(
        ecOneTimePrekeyCount: Int,
        pqOneTimePrekeyCount: Int,
    ): SecureMessagingProvisioningPlan = SecureMessagingProvisioningPlan(
        ecOneTimePrekeyCount = ecOneTimePrekeyCount,
        pqOneTimePrekeyCount = pqOneTimePrekeyCount,
        identityKeyChange = false,
    )
}

internal enum class LibSignalCompanionDirection {
    OUTBOUND,
    INBOUND,
}

internal interface LibSignalPersistedEnvelope {
    val recipient: SecureMessagingCryptoAddress
    val kind: SecureMessagingEnvelopeKind

    fun copyCiphertextBytes(): ByteArray
}

internal interface LibSignalCompanionRecord {
    val recordNamespace: String
    val recordKey: String
    val recordVersion: Long
    val updatedAtEpochMillis: Long
    val direction: LibSignalCompanionDirection
    val messageId: String
    val clientMessageId: String
    val conversationId: String
    val rosterRevision: String
    val sender: SecureMessagingCryptoAddress
    val replyToMessageId: String?
    val authenticatedText: String

    fun ciphertextFanout(): List<LibSignalPersistedEnvelope>
}

internal class LibSignalCompanionRecordPage(
    records: List<LibSignalCompanionRecord>,
    val nextAfterRecordKey: String?,
) {
    private val immutableRecords = records.toList()

    init {
        check(immutableRecords.all { it is DecodedLibSignalCompanionRecord }) {
            "Companion page contains a record not decoded from encrypted durable state"
        }
        check(immutableRecords.zipWithNext().all { (left, right) -> left.recordKey < right.recordKey }) {
            "Durable companion records are not strictly record-key ordered"
        }
        nextAfterRecordKey?.let { cursor ->
            check(immutableRecords.lastOrNull()?.recordKey == cursor) {
                "Durable companion continuation cursor does not identify the final record"
            }
        }
    }

    fun records(): List<LibSignalCompanionRecord> = immutableRecords.toList()
}

/**
 * Reads a companion record only through the encrypted durable store. No raw-blob decoder is
 * exposed to repositories, so authenticated text cannot be materialized from pre-commit bytes.
 */
@Singleton
internal class LibSignalCompanionStateReader @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
) {
    suspend fun read(namespace: String, recordKey: String): LibSignalCompanionRecord? {
        val record = try {
            stateStore.read(namespace, recordKey)
        } catch (error: SecureMessagingStateUnavailableException) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                "Encrypted secure-message state is unavailable",
                error,
            )
        } ?: return null
        return try {
            try {
                LibSignalCompanionRecordCodec.decode(
                    bytes = record.bytes,
                    recordNamespace = record.namespace,
                    recordKey = record.recordKey,
                    recordVersion = record.version,
                    updatedAtEpochMillis = record.updatedAtEpochMillis,
                )
            } catch (error: SecureMessagingCryptographicFailureException) {
                throw error
            } catch (error: Exception) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                    "Durable secure-message companion state is corrupt",
                    error,
                )
            }
        } finally {
            record.bytes.fill(0)
        }
    }

    suspend fun readPage(
        namespace: String,
        afterRecordKey: String? = null,
        limit: Int,
    ): LibSignalCompanionRecordPage {
        val page = try {
            stateStore.readNamespacePage(namespace, afterRecordKey, limit)
        } catch (error: SecureMessagingStateUnavailableException) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                "Encrypted secure-message state is unavailable",
                error,
            )
        }
        val rawRecords = page.records()
        return try {
            val decoded = rawRecords.map { record ->
                try {
                    LibSignalCompanionRecordCodec.decode(
                        bytes = record.bytes,
                        recordNamespace = record.namespace,
                        recordKey = record.recordKey,
                        recordVersion = record.version,
                        updatedAtEpochMillis = record.updatedAtEpochMillis,
                    )
                } catch (error: SecureMessagingCryptographicFailureException) {
                    throw error
                } catch (error: Exception) {
                    throw cryptographicFailure(
                        SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                        "Durable secure-message companion state is corrupt",
                        error,
                    )
                }
            }
            LibSignalCompanionRecordPage(decoded, page.nextAfterRecordKey)
        } finally {
            rawRecords.forEach { it.bytes.fill(0) }
        }
    }
}

private class DecodedLibSignalPersistedEnvelope(
    override val recipient: SecureMessagingCryptoAddress,
    override val kind: SecureMessagingEnvelopeKind,
    ciphertext: ByteArray,
) : LibSignalPersistedEnvelope {
    private val opaqueCiphertext = OpaqueCryptoBytes.copyOf(ciphertext)

    override fun copyCiphertextBytes(): ByteArray = opaqueCiphertext.copyBytes()
}

private class DecodedLibSignalCompanionRecord(
    override val recordNamespace: String,
    override val recordKey: String,
    override val recordVersion: Long,
    override val updatedAtEpochMillis: Long,
    override val direction: LibSignalCompanionDirection,
    override val messageId: String,
    override val clientMessageId: String,
    override val conversationId: String,
    override val rosterRevision: String,
    override val sender: SecureMessagingCryptoAddress,
    override val replyToMessageId: String?,
    override val authenticatedText: String,
    ciphertextFanout: List<LibSignalPersistedEnvelope>,
) : LibSignalCompanionRecord {
    private val immutableCiphertextFanout = ciphertextFanout.toList()

    override fun ciphertextFanout(): List<LibSignalPersistedEnvelope> =
        immutableCiphertextFanout.toList()

    fun snapshot(): LibSignalCompanionRecord = DecodedLibSignalCompanionRecord(
        recordNamespace = recordNamespace,
        recordKey = recordKey,
        recordVersion = recordVersion,
        updatedAtEpochMillis = updatedAtEpochMillis,
        direction = direction,
        messageId = messageId,
        clientMessageId = clientMessageId,
        conversationId = conversationId,
        rosterRevision = rosterRevision,
        sender = sender,
        replyToMessageId = replyToMessageId,
        authenticatedText = authenticatedText,
        ciphertextFanout = immutableCiphertextFanout.map { envelope ->
            val ciphertext = envelope.copyCiphertextBytes()
            try {
                DecodedLibSignalPersistedEnvelope(
                    recipient = envelope.recipient,
                    kind = envelope.kind,
                    ciphertext = ciphertext,
                )
            } finally {
                ciphertext.fill(0)
            }
        },
    )
}

/** Rejects caller implementations and returns a fresh private decoded snapshot. */
internal fun requireDurableLibSignalCompanionRecord(
    record: LibSignalCompanionRecord,
): LibSignalCompanionRecord {
    check(record is DecodedLibSignalCompanionRecord) {
        "Companion record was not decoded from encrypted durable state"
    }
    return record.snapshot()
}

private data class LoadedProtocolState(
    val state: BoundLibSignalState,
    val expectedVersion: Long?,
)

private class LibSignalTransaction(
    private val activation: SecureMessagingActivationCapability,
    private val stateStore: SecureMessagingStateStore,
    loaded: LoadedProtocolState,
    private val releaseGate: () -> Unit,
) : FailClosedSecureMessagingCryptoTransaction(activation) {
    private var protocolState: BoundLibSignalState? = loaded.state
    private val protocolExpectedVersion = loaded.expectedVersion
    private val random = SecureRandom()
    private val gateReleased = AtomicBoolean(false)

    private var provisioningPlan: SecureMessagingProvisioningPlan? = null
    private var sessionRequest: SecureMessagingSessionEstablishmentSnapshot? = null
    private var encryptionRequest: StagedEncryption? = null
    private var decryptionRequest: SecureMessagingDecryptionRequestSnapshot? = null
    private var preparedOperation: PreparedOperation? = null
    private var preparedHandle: PreparedCommit? = null

    private data class PreparedResult(
        val handle: PreparedCommit,
        val operation: PreparedOperation,
    )

    private class PreparedOperation(
        val operation: SecureMessagingCryptoOperation,
        val companion: PreparedCompanion? = null,
    ) : AutoCloseable {
        override fun close() {
            companion?.close()
        }
    }

    private class PreparedCompanion(
        val destination: PreparedDestination,
        sourceBytes: ByteArray,
    ) : AutoCloseable {
        val bytes = sourceBytes.copyOf()

        init {
            sourceBytes.fill(0)
        }

        override fun close() = bytes.fill(0)
    }

    private data class PreparedDestination(
        val namespace: String,
        val recordKey: String,
        val expectedVersion: Long?,
    )

    private class StagedEncryption(
        val plan: SecureMessagingEncryptionPlanSnapshot,
        val clientMessageId: String,
        val replyToMessageId: String?,
        sourcePlaintext: ByteArray,
    ) : AutoCloseable {
        val plaintext = sourcePlaintext.copyOf()

        init {
            sourcePlaintext.fill(0)
        }

        override fun close() = plaintext.fill(0)
    }

    override suspend fun stageProvisioningMaterial(plan: SecureMessagingProvisioningPlan) {
        provisioningPlan = plan.copy()
    }

    override suspend fun stageSessionMaterial(request: SecureMessagingSessionEstablishmentRequest) {
        sessionRequest = SecureMessagingCryptoWireMapper.requireSessionEstablishment(request)
    }

    override suspend fun findMissingSessionAddresses(
        plan: SecureMessagingEncryptionPlan,
        candidates: List<SecureMessagingCryptoAddress>,
    ): Collection<SecureMessagingCryptoAddress> {
        val state = requireState()
        val planSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
        verifyLocalEnrollmentMatchesRoster(state, planSnapshot, planSnapshot.sender)
        return candidates.sortedWith(CRYPTO_ADDRESS_ORDER).filter { address ->
            try {
                state.assertRemoteDeviceBindingIfPresent(address)
            } catch (error: IllegalStateException) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                    "A Signal address was reassigned to another server device",
                    error,
                )
            }
            val protocolAddress = address.toSignalAddress()
            if (!state.store.containsSession(protocolAddress)) {
                true
            } else {
                val session = state.store.loadSession(protocolAddress)
                val rosterBinding = checkNotNull(planSnapshot.deviceBinding(address)) {
                    "A recipient is absent from the authoritative roster"
                }
                val identity = session.remoteIdentityKey?.serialize() ?: throw cryptographicFailure(
                    SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                    "A persisted recipient session has no remote identity",
                )
                try {
                    if (!identityDigestMatches(identity, rosterBinding.identityKeySha256)) {
                        throw cryptographicFailure(
                            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                            "A recipient identity changed while an existing session was active",
                        )
                    }
                } finally {
                    identity.fill(0)
                }
                if (!session.hasSenderChain(REQUIRED_PQ_RATIO) ||
                    session.remoteRegistrationId != rosterBinding.registrationId
                ) {
                    // A registration rotation with the same pinned identity requires fresh PQXDH.
                    // The isolated transaction removes the obsolete session only if the matching
                    // replacement bundle is later committed atomically.
                    state.store.deleteSession(protocolAddress)
                    true
                } else {
                    false
                }
            }
        }
    }

    override suspend fun stageEncryptionMaterial(request: SecureMessagingEncryptionRequest) {
        val plaintext = request.copyPlaintextBytes()
        encryptionRequest = try {
            StagedEncryption(
                plan = request.planSnapshot.snapshot(),
                clientMessageId = request.clientMessageId,
                replyToMessageId = request.replyToMessageId,
                sourcePlaintext = plaintext,
            )
        } catch (error: Throwable) {
            plaintext.fill(0)
            throw error
        }
    }

    override suspend fun stageDecryptionMaterial(request: SecureMessagingDecryptionRequest) {
        decryptionRequest = SecureMessagingCryptoWireMapper.requireDecryptionRequest(request)
    }

    override suspend fun prepareStaged(
        operation: SecureMessagingCryptoOperation,
        companionStateIntent: SecureMessagingCompanionStateIntent?,
    ): PreparedCommit {
        check(preparedOperation == null && preparedHandle == null) {
            "A libsignal transaction may prepare exactly one operation"
        }
        val prepared = when (operation) {
            SecureMessagingCryptoOperation.PROVISION -> prepareProvisioning()
            SecureMessagingCryptoOperation.ESTABLISH_SESSIONS -> prepareSessions()
            SecureMessagingCryptoOperation.ENCRYPT -> prepareEncryption(
                checkNotNull(companionStateIntent),
            )
            SecureMessagingCryptoOperation.DECRYPT -> prepareDecryption(
                checkNotNull(companionStateIntent),
            )
        }
        preparedOperation = prepared.operation
        preparedHandle = prepared.handle
        return prepared.handle
    }

    override suspend fun commitPrepared(
        operation: SecureMessagingCryptoOperation,
        preparedResult: PreparedCommit,
    ) {
        check(preparedResult === preparedHandle) {
            "Prepared libsignal result belongs to another transaction"
        }
        val prepared = checkNotNull(preparedOperation) { "Libsignal operation was not prepared" }
        check(prepared.operation == operation) { "Prepared libsignal operation changed before commit" }
        val stateBytes = BoundLibSignalStateCodec.encode(requireState())
        try {
            val writes = buildList {
                add(
                    SecureMessagingStateWrite(
                        namespace = PROTOCOL_NAMESPACE,
                        recordKey = PROTOCOL_RECORD_KEY,
                        expectedVersion = protocolExpectedVersion,
                        bytes = stateBytes,
                    ),
                )
                prepared.companion?.let { companion ->
                    add(
                        SecureMessagingStateWrite(
                            namespace = companion.destination.namespace,
                            recordKey = companion.destination.recordKey,
                            expectedVersion = companion.destination.expectedVersion,
                            bytes = companion.bytes,
                        ),
                    )
                }
            }
            try {
                stateStore.withActivationLease(activation) {
                    stateStore.writeBatch(writes)
                }
            } catch (error: SecureMessagingStateUnavailableException) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                    "Secure messaging state could not be committed",
                    error,
                )
            } finally {
                // A stale activation can be rejected before writeBatch takes ownership. Wipe the
                // staged copies here as well; SecureMessagingStateWrite cleanup is idempotent.
                writes.forEach(SecureMessagingStateWrite::wipeBytes)
            }
        } finally {
            stateBytes.fill(0)
        }
    }

    override suspend fun abortStaged() = Unit

    override fun wipeStagedSecrets() {
        provisioningPlan = null
        sessionRequest = null
        encryptionRequest?.close()
        encryptionRequest = null
        decryptionRequest = null
        preparedOperation?.close()
        preparedOperation = null
        preparedHandle = null
        protocolState?.close()
        protocolState = null
        if (gateReleased.compareAndSet(false, true)) releaseGate()
    }

    private fun prepareProvisioning(): PreparedResult {
        val plan = checkNotNull(provisioningPlan) { "Provisioning material was not staged" }
        val state = requireState()
        if (plan.identityKeyChange) {
            state.store.replaceLocalIdentity(
                IdentityKeyPair.generate(),
                KeyHelper.generateRegistrationId(false),
            )
            state.clearRemoteDeviceBindings()
        }

        val identity = state.store.identityKeyPair
        val now = System.currentTimeMillis()
        val signedPrekey = generateSignedPrekey(state.store, identity, now)
        val oneTimePrekeys = List(plan.ecOneTimePrekeyCount) {
            generateEcOneTimePrekey(state.store)
        }
        val pqPrekeys = List(plan.pqOneTimePrekeyCount) {
            generatePqPrekey(state.store, identity, now, lastResort = false)
        }
        val pqLastResort = generatePqPrekey(state.store, identity, now, lastResort = true)
        val identityPublic = identity.publicKey.serialize()
        val bundle = try {
            SecureMessagingLocalPublicBundle(
                registrationId = state.store.localRegistrationId,
                identityKey = OpaqueCryptoBytes.copyOf(identityPublic),
                signedPrekey = signedPrekey,
                oneTimePrekeys = oneTimePrekeys,
                pqPrekeys = pqPrekeys,
                pqLastResortPrekey = pqLastResort,
            )
        } finally {
            identityPublic.fill(0)
        }
        val handle = preparedProvisioning(bundle)
        val pendingPublication = PendingLibSignalPublicationCodec.encode(
            PendingLibSignalPublication(bundle, plan.identityKeyChange),
        )
        try {
            state.replacePendingPublication(pendingPublication)
        } finally {
            pendingPublication.fill(0)
        }
        return PreparedResult(handle, PreparedOperation(SecureMessagingCryptoOperation.PROVISION))
    }

    private fun prepareSessions(): PreparedResult {
        val request = checkNotNull(sessionRequest) { "Session-establishment material was not staged" }
        val state = requireState()
        val local = request.localSender
        state.bindLocalAddress(local)
        verifyLocalEnrollmentMatchesRoster(state, request.plan, local)
        val localAddress = local.toSignalAddress()
        val addresses = request.bundles().sortedWith { left, right ->
            CRYPTO_ADDRESS_ORDER.compare(left.address, right.address)
        }
            .map { remote ->
                val authoritative = checkNotNull(request.plan.deviceBinding(remote.address)) {
                    "A claimed remote key bundle is absent from the authoritative roster"
                }
                check(authoritative.registrationId == remote.registrationId) {
                    "A claimed remote registration ID differs from the authoritative roster"
                }
                val identity = remote.identityKey.copyBytes()
                try {
                    if (!identityDigestMatches(identity, authoritative.identityKeySha256)) {
                        throw cryptographicFailure(
                            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                            "A consumed key bundle identity differs from the authoritative roster",
                        )
                    }
                } finally {
                    identity.fill(0)
                }
                establishSession(state, localAddress, remote)
                remote.address
            }
        val handle = preparedSessionsEstablished(
            request.conversationId,
            request.rosterRevision,
            addresses,
        )
        return PreparedResult(
            handle,
            PreparedOperation(SecureMessagingCryptoOperation.ESTABLISH_SESSIONS),
        )
    }

    private fun prepareEncryption(intent: SecureMessagingCompanionStateIntent): PreparedResult {
        val request = checkNotNull(encryptionRequest) { "Encryption material was not staged" }
        val state = requireState()
        val local = request.plan.sender
        state.bindLocalAddress(local)
        verifyLocalEnrollmentMatchesRoster(state, request.plan, local)
        val localAddress = local.toSignalAddress()
        val envelopes = request.plan.recipients.addresses()
            .sortedWith(CRYPTO_ADDRESS_ORDER)
            .map { recipient ->
                try {
                    state.requireRemoteDeviceBinding(recipient)
                } catch (error: IllegalStateException) {
                    throw cryptographicFailure(
                        SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                        "A recipient device no longer matches its committed Signal address",
                        error,
                    )
                }
                val remoteAddress = recipient.toSignalAddress()
                val session = state.store.loadSession(remoteAddress)
                check(session.hasSenderChain(REQUIRED_PQ_RATIO)) {
                    "A recipient session has no usable PQ sender chain"
                }
                verifySessionMatchesRoster(
                    session = session,
                    rosterBinding = checkNotNull(request.plan.deviceBinding(recipient)) {
                        "A recipient is absent from the authoritative roster"
                    },
                )
                val ciphertext = SessionCipher(
                    state.store,
                    state.store,
                    state.store,
                    state.store,
                    state.store,
                    localAddress,
                    remoteAddress,
                ).encrypt(request.plaintext)
                val kind = when (ciphertext.type) {
                    CiphertextMessage.PREKEY_TYPE -> SecureMessagingEnvelopeKind.PREKEY
                    CiphertextMessage.WHISPER_TYPE -> SecureMessagingEnvelopeKind.SESSION
                    else -> error("Libsignal produced an unsupported direct-message kind")
                }
                val serialized = ciphertext.serialize()
                try {
                    SecureMessagingPreparedEnvelope(
                        recipient = recipient,
                        kind = kind,
                        ciphertext = OpaqueCryptoBytes.copyOf(serialized),
                    )
                } finally {
                    serialized.fill(0)
                }
            }
        val fanout = SecureMessagingPreparedFanout(
            conversationId = request.plan.conversationId,
            clientMessageId = request.clientMessageId,
            rosterRevision = request.plan.rosterRevision,
            recipients = request.plan.recipients,
            envelopes = envelopes,
            replyToMessageId = request.replyToMessageId,
        )
        val companion = PreparedCompanion(
            destination = resolveCompanionDestination(intent),
            sourceBytes = LibSignalCompanionRecordCodec.encode(
                operation = SecureMessagingCryptoOperation.ENCRYPT,
                messageId = request.clientMessageId,
                clientMessageId = request.clientMessageId,
                conversationId = request.plan.conversationId,
                rosterRevision = request.plan.rosterRevision,
                sender = local,
                replyToMessageId = request.replyToMessageId,
                plaintext = request.plaintext,
                envelopes = fanout.envelopes,
            ),
        )
        val handle = try {
            preparedEncryption(fanout)
        } catch (error: Throwable) {
            companion.close()
            throw error
        }
        return PreparedResult(
            handle,
            PreparedOperation(SecureMessagingCryptoOperation.ENCRYPT, companion),
        )
    }

    private fun prepareDecryption(intent: SecureMessagingCompanionStateIntent): PreparedResult {
        val request = checkNotNull(decryptionRequest) { "Decryption material was not staged" }
        val state = requireState()
        val local = request.localRecipient
        state.bindLocalAddress(local)
        val remoteAddress = request.sender.toSignalAddress()
        val localAddress = local.toSignalAddress()
        val ciphertext = request.copyCiphertextBytes()
        val plaintext = try {
            classifyLibSignalFailure {
                when (request.envelopeKind) {
                    SecureMessagingEnvelopeKind.PREKEY -> decryptPrekey(
                        state,
                        request,
                        localAddress,
                        remoteAddress,
                        ciphertext,
                    )
                    SecureMessagingEnvelopeKind.SESSION -> decryptSession(
                        state,
                        request,
                        localAddress,
                        remoteAddress,
                        ciphertext,
                    )
                }
            }
        } finally {
            ciphertext.fill(0)
        }
        try {
            val companion = PreparedCompanion(
                destination = resolveCompanionDestination(intent),
                sourceBytes = LibSignalCompanionRecordCodec.encode(
                    operation = SecureMessagingCryptoOperation.DECRYPT,
                    messageId = request.messageId,
                    clientMessageId = request.clientMessageId,
                    conversationId = request.conversationId,
                    rosterRevision = request.rosterRevision,
                    sender = request.sender,
                    replyToMessageId = request.replyToMessageId,
                    plaintext = plaintext,
                    envelopes = emptyList(),
                ),
            )
            val handle = try {
                try {
                    preparedDecryption(
                        messageId = request.messageId,
                        conversationId = request.conversationId,
                        sender = request.sender,
                        plaintext = plaintext,
                        isHistoryBackfill = request.isHistoryBackfill,
                    )
                } catch (error: SecureMessagingCryptographicFailureException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    throw cryptographicFailure(
                        SecureMessagingQuarantineReason.MALFORMED_WIRE_DATA,
                        "Decrypted secure-message content failed authenticated validation",
                        error,
                    )
                }
            } catch (error: Throwable) {
                companion.close()
                throw error
            }
            return PreparedResult(
                handle,
                PreparedOperation(SecureMessagingCryptoOperation.DECRYPT, companion),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun establishSession(
        state: BoundLibSignalState,
        localAddress: SignalProtocolAddress,
        remote: SecureMessagingRemoteKeyBundle,
    ) {
        try {
            state.assertRemoteDeviceBindingIfPresent(remote.address)
        } catch (error: IllegalStateException) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                "A Signal address was reassigned to another server device",
                error,
            )
        }
        val remoteAddress = remote.address.toSignalAddress()
        val identityBytes = remote.identityKey.copyBytes()
        val signedBytes = remote.signedPrekey.publicKey.copyBytes()
        val signedSignature = checkNotNull(remote.signedPrekey.signature).copyBytes()
        val oneTimeBytes = remote.oneTimePrekey?.publicKey?.copyBytes()
        val pqBytes = remote.pqPrekey.publicKey.copyBytes()
        val pqSignature = checkNotNull(remote.pqPrekey.signature).copyBytes()
        try {
            val identity = IdentityKey(identityBytes)
            val signed = ECPublicKey(signedBytes)
            val pq = KEMPublicKey(pqBytes)
            if (!identity.publicKey.verifySignature(signedBytes, signedSignature)) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.SIGNATURE_FAILURE,
                    "Remote signed-prekey signature verification failed",
                )
            }
            if (!identity.publicKey.verifySignature(pqBytes, pqSignature)) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.SIGNATURE_FAILURE,
                    "Remote PQ-prekey signature verification failed",
                )
            }
            state.store.getIdentity(remoteAddress)?.let { pinned ->
                val pinnedBytes = pinned.serialize()
                try {
                    if (!MessageDigest.isEqual(pinnedBytes, identityBytes)) {
                        throw cryptographicFailure(
                            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                            "Remote messaging identity changed",
                        )
                    }
                } finally {
                    pinnedBytes.fill(0)
                }
            }
            val oneTime = oneTimeBytes?.let(::ECPublicKey)
            val bundle = PreKeyBundle(
                remote.registrationId,
                remote.address.signalDeviceId,
                remote.oneTimePrekey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
                oneTime,
                remote.signedPrekey.id,
                signed,
                signedSignature,
                identity,
                remote.pqPrekey.id,
                pq,
                pqSignature,
            )
            classifyLibSignalFailure {
                SessionBuilder(
                    state.store,
                    state.store,
                    state.store,
                    state.store,
                    remoteAddress,
                    localAddress,
                ).process(bundle)
            }
            val session = state.store.loadSession(remoteAddress)
            check(session.hasSenderChain(REQUIRED_PQ_RATIO)) {
                "PQXDH did not produce a usable PQ sender chain"
            }
            check(session.remoteRegistrationId == remote.registrationId) {
                "Remote registration ID changed during session establishment"
            }
            val sessionIdentity = checkNotNull(session.remoteIdentityKey) {
                "PQXDH produced a session without a remote identity"
            }.serialize()
            try {
                if (!MessageDigest.isEqual(sessionIdentity, identityBytes)) {
                    throw cryptographicFailure(
                        SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                        "Remote identity changed during session establishment",
                    )
                }
            } finally {
                sessionIdentity.fill(0)
            }
            state.bindRemoteDevice(remote.address)
        } finally {
            identityBytes.fill(0)
            signedBytes.fill(0)
            signedSignature.fill(0)
            oneTimeBytes?.fill(0)
            pqBytes.fill(0)
            pqSignature.fill(0)
        }
    }

    private fun decryptPrekey(
        state: BoundLibSignalState,
        request: SecureMessagingDecryptionRequestSnapshot,
        localAddress: SignalProtocolAddress,
        remoteAddress: SignalProtocolAddress,
        ciphertext: ByteArray,
    ): ByteArray {
        try {
            state.assertRemoteDeviceBindingIfPresent(request.sender)
        } catch (error: IllegalStateException) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                "The sender Signal address was reassigned to another server device",
                error,
            )
        }
        val message = PreKeySignalMessage(ciphertext)
        if (message.registrationId != request.senderRegistrationId) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                "Prekey message registration differs from authenticated sender metadata",
            )
        }
        val messageIdentity = message.identityKey.serialize()
        try {
            if (!identityDigestMatches(messageIdentity, request.senderIdentityKeySha256)) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                    "Prekey message identity differs from authenticated sender metadata",
                )
            }
            state.store.getIdentity(remoteAddress)?.let { pinned ->
                val pinnedBytes = pinned.serialize()
                try {
                    if (!MessageDigest.isEqual(pinnedBytes, messageIdentity)) {
                        throw cryptographicFailure(
                            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                            "Prekey message identity does not match the pinned identity",
                        )
                    }
                } finally {
                    pinnedBytes.fill(0)
                }
            }
            val plaintext = sessionCipher(state, localAddress, remoteAddress).decrypt(message)
            verifyCommittedRemoteSession(state, request, remoteAddress)
            state.bindRemoteDevice(request.sender)
            return plaintext
        } finally {
            messageIdentity.fill(0)
        }
    }

    private fun decryptSession(
        state: BoundLibSignalState,
        request: SecureMessagingDecryptionRequestSnapshot,
        localAddress: SignalProtocolAddress,
        remoteAddress: SignalProtocolAddress,
        ciphertext: ByteArray,
    ): ByteArray {
        try {
            state.requireRemoteDeviceBinding(request.sender)
        } catch (error: IllegalStateException) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                "The sender device no longer matches its committed Signal address",
                error,
            )
        }
        verifyCommittedRemoteSession(state, request, remoteAddress)
        val message = SignalMessage(ciphertext)
        val plaintext = sessionCipher(state, localAddress, remoteAddress).decrypt(message)
        verifyCommittedRemoteSession(state, request, remoteAddress)
        return plaintext
    }

    private fun sessionCipher(
        state: BoundLibSignalState,
        localAddress: SignalProtocolAddress,
        remoteAddress: SignalProtocolAddress,
    ): SessionCipher = SessionCipher(
        state.store,
        state.store,
        state.store,
        state.store,
        state.store,
        localAddress,
        remoteAddress,
    )

    private fun verifyCommittedRemoteSession(
        state: BoundLibSignalState,
        request: SecureMessagingDecryptionRequestSnapshot,
        remoteAddress: SignalProtocolAddress,
    ) {
        check(state.store.containsSession(remoteAddress)) { "No session exists for the message sender" }
        val session = state.store.loadSession(remoteAddress)
        check(session.hasSenderChain(REQUIRED_PQ_RATIO)) {
            "The message sender session has no usable PQ sender chain"
        }
        if (session.remoteRegistrationId != request.senderRegistrationId) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                "Message sender registration ID does not match the committed session",
            )
        }
        val identity = checkNotNull(session.remoteIdentityKey) {
            "The committed session has no remote identity"
        }.serialize()
        try {
            if (!identityDigestMatches(identity, request.senderIdentityKeySha256)) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                    "Message sender identity differs from authenticated roster metadata",
                )
            }
            val pinned = checkNotNull(state.store.getIdentity(remoteAddress)) {
                "The message sender has no pinned identity"
            }.serialize()
            try {
                if (!MessageDigest.isEqual(identity, pinned)) {
                    throw cryptographicFailure(
                        SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                        "The committed session and pinned sender identity disagree",
                    )
                }
            } finally {
                pinned.fill(0)
            }
        } finally {
            identity.fill(0)
        }
    }

    private fun generateSignedPrekey(
        store: LibSignalProtocolStore,
        identity: IdentityKeyPair,
        timestamp: Long,
    ): SecureMessagingPublicPrekey {
        // The backend rejects a rotated signed prekey whose ID does not strictly increase.
        // Random selection is safe for one-time keys but would make replenishment fail roughly
        // half the time, so rotation IDs advance monotonically over every locally retained key.
        val id = nextMonotonicPrekeyId(
            currentMaximum = store.signedPreKeyIds().maxOrNull(),
            inUse = store::containsSignedPreKey,
        )
        val keyPair = ECKeyPair.generate()
        val publicBytes = keyPair.publicKey.serialize()
        val signature = identity.privateKey.calculateSignature(publicBytes)
        try {
            store.storeSignedPreKey(id, SignedPreKeyRecord(id, timestamp, keyPair, signature))
            return SecureMessagingPublicPrekey(
                id,
                OpaqueCryptoBytes.copyOf(publicBytes),
                OpaqueCryptoBytes.copyOf(signature),
            )
        } finally {
            publicBytes.fill(0)
            signature.fill(0)
        }
    }

    private fun generateEcOneTimePrekey(store: LibSignalProtocolStore): SecureMessagingPublicPrekey {
        val id = nextPrekeyId(store::containsPreKey)
        val keyPair = ECKeyPair.generate()
        val publicBytes = keyPair.publicKey.serialize()
        try {
            store.storePreKey(id, PreKeyRecord(id, keyPair))
            return SecureMessagingPublicPrekey(id, OpaqueCryptoBytes.copyOf(publicBytes))
        } finally {
            publicBytes.fill(0)
        }
    }

    private fun generatePqPrekey(
        store: LibSignalProtocolStore,
        identity: IdentityKeyPair,
        timestamp: Long,
        lastResort: Boolean,
    ): SecureMessagingPublicPrekey {
        // The last-resort PQ key is a rotating bundle key and has the same strictly-increasing
        // server contract as the signed prekey. Ordinary PQ one-time keys remain random.
        val id = if (lastResort) {
            nextMonotonicPrekeyId(
                currentMaximum = store.lastResortKyberPreKeyIds().maxOrNull(),
                inUse = store::containsKyberPreKey,
            )
        } else {
            nextPrekeyId(store::containsKyberPreKey)
        }
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val publicBytes = keyPair.publicKey.serialize()
        val signature = identity.privateKey.calculateSignature(publicBytes)
        try {
            store.storeKyberPreKey(id, KyberPreKeyRecord(id, timestamp, keyPair, signature))
            if (lastResort) store.markLastResortKyberPreKey(id)
            return SecureMessagingPublicPrekey(
                id,
                OpaqueCryptoBytes.copyOf(publicBytes),
                OpaqueCryptoBytes.copyOf(signature),
            )
        } finally {
            publicBytes.fill(0)
            signature.fill(0)
        }
    }

    private fun nextPrekeyId(inUse: (Int) -> Boolean): Int {
        repeat(MAX_PREKEY_ID + 1) {
            val candidate = random.nextInt(MAX_PREKEY_ID + 1)
            if (!inUse(candidate)) return candidate
        }
        error("The secure messaging prekey ID space is exhausted")
    }

    private fun nextMonotonicPrekeyId(
        currentMaximum: Int?,
        inUse: (Int) -> Boolean,
    ): Int {
        var candidate = (currentMaximum ?: -1).toLong() + 1L
        while (candidate <= MAX_PREKEY_ID && inUse(candidate.toInt())) candidate++
        check(candidate <= MAX_PREKEY_ID) {
            "The secure messaging rotating prekey ID space is exhausted"
        }
        return candidate.toInt()
    }

    private fun requireState(): BoundLibSignalState = checkNotNull(protocolState) {
        "Libsignal transaction state was already wiped"
    }

    private fun resolveCompanionDestination(
        intent: SecureMessagingCompanionStateIntent,
    ): PreparedDestination = companionStateDestination(intent).let { destination ->
        PreparedDestination(
            namespace = destination.namespace,
            recordKey = destination.recordKey,
            expectedVersion = destination.expectedVersion,
        )
    }

    private fun verifySessionMatchesRoster(
        session: org.signal.libsignal.protocol.state.SessionRecord,
        rosterBinding: SecureMessagingRosterDeviceBinding,
    ) {
        if (session.remoteRegistrationId != rosterBinding.registrationId) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "Recipient registration ID differs from the authoritative roster",
            )
        }
        val identity = checkNotNull(session.remoteIdentityKey) {
            "The recipient session has no remote identity"
        }.serialize()
        try {
            if (!identityDigestMatches(identity, rosterBinding.identityKeySha256)) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                    "Recipient identity differs from the authoritative roster",
                )
            }
        } finally {
            identity.fill(0)
        }
    }

    private fun verifyLocalEnrollmentMatchesRoster(
        state: BoundLibSignalState,
        plan: SecureMessagingEncryptionPlanSnapshot,
        local: SecureMessagingCryptoAddress,
    ) {
        val rosterBinding = checkNotNull(plan.deviceBinding(local)) {
            "The local device is absent from the authoritative roster"
        }
        if (state.store.localRegistrationId != rosterBinding.registrationId) {
            throw cryptographicFailure(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "Local registration ID differs from the authoritative roster",
            )
        }
        val identity = state.store.identityKeyPair.publicKey.serialize()
        try {
            if (!identityDigestMatches(identity, rosterBinding.identityKeySha256)) {
                throw cryptographicFailure(
                    SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                    "Local identity differs from the authoritative roster",
                )
            }
        } finally {
            identity.fill(0)
        }
    }
}

private data class ProtocolAddressKey(
    val userId: String,
    val signalDeviceId: Int,
) : Comparable<ProtocolAddressKey> {
    override fun compareTo(other: ProtocolAddressKey): Int =
        compareValuesBy(this, other, ProtocolAddressKey::userId, ProtocolAddressKey::signalDeviceId)
}

private class BoundLibSignalState(
    val binding: SecureMessagingSessionBinding,
    var localAddress: SecureMessagingCryptoAddress?,
    private val remoteServerDeviceIds: MutableMap<ProtocolAddressKey, String>,
    pendingPublicationBytes: ByteArray?,
    val store: LibSignalProtocolStore,
) : AutoCloseable {
    private var pendingPublicationBytes = pendingPublicationBytes?.copyOf()

    fun bindLocalAddress(address: SecureMessagingCryptoAddress) {
        check(address.userId == binding.userId && address.serverDeviceId == binding.serverDeviceId) {
            "Local Signal address does not match the active authentication binding"
        }
        val current = localAddress
        check(current == null || current == address) { "Local Signal address changed within one session epoch" }
        localAddress = address
    }

    fun bindRemoteDevice(address: SecureMessagingCryptoAddress) {
        val key = address.protocolKey()
        require(key in remoteServerDeviceIds || remoteServerDeviceIds.size < MAX_REMOTE_BINDINGS) {
            "Too many persisted remote device bindings"
        }
        val previous = remoteServerDeviceIds.putIfAbsent(key, address.serverDeviceId)
        check(previous == null || previous == address.serverDeviceId) {
            "A Signal address was reassigned to another server device"
        }
    }

    fun assertRemoteDeviceBindingIfPresent(address: SecureMessagingCryptoAddress) {
        remoteServerDeviceIds[address.protocolKey()]?.let { serverDeviceId ->
            check(serverDeviceId == address.serverDeviceId) {
                "A Signal address was reassigned to another server device"
            }
        }
    }

    fun requireRemoteDeviceBinding(address: SecureMessagingCryptoAddress) {
        check(remoteServerDeviceIds[address.protocolKey()] == address.serverDeviceId) {
            "The recipient device is not bound to its committed Signal address"
        }
    }

    fun remoteBindings(): Map<ProtocolAddressKey, String> = remoteServerDeviceIds.toMap()

    fun retireRemoteDevices(userId: String, serverDeviceId: String?): Int {
        val retired = remoteServerDeviceIds.filter { (address, boundServerDeviceId) ->
            address.userId == userId &&
                (serverDeviceId == null || boundServerDeviceId == serverDeviceId)
        }.keys
        retired.forEach { address ->
            val protocolAddress = SignalProtocolAddress(address.userId, address.signalDeviceId)
            store.deleteSession(protocolAddress)
            store.deleteIdentity(protocolAddress)
            remoteServerDeviceIds.remove(address)
        }
        return retired.size
    }

    fun clearRemoteDeviceBindings() = remoteServerDeviceIds.clear()

    fun copyPendingPublicationBytes(): ByteArray? = pendingPublicationBytes?.copyOf()

    fun replacePendingPublication(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PENDING_PUBLICATION_BYTES) {
            "Invalid pending key-publication record"
        }
        val replacement = bytes.copyOf()
        pendingPublicationBytes?.fill(0)
        pendingPublicationBytes = replacement
    }

    fun clearPendingPublication(): Boolean {
        val pending = pendingPublicationBytes ?: return false
        pending.fill(0)
        pendingPublicationBytes = null
        return true
    }

    override fun close() {
        pendingPublicationBytes?.fill(0)
        pendingPublicationBytes = null
        store.close()
    }

    companion object {
        fun create(binding: SecureMessagingSessionBinding): BoundLibSignalState = BoundLibSignalState(
            binding = binding,
            localAddress = null,
            remoteServerDeviceIds = mutableMapOf(),
            pendingPublicationBytes = null,
            store = LibSignalProtocolStore.create(),
        )
    }
}

private data class PendingLibSignalPublication(
    val bundle: SecureMessagingLocalPublicBundle,
    val identityKeyChange: Boolean,
)

/** Exact public bundle retained atomically with its matching private libsignal records. */
private object PendingLibSignalPublicationCodec {
    private val magic = byteArrayOf(0x4b, 0x49, 0x54, 0x4b, 0x45, 0x59, 0x32)
    private const val schema = 1
    private const val ecPublicBytes = 33
    private const val pqPublicBytes = 1_569
    private const val signatureBytes = 64
    private const val maxPrekeys = 1_000

    fun encode(publication: PendingLibSignalPublication): ByteArray {
        val output = WipeableByteArrayOutputStream()
        try {
            DataOutputStream(output).use { data ->
                data.write(magic)
                data.writeInt(schema)
                data.writeBoolean(publication.identityKeyChange)
                data.writeInt(publication.bundle.registrationId)
                data.writeOpaque(publication.bundle.identityKey, ecPublicBytes)
                data.writePrekey(publication.bundle.signedPrekey, ecPublicBytes, signatureBytes)
                val ec = publication.bundle.oneTimePrekeys
                require(ec.size in 1..maxPrekeys) { "Invalid pending EC prekey count" }
                data.writeInt(ec.size)
                ec.forEach { data.writePrekey(it, ecPublicBytes, signatureSize = null) }
                val pq = publication.bundle.pqPrekeys
                require(pq.size in 1..maxPrekeys) { "Invalid pending PQ prekey count" }
                data.writeInt(pq.size)
                pq.forEach { data.writePrekey(it, pqPublicBytes, signatureBytes) }
                data.writePrekey(
                    publication.bundle.pqLastResortPrekey,
                    pqPublicBytes,
                    signatureBytes,
                )
            }
            val encoded = output.toByteArray()
            try {
                require(encoded.size <= MAX_PENDING_PUBLICATION_BYTES) {
                    "Pending key publication is too large"
                }
                return encoded
            } catch (error: Throwable) {
                encoded.fill(0)
                throw error
            }
        } finally {
            output.wipe()
        }
    }

    fun decode(bytes: ByteArray): PendingLibSignalPublication {
        require(bytes.size in 64..MAX_PENDING_PUBLICATION_BYTES) {
            "Invalid pending key-publication size"
        }
        val owned = bytes.copyOf()
        val input = ByteArrayInputStream(owned)
        try {
            return DataInputStream(input).use { data ->
                require(data.readExact(magic.size).contentEquals(magic)) {
                    "Invalid pending key-publication header"
                }
                require(data.readInt() == schema) { "Unsupported pending key-publication schema" }
                val identityKeyChange = data.readStrictBoolean()
                val registrationId = data.readInt()
                val identity = data.readOpaque(ecPublicBytes)
                val signed = data.readPrekey(ecPublicBytes, signatureBytes)
                val ecCount = data.readBoundedCount(maxPrekeys)
                require(ecCount > 0) { "Pending key publication has no EC one-time prekeys" }
                val ec = List(ecCount) { data.readPrekey(ecPublicBytes, signatureSize = null) }
                val pqCount = data.readBoundedCount(maxPrekeys)
                require(pqCount > 0) { "Pending key publication has no PQ one-time prekeys" }
                val pq = List(pqCount) { data.readPrekey(pqPublicBytes, signatureBytes) }
                val lastResort = data.readPrekey(pqPublicBytes, signatureBytes)
                require(input.available() == 0) { "Pending key publication contains trailing bytes" }
                PendingLibSignalPublication(
                    bundle = SecureMessagingLocalPublicBundle(
                        registrationId = registrationId,
                        identityKey = identity,
                        signedPrekey = signed,
                        oneTimePrekeys = ec,
                        pqPrekeys = pq,
                        pqLastResortPrekey = lastResort,
                    ),
                    identityKeyChange = identityKeyChange,
                )
            }
        } finally {
            owned.fill(0)
        }
    }

    private fun DataOutputStream.writeOpaque(value: OpaqueCryptoBytes, expectedSize: Int) {
        val bytes = value.copyBytes()
        try {
            require(bytes.size == expectedSize) { "Invalid pending key-publication value" }
            writeBytes(bytes, expectedSize)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readOpaque(expectedSize: Int): OpaqueCryptoBytes {
        val bytes = readBytes(expectedSize)
        try {
            require(bytes.size == expectedSize) { "Invalid pending key-publication value" }
            return OpaqueCryptoBytes.copyOf(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writePrekey(
        prekey: SecureMessagingPublicPrekey,
        publicSize: Int,
        signatureSize: Int?,
    ) {
        writeInt(prekey.id)
        writeOpaque(prekey.publicKey, publicSize)
        if (signatureSize == null) {
            require(prekey.signature == null) { "Pending EC prekey unexpectedly has a signature" }
        } else {
            writeOpaque(
                checkNotNull(prekey.signature) { "Pending signed prekey has no signature" },
                signatureSize,
            )
        }
    }

    private fun DataInputStream.readPrekey(
        publicSize: Int,
        signatureSize: Int?,
    ): SecureMessagingPublicPrekey = SecureMessagingPublicPrekey(
        id = readInt(),
        publicKey = readOpaque(publicSize),
        signature = signatureSize?.let { readOpaque(it) },
    )
}

private object BoundLibSignalStateCodec {
    fun encode(state: BoundLibSignalState): ByteArray {
        val protocolBytes = state.store.serialize()
        return try {
            val output = WipeableByteArrayOutputStream()
            try {
                DataOutputStream(output).use { data ->
                    data.write(STATE_MAGIC)
                    data.writeInt(STATE_SCHEMA)
                    data.writeString(state.binding.sessionEpoch)
                    data.writeString(state.binding.userId)
                    data.writeString(state.binding.serverDeviceId)
                    data.writeString(state.binding.installationId)
                    val local = state.localAddress
                    data.writeBoolean(local != null)
                    local?.let(data::writeCryptoAddress)
                    val remotes = state.remoteBindings().toSortedMap()
                    require(remotes.size <= MAX_REMOTE_BINDINGS) { "Too many remote device bindings" }
                    data.writeInt(remotes.size)
                    remotes.forEach { (address, serverDeviceId) ->
                        data.writeString(address.userId)
                        data.writeInt(address.signalDeviceId)
                        data.writeString(serverDeviceId)
                    }
                    val pendingPublication = state.copyPendingPublicationBytes()
                    try {
                        data.writeBoolean(pendingPublication != null)
                        pendingPublication?.let {
                            data.writeBytes(it, MAX_PENDING_PUBLICATION_BYTES)
                        }
                    } finally {
                        pendingPublication?.fill(0)
                    }
                    data.writeBytes(protocolBytes, MAX_PROTOCOL_STATE_BYTES)
                }
                val encoded = output.toByteArray()
                try {
                    require(encoded.size <= MAX_BOUND_STATE_BYTES) { "Bound libsignal state is too large" }
                    encoded
                } catch (error: Throwable) {
                    encoded.fill(0)
                    throw error
                }
            } finally {
                output.wipe()
            }
        } finally {
            protocolBytes.fill(0)
        }
    }

    fun decode(bytes: ByteArray): BoundLibSignalState {
        require(bytes.size in MIN_BOUND_STATE_BYTES..MAX_BOUND_STATE_BYTES) {
            "Invalid bound libsignal state size"
        }
        val owned = bytes.copyOf()
        val input = ByteArrayInputStream(owned)
        var store: LibSignalProtocolStore? = null
        try {
            val decoded = DataInputStream(input).use { data ->
                require(data.readExact(STATE_MAGIC.size).contentEquals(STATE_MAGIC)) {
                    "Invalid bound libsignal state header"
                }
                val schema = data.readInt()
                require(schema in STATE_SCHEMA_WITHOUT_PENDING..STATE_SCHEMA) {
                    "Unsupported bound libsignal state schema"
                }
                val binding = SecureMessagingSessionBinding(
                    sessionEpoch = data.readString(),
                    userId = data.readString(),
                    serverDeviceId = data.readString(),
                    installationId = data.readString(),
                )
                val local = if (data.readStrictBoolean()) data.readCryptoAddress() else null
                local?.let {
                    require(it.userId == binding.userId && it.serverDeviceId == binding.serverDeviceId) {
                        "Persisted local Signal address does not match its authentication binding"
                    }
                }
                val count = data.readBoundedCount(MAX_REMOTE_BINDINGS)
                val remotes = buildMap(count) {
                    repeat(count) {
                        val userId = data.readString()
                        val signalDeviceId = data.readInt()
                        val serverDeviceId = data.readString()
                        val address = SecureMessagingCryptoAddress(
                            userId,
                            serverDeviceId,
                            signalDeviceId,
                        )
                        val key = address.protocolKey()
                        require(put(key, serverDeviceId) == null) {
                            "Duplicate persisted Signal address binding"
                        }
                    }
                }.toMutableMap()
                val pendingPublication = if (
                    schema >= STATE_SCHEMA_WITH_PENDING && data.readStrictBoolean()
                ) {
                    data.readBytes(MAX_PENDING_PUBLICATION_BYTES)
                } else {
                    null
                }
                val protocolBytes = data.readBytes(MAX_PROTOCOL_STATE_BYTES)
                val protocolStore = try {
                    LibSignalProtocolStore.deserialize(protocolBytes)
                } finally {
                    protocolBytes.fill(0)
                }
                store = protocolStore
                require(input.available() == 0) { "Bound libsignal state contains trailing bytes" }
                try {
                    BoundLibSignalState(
                        binding,
                        local,
                        remotes,
                        pendingPublication,
                        protocolStore,
                    )
                } finally {
                    pendingPublication?.fill(0)
                }
            }
            store = null
            return decoded
        } finally {
            store?.close()
            owned.fill(0)
        }
    }
}

private object LibSignalCompanionRecordCodec {
    fun encode(
        operation: SecureMessagingCryptoOperation,
        messageId: String,
        clientMessageId: String,
        conversationId: String,
        rosterRevision: String,
        sender: SecureMessagingCryptoAddress,
        replyToMessageId: String?,
        plaintext: ByteArray,
        envelopes: List<SecureMessagingPreparedEnvelope>,
    ): ByteArray {
        require(operation == SecureMessagingCryptoOperation.ENCRYPT || operation == SecureMessagingCryptoOperation.DECRYPT) {
            "Only message operations have companion state"
        }
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_COMPANION_PLAINTEXT_BYTES) {
            "Invalid companion plaintext size"
        }
        when (operation) {
            SecureMessagingCryptoOperation.ENCRYPT -> {
                SecureMessagingExactRecipientSet(envelopes.map(SecureMessagingPreparedEnvelope::recipient))
                require(envelopes.size == envelopes.map { it.recipient.serverDeviceId }.distinct().size) {
                    "Outbound companion state contains a duplicate recipient"
                }
            }
            SecureMessagingCryptoOperation.DECRYPT -> require(envelopes.isEmpty()) {
                "Inbound companion state cannot contain an outbound fanout"
            }
            else -> error("Unsupported companion operation")
        }
        val output = WipeableByteArrayOutputStream()
        try {
            DataOutputStream(output).use { data ->
                data.write(COMPANION_MAGIC)
                data.writeInt(COMPANION_SCHEMA)
                data.writeByte(
                    when (operation) {
                        SecureMessagingCryptoOperation.ENCRYPT -> 1
                        SecureMessagingCryptoOperation.DECRYPT -> 2
                        else -> error("Unsupported companion operation")
                    },
                )
                data.writeString(messageId)
                data.writeString(clientMessageId)
                data.writeString(conversationId)
                data.writeString(rosterRevision)
                data.writeCryptoAddress(sender)
                data.writeBoolean(replyToMessageId != null)
                replyToMessageId?.let(data::writeString)
                data.writeBytes(plaintext, MAX_COMPANION_PLAINTEXT_BYTES)
                data.writeInt(envelopes.size)
                envelopes.sortedWith { left, right ->
                    CRYPTO_ADDRESS_ORDER.compare(left.recipient, right.recipient)
                }.forEach { envelope ->
                    data.writeCryptoAddress(envelope.recipient)
                    data.writeByte(
                        when (envelope.kind) {
                            SecureMessagingEnvelopeKind.PREKEY -> 1
                            SecureMessagingEnvelopeKind.SESSION -> 2
                        },
                    )
                    val ciphertext = envelope.ciphertext.copyBytes()
                    try {
                        data.writeBytes(ciphertext, MAX_PERSISTED_ENVELOPE_BYTES)
                    } finally {
                        ciphertext.fill(0)
                    }
                }
            }
            val encoded = output.toByteArray()
            try {
                require(encoded.size <= MAX_COMPANION_RECORD_BYTES) { "Companion message state is too large" }
                return encoded
            } catch (error: Throwable) {
                encoded.fill(0)
                throw error
            }
        } finally {
            output.wipe()
        }
    }

    fun decode(
        bytes: ByteArray,
        recordNamespace: String,
        recordKey: String,
        recordVersion: Long,
        updatedAtEpochMillis: Long,
    ): LibSignalCompanionRecord {
        require(bytes.size in MIN_COMPANION_RECORD_BYTES..MAX_COMPANION_RECORD_BYTES) {
            "Invalid companion message-state size"
        }
        require(recordVersion > 0) { "Invalid durable companion-state version" }
        require(updatedAtEpochMillis > 0) { "Invalid durable companion-state timestamp" }
        val owned = bytes.copyOf()
        val input = ByteArrayInputStream(owned)
        var plaintext: ByteArray? = null
        try {
            return DataInputStream(input).use { data ->
                require(data.readExact(COMPANION_MAGIC.size).contentEquals(COMPANION_MAGIC)) {
                    "Invalid companion message-state header"
                }
                require(data.readInt() == COMPANION_SCHEMA) {
                    "Unsupported companion message-state schema"
                }
                val direction = when (data.readUnsignedByte()) {
                    1 -> LibSignalCompanionDirection.OUTBOUND
                    2 -> LibSignalCompanionDirection.INBOUND
                    else -> throw IllegalArgumentException("Invalid companion message direction")
                }
                val messageId = data.readString()
                val clientMessageId = data.readString()
                val conversationId = data.readString()
                val rosterRevision = data.readString()
                val sender = data.readCryptoAddress()
                val replyToMessageId = if (data.readStrictBoolean()) data.readString() else null
                val contentBytes = data.readBytes(MAX_COMPANION_PLAINTEXT_BYTES)
                plaintext = contentBytes
                val envelopeCount = data.readBoundedCount(MAX_COMPANION_ENVELOPES)
                val envelopes = List(envelopeCount) {
                    val recipient = data.readCryptoAddress()
                    val kind = when (data.readUnsignedByte()) {
                        1 -> SecureMessagingEnvelopeKind.PREKEY
                        2 -> SecureMessagingEnvelopeKind.SESSION
                        else -> throw IllegalArgumentException("Invalid persisted envelope kind")
                    }
                    val ciphertext = data.readBytes(MAX_PERSISTED_ENVELOPE_BYTES)
                    try {
                        DecodedLibSignalPersistedEnvelope(recipient, kind, ciphertext)
                    } finally {
                        ciphertext.fill(0)
                    }
                }
                require(input.available() == 0) {
                    "Companion message state contains trailing bytes"
                }
                when (direction) {
                    LibSignalCompanionDirection.OUTBOUND -> {
                        SecureMessagingExactRecipientSet(envelopes.map { it.recipient })
                        require(envelopes.size == envelopes.map { it.recipient.serverDeviceId }.distinct().size) {
                            "Persisted outbound fanout contains a duplicate recipient"
                        }
                    }
                    LibSignalCompanionDirection.INBOUND -> require(envelopes.isEmpty()) {
                        "Persisted inbound state cannot contain an outbound fanout"
                    }
                }
                val authenticated = if (
                    recordNamespace == SecureMessagingProjectionStore.HISTORY_COMPANION_NAMESPACE
                ) {
                    SecureMessagingAuthenticatedPlaintext.history(
                        messageId = messageId,
                        conversationId = conversationId,
                        sender = sender,
                        plaintext = contentBytes,
                    )
                } else {
                    SecureMessagingAuthenticatedPlaintext(
                        messageId = messageId,
                        conversationId = conversationId,
                        sender = sender,
                        plaintext = contentBytes,
                    )
                }
                val text = try {
                    check(authenticated.contentBinding.clientMessageId == clientMessageId) {
                        "Persisted content client message ID changed"
                    }
                    check(authenticated.contentBinding.rosterRevision == rosterRevision) {
                        "Persisted content roster revision changed"
                    }
                    check(authenticated.contentBinding.replyToMessageId == replyToMessageId) {
                        "Persisted content reply target changed"
                    }
                    if (direction == LibSignalCompanionDirection.OUTBOUND) {
                        check(messageId == clientMessageId) {
                            "Persisted outbound message ID must equal its client message ID"
                        }
                    }
                    authenticated.copyText()
                } finally {
                    authenticated.close()
                }
                DecodedLibSignalCompanionRecord(
                    recordNamespace = recordNamespace,
                    recordKey = recordKey,
                    recordVersion = recordVersion,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                    direction = direction,
                    messageId = messageId,
                    clientMessageId = clientMessageId,
                    conversationId = conversationId,
                    rosterRevision = rosterRevision,
                    sender = sender,
                    replyToMessageId = replyToMessageId,
                    authenticatedText = text,
                    ciphertextFanout = envelopes,
                )
            }
        } finally {
            plaintext?.fill(0)
            owned.fill(0)
        }
    }
}

/** Encodes only a metadata-matched, authenticated history value into the canonical projection. */
internal fun encodeRecoveredHistoryCompanionRecord(
    history: SecureMessagingAuthenticatedHistory,
): ByteArray {
    val content = encodeSecureMessagingTextContent(
        SecureMessagingTextContentBinding(
            clientMessageId = history.clientMessageId,
            conversationId = history.conversationId,
            rosterRevision = history.rosterRevision,
            sender = history.sender,
            replyToMessageId = history.replyToMessageId,
        ),
        history.text,
    )
    return try {
        LibSignalCompanionRecordCodec.encode(
            operation = SecureMessagingCryptoOperation.DECRYPT,
            messageId = history.messageId,
            clientMessageId = history.clientMessageId,
            conversationId = history.conversationId,
            rosterRevision = history.rosterRevision,
            sender = history.sender,
            replyToMessageId = history.replyToMessageId,
            plaintext = content,
            envelopes = emptyList(),
        )
    } finally {
        content.fill(0)
    }
}

private fun SecureMessagingCryptoAddress.toSignalAddress(): SignalProtocolAddress =
    SignalProtocolAddress(userId, signalDeviceId)

private fun SecureMessagingCryptoAddress.protocolKey(): ProtocolAddressKey =
    ProtocolAddressKey(userId, signalDeviceId)

private fun identityDigestMatches(identity: ByteArray, expectedHex: String): Boolean {
    val expected = expectedHex.hexToBytes()
    val actual = MessageDigest.getInstance("SHA-256").digest(identity)
    return try {
        MessageDigest.isEqual(actual, expected)
    } finally {
        actual.fill(0)
        expected.fill(0)
    }
}

private fun cryptographicFailure(
    reason: SecureMessagingQuarantineReason,
    message: String,
    cause: Throwable? = null,
): SecureMessagingCryptographicFailureException =
    SecureMessagingCryptographicFailureException(reason, message, cause)

private inline fun <T> classifyLibSignalFailure(block: () -> T): T = try {
    block()
} catch (error: SecureMessagingCryptographicFailureException) {
    throw error
} catch (error: Exception) {
    val reason = when (error) {
        is DuplicateMessageException,
        is ReusedBaseKeyException,
        -> SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK

        is UntrustedIdentityException -> SecureMessagingQuarantineReason.IDENTITY_CHANGED

        is InvalidMacException,
        is InvalidMessageException,
        -> SecureMessagingQuarantineReason.SIGNATURE_FAILURE

        is NoSessionException,
        is InvalidSessionException,
        is InvalidKeyIdException,
        -> SecureMessagingQuarantineReason.STATE_UNAVAILABLE

        is InvalidVersionException,
        is LegacyMessageException,
        is InvalidKeyException,
        is InvalidRegistrationIdException,
        is IllegalArgumentException,
        -> SecureMessagingQuarantineReason.MALFORMED_WIRE_DATA

        else -> throw error
    }
    throw cryptographicFailure(
        reason,
        "Libsignal rejected authenticated secure-message material",
        error,
    )
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).let { digest ->
        try {
            digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } finally {
            digest.fill(0)
        }
    }

private fun String.hexToBytes(): ByteArray {
    require(length == 64) { "Invalid SHA-256 digest" }
    return ByteArray(length / 2) { index ->
        val high = Character.digit(this[index * 2], 16)
        val low = Character.digit(this[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "Invalid SHA-256 digest" }
        ((high shl 4) or low).toByte()
    }
}

private fun DataOutputStream.writeCryptoAddress(address: SecureMessagingCryptoAddress) {
    writeString(address.userId)
    writeString(address.serverDeviceId)
    writeInt(address.signalDeviceId)
}

private fun DataInputStream.readCryptoAddress(): SecureMessagingCryptoAddress =
    SecureMessagingCryptoAddress(readString(), readString(), readInt())

private fun DataOutputStream.writeString(value: String) {
    writeBytes(value.toByteArray(Charsets.UTF_8), MAX_CODEC_STRING_BYTES)
}

private fun DataInputStream.readString(): String {
    val bytes = readBytes(MAX_CODEC_STRING_BYTES)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } finally {
        bytes.fill(0)
    }
}

private fun DataOutputStream.writeBytes(bytes: ByteArray, maxBytes: Int) {
    require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Invalid secure messaging record size" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBytes(maxBytes: Int): ByteArray {
    val size = readInt()
    require(size in 1..maxBytes) { "Invalid secure messaging record size" }
    return readExact(size)
}

private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

private fun DataInputStream.readStrictBoolean(): Boolean = when (readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException("Invalid secure messaging boolean")
}

private fun DataInputStream.readBoundedCount(max: Int): Int = readInt().also {
    require(it in 0..max) { "Invalid secure messaging record collection size" }
}

private class WipeableByteArrayOutputStream : OutputStream() {
    private var buffer = ByteArray(1_024)
    private var count = 0

    override fun write(value: Int) {
        ensureCapacity(1)
        buffer[count++] = value.toByte()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Invalid secure messaging output range"
        }
        ensureCapacity(length)
        bytes.copyInto(buffer, destinationOffset = count, startIndex = offset, endIndex = offset + length)
        count += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(count)

    fun wipe() {
        buffer.fill(0)
        buffer = ByteArray(0)
        count = 0
    }

    private fun ensureCapacity(additionalBytes: Int) {
        val required = count.toLong() + additionalBytes.toLong()
        require(required <= Int.MAX_VALUE) { "Secure messaging output is too large" }
        if (required <= buffer.size) return
        var capacity = maxOf(buffer.size, 1)
        while (capacity < required.toInt()) {
            val doubled = if (capacity > Int.MAX_VALUE / 2) Int.MAX_VALUE else capacity * 2
            capacity = maxOf(required.toInt(), doubled)
        }
        val replacement = ByteArray(capacity)
        buffer.copyInto(replacement, endIndex = count)
        buffer.fill(0)
        buffer = replacement
    }
}

private val CRYPTO_ADDRESS_ORDER = compareBy<SecureMessagingCryptoAddress>(
    SecureMessagingCryptoAddress::userId,
    SecureMessagingCryptoAddress::signalDeviceId,
    SecureMessagingCryptoAddress::serverDeviceId,
)

private const val REQUIRED_PQ_RATIO = 1.0
private const val MAX_PREKEY_ID = 16_777_215
private const val PROTOCOL_NAMESPACE = "libsignal-v2"
private const val PROTOCOL_RECORD_KEY = "active-protocol-state"
private val STATE_MAGIC = byteArrayOf(0x4b, 0x49, 0x54, 0x4c, 0x53, 0x42, 0x32)
private const val STATE_SCHEMA_WITHOUT_PENDING = 1
private const val STATE_SCHEMA_WITH_PENDING = 2
private const val STATE_SCHEMA = STATE_SCHEMA_WITH_PENDING
private const val MIN_BOUND_STATE_BYTES = 32
private const val MAX_BOUND_STATE_BYTES = 65 * 1024 * 1024
private const val MAX_PROTOCOL_STATE_BYTES = 64 * 1024 * 1024
private const val MAX_PENDING_PUBLICATION_BYTES = 2 * 1024 * 1024
private const val MAX_REMOTE_BINDINGS = 10_000
private val CANONICAL_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val COMPANION_MAGIC = byteArrayOf(0x4b, 0x49, 0x54, 0x4d, 0x53, 0x47, 0x32)
private const val COMPANION_SCHEMA = 1
private const val MAX_COMPANION_PLAINTEXT_BYTES = 64 * 1024
private const val MAX_PERSISTED_ENVELOPE_BYTES = 128 * 1024
private const val MAX_COMPANION_ENVELOPES = 99
private const val MIN_COMPANION_RECORD_BYTES = 64
private const val MAX_COMPANION_RECORD_BYTES = 16 * 1024 * 1024
private const val MAX_CODEC_STRING_BYTES = 512
