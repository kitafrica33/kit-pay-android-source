package com.kit.wallet.data.messaging

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.kit.wallet.data.local.AccountMessageArchiveEntity
import com.kit.wallet.data.local.KitWalletDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Canonical account and installation identity for one independently encrypted local archive. */
data class AccountMessageArchiveOwner(
    val ownerAccountId: String,
    val installationId: String,
) {
    init {
        requireCanonicalArchiveUuid(ownerAccountId, "archive owner account ID")
        requireCanonicalArchiveUuid(installationId, "archive installation ID")
    }
}

data class AccountMessageArchiveRecord(
    val owner: AccountMessageArchiveOwner,
    val recordKey: String,
    val version: Long,
    val bytes: ByteArray,
    val updatedAtEpochMillis: Long,
)

class AccountMessageArchivePage(
    records: List<AccountMessageArchiveRecord>,
    val nextAfterRecordKey: String?,
) {
    private val immutableRecords = records.toList()

    init {
        require(
            immutableRecords.zipWithNext().all { (left, right) ->
                left.recordKey < right.recordKey
            },
        ) { "Account message archive page must be strictly record-key ordered" }
        nextAfterRecordKey?.let { cursor ->
            validateAccountMessageArchiveRecordKey(cursor)
            require(immutableRecords.lastOrNull()?.recordKey == cursor) {
                "Account message archive cursor must identify the final returned record"
            }
        }
    }

    fun records(): List<AccountMessageArchiveRecord> = immutableRecords.toList()
}

