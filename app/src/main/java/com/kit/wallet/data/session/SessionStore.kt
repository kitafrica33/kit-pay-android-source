package com.kit.wallet.data.session

import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.isRecoverableSecureMessagingStateLoss
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

enum class ProfileSetupState {
    UNKNOWN,
    REQUIRED,
    COMPLETED,
    ;

    /** Unknown is deliberately fail-closed until the backend confirms a completed profile. */
    val requiresSetup: Boolean
        get() = this != COMPLETED
}

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
    val accessTokenExpiresAtEpochSeconds: Long? = null,
    val accountId: String? = null,
    /**
     * Stable only for this locally adopted authenticated session. It owns every unencrypted
     * profile/wallet projection and is preserved across access-token refreshes.
     */
    val cacheScopeId: String = sessionId,
    val profileSetupState: ProfileSetupState = ProfileSetupState.UNKNOWN,
    val messagingResetProof: SecureMessagingResetProofFence? = null,
    /** Device-local replay proof for recovering one committed refresh whose response was lost. */
    val refreshReplayNonce: String = UUID.randomUUID().toString(),
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID must not be blank" }
        require(cacheScopeId.isNotBlank()) { "Cache scope ID must not be blank" }
        require(runCatching { UUID.fromString(refreshReplayNonce).toString() }.getOrNull() ==
            refreshReplayNonce
        ) { "Refresh replay nonce must be a canonical UUID" }
    }

    fun fence(): SessionFence = SessionFence(
        sessionId = sessionId,
        cacheScopeId = cacheScopeId,
        accountId = accountId,
    )

    fun isAccessTokenUsable(
        nowEpochSeconds: Long,
        clockSkewSeconds: Long = 30,
    ): Boolean = accessToken.isNotBlank() &&
        (accessTokenExpiresAtEpochSeconds == null ||
            accessTokenExpiresAtEpochSeconds > nowEpochSeconds + clockSkewSeconds)
}

/** AEAD-persisted exact reset intent, upgraded with N+1 only after validated server proof. */
data class SecureMessagingResetProofFence(
    val serverDeviceId: String,
    val previousEnrollmentEpoch: Long,
    val resultingEnrollmentEpoch: Long? = null,
    val previousRegistrationId: Int,
    val previousIdentityKeySha256: String,
    val previousBundleVersion: Int,
) {
    init {
        resultingEnrollmentEpoch?.let {
            require(it == previousEnrollmentEpoch + 1L)
        }
    }

    val proved: Boolean get() = resultingEnrollmentEpoch != null
}

/** Applies the credential checks required before a session can be persisted. */
internal fun SessionTokens.requireValidCredentials() {
    require(accessToken.isNotBlank()) { "Access token must not be blank" }
    require(refreshToken.isNotBlank()) { "Refresh token must not be blank" }
    require(sessionId.isNotBlank()) { "Session ID must not be blank" }
}

internal fun SessionTokens.hasSameRefreshCredential(other: SessionTokens): Boolean =
    sessionId == other.sessionId &&
        accessToken == other.accessToken &&
        refreshToken == other.refreshToken &&
        refreshReplayNonce == other.refreshReplayNonce

data class SessionFence(
    val sessionId: String,
    val cacheScopeId: String,
    val accountId: String?,
)

/** Non-secret, process-local compare-and-set token for adopting an authentication response. */
data class SessionSnapshot(
    val revision: Long,
    val fence: SessionFence?,
)

class SessionInvalidatedException : IllegalStateException(
    "The authenticated session changed while the request was in progress",
)

interface SessionStore {
    val session: StateFlow<SessionTokens?>

    /** True while an existing encrypted credential is retained for a retryable secure-store read. */
    val restorationPending: StateFlow<Boolean>
        get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True only when foreground retries may recover the retained credential without user action. */
    val restorationRetryable: StateFlow<Boolean>
        get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun current(): SessionTokens?

