package com.kit.wallet.feature.calls

/**
 * Process-local lifecycle state for calls handed to Android Telecom.
 *
 * Telecom creates a [android.telecom.Connection] asynchronously after `placeCall` or
 * `addNewIncomingCall`. A terminal backend event can therefore arrive before that callback. Those
 * calls stay as unresolved tombstones until the callback is observed, preventing a late callback
 * from recreating a ringing or dialing call after it already ended.
 */
internal class TerminalAwareTelecomCallRegistry<Metadata : Any, State : Any, Connection : Any, Disconnect : Any>(
    private val maxResolvedTombstones: Int = DEFAULT_RESOLVED_TOMBSTONES,
) {
    init {
        require(maxResolvedTombstones > 0)
    }

    private val liveCalls = mutableMapOf<String, RegisteredTelecomCall<Metadata, State, Connection>>()
    private val terminalCalls = linkedMapOf<String, TerminalTelecomCall<Disconnect>>()

    /**
     * Adds or refreshes a live call. A terminal call id is never made live again in this process.
     */
    @Synchronized
    fun track(
        callId: String,
        metadata: Metadata,
        initialState: State,
    ): TelecomTrackResult<Metadata, State, Connection>? {
        if (terminalCalls.containsKey(callId)) return null

        val previous = liveCalls[callId]
        val next = previous?.copy(metadata = metadata)
            ?: RegisteredTelecomCall(metadata = metadata, state = initialState)
        liveCalls[callId] = next
        return TelecomTrackResult(call = next, alreadyTracked = previous != null)
    }

    /**
     * Resolves a failed `placeCall`/`addNewIncomingCall` attempt as bounded terminal history. If a
     * terminal event raced with the platform failure, its reason is preserved. Otherwise the
     * supplied failure reason still blocks a callback accepted just before Telecom threw.
     */
    @Synchronized
    fun registrationFailed(
        callId: String,
        disconnect: Disconnect,
    ): RegisteredTelecomCall<Metadata, State, Connection>? {
        val live = liveCalls.remove(callId)
        val terminal = terminalCalls.remove(callId)
        if (live == null && terminal == null) return null

        terminalCalls[callId] = TerminalTelecomCall(
            disconnect = terminal?.disconnect ?: disconnect,
            awaitingConnection = false,
        )
        trimResolvedTombstones()
        return live
    }

    @Synchronized
    fun updateMetadata(
        callId: String,
        transform: (Metadata) -> Metadata,
        applyToConnection: (Connection, Metadata) -> Unit,
    ): RegisteredTelecomCall<Metadata, State, Connection>? {
        val current = liveCalls[callId] ?: return null
        val updated = current.copy(metadata = transform(current.metadata))
        liveCalls[callId] = updated
        updated.connection?.let { applyToConnection(it, updated.metadata) }
        return updated
    }

    @Synchronized
    fun updateState(
        callId: String,
        state: State,
        applyToConnection: (Connection, State) -> Unit,
    ): RegisteredTelecomCall<Metadata, State, Connection>? {
        val current = liveCalls[callId] ?: return null
        val updated = current.copy(state = state)
        liveCalls[callId] = updated
        updated.connection?.let { applyToConnection(it, state) }
        return updated
    }

    /**
     * Atomically attaches and prepares a Telecom connection, or resolves it against a tombstone.
     * [prepareLiveConnection] runs while lifecycle mutation is excluded, so `finish` cannot
     * disconnect the connection and then have an older ringing/dialing state applied afterward.
     */
    @Synchronized
    fun attachConnection(
        callId: String,
        metadata: Metadata,
        initialState: State,
        createConnection: () -> Connection,
        prepareLiveConnection: (Connection, State) -> Unit,
    ): TelecomConnectionResolution<Metadata, State, Connection, Disconnect> {
        terminalCalls[callId]?.let { terminal ->
            if (terminal.awaitingConnection) {
                terminalCalls.remove(callId)
                terminalCalls[callId] = terminal.copy(awaitingConnection = false)
                trimResolvedTombstones()
            }
            return TelecomConnectionResolution(terminalDisconnect = terminal.disconnect)
        }

        val connection = createConnection()
        val current = liveCalls[callId]
            ?: RegisteredTelecomCall(metadata = metadata, state = initialState)
        val attached = current.copy(metadata = metadata, connection = connection)
        liveCalls[callId] = attached
        prepareLiveConnection(connection, attached.state)
        return TelecomConnectionResolution(liveCall = attached)
    }

    /**
     * Makes termination durable before the platform connection is completed. When no connection
     * exists yet, the tombstone is unresolved and is deliberately excluded from bounded pruning.
     */
    @Synchronized
    fun finish(
        callId: String,
        disconnect: Disconnect,
    ): RegisteredTelecomCall<Metadata, State, Connection>? {
        val live = liveCalls.remove(callId)
        val previous = terminalCalls.remove(callId)
        if (live == null && previous == null) return null

        terminalCalls[callId] = TerminalTelecomCall(
            disconnect = disconnect,
            awaitingConnection = previous?.awaitingConnection ?: (live?.connection == null),
        )
        trimResolvedTombstones()
        return live
    }

    private fun trimResolvedTombstones() {
        var resolvedCount = terminalCalls.values.count { !it.awaitingConnection }
        if (resolvedCount <= maxResolvedTombstones) return

        val iterator = terminalCalls.entries.iterator()
        while (iterator.hasNext() && resolvedCount > maxResolvedTombstones) {
            if (!iterator.next().value.awaitingConnection) {
                iterator.remove()
                resolvedCount -= 1
            }
        }
    }

    private companion object {
        const val DEFAULT_RESOLVED_TOMBSTONES = 256
    }
}

internal data class RegisteredTelecomCall<Metadata : Any, State : Any, Connection : Any>(
    val metadata: Metadata,
    val state: State,
    val connection: Connection? = null,
)

internal data class TelecomTrackResult<Metadata : Any, State : Any, Connection : Any>(
    val call: RegisteredTelecomCall<Metadata, State, Connection>,
    val alreadyTracked: Boolean,
)

internal data class TelecomConnectionResolution<Metadata : Any, State : Any, Connection : Any, Disconnect : Any>(
    val liveCall: RegisteredTelecomCall<Metadata, State, Connection>? = null,
    val terminalDisconnect: Disconnect? = null,
) {
    init {
        require((liveCall == null) != (terminalDisconnect == null))
    }
}

private data class TerminalTelecomCall<Disconnect : Any>(
    val disconnect: Disconnect,
    val awaitingConnection: Boolean,
)
