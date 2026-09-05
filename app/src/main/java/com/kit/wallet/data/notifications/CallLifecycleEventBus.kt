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
    /**
     * Server-authoritative instant the call became active, and the server's own clock
     * when it sent this event. Only ever populated on [CallLifecycleKind.ANSWERED], and
     * only once [CallAnswerSignalPolicy] has accepted them. [serverTime] may be absent
     * where [answeredAt] is present — an older server sends only the answer — but never
     * the other way round, because a send instant on its own anchors nothing.
     */
    val answeredAt: String? = null,
    val serverTime: String? = null,
    /** Process-local notification/Telecom intent; wire decoding can never populate this flag. */
    val localEndRequested: Boolean = false,
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
            // The same validator the socket frame goes through, so neither route is the
            // softer way in: a lifecycle event moves a call's state whichever carried it.
            val callId = CallAnswerSignalPolicy.callId(data["call_id"]) ?: return null
            val state = data["state"]?.trim()?.takeIf(String::isNotEmpty)

            // An answer may only announce the one state it means, on this route and on the
            // socket alike. A `call.answered` claiming anything else is a payload the
            // server does not produce, and letting it through would give one event type
            // the power to move a call into a state nothing else expects from it.
            if (kind == CallLifecycleKind.ANSWERED && !CallAnswerSignalPolicy.announcesActive(state)) {
                return null
            }

            // Validated by the same rules the socket frame is — and refused the same way.
            // A pair that is present but fails them is a replay or a forgery whichever
            // transport carried it, so the whole event is dropped rather than letting the
            // push become the softer way in. Only a push that carries no timestamp at all —
            // an older server's answer — is acted on without an anchor: there is nothing to
            // validate, and the route itself is authenticated by FCM and the server.
            val answeredAt = data["answered_at"]?.trim()?.takeIf(String::isNotEmpty)
            val serverTime = data["server_time"]?.trim()?.takeIf(String::isNotEmpty)
            val anchor = if (kind == CallLifecycleKind.ANSWERED &&
                (answeredAt != null || serverTime != null)
            ) {
                CallAnswerSignalPolicy.anchor(answeredAt, serverTime) ?: return null
            } else {
                null
            }

            return CallLifecycleEvent(
                callId = callId,
                kind = kind,
                state = state,
                reason = data["end_reason"]?.trim()?.takeIf(String::isNotEmpty),
                answeredAt = anchor?.answeredAt,
                serverTime = anchor?.serverTime,
            )
        }
    }
}

/** Whether this event closes the shared ringing window on every recipient device. */
internal fun CallLifecycleEvent.endsRingingSurface(): Boolean =
    kind == CallLifecycleKind.ANSWERED || terminal

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
