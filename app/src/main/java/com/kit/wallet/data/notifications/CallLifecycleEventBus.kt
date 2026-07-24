package com.kit.wallet.data.notifications

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class CallLifecycleKind(val wireType: String) {
    ANSWERED("call.answered"),
    DECLINED("call.declined"),
    ENDED("call.ended"),
    MISSED("call.missed"),
}

data class CallLifecycleEvent(
    val callId: String,
    val kind: CallLifecycleKind,
    val state: String? = null,
    val reason: String? = null,
) {
    val terminal: Boolean
        get() = when (kind) {
            CallLifecycleKind.ANSWERED -> false
            CallLifecycleKind.DECLINED -> state.equals("declined", ignoreCase = true)
            CallLifecycleKind.ENDED, CallLifecycleKind.MISSED -> true
        }

    companion object {
        fun fromData(data: Map<String, String>): CallLifecycleEvent? {
            val kind = CallLifecycleKind.entries.firstOrNull { it.wireType == data["type"] }
                ?: return null
            val callId = IncomingCallPayload.callId(data) ?: return null
            return CallLifecycleEvent(
                callId = callId,
                kind = kind,
                state = data["state"]?.trim()?.takeIf(String::isNotEmpty),
                reason = data["end_reason"]?.trim()?.takeIf(String::isNotEmpty),
            )
        }
    }
}

/** Process-local bridge from push delivery to any matching foreground call screen. */
@Singleton
class CallLifecycleEventBus @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<CallLifecycleEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = mutableEvents.asSharedFlow()

    fun publish(data: Map<String, String>): Boolean {
        val event = CallLifecycleEvent.fromData(data) ?: return false
        return publish(event)
    }

    /** Publishes a locally-derived lifecycle event, such as the server-declared ring deadline. */
    fun publish(event: CallLifecycleEvent): Boolean = mutableEvents.tryEmit(event)
}
