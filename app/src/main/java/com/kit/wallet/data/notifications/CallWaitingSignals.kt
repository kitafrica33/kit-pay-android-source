package com.kit.wallet.data.notifications

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the user is currently in a connected call, so a second incoming call can be
 * surfaced as call-waiting inside the active call instead of taking over the screen full-screen.
 */
@Singleton
class ActiveCallStateHolder @Inject constructor() {
    private val mutableActiveCallId = MutableStateFlow<String?>(null)

    /** The backend id of the call the user is currently connected to, or null when not in a call. */
    val activeCallId: StateFlow<String?> = mutableActiveCallId.asStateFlow()

    fun setActiveCall(callId: String?) {
        mutableActiveCallId.value = callId
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
