package com.kit.wallet.data.notifications

import com.kit.wallet.feature.calls.CallDurationAnchor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the rest of the app may know about the call the user is connected to right now.
 *
 * Every field is taken from the authenticated call session — the server's call id, its
 * participant roster, its conversation linkage and its answer anchor — never from anything a
 * notification tap or intent extra claimed. Surfaces that show or reopen the live call match
 * against this and do nothing on a mismatch.
 */
internal data class ActiveCallPresence(
    /** Backend id of the connected call. */
    val callId: String,
    /** Resolved display name of the other side, exactly as the call screen shows it. */
    val name: String,
    /** Authenticated participant user ids, for binding the call to a direct chat's peer. */
    val participantUserIds: List<String>,
    /** Server-reported owning conversation, when the call was started from one. */
    val conversationId: String?,
    val video: Boolean,
    /** Authoritative answer anchor the per-second timer counts from; null until known. */
    val anchor: CallDurationAnchor?,
) {
    /**
     * Whether this live call belongs to the chat identified by [chatId]/[isGroup]/[peerUserId].
     *
     * A group chat matches only on the server's conversation linkage. A direct chat also matches
     * when its authenticated peer is on the call's participant roster (ids compared ignoring
     * case, like every call-id and user-id comparison in the call stack). Anything else — blank
     * ids, missing linkage — fails closed to "not this chat's call".
     */
    fun matchesChat(chatId: String, isGroup: Boolean, peerUserId: String?): Boolean {
        if (chatId.isNotBlank() && conversationId == chatId) return true
        if (isGroup) return false
        if (peerUserId.isNullOrBlank()) return false
        return participantUserIds.any { it.equals(peerUserId, ignoreCase = true) }
    }
}

/**
 * Tracks whether the user is currently in a connected call, so a second incoming call can be
 * surfaced as call-waiting inside the active call instead of taking over the screen full-screen,
 * and so chat surfaces can show — and return to — the live call.
 */
@Singleton
class ActiveCallStateHolder @Inject constructor() {
    private val mutableActiveCallId = MutableStateFlow<String?>(null)
    private val mutablePresence = MutableStateFlow<ActiveCallPresence?>(null)

    /** The backend id of the call the user is currently connected to, or null when not in a call. */
    val activeCallId: StateFlow<String?> = mutableActiveCallId.asStateFlow()

    /**
     * The connected call as chat surfaces may present it, or null when not in a call. Non-null
     * only while [activeCallId] names the same call: publishing keeps them in step, and clearing
     * or changing the id drops any presence it no longer describes.
     */
    internal val presence: StateFlow<ActiveCallPresence?> = mutablePresence.asStateFlow()

    fun setActiveCall(callId: String?) {
        mutableActiveCallId.value = callId
        val current = mutablePresence.value
        if (
            current != null &&
            (callId == null || !current.callId.equals(callId, ignoreCase = true))
        ) {
            mutablePresence.value = null
        }
    }

    internal fun publishPresence(presence: ActiveCallPresence) {
        mutableActiveCallId.value = presence.callId
        mutablePresence.value = presence
    }
}

/**
 * Delivers an incoming call to an already-active call screen so it can be shown as a call-waiting
 * banner. The push receiver publishes here only while the user is in a call.
 */
@Singleton
class IncomingCallRelay @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<IncomingCallPayload>(extraBufferCapacity = 8)
    val events: SharedFlow<IncomingCallPayload> = mutableEvents.asSharedFlow()

    fun publish(payload: IncomingCallPayload) {
        mutableEvents.tryEmit(payload)
    }
}
