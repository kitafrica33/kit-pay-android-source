package com.kit.wallet.data.messaging

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.local.SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY
import com.kit.wallet.data.local.SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE
import com.kit.wallet.data.local.SecureMessagingRecordEntity
import com.kit.wallet.data.session.CoroutineOwnedMutex
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

/** AES-GCM rejected one locally persisted record after Android Keystore resolved a key handle. */
@VisibleForTesting
internal class SecureMessagingRecordAuthenticationFailedException(
    cause: Throwable,
) : java.security.GeneralSecurityException(
    "Secure messaging at-rest record authentication failed",
    cause,
)

/**
 * An affected pre-code-19 install contains a record that cannot be authenticated with the
 * retained Android Keystore key. This type is issued only while the migration marker proves that
 * the unreadable row predates the code-19 validation boundary; AES-GCM cannot distinguish a
 * replaced legacy key from legacy ciphertext corruption.
 */
@VisibleForTesting
internal class SecureMessagingLegacyStateUnreadableException(
    cause: Throwable,
) : IllegalStateException(
    "Legacy secure messaging state cannot be authenticated",
    cause,
)

/** Validates every pre-fix encrypted record before activation can write any new state. */
fun interface SecureMessagingLegacyStateValidator {
    suspend fun validateAndRetireLegacyKeyContinuity()
}

internal object NoOpSecureMessagingLegacyStateValidator : SecureMessagingLegacyStateValidator {
    override suspend fun validateAndRetireLegacyKeyContinuity() = Unit
}

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

    /**
     * Holds the open-state lifecycle lease across a compound operation that is already fenced by
     * its exact authenticated-session owner. Unlike [withActivationLease], this does not establish
     * activation authority by itself; callers must acquire the SessionStore owner first.
     */
    suspend fun <T> withStateLease(operation: suspend () -> T): T = operation()

    /**
     * Runs state work only for one exact messaging activation. Production holds the same exclusive
     * lease used by erasure, so an obsolete coroutine can neither cross erase/reopen nor commit
     * into a replacement activation's freshly opened store.
     */
    suspend fun <T> withActivationLease(
        activation: SecureMessagingActivationCapability,
        readyRequired: Boolean = false,
        operation: suspend () -> T,
    ): T {
        val provenance = SecureMessagingActivationProvenance.requireCurrent(
            activation,
            readyRequired,
        )
        val result = operation()
        provenance.assertCurrent(readyRequired)
        return result
    }

    suspend fun deleteNamespace(namespace: String)

    /** Cryptographically erases all device-local messaging state before a Kit session is removed. */
    override suspend fun eraseAll()
}

fun interface SecureMessagingStateEraser {
    suspend fun eraseAll()

    /**
     * Runs [finalSnapshot] after all in-flight state operations have finished and before state is
     * closed and erased. Production implementations keep the same exclusive state lease across
     * both calls, so no accepted commit can land between the snapshot and cryptographic erasure.
     */
    suspend fun eraseAllAfterFinalSnapshot(finalSnapshot: suspend () -> Unit) {
        finalSnapshot()
        eraseAll()
    }

    suspend fun allowForActiveSession() = Unit
}

