package com.kit.wallet.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.messaging.deleteKeystoreAliasAndVerifyAbsent
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStoreException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val mutex = CoroutineOwnedMutex()
    private val initialRestore = readRetainingFailure()
    private val _session = MutableStateFlow(initialRestore.tokens)
    private val _restorationPending = MutableStateFlow(initialRestore.pending)
    private val _restorationRetryable = MutableStateFlow(initialRestore.retryRequired)
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
    override val restorationPending: StateFlow<Boolean> = _restorationPending.asStateFlow()
    override val restorationRetryable: StateFlow<Boolean> = _restorationRetryable.asStateFlow()

    override fun current(): SessionTokens? = _session.value

    override fun snapshot(): SessionSnapshot = sessionSnapshot

    override suspend fun retryRestore(): Boolean = mutex.withLock {
        _session.value?.let { published ->
            if (!_restorationPending.value && messagingLifecycle.stateAvailable.value) {
                return@withLock true
            }
            return@withLock activatePublishedMessagingForRestoreLocked(published)
        }
        val encrypted = preferences.getString(KEY_SESSION, null)
        val attempt = retryRetainedEncryptedSession(
            encryptedSession = encrypted,
            messagingErasurePending = preferences.getBoolean(
                KEY_MESSAGING_ERASURE_PENDING,
                false,
            ),
            finishPendingMessagingErasure = {
                // This marker fences an interrupted logout/session replacement. Once cleanup
                // succeeds, delete the fenced credential rather than resurrecting a prior login.
                messagingLifecycle.beforeSessionClear()
                preferences.edit()
                    .remove(KEY_SESSION)
                    .remove(KEY_MESSAGING_ERASURE_PENDING)
                    .commit()
            },
            decode = ::decodeSession,
        )
        val restored = attempt.tokens
        if (restored == null) {
            _restorationPending.value = attempt.pending
            _restorationRetryable.value = attempt.retryRequired
            return@withLock false
        }
        publishSessionLocked(restored)
        activatePublishedMessagingForRestoreLocked(restored)
    }

    override suspend fun discardPendingRestoration() = mutex.withLock {
        if (pendingRestorationDiscardTarget(
                restorationPending = _restorationPending.value,
                sessionPublished = _session.value != null,
            ) == PendingRestorationDiscardTarget.NONE
        ) {
            return@withLock
        }
        clearLocked()
    }

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

    override suspend fun replaceIfUnchangedAfterFinalMessagingSnapshot(
        expected: SessionSnapshot,
        tokens: SessionTokens,
        finalMessagingSnapshot: suspend (SessionFence) -> Unit,
    ): Boolean {
        tokens.requireValidCredentials()
        return mutex.withLock {
            if (sessionSnapshot != expected) return@withLock false
            val previous = _session.value?.fence()
            persistLocked(tokens) {
                if (previous != null && previous != tokens.fence()) {
                    finalMessagingSnapshot(previous)
                }
            }
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
                latest.refreshToken != expectedCredentials.refreshToken ||
                latest.refreshReplayNonce != expectedCredentials.refreshReplayNonce
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

    override suspend fun <T> withUnchangedSession(
        expected: SessionSnapshot,
        block: suspend (SessionTokens?) -> T,
    ): T = mutex.withLock {
        if (sessionSnapshot != expected) throw SessionInvalidatedException()
        block(_session.value)
    }

    override suspend fun clearIfCurrent(expected: SessionFence): Boolean = mutex.withLock {
        if (_session.value?.fence() != expected) return@withLock false
        clearLocked()
        true
    }

    override suspend fun prepareClearIfCurrent(expected: SessionFence): Boolean = mutex.withLock {
        if (_session.value?.fence() != expected) return@withLock false
        markMessagingErasurePending()
        true
    }

    override suspend fun clearIfCurrentAfterFinalMessagingSnapshot(
        expected: SessionFence,
        allowPermanentlyUnavailableSnapshot: Boolean,
        finalMessagingSnapshot: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (_session.value?.fence() != expected) return@withLock false
        clearLocked(
            finalMessagingSnapshot = finalMessagingSnapshot,
            allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
        )
        true
    }

    override suspend fun clearIfCurrentForSecureMessagingRecovery(
        expected: SessionFence,
        activationFence: SecureMessagingSessionFence,
        allowPermanentlyUnavailableSnapshot: Boolean,
        finalMessagingSnapshot: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (_session.value?.fence() != expected) return@withLock false
        clearLocked(
            finalMessagingSnapshot = finalMessagingSnapshot,
            allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
            recoveryActivationFence = activationFence,
        )
        true
    }

    override suspend fun clearIfCredentialsCurrent(expected: SessionTokens): Boolean =
        mutex.withLock {
            val latest = _session.value ?: return@withLock false
            if (!latest.hasSameRefreshCredential(expected)) return@withLock false
            clearLocked()
            true
        }

    override suspend fun clearIfCredentialsCurrentAfterFinalMessagingSnapshot(
        expected: SessionTokens,
        allowPermanentlyUnavailableSnapshot: Boolean,
        finalMessagingSnapshot: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        val latest = _session.value ?: return@withLock false
        if (!latest.hasSameRefreshCredential(expected)) return@withLock false
        clearLocked(
            finalMessagingSnapshot = finalMessagingSnapshot,
            allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
        )
        true
    }

    override suspend fun resetSecureMessagingStateIfCurrent(
        expected: SessionFence,
        activationFence: SecureMessagingSessionFence,
        allowPermanentlyUnavailableSnapshot: Boolean,
        finalMessagingSnapshot: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (_session.value?.fence() != expected) return@withLock false

        var erasureFenced = false
        try {
            messagingLifecycle.resetForRecovery(
                fence = activationFence,
                allowPermanentlyUnavailableSnapshot =
                    allowPermanentlyUnavailableSnapshot,
                finalSnapshot = finalMessagingSnapshot,
                beforeErasure = {
                    markMessagingErasurePending()
                    erasureFenced = true
                },
            )
        } catch (error: Throwable) {
            // Before the fence is durable, both the authenticated owner and readable messaging
            // state remain intact so a transient archive/Keystore failure can be retried.
            if (erasureFenced) abandonSessionDuringPendingMessagingErasure()
            throw error
        }

        if (!preferences.edit().remove(KEY_MESSAGING_ERASURE_PENDING).commit()) {
            abandonSessionDuringPendingMessagingErasure()
            error("The secure messaging erasure marker could not be cleared")
        }
        try {
            val current = _session.value
                ?.takeIf { it.fence() == expected }
                ?: throw SessionInvalidatedException()
            check(activatePublishedMessagingLocked(current)) {
                "Secure messaging recovery lost its authenticated session"
            }
        } catch (error: Throwable) {
            // The reset and durable marker removal already completed. Keep the exact authenticated
            // session behind the restoration gate and retry reopening; resurrecting the erasure
            // marker here would make process restart destroy a successfully reset session.
            if (_restorationPending.value && _session.value?.fence() == expected) {
                retryPublishedMessagingActivation(expected)
            }
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

    private suspend fun clearLocked(
        finalMessagingSnapshot: (suspend () -> Unit)? = null,
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        recoveryActivationFence: SecureMessagingSessionFence? = null,
    ) {
        // Never cross the first suspending erasure boundary unless the durable fence exists.
        // If this commit fails, abort with the still-readable session intact so process death
        // cannot resurrect credentials after their messaging state was already destroyed.
        var erasureFenced = false
        var erasureSucceeded = false
        try {
            val snapshot: suspend () -> Unit = {
                finalMessagingSnapshot?.invoke()
                Unit
            }
            val fenceErasure: () -> Unit = {
                // The exclusive messaging-state lease is still held. Persist the crash fence
                // after a successful snapshot but before lifecycle invalidation or key erasure.
                markMessagingErasurePending()
                erasureFenced = true
            }
            if (recoveryActivationFence == null) {
                messagingLifecycle.beforeSessionClear(
                    allowPermanentlyUnavailableSnapshot =
                        allowPermanentlyUnavailableSnapshot,
                    finalSnapshot = snapshot,
                    beforeErasure = fenceErasure,
                )
            } else {
                messagingLifecycle.resetForRecovery(
                    fence = recoveryActivationFence,
                    allowPermanentlyUnavailableSnapshot =
                        allowPermanentlyUnavailableSnapshot,
                    finalSnapshot = snapshot,
                    beforeErasure = fenceErasure,
                )
            }
            erasureSucceeded = true
        } finally {
            if (erasureFenced) {
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
            }
        }
    }

    private suspend fun persistLocked(
        tokens: SessionTokens,
        finalMessagingSnapshot: suspend () -> Unit = {},
    ) {
        val existing = _session.value
        val isSameSession = existing?.fence() == tokens.fence()
        val allowKeyCreation = sessionKeyCreationAllowed(
            hasEncryptedSession = preferences.getString(KEY_SESSION, null) != null,
            hasCurrentSession = existing != null,
        )
        if (!isSameSession) {
            var erasureFenced = false
            try {
                messagingLifecycle.beforeSessionSave(
                    isSameSession = false,
                    finalSnapshot = finalMessagingSnapshot,
                    beforeErasure = {
                        markMessagingErasurePending()
                        erasureFenced = true
                    },
                )
            } catch (error: Throwable) {
                // Snapshot/marker failure happens before state erasure and retains the old owner.
                // Once fenced, cleanup may have started, so the old credential must not survive.
                if (erasureFenced) abandonSessionDuringPendingMessagingErasure()
                throw error
            }
        }
        val json = adapter.toJson(tokens.toDiskPayload())
        val encryptedSession = try {
            encrypt(json, allowKeyCreation = allowKeyCreation)
        } catch (error: Throwable) {
            if (!isSameSession) abandonSessionDuringPendingMessagingErasure()
            throw error
        }
        val persistence = preferences.edit().putString(KEY_SESSION, encryptedSession)
        if (!isSameSession) {
            persistence.remove(KEY_MESSAGING_ERASURE_PENDING)
        }
        val persisted = persistence.commit()
        if (!persisted) {
            if (!isSameSession) abandonSessionDuringPendingMessagingErasure()
            error("The secure session could not be persisted")
        }
        publishSessionLocked(tokens)
        try {
            val messagingActivated = activatePublishedMessagingLocked(tokens)
            if (messagingActivated) {
                runCatching { messagingSyncScheduler.get().schedule() }
            }
        } catch (error: Throwable) {
            if (_restorationPending.value && _session.value?.fence() == tokens.fence()) {
                retryPublishedMessagingActivation(tokens.fence())
            }
            throw error
        }
    }

    private fun readRetainingFailure(): InitialSessionRestore {
        val encrypted = preferences.getString(KEY_SESSION, null)
        return restoreRetainingEncryptedSession(
            encryptedSession = encrypted,
            messagingErasurePending = preferences.getBoolean(
                KEY_MESSAGING_ERASURE_PENDING,
                false,
            ),
            decode = ::decodeSession,
        )
    }

    private fun decodeSession(encrypted: String): SessionTokens =
        decodeSessionPersistingLegacyNonce(
            encryptedSession = encrypted,
            decodePayload = { ciphertext ->
                requireNotNull(adapter.fromJson(decrypt(ciphertext))) {
                    "The encrypted session payload is empty"
                }
            },
            encodePayload = { payload ->
                // A successful decrypt proves that this ciphertext already owns a key. Never
                // create a replacement alias while rewriting the upgraded payload.
                encrypt(adapter.toJson(payload), allowKeyCreation = false)
            },
            persistEncryptedSession = { migrated ->
                preferences.edit().putString(KEY_SESSION, migrated).commit()
            },
        )

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
                        preferences.edit()
                            .remove(KEY_SESSION)
                            .remove(KEY_MESSAGING_ERASURE_PENDING)
                            .commit()
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
        _restorationPending.value = false
        _restorationRetryable.value = false
        sessionSnapshot = SessionSnapshot(
            revision = sessionSnapshot.revision + 1L,
            fence = tokens?.fence(),
        )
    }

    private fun invalidateSessionKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        deleteKeystoreAliasAndVerifyAbsent(
            deleteEntry = { keyStore.deleteEntry(KEY_ALIAS) },
            aliasExists = { keyStore.containsAlias(KEY_ALIAS) },
        )
    }

    private fun activateRestoredMessagingSession() {
        val expected = _session.value?.fence() ?: return
        applicationScope.launch {
            mutex.withLock {
                val current = _session.value
                    ?.takeIf { it.fence() == expected }
                    ?: return@withLock
                val activated = activatePublishedMessagingForRestoreLocked(current)
                if (!activated) retryPublishedMessagingActivation(expected)
            }
        }
    }

    private suspend fun activatePublishedMessagingForRestoreLocked(
        tokens: SessionTokens,
    ): Boolean = try {
        val activated = activatePublishedMessagingLocked(tokens)
        if (activated) runCatching { messagingSyncScheduler.get().schedule() }
        activated
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Keep the published credential behind the restoration gate and retry its exact fence;
        // foreground/UI retries must not mistake an authenticated-but-closed state for readiness.
        false
    }

    private suspend fun activatePublishedMessagingLocked(tokens: SessionTokens): Boolean =
        activateMessagingForPublishedSession(
            expected = tokens.fence(),
            currentSession = { _session.value?.fence() },
            activate = {
                messagingLifecycle.afterSessionSave()
                _restorationPending.value = false
                _restorationRetryable.value = false
            },
            onActivationFailure = {
                _restorationPending.value = true
                _restorationRetryable.value = true
            },
        )

    private fun retryPublishedMessagingActivation(expected: SessionFence) {
        applicationScope.launch {
            retryPublishedMessagingActivationWithRetries(
                attempts = MESSAGING_ACTIVATION_RETRY_ATTEMPTS,
                retryDelayMillis = MESSAGING_ACTIVATION_RETRY_DELAY_MILLIS,
                attemptActivation = {
                    mutex.withLock {
                        val current = _session.value
                            ?.takeIf { it.fence() == expected }
                            ?: return@withLock null
                        activatePublishedMessagingForRestoreLocked(current)
                    }
                },
                waitBeforeNextAttempt = { delay(it) },
            )
        }
    }

    private fun encrypt(
        plainText: String,
        allowKeyCreation: Boolean,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey(allowCreation = allowKeyCreation))
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
        // Decryption must never replace a temporarily unavailable Android 9/OEM Keystore alias:
        // doing so would make the retained AES-GCM ciphertext permanently unrecoverable.
        cipher.init(
            Cipher.DECRYPT_MODE,
            sessionKey(allowCreation = false),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun sessionKey(allowCreation: Boolean): SecretKey = resolveSessionKey(
        allowCreation = allowCreation,
        loadExisting = {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        },
        createNew = ::generateSessionKey,
    )

    private fun generateSessionKey(): SecretKey {
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
        const val MESSAGING_ACTIVATION_RETRY_ATTEMPTS = 3
        const val MESSAGING_ACTIVATION_RETRY_DELAY_MILLIS = 250L
    }
}

@VisibleForTesting
internal data class InitialSessionRestore(
    val tokens: SessionTokens?,
    val retryRequired: Boolean,
    val recoveryRequired: Boolean = false,
) {
    val pending: Boolean
        get() = tokens == null && (retryRequired || recoveryRequired)
}

/**
 * Classifies an initial secure-store read without mutating its encrypted credential. Android 9
 * Keystore providers can reject an otherwise valid key while the device is locked or an OEM
 * provider is recovering; that is a retryable read, not proof that the Kit session was revoked.
 */
@VisibleForTesting
internal fun restoreRetainingEncryptedSession(
    encryptedSession: String?,
    messagingErasurePending: Boolean,
    decode: (String) -> SessionTokens,
): InitialSessionRestore {
    if (messagingErasurePending) {
        return InitialSessionRestore(tokens = null, retryRequired = true)
    }
    if (encryptedSession == null) {
        return InitialSessionRestore(tokens = null, retryRequired = false)
    }
    return try {
        InitialSessionRestore(
            tokens = decode(encryptedSession).also(SessionTokens::requireValidCredentials),
            retryRequired = false,
        )
    } catch (error: Exception) {
        val retryable = isRetryableSessionRestoreFailure(error)
        InitialSessionRestore(
            tokens = null,
            retryRequired = retryable,
            recoveryRequired = !retryable,
        )
    }
}

/** Completes isolated messaging cleanup, then retries the exact retained credential ciphertext. */
@VisibleForTesting
internal suspend fun retryRetainedEncryptedSession(
    encryptedSession: String?,
    messagingErasurePending: Boolean,
    finishPendingMessagingErasure: suspend () -> Boolean,
    decode: (String) -> SessionTokens,
): InitialSessionRestore {
    if (messagingErasurePending) {
        val cleanupSucceeded = try {
            finishPendingMessagingErasure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!cleanupSucceeded) {
            return InitialSessionRestore(tokens = null, retryRequired = true)
        }
        // The marker proves this blob was fenced by logout/session replacement. Cleanup success
        // includes deleting that blob; it must never be decoded as a valid restored login.
        return InitialSessionRestore(tokens = null, retryRequired = false)
    }
    if (encryptedSession == null) {
        return InitialSessionRestore(tokens = null, retryRequired = false)
    }
    return restoreRetainingEncryptedSession(
        encryptedSession = encryptedSession,
        messagingErasurePending = false,
        decode = decode,
    )
}

/** Selects an existing decrypt key without ever replacing a temporarily hidden Keystore alias. */
@VisibleForTesting
internal fun <T : Any> resolveSessionKey(
    allowCreation: Boolean,
    loadExisting: () -> T?,
    createNew: () -> T,
): T {
    loadExisting()?.let { return it }
    if (!allowCreation) throw SessionKeyTemporarilyUnavailableException()
    return createNew()
}

/** Retained credentials must never be rebound to a replacement Android Keystore alias. */
@VisibleForTesting
internal fun sessionKeyCreationAllowed(
    hasEncryptedSession: Boolean,
    hasCurrentSession: Boolean,
): Boolean = !hasEncryptedSession && !hasCurrentSession

/**
 * Opens secure-messaging state for the exact session that was just published. Session-store
 * callers hold their session mutex across this operation, so logout/replacement cannot interleave
 * between the fence check and activation. Cancellation is restored only after activation has had
 * an uncancellable opportunity to open both the lifecycle gate and its observable readiness flow.
 */
@VisibleForTesting
internal suspend fun activateMessagingForPublishedSession(
    expected: SessionFence,
    currentSession: () -> SessionFence?,
    activate: suspend () -> Unit,
    onActivationFailure: (Throwable) -> Unit = {},
): Boolean {
    val callerContext = currentCoroutineContext()
    val activation = withContext(NonCancellable) {
        if (currentSession() != expected) {
            Result.success(false)
        } else {
            try {
                activate()
                Result.success(true)
            } catch (error: Throwable) {
                // Transport the exact failure out of withContext so coroutine stack-trace
                // recovery cannot replace it before cancellation precedence is decided.
                onActivationFailure(error)
                Result.failure(error)
            }
        }
    }

    try {
        callerContext.ensureActive()
    } catch (cancelled: CancellationException) {
        activation.exceptionOrNull()
            ?.takeIf { it !== cancelled }
            ?.let(cancelled::addSuppressed)
        throw cancelled
    }
    return activation.getOrThrow()
}

/** Bounded exact-session gate retries used after a published session fails to reopen messaging. */
@VisibleForTesting
internal suspend fun retryPublishedMessagingActivationWithRetries(
    attempts: Int,
    retryDelayMillis: Long,
    attemptActivation: suspend () -> Boolean?,
    waitBeforeNextAttempt: suspend (Long) -> Unit,
): Boolean {
    require(attempts > 0)
    require(retryDelayMillis >= 0L)
    repeat(attempts) { attempt ->
        when (attemptActivation()) {
            true -> return true
            null -> return false
            false -> if (attempt < attempts - 1) {
                waitBeforeNextAttempt(retryDelayMillis * (attempt + 1L))
            }
        }
    }
    return false
}

@VisibleForTesting
internal enum class PendingRestorationDiscardTarget {
    NONE,
    RETAINED_CREDENTIAL,
    PUBLISHED_CREDENTIAL,
}

/** Both an unreadable credential and a published session whose messaging gate failed are discardable. */
@VisibleForTesting
internal fun pendingRestorationDiscardTarget(
    restorationPending: Boolean,
    sessionPublished: Boolean,
): PendingRestorationDiscardTarget = when {
    !restorationPending -> PendingRestorationDiscardTarget.NONE
    sessionPublished -> PendingRestorationDiscardTarget.PUBLISHED_CREDENTIAL
    else -> PendingRestorationDiscardTarget.RETAINED_CREDENTIAL
}

/** A missing decrypt alias may be temporarily hidden by Android 9/OEM Keystore providers. */
@VisibleForTesting
internal class SessionKeyTemporarilyUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("The encrypted session key is temporarily unavailable", cause)

/** Only provider/lock-state failures receive automatic retries; corrupt data requires consent. */
@VisibleForTesting
internal fun isRetryableSessionRestoreFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .any { cause ->
            cause is SessionKeyTemporarilyUnavailableException ||
                cause is UserNotAuthenticatedException ||
                cause.javaClass.name == "android.security.KeyStoreException" ||
                cause is KeyStoreException ||
                cause is UnrecoverableKeyException ||
                cause is ProviderException
        }

/** Persists the crash fence before the first suspension in logout/session replacement cleanup. */
@VisibleForTesting
internal suspend fun fenceThenEraseMessagingAndClearSession(
    persistErasureFence: () -> Unit,
    eraseMessaging: suspend () -> Unit,
    clearSession: (erasureSucceeded: Boolean) -> Unit,
) {
    persistErasureFence()
    eraseMessagingThenClearSession(eraseMessaging, clearSession)
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

/**
 * Upgrades a code-15 credential before returning it to any network consumer. The generated
 * refresh replay nonce must be committed with the encrypted session first: otherwise a process
 * restart after a lost refresh response would generate a different nonce and look like theft to
 * the server. Any encode/commit failure throws, so the caller retains restoration-pending state
 * and never publishes the decoded credentials.
 */
@VisibleForTesting
internal fun decodeSessionPersistingLegacyNonce(
    encryptedSession: String,
    decodePayload: (String) -> SessionDiskPayload,
    encodePayload: (SessionDiskPayload) -> String,
    persistEncryptedSession: (String) -> Boolean,
): SessionTokens {
    val payload = decodePayload(encryptedSession)
    val tokens = payload.toSessionTokens().also(SessionTokens::requireValidCredentials)
    if (payload.refreshReplayNonce == null) {
        val migrated = encodePayload(tokens.toDiskPayload())
        check(persistEncryptedSession(migrated)) {
            "The upgraded secure session could not be persisted"
        }
    }
    return tokens
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
    val refreshReplayNonce: String? = null,
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
    refreshReplayNonce = refreshReplayNonce,
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
    refreshReplayNonce = refreshReplayNonce ?: java.util.UUID.randomUUID().toString(),
)
