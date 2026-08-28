package com.kit.wallet.data.notifications

import com.kit.wallet.data.session.SessionFence
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal const val ACTION_OPEN_AUTHORIZED_INCOMING_CALL =
    "com.kit.wallet.action.OPEN_AUTHORIZED_INCOMING_CALL"
internal const val EXTRA_INCOMING_CALL_AUTHORIZATION =
    "com.kit.wallet.extra.INCOMING_CALL_AUTHORIZATION"

internal enum class IncomingCallLaunchPurpose {
    OPEN,
    ANSWER,
}

/**
 * The only incoming-call route that may raise [com.kit.wallet.MainActivity] above the keyguard.
 * It is created by the app-private relay activity and recovered through a one-time capability;
 * public URIs and caller-supplied extras never construct this value.
 */
internal data class AuthorizedIncomingCallLaunch(
    val callId: String,
    val purpose: IncomingCallLaunchPurpose,
    val session: SessionFence,
    val ringExpiresAt: Instant,
    val expiresAt: Instant,
) {
    val acceptRequested: Boolean
        get() = purpose == IncomingCallLaunchPurpose.ANSWER
}

/**
 * Process-private, one-time authority handed from the non-exported notification relay to
 * [com.kit.wallet.MainActivity]. A notification PendingIntent survives process death by opening
 * the relay first; the relay then issues a fresh grant in the new process. The token itself never
 * appears in a public URI and is consumed even when its session or expiry check fails.
 */
@Singleton
internal class IncomingCallLaunchAuthorizer @Inject constructor(
    private val clock: Clock,
) {
    private data class Grant(
        val callId: String,
        val purpose: IncomingCallLaunchPurpose,
        val session: SessionFence,
        val issuedAt: Instant,
        val ringExpiresAt: Instant,
        val expiresAt: Instant,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val grants = linkedMapOf<String, Grant>()

    fun issue(
        callId: String,
        purpose: IncomingCallLaunchPurpose,
        session: SessionFence,
        ringExpiresAt: String?,
    ): String? {
        val canonicalCallId = canonicalIncomingCallId(callId) ?: return null
        if (!session.isUsableCallFence()) return null
        val now = clock.instant()
        val serverExpiry = ringExpiresAt
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        if (!serverExpiry.isAfter(now)) return null
        val boundedExpiry = minOf(serverExpiry, now.plus(MAX_GRANT_LIFETIME))

        return synchronized(lock) {
            purgeExpiredLocked(now)
            while (grants.size >= MAX_ACTIVE_GRANTS) {
                grants.remove(grants.keys.first())
            }
            var token: String
            do {
                val entropy = ByteArray(TOKEN_BYTES).also(random::nextBytes)
                token = try {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
                } finally {
                    entropy.fill(0)
                }
            } while (token in grants)
            grants[token] = Grant(
                callId = canonicalCallId,
                purpose = purpose,
                session = session,
                issuedAt = now,
                ringExpiresAt = serverExpiry,
                expiresAt = boundedExpiry,
            )
            token
        }
    }

    /** Consumes the named grant before checking it so a failed attempt cannot be replayed. */
    fun consume(token: String?, currentSession: SessionFence?): AuthorizedIncomingCallLaunch? {
        if (token == null || !TOKEN_PATTERN.matches(token)) return null
        val now = clock.instant()
        return synchronized(lock) {
            val grant = grants.remove(token) ?: return@synchronized null
            if (
                currentSession == null ||
                currentSession != grant.session ||
                now.isBefore(grant.issuedAt) ||
                !now.isBefore(grant.expiresAt)
            ) {
                null
            } else {
                AuthorizedIncomingCallLaunch(
                    callId = grant.callId,
                    purpose = grant.purpose,
                    session = grant.session,
                    ringExpiresAt = grant.ringExpiresAt,
                    expiresAt = grant.expiresAt,
                )
            }
        }
    }

    fun revokeAll() = synchronized(lock) {
        grants.clear()
    }

    private fun purgeExpiredLocked(now: Instant) {
        grants.entries.removeAll { (_, grant) ->
            now.isBefore(grant.issuedAt) || !now.isBefore(grant.expiresAt)
        }
    }

    private fun SessionFence.isUsableCallFence(): Boolean =
        sessionId.isNotBlank() &&
            sessionId.length <= MAX_FENCE_COMPONENT_LENGTH &&
            cacheScopeId.isNotBlank() &&
            cacheScopeId.length <= MAX_FENCE_COMPONENT_LENGTH &&
            canonicalIncomingCallId(accountId) != null

    private companion object {
        val MAX_GRANT_LIFETIME: Duration = Duration.ofMinutes(1)
        const val TOKEN_BYTES = 32
        const val MAX_ACTIVE_GRANTS = 16
        const val MAX_FENCE_COMPONENT_LENGTH = 256
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

    }
}

private val CANONICAL_INCOMING_CALL_UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)

internal fun canonicalIncomingCallId(raw: String?): String? {
    val value = raw ?: return null
    if (!CANONICAL_INCOMING_CALL_UUID.matches(value)) return null
    val canonical = runCatching { UUID.fromString(value).toString() }.getOrNull() ?: return null
    return canonical.takeIf { it.equals(value, ignoreCase = true) }
}
