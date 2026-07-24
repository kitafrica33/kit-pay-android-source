package com.kit.wallet.feature.calls

/**
 * Delivers a terminal Telecom transition exactly once, even when the backend call id is not known
 * until an in-flight create-call request completes.
 *
 * [resolveCallId] must be called after the call has been registered with Telecom. This ordering
 * lets a terminal request made while `POST /calls` is suspended become a tombstone before Android
 * Telecom's asynchronous `createConnection` callback can recreate a dialing call.
 */
internal class DeferredCallTermination<Disconnect : Any>(
    private val finish: (callId: String, disconnect: Disconnect) -> Unit,
    initialCallId: String? = null,
) {
    private var callId: String? = initialCallId
    private var requestedDisconnect: Disconnect? = null
    private var delivered = false

    fun resolveCallId(callId: String) {
        if (this.callId == null) this.callId = callId
        deliverIfReady()
    }

    fun terminate(disconnect: Disconnect) {
        if (requestedDisconnect == null) requestedDisconnect = disconnect
        deliverIfReady()
    }

    private fun deliverIfReady() {
        if (delivered) return
        val resolvedCallId = callId ?: return
        val resolvedDisconnect = requestedDisconnect ?: return
        delivered = true
        finish(resolvedCallId, resolvedDisconnect)
    }
}