    fun snapshot(): SessionSnapshot

    /** Retries a retained credential after device unlock/foreground without deleting it on failure. */
    suspend fun retryRestore(): Boolean = current() != null

    /** Explicitly abandons an unreadable retained credential after the user chooses to sign in again. */
    suspend fun discardPendingRestoration() {
        clear()
    }

    suspend fun save(tokens: SessionTokens)

    /**
     * Atomically adopts [tokens] only if no login, logout, or refresh changed [expected].
     * This prevents a late authentication/refresh response from resurrecting a cleared session.
     */
    suspend fun saveIfUnchanged(expected: SessionSnapshot, tokens: SessionTokens): Boolean

    /**
     * Atomically adopts [tokens] only if [expected] is still current. When that adoption replaces
     * an authenticated messaging owner, production storage drains active messaging commits, runs
     * [finalMessagingSnapshot] for the old owner, and only then writes the erasure crash fence.
     * A failed snapshot therefore leaves both the old session and its messaging state intact.
     */
    suspend fun replaceIfUnchangedAfterFinalMessagingSnapshot(
        expected: SessionSnapshot,
        tokens: SessionTokens,
        finalMessagingSnapshot: suspend (SessionFence) -> Unit,
    ): Boolean = throw UnsupportedOperationException(
        "This session store cannot atomically replace an authenticated messaging owner",
    )

    /** Adopts rotated credentials while merging metadata written during the refresh request. */
    suspend fun adoptRefreshedCredentialsIfCurrent(
        expectedCredentials: SessionTokens,
        refreshedCredentials: SessionTokens,
    ): Boolean {
        refreshedCredentials.requireValidCredentials()
        val expectedSnapshot = snapshot()
        val latest = current() ?: return false
        if (latest.sessionId != expectedCredentials.sessionId ||
            latest.accessToken != expectedCredentials.accessToken ||
            latest.refreshToken != expectedCredentials.refreshToken ||
            latest.refreshReplayNonce != expectedCredentials.refreshReplayNonce
        ) {
            return false
        }
        check(refreshedCredentials.sessionId == latest.sessionId) {
            "A token refresh cannot replace the authenticated session epoch"
        }
        return saveIfUnchanged(
            expectedSnapshot,
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
    }

    /** Updates the durable setup gate only while the same authenticated cache owner is active. */
    suspend fun updateProfileSetupState(
        expected: SessionFence,
        state: ProfileSetupState,
    ): Boolean

    /**
     * Serializes a cache mutation with session replacement/clear and rejects an obsolete owner.
     */
    suspend fun <T> withCurrentSession(
        expected: SessionFence,
        block: suspend (SessionTokens) -> T,
    ): T

    /** Serializes work against login, replacement, and clear for an exact nullable snapshot. */
    suspend fun <T> withUnchangedSession(
        expected: SessionSnapshot,
        block: suspend (SessionTokens?) -> T,
    ): T {
        if (snapshot() != expected) throw SessionInvalidatedException()
        val result = block(current())
        if (snapshot() != expected) throw SessionInvalidatedException()
        return result
    }

    /** Clears credentials only if [expected] still owns the local authenticated session. */
    suspend fun clearIfCurrent(expected: SessionFence): Boolean

    /** Crash-fences an intended clear without erasing or revoking the current in-memory owner. */
    suspend fun prepareClearIfCurrent(expected: SessionFence): Boolean =
        current()?.fence() == expected

    /**
     * Clears the exact session while serializing [finalMessagingSnapshot] with messaging-state
     * erasure. Production storage drains earlier commits, runs the snapshot, then erases state
     * without admitting another commit in between.
     */
    suspend fun clearIfCurrentAfterFinalMessagingSnapshot(
        expected: SessionFence,
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalMessagingSnapshot: suspend () -> Unit,
    ): Boolean {
        if (current()?.fence() != expected) return false
        try {
            finalMessagingSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!allowPermanentlyUnavailableSnapshot ||
                !isRecoverableSecureMessagingStateLoss(error)
            ) {
                throw error
            }
        }
        return clearIfCurrent(expected)
    }

