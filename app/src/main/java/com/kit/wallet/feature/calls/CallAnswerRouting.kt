package com.kit.wallet.feature.calls

/** What an accepted `call.answered` signal means for the screen that received it. */
internal enum class CallAnswerAction {
    /**
     * Another device on this account took the call. This screen is still offering it, so it
     * stops offering it — the OS call is finished as answered-elsewhere rather than missed.
     */
    SUPERSEDE_LOCAL_RING,

    /** The person called picked up. The outgoing call leaves ringing and starts connecting. */
    ADVANCE_TO_CONNECTING,

    /**
     * Nothing to move. The signal is still worth having: it carries the authoritative answer
     * instant, so the timer takes its origin from it either way.
     */
    ANCHOR_ONLY,
}

/**
 * Decides what an answer signal does to a call screen, given only that screen's phase.
 *
 * The same `call.answered` reaches three kinds of device, and each has to do something
 * different with it: the caller advances, the account's *other* ringing devices stop, and
 * the device that actually answered does nothing but take the timestamp. Getting that wrong
 * in either direction is visible — a device that ignores it keeps ringing for a call the
 * user is already on, and a device that over-applies it hangs up the call it just answered.
 *
 * The distinguishing signal is that the answering device is already past its ring by the
 * time its own answer echoes back: `connect` moves the phase and takes out a start job
 * before it sends the request, so it holds a connection or an in-flight one, and a sibling
 * device holds neither.
 *
 * Pure Kotlin, so every phase is pinned by a unit test rather than by two real handsets.
 */
internal object CallAnswerRouting {
    /** Phases in which this screen is still offering an incoming call to the user. */
    private val OFFERING = setOf(CallPhase.VALIDATING, CallPhase.INCOMING)

    fun actionFor(
        phase: CallPhase,
        hasConnection: Boolean,
        starting: Boolean,
    ): CallAnswerAction = when {
        !hasConnection && !starting && phase in OFFERING -> CallAnswerAction.SUPERSEDE_LOCAL_RING
        phase == CallPhase.RINGING -> CallAnswerAction.ADVANCE_TO_CONNECTING
        else -> CallAnswerAction.ANCHOR_ONLY
    }

    /**
     * Where a call lands once its room is connected.
     *
     * [alreadyAnswered] is the buffered-answer case: the callee picked up while `POST
     * /calls` was still in flight, so by the time the room is up this caller is past
     * ringing. Showing "Ringing…" here would hand back exactly the delay the buffer exists
     * to remove, and no second answer signal is coming to correct it.
     */
    fun phaseAfterConnect(
        hasRemoteParticipants: Boolean,
        incoming: Boolean,
        alreadyAnswered: Boolean,
    ): CallPhase = when {
        hasRemoteParticipants -> CallPhase.CONNECTED
        incoming || alreadyAnswered -> CallPhase.CONNECTING
        else -> CallPhase.RINGING
    }

    /**
     * Whether a call that has just been placed still needs its ring window armed.
     *
     * Answering ends the window. Arming one over a call that has already been answered
     * would let the original deadline expire and mark a call the user is on as missed —
     * and the buffered answer arrives *before* the response that carries the deadline, so
     * this is the only place left to refuse it.
     */
    fun armsRingDeadline(incoming: Boolean, alreadyAnswered: Boolean): Boolean =
        !incoming && !alreadyAnswered
}
