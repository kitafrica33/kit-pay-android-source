package com.kit.wallet.data.messaging

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.local.SecureMessagingRecordEntity
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SecureMessagingRecord(
    val namespace: String,
    val recordKey: String,
    val version: Long,
    val bytes: ByteArray,
    val updatedAtEpochMillis: Long = 0,
)

class SecureMessagingRecordPage(
    records: List<SecureMessagingRecord>,
    val nextAfterRecordKey: String?,
) {
    private val immutableRecords = records.toList()

    init {
        immutableRecords.forEach { record ->
            validateRecordAddress(record.namespace, record.recordKey)
            require(record.version > 0 && record.updatedAtEpochMillis > 0) {
                "Invalid secure messaging namespace-page metadata"
            }
        }
        require(immutableRecords.map(SecureMessagingRecord::namespace).distinct().size <= 1) {
            "A secure messaging namespace page cannot mix namespaces"
        }
        require(
            immutableRecords.zipWithNext().all { (left, right) ->
                left.recordKey < right.recordKey
            },
        ) { "Secure messaging namespace page must be strictly record-key ordered" }
        nextAfterRecordKey?.let { cursor ->
            require(SECURE_RECORD_ADDRESS.matches(cursor)) {
                "Invalid secure messaging namespace-page continuation cursor"
            }
            require(immutableRecords.lastOrNull()?.recordKey == cursor) {
                "A namespace-page continuation cursor must identify its final record"
            }
        }
    }

    fun records(): List<SecureMessagingRecord> = immutableRecords.toList()
}

data class SecureMessagingRecordVersion(
    val namespace: String,
    val recordKey: String,
    val version: Long,
)

class SecureMessagingStateWrite(
    val namespace: String,
    val recordKey: String,
    val expectedVersion: Long?,
    bytes: ByteArray,
) {
    private val lock = Any()
    private val immutableBytes = bytes.copyOf()
    private var consumed = false

    fun copyBytes(): ByteArray = synchronized(lock) {
        check(!consumed) { "Secure messaging state write has already been consumed" }
        immutableBytes.copyOf()
    }

    /**
     * Gives the production store one isolated snapshot and immediately destroys this object's
     * retained plaintext. A state write is deliberately single-use once persistence starts.
     */
    internal fun consumeBytes(): ByteArray = synchronized(lock) {
        check(!consumed) { "Secure messaging state write has already been consumed" }
        consumed = true
        immutableBytes.copyOf().also { immutableBytes.fill(0) }
    }

    internal fun wipeBytes() = synchronized(lock) {
        consumed = true
        immutableBytes.fill(0)
    }
}

private data class SecureMessagingStateWriteSnapshot(
    val namespace: String,
    val recordKey: String,
    val expectedVersion: Long?,
    val bytes: ByteArray,
)

class SecureMessagingStateConflictException(message: String) : IllegalStateException(message)

class SecureMessagingStateUnavailableException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

/** A provider/lock-state failure that may recover without replacing or erasing encrypted state. */
internal class SecureMessagingStateRetryableException(
    message: String,
    cause: Throwable,
) : java.io.IOException(message, cause)

interface SecureMessagingStateStore : SecureMessagingStateEraser {
    suspend fun read(namespace: String, recordKey: String): SecureMessagingRecord?

    /**
     * Returns one deterministic, record-key-ordered page of decrypted records. The caller owns and
     * must wipe every returned byte array after decoding.
     */
    suspend fun readNamespacePage(
        namespace: String,
        afterRecordKey: String? = null,
        limit: Int,
    ): SecureMessagingRecordPage

    /** `expectedVersion=null` creates a record and fails if one already exists. */
    suspend fun write(
        namespace: String,
        recordKey: String,
        expectedVersion: Long?,
        bytes: ByteArray,
    ): SecureMessagingRecordVersion

    /** Atomically commits all records or none, allowing ratchet/replay/message state to advance together. */
    suspend fun writeBatch(writes: List<SecureMessagingStateWrite>): List<SecureMessagingRecordVersion>

    suspend fun deleteNamespace(namespace: String)

    /** Cryptographically erases all device-local messaging state before a Kit session is removed. */
    override suspend fun eraseAll()
}

fun interface SecureMessagingStateEraser {
    suspend fun eraseAll()

    suspend fun allowForActiveSession() = Unit
}

