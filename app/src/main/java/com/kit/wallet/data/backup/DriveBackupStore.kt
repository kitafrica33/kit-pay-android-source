package com.kit.wallet.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How often Kit Pay backs itself up without being asked.
 *
 * Off is the default. A backup is a copy of somebody's private conversations leaving their phone,
 * and that is a decision they make, not one made for them.
 */
enum class MessageBackupFrequency(val label: String, val intervalMillis: Long?) {
    OFF("Off", null),
    DAILY("Daily", 24L * 60 * 60 * 1000),
    WEEKLY("Weekly", 7L * 24 * 60 * 60 * 1000),
    MONTHLY("Monthly", 30L * 24 * 60 * 60 * 1000),
}

/** Everything the backup feature remembers between runs, for exactly one signed-in account. */
data class DriveBackupState(
    val accountId: String? = null,
    val connected: Boolean = false,
    val frequency: MessageBackupFrequency = MessageBackupFrequency.OFF,
    val requiresUnmeteredNetwork: Boolean = true,
    val recoveryCodeConfirmed: Boolean = false,
    val driveFileId: String? = null,
    val lastBackupAtEpochMillis: Long? = null,
    val lastBackupBytes: Long? = null,
    val lastBackupMessageCount: Int? = null,
) {
    val everBackedUp: Boolean get() = lastBackupAtEpochMillis != null

    fun isDue(now: Long): Boolean {
        val interval = frequency.intervalMillis ?: return false
        val last = lastBackupAtEpochMillis ?: return true
        // A clock that jumped backwards must not postpone a backup indefinitely.
        return last > now || now - last >= interval
    }
}

/**
 * The backup key and the schedule, at rest.
 *
 * No Google credential is kept here, or anywhere else in the app. Play Services holds the grant and
 * issues a short-lived access token per operation, so the only thing worth stealing from this file
 * is the backup key — which is sealed under a non-exportable Android Keystore key, making the file
 * on disk useless to anything that reads it off the device. That also means it does not survive a factory reset or
 * moving to a new phone — which is the point of the recovery code, and why the user is shown one
 * before the first backup rather than after they need it.
 *
 * State belongs to one account. Signing in as somebody else finds no state at all rather than
 * inheriting the previous user's grant.
 */
@Singleton
class DriveBackupStore @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val adapter = moshi.adapter(StoredBackupState::class.java)
    private val mutex = Mutex()
    private val stored = MutableStateFlow(read())

    val state: StateFlow<DriveBackupState> = stored.asStateFlow()

    fun forAccount(accountId: String?): DriveBackupState {
        val current = stored.value
        return if (accountId != null && current.accountId == accountId) {
            current
        } else {
            DriveBackupState(accountId = accountId)
        }
    }

    suspend fun update(
        accountId: String,
        transform: (DriveBackupState) -> DriveBackupState,
    ): DriveBackupState = mutex.withLock {
        val base = forAccount(accountId)
        val updated = transform(base).copy(accountId = accountId)
        writeLocked(updated, secretLocked(accountId))
        updated
    }

    /** The backup key for [accountId], minting one on first use. */
    suspend fun key(accountId: String): KitBackupKey = mutex.withLock {
        secretLocked(accountId)?.let { return@withLock it }
        val minted = KitBackupKey.random()
        writeLocked(forAccount(accountId).copy(accountId = accountId), minted)
        minted
    }

    /** Adopts a key the user restored from a recovery code, replacing any local one. */
    suspend fun adoptKey(accountId: String, key: KitBackupKey): Unit = mutex.withLock {
        writeLocked(forAccount(accountId).copy(accountId = accountId), key)
    }

    /** Records that Google has granted the app-folder scope for this account. */
    suspend fun connect(accountId: String): DriveBackupState = mutex.withLock {
        val updated = forAccount(accountId).copy(accountId = accountId, connected = true)
        writeLocked(updated, secretLocked(accountId))
        updated
    }

    /**
     * Forgets the Google grant but keeps the backup key and the schedule preference, so a user who
     * reconnects is not silently given a new key that cannot open the archive already in Drive.
     */
    suspend fun disconnect(accountId: String): DriveBackupState = mutex.withLock {
        val updated = forAccount(accountId).copy(
            accountId = accountId,
            connected = false,
            frequency = MessageBackupFrequency.OFF,
            driveFileId = null,
        )
        writeLocked(updated, secretLocked(accountId))
        updated
    }

    /** Sign-out and account replacement: nothing about the previous user may remain. */
    fun clear() {
        preferences.edit().remove(KEY_STATE).commit()
        stored.value = DriveBackupState()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun secretLocked(accountId: String): KitBackupKey? {
        val current = readStored()?.takeIf { it.accountId == accountId } ?: return null
        val encoded = current.backupKey ?: return null
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        return bytes?.takeIf { it.size == KitBackupKey.KEY_BYTES }?.let(::KitBackupKey)
    }

    private fun writeLocked(state: DriveBackupState, key: KitBackupKey?) {
        val keyBytes = key?.bytes()
        val encoded = try {
            StoredBackupState(
                accountId = state.accountId,
                connected = state.connected,
                frequency = state.frequency.name,
                requiresUnmeteredNetwork = state.requiresUnmeteredNetwork,
                recoveryCodeConfirmed = state.recoveryCodeConfirmed,
                driveFileId = state.driveFileId,
                lastBackupAtEpochMillis = state.lastBackupAtEpochMillis,
                lastBackupBytes = state.lastBackupBytes,
                lastBackupMessageCount = state.lastBackupMessageCount,
                backupKey = keyBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            )
        } finally {
            keyBytes?.fill(0)
        }
        val committed = preferences.edit()
            .putString(KEY_STATE, encrypt(adapter.toJson(encoded)))
            .commit()
        check(committed) { "The backup settings could not be saved" }
        stored.value = state
    }

    private fun read(): DriveBackupState = readStored()?.let { stored ->
        DriveBackupState(
            accountId = stored.accountId,
            connected = stored.connected,
            frequency = runCatching { MessageBackupFrequency.valueOf(stored.frequency) }
                .getOrDefault(MessageBackupFrequency.OFF),
            requiresUnmeteredNetwork = stored.requiresUnmeteredNetwork,
            recoveryCodeConfirmed = stored.recoveryCodeConfirmed,
            driveFileId = stored.driveFileId,
            lastBackupAtEpochMillis = stored.lastBackupAtEpochMillis,
            lastBackupBytes = stored.lastBackupBytes,
            lastBackupMessageCount = stored.lastBackupMessageCount,
        )
    } ?: DriveBackupState()

    private fun readStored(): StoredBackupState? {
        val blob = preferences.getString(KEY_STATE, null) ?: return null
        return runCatching { adapter.fromJson(decrypt(blob)) }.getOrNull()
    }

    private fun encrypt(json: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, sealed)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(SEPARATOR)
        require(parts.size == 2) { "Malformed stored backup settings" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val sealed = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
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
        const val PREFERENCES = "kit_wallet_drive_backup"
        const val KEY_STATE = "state_v1"
        const val KEY_ALIAS = "kit_wallet_drive_backup_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = "."
    }
}

@JsonClass(generateAdapter = true)
internal data class StoredBackupState(
    val accountId: String?,
    val connected: Boolean,
    val frequency: String,
    val requiresUnmeteredNetwork: Boolean,
    val recoveryCodeConfirmed: Boolean,
    val driveFileId: String?,
    val lastBackupAtEpochMillis: Long?,
    val lastBackupBytes: Long?,
    val lastBackupMessageCount: Int?,
    val backupKey: String?,
)
