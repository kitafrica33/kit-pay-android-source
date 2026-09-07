package com.kit.wallet.feature.calls

import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A link opens an authenticated preview. Possession never authorizes joining or inviting. */
class CallInviteLink private constructor(val token: String) {
    fun deepLinkUri(): String = "kitpay://call-invites/$token"
    override fun toString(): String = "CallInviteLink([redacted])"

    companion object {
        private val tokenPattern = Regex("[A-Za-z0-9]{64}")
        fun fromToken(token: String): CallInviteLink? = token.takeIf(tokenPattern::matches)?.let(::CallInviteLink)

        fun fromDeepLink(raw: String): CallInviteLink? {
            if (raw.length != "kitpay://call-invites/".length + 64) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme != "kitpay" || uri.host != "call-invites" || uri.rawAuthority != "call-invites" ||
                uri.rawQuery != null || uri.rawFragment != null || uri.rawPath != uri.path
            ) return null
            return uri.path?.removePrefix("/")?.let(::fromToken)
        }
    }
}

object ScheduledCallPolicy {
    const val MAX_RECIPIENTS = 20
    const val MAX_TITLE_LENGTH = 160
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

    fun canonicalId(raw: String?): String? = raw?.takeIf(uuidPattern::matches)
        ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }

    fun recipients(ids: List<String>, ownId: String?): List<String> {
        require(ids.size in 1..MAX_RECIPIENTS) { "Choose between 1 and 20 Kit Pay contacts." }
        val normalized = ids.map { requireNotNull(canonicalId(it)) { "Choose a valid Kit Pay contact." } }
        require(normalized.distinct().size == normalized.size) { "Choose each contact only once." }
        require(canonicalId(ownId) !in normalized) { "You cannot call yourself." }
        return normalized
    }

    fun title(raw: String): String? {
        val result = raw.trim()
        require(result.length <= MAX_TITLE_LENGTH && result.none(Char::isISOControl)) {
            "Use a title of at most 160 characters."
        }
        return result.takeIf(String::isNotEmpty)
    }

    /** Explicit local offset and seconds; nonexistent/ambiguous DST wall times need reselection. */
    fun startsAt(local: LocalDateTime, zone: ZoneId, now: Instant): String {
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "This time changes with daylight saving. Choose another time." }
        val selected = local.atOffset(offsets.single())
        validateStart(selected.toInstant(), now)
        return selected.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX"))
    }

    fun validateStart(start: Instant, now: Instant) {
        require(start.isAfter(now)) { "Choose a future time." }
        require(!start.isAfter(now.atZone(java.time.ZoneOffset.UTC).plusYears(1).toInstant())) {
            "Schedule calls no more than one year ahead."
        }
    }

    fun secureRtcUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.fragment == null && uri.query == null && uri.port in -1..65535 && uri.port != 0
    }

    fun requireFreshExpiry(expiresAt: String, serverTime: String) {
        require(Instant.parse(expiresAt).isAfter(Instant.parse(serverTime))) { "This invitation has expired." }
    }
}

/** Bounded page admission: malformed or repeating cursors never cause an automatic request loop. */
internal class ScheduledCallPageCursor {
    private val seen = mutableSetOf<String>()
    fun reset() = seen.clear()
    fun accept(next: String?, hasMore: Boolean): String? {
        if (!hasMore) return null
        require(!next.isNullOrBlank() && next.length <= 4096 && seen.size < 100 && seen.add(next)) {
            "Could not load more scheduled calls. Refresh to try again."
        }
        return next
    }
}
