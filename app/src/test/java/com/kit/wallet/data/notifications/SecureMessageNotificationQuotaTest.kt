package com.kit.wallet.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageNotificationQuotaTest {
    @Test
    fun `exact replay replaces quietly while a newer message in the conversation alerts`() {
        val tag = secureMessageConversationNotificationTag(CONVERSATION_ID)
        val existing = active(tag = tag, digest = FIRST_DIGEST, sentAt = 100L)

        val replay = plan(
            active = listOf(existing),
            targetTag = tag,
            digest = FIRST_DIGEST,
            sentAt = 100L,
        )
        val newer = plan(
            active = listOf(existing),
            targetTag = tag,
            digest = SECOND_DIGEST,
            sentAt = 101L,
        )

        assertTrue(replay.shouldPublish)
        assertTrue(replay.onlyAlertOnce)
        assertTrue(replay.tagsToCancel.isEmpty())
        assertTrue(newer.shouldPublish)
        assertFalse(newer.onlyAlertOnce)
        assertTrue(newer.tagsToCancel.isEmpty())
    }

    @Test
    fun `older recovered history cannot replace the latest conversation preview`() {
        val tag = secureMessageConversationNotificationTag(CONVERSATION_ID)

        val plan = plan(
            active = listOf(active(tag, SECOND_DIGEST, sentAt = 200L)),
            targetTag = tag,
            digest = FIRST_DIGEST,
            sentAt = 100L,
        )

        assertFalse(plan.shouldPublish)
        assertFalse(plan.onlyAlertOnce)
        assertTrue(plan.tagsToCancel.isEmpty())
    }

    @Test
    fun `exact nanoseconds and digest tie break converge equal-time delivery`() {
        val tag = secureMessageConversationNotificationTag(CONVERSATION_ID)
        val earlierNano = plan(
            active = listOf(
                active(tag, FIRST_DIGEST, sentAt = 100L, sentAtNano = 2),
            ),
            targetTag = tag,
            digest = SECOND_DIGEST,
            sentAt = 100L,
            sentAtNano = 1,
        )
        val lowerDigest = minOf(FIRST_DIGEST, SECOND_DIGEST)
        val higherDigest = maxOf(FIRST_DIGEST, SECOND_DIGEST)
        val higherDigestArrival = plan(
            active = listOf(active(tag, lowerDigest, sentAt = 100L)),
            targetTag = tag,
            digest = higherDigest,
            sentAt = 100L,
        )
        val lowerDigestArrival = plan(
            active = listOf(active(tag, higherDigest, sentAt = 100L)),
            targetTag = tag,
            digest = lowerDigest,
            sentAt = 100L,
        )

        assertFalse(earlierNano.shouldPublish)
        assertTrue(higherDigestArrival.shouldPublish)
        assertFalse(lowerDigestArrival.shouldPublish)
    }

    @Test
    fun `thirty third conversation evicts the oldest and preserves call capacity`() {
        val active = (0 until MAX_ACTIVE_SECURE_MESSAGE_NOTIFICATIONS).map { index ->
            active(tag = "conversation-$index", digest = "$index", sentAt = index.toLong())
        }

        val plan = plan(
            active = active,
            targetTag = "new-conversation",
            digest = FIRST_DIGEST,
            sentAt = 100L,
        )

        assertTrue(plan.shouldPublish)
        assertEquals(listOf("conversation-0"), plan.tagsToCancel)
        assertEquals(
            ANDROID_ACTIVE_NOTIFICATION_LIMIT,
            MAX_ACTIVE_SECURE_MESSAGE_NOTIFICATIONS + RESERVED_NON_MESSAGE_NOTIFICATION_SLOTS,
        )
    }

    @Test
    fun `replacement at the cap consumes no additional slot`() {
        val target = "conversation-31"
        val active = (0 until MAX_ACTIVE_SECURE_MESSAGE_NOTIFICATIONS).map { index ->
            active(tag = "conversation-$index", digest = "$index", sentAt = index.toLong())
        }

        val plan = plan(
            active = active,
            targetTag = target,
            digest = SECOND_DIGEST,
            sentAt = 100L,
        )

        assertTrue(plan.shouldPublish)
        assertFalse(plan.onlyAlertOnce)
        assertTrue(plan.tagsToCancel.isEmpty())
    }

    @Test
    fun `overfull state evicts enough oldest conversations deterministically`() {
        val active = (0 until MAX_ACTIVE_SECURE_MESSAGE_NOTIFICATIONS + 3).map { index ->
            active(
                tag = "conversation-$index",
                digest = "$index",
                sentAt = if (index < 2) 0L else index.toLong(),
                postedAt = index.toLong(),
            )
        }

        val plan = plan(
            active = active,
            targetTag = "new-conversation",
            digest = FIRST_DIGEST,
            sentAt = 100L,
        )

        assertEquals(
            listOf("conversation-0", "conversation-1", "conversation-2", "conversation-3"),
            plan.tagsToCancel,
        )
    }

    @Test
    fun `notification identities are stable digests and never contain raw ids`() {
        val firstDigest = secureMessageIdentifierDigest(MESSAGE_ID)
        val secondDigest = secureMessageIdentifierDigest(SECOND_MESSAGE_ID)
        val tag = secureMessageConversationNotificationTag(CONVERSATION_ID)

        assertEquals(firstDigest, secureMessageIdentifierDigest(MESSAGE_ID))
        assertTrue(firstDigest.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals(firstDigest, secondDigest)
        assertTrue(tag.startsWith(SECURE_MESSAGE_CONVERSATION_TAG_PREFIX))
        assertFalse(tag.contains(CONVERSATION_ID))
        assertFalse(firstDigest.contains(MESSAGE_ID))
    }

    private fun plan(
        active: List<ActiveSecureMessageNotification>,
        targetTag: String,
        digest: String,
        sentAt: Long,
        sentAtNano: Int = 0,
    ): SecureMessageNotificationPublicationPlan = planSecureMessageNotificationPublication(
        active = active,
        targetTag = targetTag,
        incomingMessageDigest = digest,
        incomingSentAtEpochSecond = sentAt,
        incomingSentAtNano = sentAtNano,
    )

    private fun active(
        tag: String,
        digest: String?,
        sentAt: Long,
        sentAtNano: Int = 0,
        postedAt: Long = sentAt,
    ) = ActiveSecureMessageNotification(tag, digest, sentAt, sentAtNano, postedAt)

    private companion object {
        const val CONVERSATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val MESSAGE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SECOND_MESSAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val FIRST_DIGEST = secureMessageIdentifierDigest(MESSAGE_ID)
        val SECOND_DIGEST = secureMessageIdentifierDigest(SECOND_MESSAGE_ID)
    }
}
