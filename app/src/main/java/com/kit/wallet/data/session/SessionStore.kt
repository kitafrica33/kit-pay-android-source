package com.kit.wallet.data.session

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
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID must not be blank" }
        require(cacheScopeId.isNotBlank()) { "Cache scope ID must not be blank" }
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

/** Applies the credential checks required before a session can be persisted. */
internal fun SessionTokens.requireValidCredentials() {
    require(accessToken.isNotBlank()) { "Access token must not be blank" }
    require(refreshToken.isNotBlank()) { "Refresh token must not be blank" }
    require(sessionId.isNotBlank()) { "Session ID must not be blank" }
}

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

    fun current(): SessionTokens?

    fun snapshot(): SessionSnapshot

    suspend fun save(tokens: SessionTokens)

    /**
     * Atomically adopts [tokens] only if no login, logout, or refresh changed [expected].
     * This prevents a late authentication/refresh response from resurrecting a cleared session.
     */
    suspend fun saveIfUnchanged(expected: SessionSnapshot, tokens: SessionTokens): Boolean

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

    /** Clears credentials only if [expected] still owns the local authenticated session. */
    suspend fun clearIfCurrent(expected: SessionFence): Boolean

    suspend fun clear()
}
