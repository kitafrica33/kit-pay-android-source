package com.kit.wallet

import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small exact-fence session store shared by owner-scoped cache tests. */
internal class MutableTestSessionStore(initial: SessionTokens?) : SessionStore {
    private val mutableSession = MutableStateFlow(initial)
    private var revision = 0L
    override val session: StateFlow<SessionTokens?> = mutableSession

    override fun current(): SessionTokens? = mutableSession.value

    override fun snapshot(): SessionSnapshot = SessionSnapshot(revision, current()?.fence())

    override suspend fun save(tokens: SessionTokens) {
        mutableSession.value = tokens
        revision++
    }

    override suspend fun saveIfUnchanged(
        expected: SessionSnapshot,
        tokens: SessionTokens,
    ): Boolean {
        if (snapshot() != expected) return false
        save(tokens)
        return true
    }

    override suspend fun updateProfileSetupState(
        expected: SessionFence,
        state: ProfileSetupState,
    ): Boolean {
        val current = current() ?: return false
        if (current.fence() != expected) return false
        save(current.copy(profileSetupState = state))
        return true
    }

    override suspend fun <T> withCurrentSession(
        expected: SessionFence,
        block: suspend (SessionTokens) -> T,
    ): T {
        val current = current() ?: throw SessionInvalidatedException()
        if (current.fence() != expected) throw SessionInvalidatedException()
        val result = block(current)
        if (current()?.fence() != expected) throw SessionInvalidatedException()
        return result
    }

    override suspend fun clearIfCurrent(expected: SessionFence): Boolean {
        if (current()?.fence() != expected) return false
        clear()
        return true
    }

    override suspend fun clear() {
        mutableSession.value = null
        revision++
    }
}

internal fun testSession(
    accountId: String,
    sessionId: String = "session-$accountId",
    cacheScopeId: String = "scope-$accountId",
): SessionTokens = SessionTokens(
    accessToken = "access-$accountId",
    refreshToken = "refresh-$accountId",
    sessionId = sessionId,
    accountId = accountId,
    cacheScopeId = cacheScopeId,
)
