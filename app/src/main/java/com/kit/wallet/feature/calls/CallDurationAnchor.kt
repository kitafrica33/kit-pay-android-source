package com.kit.wallet.feature.calls

import java.time.Duration
import java.time.Instant

/**
 * The point on this device's monotonic clock that the call was answered.
 *
 * Held as an `elapsedRealtime()` reading rather than a wall-clock instant on purpose:
 * the phone's wall clock can step — NTP correction, timezone edit, a device that woke
 * from doze with a stale clock — and a timer anchored to it would jump with it.
 * `elapsedRealtime()` only ever moves forward at one second per second.
 */
internal data class CallDurationAnchor(
    val callId: String,
    val elapsedRealtimeAtAnswerMillis: Long,
)

/**
 * Decides where that anchor sits from whatever the server told us.
 *
 * The same answer arrives by up to three routes — the accept response, the
 * `kit.call.answered` socket frame and the `call.answered` push — and each carries the
 * same authoritative `answered_at` beside the server's own `server_time`. Subtracting
 * one from the other gives the call's age **when that message was built**, entirely in
 * server time. Neither timestamp is ever compared against the device's clock, so the
 * anchor cannot inherit its drift.
 *
 * Every route under-reports by its own transit delay, because the message spends time
 * in flight after the server stamps it. So the candidate anchors are all at or after
 * the true answer, and the earliest of them is the most accurate. Keeping the earliest
 * is therefore both the closest estimate and the reason the displayed duration only
 * ever moves forward: a second signal can correct the timer up, never back.
 *
 * Pure Kotlin so the whole rule is pinned by unit tests instead of by a device.
 */
internal object CallDurationAnchorPolicy {
    /**
     * The longest age a signal may claim. The server hard-caps a call at four hours, so
     * anything beyond that is a replayed or forged answer rather than a long call, and
     * honouring it would show a caller a wildly inflated duration. Such a signal is
     * dropped rather than clamped — a bogus anchor is worse than the one already held.
     */
    const val MAXIMUM_AGE_SECONDS: Long = 14_400

    /**
     * @param answeredAt server-authoritative ISO-8601 instant the call became active.
     * @param serverTime the server's clock when it built the message carrying [answeredAt].
     *   Null on a server that predates the field: the age is then taken as zero, which is
     *   the pre-existing behaviour of starting the timer on receipt, and still never
     *   reads the device clock.
     * @param elapsedRealtimeMillis this device's monotonic clock, read on receipt.
     * @param previous the anchor already held for this call, if any.
     */
    fun anchor(
        callId: String,
        answeredAt: String?,
        serverTime: String?,
        elapsedRealtimeMillis: Long,
        previous: CallDurationAnchor? = null,
    ): CallDurationAnchor? {
        // Ignoring case: the accept response carries the id verbatim while validated
        // events carry it canonically lowercased, and both describe the same call.
        val current = previous?.takeIf { it.callId.equals(callId, ignoreCase = true) }
        if (callId.isBlank()) return current
        val answered = answeredAt?.let { parse(it) } ?: return current

        // A server that has not been taught server_time yet gets an age of zero rather
        // than a reading of this phone's clock, which is exactly what it would take to
        // reintroduce the drift this class exists to keep out.
        val stamped = serverTime?.let { parse(it) } ?: answered
        val ageSeconds = Duration.between(answered, stamped).seconds
        if (ageSeconds > MAXIMUM_AGE_SECONDS) return current

        // A tiny negative age is ordinary: the two instants can be stamped by different
        // processes a few milliseconds apart. It just means no time has passed yet.
        val candidate = CallDurationAnchor(
            callId = callId,
            elapsedRealtimeAtAnswerMillis = elapsedRealtimeMillis - ageSeconds.coerceAtLeast(0) * 1_000,
        )
        if (current == null) return candidate
        return if (candidate.elapsedRealtimeAtAnswerMillis < current.elapsedRealtimeAtAnswerMillis) {
            candidate
        } else {
            current
        }
    }

    /**
     * Anchors on this instant, for a call that is connected with nothing authoritative to
     * anchor to. Never earlier than the true answer, so a later server signal can still
     * correct it forward through [anchor].
     */
    fun anchorOnConnect(callId: String, elapsedRealtimeMillis: Long): CallDurationAnchor =
        CallDurationAnchor(callId = callId, elapsedRealtimeAtAnswerMillis = elapsedRealtimeMillis)

    fun seconds(anchor: CallDurationAnchor?, elapsedRealtimeMillis: Long): Long {
        if (anchor == null) return 0
        return ((elapsedRealtimeMillis - anchor.elapsedRealtimeAtAnswerMillis) / 1_000)
            .coerceAtLeast(0)
    }

    private fun parse(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
}