data class EncryptedMessagingRecord(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface SecureMessagingRecordCipher {
    fun encrypt(
        aad: ByteArray,
        plaintext: ByteArray,
        allowKeyCreation: Boolean,
    ): EncryptedMessagingRecord

    fun decrypt(aad: ByteArray, record: EncryptedMessagingRecord): ByteArray
    fun eraseKey()
}

@Singleton
class AndroidKeystoreMessagingRecordCipher @Inject constructor() : SecureMessagingRecordCipher {
    override fun encrypt(
        aad: ByteArray,
        plaintext: ByteArray,
        allowKeyCreation: Boolean,
    ): EncryptedMessagingRecord = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, recordKey(allowKeyCreation))
        cipher.updateAAD(aad)
        EncryptedMessagingRecord(
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    } catch (error: Exception) {
        throw secureMessagingStateAccessFailure(
            "Secure messaging state could not be encrypted",
            error,
        )
    }

    override fun decrypt(aad: ByteArray, record: EncryptedMessagingRecord): ByteArray = try {
        require(record.iv.size == GCM_IV_BYTES) { "Invalid secure messaging state IV" }
        require(record.ciphertext.size >= GCM_TAG_BYTES) { "Invalid secure messaging ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            // Never manufacture a replacement key while ciphertext is present. Some Android 9
            // Keystore providers briefly hide an existing alias while locked or recovering; a
            // replacement would make every retained messaging record permanently undecryptable.
            recordKey(allowCreation = false),
            GCMParameterSpec(GCM_TAG_BITS, record.iv),
        )
        cipher.updateAAD(aad)
        cipher.doFinal(record.ciphertext)
    } catch (error: Exception) {
        throw secureMessagingStateAccessFailure(
            "Secure messaging state failed authenticated decryption",
            error,
        )
    }

    @Synchronized
    override fun eraseKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (error: Exception) {
            throw SecureMessagingStateUnavailableException(
                "Secure messaging state key could not be erased",
                error,
            )
        }
    }

    private fun recordKey(allowCreation: Boolean): SecretKey = resolveSecureMessagingRecordKey(
        allowCreation = allowCreation,
        loadExisting = {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        },
        createNew = ::generateKey,
    )

    @Synchronized
    private fun generateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "kit_pay_secure_messaging_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val GCM_IV_BYTES = 12
    }
}

