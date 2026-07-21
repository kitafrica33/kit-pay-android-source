package com.kit.wallet.data.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Copy-on-load libsignal store used inside one secure-messaging crypto transaction.
 *
 * The live values are serialized records rather than long-lived native handles. A transaction
 * receives an isolated copy, libsignal mutates that copy through [SignalProtocolStore], and the
 * complete deterministic snapshot is encrypted and compare-and-set with the ratchet's companion
 * message state. No method in this class writes Room directly. This direct-message-only store does
 * not implement libsignal's SenderKeyStore; callers must use the explicit SessionBuilder and
 * SessionCipher constructors instead of the combined group-capable SignalProtocolStore facade.
 */
internal class LibSignalProtocolStore private constructor(
    private var localIdentityKeyPair: ByteArray,
    private var localRegistrationId: Int,
    private val remoteIdentities: MutableMap<AddressKey, ByteArray>,
    private val preKeys: MutableMap<Int, ByteArray>,
    private val signedPreKeys: MutableMap<Int, ByteArray>,
    private val kyberPreKeys: MutableMap<Int, ByteArray>,
    private val lastResortKyberPreKeyIds: MutableSet<Int>,
    private val usedLastResortBaseKeys: MutableSet<UsedKyberBaseKey>,
    private val sessions: MutableMap<AddressKey, ByteArray>,
) : IdentityKeyStore, PreKeyStore, SessionStore, SignedPreKeyStore, KyberPreKeyStore, AutoCloseable {
    private var closed = false

    override fun getIdentityKeyPair(): IdentityKeyPair {
        checkOpen()
        return IdentityKeyPair(localIdentityKeyPair.copyOf())
    }

    override fun getLocalRegistrationId(): Int {
        checkOpen()
        return localRegistrationId
    }

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        checkOpen()
        val key = address.toKey()
        val encoded = identityKey.serialize().copyOf()
        require(encoded.size == EC_PUBLIC_KEY_BYTES) { "Invalid remote identity key length" }
        val previous = remoteIdentities[key]
        return if (previous == null) {
            putSecret(remoteIdentities, key, encoded, MAX_REMOTE_IDENTITIES, MAX_PUBLIC_KEY_BYTES)
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else if (previous.contentEquals(encoded)) {
            encoded.fill(0)
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            putSecret(remoteIdentities, key, encoded, MAX_REMOTE_IDENTITIES, MAX_PUBLIC_KEY_BYTES)
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        checkOpen()
        val pinned = remoteIdentities[address.toKey()] ?: return true
        return pinned.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        remoteIdentities[address.toKey()]?.let { IdentityKey(it.copyOf()) }

    /** Removes a retired peer identity without ever exposing its encoded key material. */
    internal fun deleteIdentity(address: SignalProtocolAddress) {
        checkOpen()
        removeSecret(remoteIdentities, address.toKey())
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        checkOpen()
        return PreKeyRecord(
            preKeys[preKeyId]?.copyOf()
                ?: throw InvalidKeyIdException("No local EC prekey for ID $preKeyId"),
        )
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        checkOpen()
        requirePreKeyId(preKeyId)
        require(record.id == preKeyId) { "EC prekey record ID changed" }
        putSecret(
            preKeys,
            preKeyId,
            record.serialize(),
            MAX_PREKEY_RECORDS,
            MAX_PROTOCOL_RECORD_BYTES,
        )
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        checkOpen()
        return preKeys.containsKey(preKeyId)
    }

    override fun removePreKey(preKeyId: Int) {
        checkOpen()
        removeSecret(preKeys, preKeyId)
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        checkOpen()
        return sessions[address.toKey()]?.let { SessionRecord(it.copyOf()) } ?: SessionRecord()
    }

    override fun loadExistingSessions(
        addresses: List<SignalProtocolAddress>,
    ): List<SessionRecord> {
        checkOpen()
        require(addresses.size <= MAX_TRANSACTION_RECIPIENTS) {
            "Too many sessions requested in one direct-message transaction"
        }
        require(addresses.distinct().size == addresses.size) {
            "A session batch cannot contain duplicate addresses"
        }
        return addresses.map { address ->
        val serialized = sessions[address.toKey()]
            ?: throw NoSessionException(address, "No session for $address")
        SessionRecord(serialized.copyOf())
        }
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        checkOpen()
        requireAddressName(name)
        return sessions.keys.asSequence()
            .filter { it.name == name }
            .map(AddressKey::deviceId)
            .filter { it != PRIMARY_SIGNAL_DEVICE_ID }
            .sorted()
            .toList()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        checkOpen()
        putSecret(
            sessions,
            address.toKey(),
            record.serialize(),
            MAX_SESSIONS,
            MAX_PROTOCOL_RECORD_BYTES,
            allowEmpty = true,
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        sessions.containsKey(address.toKey())

    override fun deleteSession(address: SignalProtocolAddress) {
        checkOpen()
        removeSecret(sessions, address.toKey())
    }

    override fun deleteAllSessions(name: String) {
        checkOpen()
        requireAddressName(name)
        sessions.keys.filter { it.name == name }.forEach { removeSecret(sessions, it) }
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        checkOpen()
        return SignedPreKeyRecord(
            signedPreKeys[signedPreKeyId]?.copyOf()
                ?: throw InvalidKeyIdException("No local signed prekey for ID $signedPreKeyId"),
        )
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        checkOpen()
        return signedPreKeys.toSortedMap().values.map { SignedPreKeyRecord(it.copyOf()) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        checkOpen()
        requirePreKeyId(signedPreKeyId)
        require(record.id == signedPreKeyId) { "Signed prekey record ID changed" }
        putSecret(
            signedPreKeys,
            signedPreKeyId,
            record.serialize(),
            MAX_SIGNED_PREKEY_RECORDS,
            MAX_PROTOCOL_RECORD_BYTES,
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        checkOpen()
        return signedPreKeys.containsKey(signedPreKeyId)
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        checkOpen()
        removeSecret(signedPreKeys, signedPreKeyId)
        removeReplayMarkers { it.signedPreKeyId == signedPreKeyId }
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        checkOpen()
        return KyberPreKeyRecord(
            kyberPreKeys[kyberPreKeyId]?.copyOf()
                ?: throw InvalidKeyIdException("No local PQ prekey for ID $kyberPreKeyId"),
        )
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        checkOpen()
        return kyberPreKeys.toSortedMap().values.map { KyberPreKeyRecord(it.copyOf()) }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        checkOpen()
        requirePreKeyId(kyberPreKeyId)
        require(record.id == kyberPreKeyId) { "PQ prekey record ID changed" }
        putSecret(
            kyberPreKeys,
            kyberPreKeyId,
            record.serialize(),
            MAX_KYBER_PREKEY_RECORDS,
            MAX_PROTOCOL_RECORD_BYTES,
        )
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        checkOpen()
        return kyberPreKeys.containsKey(kyberPreKeyId)
    }

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey,
    ) {
        checkOpen()
        if (!kyberPreKeys.containsKey(kyberPreKeyId)) {
            throw InvalidKeyIdException("No local PQ prekey for ID $kyberPreKeyId")
        }
        if (!signedPreKeys.containsKey(signedPreKeyId)) {
            throw InvalidKeyIdException("No local signed prekey for ID $signedPreKeyId")
        }
        if (kyberPreKeyId !in lastResortKyberPreKeyIds) {
            removeSecret(kyberPreKeys, kyberPreKeyId)
            return
        }
        val replayMarker = UsedKyberBaseKey(
            kyberPreKeyId = kyberPreKeyId,
            signedPreKeyId = signedPreKeyId,
            baseKey = baseKey.serialize().copyOf(),
        )
        if (replayMarker in usedLastResortBaseKeys) {
            throw ReusedBaseKeyException("A last-resort PQ prekey base key was reused")
        }
        require(usedLastResortBaseKeys.size < MAX_REPLAY_MARKERS) {
            "The last-resort PQ replay-marker budget is exhausted; rotate the key"
        }
        requireStateBudget(additionalBytes = replayMarker.baseKeyBytes().size)
        usedLastResortBaseKeys += replayMarker
    }

    /** Marks a generated PQ record as reusable only with persisted base-key replay protection. */
    fun markLastResortKyberPreKey(kyberPreKeyId: Int) {
        checkOpen()
        require(kyberPreKeys.containsKey(kyberPreKeyId)) {
            "A last-resort PQ prekey must be stored before it is marked"
        }
        require(
            kyberPreKeyId in lastResortKyberPreKeyIds ||
                lastResortKyberPreKeyIds.size < MAX_LAST_RESORT_PREKEYS,
        ) { "Too many last-resort PQ prekeys" }
        lastResortKyberPreKeyIds += kyberPreKeyId
    }

    /** Removes a retired PQ record and all replay markers that can no longer be referenced. */
    fun removeKyberPreKey(kyberPreKeyId: Int) {
        checkOpen()
        removeSecret(kyberPreKeys, kyberPreKeyId)
        lastResortKyberPreKeyIds.remove(kyberPreKeyId)
        removeReplayMarkers { it.kyberPreKeyId == kyberPreKeyId }
    }

    fun replaceLocalIdentity(identityKeyPair: IdentityKeyPair, registrationId: Int) {
        checkOpen()
        requireRegistrationId(registrationId)
        val replacement = identityKeyPair.serialize().copyOf()
        require(replacement.isNotEmpty() && replacement.size <= MAX_IDENTITY_PAIR_BYTES) {
            "Invalid local identity-key pair"
        }
        IdentityKeyPair(replacement)
        val previousIdentity = localIdentityKeyPair
        localIdentityKeyPair = replacement
        localRegistrationId = registrationId
        previousIdentity.fill(0)
        clearSecrets(preKeys)
        clearSecrets(signedPreKeys)
        clearSecrets(kyberPreKeys)
        clearSecrets(sessions)
        lastResortKyberPreKeyIds.clear()
        removeReplayMarkers { true }
    }

    /** Returns plaintext protocol state. The caller owns the result and must wipe it after encryption. */
    fun serialize(): ByteArray {
        check(!closed) { "Libsignal protocol state is closed" }
        return LibSignalProtocolStateCodec.encode(this)
    }

    fun isolatedCopy(): LibSignalProtocolStore {
        val encoded = serialize()
        return try {
            deserialize(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    fun pinnedIdentity(address: SecureMessagingCryptoAddress): ByteArray? =
        remoteIdentities[AddressKey(address.userId, address.signalDeviceId)]?.copyOf().also {
            checkOpen()
        }

    fun serializedSession(address: SecureMessagingCryptoAddress): ByteArray? =
        sessions[AddressKey(address.userId, address.signalDeviceId)]?.copyOf().also {
            checkOpen()
        }

    /** Public identifiers only; used to satisfy the server's monotonic rotation contract. */
    internal fun signedPreKeyIds(): List<Int> {
        checkOpen()
        return signedPreKeys.keys.sorted()
    }

    /** Public identifiers only; used to satisfy the server's monotonic rotation contract. */
    internal fun lastResortKyberPreKeyIds(): List<Int> {
        checkOpen()
        return lastResortKyberPreKeyIds.sorted()
    }

    override fun close() {
        if (closed) return
        closed = true
        localIdentityKeyPair.fill(0)
        clearSecrets(remoteIdentities)
        clearSecrets(preKeys)
        clearSecrets(signedPreKeys)
        clearSecrets(kyberPreKeys)
        clearSecrets(sessions)
        lastResortKyberPreKeyIds.clear()
        usedLastResortBaseKeys.forEach(UsedKyberBaseKey::close)
        usedLastResortBaseKeys.clear()
    }

    private fun <K> putSecret(
        values: MutableMap<K, ByteArray>,
        key: K,
        ownedValue: ByteArray,
        maxCount: Int,
        maxValueBytes: Int,
        allowEmpty: Boolean = false,
    ) {
        try {
            check(!closed) { "Libsignal protocol state is closed" }
            require((allowEmpty || ownedValue.isNotEmpty()) && ownedValue.size <= maxValueBytes) {
                "Invalid secure messaging protocol record size"
            }
            require(key in values || values.size < maxCount) {
                "Secure messaging state collection is too large"
            }
            val previous = values[key]
            requireStateBudget(ownedValue.size - (previous?.size ?: 0))
            values[key] = ownedValue
            previous?.fill(0)
        } catch (error: Throwable) {
            ownedValue.fill(0)
            throw error
        }
    }

    private fun <K> removeSecret(values: MutableMap<K, ByteArray>, key: K) {
        values.remove(key)?.fill(0)
    }

    private fun <K> clearSecrets(values: MutableMap<K, ByteArray>) {
        values.values.forEach { it.fill(0) }
        values.clear()
    }

    private fun removeReplayMarkers(predicate: (UsedKyberBaseKey) -> Boolean) {
        val removed = usedLastResortBaseKeys.filter(predicate)
        usedLastResortBaseKeys.removeAll(removed.toSet())
        removed.forEach(UsedKyberBaseKey::close)
    }

    private fun requireStateBudget(additionalBytes: Int) {
        require(additionalBytes <= MAX_STATE_BYTES) { "Secure messaging protocol state is too large" }
        val liveBytes = localIdentityKeyPair.size.toLong() +
            remoteIdentities.values.sumOf { it.size.toLong() } +
            preKeys.values.sumOf { it.size.toLong() } +
            signedPreKeys.values.sumOf { it.size.toLong() } +
            kyberPreKeys.values.sumOf { it.size.toLong() } +
            sessions.values.sumOf { it.size.toLong() } +
            usedLastResortBaseKeys.sumOf { it.encodedSize.toLong() }
        require(liveBytes + additionalBytes <= MAX_STATE_BYTES) {
            "Secure messaging protocol state is too large"
        }
    }

    internal fun snapshot(): Snapshot {
        checkOpen()
        requireStateBudget(additionalBytes = 0)
        return Snapshot(
            localIdentityKeyPair = localIdentityKeyPair.copyOf(),
            localRegistrationId = localRegistrationId,
            remoteIdentities = remoteIdentities.mapValues { it.value.copyOf() },
            preKeys = preKeys.mapValues { it.value.copyOf() },
            signedPreKeys = signedPreKeys.mapValues { it.value.copyOf() },
            kyberPreKeys = kyberPreKeys.mapValues { it.value.copyOf() },
            lastResortKyberPreKeyIds = lastResortKyberPreKeyIds.toSet(),
            usedLastResortBaseKeys = usedLastResortBaseKeys.mapTo(mutableSetOf()) { it.copyValue() },
            sessions = sessions.mapValues { it.value.copyOf() },
        )
    }

    internal data class Snapshot(
        val localIdentityKeyPair: ByteArray,
        val localRegistrationId: Int,
        val remoteIdentities: Map<AddressKey, ByteArray>,
        val preKeys: Map<Int, ByteArray>,
        val signedPreKeys: Map<Int, ByteArray>,
        val kyberPreKeys: Map<Int, ByteArray>,
        val lastResortKyberPreKeyIds: Set<Int>,
        val usedLastResortBaseKeys: Set<UsedKyberBaseKey>,
        val sessions: Map<AddressKey, ByteArray>,
    ) : AutoCloseable {
        override fun close() {
            localIdentityKeyPair.fill(0)
            remoteIdentities.values.forEach { it.fill(0) }
            preKeys.values.forEach { it.fill(0) }
            signedPreKeys.values.forEach { it.fill(0) }
            kyberPreKeys.values.forEach { it.fill(0) }
            usedLastResortBaseKeys.forEach(UsedKyberBaseKey::close)
            sessions.values.forEach { it.fill(0) }
        }
    }

    internal data class AddressKey(val name: String, val deviceId: Int) : Comparable<AddressKey> {
        init {
            requireAddressName(name)
            require(deviceId in 1..127) { "Invalid libsignal device ID" }
        }

        override fun compareTo(other: AddressKey): Int =
            compareValuesBy(this, other, AddressKey::name, AddressKey::deviceId)
    }

    internal class UsedKyberBaseKey(
        val kyberPreKeyId: Int,
        val signedPreKeyId: Int,
        baseKey: ByteArray,
    ) : Comparable<UsedKyberBaseKey> {
        private val immutableBaseKey = baseKey.copyOf()

        init {
            requirePreKeyId(kyberPreKeyId)
            requirePreKeyId(signedPreKeyId)
            require(immutableBaseKey.size == EC_PUBLIC_KEY_BYTES) { "Invalid PQ replay base key" }
        }

        fun baseKeyBytes(): ByteArray = immutableBaseKey.copyOf()

        val encodedSize: Int get() = immutableBaseKey.size

        fun copyValue(): UsedKyberBaseKey =
            UsedKyberBaseKey(kyberPreKeyId, signedPreKeyId, immutableBaseKey)

        fun close() {
            immutableBaseKey.fill(0)
        }

        override fun equals(other: Any?): Boolean = other is UsedKyberBaseKey &&
            kyberPreKeyId == other.kyberPreKeyId &&
            signedPreKeyId == other.signedPreKeyId &&
            immutableBaseKey.contentEquals(other.immutableBaseKey)

        override fun hashCode(): Int =
            31 * (31 * kyberPreKeyId + signedPreKeyId) + immutableBaseKey.contentHashCode()

        override fun compareTo(other: UsedKyberBaseKey): Int {
            compareValues(kyberPreKeyId, other.kyberPreKeyId).takeIf { it != 0 }?.let { return it }
            compareValues(signedPreKeyId, other.signedPreKeyId).takeIf { it != 0 }?.let { return it }
            return compareByteArrays(immutableBaseKey, other.immutableBaseKey)
        }
    }

    private fun SignalProtocolAddress.toKey(): AddressKey {
        checkOpen()
        return AddressKey(name, deviceId)
    }

    private fun checkOpen() {
        check(!closed) { "Libsignal protocol state is closed" }
    }

    companion object {
        fun create(): LibSignalProtocolStore {
            val registrationId = KeyHelper.generateRegistrationId(false)
            return LibSignalProtocolStore(
                localIdentityKeyPair = IdentityKeyPair.generate().serialize(),
                localRegistrationId = registrationId,
                remoteIdentities = mutableMapOf(),
                preKeys = mutableMapOf(),
                signedPreKeys = mutableMapOf(),
                kyberPreKeys = mutableMapOf(),
                lastResortKyberPreKeyIds = mutableSetOf(),
                usedLastResortBaseKeys = mutableSetOf(),
                sessions = mutableMapOf(),
            )
        }

        fun deserialize(bytes: ByteArray): LibSignalProtocolStore =
            LibSignalProtocolStateCodec.decode(bytes)

        internal fun restore(snapshot: Snapshot): LibSignalProtocolStore {
            requireRegistrationId(snapshot.localRegistrationId)
            // Parse eagerly so corrupt identity state fails before any protocol operation.
            IdentityKeyPair(snapshot.localIdentityKeyPair)
            val canonicalRemoteIdentities = snapshot.remoteIdentities.mapValuesTo(mutableMapOf()) {
                IdentityKey(it.value).serialize()
            }
            val canonicalPreKeys = snapshot.preKeys.mapValuesTo(mutableMapOf()) { (id, bytes) ->
                PreKeyRecord(bytes).let { record ->
                    require(record.id == id) { "Secure messaging EC prekey ID changed" }
                    record.serialize()
                }
            }
            val canonicalSignedPreKeys = snapshot.signedPreKeys
                .mapValuesTo(mutableMapOf()) { (id, bytes) ->
                    SignedPreKeyRecord(bytes).let { record ->
                        require(record.id == id) { "Secure messaging signed prekey ID changed" }
                        record.serialize()
                    }
                }
            val canonicalKyberPreKeys = snapshot.kyberPreKeys
                .mapValuesTo(mutableMapOf()) { (id, bytes) ->
                    KyberPreKeyRecord(bytes).let { record ->
                        require(record.id == id) { "Secure messaging PQ prekey ID changed" }
                        record.serialize()
                    }
                }
            val canonicalSessions = snapshot.sessions.mapValuesTo(mutableMapOf()) {
                SessionRecord(it.value).serialize()
            }
            return LibSignalProtocolStore(
                localIdentityKeyPair = snapshot.localIdentityKeyPair.copyOf(),
                localRegistrationId = snapshot.localRegistrationId,
                remoteIdentities = canonicalRemoteIdentities,
                preKeys = canonicalPreKeys,
                signedPreKeys = canonicalSignedPreKeys,
                kyberPreKeys = canonicalKyberPreKeys,
                lastResortKyberPreKeyIds = snapshot.lastResortKyberPreKeyIds.toMutableSet(),
                usedLastResortBaseKeys = snapshot.usedLastResortBaseKeys
                    .mapTo(mutableSetOf()) { it.copyValue() },
                sessions = canonicalSessions,
            ).also { restored ->
                require(restored.lastResortKyberPreKeyIds.all(restored.kyberPreKeys::containsKey)) {
                    "Secure messaging state references a missing last-resort PQ prekey"
                }
                require(restored.usedLastResortBaseKeys.all {
                    it.kyberPreKeyId in restored.lastResortKyberPreKeyIds &&
                        restored.signedPreKeys.containsKey(it.signedPreKeyId)
                }) { "Secure messaging state contains an orphaned PQ replay marker" }
                restored.requireStateBudget(additionalBytes = 0)
            }
        }
    }
}

private object LibSignalProtocolStateCodec {
    fun encode(store: LibSignalProtocolStore): ByteArray {
        val snapshot = store.snapshot()
        val output = WipingBoundedByteArrayOutputStream(MAX_STATE_BYTES)
        val data = DataOutputStream(output)
        val encoded = try {
                data.write(MAGIC)
                data.writeInt(SCHEMA_VERSION)
                data.writeByteArray(snapshot.localIdentityKeyPair, MAX_IDENTITY_PAIR_BYTES)
                data.writeInt(snapshot.localRegistrationId)
                data.writeAddressMap(
                    snapshot.remoteIdentities,
                    MAX_REMOTE_IDENTITIES,
                    MAX_PUBLIC_KEY_BYTES,
                )
                data.writeIntMap(snapshot.preKeys, MAX_PREKEY_RECORDS, MAX_PROTOCOL_RECORD_BYTES)
                data.writeIntMap(snapshot.signedPreKeys, MAX_SIGNED_PREKEY_RECORDS, MAX_PROTOCOL_RECORD_BYTES)
                data.writeIntMap(snapshot.kyberPreKeys, MAX_KYBER_PREKEY_RECORDS, MAX_PROTOCOL_RECORD_BYTES)
                data.writeIntSet(snapshot.lastResortKyberPreKeyIds, MAX_LAST_RESORT_PREKEYS)
                data.writeInt(snapshot.usedLastResortBaseKeys.size)
                require(snapshot.usedLastResortBaseKeys.size <= MAX_REPLAY_MARKERS) {
                    "Too many secure messaging PQ replay markers"
                }
                snapshot.usedLastResortBaseKeys.sorted().forEach { marker ->
                    data.writeInt(marker.kyberPreKeyId)
                    data.writeInt(marker.signedPreKeyId)
                    data.writeByteArray(marker.baseKeyBytes(), EC_PUBLIC_KEY_BYTES)
                }
                data.writeAddressMap(
                    snapshot.sessions,
                    MAX_SESSIONS,
                    MAX_PROTOCOL_RECORD_BYTES,
                    allowEmptyValues = true,
                )
                data.flush()
            output.toOwnedByteArray()
        } finally {
            output.close()
            snapshot.close()
        }
        if (encoded.size > MAX_STATE_BYTES) {
            encoded.fill(0)
            error("Secure messaging protocol state is too large")
        }
        return encoded
    }

    fun decode(bytes: ByteArray): LibSignalProtocolStore {
        require(bytes.size in (MAGIC.size + Int.SIZE_BYTES + 1)..MAX_STATE_BYTES) {
            "Invalid secure messaging protocol state size"
        }
        val input = ByteArrayInputStream(bytes)
        val snapshot = DataInputStream(input).use { data ->
            require(data.readExact(MAGIC.size).contentEquals(MAGIC)) {
                "Invalid secure messaging protocol state header"
            }
            require(data.readInt() == SCHEMA_VERSION) {
                "Unsupported secure messaging protocol state schema"
            }
            val identity = data.readByteArray(MAX_IDENTITY_PAIR_BYTES)
            val registrationId = data.readInt().also(::requireRegistrationId)
            val remoteIdentities = data.readAddressMap(MAX_REMOTE_IDENTITIES, MAX_PUBLIC_KEY_BYTES)
            val preKeys = data.readIntMap(MAX_PREKEY_RECORDS, MAX_PROTOCOL_RECORD_BYTES)
            val signedPreKeys = data.readIntMap(MAX_SIGNED_PREKEY_RECORDS, MAX_PROTOCOL_RECORD_BYTES)
            val kyberPreKeys = data.readIntMap(MAX_KYBER_PREKEY_RECORDS, MAX_PROTOCOL_RECORD_BYTES)
            val lastResort = data.readIntSet(MAX_LAST_RESORT_PREKEYS)
            val replayCount = data.readBoundedCount(MAX_REPLAY_MARKERS)
            val replayMarkers = buildSet(replayCount) {
                repeat(replayCount) {
                    val marker = LibSignalProtocolStore.UsedKyberBaseKey(
                        kyberPreKeyId = data.readInt().also(::requirePreKeyId),
                        signedPreKeyId = data.readInt().also(::requirePreKeyId),
                        baseKey = data.readByteArray(EC_PUBLIC_KEY_BYTES),
                    )
                    require(add(marker)) { "Duplicate secure messaging PQ replay marker" }
                }
            }
            val sessions = data.readAddressMap(
                MAX_SESSIONS,
                MAX_PROTOCOL_RECORD_BYTES,
                allowEmptyValues = true,
            )
            require(input.available() == 0) { "Secure messaging protocol state contains trailing bytes" }
            LibSignalProtocolStore.Snapshot(
                localIdentityKeyPair = identity,
                localRegistrationId = registrationId,
                remoteIdentities = remoteIdentities,
                preKeys = preKeys,
                signedPreKeys = signedPreKeys,
                kyberPreKeys = kyberPreKeys,
                lastResortKyberPreKeyIds = lastResort,
                usedLastResortBaseKeys = replayMarkers,
                sessions = sessions,
            )
        }
        return try {
            LibSignalProtocolStore.restore(snapshot)
        } finally {
            snapshot.close()
        }
    }

    private fun DataOutputStream.writeAddressMap(
        values: Map<LibSignalProtocolStore.AddressKey, ByteArray>,
        maxCount: Int,
        maxValueBytes: Int,
        allowEmptyValues: Boolean = false,
    ) {
        require(values.size <= maxCount) { "Secure messaging state collection is too large" }
        writeInt(values.size)
        values.toSortedMap().forEach { (address, value) ->
            writeString(address.name)
            writeInt(address.deviceId)
            writeByteArray(value, maxValueBytes, allowEmpty = allowEmptyValues)
        }
    }

    private fun DataInputStream.readAddressMap(
        maxCount: Int,
        maxValueBytes: Int,
        allowEmptyValues: Boolean = false,
    ): Map<LibSignalProtocolStore.AddressKey, ByteArray> {
        val count = readBoundedCount(maxCount)
        return buildMap(count) {
            repeat(count) {
                val key = LibSignalProtocolStore.AddressKey(readString(), readInt())
                require(put(key, readByteArray(maxValueBytes, allowEmpty = allowEmptyValues)) == null) {
                    "Duplicate secure messaging protocol address"
                }
            }
        }
    }

    private fun DataOutputStream.writeIntMap(
        values: Map<Int, ByteArray>,
        maxCount: Int,
        maxValueBytes: Int,
    ) {
        require(values.size <= maxCount) { "Secure messaging state collection is too large" }
        writeInt(values.size)
        values.toSortedMap().forEach { (id, value) ->
            requirePreKeyId(id)
            writeInt(id)
            writeByteArray(value, maxValueBytes)
        }
    }

    private fun DataInputStream.readIntMap(maxCount: Int, maxValueBytes: Int): Map<Int, ByteArray> {
        val count = readBoundedCount(maxCount)
        return buildMap(count) {
            repeat(count) {
                val id = readInt().also(::requirePreKeyId)
                require(put(id, readByteArray(maxValueBytes)) == null) {
                    "Duplicate secure messaging prekey ID"
                }
            }
        }
    }

    private fun DataOutputStream.writeIntSet(values: Set<Int>, maxCount: Int) {
        require(values.size <= maxCount) { "Secure messaging state collection is too large" }
        writeInt(values.size)
        values.sorted().forEach {
            requirePreKeyId(it)
            writeInt(it)
        }
    }

    private fun DataInputStream.readIntSet(maxCount: Int): Set<Int> {
        val count = readBoundedCount(maxCount)
        return buildSet(count) {
            repeat(count) {
                require(add(readInt().also(::requirePreKeyId))) {
                    "Duplicate secure messaging prekey ID"
                }
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        requireAddressName(value)
        writeByteArray(value.toByteArray(Charsets.UTF_8), MAX_ADDRESS_NAME_BYTES)
    }

    private fun DataInputStream.readString(): String {
        val bytes = readByteArray(MAX_ADDRESS_NAME_BYTES)
        val value = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
        requireAddressName(value)
        return value
    }

    private fun DataOutputStream.writeByteArray(
        value: ByteArray,
        maxBytes: Int,
        allowEmpty: Boolean = false,
    ) {
        require((allowEmpty || value.isNotEmpty()) && value.size <= maxBytes) {
            "Invalid secure messaging protocol record size"
        }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readByteArray(
        maxBytes: Int,
        allowEmpty: Boolean = false,
    ): ByteArray {
        val size = readInt()
        val minimumSize = if (allowEmpty) 0 else 1
        require(size in minimumSize..maxBytes) {
            "Invalid secure messaging protocol record size"
        }
        return readExact(size)
    }

    private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also {
        readFully(it)
    }

    private fun DataInputStream.readBoundedCount(maxCount: Int): Int = readInt().also {
        require(it in 0..maxCount) { "Invalid secure messaging state collection size" }
    }

}

private class WipingBoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(count < maximumBytes) { "Secure messaging protocol state is too large" }
        super.write(value)
    }

    override fun write(value: ByteArray, offset: Int, length: Int) {
        require(length >= 0 && count.toLong() + length <= maximumBytes) {
            "Secure messaging protocol state is too large"
        }
        super.write(value, offset, length)
    }

    fun toOwnedByteArray(): ByteArray = buf.copyOf(count)

    override fun close() {
        buf.fill(0)
        count = 0
        super.close()
    }
}

private fun requireAddressName(value: String) {
    require(CANONICAL_UUID.matches(value)) {
        "Invalid libsignal address name"
    }
}

private fun requireRegistrationId(value: Int) {
    require(value in 1..16_380) { "Invalid libsignal registration ID" }
}

private fun requirePreKeyId(value: Int) {
    require(value in 0..16_777_215) { "Invalid libsignal prekey ID" }
}

private fun compareByteArrays(left: ByteArray, right: ByteArray): Int {
    val common = minOf(left.size, right.size)
    for (index in 0 until common) {
        compareValues(left[index].toInt() and 0xff, right[index].toInt() and 0xff)
            .takeIf { it != 0 }
            ?.let { return it }
    }
    return compareValues(left.size, right.size)
}

private val MAGIC = byteArrayOf(0x4b, 0x49, 0x54, 0x4c, 0x53, 0x32)
private val CANONICAL_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private const val SCHEMA_VERSION = 1
private const val MAX_STATE_BYTES = 16 * 1024 * 1024
private const val MAX_IDENTITY_PAIR_BYTES = 512
private const val MAX_PUBLIC_KEY_BYTES = 4 * 1024
private const val MAX_PROTOCOL_RECORD_BYTES = 2 * 1024 * 1024
private const val MAX_ADDRESS_NAME_BYTES = 256
private const val MAX_REMOTE_IDENTITIES = 10_000
private const val MAX_PREKEY_RECORDS = 2_000
private const val MAX_SIGNED_PREKEY_RECORDS = 64
private const val MAX_KYBER_PREKEY_RECORDS = 2_000
private const val MAX_LAST_RESORT_PREKEYS = 16
private const val MAX_REPLAY_MARKERS = 20_000
private const val MAX_SESSIONS = 10_000
private const val MAX_TRANSACTION_RECIPIENTS = 100
private const val PRIMARY_SIGNAL_DEVICE_ID = 1
private const val EC_PUBLIC_KEY_BYTES = 33