data class EncryptedAccountMessageArchiveRecord(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/** Distinguishes a genuinely new archive alias from an existing handle found during bootstrap. */
@VisibleForTesting
internal data class AccountMessageArchiveKeyResolution<T : Any>(
    val key: T,
    val createdNow: Boolean,
)

class AccountMessageArchiveConflictException(message: String) : IllegalStateException(message)

class AccountMessageArchiveUnavailableException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

interface AccountMessageArchiveCipher {
    fun encrypt(
        owner: AccountMessageArchiveOwner,
        aad: ByteArray,
        plaintext: ByteArray,
        allowKeyCreation: Boolean,
    ): EncryptedAccountMessageArchiveRecord

    fun decrypt(
        owner: AccountMessageArchiveOwner,
        aad: ByteArray,
        record: EncryptedAccountMessageArchiveRecord,
    ): ByteArray

    fun eraseKey(owner: AccountMessageArchiveOwner)
}

interface AccountMessageArchiveStore {
    suspend fun read(
        owner: AccountMessageArchiveOwner,
        recordKey: String,
    ): AccountMessageArchiveRecord?

    suspend fun readPage(
        owner: AccountMessageArchiveOwner,
        afterRecordKey: String? = null,
        limit: Int,
    ): AccountMessageArchivePage

    /** `expectedVersion=null` creates a record and fails when that exact record already exists. */
    suspend fun write(
        owner: AccountMessageArchiveOwner,
        recordKey: String,
        expectedVersion: Long?,
        bytes: ByteArray,
    ): Long

    /** Cryptographically erases one installation-scoped archive before deleting its rows. */
    suspend fun eraseOwner(owner: AccountMessageArchiveOwner)

    /** Erases every locally retained installation archive for the canonical account. */
    suspend fun eraseAccount(ownerAccountId: String)
}

@Singleton
class AndroidKeystoreAccountMessageArchiveCipher @Inject constructor(
    @ApplicationContext private val context: Context,
) :
    AccountMessageArchiveCipher {
    private val keyHealth = context.getSharedPreferences(
        KEY_HEALTH_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun encrypt(
        owner: AccountMessageArchiveOwner,
        aad: ByteArray,
        plaintext: ByteArray,
        allowKeyCreation: Boolean,
    ): EncryptedAccountMessageArchiveRecord = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val resolvedKey = archiveKey(owner, allowKeyCreation)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, resolvedKey.key)
        } catch (error: Exception) {
            throw archiveKeyOperationFailure(owner, error, resolvedKey.createdNow)
        }
        val ciphertext = completeKeystoreCipherOperation(
            operation = {
                cipher.updateAAD(aad)
                cipher.doFinal(plaintext)
            },
            classifyFailure = {
                archiveKeyOperationFailure(owner, it, resolvedKey.createdNow)
            },
            onSuccess = { clearKeyFailureObservations(owner) },
        )
        EncryptedAccountMessageArchiveRecord(
            iv = cipher.iv.copyOf(),
            ciphertext = ciphertext,
        )
    } catch (error: Exception) {
        throw AccountMessageArchiveUnavailableException(
            "Account message archive could not be encrypted",
            error,
        )
    }

    override fun decrypt(
        owner: AccountMessageArchiveOwner,
        aad: ByteArray,
        record: EncryptedAccountMessageArchiveRecord,
    ): ByteArray = try {
        require(record.iv.size == GCM_IV_BYTES) { "Invalid account message archive IV" }
        require(record.ciphertext.size >= GCM_TAG_BYTES) {
            "Invalid account message archive ciphertext"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val resolvedKey = archiveKey(owner, allowCreation = false)
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                // Retained archive ciphertext must never be rebound to a replacement Android 9 key.
                resolvedKey.key,
                GCMParameterSpec(GCM_TAG_BITS, record.iv),
            )
        } catch (error: Exception) {
            // Some Android 9 providers return an alias-backed key handle, then report its permanent
            // loss only when Cipher.init asks Keystore to use it. Classify that exact path through
            // the same bounded proof as an unrecoverable getKey result.
            throw archiveKeyOperationFailure(owner, error, resolvedKey.createdNow)
        }
        completeKeystoreCipherOperation(
            operation = {
                cipher.updateAAD(aad)
                cipher.doFinal(record.ciphertext)
            },
            classifyFailure = {
                archiveKeyOperationFailure(owner, it, resolvedKey.createdNow)
            },
            onSuccess = { clearKeyFailureObservations(owner) },
        )
    } catch (error: Exception) {
        throw AccountMessageArchiveUnavailableException(
            "Account message archive failed authenticated decryption",
            error,
        )
    }

    @Synchronized
    override fun eraseKey(owner: AccountMessageArchiveOwner) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val alias = accountMessageArchiveKeyAlias(owner)
            // Android 9/OEM providers can temporarily hide an existing alias. Visibility is not
            // proof of absence, so always ask Keystore to delete the exact owner-scoped entry.
            deleteKeystoreAliasAndVerifyAbsent(
                deleteEntry = { keyStore.deleteEntry(alias) },
                aliasExists = { keyStore.containsAlias(alias) },
            )
            clearKeyFailureObservations(owner)
        } catch (error: Exception) {
            throw AccountMessageArchiveUnavailableException(
                "Account message archive key could not be erased",
                error,
            )
        }
    }

    @Synchronized
    private fun archiveKey(
        owner: AccountMessageArchiveOwner,
        allowCreation: Boolean,
    ): AccountMessageArchiveKeyResolution<SecretKey> {
        var freshKeyGenerationStarted = false
        return try {
            resolveAccountMessageArchiveKeyWithCreationStatus(
                allowCreation = allowCreation,
                loadExisting = {
                    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    val alias = accountMessageArchiveKeyAlias(owner)
                    val existing = keyStore.getKey(alias, null) as? SecretKey
                    if (existing == null && keyStore.containsAlias(alias)) {
                        throw UnrecoverableKeyException(
                            "Android Keystore retained an archive alias without a key handle",
                        )
                    }
                    existing
                        // A returned handle disproves a missing alias, but Cipher.init must still
                        // prove that the provider can recover and use its underlying key material.
                        ?.also { clearMissingKeyObservations(owner) }
                },
                createNew = {
                    generateKey(owner) { freshKeyGenerationStarted = true }
                },
                missingKeyFailure = { missingKeyFailure(owner) },
                classifyExistingAliasFailure = {
                    classifyRetryableAliasAccessFailure(owner, it)
                },
            )
        } catch (error: Exception) {
            // Empty-table bootstrap permission belongs only to a generation attempt that actually
            // reached KeyGenerator. A returned or throwing existing alias must use the same bounded
            // Android 9 recovery proof as an archive that already has ciphertext rows.
            throw classifyAccountMessageArchiveKeyOperationFailure(
                error = error,
                keyCreatedNow = freshKeyGenerationStarted,
                classifyExistingAliasFailure = {
                    classifyRetryableAliasAccessFailure(owner, it)
                },
            )
        }
    }

    @Synchronized
    private fun archiveKeyOperationFailure(
        owner: AccountMessageArchiveOwner,
        error: Exception,
        keyCreatedNow: Boolean,
    ): Exception = classifyAccountMessageArchiveKeyOperationFailure(
        error = error,
        keyCreatedNow = keyCreatedNow,
        classifyExistingAliasFailure = {
            classifyRetryableAliasAccessFailure(owner, it)
        },
    )

    /** Provider failures advance a loss proof only when a separate alias probe corroborates them. */
    @Synchronized
    private fun classifyRetryableAliasAccessFailure(
        owner: AccountMessageArchiveOwner,
        error: Exception,
    ): RuntimeException {
        val requiresUserAuthentication = generateSequence<Throwable>(error) { it.cause }
            .any { it is UserNotAuthenticatedException }
        if (requiresUserAuthentication) clearKeyFailureObservations(owner)
        val aliasPresent = runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .containsAlias(accountMessageArchiveKeyAlias(owner))
        }.getOrNull()
        val unrecoverable = error.hasUnrecoverableAccountMessageArchiveKeyCause()
        when {
            aliasPresent == false -> clearUnrecoverableKeyObservations(owner)
            aliasPresent == true && unrecoverable -> clearMissingKeyObservations(owner)
            aliasPresent == true -> clearKeyFailureObservations(owner)
        }
        return classifyAccountMessageArchiveKeyAccessFailure(
            cause = error,
            userAuthenticationRequired = requiresUserAuthentication,
            aliasPresent = aliasPresent,
            observeMissingAlias = { cause -> missingKeyFailure(owner, cause) },
            observeUnrecoverableAlias = { cause -> unrecoverableKeyFailure(owner, cause) },
        )
    }

    @Synchronized
    private fun missingKeyFailure(
        owner: AccountMessageArchiveOwner,
        cause: Throwable? = null,
    ): RuntimeException {
        // A null getKey result is independently checked against alias visibility. A present alias
        // with no key handle is an unrecoverable-key observation, not proof that the alias vanished.
        if (cause == null) {
            val aliasPresent = runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    .containsAlias(accountMessageArchiveKeyAlias(owner))
            }.getOrNull()
            if (aliasPresent == true) return unrecoverableKeyFailure(owner)
            if (aliasPresent == null) {
                return AccountMessageArchiveKeyUnavailableException()
            }
        }
        clearUnrecoverableKeyObservations(owner)
        return observedKeyFailure(
            owner = owner,
            cause = cause,
            countPreference = missingCountPreference(owner),
            firstObservedAtPreference = firstMissingAtPreference(owner),
            permanentFailure = ::AccountMessageArchiveKeyPermanentlyMissingException,
        )
    }

    @Synchronized
    private fun unrecoverableKeyFailure(
        owner: AccountMessageArchiveOwner,
        cause: Throwable? = null,
    ): RuntimeException {
        clearMissingKeyObservations(owner)
        return observedKeyFailure(
            owner = owner,
            cause = cause,
            countPreference = unrecoverableCountPreference(owner),
            firstObservedAtPreference = firstUnrecoverableAtPreference(owner),
            permanentFailure = ::AccountMessageArchiveKeyPermanentlyUnrecoverableException,
        )
    }

    private fun observedKeyFailure(
        owner: AccountMessageArchiveOwner,
        cause: Throwable?,
        countPreference: String,
        firstObservedAtPreference: String,
        permanentFailure: (Throwable?) -> RuntimeException,
    ): RuntimeException {
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
            nowEpochMillis = System.currentTimeMillis(),
        )
        val persisted = keyHealth.edit()
            .putInt(countPreference, observed.consecutiveUnlockedMisses)
            .putLong(firstObservedAtPreference, observed.firstUnlockedMissAtEpochMillis)
            .commit()
        return if (persisted && observed.permanentlyMissing) {
            permanentFailure(cause)
        } else {
            AccountMessageArchiveKeyUnavailableException(cause)
        }
    }

    @Synchronized
    private fun clearMissingKeyObservations(owner: AccountMessageArchiveOwner) {
        val countPreference = missingCountPreference(owner)
        val firstObservedAtPreference = firstMissingAtPreference(owner)
        if (keyHealth.contains(countPreference) || keyHealth.contains(firstObservedAtPreference)) {
            keyHealth.edit()
                .remove(countPreference)
                .remove(firstObservedAtPreference)
                .apply()
        }
    }

    @Synchronized
    private fun clearUnrecoverableKeyObservations(owner: AccountMessageArchiveOwner) {
        val countPreference = unrecoverableCountPreference(owner)
        val firstObservedAtPreference = firstUnrecoverableAtPreference(owner)
        if (keyHealth.contains(countPreference) || keyHealth.contains(firstObservedAtPreference)) {
            keyHealth.edit()
                .remove(countPreference)
                .remove(firstObservedAtPreference)
                .apply()
        }
    }

    @Synchronized
    private fun clearKeyFailureObservations(owner: AccountMessageArchiveOwner) {
        clearMissingKeyObservations(owner)
        clearUnrecoverableKeyObservations(owner)
    }

    private fun missingCountPreference(owner: AccountMessageArchiveOwner): String =
        "${accountMessageArchiveKeyAlias(owner)}:missing_count"

    private fun firstMissingAtPreference(owner: AccountMessageArchiveOwner): String =
        "${accountMessageArchiveKeyAlias(owner)}:first_missing_at"

    private fun unrecoverableCountPreference(owner: AccountMessageArchiveOwner): String =
        "${accountMessageArchiveKeyAlias(owner)}:unrecoverable_count"

    private fun firstUnrecoverableAtPreference(owner: AccountMessageArchiveOwner): String =
        "${accountMessageArchiveKeyAlias(owner)}:first_unrecoverable_at"

    @Synchronized
    private fun generateKey(
        owner: AccountMessageArchiveOwner,
        onFreshGenerationStarted: () -> Unit,
    ): AccountMessageArchiveKeyResolution<SecretKey> {
        val alias = accountMessageArchiveKeyAlias(owner)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { existing ->
            return AccountMessageArchiveKeyResolution(existing, createdNow = false)
        }
        if (keyStore.containsAlias(alias)) {
            throw UnrecoverableKeyException(
                "Android Keystore retained an archive alias without a key handle",
            )
        }

        onFreshGenerationStarted()
        val generated = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
        return AccountMessageArchiveKeyResolution(generated, createdNow = true)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_HEALTH_PREFERENCES = "kit_pay_account_message_archive_key_health_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val GCM_IV_BYTES = 12
    }
}