@Singleton
class RoomSecureMessagingStateStore @Inject constructor(
    private val database: KitWalletDatabase,
    private val cipher: SecureMessagingRecordCipher,
) : SecureMessagingStateStore {
    private val records get() = database.secureMessagingRecordDao()
    private val lifecycleGate = SecureMessagingLifecycleGate()

    override suspend fun read(
        namespace: String,
        recordKey: String,
    ): SecureMessagingRecord? = lifecycleGate.withOperation {
        validateRecordAddress(namespace, recordKey)
        val stored = records.get(namespace, recordKey) ?: return@withOperation null
        val bytes = try {
            require(
                stored.namespace == namespace &&
                    stored.recordKey == recordKey &&
                    stored.version > 0 &&
                    stored.updatedAtEpochMillis > 0,
            ) { "Secure messaging record query returned invalid metadata" }
            cipher.decrypt(
                messagingRecordAad(namespace, recordKey, stored.version),
                EncryptedMessagingRecord(stored.iv, stored.ciphertext),
            )
        } finally {
            stored.iv.fill(0)
            stored.ciphertext.fill(0)
        }
        try {
            SecureMessagingRecord(
                namespace = namespace,
                recordKey = recordKey,
                version = stored.version,
                bytes = bytes,
                updatedAtEpochMillis = stored.updatedAtEpochMillis,
            )
        } catch (error: Throwable) {
            bytes.fill(0)
            throw error
        }
    }

    override suspend fun readNamespacePage(
        namespace: String,
        afterRecordKey: String?,
        limit: Int,
    ): SecureMessagingRecordPage = lifecycleGate.withOperation {
        validateSecureMessagingNamespacePageRequest(namespace, afterRecordKey, limit)
        val decrypted = ArrayList<SecureMessagingRecord>(limit)
        val encryptedRows = records.page(
            namespace = namespace,
            afterRecordKey = afterRecordKey,
            limit = limit + 1,
        )
        try {
            require(encryptedRows.size <= limit + 1) {
                "Secure messaging namespace query exceeded its requested bound"
            }
            val selectedRows = encryptedRows.take(limit)
            var previousKey = afterRecordKey
            encryptedRows.forEach { stored ->
                require(stored.namespace == namespace) {
                    "Secure messaging namespace query returned another namespace"
                }
                validateRecordAddress(stored.namespace, stored.recordKey)
                val cursor = previousKey
                require(cursor == null || stored.recordKey > cursor) {
                    "Secure messaging namespace query is not strictly ordered"
                }
                require(stored.version > 0 && stored.updatedAtEpochMillis > 0) {
                    "Secure messaging namespace query returned invalid metadata"
                }
                previousKey = stored.recordKey
            }
            selectedRows.forEach { stored ->
                val bytes = cipher.decrypt(
                    messagingRecordAad(stored.namespace, stored.recordKey, stored.version),
                    EncryptedMessagingRecord(stored.iv, stored.ciphertext),
                )
                try {
                    decrypted += SecureMessagingRecord(
                        namespace = stored.namespace,
                        recordKey = stored.recordKey,
                        version = stored.version,
                        bytes = bytes,
                        updatedAtEpochMillis = stored.updatedAtEpochMillis,
                    )
                } catch (error: Throwable) {
                    bytes.fill(0)
                    throw error
                }
            }
            SecureMessagingRecordPage(
                records = decrypted,
                nextAfterRecordKey = if (encryptedRows.size > limit) {
                    selectedRows.last().recordKey
                } else {
                    null
                },
            )
        } catch (error: Throwable) {
            decrypted.forEach { it.bytes.fill(0) }
            throw error
        } finally {
            encryptedRows.forEach { stored ->
                stored.iv.fill(0)
                stored.ciphertext.fill(0)
            }
        }
    }

    override suspend fun write(
        namespace: String,
        recordKey: String,
        expectedVersion: Long?,
        bytes: ByteArray,
    ): SecureMessagingRecordVersion = writeBatch(
        listOf(SecureMessagingStateWrite(namespace, recordKey, expectedVersion, bytes)),
    ).single()

    override suspend fun writeBatch(
        writes: List<SecureMessagingStateWrite>,
    ): List<SecureMessagingRecordVersion> = try {
        lifecycleGate.withOperation {
            val snapshots = ArrayList<SecureMessagingStateWriteSnapshot>(writes.size)
            try {
                writes.forEach { write ->
                    snapshots += SecureMessagingStateWriteSnapshot(
                        namespace = write.namespace,
                        recordKey = write.recordKey,
                        expectedVersion = write.expectedVersion,
                        bytes = write.consumeBytes(),
                    )
                }
                require(snapshots.size in 1..MAX_ATOMIC_WRITES) {
                    "A secure messaging state transaction requires 1 to $MAX_ATOMIC_WRITES records"
                }
                snapshots.forEach {
                    validateRecordAddress(it.namespace, it.recordKey)
                    require(it.bytes.isNotEmpty()) { "Secure messaging state must not be empty" }
                }
                require(
                    snapshots.map { it.namespace to it.recordKey }.distinct().size == snapshots.size,
                ) {
                    "A secure messaging state transaction cannot write one record twice"
                }

                database.withTransaction {
                    val existingRecordCount = records.count()
                    snapshots.mapIndexed { writeIndex, write ->
                        val current = records.get(write.namespace, write.recordKey)
                        try {
                            val newVersion = when {
                                write.expectedVersion == null && current == null -> 1L
                                write.expectedVersion == null ->
                                    throw SecureMessagingStateConflictException(
                                        "Secure messaging state already exists",
                                    )
                                current == null || current.version != write.expectedVersion ->
                                    throw SecureMessagingStateConflictException(
                                        "Secure messaging state changed before it could be committed",
                                    )
                                write.expectedVersion == Long.MAX_VALUE ->
                                    throw SecureMessagingStateConflictException(
                                        "Secure messaging state version is exhausted",
                                    )
                                else -> write.expectedVersion + 1
                            }
                            val aad = messagingRecordAad(
                                write.namespace,
                                write.recordKey,
                                newVersion,
                            )
                            val encrypted = try {
                                cipher.encrypt(
                                    aad = aad,
                                    plaintext = write.bytes,
                                    allowKeyCreation = secureMessagingKeyCreationAllowed(
                                        existingRecordCount = existingRecordCount,
                                        writeIndex = writeIndex,
                                    ),
                                )
                            } finally {
                                aad.fill(0)
                            }
                            try {
                                val updatedAt = System.currentTimeMillis()
                                if (current == null) {
                                    records.insert(
                                        SecureMessagingRecordEntity(
                                            namespace = write.namespace,
                                            recordKey = write.recordKey,
                                            version = newVersion,
                                            iv = encrypted.iv,
                                            ciphertext = encrypted.ciphertext,
                                            updatedAtEpochMillis = updatedAt,
                                        ),
                                    )
                                } else {
                                    val updated = records.compareAndSet(
                                        namespace = write.namespace,
                                        recordKey = write.recordKey,
                                        expectedVersion = requireNotNull(write.expectedVersion),
                                        newVersion = newVersion,
                                        iv = encrypted.iv,
                                        ciphertext = encrypted.ciphertext,
                                        updatedAtEpochMillis = updatedAt,
                                    )
                                    if (updated != 1) {
                                        throw SecureMessagingStateConflictException(
                                            "Secure messaging state changed before it could be committed",
                                        )
                                    }
                                }

                                SecureMessagingRecordVersion(
                                    write.namespace,
                                    write.recordKey,
                                    newVersion,
                                )
                            } finally {
                                // Room has synchronously bound the blobs when its suspend DAO call
                                // returns; no entity or binding may retain these temporary arrays.
                                encrypted.iv.fill(0)
                                encrypted.ciphertext.fill(0)
                            }
                        } finally {
                            current?.iv?.fill(0)
                            current?.ciphertext?.fill(0)
                        }
                    }
                }
            } finally {
                snapshots.forEach { it.bytes.fill(0) }
            }
        }
    } finally {
        // This also covers validation failures and a lifecycle gate closed by logout.
        writes.forEach(SecureMessagingStateWrite::wipeBytes)
    }

    override suspend fun deleteNamespace(namespace: String) = lifecycleGate.withOperation {
        validateRecordAddress(namespace, "namespace-delete")
        records.deleteNamespace(namespace)
    }

    override suspend fun eraseAll() = lifecycleGate.erase {
        eraseMessagingKeyAndRecords(
            eraseKey = cipher::eraseKey,
            eraseRecords = records::deleteAll,
        )
    }

    override suspend fun allowForActiveSession() {
        lifecycleGate.open()
    }
}

