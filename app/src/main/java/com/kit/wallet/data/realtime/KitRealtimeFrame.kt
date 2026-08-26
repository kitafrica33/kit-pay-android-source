package com.kit.wallet.data.realtime

/**
 * The complete set of inbound frames this client is willing to understand.
 *
 * Anything the codec cannot map onto one of these is dropped without side effect,
 * which is what keeps an unrecognised or hostile frame from reaching the state
 * machine at all. In particular there is no `Unknown` case to fall through to.
 */
internal sealed interface KitRealtimeFrame {
    /** `pusher:connection_established` — carries the socket id every auth POST is bound to. */
    data class Established(
        val socketId: String,
        val activityTimeoutSeconds: Int,
    ) : KitRealtimeFrame

    /** `pusher:ping` — must be answered with a pong. */
    data object Ping : KitRealtimeFrame

    /** `pusher:pong` — the answer to our own keepalive; clears the pong deadline. */
    data object Pong : KitRealtimeFrame

    /** `pusher:error`. [code] drives the taxonomy in [KitRealtimeErrorPolicy]. */
    data class Failure(
        val code: Int?,
        val message: String?,
    ) : KitRealtimeFrame

    /** `pusher_internal:subscription_succeeded`. [members] is empty for private channels. */
    data class SubscriptionSucceeded(
        val channel: String,
        val members: Set<String>,
    ) : KitRealtimeFrame

    /** `pusher:subscription_error` — the grant was rejected after we had signed it. */
    data class SubscriptionFailed(
        val channel: String,
        val status: Int?,
    ) : KitRealtimeFrame

    /** `pusher_internal:member_added` — fires on a user's *first* connection, not per device. */
    data class MemberAdded(
        val channel: String,
        val user: String,
    ) : KitRealtimeFrame

    /** `pusher_internal:member_removed` — fires on a user's *last* connection. */
    data class MemberRemoved(
        val channel: String,
        val user: String,
    ) : KitRealtimeFrame

    /**
     * `kit.sync.nudge` — content-free. There is deliberately no conversation id,
     * sender, cursor, count or timestamp to carry, so this case holds nothing: the
     * advisory `reason` is parsed only to validate the frame and is never branched on.
     */
    data object SyncNudge : KitRealtimeFrame

    /** `kit.typing` / `kit.typing.stop`, attributed by the server from the actor's session. */
    data class Typing(
        val channel: String,
        val user: String,
        val active: Boolean,
    ) : KitRealtimeFrame
}

/** What a `pusher:error` code obliges the connection to do next. */
internal enum class KitRealtimeErrorAction {
    /** 4000-4099: the connection will never succeed as configured. Stop trying. */
    Suspend,

    /** 4100-4199: retry, but not immediately. */
    Backoff,

    /** 4200-4299: reconnect at once unless repeated before the connection stabilises. */
    ReconnectImmediately,
}

internal object KitRealtimeErrorPolicy {
    /**
     * Pusher's documented close/error taxonomy.
     *
     * An absent or out-of-range code is treated as [KitRealtimeErrorAction.Backoff]
     * rather than as a reconnect: a server we cannot interpret must cost us a
     * delay, never a tight loop.
     */
    fun actionFor(code: Int?): KitRealtimeErrorAction = when (code) {
        null -> KitRealtimeErrorAction.Backoff
        in 4000..4099 -> KitRealtimeErrorAction.Suspend
        in 4100..4199 -> KitRealtimeErrorAction.Backoff
        in 4200..4299 -> KitRealtimeErrorAction.ReconnectImmediately
        else -> KitRealtimeErrorAction.Backoff
    }
}
