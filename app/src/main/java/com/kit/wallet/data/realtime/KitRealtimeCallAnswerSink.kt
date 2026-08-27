package com.kit.wallet.data.realtime

import com.kit.wallet.data.notifications.CallAnswerSignalPolicy
import com.kit.wallet.data.notifications.CallLifecycleEvent
import com.kit.wallet.data.notifications.CallLifecycleEventBus
import com.kit.wallet.data.notifications.CallLifecycleKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only route from a `kit.call.answered` frame into the call screen.
 *
 * It publishes onto the same bus the `call.answered` push already publishes onto, in
 * the same shape, so the call screen has one answer path rather than two. Whichever of
 * the socket and the push lands first wins; the loser is a duplicate the screen already
 * ignores, because both carry the same `answered_at` and the anchor only ever moves
 * earlier.
 *
 * Deliberately narrower than the push receiver: it does not touch Telecom, cancel a
 * notification or clear a ring deadline. Those are side effects on OS-owned state, and
 * the push that performs them is still coming. Duplicating them from a second source
 * would mean two orderings to reason about for no gain, since the thing this exists to
 * make fast is the audio and the timer, not the banner.
 *
 * Like the nudge sink it is closed unless the socket is `Live`, so a frame that arrives
 * during teardown is dropped rather than applied to a screen that is going away.
 */
@Singleton
internal class KitRealtimeCallAnswerSink @Inject constructor(
    private val callEvents: CallLifecycleEventBus,
) {
    @Volatile
    private var accepting: Boolean = false

    fun open() {
        accepting = true
    }

    fun close() {
        accepting = false
    }

    /** Returns whether the frame was published, which is what the tests assert. */
    fun onCallAnswered(frame: KitRealtimeFrame.CallAnswered): Boolean {
        if (!accepting) return false

        // Validated all the way through before anything downstream can act on it, because
        // acting on it cancels a ring deadline and moves a call's state. The codec has
        // already checked the frame's shape; this checks that what it says is possible —
        // a real call id, real instants, an age a call could actually have reached.
        val callId = CallAnswerSignalPolicy.callId(frame.callId) ?: return false
        val anchor = CallAnswerSignalPolicy.anchor(frame.answeredAt, frame.serverTime) ?: return false

        return callEvents.publish(
            CallLifecycleEvent(
                callId = callId,
                kind = CallLifecycleKind.ANSWERED,
                state = CallAnswerSignalPolicy.ACTIVE_STATE,
                answeredAt = anchor.answeredAt,
                serverTime = anchor.serverTime,
            ),
        )
    }
}
