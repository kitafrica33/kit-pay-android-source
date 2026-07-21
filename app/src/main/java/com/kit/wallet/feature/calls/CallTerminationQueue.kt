package com.kit.wallet.feature.calls

internal enum class BackendCallTerminationKind { END, DECLINE }

internal data class PendingCallTermination(
    val callId: String,
    val kind: BackendCallTerminationKind,
    val reason: String = "cancelled",
)

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
}
