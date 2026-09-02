package com.kit.wallet.data.notifications

import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import java.security.SecureRandom
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
    val ringLease: CallRingLease,
) {
    val acceptRequested: Boolean
        get() = purpose == IncomingCallLaunchPurpose.ANSWER

    val ringExpiresAt: String
        get() = ringLease.sourceRingExpiresAt
}

/**
 * Process-private, one-time authority handed from the non-exported notification relay to
 * [com.kit.wallet.MainActivity]. A notification PendingIntent survives process death by opening
 * the relay first; the relay then issues a fresh grant in the new process. The token itself never
 * appears in a public URI and is consumed even when its session or expiry check fails.
 */
@Singleton
internal class IncomingCallLaunchAuthorizer @Inject constructor(
    private val elapsedRealtimeClock: ElapsedRealtimeClock,
    private val bootSessionIdProvider: BootSessionIdProvider,
) {
    private data class Grant(
        val callId: String,
        val purpose: IncomingCallLaunchPurpose,
        val session: SessionFence,
        val issuedAtElapsedRealtimeMillis: Long,
        val ringLease: CallRingLease,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val grants = linkedMapOf<String, Grant>()

    fun issue(
        callId: String,
        purpose: IncomingCallLaunchPurpose,
        session: SessionFence,
        ringLease: CallRingLease,
    ): String? {
        val canonicalCallId = canonicalIncomingCallId(callId) ?: return null
        if (!session.isUsableCallFence()) return null
        val now = elapsedRealtimeClock.millis()
        val bootId = bootSessionIdProvider.currentBootId()
        if (ringLease.remainingMillis(now, bootId) == null) return null

        return synchronized(lock) {
            purgeExpiredLocked(now, bootId)
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
                issuedAtElapsedRealtimeMillis = now,
                ringLease = ringLease,
            )
            token
        }
    }

    /** Consumes the named grant before checking it so a failed attempt cannot be replayed. */
    fun consume(token: String?, currentSession: SessionFence?): AuthorizedIncomingCallLaunch? {
        if (token == null || !TOKEN_PATTERN.matches(token)) return null
        val now = elapsedRealtimeClock.millis()
        val bootId = bootSessionIdProvider.currentBootId()
        return synchronized(lock) {
            val grant = grants.remove(token) ?: return@synchronized null
            if (
                currentSession == null ||
                currentSession != grant.session ||
                now < grant.issuedAtElapsedRealtimeMillis ||
                grant.ringLease.remainingMillis(now, bootId) == null
            ) {
                null
            } else {
                AuthorizedIncomingCallLaunch(
                    callId = grant.callId,
                    purpose = grant.purpose,
                    session = grant.session,
                    ringLease = grant.ringLease,
                )
            }
        }
    }

    fun revokeAll() = synchronized(lock) {
        grants.clear()
    }

    private fun purgeExpiredLocked(nowElapsedRealtimeMillis: Long, currentBootSessionId: Long?) {
        grants.entries.removeAll { (_, grant) ->
            nowElapsedRealtimeMillis < grant.issuedAtElapsedRealtimeMillis ||
                grant.ringLease.remainingMillis(
                    nowElapsedRealtimeMillis,
                    currentBootSessionId,
                ) == null
        }
    }

    private fun SessionFence.isUsableCallFence(): Boolean =
        sessionId.isNotBlank() &&
            sessionId.length <= MAX_FENCE_COMPONENT_LENGTH &&
            cacheScopeId.isNotBlank() &&
            cacheScopeId.length <= MAX_FENCE_COMPONENT_LENGTH &&
            canonicalIncomingCallId(accountId) != null

    private companion object {
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
