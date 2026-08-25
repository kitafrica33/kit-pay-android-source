package com.kit.wallet.data.realtime

/**
 * The socket, reduced to the four things the state machine actually needs.
 *
 * Narrow on purpose: with the transport behind an interface, every row of the
 * failure table — refused upgrade, protocol error, missed pong, lost network,
 * lifetime expiry — is exercised by an ordinary JVM unit test against a fake,
 * rather than only by a device run that CI never performs.
 */
internal interface KitRealtimeTransport {
    /** Opens a connection. Callbacks arrive on [listener] until it is closed. */
    fun open(url: String, listener: Listener)

    /** Queues one text frame. Returns false if the socket is already gone. */
    fun send(text: String): Boolean

    /**
     * Graceful close: flushes queued frames, sends the close frame, waits for the
     * peer. Used for the lifetime reconnect and for backgrounding, so the server
     * reports `member_removed` in under a second instead of waiting out its
     * activity timeout.
     */
    fun close(code: Int, reason: String)

    /** Immediate teardown with no close handshake. Used when the peer is unresponsive. */
    fun cancel()

    interface Listener {
        fun onOpen()

        fun onFrame(text: String)

        fun onClosed(code: Int, reason: String)

        fun onFailure(error: Throwable)
    }

    companion object {
        /** The only close code we ever originate: a normal, intentional close. */
        const val CLOSE_NORMAL: Int = 1000
    }
}