@Singleton
class RoomAccountMessageArchiveStore @Inject constructor(
    private val database: KitWalletDatabase,
    private val cipher: AccountMessageArchiveCipher,
) : AccountMessageArchiveStore {
    private val records get() = database.accountMessageArchiveDao()
    private val mutex = Mutex()

    override suspend fun read(
        owner: AccountMessageArchiveOwner,
        recordKey: String,
    ): AccountMessageArchiveRecord? = mutex.withLock {
        validateAccountMessageArchiveRecordKey(recordKey)
        val stored = records.get(
            owner.ownerAccountId,
            owner.installationId,
            recordKey,
        ) ?: return@withLock null
        try {
            decodeStored(owner, stored)
        } catch (error: Throwable) {
            if (!error.hasPermanentlyMissingAccountMessageArchiveKey()) throw error
            abandonUnreadableOwnerLocked(owner)
            null
        }
    }

    override suspend fun readPage(
        owner: AccountMessageArchiveOwner,
        afterRecordKey: String?,
        limit: Int,
    ): AccountMessageArchivePage = mutex.withLock {
        afterRecordKey?.let(::validateAccountMessageArchiveRecordKey)
        require(limit in 1..MAX_ACCOUNT_MESSAGE_ARCHIVE_PAGE_SIZE) {
            "Account message archive page limit must be between 1 and " +
                MAX_ACCOUNT_MESSAGE_ARCHIVE_PAGE_SIZE
        }
        val stored = records.page(
            ownerAccountId = owner.ownerAccountId,
            installationId = owner.installationId,
            afterRecordKey = afterRecordKey,
            limit = limit + 1,
        )
        val decoded = ArrayList<AccountMessageArchiveRecord>(limit)
        try {
            require(stored.size <= limit + 1) {
                "Account message archive query exceeded its requested bound"
            }
            var previousKey = afterRecordKey
            stored.forEach { entity ->
                require(entity.ownerAccountId == owner.ownerAccountId) {
                    "Account message archive query returned another owner"
                }
                require(entity.installationId == owner.installationId) {
                    "Account message archive query returned another installation"
                }
                validateAccountMessageArchiveRecordKey(entity.recordKey)
                require(previousKey == null || entity.recordKey > previousKey!!) {
                    "Account message archive query is not strictly ordered"
                }
                previousKey = entity.recordKey
            }
            stored.take(limit).forEach { decoded += decodeStored(owner, it) }
            AccountMessageArchivePage(
                records = decoded,
                nextAfterRecordKey = if (stored.size > limit) {
                    decoded.last().recordKey
                } else {
                    null
                },
            )
        } catch (error: Throwable) {
            decoded.forEach { it.bytes.fill(0) }
            if (!error.hasPermanentlyMissingAccountMessageArchiveKey()) throw error
            abandonUnreadableOwnerLocked(owner)
            AccountMessageArchivePage(emptyList(), nextAfterRecordKey = null)
        } finally {
            stored.forEach(AccountMessageArchiveEntity::wipeEncryptedBytes)
        }
    }

    override suspend fun write(
        owner: AccountMessageArchiveOwner,
        recordKey: String,
        expectedVersion: Long?,
        bytes: ByteArray,
    ): Long = mutex.withLock {
        validateAccountMessageArchiveRecordKey(recordKey)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ACCOUNT_MESSAGE_ARCHIVE_RECORD_BYTES) {
            "Account message archive record must contain 1 to " +
                "$MAX_ACCOUNT_MESSAGE_ARCHIVE_RECORD_BYTES bytes"
        }
        val plaintext = bytes.copyOf()
        var bootstrapEncryptionCompleted = false
        suspend fun writeOnce(expectedVersionForAttempt: Long?): Long = database.withTransaction {
            bootstrapEncryptionCompleted = false
            val existingCount = records.countForOwner(
                owner.ownerAccountId,
                owner.installationId,
            )
            val current = records.get(
                owner.ownerAccountId,
                owner.installationId,
                recordKey,
            )
            try {
                val newVersion = when {
                    expectedVersionForAttempt == null && current == null -> 1L
                    expectedVersionForAttempt == null ->
                        throw AccountMessageArchiveConflictException(
                            "Account message archive record already exists",
                        )
                    current == null || current.version != expectedVersionForAttempt ->
                        throw AccountMessageArchiveConflictException(
                            "Account message archive record changed before it could be committed",
                        )
                    expectedVersionForAttempt == Long.MAX_VALUE ->
                        throw AccountMessageArchiveConflictException(
                            "Account message archive record version is exhausted",
                        )
                    else -> expectedVersionForAttempt + 1L
                }
                val aad = accountMessageArchiveAad(owner, recordKey, newVersion)
                val encrypted = try {
                    cipher.encrypt(
                        owner = owner,
                        aad = aad,
                        plaintext = plaintext,
                        allowKeyCreation = accountMessageArchiveKeyCreationAllowed(
                            existingOwnerRecordCount = existingCount,
                        ),
                    )
                } finally {
                    aad.fill(0)
                }
                if (existingCount == 0) bootstrapEncryptionCompleted = true
                try {
                    val updatedAt = System.currentTimeMillis()
                    if (current == null) {
                        records.insert(
                            AccountMessageArchiveEntity(
                                ownerAccountId = owner.ownerAccountId,
                                installationId = owner.installationId,
                                recordKey = recordKey,
                                version = newVersion,
                                iv = encrypted.iv,
                                ciphertext = encrypted.ciphertext,
                                updatedAtEpochMillis = updatedAt,
                            ),
                        )
                    } else {
                        val updated = records.compareAndSet(
                            ownerAccountId = owner.ownerAccountId,
                            installationId = owner.installationId,
                            recordKey = recordKey,
                            expectedVersion = requireNotNull(expectedVersionForAttempt),
                            newVersion = newVersion,
                            iv = encrypted.iv,
                            ciphertext = encrypted.ciphertext,
                            updatedAtEpochMillis = updatedAt,
                        )
                        if (updated != 1) {
                            throw AccountMessageArchiveConflictException(
                                "Account message archive record changed before it could be committed",
                            )
                        }
                    }
                    newVersion
                } finally {
                    encrypted.iv.fill(0)
                    encrypted.ciphertext.fill(0)
                }
            } finally {
                current?.wipeEncryptedBytes()
            }
        }

        try {
            var recoveryExpectedVersion = expectedVersion
            repeat(MAX_PERMANENT_KEY_WRITE_RECOVERY_ATTEMPTS) { recoveryAttempt ->
                try {
                    return@withLock writeOnce(recoveryExpectedVersion)
                } catch (error: Exception) {
                    if (bootstrapEncryptionCompleted) {
                        // Keystore is outside Room's transaction. If the first encrypted row did
                        // not commit, remove the otherwise unaddressable bootstrap alias.
                        try {
                            abandonUnreadableOwnerLocked(owner)
                        } catch (cleanupFailure: Exception) {
                            error.addSuppressed(cleanupFailure)
                        }
                        throw error
                    }
                    if (recoveryAttempt > 0 ||
                        !error.hasPermanentlyMissingAccountMessageArchiveKey()
                    ) {
                        throw error
                    }
                    // A proved-dead archive key protects no readable history. Remove its exact
                    // alias and rows, then give the caller's still-owned plaintext one fresh-key
                    // attempt. This is the only path that recovers an orphan alias with no rows.
                    try {
                        abandonUnreadableOwnerLocked(owner)
                    } catch (cleanupFailure: Exception) {
                        error.addSuppressed(cleanupFailure)
                        throw error
                    }
                    recoveryExpectedVersion = null
                }
            }
            error("Account message archive permanent-key recovery bound was exhausted")
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun eraseOwner(owner: AccountMessageArchiveOwner) = mutex.withLock {
        eraseOwnerLocked(owner)
    }

    override suspend fun eraseAccount(ownerAccountId: String) = mutex.withLock {
        requireCanonicalArchiveUuid(ownerAccountId, "archive owner account ID")
        val installations = records.installationIdsForAccount(ownerAccountId)
        var failure: Exception? = null
        installations.forEach { installationId ->
            val owner = AccountMessageArchiveOwner(ownerAccountId, installationId)
            try {
                cipher.eraseKey(owner)
            } catch (error: Exception) {
                failure?.addSuppressed(error)
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
        // Rows retain every installation ID needed to retry an alias deletion. Remove them only
        // after every per-owner key is gone; otherwise a failed alias becomes unaddressable.
        records.deleteAccount(ownerAccountId)
        Unit
    }

    private suspend fun eraseOwnerLocked(owner: AccountMessageArchiveOwner) {
        cipher.eraseKey(owner)
        records.deleteOwner(owner.ownerAccountId, owner.installationId)
    }

    /**
     * A display-only archive with ciphertext but no key cannot be recovered. Abandon its rows so a
     * later accepted projection can bootstrap a fresh key instead of retrying the dead alias
     * forever. Rows remain as a durable retry address until the unusable alias is actually gone.
     */
    private suspend fun abandonUnreadableOwnerLocked(owner: AccountMessageArchiveOwner) {
        cipher.eraseKey(owner)
        records.deleteOwner(owner.ownerAccountId, owner.installationId)
    }

    private fun decodeStored(
        owner: AccountMessageArchiveOwner,
        stored: AccountMessageArchiveEntity,
    ): AccountMessageArchiveRecord {
        require(stored.ownerAccountId == owner.ownerAccountId) {
            "Account message archive record belongs to another owner"
        }
        require(stored.installationId == owner.installationId) {
            "Account message archive record belongs to another installation"
        }
        validateAccountMessageArchiveRecordKey(stored.recordKey)
        require(stored.version > 0L && stored.updatedAtEpochMillis > 0L) {
            "Invalid account message archive record metadata"
        }
        val aad = accountMessageArchiveAad(owner, stored.recordKey, stored.version)
        val plaintext = try {
            cipher.decrypt(
                owner = owner,
                aad = aad,
                record = EncryptedAccountMessageArchiveRecord(
                    iv = stored.iv,
                    ciphertext = stored.ciphertext,
                ),
            )
        } finally {
            aad.fill(0)
            stored.wipeEncryptedBytes()
        }
        return try {
            require(plaintext.isNotEmpty() &&
                plaintext.size <= MAX_ACCOUNT_MESSAGE_ARCHIVE_RECORD_BYTES
            ) { "Invalid account message archive plaintext size" }
            AccountMessageArchiveRecord(
                owner = owner,
                recordKey = stored.recordKey,
                version = stored.version,
                bytes = plaintext,
                updatedAtEpochMillis = stored.updatedAtEpochMillis,
            )
        } catch (error: Throwable) {
            plaintext.fill(0)
            throw error
        }
    }
}

@VisibleForTesting
internal fun accountMessageArchiveAad(
    owner: AccountMessageArchiveOwner,
    recordKey: String,
    version: Long,
): ByteArray {
    validateAccountMessageArchiveRecordKey(recordKey)
    require(version > 0L) { "Account message archive version must be positive" }
    val ownerBytes = owner.ownerAccountId.toByteArray(Charsets.UTF_8)
    val installationBytes = owner.installationId.toByteArray(Charsets.UTF_8)
    val recordKeyBytes = recordKey.toByteArray(Charsets.UTF_8)
    return try {
        ByteBuffer.allocate(
            ARCHIVE_AAD_MAGIC.size + Int.SIZE_BYTES +
                Int.SIZE_BYTES + ownerBytes.size +
                Int.SIZE_BYTES + installationBytes.size +
                Int.SIZE_BYTES + recordKeyBytes.size + Long.SIZE_BYTES,
        )
            .put(ARCHIVE_AAD_MAGIC)
            .putInt(ARCHIVE_AAD_SCHEMA_VERSION)
            .putInt(ownerBytes.size)
            .put(ownerBytes)
            .putInt(installationBytes.size)
            .put(installationBytes)
            .putInt(recordKeyBytes.size)
            .put(recordKeyBytes)
            .putLong(version)
            .array()
    } finally {
        ownerBytes.fill(0)
        installationBytes.fill(0)
        recordKeyBytes.fill(0)
    }
}

/** Each account+installation owns a distinct opaque Android-Keystore alias. */
@VisibleForTesting
internal fun accountMessageArchiveKeyAlias(owner: AccountMessageArchiveOwner): String {
    val ownerBytes = owner.ownerAccountId.toByteArray(Charsets.UTF_8)
    val installationBytes = owner.installationId.toByteArray(Charsets.UTF_8)
    val digest = try {
        MessageDigest.getInstance("SHA-256").run {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(ownerBytes.size).array())
            update(ownerBytes)
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(installationBytes.size).array())
            update(installationBytes)
            digest()
        }
    } finally {
        ownerBytes.fill(0)
        installationBytes.fill(0)
    }
    return try {
        ACCOUNT_MESSAGE_ARCHIVE_KEY_ALIAS_PREFIX + digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    } finally {
        digest.fill(0)
    }
}

@VisibleForTesting
internal fun accountMessageArchiveKeyCreationAllowed(
    existingOwnerRecordCount: Int,
): Boolean {
    require(existingOwnerRecordCount >= 0) {
        "Account message archive record count cannot be negative"
    }
    return existingOwnerRecordCount == 0
}

@VisibleForTesting
internal fun <T : Any> resolveAccountMessageArchiveKey(
    allowCreation: Boolean,
    loadExisting: () -> T?,
    createNew: () -> T,
    missingKeyFailure: () -> RuntimeException = { AccountMessageArchiveKeyUnavailableException() },
): T {
    loadExisting()?.let { return it }
    if (!allowCreation) throw missingKeyFailure()
    return createNew()
}

/**
 * Production resolution retains whether this invocation actually generated the returned alias.
 * An empty owner archive permits bootstrap, but cannot make an existing unusable alias fresh.
 */
@VisibleForTesting
internal fun <T : Any> resolveAccountMessageArchiveKeyWithCreationStatus(
    allowCreation: Boolean,
    loadExisting: () -> T?,
    createNew: () -> AccountMessageArchiveKeyResolution<T>,
    missingKeyFailure: () -> RuntimeException = { AccountMessageArchiveKeyUnavailableException() },
    classifyExistingAliasFailure: ((Exception) -> Exception)? = null,
): AccountMessageArchiveKeyResolution<T> {
    val existing = try {
        loadExisting()
    } catch (error: Exception) {
        val classifier = classifyExistingAliasFailure ?: throw error
        throw classifyAccountMessageArchiveKeyOperationFailure(
            error = error,
            keyCreatedNow = false,
            classifyExistingAliasFailure = classifier,
        )
    }
    existing?.let { resolved ->
        return AccountMessageArchiveKeyResolution(resolved, createdNow = false)
    }
    if (!allowCreation) throw missingKeyFailure()
    return createNew()
}

/** Existing archive aliases use bounded Android-9 recovery even when their table is empty. */
@VisibleForTesting
internal fun classifyAccountMessageArchiveKeyOperationFailure(
    error: Exception,
    keyCreatedNow: Boolean,
    classifyExistingAliasFailure: (Exception) -> Exception,
): Exception = when {
    error.hasPermanentlyInvalidatedAccountMessageArchiveKey() ->
        AccountMessageArchiveKeyPermanentlyUnrecoverableException(error)
    keyCreatedNow ||
        error.hasAccountMessageArchiveKeyObservation() ||
        !error.isRetryableAccountMessageArchiveKeyFailure() -> error
    else -> classifyExistingAliasFailure(error)
}

@VisibleForTesting
internal class AccountMessageArchiveKeyUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException(
    "The account message archive key is unavailable",
    cause,
)

@VisibleForTesting
internal class AccountMessageArchiveKeyPermanentlyMissingException(
    cause: Throwable? = null,
) : IllegalStateException(
    "The account message archive key is permanently missing",
    cause,
)

@VisibleForTesting
internal class AccountMessageArchiveKeyPermanentlyUnrecoverableException(
    cause: Throwable? = null,
) : IllegalStateException(
    "The account message archive key is permanently unrecoverable",
    cause,
)

/** Generic provider errors never prove loss without a separate absent/unrecoverable alias probe. */
@VisibleForTesting
internal fun classifyAccountMessageArchiveKeyAccessFailure(
    cause: Throwable,
    userAuthenticationRequired: Boolean,
    aliasPresent: Boolean?,
    observeMissingAlias: (Throwable) -> RuntimeException,
    observeUnrecoverableAlias: (Throwable) -> RuntimeException,
): RuntimeException = when {
    userAuthenticationRequired -> AccountMessageArchiveKeyUnavailableException(cause)
    aliasPresent == false -> observeMissingAlias(cause)
    aliasPresent == true && cause.hasUnrecoverableAccountMessageArchiveKeyCause() ->
        observeUnrecoverableAlias(cause)
    else -> AccountMessageArchiveKeyUnavailableException(cause)
}

@VisibleForTesting
internal fun Throwable.hasPermanentlyMissingAccountMessageArchiveKey(): Boolean =
    generateSequence(this) { it.cause }
        .any {
            it is AccountMessageArchiveKeyPermanentlyMissingException ||
                it is AccountMessageArchiveKeyPermanentlyUnrecoverableException
        }

private fun Throwable.hasUnrecoverableAccountMessageArchiveKeyCause(): Boolean =
    generateSequence(this) { it.cause }
        .any {
            it is UnrecoverableKeyException ||
                it is KeyPermanentlyInvalidatedException ||
                it is InvalidKeyException
        }

private fun Throwable.hasPermanentlyInvalidatedAccountMessageArchiveKey(): Boolean =
    generateSequence(this) { it.cause }.any { it is KeyPermanentlyInvalidatedException }

private fun Throwable.hasAccountMessageArchiveKeyObservation(): Boolean =
    generateSequence(this) { it.cause }
        .any {
            it is AccountMessageArchiveKeyUnavailableException ||
                it is AccountMessageArchiveKeyPermanentlyMissingException ||
                it is AccountMessageArchiveKeyPermanentlyUnrecoverableException
        }

@VisibleForTesting
internal fun Throwable.isRetryableAccountMessageArchiveKeyFailure(): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause is UserNotAuthenticatedException ||
            cause.javaClass.name == "android.security.KeyStoreException" ||
            cause is InvalidKeyException ||
            cause is KeyStoreException ||
            cause is UnrecoverableKeyException ||
            cause is ProviderException
    }

