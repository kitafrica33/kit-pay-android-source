package com.kit.wallet.data.notifications

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val SECURE_MESSAGE_NOTIFICATION_ID = 4_201
internal const val ANDROID_ACTIVE_NOTIFICATION_LIMIT = 50
internal const val RESERVED_NON_MESSAGE_NOTIFICATION_SLOTS = 18
internal const val MAX_ACTIVE_SECURE_MESSAGE_NOTIFICATIONS =
    ANDROID_ACTIVE_NOTIFICATION_LIMIT - RESERVED_NON_MESSAGE_NOTIFICATION_SLOTS
internal const val SECURE_MESSAGE_CONVERSATION_TAG_PREFIX = "kit_secure_conversation:"
internal const val LEGACY_SECURE_MESSAGE_TAG_PREFIX = "kit_secure_message:"
internal const val EXTRA_SECURE_MESSAGE_DIGEST = "kit_secure_message_digest"
internal const val EXTRA_SECURE_MESSAGE_SENT_AT_EPOCH_SECOND =
    "kit_secure_message_sent_at_epoch_second"
internal const val EXTRA_SECURE_MESSAGE_SENT_AT_NANO = "kit_secure_message_sent_at_nano"

/** Minimal platform-notification state needed for deterministic quota planning. */
internal data class ActiveSecureMessageNotification(
    val tag: String,
    val messageDigest: String?,
    val sentAtEpochSecond: Long,
    val sentAtNano: Int,
    val postedAtEpochMillis: Long,
) {
    init {
        require(sentAtNano in 0..999_999_999)
    }
}

internal data class SecureMessageNotificationPublicationPlan(
    val shouldPublish: Boolean,
    val onlyAlertOnce: Boolean,
    val tagsToCancel: List<String>,
)

/**
 * Keeps one latest notification per conversation and bounds Kit Pay's message share of Android's
 * package-wide 50-notification quota. The remaining slots stay available to ringing, call-waiting
 * and foreground-call notifications even when many conversations are unread.
 */
internal fun planSecureMessageNotificationPublication(
    active: List<ActiveSecureMessageNotification>,
    targetTag: String,
    incomingMessageDigest: String,
    incomingSentAtEpochSecond: Long,
    incomingSentAtNano: Int,
): SecureMessageNotificationPublicationPlan {
    require(incomingSentAtNano in 0..999_999_999)
    check(active.count { it.tag == targetTag } <= 1) {
        "Android returned duplicate secure-message notification identities"
    }
    val existing = active.singleOrNull { it.tag == targetTag }
    val stale = existing != null && compareNotificationOrder(
        existing = existing,
        incomingMessageDigest = incomingMessageDigest,
        incomingSentAtEpochSecond = incomingSentAtEpochSecond,
        incomingSentAtNano = incomingSentAtNano,
    ) > 0
    val otherConversations = active.filterNot { it.tag == targetTag }
    val excess = (
        otherConversations.size - (MAX_ACTIVE_SECURE_MESSAGE_NOTIFICATIONS - 1)
        ).coerceAtLeast(0)
    val evictions = otherConversations
        .sortedWith(
            compareBy<ActiveSecureMessageNotification> { it.sentAtEpochSecond }
                .thenBy { it.sentAtNano }
                .thenBy { it.messageDigest.orEmpty() }
                .thenBy { it.postedAtEpochMillis }
                .thenBy { it.tag },
        )
        .take(excess)
        .map(ActiveSecureMessageNotification::tag)

    return SecureMessageNotificationPublicationPlan(
        shouldPublish = !stale,
        onlyAlertOnce = !stale && existing?.messageDigest == incomingMessageDigest,
        tagsToCancel = evictions,
    )
}

/**
 * Exact authenticated time wins first. A digest tie-break makes equal-Instant delivery converge
 * on one preview regardless of sync/recovery order; a null digest belongs only to pre-code-23
 * notification state and yields to any authenticated current message.
 */
private fun compareNotificationOrder(
    existing: ActiveSecureMessageNotification,
    incomingMessageDigest: String,
    incomingSentAtEpochSecond: Long,
    incomingSentAtNano: Int,
): Int = compareValues(existing.sentAtEpochSecond, incomingSentAtEpochSecond)
    .takeIf { it != 0 }
    ?: compareValues(existing.sentAtNano, incomingSentAtNano)
        .takeIf { it != 0 }
    ?: compareValues(existing.messageDigest.orEmpty(), incomingMessageDigest)

internal fun secureMessageConversationNotificationTag(conversationId: String): String =
    SECURE_MESSAGE_CONVERSATION_TAG_PREFIX + secureMessageIdentifierDigest(conversationId)

/** Stores only a one-way identifier in system notification metadata and tags. */
internal fun secureMessageIdentifierDigest(identifier: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(identifier.toByteArray(StandardCharsets.UTF_8))
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"