    /**
     * Exact-activation variant for an unverifiable secure-messaging transition. Production
     * storage rejects a stale [activationFence] before snapshotting or erasing local state.
     */
    suspend fun clearIfCurrentForSecureMessagingRecovery(
        expected: SessionFence,
        activationFence: SecureMessagingSessionFence,
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalMessagingSnapshot: suspend () -> Unit,
    ): Boolean = clearIfCurrentAfterFinalMessagingSnapshot(
        expected = expected,
        allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
        finalMessagingSnapshot = finalMessagingSnapshot,
    )

    /** Clears only the exact refresh generation rejected by the server. */
    suspend fun clearIfCredentialsCurrent(expected: SessionTokens): Boolean {
        val latest = current() ?: return false
        if (!latest.hasSameRefreshCredential(expected)) return false
        return clearIfCurrent(expected.fence())
    }

    /** Exact-refresh-generation clear with the same atomic final messaging snapshot guarantee. */
    suspend fun clearIfCredentialsCurrentAfterFinalMessagingSnapshot(
        expected: SessionTokens,
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalMessagingSnapshot: suspend () -> Unit,
    ): Boolean {
        val latest = current() ?: return false
        if (!latest.hasSameRefreshCredential(expected)) return false
        return clearIfCurrentAfterFinalMessagingSnapshot(
            expected = expected.fence(),
            allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
            finalMessagingSnapshot = finalMessagingSnapshot,
        )
    }

    /**
     * Crash-safely erases and reopens messaging state only while [expected] and the exact
     * messaging activation generation still own this session. Production storage runs
     * [finalMessagingSnapshot] under the same exclusive messaging-state lease and writes its
     * crash fence only after that snapshot succeeds. Proved key loss or migration-fenced
     * unreadable legacy state may bypass only its own snapshot failure because that state cannot
     * be safely retained through recovery.
     */
    suspend fun resetSecureMessagingStateIfCurrent(
        expected: SessionFence,
        activationFence: SecureMessagingSessionFence,
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalMessagingSnapshot: suspend () -> Unit = {},
    ): Boolean = throw UnsupportedOperationException(
        "This session store cannot reset secure messaging state",
    )

    /**
     * Exact-proof variant used after the server has durably advanced one pinned enrollment.
     * Production storage rechecks [proof] while holding the same session mutex that fences the
     * destructive local reset, so a removed or replaced proof cannot race into local erasure.
     */
    suspend fun resetSecureMessagingStateAfterProvenRemoteResetIfCurrent(
        expected: SessionFence,
        activationFence: SecureMessagingSessionFence,
        proof: SecureMessagingResetProofFence,
        allowPermanentlyUnavailableSnapshot: Boolean = false,
        finalMessagingSnapshot: suspend () -> Unit = {},
    ): Boolean {
        val current = current() ?: return false
        if (!proof.proved ||
            current.fence() != expected ||
            current.messagingResetProof != proof
        ) {
            return false
        }
        return resetSecureMessagingStateIfCurrent(
            expected = expected,
            activationFence = activationFence,
            allowPermanentlyUnavailableSnapshot = allowPermanentlyUnavailableSnapshot,
            finalMessagingSnapshot = finalMessagingSnapshot,
        )
    }

    suspend fun recordMessagingResetPendingIfCurrent(
        expected: SessionFence,
        pending: SecureMessagingResetProofFence,
    ): Boolean = false

    suspend fun recordMessagingResetProofIfCurrent(
        expected: SessionFence,
        proof: SecureMessagingResetProofFence,
    ): Boolean = false

    suspend fun clearMessagingResetProofIfCurrent(
        expected: SessionFence,
        proof: SecureMessagingResetProofFence,
    ): Boolean = false

    suspend fun clear()
}
