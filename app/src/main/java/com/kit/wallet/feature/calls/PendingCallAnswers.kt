package com.kit.wallet.feature.calls

import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleKind

/**
 * Holds an answer that arrived before this screen knew which call it was for.
 *
 * Placing a call is two things at once: a `POST /calls` whose response carries the call
 * id, and a socket that is already live. On a slow uplink the person being called can
 * pick up, and the server can send both the frame and the push, while that response is
 * still in flight. Until it lands `connection` is null, so the screen has nothing to
 * match an inbound answer against and would drop it — costing exactly the latency the
 * signal exists to remove, and leaving the timer anchored at connect instead of at the
 * answer.
 *
 * The event bus cannot solve this itself: it replays nothing, deliberately, so that a
 * screen opened later never acts on a stale call. So the screen holds the answer instead,
 * keyed by the id it names, and claims it the moment a response arrives with that id.
 * An answer for any other call is never claimed and simply ages out of the buffer.
 *
 * Bounded, because it is fed by the network: it keeps the most recent [CAPACITY]
 * unclaimed answers and discards the oldest beyond that.
 *
 * Pure Kotlin, so the fencing rule is pinned by a unit test rather than by a device.
 */
internal class PendingCallAnswers {
    private val held = LinkedHashMap<String, CallLifecycleEvent>()

    val size: Int get() = held.size

    /** Returns whether the event was held, which is what the tests assert. */
    fun remember(event: CallLifecycleEvent): Boolean {
        if (event.kind != CallLifecycleKind.ANSWERED) return false
        if (event.callId.isBlank()) return false

        // Re-inserted rather than merged, so a repeat of the same answer refreshes its
        // position instead of ageing out behind newer, unrelated ones. Keyed lowercase,
        // because the claim arrives with the id verbatim from a REST response while the
        // event's id has been through canonical validation — the same call must never
        // fail to claim itself over that case difference.
        val key = event.callId.lowercase()
        held.remove(key)
        held[key] = event
        while (held.size > CAPACITY) {
            held.remove(held.keys.first())
        }
        return true
    }

    /**
     * Claims the answer for [callId], if one is held. Claiming removes it: an answer is
     * applied once, and a second call for the same id returns nothing.
     */
    fun claim(callId: String): CallLifecycleEvent? = held.remove(callId.lowercase())

    /** Forgotten when a new call starts, so nothing from a previous one can be claimed. */
    fun clear() = held.clear()

    private companion object {
        const val CAPACITY = 8
    }
}
