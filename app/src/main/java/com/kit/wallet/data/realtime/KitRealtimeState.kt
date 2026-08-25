package com.kit.wallet.data.realtime

/**
 * Where the one socket is in its life.
 *
 * Only [Live] means "the durable log will be nudged": every other state is a
 * signal to the fallback poller that it has to carry the conversation instead.
 */
sealed interface KitRealtimeState {
    /** No socket, and no reason to open one — backgrounded, or no capability. */
    data object Idle : KitRealtimeState

    /** The TCP/TLS upgrade is in flight. */
    data object Connecting : KitRealtimeState

    /** Upgraded; waiting for `pusher:connection_established` and the socket id. */
    data object Handshaking : KitRealtimeState

    /** The account's own authenticated private nudge channel is being subscribed. */
    data object Subscribing : KitRealtimeState

    /** Subscribed to the nudge channel. Periodic messaging polling stops here. */
    data object Live : KitRealtimeState

    /** Disconnected, retrying at [retryAtElapsedMillis] on the elapsed-time clock. */
    data class Backoff(val retryAtElapsedMillis: Long) : KitRealtimeState

    /**
     * Terminal for this session epoch: the connection cannot succeed as configured
     * and retrying would only burn battery. A new sign-in clears it.
     */
    data class Suspended(val reason: KitRealtimeSuspension) : KitRealtimeState
}

/** Why a socket was given up on. Reported for diagnostics; never rendered. */
enum class KitRealtimeSuspension {
    /** `pusher:error` 4000-4099 — the app key, protocol or version is unusable. */
    ProtocolRejected,

    /** Two consecutive 401s across a token refresh. */
    Unauthenticated,

    /** 403 on the account's own nudge channel. */
    Forbidden,

    /** 503 from `messaging.protocol-access` — the feature is off for this account. */
    ProtocolUnavailable,
}
