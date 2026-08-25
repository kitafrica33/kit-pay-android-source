package com.kit.wallet.data.realtime

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The one and only WebSocket in the process, wrapped in OkHttp.
 *
 * Built from the shared authenticated client's `newBuilder()` and **never by
 * mutating it**: that instance is a `@Singleton` every wallet request goes
 * through, and it carries a 30-second read timeout that is correct for a request
 * and fatal for a socket that is idle by design. Two settings differ here:
 *
 * - `readTimeout(ZERO)` — an idle socket is the normal state, not a stall. Liveness
 *   is established by the protocol's own ping/pong, which we drive ourselves and
 *   can therefore reason about.
 * - `pingInterval(ZERO)` — OkHttp's ping is a *WebSocket control frame*, invisible
 *   to the socket server's application-level activity timeout. Answering that
 *   timeout needs a `pusher:ping`, so OkHttp's would cost radio wakeups and prove
 *   nothing.
 *
 * The interceptors and the `SessionAuthenticator` come along with the builder,
 * which is deliberate: the upgrade request is authenticated exactly like every
 * other request, with no token handling written here.
 *
 * Callbacks are fenced by a generation counter. A socket that has been replaced
 * can still deliver a queued `onFailure` from OkHttp's reader thread, and letting
 * that reach the state machine would have a dead connection tear down its live
 * successor.
 */
@Singleton
internal class KitRealtimeClient @Inject constructor(
    httpClient: OkHttpClient,
) : KitRealtimeTransport {
    private val client: OkHttpClient = httpClient.newBuilder()
        .pingInterval(Duration.ZERO)
        .readTimeout(Duration.ZERO)
        .build()

    private val generation = AtomicLong(0L)

    @Volatile
    private var socket: WebSocket? = null

    override fun open(url: String, listener: KitRealtimeTransport.Listener) {
        val current = generation.incrementAndGet()
        socket?.cancel()

        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (isStale(current)) return
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isStale(current)) return
                    listener.onFrame(text)
                }

                // Binary frames have no place in this protocol. Ignored rather than
                // treated as a failure: an unexpected frame is not a dead socket.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Acknowledge the peer's close so the handshake completes and the
                    // server drops us now, rather than at its activity timeout.
                    webSocket.close(KitRealtimeTransport.CLOSE_NORMAL, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isStale(current)) return
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (isStale(current)) return
                    listener.onFailure(t)
                }
            },
        )
    }

    override fun send(text: String): Boolean = socket?.send(text) == true

    override fun close(code: Int, reason: String) {
        val closing = socket
        socket = null
        // Retire the generation first: the close handshake produces callbacks we
        // asked for and have already accounted for.
        generation.incrementAndGet()
        closing?.close(code, reason)
    }

    override fun cancel() {
        val cancelling = socket
        socket = null
        generation.incrementAndGet()
        cancelling?.cancel()
    }

    private fun isStale(observed: Long): Boolean = generation.get() != observed
}