private fun requireCanonicalArchiveUuid(value: String, field: String) {
    require(runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
        "$field must be a canonical lowercase UUID"
    }
}

private fun validateAccountMessageArchiveRecordKey(recordKey: String) {
    require(ACCOUNT_MESSAGE_ARCHIVE_RECORD_KEY.matches(recordKey)) {
        "Invalid account message archive record key"
    }
}

private fun AccountMessageArchiveEntity.wipeEncryptedBytes() {
    iv.fill(0)
    ciphertext.fill(0)
}

private const val MAX_ACCOUNT_MESSAGE_ARCHIVE_PAGE_SIZE = 100
private const val MAX_ACCOUNT_MESSAGE_ARCHIVE_RECORD_BYTES = 128 * 1024
private const val MAX_PERMANENT_KEY_WRITE_RECOVERY_ATTEMPTS = 2
private const val ARCHIVE_AAD_SCHEMA_VERSION = 1
private val ARCHIVE_AAD_MAGIC = "kit.account-message-archive".toByteArray(Charsets.US_ASCII)
private const val ACCOUNT_MESSAGE_ARCHIVE_KEY_ALIAS_PREFIX = "kit_pay_account_message_archive_aes_v1_"
private val ACCOUNT_MESSAGE_ARCHIVE_RECORD_KEY = Regex("^[A-Za-z0-9._:@-]{1,160}$")