@Singleton
class SecureMessagingSessionLifecycle @Inject internal constructor(
    private val eraser: SecureMessagingStateEraser,
    private val lifecycle: SecureMessagingLifecycleGuard,
    private val notifications: SecureMessagingIncomingNotificationSink =
        NoOpSecureMessagingIncomingNotificationSink,
) {
    private val mutableStateAvailable = MutableStateFlow(false)
    val stateAvailable = mutableStateAvailable.asStateFlow()

    suspend fun beforeSessionSave(isSameSession: Boolean) {
        if (!isSameSession) eraseActivationState()
    }

    suspend fun beforeSessionClear() {
        eraseActivationState()
    }

    suspend fun afterSessionSave() {
        eraser.allowForActiveSession()
        mutableStateAvailable.value = true
    }

    /** Exact-generation variant used after an enrollment reset proof or local revocation proof. */
    internal suspend fun resetForRecovery(fence: SecureMessagingSessionFence) {
        lifecycle.beginRecoveryErasure(fence)
        mutableStateAvailable.value = false
        runCatching { notifications.cancelAll() }
        try {
            eraser.eraseAll()
        } finally {
            lifecycle.finishErasure()
        }
    }

    private suspend fun eraseActivationState() {
        mutableStateAvailable.value = false
        // Revoke local tap grants before any suspension in cryptographic erasure.
        runCatching { notifications.cancelAll() }
        lifecycle.beginErasure()
        try {
            eraser.eraseAll()
        } finally {
            // A failed key/database erase still invalidates every in-memory capability. The
            // session store separately retains its erasure-pending marker and refuses reuse.
            lifecycle.finishErasure()
        }
    }
}

internal fun interface SecureMessagingCurrentActivationRevocation {
    suspend fun eraseSecureMessagingState()
}

internal object NoOpSecureMessagingCurrentActivationRevocation :
    SecureMessagingCurrentActivationRevocation {
    override suspend fun eraseSecureMessagingState() = Unit
}

@Singleton
internal class ErasingSecureMessagingCurrentActivationRevocation @Inject constructor(
    private val sessionLifecycle: SecureMessagingSessionLifecycle,
) : SecureMessagingCurrentActivationRevocation {
    override suspend fun eraseSecureMessagingState() {
        sessionLifecycle.beforeSessionClear()
    }
}

@VisibleForTesting
internal class SecureMessagingLifecycleGate {
    private val mutex = Mutex()
    private var operationsAllowed = false

    suspend fun <T> withOperation(operation: suspend () -> T): T = mutex.withLock {
        check(operationsAllowed) {
            "Secure messaging state is unavailable without an active local session"
        }
        operation()
    }

    suspend fun erase(erasure: suspend () -> Unit) = mutex.withLock {
        // Close first and remain closed even if cryptographic/database erasure fails.
        operationsAllowed = false
        erasure()
    }

    suspend fun open() = mutex.withLock {
        operationsAllowed = true
    }
}