data class EncryptedMessagingRecord(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/** Distinguishes a genuinely new alias from an existing handle found during bootstrap. */
@VisibleForTesting
internal data class SecureMessagingRecordKeyResolution<T : Any>(
    val key: T,
    val createdNow: Boolean,
)

@VisibleForTesting
internal enum class SecureMessagingRecordKeyOperation {
    ENCRYPT,
    DECRYPT,
}

/** A successful cipher mode proves only that mode usable; the other mode's proof must survive. */
@VisibleForTesting
internal fun shouldClearSecureMessagingRecordKeyFailureEvidence(
    failedOperation: SecureMessagingRecordKeyOperation,
    successfulOperation: SecureMessagingRecordKeyOperation,
): Boolean = failedOperation == successfulOperation

/**
 * Retains only a key handle that has already completed an authenticated cipher operation.
 *
 * Some Android 9 providers return the generated non-exportable handle from [KeyGenerator], use it
 * successfully, and then report the same alias as absent to a newly loaded [KeyStore]. Keeping the
 * proven handle for this process closes that provider visibility gap without ever permitting a
 * replacement key while ciphertext exists.
 */
@VisibleForTesting
internal class SecureMessagingRecordKeyHandleCache<T : Any>(
    private val canRetain: (T) -> Boolean = { true },
) {
    private var retained: T? = null

    @Synchronized
    fun resolve(
        resolveUncached: () -> SecureMessagingRecordKeyResolution<T>,
    ): SecureMessagingRecordKeyResolution<T> {
        retained?.let { key ->
            return SecureMessagingRecordKeyResolution(key, createdNow = false)
        }
        return resolveUncached()
    }

    @Synchronized
    fun retainAfterSuccessfulUse(key: T) {
        if (canRetain(key)) retained = key
    }

    @Synchronized
    fun evictIfSame(key: T) {
        if (retained === key) retained = null
    }

    @Synchronized
    fun clear() {
        retained = null
    }
}

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
class AndroidKeystoreMessagingRecordCipher @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecureMessagingRecordCipher {
    private val keyHealth = context.getSharedPreferences(KEY_HEALTH_PREFERENCES, Context.MODE_PRIVATE)
    private val recordKeyHandles = SecureMessagingRecordKeyHandleCache<SecretKey>(
        canRetain = ::isNonExportableKeyHandle,
    )

    override fun encrypt(
        aad: ByteArray,
        plaintext: ByteArray,
        allowKeyCreation: Boolean,
    ): EncryptedMessagingRecord = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val resolvedKey = recordKey(
            allowCreation = allowKeyCreation,
            operation = SecureMessagingRecordKeyOperation.ENCRYPT,
        )
        try {
            cipher.init(Cipher.ENCRYPT_MODE, resolvedKey.key)
        } catch (error: Exception) {
            throw recordKeyOperationFailure(
                error,
                resolvedKey,
                SecureMessagingRecordKeyOperation.ENCRYPT,
            )
        }
        val ciphertext = completeKeystoreCipherOperation(
            operation = {
                cipher.updateAAD(aad)
                cipher.doFinal(plaintext)
            },
            classifyFailure = {
                recordKeyOperationFailure(
                    it,
                    resolvedKey,
                    SecureMessagingRecordKeyOperation.ENCRYPT,
                )
            },
            onSuccess = {
                recordKeyHandles.retainAfterSuccessfulUse(resolvedKey.key)
                clearKeyFailureObservationsAfter(
                    SecureMessagingRecordKeyOperation.ENCRYPT,
                )
            },
        )
        EncryptedMessagingRecord(
            iv = cipher.iv.copyOf(),
            ciphertext = ciphertext,
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
        val resolvedKey = recordKey(
            allowCreation = false,
            operation = SecureMessagingRecordKeyOperation.DECRYPT,
        )
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                // Never manufacture a replacement key while ciphertext is present. Some Android 9
                // Keystore providers briefly hide an existing alias while locked or recovering; a
                // replacement would make every retained messaging record permanently undecryptable.
                resolvedKey.key,
                GCMParameterSpec(GCM_TAG_BITS, record.iv),
            )
        } catch (error: Exception) {
            // Android 9 providers can defer an alias lookup until Cipher.init(). Feed those
            // alias-specific failures through the same bounded missing-key proof as getKey().
            throw recordKeyOperationFailure(
                error,
                resolvedKey,
                SecureMessagingRecordKeyOperation.DECRYPT,
            )
        }
        completeKeystoreCipherOperation(
            operation = {
                cipher.updateAAD(aad)
                cipher.doFinal(record.ciphertext)
            },
            classifyFailure = {
                recordKeyOperationFailure(
                    it,
                    resolvedKey,
                    SecureMessagingRecordKeyOperation.DECRYPT,
                )
            },
            onSuccess = {
                recordKeyHandles.retainAfterSuccessfulUse(resolvedKey.key)
                clearKeyFailureObservationsAfter(
                    SecureMessagingRecordKeyOperation.DECRYPT,
                )
            },
        )
    } catch (error: Exception) {
        val classified = if (error.hasGcmAuthenticationFailure()) {
            SecureMessagingRecordAuthenticationFailedException(error)
        } else {
            error
        }
        throw secureMessagingStateAccessFailure(
            "Secure messaging state failed authenticated decryption",
            classified,
        )
    }

    @Synchronized
    override fun eraseKey() {
        // Once exact erasure begins, no in-process handle may outlive that lifecycle boundary,
        // including when the provider subsequently fails the physical alias deletion.
        recordKeyHandles.clear()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            deleteKeystoreAliasAndVerifyAbsent(
                deleteEntry = { keyStore.deleteEntry(KEY_ALIAS) },
                aliasExists = { keyStore.containsAlias(KEY_ALIAS) },
            )
            clearKeyFailureObservations()
        } catch (error: Exception) {
            throw SecureMessagingStateUnavailableException(
                "Secure messaging state key could not be erased",
                error,
            )
        }
    }

    @Synchronized
    private fun recordKey(
        allowCreation: Boolean,
        operation: SecureMessagingRecordKeyOperation,
    ): SecureMessagingRecordKeyResolution<SecretKey> {
        var freshKeyGenerationStarted = false
        return try {
            recordKeyHandles.resolve {
                resolveSecureMessagingRecordKeyWithCreationStatus(
                    allowCreation = allowCreation,
                    loadExisting = {
                        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.also {
                            // A visible handle disproves only alias absence. Recoverability is
                            // proved separately by a successful complete cipher operation.
                            clearMissingKeyObservations()
                        }
                    },
                    createNew = {
                        generateKey { freshKeyGenerationStarted = true }
                    },
                    missingKeyFailure = ::missingKeyFailure,
                    classifyExistingAliasFailure = { error ->
                        classifyRetryableAliasAccessFailure(error, operation)
                    },
                )
            }
        } catch (error: Exception) {
            // A failure while resolving an already-present alias must not inherit the empty-store
            // permission to create. Only an attempt that reached fresh key generation retains
            // bootstrap retry semantics.
            throw classifySecureMessagingRecordKeyOperationFailure(
                error = error,
                keyCreatedNow = freshKeyGenerationStarted,
                classifyExistingAliasFailure = { failure ->
                    classifyRetryableAliasAccessFailure(failure, operation)
                },
            )
        }
    }

    @Synchronized
    private fun recordKeyOperationFailure(
        error: Exception,
        resolvedKey: SecureMessagingRecordKeyResolution<SecretKey>,
        operation: SecureMessagingRecordKeyOperation,
    ): Exception {
        val classified = classifySecureMessagingRecordKeyOperationFailure(
            error = error,
            keyCreatedNow = resolvedKey.createdNow,
            classifyExistingAliasFailure = { failure ->
                classifyRetryableAliasAccessFailure(failure, operation)
            },
        )
        if (isPermanentlyMissingSecureMessagingRecordKey(classified)) {
            // Authentication and one-off provider failures must retain the only proven Android 9
            // handle. Evict only after permanent invalidation or the bounded missing/unrecoverable
            // proof has completed; exact secure-state recovery then clears the lifecycle fully.
            recordKeyHandles.evictIfSame(resolvedKey.key)
        }
        return classified
    }

    private fun isNonExportableKeyHandle(key: SecretKey): Boolean {
        val encoded = try {
            key.encoded
        } catch (_: Exception) {
            return false
        }
        return if (encoded == null) {
            true
        } else {
            encoded.fill(0)
            false
        }
    }

    /**
     * Provider errors alone do not prove that ciphertext lost its key. Advance recovery only for
     * an independently absent alias, or the narrow present-but-UnrecoverableKeyException case.
     */
    @Synchronized
    private fun classifyRetryableAliasAccessFailure(
        error: Exception,
        operation: SecureMessagingRecordKeyOperation,
    ): Exception {
        val requiresUserAuthentication = generateSequence<Throwable>(error) { it.cause }
            .any { it is UserNotAuthenticatedException }
        if (requiresUserAuthentication) {
            // Lock state says nothing about whether the same handle supports the other cipher
            // mode. Restart only this operation's proof while preserving the other mode.
            clearMissingKeyObservations()
            clearUnrecoverableKeyObservations(operation)
        }
        val aliasPresent = runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(KEY_ALIAS)
        }.getOrNull()
        if (aliasPresent == true) {
            // A visible alias disproves only alias absence. An absent alias does not prove either
            // cipher mode healthy, so it must not erase an independent ENCRYPT/DECRYPT failure
            // proof when an Android 9 provider alternates between visibility states.
            clearMissingKeyObservations()
        }
        return classifySecureMessagingRecordKeyAccessFailure(
            cause = error,
            userAuthenticationRequired = requiresUserAuthentication,
            aliasPresent = aliasPresent,
            observeMissingAlias = ::missingKeyFailure,
            observeUnrecoverableAlias = { cause ->
                unrecoverableKeyFailure(cause, operation)
            },
        )
    }

    @Synchronized
    private fun missingKeyFailure(cause: Throwable? = null): RuntimeException {
        // Alias absence and cipher-mode usability are independent provider observations. Preserve
        // operation evidence so alternating Android 9 failures cannot reset both bounded proofs.
        return observedKeyFailure(
            cause = cause,
            countPreference = KEY_MISSING_COUNT,
            firstObservedAtPreference = KEY_FIRST_MISSING_AT,
            permanentFailure = ::SecureMessagingRecordKeyPermanentlyMissingException,
        )
    }

    @Synchronized
    private fun unrecoverableKeyFailure(
        cause: Throwable,
        operation: SecureMessagingRecordKeyOperation,
    ): RuntimeException {
        clearMissingKeyObservations()
        return observedKeyFailure(
            cause = cause,
            countPreference = operation.unrecoverableCountPreference(),
            firstObservedAtPreference = operation.firstUnrecoverableAtPreference(),
            permanentFailure = ::SecureMessagingRecordKeyPermanentlyUnrecoverableException,
        )
    }

    private fun observedKeyFailure(
        cause: Throwable?,
        countPreference: String,
        firstObservedAtPreference: String,
        permanentFailure: (Throwable?) -> RuntimeException,
    ): RuntimeException {
        val now = System.currentTimeMillis()
        val previous = SecureMessagingRecordKeyMissState(
            consecutiveUnlockedMisses = keyHealth.getInt(countPreference, 0),
            firstUnlockedMissAtEpochMillis = keyHealth.getLong(firstObservedAtPreference, 0L),
        )
        val requiresUserAuthentication = generateSequence(cause) { it.cause }
            .any { it is UserNotAuthenticatedException }
        val userUnlocked = !requiresUserAuthentication &&
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        val observed = observeSecureMessagingRecordKeyMiss(
            previous = previous,
            userUnlocked = userUnlocked,
            nowEpochMillis = now,
        )
        keyHealth.edit()
            .putInt(countPreference, observed.consecutiveUnlockedMisses)
            .putLong(firstObservedAtPreference, observed.firstUnlockedMissAtEpochMillis)
            .commit()
        return if (observed.permanentlyMissing) {
            permanentFailure(cause)
        } else {
            SecureMessagingRecordKeyTemporarilyUnavailableException(cause)
        }
    }

    @Synchronized
    private fun clearMissingKeyObservations() {
        if (keyHealth.contains(KEY_MISSING_COUNT) || keyHealth.contains(KEY_FIRST_MISSING_AT)) {
            keyHealth.edit()
                .remove(KEY_MISSING_COUNT)
                .remove(KEY_FIRST_MISSING_AT)
                .apply()
        }
    }

    @Synchronized
    private fun clearUnrecoverableKeyObservations(
        operation: SecureMessagingRecordKeyOperation? = null,
    ) {
        val operations = SecureMessagingRecordKeyOperation.entries.filter { failedOperation ->
            operation == null || shouldClearSecureMessagingRecordKeyFailureEvidence(
                failedOperation = failedOperation,
                successfulOperation = operation,
            )
        }
        val editor = keyHealth.edit()
        var changed = false
        operations.forEach { keyOperation ->
            val count = keyOperation.unrecoverableCountPreference()
            val first = keyOperation.firstUnrecoverableAtPreference()
            if (keyHealth.contains(count) || keyHealth.contains(first)) {
                editor.remove(count).remove(first)
                changed = true
            }
        }
        // Code 21 used one mode-agnostic counter. It cannot safely contribute to either new proof.
        if (keyHealth.contains(KEY_LEGACY_UNRECOVERABLE_COUNT) ||
            keyHealth.contains(KEY_LEGACY_FIRST_UNRECOVERABLE_AT)
        ) {
            editor.remove(KEY_LEGACY_UNRECOVERABLE_COUNT)
                .remove(KEY_LEGACY_FIRST_UNRECOVERABLE_AT)
            changed = true
        }
        if (changed) editor.apply()
    }

    @Synchronized
    private fun clearKeyFailureObservationsAfter(
        operation: SecureMessagingRecordKeyOperation,
    ) {
        // A successful decrypt proves DECRYPT_MODE only. It must not clear evidence that this OEM
        // consistently rejects ENCRYPT_MODE, which otherwise strands confirmation forever.
        clearMissingKeyObservations()
        clearUnrecoverableKeyObservations(operation)
    }

    @Synchronized
    private fun clearKeyFailureObservations() {
        clearMissingKeyObservations()
        clearUnrecoverableKeyObservations()
    }

    private fun SecureMessagingRecordKeyOperation.unrecoverableCountPreference(): String =
        when (this) {
            SecureMessagingRecordKeyOperation.ENCRYPT -> KEY_ENCRYPT_UNRECOVERABLE_COUNT
            SecureMessagingRecordKeyOperation.DECRYPT -> KEY_DECRYPT_UNRECOVERABLE_COUNT
        }

    private fun SecureMessagingRecordKeyOperation.firstUnrecoverableAtPreference(): String =
        when (this) {
            SecureMessagingRecordKeyOperation.ENCRYPT -> KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT
            SecureMessagingRecordKeyOperation.DECRYPT -> KEY_DECRYPT_FIRST_UNRECOVERABLE_AT
        }

    @Synchronized
    private fun generateKey(
        onFreshGenerationStarted: () -> Unit,
    ): SecureMessagingRecordKeyResolution<SecretKey> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { existing ->
            return SecureMessagingRecordKeyResolution(existing, createdNow = false)
        }

        onFreshGenerationStarted()
        val generated = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).run {
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
        return SecureMessagingRecordKeyResolution(generated, createdNow = true)
    }

    private companion object {
        const val KEY_ALIAS = "kit_pay_secure_messaging_aes_v1"
        const val KEY_HEALTH_PREFERENCES = "kit_pay_secure_messaging_key_health_v1"
        const val KEY_MISSING_COUNT = "missing_alias_count"
        const val KEY_FIRST_MISSING_AT = "missing_alias_first_seen_at"
        const val KEY_ENCRYPT_UNRECOVERABLE_COUNT = "encrypt_unrecoverable_alias_count"
        const val KEY_ENCRYPT_FIRST_UNRECOVERABLE_AT =
            "encrypt_unrecoverable_alias_first_seen_at"
        const val KEY_DECRYPT_UNRECOVERABLE_COUNT = "decrypt_unrecoverable_alias_count"
        const val KEY_DECRYPT_FIRST_UNRECOVERABLE_AT =
            "decrypt_unrecoverable_alias_first_seen_at"
        const val KEY_LEGACY_UNRECOVERABLE_COUNT = "unrecoverable_alias_count"
        const val KEY_LEGACY_FIRST_UNRECOVERABLE_AT = "unrecoverable_alias_first_seen_at"
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
) : SecureMessagingStateStore, SecureMessagingLegacyStateValidator {
    private val records get() = database.secureMessagingRecordDao()
    private val metadata get() = database.secureMessagingMetadataDao()
    private val lifecycleGate = SecureMessagingLifecycleGate()

    override suspend fun validateAndRetireLegacyKeyContinuity() =
        withContext(Dispatchers.IO) {
            lifecycleGate.withOperation {
                if (!legacyKeyContinuityPending()) return@withOperation

                var afterNamespace: String? = null
                var afterRecordKey: String? = null
                var validatedRecords = 0
                while (true) {
                    val encryptedRows = records.globalPage(
                        afterNamespace = afterNamespace,
                        afterRecordKey = afterRecordKey,
                        limit = LEGACY_KEY_VALIDATION_PAGE_SIZE,
                    )
                    try {
                        if (encryptedRows.isEmpty()) break
                        encryptedRows.forEach { stored ->
                            validateRecordAddress(stored.namespace, stored.recordKey)
                            require(stored.version > 0 && stored.updatedAtEpochMillis > 0) {
                                "Legacy secure messaging validation found invalid record metadata"
                            }
                            val previousNamespace = afterNamespace
                            val previousRecordKey = afterRecordKey
                            require(
                                previousNamespace == null ||
                                    stored.namespace > previousNamespace ||
                                    (
                                        stored.namespace == previousNamespace &&
                                            previousRecordKey != null &&
                                            stored.recordKey > previousRecordKey
                                        ),
                            ) { "Legacy secure messaging validation is not strictly ordered" }

                            val plaintext = decryptStoredRecord(stored)
                            plaintext.fill(0)
                            afterNamespace = stored.namespace
                            afterRecordKey = stored.recordKey
                            validatedRecords++
                            check(validatedRecords <= MAX_LEGACY_KEY_VALIDATION_RECORDS) {
                                "Legacy secure messaging state exceeds the validation bound"
                            }
                        }
                        if (encryptedRows.size < LEGACY_KEY_VALIDATION_PAGE_SIZE) break
                    } finally {
                        encryptedRows.forEach { it.wipeEncryptedBytes() }
                    }
                }

                // Only a complete, authenticated scan retires migration recovery authority. A
                // crash or provider failure leaves the marker durable and retries before the next
                // write.
                metadata.remove(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY)
            }
        }

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
            decryptStoredRecord(stored)
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
                val bytes = decryptStoredRecord(stored)
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

    override suspend fun <T> withActivationLease(
        activation: SecureMessagingActivationCapability,
        readyRequired: Boolean,
        operation: suspend () -> T,
    ): T = lifecycleGate.withActivationOperation(
        activation = activation,
        readyRequired = readyRequired,
        operation = operation,
    )

    override suspend fun <T> withStateLease(operation: suspend () -> T): T =
        lifecycleGate.withOperation(operation)

    override suspend fun deleteNamespace(namespace: String) = lifecycleGate.withOperation {
        validateRecordAddress(namespace, "namespace-delete")
        records.deleteNamespace(namespace)
    }

    override suspend fun eraseAll() = eraseAllAfterFinalSnapshot { }

    override suspend fun eraseAllAfterFinalSnapshot(finalSnapshot: suspend () -> Unit) =
        lifecycleGate.eraseAfterFinalSnapshot(finalSnapshot) {
            eraseMessagingKeyAndRecords(
                eraseKey = cipher::eraseKey,
                eraseRecords = {
                    records.deleteAll()
                    metadata.remove(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY)
                },
            )
        }

    override suspend fun allowForActiveSession() {
        lifecycleGate.open()
    }

    private suspend fun decryptStoredRecord(stored: SecureMessagingRecordEntity): ByteArray {
        val aad = messagingRecordAad(stored.namespace, stored.recordKey, stored.version)
        return try {
            cipher.decrypt(
                aad,
                EncryptedMessagingRecord(stored.iv, stored.ciphertext),
            )
        } catch (error: Exception) {
            if (isSecureMessagingRecordAuthenticationFailure(error) &&
                legacyKeyContinuityPending()
            ) {
                throw SecureMessagingLegacyStateUnreadableException(error)
            }
            throw error
        } finally {
            aad.fill(0)
        }
    }

    private suspend fun legacyKeyContinuityPending(): Boolean =
        metadata.get(SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY) ==
            SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE

    private fun SecureMessagingRecordEntity.wipeEncryptedBytes() {
        iv.fill(0)
        ciphertext.fill(0)
    }

    private companion object {
        const val LEGACY_KEY_VALIDATION_PAGE_SIZE = 100
        const val MAX_LEGACY_KEY_VALIDATION_RECORDS = 100_000
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

    suspend fun beforeSessionSave(
        isSameSession: Boolean,
        finalSnapshot: suspend () -> Unit = {},
        beforeErasure: () -> Unit = {},
    ) {
        if (!isSameSession) {
            eraseActivationState(
                finalSnapshot = finalSnapshot,
                beforeErasure = beforeErasure,
            )
        }
    }

    suspend fun beforeSessionClear(
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalSnapshot: suspend () -> Unit = {},
        beforeErasure: () -> Unit = {},
    ) {
        eraseActivationState(
            allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
            finalSnapshot = finalSnapshot,
            beforeErasure = beforeErasure,
        )
    }

    suspend fun afterSessionSave() {
        eraser.allowForActiveSession()
        mutableStateAvailable.value = true
    }

    /** Exact-generation variant used after an enrollment reset proof or local revocation proof. */
    internal suspend fun resetForRecovery(
        fence: SecureMessagingSessionFence,
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalSnapshot: suspend () -> Unit = {},
        beforeErasure: () -> Unit = {},
    ) {
        var erasureStarted = false
        try {
            eraser.eraseAllAfterFinalSnapshot {
                lifecycle.assertRecoveryErasureCurrent(fence)
                runFinalSnapshot(
                    allowPermanentlyUnavailableSnapshot,
                    finalSnapshot,
                )
                // Validate the exact activation and persist the caller's crash fence atomically.
                // A stale fence or marker failure therefore leaves this activation retryable.
                lifecycle.beginRecoveryErasure(fence, beforeErasure)
                erasureStarted = true
                mutableStateAvailable.value = false
                runCatching { notifications.cancelAll() }
            }
        } finally {
            if (erasureStarted) lifecycle.finishErasure()
        }
    }

    private suspend fun eraseActivationState(
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalSnapshot: suspend () -> Unit = {},
        beforeErasure: () -> Unit = {},
    ) {
        var erasureStarted = false
        try {
            eraser.eraseAllAfterFinalSnapshot {
                // The exclusive state lease has drained earlier commits. Snapshot while the
                // existing capability is still valid; if it fails, leave the session usable so
                // logout can be retried without losing the only recoverable projection.
                runFinalSnapshot(
                    allowPermanentlyUnavailableSnapshot,
                    finalSnapshot,
                )
                beforeErasure()
                mutableStateAvailable.value = false
                runCatching { notifications.cancelAll() }
                lifecycle.beginErasure()
                erasureStarted = true
            }
        } finally {
            if (erasureStarted) {
                // A failed key/database erase still invalidates every in-memory capability. The
                // session store separately retains its erasure-pending marker and refuses reuse.
                lifecycle.finishErasure()
            }
        }
    }

    private suspend fun runFinalSnapshot(
        allowPermanentlyUnavailableSnapshot: Boolean,
        finalSnapshot: suspend () -> Unit,
    ) {
        try {
            finalSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!allowPermanentlyUnavailableSnapshot ||
                !isRecoverableSecureMessagingStateLoss(error)
            ) {
                throw error
            }
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
    private val mutex = CoroutineOwnedMutex()
    private var operationsAllowed = false

    suspend fun <T> withOperation(operation: suspend () -> T): T = withExclusiveLease {
        check(operationsAllowed) {
            "Secure messaging state is unavailable without an active local session"
        }
        operation()
    }

    suspend fun <T> withActivationOperation(
        activation: SecureMessagingActivationCapability,
        readyRequired: Boolean = false,
        operation: suspend () -> T,
    ): T = withExclusiveLease {
        check(operationsAllowed) {
            "Secure messaging state is unavailable without an active local session"
        }
        val provenance = SecureMessagingActivationProvenance.requireCurrent(
            activation,
            readyRequired,
        )
        val result = operation()
        provenance.assertCurrent(readyRequired)
        result
    }

    suspend fun erase(erasure: suspend () -> Unit) =
        eraseAfterFinalSnapshot(finalSnapshot = {}, erasure = erasure)

    suspend fun eraseAfterFinalSnapshot(
        finalSnapshot: suspend () -> Unit,
        erasure: suspend () -> Unit,
    ) = withExclusiveLease {
        finalSnapshot()
        // Close only after the final snapshot succeeds, and remain closed even if
        // cryptographic/database erasure subsequently fails.
        operationsAllowed = false
        erasure()
    }

    suspend fun open() = withExclusiveLease {
        operationsAllowed = true
    }

    /** Allows state-store calls made by the same coroutine during the final snapshot. */
    private suspend fun <T> withExclusiveLease(operation: suspend () -> T): T =
        mutex.withLock(operation)
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

/**
 * Deletes by exact alias without using `containsAlias(false)` as proof of erasure. Android 9 OEM
 * providers can temporarily hide a live alias from lookup; the delete operation must still be
 * attempted, and a provider that leaves the alias visible must keep lifecycle recovery fenced.
 */
@VisibleForTesting
internal fun deleteKeystoreAliasAndVerifyAbsent(
    deleteEntry: () -> Unit,
    aliasExists: () -> Boolean,
) {
    deleteEntry()
    check(!aliasExists()) { "Android Keystore retained an alias after deletion" }
}

/**
 * Android 9 providers may defer use of an alias-backed key until updateAAD/doFinal. Keep failure
 * classification around that complete operation and clear key-health evidence only on success.
 */
@VisibleForTesting
internal fun <T> completeKeystoreCipherOperation(
    operation: () -> T,
    classifyFailure: (Exception) -> Exception,
    onSuccess: () -> Unit,
): T {
    val result = try {
        operation()
    } catch (error: Exception) {
        throw classifyFailure(error)
    }
    onSuccess()
    return result
}

/** A decrypt/update path must never replace an alias that Android 9 temporarily cannot expose. */
@VisibleForTesting
internal fun <T : Any> resolveSecureMessagingRecordKey(
    allowCreation: Boolean,
    loadExisting: () -> T?,
    createNew: () -> T,
    missingKeyFailure: () -> RuntimeException = {
        SecureMessagingRecordKeyTemporarilyUnavailableException()
    },
): T {
    loadExisting()?.let { return it }
    if (!allowCreation) throw missingKeyFailure()
    return createNew()
}

/**
 * Production key resolution retains whether this call actually generated the returned alias.
 * An empty database merely permits creation; it does not make an existing unusable alias fresh.
 */
@VisibleForTesting
internal fun <T : Any> resolveSecureMessagingRecordKeyWithCreationStatus(
    allowCreation: Boolean,
    loadExisting: () -> T?,
    createNew: () -> SecureMessagingRecordKeyResolution<T>,
    missingKeyFailure: () -> RuntimeException = {
        SecureMessagingRecordKeyTemporarilyUnavailableException()
    },
    classifyExistingAliasFailure: ((Exception) -> Exception)? = null,
): SecureMessagingRecordKeyResolution<T> {
    val existing = try {
        loadExisting()
    } catch (error: Exception) {
        val classifier = classifyExistingAliasFailure ?: throw error
        throw classifySecureMessagingRecordKeyOperationFailure(
            error = error,
            keyCreatedNow = false,
            classifyExistingAliasFailure = classifier,
        )
    }
    existing?.let { resolved ->
        return SecureMessagingRecordKeyResolution(resolved, createdNow = false)
    }
    if (!allowCreation) throw missingKeyFailure()
    return createNew()
}

/** Existing aliases use the bounded Android-9 recovery proof even on an empty-store write. */
@VisibleForTesting
internal fun classifySecureMessagingRecordKeyOperationFailure(
    error: Exception,
    keyCreatedNow: Boolean,
    classifyExistingAliasFailure: (Exception) -> Exception,
): Exception = when {
    error.hasPermanentlyInvalidatedSecureMessagingRecordKey() ->
        SecureMessagingRecordKeyPermanentlyUnrecoverableException(error)
    keyCreatedNow ||
        isTransientSecureMessagingRecordKeyFailure(error) ||
        isPermanentlyMissingSecureMessagingRecordKey(error) ||
        !isRetryableSecureMessagingStateFailure(error) -> error
    else -> classifyExistingAliasFailure(error)
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

/** A generic provider failure is never by itself evidence that a retained alias disappeared. */
@VisibleForTesting
internal fun classifySecureMessagingRecordKeyAccessFailure(
    cause: Throwable,
    userAuthenticationRequired: Boolean,
    aliasPresent: Boolean?,
    observeMissingAlias: (Throwable) -> RuntimeException,
    observeUnrecoverableAlias: (Throwable) -> RuntimeException,
): RuntimeException = when {
    userAuthenticationRequired -> SecureMessagingRecordKeyTemporarilyUnavailableException(cause)
    aliasPresent == false -> observeMissingAlias(cause)
    aliasPresent == true && cause.hasUnrecoverableSecureMessagingRecordKeyCause() ->
        observeUnrecoverableAlias(cause)
    else -> SecureMessagingRecordKeyTemporarilyUnavailableException(cause)
}

@VisibleForTesting
internal class SecureMessagingRecordKeyTemporarilyUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("The secure messaging record key is temporarily unavailable", cause)

/** The device is unlocked and repeated observations prove that retained ciphertext lost its key. */
@VisibleForTesting
internal class SecureMessagingRecordKeyPermanentlyMissingException(
    cause: Throwable? = null,
) : IllegalStateException(
    "The secure messaging record key is permanently missing",
    cause,
)

/** The alias exists, but Android repeatedly proves that its key material cannot be recovered. */
@VisibleForTesting
internal class SecureMessagingRecordKeyPermanentlyUnrecoverableException(
    cause: Throwable? = null,
) : IllegalStateException(
    "The secure messaging record key is permanently unrecoverable",
    cause,
)

@VisibleForTesting
internal data class SecureMessagingRecordKeyMissState(
    val consecutiveUnlockedMisses: Int = 0,
    val firstUnlockedMissAtEpochMillis: Long = 0L,
) {
    val permanentlyMissing: Boolean
        get() = consecutiveUnlockedMisses >= PERMANENT_MISSING_KEY_OBSERVATIONS &&
            firstUnlockedMissAtEpochMillis > 0L
}

/**
 * A single null lookup is ambiguous on Android 9. Escalate only after several unlocked-device
 * observations spanning a real interval; lock-state or clock anomalies restart the proof.
 */
@VisibleForTesting
internal fun observeSecureMessagingRecordKeyMiss(
    previous: SecureMessagingRecordKeyMissState,
    userUnlocked: Boolean,
    nowEpochMillis: Long,
): SecureMessagingRecordKeyMissState {
    if (!userUnlocked || nowEpochMillis <= 0L) return SecureMessagingRecordKeyMissState()
    val first = previous.firstUnlockedMissAtEpochMillis
    val continues = previous.consecutiveUnlockedMisses > 0 &&
        first > 0L &&
        nowEpochMillis >= first &&
        nowEpochMillis - first <= MAX_MISSING_KEY_OBSERVATION_WINDOW_MILLIS
    val firstObservedAt = if (continues) first else nowEpochMillis
    val count = if (continues) {
        (previous.consecutiveUnlockedMisses + 1).coerceAtMost(PERMANENT_MISSING_KEY_OBSERVATIONS)
    } else {
        1
    }
    val elapsed = nowEpochMillis - firstObservedAt
    return SecureMessagingRecordKeyMissState(
        consecutiveUnlockedMisses = if (
            count >= PERMANENT_MISSING_KEY_OBSERVATIONS &&
            elapsed < MIN_PERMANENT_MISSING_KEY_INTERVAL_MILLIS
        ) {
            PERMANENT_MISSING_KEY_OBSERVATIONS - 1
        } else {
            count
        },
        firstUnlockedMissAtEpochMillis = firstObservedAt,
    )
}

/**
 * Provider/lock-state failures retry without resetting the server enrollment. Authenticated
 * corruption and permanently invalidated keys continue through the fenced enrollment recovery.
 */
@VisibleForTesting
internal fun isRetryableSecureMessagingStateFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.toList()
    if (causes.any {
            it is KeyPermanentlyInvalidatedException ||
                it is SecureMessagingRecordKeyPermanentlyMissingException ||
                it is SecureMessagingRecordKeyPermanentlyUnrecoverableException
        }
    ) {
        return false
    }
    return causes.any { cause ->
        cause is SecureMessagingRecordKeyTemporarilyUnavailableException ||
            cause is UserNotAuthenticatedException ||
            cause.javaClass.name == "android.security.KeyStoreException" ||
            cause is InvalidKeyException ||
            cause is KeyStoreException ||
            cause is UnrecoverableKeyException ||
            cause is ProviderException
        }
}

private fun Throwable.hasUnrecoverableSecureMessagingRecordKeyCause(): Boolean =
    generateSequence(this) { it.cause }
        .any {
            it is UnrecoverableKeyException ||
                it is KeyPermanentlyInvalidatedException ||
                it is InvalidKeyException
        }

private fun Throwable.hasPermanentlyInvalidatedSecureMessagingRecordKey(): Boolean =
    generateSequence(this) { it.cause }.any { it is KeyPermanentlyInvalidatedException }

/** Marks only AES-GCM authentication failures produced by this local at-rest cipher. */
private fun Throwable.hasGcmAuthenticationFailure(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is AEADBadTagException || it is BadPaddingException
    }

@VisibleForTesting
internal fun isSecureMessagingRecordAuthenticationFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .any { it is SecureMessagingRecordAuthenticationFailedException }

/** True only for the explicit Android-Keystore alias observation used by bounded local retries. */
@VisibleForTesting
internal fun isTransientSecureMessagingRecordKeyFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.toList()
    return causes.none {
        it is SecureMessagingRecordKeyPermanentlyMissingException ||
            it is SecureMessagingRecordKeyPermanentlyUnrecoverableException
    } &&
        causes.any { it is SecureMessagingRecordKeyTemporarilyUnavailableException }
}

/** Distinguishes permanently unavailable at-rest keys from authenticated ciphertext corruption. */
@VisibleForTesting
internal fun isPermanentlyMissingSecureMessagingRecordKey(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .any {
            it is SecureMessagingRecordKeyPermanentlyMissingException ||
                it is SecureMessagingRecordKeyPermanentlyUnrecoverableException ||
                it is KeyPermanentlyInvalidatedException
        }

/**
 * Recovery authority is limited to a proved missing/unusable alias or migration-fenced unreadable
 * pre-code-19 state. Once a complete authenticated scan retires that marker, ordinary corruption
 * remains fail closed.
 */
@VisibleForTesting
internal fun isRecoverableSecureMessagingStateLoss(error: Throwable): Boolean =
    isPermanentlyMissingSecureMessagingRecordKey(error) ||
        generateSequence(error) { it.cause }
            .any { it is SecureMessagingLegacyStateUnreadableException }

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

private const val PERMANENT_MISSING_KEY_OBSERVATIONS = 4
private const val MIN_PERMANENT_MISSING_KEY_INTERVAL_MILLIS = 15_000L
private const val MAX_MISSING_KEY_OBSERVATION_WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
private const val MAX_ATOMIC_WRITES = 256
internal const val MAX_SECURE_MESSAGING_NAMESPACE_PAGE_SIZE = 100
