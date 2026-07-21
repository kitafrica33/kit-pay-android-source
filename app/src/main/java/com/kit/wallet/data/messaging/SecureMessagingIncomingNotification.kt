package com.kit.wallet.data.messaging

import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Metadata surfaced only after authenticated ciphertext and ratchet state commit together. */
internal data class SecureMessagingIncomingNotification(
    val messageId: String,
    val conversationId: String,
    val sessionEpoch: String,
)

internal class SecureMessagingNotificationPublicationException(cause: Exception) : IOException(
    "Secure-message notification publication failed",
    cause,
)

internal const val ACTION_OPEN_AUTHORIZED_SECURE_MESSAGE =
    "com.kit.wallet.action.OPEN_AUTHORIZED_SECURE_MESSAGE"
internal const val EXTRA_SECURE_MESSAGE_AUTHORIZATION =
    "com.kit.wallet.extra.SECURE_MESSAGE_AUTHORIZATION"

/**
 * Process-local, one-time authority for opening a conversation from an explicit notification
 * PendingIntent. No public URI contains a conversation ID, and a token is useful only for the
 * exact authenticated session epoch that received the message.
 */
@Singleton
class SecureMessageNavigationAuthorizer @Inject constructor(
    private val clock: Clock,
) {
    private data class Grant(
        val conversationId: String,
        val sessionEpoch: String,
        val issuedAt: Instant,
        val expiresAt: Instant,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val grants = linkedMapOf<String, Grant>()

    fun issue(conversationId: String, sessionEpoch: String): String {
        require(CANONICAL_UUID.matches(conversationId)) {
            "A secure-message notification requires a canonical conversation ID"
        }
        require(sessionEpoch.isNotBlank() && sessionEpoch.length <= MAX_SESSION_EPOCH_LENGTH) {
            "A secure-message notification requires a bounded authentication epoch"
        }
        val now = clock.instant()
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
                conversationId = conversationId,
                sessionEpoch = sessionEpoch,
                issuedAt = now,
                expiresAt = now.plus(GRANT_LIFETIME),
            )
            token
        }
    }

    /** Consumes the token even when it is expired or belongs to another authentication epoch. */
    fun consume(token: String?, currentSessionEpoch: String?): String? {
        if (token == null || !TOKEN_PATTERN.matches(token)) return null
        val now = clock.instant()
        return synchronized(lock) {
            val grant = grants.remove(token) ?: return@synchronized null
            if (
                currentSessionEpoch == null ||
                currentSessionEpoch != grant.sessionEpoch ||
                now.isBefore(grant.issuedAt) ||
                !now.isBefore(grant.expiresAt)
            ) {
                null
            } else {
                grant.conversationId
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

    private companion object {
        val GRANT_LIFETIME: Duration = Duration.ofMinutes(10)
        const val TOKEN_BYTES = 32
        const val MAX_ACTIVE_GRANTS = 256
        const val MAX_SESSION_EPOCH_LENGTH = 256
        val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}

internal fun interface SecureMessagingIncomingNotificationSink {
    /**
     * Idempotently publishes or replaces the notification identified by [notification.messageId].
     * The durable event processor deliberately retries calls whose completion is ambiguous.
     */
    fun publish(notification: SecureMessagingIncomingNotification)

    /** Cancels every message notification and invalidates all outstanding tap authorities. */
    fun cancelAll() = Unit
}

internal object NoOpSecureMessagingIncomingNotificationSink :
    SecureMessagingIncomingNotificationSink {
    override fun publish(notification: SecureMessagingIncomingNotification) = Unit
}
