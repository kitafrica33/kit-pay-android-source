package com.kit.wallet.feature.chat

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionTokens
import kotlinx.coroutines.flow.StateFlow

/** Binds a long-lived decoder to the login that owned its source conversation. */
internal class VoiceNoteSessionBinding(private val sessions: StateFlow<SessionTokens?>) {
    private var owner: SessionFence? = null

    fun matches(expected: SessionFence?): Boolean = expected != null && sessions.value?.fence() == expected

    fun claim(expected: SessionFence?): Boolean {
        if (!matches(expected)) return false
        owner = expected
        return true
    }

    fun ownsCurrentSession(): Boolean = matches(owner)

    fun clear() { owner = null }

    suspend fun watch(onInvalidated: () -> Unit) {
        sessions.collect {
            if (owner != null && !ownsCurrentSession()) {
                clear()
                onInvalidated()
            }
        }
    }
}
