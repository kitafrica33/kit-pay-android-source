package com.kit.wallet.feature.calls

import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleKind

internal enum class BackendCallTerminationKind { END, DECLINE }

internal data class PendingCallTermination(
    val callId: String,
    val kind: BackendCallTerminationKind,
    val reason: String = "cancelled",
)

internal fun CallLifecycleEvent.pendingLocalTermination(): PendingCallTermination? {
    if (!localEndRequested || kind != CallLifecycleKind.ENDED) return null
    return PendingCallTermination(
        callId, BackendCallTerminationKind.END,
        reason?.takeIf { it in setOf("completed", "cancelled", "network_error") } ?: "cancelled",
    )
}

/** Keeps failed backend cleanup durable for the lifetime of the call ViewModel. */
internal class CallTerminationQueue {
    private val actions = linkedMapOf<String, PendingCallTermination>()

    val isEmpty: Boolean
        get() = actions.isEmpty()

    fun enqueue(action: PendingCallTermination) {
        // The original endpoint choice reflects whether this device had accepted the call.
        // A later retry must not replace DECLINE with END (or lose an older call ID).
        actions.putIfAbsent(action.callId, action)
    }

    fun snapshot(): List<PendingCallTermination> = actions.values.toList()

    fun completed(callId: String) {
        actions.remove(callId)
    }

    suspend fun drain(perform: suspend (PendingCallTermination) -> Boolean): Boolean {
        snapshot().forEach { action -> if (perform(action)) completed(action.callId) }
        return isEmpty
    }
}
