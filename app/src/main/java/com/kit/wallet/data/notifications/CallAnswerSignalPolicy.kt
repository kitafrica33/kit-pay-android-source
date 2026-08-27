package com.kit.wallet.data.notifications

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The two server timestamps that survived validation, ready to anchor a timer with. */
data class CallAnswerAnchorFields(
    val answeredAt: String,
    val serverTime: String?,
)

/**
 * What a `call.answered` signal has to look like before anything acts on it.
 *
 * Two routes carry this answer — the authenticated `kit.call.answered` socket frame and
 * the `call.answered` push — and acting on one has real consequences: a ring deadline is
 * cancelled, a phase moves, a timer takes an origin. Both routes are validated here, by
 * the same rules, so neither becomes the softer way in.
 *
 * The rules are all shape and self-consistency, never a comparison against this device's
 * clock. A phone with a wrong clock must not start rejecting genuine answers, which is
 * exactly what a "not too far from now" rule would do.
 *
 * Pure Kotlin, so every rule is pinned by a unit test.
 */
object CallAnswerSignalPolicy {
    /** The server hard-caps a call at four hours; a longer claimed age is a replay. */
    const val MAXIMUM_AGE_SECONDS: Long = 14_400

    /**
     * How far `answered_at` may sit after `server_time` before the pair is refused. The two
     * are stamped by different processes, so a few hundred milliseconds either way is
     * ordinary and reads as "no time has passed". Anything beyond a minute is a claim that
     * the call was answered in the future, which no honest server makes.
     */
    const val MAXIMUM_FUTURE_SKEW_SECONDS: Long = 60

    /** The only state a `call.answered` signal may announce. */
    const val ACTIVE_STATE: String = "active"

    fun announcesActive(state: String?): Boolean = state == ACTIVE_STATE

    /**
     * Normalises a call id, refusing anything that is not the UUID the server issues.
     *
     * `UUID.fromString` alone is not that check: it is deliberately permissive about short
     * hex groups and silently zero-pads them, so a truncated id parses and comes back as a
     * *different, valid-looking* id. Requiring the parse to round-trip to what was sent is
     * what makes this a validation rather than a repair.
     */
    fun callId(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val canonical = runCatching { UUID.fromString(trimmed).toString() }.getOrNull() ?: return null
        return canonical.takeIf { it.equals(trimmed, ignoreCase = true) }
    }

    /**
     * Validates the timestamp pair, or returns null if the signal must not be anchored to.
     *
     * A missing [serverTime] is allowed: an older server sends only the answer instant, and
     * the anchor policy reads that as an age of zero. A *present but unparseable* one is
     * not — that is a malformed message rather than an old one, and taking half of it would
     * mean anchoring to a number the server never sent.
     */
    fun anchor(answeredAt: String?, serverTime: String?): CallAnswerAnchorFields? {
        val answeredRaw = answeredAt?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val answered = parse(answeredRaw) ?: return null

        val stampedRaw = serverTime?.trim()?.takeIf(String::isNotEmpty)
        if (stampedRaw == null) {
            return CallAnswerAnchorFields(answeredAt = answeredRaw, serverTime = null)
        }

        val stamped = parse(stampedRaw) ?: return null
        val ageSeconds = Duration.between(answered, stamped).seconds
        if (ageSeconds > MAXIMUM_AGE_SECONDS) return null
        if (ageSeconds < -MAXIMUM_FUTURE_SKEW_SECONDS) return null

        return CallAnswerAnchorFields(answeredAt = answeredRaw, serverTime = stampedRaw)
    }

    private fun parse(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
}