@VisibleForTesting
internal suspend fun eraseMessagingKeyAndRecords(
    eraseKey: () -> Unit,
    eraseRecords: suspend () -> Unit,
) {
    var keyFailure: Exception? = null
    try {
        eraseKey()
    } catch (error: Exception) {
        keyFailure = error
    }

    try {
        eraseRecords()
    } catch (recordFailure: Exception) {
        keyFailure?.addSuppressed(recordFailure)
        if (keyFailure == null) throw recordFailure
    }

    keyFailure?.let { throw it }
}

/** A decrypt/update path must never replace an alias that Android 9 temporarily cannot expose. */
@VisibleForTesting
internal fun <T : Any> resolveSecureMessagingRecordKey(
    allowCreation: Boolean,
    loadExisting: () -> T?,
    createNew: () -> T,
): T {
    loadExisting()?.let { return it }
    if (!allowCreation) throw SecureMessagingRecordKeyTemporarilyUnavailableException()
    return createNew()
}

/** Only the first encryption in a genuinely empty store may bootstrap a new at-rest key. */
@VisibleForTesting
internal fun secureMessagingKeyCreationAllowed(
    existingRecordCount: Int,
    writeIndex: Int,
): Boolean {
    require(existingRecordCount >= 0) { "Secure messaging record count cannot be negative" }
    require(writeIndex >= 0) { "Secure messaging write index cannot be negative" }
    return existingRecordCount == 0 && writeIndex == 0
}

@VisibleForTesting
internal class SecureMessagingRecordKeyTemporarilyUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("The secure messaging record key is temporarily unavailable", cause)

/**
 * Provider/lock-state failures retry without resetting the server enrollment. Authenticated
 * corruption and permanently invalidated keys continue through the fenced enrollment recovery.
 */
@VisibleForTesting
internal fun isRetryableSecureMessagingStateFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.toList()
    if (causes.any { it is KeyPermanentlyInvalidatedException }) return false
    return causes.any { cause ->
        cause is SecureMessagingRecordKeyTemporarilyUnavailableException ||
            cause is UserNotAuthenticatedException ||
            cause.javaClass.name == "android.security.KeyStoreException" ||
            cause is KeyStoreException ||
            cause is UnrecoverableKeyException ||
            cause is ProviderException
    }
}

/** Keeps transient Android 9/OEM provider failures retryable while corruption stays fail-closed. */
@VisibleForTesting
internal fun secureMessagingStateAccessFailure(
    message: String,
    cause: Exception,
): Exception = if (isRetryableSecureMessagingStateFailure(cause)) {
    SecureMessagingStateRetryableException(message, cause)
} else {
    SecureMessagingStateUnavailableException(message, cause)
}

@VisibleForTesting
internal fun messagingRecordAad(namespace: String, recordKey: String, version: Long): ByteArray {
    val namespaceBytes = namespace.toByteArray(Charsets.UTF_8)
    val recordKeyBytes = recordKey.toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(Int.SIZE_BYTES + namespaceBytes.size + Int.SIZE_BYTES + recordKeyBytes.size + Long.SIZE_BYTES)
        .putInt(namespaceBytes.size)
        .put(namespaceBytes)
        .putInt(recordKeyBytes.size)
        .put(recordKeyBytes)
        .putLong(version)
        .array()
}

private fun validateRecordAddress(namespace: String, recordKey: String) {
    require(SECURE_RECORD_ADDRESS.matches(namespace)) { "Invalid secure messaging namespace" }
    require(SECURE_RECORD_ADDRESS.matches(recordKey)) { "Invalid secure messaging record key" }
}

internal fun validateSecureMessagingNamespacePageRequest(
    namespace: String,
    afterRecordKey: String?,
    limit: Int,
) {
    require(SECURE_RECORD_ADDRESS.matches(namespace)) { "Invalid secure messaging namespace" }
    afterRecordKey?.let {
        require(SECURE_RECORD_ADDRESS.matches(it)) {
            "Invalid secure messaging namespace-page cursor"
        }
    }
    require(limit in 1..MAX_SECURE_MESSAGING_NAMESPACE_PAGE_SIZE) {
        "Secure messaging namespace page limit must be between 1 and " +
            MAX_SECURE_MESSAGING_NAMESPACE_PAGE_SIZE
    }
}

private val SECURE_RECORD_ADDRESS = Regex("^[A-Za-z0-9._:@-]{1,160}$")

private const val MAX_ATOMIC_WRITES = 256
internal const val MAX_SECURE_MESSAGING_NAMESPACE_PAGE_SIZE = 100
