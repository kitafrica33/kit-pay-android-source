package com.kit.wallet.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.worker.SecureMessagingSyncScheduler
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists the device session as one AES-GCM authenticated blob. The non-exportable
 * AES key lives in Android Keystore; SharedPreferences only contains IV + ciphertext.
 */
@Singleton
class KeystoreSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
    private val messagingLifecycle: SecureMessagingSessionLifecycle,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val messagingSyncScheduler: dagger.Lazy<SecureMessagingSyncScheduler>,
) : SessionStore {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val adapter = moshi.adapter(SessionDiskPayload::class.java)
    private val mutex = Mutex()
    private val _session = MutableStateFlow(readSafely())
    @Volatile
    private var sessionSnapshot = SessionSnapshot(
        revision = 0L,
        fence = _session.value?.fence(),
    )

    init {
        if (_session.value == null) {
            retryPendingMessagingErasure()
        } else {
            activateRestoredMessagingSession()
        }
    }

    override val session: StateFlow<SessionTokens?> = _session.asStateFlow()

    override fun current(): SessionTokens? = _session.value

    override fun snapshot(): SessionSnapshot = sessionSnapshot

    override suspend fun save(tokens: SessionTokens) {
        tokens.requireValidCredentials()

        mutex.withLock { persistLocked(tokens) }
    }

    override suspend fun saveIfUnchanged(
        expected: SessionSnapshot,
        tokens: SessionTokens,
    ): Boolean {
        tokens.requireValidCredentials()
        return mutex.withLock {
            if (sessionSnapshot != expected) return@withLock false
            persistLocked(tokens)
            true
        }
    }

    override suspend fun adoptRefreshedCredentialsIfCurrent(
        expectedCredentials: SessionTokens,
        refreshedCredentials: SessionTokens,
    ): Boolean {
        refreshedCredentials.requireValidCredentials()
        return mutex.withLock {
            val latest = _session.value ?: return@withLock false
            if (latest.sessionId != expectedCredentials.sessionId ||
                latest.accessToken != expectedCredentials.accessToken ||
                latest.refreshToken != expectedCredentials.refreshToken
            ) {
                return@withLock false
            }
            check(refreshedCredentials.sessionId == latest.sessionId) {
                "A token refresh cannot replace the authenticated session epoch"
            }
            persistLocked(
                refreshedCredentials.copy(
                    accountId = latest.accountId ?: refreshedCredentials.accountId,
                    cacheScopeId = latest.cacheScopeId,
                    profileSetupState = if (
                        latest.profileSetupState != expectedCredentials.profileSetupState
                    ) {
                        latest.profileSetupState
                    } else {
                        refreshedCredentials.profileSetupState
                    },
                    messagingResetProof = latest.messagingResetProof,
                ),
            )
            true
        }
    }

    override suspend fun updateProfileSetupState(
        expected: SessionFence,
        state: ProfileSetupState,
    ): Boolean = mutex.withLock {
        val current = _session.value
        if (current?.fence() != expected) return@withLock false
        if (current.profileSetupState != state) {
            persistLocked(current.copy(profileSetupState = state))
        }
        true
    }

    override suspend fun <T> withCurrentSession(
        expected: SessionFence,
        block: suspend (SessionTokens) -> T,
    ): T = mutex.withLock {
        val current = _session.value ?: throw SessionInvalidatedException()
        if (current.fence() != expected) throw SessionInvalidatedException()
        block(current)
    }

    override suspend fun clearIfCurrent(expected: SessionFence): Boolean = mutex.withLock {
        if (_session.value?.fence() != expected) return@withLock false
        clearLocked()
        true
    }

    override suspend fun resetSecureMessagingStateIfCurrent(
        expected: SessionFence,
        activationFence: SecureMessagingSessionFence,
    ): Boolean = mutex.withLock {
        if (_session.value?.fence() != expected) return@withLock false

        markMessagingErasurePending()
        try {
            messagingLifecycle.resetForRecovery(activationFence)
        } catch (error: Throwable) {
            abandonSessionDuringPendingMessagingErasure()
            throw error
        }

        if (!preferences.edit().remove(KEY_MESSAGING_ERASURE_PENDING).commit()) {
            abandonSessionDuringPendingMessagingErasure()
            error("The secure messaging erasure marker could not be cleared")
        }
        try {
            messagingLifecycle.afterSessionSave()
        } catch (error: Throwable) {
            runCatching { markMessagingErasurePending() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            abandonSessionDuringPendingMessagingErasure()
            throw error
        }
        runCatching { messagingSyncScheduler.get().schedule() }
        true
    }

    override suspend fun recordMessagingResetPendingIfCurrent(
        expected: SessionFence,
        pending: SecureMessagingResetProofFence,
    ): Boolean = mutex.withLock {
        require(!pending.proved) { "A pending reset cannot contain a result epoch" }
        val current = _session.value ?: return@withLock false
        if (current.fence() != expected) return@withLock false
        val existing = current.messagingResetProof
        if (existing != null) {
            return@withLock existing.copy(resultingEnrollmentEpoch = null) == pending
        }
        persistLocked(current.copy(messagingResetProof = pending))
        true
    }

    override suspend fun recordMessagingResetProofIfCurrent(
        expected: SessionFence,
        proof: SecureMessagingResetProofFence,
    ): Boolean = mutex.withLock {
        val current = _session.value ?: return@withLock false
        if (current.fence() != expected || !proof.proved) return@withLock false
        val pending = current.messagingResetProof ?: return@withLock false
        if (pending.copy(resultingEnrollmentEpoch = null) !=
            proof.copy(resultingEnrollmentEpoch = null)
        ) {
            return@withLock false
        }
        persistLocked(current.copy(messagingResetProof = proof))
        true
    }

    override suspend fun clearMessagingResetProofIfCurrent(
        expected: SessionFence,
        proof: SecureMessagingResetProofFence,
    ): Boolean = mutex.withLock {
        val current = _session.value ?: return@withLock false
        if (current.fence() != expected || current.messagingResetProof != proof) {
            return@withLock false
        }
        persistLocked(current.copy(messagingResetProof = null))
        true
    }

    override suspend fun clear() {
        mutex.withLock { clearLocked() }
    }

    private suspend fun clearLocked() {
        var markerFailure: Throwable? = null
        try {
            markMessagingErasurePending()
        } catch (error: Throwable) {
            markerFailure = error
        }
        eraseMessagingThenClearSession(
            eraseMessaging = messagingLifecycle::beforeSessionClear,
            clearSession = { erasureSucceeded ->
                val removed = preferences.edit()
                    .remove(KEY_SESSION)
                    .putBoolean(KEY_MESSAGING_ERASURE_PENDING, !erasureSucceeded)
                    .commit()
                var invalidationFailure: Throwable? = null
                if (!removed) {
                    // If storage refuses the deletion, destroying the session AES key makes
                    // any surviving credential blob permanently unreadable after restart.
                    invalidationFailure = runCatching { invalidateSessionKey() }.exceptionOrNull()
                }
                publishSessionLocked(null)
                invalidationFailure?.let { throw it }
            },
        )
        markerFailure?.let { throw it }
    }

    private suspend fun persistLocked(tokens: SessionTokens) {
        val existing = _session.value
        val isSameSession = existing?.fence() == tokens.fence()
        if (!isSameSession) {
            markMessagingErasurePending()
            try {
                messagingLifecycle.beforeSessionSave(isSameSession = false)
            } catch (error: Throwable) {
                abandonSessionDuringPendingMessagingErasure()
                throw error
            }
        }
        val json = adapter.toJson(tokens.toDiskPayload())
        val encryptedSession = try {
            encrypt(json)
        } catch (error: Throwable) {
            if (!isSameSession) abandonSessionDuringPendingMessagingErasure()
            throw error
        }
        val persisted = preferences.edit()
            .putString(KEY_SESSION, encryptedSession)
            .remove(KEY_MESSAGING_ERASURE_PENDING)
            .commit()
        if (!persisted) {
            if (!isSameSession) abandonSessionDuringPendingMessagingErasure()
            error("The secure session could not be persisted")
        }
        publishSessionLocked(tokens)
        messagingLifecycle.afterSessionSave()
        runCatching { messagingSyncScheduler.get().schedule() }
    }

    private fun readSafely(): SessionTokens? {
        if (preferences.getBoolean(KEY_MESSAGING_ERASURE_PENDING, false)) {
            // A process may have died after the messaging key was deleted but before rows or the
            // old Kit session were cleared. Never reopen that session or its now-invalid state.
            preferences.edit().remove(KEY_SESSION).commit()
            return null
        }
        val encrypted = preferences.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            adapter.fromJson(decrypt(encrypted))?.toSessionTokens()
        }.getOrElse {
            // A restored app backup or invalidated lock-screen key must never leave a
            // half-readable credential behind. Force a fresh sign-in instead.
            preferences.edit()
                .remove(KEY_SESSION)
                .putBoolean(KEY_MESSAGING_ERASURE_PENDING, true)
                .commit()
            null
        }
    }

    private fun retryPendingMessagingErasure() {
        if (!preferences.getBoolean(KEY_MESSAGING_ERASURE_PENDING, false)) return
        applicationScope.launch {
            mutex.withLock {
                if (_session.value != null ||
                    !preferences.getBoolean(KEY_MESSAGING_ERASURE_PENDING, false)
                ) {
                    return@withLock
                }
                runCatching { messagingLifecycle.beforeSessionClear() }
                    .onSuccess {
                        preferences.edit().remove(KEY_MESSAGING_ERASURE_PENDING).commit()
                    }
            }
        }
    }

    private fun markMessagingErasurePending() {
        check(
            preferences.edit()
                .putBoolean(KEY_MESSAGING_ERASURE_PENDING, true)
                .commit(),
        ) { "The secure messaging erasure marker could not be persisted" }
    }

    private fun abandonSessionDuringPendingMessagingErasure() {
        preferences.edit()
            .remove(KEY_SESSION)
            .putBoolean(KEY_MESSAGING_ERASURE_PENDING, true)
            .commit()
        publishSessionLocked(null)
    }

    private fun publishSessionLocked(tokens: SessionTokens?) {
        _session.value = tokens
        sessionSnapshot = SessionSnapshot(
            revision = sessionSnapshot.revision + 1L,
            fence = tokens?.fence(),
        )
    }

    private fun invalidateSessionKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun activateRestoredMessagingSession() {
        applicationScope.launch {
            mutex.withLock {
                if (_session.value == null) return@withLock
                runCatching { messagingLifecycle.afterSessionSave() }
                    .onSuccess { runCatching { messagingSyncScheduler.get().schedule() } }
            }
        }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(blob: String): String {
        val pieces = blob.split(SEPARATOR, limit = 2)
        require(pieces.size == 2) { "Invalid encrypted session" }
        val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(pieces[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
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
        const val PREFERENCES = "kit_wallet_secure_session"
        const val KEY_SESSION = "session_v1"
        const val KEY_MESSAGING_ERASURE_PENDING = "messaging_erasure_pending_v1"
        const val KEY_ALIAS = "kit_wallet_session_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = "."
    }
}

@VisibleForTesting
internal suspend fun eraseMessagingThenClearSession(
    eraseMessaging: suspend () -> Unit,
    clearSession: (erasureSucceeded: Boolean) -> Unit,
) {
    var erasureSucceeded = false
    try {
        eraseMessaging()
        erasureSucceeded = true
    } finally {
        // A damaged messaging key/database must not retain a revoked Kit session locally.
        clearSession(erasureSucceeded)
    }
}

@VisibleForTesting
@JsonClass(generateAdapter = false)
internal data class SessionDiskPayload(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
    val accessTokenExpiresAtEpochSeconds: Long?,
    val accountId: String? = null,
    val cacheScopeId: String? = null,
    val profileSetupState: String? = null,
    val messagingResetProof: SecureMessagingResetProofFence? = null,
)

@VisibleForTesting
internal fun SessionTokens.toDiskPayload() = SessionDiskPayload(
    accessToken = accessToken,
    refreshToken = refreshToken,
    sessionId = sessionId,
    accessTokenExpiresAtEpochSeconds = accessTokenExpiresAtEpochSeconds,
    accountId = accountId,
    cacheScopeId = cacheScopeId,
    profileSetupState = when (profileSetupState) {
        ProfileSetupState.UNKNOWN -> "unknown"
        ProfileSetupState.REQUIRED -> "required"
        ProfileSetupState.COMPLETED -> "completed"
    },
    messagingResetProof = messagingResetProof,
)

@VisibleForTesting
internal fun SessionDiskPayload.toSessionTokens() = SessionTokens(
    accessToken = accessToken,
    refreshToken = refreshToken,
    sessionId = sessionId,
    accessTokenExpiresAtEpochSeconds = accessTokenExpiresAtEpochSeconds,
    accountId = accountId,
    cacheScopeId = cacheScopeId?.takeIf(String::isNotBlank) ?: sessionId,
    profileSetupState = when (profileSetupState) {
        "required" -> ProfileSetupState.REQUIRED
        "completed" -> ProfileSetupState.COMPLETED
        else -> ProfileSetupState.UNKNOWN
    },
    messagingResetProof = messagingResetProof,
)
