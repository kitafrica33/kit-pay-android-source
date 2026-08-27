package com.kit.wallet

import com.kit.wallet.data.messaging.GroupPaymentAudience
import com.kit.wallet.data.messaging.GroupPaymentSplitMode
import com.kit.wallet.data.messaging.KitGroupPaymentAction
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.CreateGroupPaymentRecipient
import com.kit.wallet.data.remote.CreateGroupPaymentRequest
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.groupPaymentsAvailable
import com.kit.wallet.data.repository.GroupPaymentDraftPolicy
import com.kit.wallet.data.repository.GroupPaymentStepUpPolicy
import com.kit.wallet.feature.chat.GroupPaymentCopy
import com.kit.wallet.feature.chat.GroupPaymentSubmissionLatch
import com.kit.wallet.feature.chat.groupPaymentDescriptor
import com.kit.wallet.feature.chat.groupPaymentSubmissionIntent
import com.kit.wallet.feature.chat.projectedGroupPaymentMessages
import com.kit.wallet.feature.chat.senderNamedMessageIds
import com.kit.wallet.ui.model.GroupPaymentShareStatus
import com.kit.wallet.ui.model.GroupPaymentSummary
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything a group payment promises that is decided on the device: what the wire is allowed to
 * carry, which of those descriptors the thread is willing to believe, and how the result reads.
 *
 * The counterpart of iOS `GroupPaymentTests`, case for case and string for string, because a group
 * with members on both platforms has to read one conversation rather than two.
 */
class GroupPaymentTest {
    @Test
    fun `one composer admits one submission and completes only once`() {
        val latch = GroupPaymentSubmissionLatch("stable-key")

        assertTrue(latch.tryBegin())
        assertFalse(latch.tryBegin())
        assertEquals("stable-key", latch.idempotencyKey)
        assertTrue(latch.completeOnce())
        assertFalse(latch.completeOnce())
        assertFalse(latch.tryBegin())
    }

    @Test
    fun `a failed attempt retries with the same payment identity`() {
        val latch = GroupPaymentSubmissionLatch("same-request")

        assertTrue(latch.tryBegin())
        latch.releaseForRetry()

        assertTrue(latch.tryBegin())
        assertEquals("same-request", latch.idempotencyKey)
        assertTrue(latch.completeOnce())
    }

    @Test
    fun `equivalent composer text keeps one canonical payment identity`() {
        val selected = listOf(GroupPaymentDraftPolicy.Member(ama, "Ama"))

        val first = groupPaymentSubmissionIntent(
            GroupPaymentSplitMode.EVEN,
            GroupPaymentAudience.SELECTED,
            selected,
            "100.00",
            emptyMap(),
            "  Lunch  ",
        )
        val retry = groupPaymentSubmissionIntent(
            GroupPaymentSplitMode.EVEN,
            GroupPaymentAudience.SELECTED,
            selected,
            "1E2",
            emptyMap(),
            "Lunch",
        )

        assertEquals(first, retry)
        assertNotEquals(
            first,
            groupPaymentSubmissionIntent(
                GroupPaymentSplitMode.EVEN,
                GroupPaymentAudience.SELECTED,
                selected,
                "101",
                emptyMap(),
                "Lunch",
            ),
        )
    }

    @Test
    fun `everyone intent ignores a stale local roster but custom amounts remain body bound`() {
        val amaMember = GroupPaymentDraftPolicy.Member(ama, "Ama")
        val benMember = GroupPaymentDraftPolicy.Member(ben, "Ben")
        val everyone = groupPaymentSubmissionIntent(
            GroupPaymentSplitMode.EVEN,
            GroupPaymentAudience.ALL,
            listOf(amaMember),
            "500",
            emptyMap(),
            null,
        )
        assertEquals(
            everyone,
            groupPaymentSubmissionIntent(
                GroupPaymentSplitMode.EVEN,
                GroupPaymentAudience.ALL,
                listOf(amaMember, benMember),
                "500",
                emptyMap(),
                null,
            ),
        )

        val custom = groupPaymentSubmissionIntent(
            GroupPaymentSplitMode.CUSTOM,
            GroupPaymentAudience.SELECTED,
            listOf(amaMember),
            "ignored",
            mapOf(ama to "250"),
            null,
        )
        assertNotEquals(
            custom,
            groupPaymentSubmissionIntent(
                GroupPaymentSplitMode.CUSTOM,
                GroupPaymentAudience.SELECTED,
                listOf(amaMember),
                "also ignored",
                mapOf(ama to "251"),
                null,
            ),
        )
    }

    private val sender = "10000000-0000-4000-8000-000000000001"
    private val ama = "10000000-0000-4000-8000-000000000002"
    private val ben = "10000000-0000-4000-8000-000000000003"
    private val cara = "10000000-0000-4000-8000-000000000004"
    private val paymentId = "60000000-0000-4000-8000-000000000001"
    private val conversationId = "20000000-0000-4000-8000-000000000001"

    // MARK: - Wire descriptor

    @Test
    fun `an even split announcement round trips through its canonical encoding`() {
        val descriptor = KitGroupPaymentMessage.create(
            action = KitGroupPaymentAction.SENT,
            groupPaymentId = paymentId,
            splitMode = GroupPaymentSplitMode.EVEN,
            audience = GroupPaymentAudience.SELECTED,
            recipientCount = 2,
            currencyCode = "UGX",
            currencyScale = 0,
            totalAmountMinor = 30_000,
            note = "Lunch & taxi",
            recipientUserIds = listOf(ama.uppercase(), ben),
        )!!

        assertTrue(descriptor.encode().startsWith(KitGroupPaymentMessage.PREFIX))
        assertEquals(listOf(ama, ben), descriptor.recipientUserIds)
        assertEquals(descriptor, KitGroupPaymentMessage.parse(descriptor.encode()))
        assertEquals(15_000L, descriptor.evenShareMinor)
        assertTrue(descriptor.dividesEvenly)
    }

    @Test
    fun `a custom split never carries the pot and only ever names chosen members`() {
        assertNull(
            "a custom split that carried its total would put every other member's share one " +
                "subtraction away",
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = paymentId,
                splitMode = GroupPaymentSplitMode.CUSTOM,
                audience = GroupPaymentAudience.SELECTED,
                recipientCount = 2,
                currencyCode = "UGX",
                currencyScale = 0,
                totalAmountMinor = 30_000,
            ),
        )
        assertNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = paymentId,
                splitMode = GroupPaymentSplitMode.CUSTOM,
                audience = GroupPaymentAudience.ALL,
                recipientCount = 2,
                currencyCode = "UGX",
                currencyScale = 0,
            ),
        )
        assertNotNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = paymentId,
                splitMode = GroupPaymentSplitMode.CUSTOM,
                audience = GroupPaymentAudience.SELECTED,
                recipientCount = 2,
                currencyCode = "UGX",
                currencyScale = 0,
            ),
        )
    }

    @Test
    fun `an even split requires a pot and a real currency`() {
        assertNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = paymentId,
                splitMode = GroupPaymentSplitMode.EVEN,
                audience = GroupPaymentAudience.ALL,
                recipientCount = 3,
                currencyCode = "UGX",
                currencyScale = 0,
                totalAmountMinor = null,
            ),
        )
        assertNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = paymentId,
                splitMode = GroupPaymentSplitMode.EVEN,
                audience = GroupPaymentAudience.ALL,
                recipientCount = 3,
                currencyCode = "ugx",
                currencyScale = 0,
                totalAmountMinor = 300,
            ),
        )
        assertNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = "not-a-uuid",
                splitMode = GroupPaymentSplitMode.EVEN,
                audience = GroupPaymentAudience.ALL,
                recipientCount = 3,
                currencyCode = "UGX",
                currencyScale = 0,
                totalAmountMinor = 300,
            ),
        )
    }

    @Test
    fun `an announcement refuses a roster that disagrees with the count`() {
        assertNull(
            "naming two of three members reads as the whole list",
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = paymentId,
                splitMode = GroupPaymentSplitMode.EVEN,
                audience = GroupPaymentAudience.SELECTED,
                recipientCount = 3,
                currencyCode = "UGX",
                currencyScale = 0,
                totalAmountMinor = 300,
                recipientUserIds = listOf(ama, ben),
            ),
        )
        assertNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.SENT,
                groupPaymentId = paymentId,
                splitMode = GroupPaymentSplitMode.EVEN,
                audience = GroupPaymentAudience.SELECTED,
                recipientCount = 2,
                currencyCode = "UGX",
                currencyScale = 0,
                totalAmountMinor = 300,
                recipientUserIds = listOf(ama, ama),
            ),
        )
    }

    @Test
    fun `an outcome descriptor carries nothing but who did what`() {
        assertNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.ACCEPTED,
                groupPaymentId = paymentId,
                currencyCode = "UGX",
                currencyScale = 0,
                totalAmountMinor = 15_000,
            ),
        )
        assertNull(
            KitGroupPaymentMessage.create(
                action = KitGroupPaymentAction.REJECTED,
                groupPaymentId = paymentId,
                note = "not today",
            ),
        )
        assertNull(KitGroupPaymentMessage.outcome(KitGroupPaymentAction.SENT, paymentId))

        val accepted = KitGroupPaymentMessage.outcome(KitGroupPaymentAction.ACCEPTED, paymentId)
        assertEquals("KITGRP1:v=1&a=accepted&id=$paymentId", accepted?.encode())
        assertEquals(accepted, KitGroupPaymentMessage.parse(accepted?.encode().orEmpty()))
    }

    @Test
    fun `parse rejects anything that is not the canonical encoding`() {
        assertNull(KitGroupPaymentMessage.parse("KITGRP1:a=accepted&v=1&id=$paymentId"))
        assertNull(KitGroupPaymentMessage.parse("KITGRP1:v=1&a=accepted&id=$paymentId&x=1"))
        assertNull(
            KitGroupPaymentMessage.parse("KITGRP1:v=1&a=accepted&id=$paymentId&id=$paymentId"),
        )
        assertNull(KitGroupPaymentMessage.parse("KITGRP1:v=2&a=accepted&id=$paymentId"))
        assertNull(KitGroupPaymentMessage.parse("KITPAY1:v=1&a=accepted&id=$paymentId"))
        assertFalse(KitGroupPaymentMessage.isGroupPaymentText("Sent 30,000 to the group"))
    }

    @Test
    fun `the group payment wire is reserved against user authored text`() {
        assertTrue(
            KitGroupPaymentMessage.beginsWithReservedPrefix(
                "   KITGRP1:v=1&a=accepted&id=$paymentId",
            ),
        )
        assertFalse(KitGroupPaymentMessage.beginsWithReservedPrefix("I sent it to the group"))
    }

    @Test
    fun `announcing a confirmed payment mirrors what the server disclosed`() {
        val even = payment(splitMode = "even", audience = "all")
        val announcement =
            KitGroupPaymentMessage.announcing(even, listOf(ama, ben, cara))!!
        assertEquals(30_000L, announcement.totalAmountMinor)
        assertEquals(listOf(ama, ben, cara), announcement.recipientUserIds)
        assertTrue(announcement.matchesAuthoritativePayment(even))

        val custom = payment(splitMode = "custom", audience = "selected")
        val customAnnouncement =
            KitGroupPaymentMessage.announcing(custom, listOf(ama, ben, cara))!!
        assertNull(
            "the sender's own view of the pot must not be re-broadcast to the group",
            customAnnouncement.totalAmountMinor,
        )

        // A roster the server disagrees with is dropped rather than published.
        val mismatched = KitGroupPaymentMessage.announcing(even, listOf(ama))!!
        assertTrue(mismatched.recipientUserIds.isEmpty())
        assertEquals(3, mismatched.recipientCount)
    }

    @Test
    fun `matchesAuthoritativePayment rejects a descriptor that overstates the pot`() {
        val even = payment(splitMode = "even", audience = "all")
        val inflated = KitGroupPaymentMessage.create(
            action = KitGroupPaymentAction.SENT,
            groupPaymentId = even.id,
            splitMode = GroupPaymentSplitMode.EVEN,
            audience = GroupPaymentAudience.ALL,
            recipientCount = 3,
            currencyCode = "UGX",
            currencyScale = 0,
            totalAmountMinor = 300_000,
        )!!
        assertFalse(inflated.matchesAuthoritativePayment(even))
    }

    @Test
    fun `the outcome message id is stable per payment action and author`() {
        val first = KitGroupPaymentMessage.outcomeMessageId(
            groupPaymentId = paymentId.uppercase(),
            action = KitGroupPaymentAction.ACCEPTED,
            actorUserId = ama,
        )
        assertEquals(
            first,
            KitGroupPaymentMessage.outcomeMessageId(
                groupPaymentId = paymentId,
                action = KitGroupPaymentAction.ACCEPTED,
                actorUserId = ama.uppercase(),
            ),
        )
        assertNotEquals(
            first,
            KitGroupPaymentMessage.outcomeMessageId(
                groupPaymentId = paymentId,
                action = KitGroupPaymentAction.REJECTED,
                actorUserId = ama,
            ),
        )
        assertNotEquals(
            first,
            KitGroupPaymentMessage.outcomeMessageId(
                groupPaymentId = paymentId,
                action = KitGroupPaymentAction.ACCEPTED,
                actorUserId = ben,
            ),
        )
    }

    // MARK: - Timeline projection

    @Test
    fun `a group thread projects an announcement and its recipients answers`() {
        val projected = projectedGroupPaymentMessages(
            listOf(
                message(sender, announcement()),
                message(ama, outcome(KitGroupPaymentAction.ACCEPTED)),
                message(ben, outcome(KitGroupPaymentAction.REJECTED)),
                message(sender, outcome(KitGroupPaymentAction.RETURNED)),
            ),
            isGroup = true,
        )

        assertEquals(4, projected.size)
        assertEquals(MessageKind.GROUP_PAYMENT, projected[0].kind)
        assertEquals(paymentId, projected[0].groupPaymentDescriptor()?.groupPaymentId)
        assertEquals(
            listOf(
                KitGroupPaymentAction.ACCEPTED,
                KitGroupPaymentAction.REJECTED,
                KitGroupPaymentAction.RETURNED,
            ),
            projected.drop(1).map { it.groupPaymentDescriptor()?.action },
        )
    }

    @Test
    fun `an outcome without its announcement renders nothing`() {
        val projected = projectedGroupPaymentMessages(
            listOf(message(ama, outcome(KitGroupPaymentAction.ACCEPTED))),
            isGroup = true,
        )
        assertTrue(
            "a claim about a payment this thread never saw is not shown at all — not even as raw " +
                "text",
            projected.isEmpty(),
        )
    }

    @Test
    fun `the thread refuses forged answers about somebody elses payment`() {
        val projected = projectedGroupPaymentMessages(
            listOf(
                message(sender, announcement(recipients = listOf(ama, ben))),
                // The sender cannot accept their own payment...
                message(sender, outcome(KitGroupPaymentAction.ACCEPTED)),
                // ...a member who was not paid cannot answer for one who was...
                message(cara, outcome(KitGroupPaymentAction.ACCEPTED)),
                // ...and nobody but the sender can return the unclaimed shares.
                message(ama, outcome(KitGroupPaymentAction.RETURNED)),
            ),
            isGroup = true,
        )

        assertEquals(0, projected.count { it.kind == MessageKind.GROUP_PAYMENT_EVENT })
        assertEquals(1, projected.count { it.kind == MessageKind.GROUP_PAYMENT })
    }

    @Test
    fun `replayed announcements and doubled answers are dropped`() {
        val projected = projectedGroupPaymentMessages(
            listOf(
                message(sender, announcement(recipients = listOf(ama, ben))),
                message(sender, announcement(recipients = listOf(ama, ben))),
                message(ama, outcome(KitGroupPaymentAction.ACCEPTED)),
                message(ama, outcome(KitGroupPaymentAction.REJECTED)),
            ),
            isGroup = true,
        )

        assertEquals(1, projected.count { it.kind == MessageKind.GROUP_PAYMENT })
        assertEquals(
            "a member answers their own share once; the second word is a replay",
            1,
            projected.count { it.kind == MessageKind.GROUP_PAYMENT_EVENT },
        )
    }

    @Test
    fun `group payment wire in a direct thread renders nothing`() {
        val chatter = message(ama, "did you send it?")
        val projected = projectedGroupPaymentMessages(
            listOf(message(sender, announcement()), chatter),
            isGroup = false,
        )
        assertEquals(
            "money sent to a group is claimed in that group, so there is nothing truthful to draw",
            listOf(chatter),
            projected,
        )
    }

    // MARK: - Sender-name runs

    @Test
    fun `a senders name heads their run and not every line of it`() {
        val first = message(ama, "one")
        val second = message(ama, "two")
        val third = message(ben, "three")
        val fourth = message(ama, "four")

        val named = senderNamedMessageIds(
            listOf(first, second, third, fourth),
            isGroup = true,
            zone = UTC,
        )

        assertEquals(setOf(first.id, third.id, fourth.id), named)
    }

    @Test
    fun `names return after a day break a card or the account holders own message`() {
        val first = message(ama, "one", epochMillis = DAY_ONE)
        val afterBreak = message(ama, "two", epochMillis = DAY_TWO)
        val mine = message(sender, "mine", outgoing = true, epochMillis = DAY_TWO + 1_000)
        val afterOwn = message(ama, "three", epochMillis = DAY_TWO + 2_000)
        val card = message(sender, announcement(), epochMillis = DAY_TWO + 3_000)
        val afterCard = message(ama, "four", epochMillis = DAY_TWO + 4_000)

        val named = senderNamedMessageIds(
            listOf(first, afterBreak, mine, afterOwn, card, afterCard),
            isGroup = true,
            zone = UTC,
        )

        assertEquals(setOf(first.id, afterBreak.id, afterOwn.id, afterCard.id), named)
        assertFalse("your own bubbles never carry your name", mine.id in named)
    }

    @Test
    fun `rows that render nothing do not break a run`() {
        val first = message(ama, "one")
        // An answer to a payment this thread never announced. The projection drops it, so it is
        // not a bubble, not a card, and not an interruption either.
        val orphan = message(ben, outcome(KitGroupPaymentAction.ACCEPTED))
        val second = message(ama, "two")
        val thread = listOf(first, orphan, second)

        val named = senderNamedMessageIds(
            projectedGroupPaymentMessages(thread, isGroup = true),
            isGroup = true,
            zone = UTC,
        )
        assertEquals(setOf(first.id), named)

        // Naming has to run on what the thread will actually draw: over the unprojected list the
        // dropped row splits Ama's run in two and her name comes back for no visible reason.
        assertTrue(second.id in senderNamedMessageIds(thread, isGroup = true, zone = UTC))
    }

    @Test
    fun `one to one threads never name their sender`() {
        assertTrue(
            senderNamedMessageIds(listOf(message(ama, "one")), isGroup = false, zone = UTC)
                .isEmpty(),
        )
    }

    // MARK: - Copy

    @Test
    fun `name lists stay short and count the rest`() {
        assertEquals("Ama", GroupPaymentCopy.nameList(listOf("Ama")))
        assertEquals("Ama and Ben", GroupPaymentCopy.nameList(listOf("Ama", "Ben")))
        assertEquals("Ama, Ben and Cara", GroupPaymentCopy.nameList(listOf("Ama", "Ben", "Cara")))
        assertEquals(
            "Ama, Ben, Cara and 1 other",
            GroupPaymentCopy.nameList(listOf("Ama", "Ben", "Cara", "Dan"), totalCount = 4),
        )
        assertEquals(
            "Ama, Ben, Cara and 4 others",
            GroupPaymentCopy.nameList(listOf("Ama", "Ben", "Cara"), totalCount = 7),
        )
        assertNull(GroupPaymentCopy.nameList(listOf(" ", "")))
    }

    @Test
    fun `announcements say who was paid and only disclose what the group may know`() {
        val everyone =
            KitGroupPaymentMessage.parse(announcement(audience = GroupPaymentAudience.ALL))!!
        assertEquals(
            "Ama sent UGX 30,000 to everyone",
            GroupPaymentCopy.announcement(
                descriptor = everyone,
                senderName = "Ama",
                isViewerSender = false,
                recipientNames = emptyList(),
            ),
        )
        assertEquals(
            "You sent UGX 30,000 to everyone",
            GroupPaymentCopy.announcement(
                descriptor = everyone,
                senderName = "Ama",
                isViewerSender = true,
                recipientNames = emptyList(),
            ),
        )

        val some =
            KitGroupPaymentMessage.parse(announcement(recipients = listOf(ama, ben)))!!
        assertEquals(
            "Ama sent UGX 30,000 to Ben and Cara",
            GroupPaymentCopy.announcement(
                descriptor = some,
                senderName = "Ama",
                isViewerSender = false,
                recipientNames = listOf("Ben", "Cara"),
            ),
        )

        val custom = KitGroupPaymentMessage.create(
            action = KitGroupPaymentAction.SENT,
            groupPaymentId = paymentId,
            splitMode = GroupPaymentSplitMode.CUSTOM,
            audience = GroupPaymentAudience.SELECTED,
            recipientCount = 2,
            currencyCode = "UGX",
            currencyScale = 0,
            recipientUserIds = listOf(ama, ben),
        )!!
        assertEquals(
            "Ama sent payments to Ben and Cara",
            GroupPaymentCopy.announcement(
                descriptor = custom,
                senderName = "Ama",
                isViewerSender = false,
                recipientNames = listOf("Ben", "Cara"),
            ),
        )
        assertEquals(
            "the sender may see the pot they themselves sent",
            "You sent UGX 30,000 to Ben and Cara",
            GroupPaymentCopy.announcement(
                descriptor = custom,
                senderName = "Ama",
                isViewerSender = true,
                recipientNames = listOf("Ben", "Cara"),
                totalOverride = 30_000,
            ),
        )
    }

    @Test
    fun `an unnamed roster falls back to counting members`() {
        val some = KitGroupPaymentMessage.parse(announcement(recipients = emptyList()))!!
        assertEquals(
            "Ama sent UGX 30,000 to 3 members",
            GroupPaymentCopy.announcement(
                descriptor = some,
                senderName = "Ama",
                isViewerSender = false,
                recipientNames = emptyList(),
            ),
        )
    }

    @Test
    fun `the even share subtitle only quotes an exact figure when it divides exactly`() {
        val exact =
            KitGroupPaymentMessage.parse(announcement(audience = GroupPaymentAudience.ALL))!!
        assertEquals("UGX 10,000 each", GroupPaymentCopy.evenShareSubtitle(exact))

        val inexact = KitGroupPaymentMessage.create(
            action = KitGroupPaymentAction.SENT,
            groupPaymentId = paymentId,
            splitMode = GroupPaymentSplitMode.EVEN,
            audience = GroupPaymentAudience.ALL,
            recipientCount = 3,
            currencyCode = "UGX",
            currencyScale = 0,
            totalAmountMinor = 30_001,
        )!!
        assertEquals("About UGX 10,000 each", GroupPaymentCopy.evenShareSubtitle(inexact))
    }

    @Test
    fun `outcome lines only speak for their author`() {
        assertEquals(
            "Ama took their share",
            GroupPaymentCopy.outcome(KitGroupPaymentAction.ACCEPTED, "Ama", isViewerActor = false),
        )
        assertEquals(
            "Ama declined their share",
            GroupPaymentCopy.outcome(KitGroupPaymentAction.REJECTED, "Ama", isViewerActor = false),
        )
        assertEquals(
            "You returned the unclaimed shares",
            GroupPaymentCopy.outcome(KitGroupPaymentAction.RETURNED, "Ama", isViewerActor = true),
        )
        assertNull(
            GroupPaymentCopy.outcome(KitGroupPaymentAction.SENT, "Ama", isViewerActor = false),
        )
    }

    @Test
    fun `sender progress is counts and never amounts`() {
        val waiting = payment(
            splitMode = "even",
            audience = "all",
            pendingCount = 2,
            acceptedCount = 1,
        )
        assertEquals("1 of 3 taken, 2 waiting", GroupPaymentCopy.progress(waiting))

        val done = payment(
            splitMode = "even",
            audience = "all",
            pendingCount = 0,
            acceptedCount = 3,
        )
        assertEquals("All 3 shares taken", GroupPaymentCopy.progress(done))

        val partlyReturned = payment(
            splitMode = "even",
            audience = "all",
            pendingCount = 0,
            acceptedCount = 2,
            returnedCount = 1,
        )
        assertEquals("2 of 3 taken, 1 returned", GroupPaymentCopy.progress(partlyReturned))
    }

    @Test
    fun `a members own card says where their share stands and nobody elses`() {
        assertEquals(
            "Waiting for you",
            GroupPaymentCopy.shareStatus(GroupPaymentShareStatus.PENDING),
        )
        assertEquals(
            "In your wallet",
            GroupPaymentCopy.shareStatus(GroupPaymentShareStatus.ACCEPTED),
        )
        assertEquals("Waiting", GroupPaymentCopy.recipientStatus(GroupPaymentShareStatus.PENDING))
        assertEquals("Taken", GroupPaymentCopy.recipientStatus(GroupPaymentShareStatus.ACCEPTED))
    }

    // MARK: - Composing a send

    @Test
    fun `an even split draft sends the pot and lets the server resolve everyone`() {
        val outcome = GroupPaymentDraftPolicy.draft(
            sourceWalletId = "wallet-1",
            splitMode = GroupPaymentSplitMode.EVEN,
            audience = GroupPaymentAudience.ALL,
            selected = members(),
            totalInput = "30000",
            customAmounts = emptyMap(),
            note = "  Lunch  ",
            scale = 0,
            availableBalanceMinor = 50_000,
        )
        val body = (outcome as GroupPaymentDraftPolicy.Outcome.Ready).request
        assertEquals("30000", body.totalAmount)
        assertEquals("Lunch", body.note)
        assertNull("the roster the server holds at send time is the true one", body.recipients)
    }

    @Test
    fun `a draft is refused before anyone is asked to approve it`() {
        val selected = members()
        assertEquals(
            "Choose at least one member to pay.",
            problem(
                GroupPaymentDraftPolicy.draft(
                    sourceWalletId = "wallet-1",
                    splitMode = GroupPaymentSplitMode.EVEN,
                    audience = GroupPaymentAudience.SELECTED,
                    selected = emptyList(),
                    totalInput = "30000",
                    customAmounts = emptyMap(),
                    note = null,
                    scale = 0,
                    availableBalanceMinor = 50_000,
                ),
            ),
        )
        assertNotNull(
            "two whole units cannot be divided between three members",
            problem(
                GroupPaymentDraftPolicy.draft(
                    sourceWalletId = "wallet-1",
                    splitMode = GroupPaymentSplitMode.EVEN,
                    audience = GroupPaymentAudience.SELECTED,
                    selected = selected,
                    totalInput = "2",
                    customAmounts = emptyMap(),
                    note = null,
                    scale = 0,
                    availableBalanceMinor = 50_000,
                ),
            ),
        )
        assertEquals(
            "Your wallet does not have that much available.",
            problem(
                GroupPaymentDraftPolicy.draft(
                    sourceWalletId = "wallet-1",
                    splitMode = GroupPaymentSplitMode.EVEN,
                    audience = GroupPaymentAudience.SELECTED,
                    selected = selected,
                    totalInput = "90000",
                    customAmounts = emptyMap(),
                    note = null,
                    scale = 0,
                    availableBalanceMinor = 50_000,
                ),
            ),
        )
        assertNotNull(
            "different amounts each means choosing the members by hand",
            problem(
                GroupPaymentDraftPolicy.draft(
                    sourceWalletId = "wallet-1",
                    splitMode = GroupPaymentSplitMode.CUSTOM,
                    audience = GroupPaymentAudience.ALL,
                    selected = selected,
                    totalInput = "",
                    customAmounts = mapOf(ama to "1000", ben to "2000", cara to "3000"),
                    note = null,
                    scale = 0,
                    availableBalanceMinor = 50_000,
                ),
            ),
        )
        assertNotNull(
            "a member with no amount is a member who would be paid nothing",
            problem(
                GroupPaymentDraftPolicy.draft(
                    sourceWalletId = "wallet-1",
                    splitMode = GroupPaymentSplitMode.CUSTOM,
                    audience = GroupPaymentAudience.SELECTED,
                    selected = selected,
                    totalInput = "",
                    customAmounts = mapOf(ama to "1000", ben to "2000"),
                    note = null,
                    scale = 0,
                    availableBalanceMinor = 50_000,
                ),
            ),
        )
    }

    @Test
    fun `a custom split draft carries every amount and no pot`() {
        val amounts = mapOf(ama to "1000", ben to "2000", cara to "3000")
        val outcome = GroupPaymentDraftPolicy.draft(
            sourceWalletId = "wallet-1",
            splitMode = GroupPaymentSplitMode.CUSTOM,
            audience = GroupPaymentAudience.SELECTED,
            selected = members(),
            totalInput = "",
            customAmounts = amounts,
            note = null,
            scale = 0,
            availableBalanceMinor = 50_000,
        )
        val body = (outcome as GroupPaymentDraftPolicy.Outcome.Ready).request
        assertNull(body.totalAmount)
        assertEquals(
            listOf(
                CreateGroupPaymentRecipient(ama, "1000"),
                CreateGroupPaymentRecipient(ben, "2000"),
                CreateGroupPaymentRecipient(cara, "3000"),
            ),
            body.recipients,
        )
        assertEquals(
            6_000L,
            GroupPaymentDraftPolicy.totalMinor(
                splitMode = GroupPaymentSplitMode.CUSTOM,
                selected = members(),
                totalInput = "",
                customAmounts = amounts,
                scale = 0,
            ),
        )
    }

    // MARK: - Step-up binding

    @Test
    fun `the approved intent pins the split the members and their amounts`() {
        val request = CreateGroupPaymentRequest(
            sourceWalletId = "wallet-1",
            splitMode = "custom",
            audience = "selected",
            totalAmount = null,
            note = "Lunch",
            recipients = listOf(
                CreateGroupPaymentRecipient(ama, "1000"),
                CreateGroupPaymentRecipient(ben, "2000"),
            ),
        )
        val intent = GroupPaymentStepUpPolicy.sendIntent(request, conversationId)

        assertEquals(conversationId, intent["conversation_id"])
        assertEquals("wallet-1", intent["source_wallet_id"])
        assertEquals("custom", intent["split_mode"])
        assertEquals("selected", intent["audience"])
        assertNull(intent["total_amount"])
        assertEquals("Lunch", intent["note"])
        assertEquals(
            "approving three shares of one pot must not be replayable as three whole pots",
            "$ama:1000,$ben:2000",
            intent["recipients"],
        )

        val evenIntent = GroupPaymentStepUpPolicy.sendIntent(
            CreateGroupPaymentRequest(
                sourceWalletId = "wallet-1",
                splitMode = "even",
                audience = "all",
                totalAmount = "30000",
                note = null,
                recipients = null,
            ),
            conversationId,
        )
        assertTrue(evenIntent.containsKey("recipients"))
        assertNull(
            "an all-members send must survive Laravel's empty-string-to-null normalization",
            evenIntent["recipients"],
        )
        assertEquals("30000", evenIntent["total_amount"])

        val reverse = GroupPaymentStepUpPolicy.reverseIntent(paymentId, reason = null)
        assertEquals(paymentId, reverse["group_payment_id"])
        assertNull(reverse["reason"])
        assertEquals("group_payment", GroupPaymentStepUpPolicy.SEND_PURPOSE)
        assertEquals("group_payment_reverse", GroupPaymentStepUpPolicy.REVERSE_PURPOSE)
    }

    // MARK: - Capability gate

    @Test
    fun `group payments need claimable transfers as well as their own flag`() {
        assertFalse(capabilities(null).groupPaymentsAvailable())
        assertFalse(
            capabilities(
                mapOf(
                    "wallets" to true,
                    "internal_transfers" to true,
                    "claimable_transfers" to false,
                    "group_payments" to true,
                ),
            ).groupPaymentsAvailable(),
        )
        assertFalse(
            capabilities(
                mapOf(
                    "wallets" to true,
                    "internal_transfers" to true,
                    "claimable_transfers" to true,
                    "group_payments" to false,
                ),
            ).groupPaymentsAvailable(),
        )
        assertTrue(
            capabilities(
                mapOf(
                    "wallets" to true,
                    "internal_transfers" to true,
                    "claimable_transfers" to true,
                    "group_payments" to true,
                ),
            ).groupPaymentsAvailable(),
        )
    }

    // MARK: - Helpers

    private fun members() = listOf(
        GroupPaymentDraftPolicy.Member(userId = ama, name = "Ama"),
        GroupPaymentDraftPolicy.Member(userId = ben, name = "Ben"),
        GroupPaymentDraftPolicy.Member(userId = cara, name = "Cara"),
    )

    private fun problem(outcome: GroupPaymentDraftPolicy.Outcome): String? =
        (outcome as? GroupPaymentDraftPolicy.Outcome.Problem)?.message

    private fun capabilities(features: Map<String, Boolean?>?) = CapabilitiesDto(
        currency = CurrencyDto("UGX", "0"),
        features = features,
    )

    private fun payment(
        splitMode: String,
        audience: String,
        recipientCount: Int = 3,
        pendingCount: Int = 3,
        acceptedCount: Int = 0,
        returnedCount: Int = 0,
    ) = GroupPaymentSummary(
        id = paymentId,
        conversationId = conversationId,
        splitMode = splitMode,
        audience = audience,
        currencyCode = "UGX",
        currencyScale = 0,
        recipientCount = recipientCount,
        totalAmountMinor = 30_000,
        senderUserId = sender,
        pendingCount = pendingCount,
        acceptedCount = acceptedCount,
        returnedCount = returnedCount,
    )

    /**
     * An even-split announcement of UGX 30,000. The stated count always matches the roster, since
     * the descriptor refuses to name a subset of the members it claims were paid.
     */
    private fun announcement(
        audience: GroupPaymentAudience = GroupPaymentAudience.SELECTED,
        recipients: List<String>? = null,
    ): String {
        val roster = if (audience == GroupPaymentAudience.ALL) {
            emptyList()
        } else {
            recipients ?: listOf(ama, ben, cara)
        }
        return KitGroupPaymentMessage.create(
            action = KitGroupPaymentAction.SENT,
            groupPaymentId = paymentId,
            splitMode = GroupPaymentSplitMode.EVEN,
            audience = audience,
            recipientCount = if (roster.isEmpty()) 3 else roster.size,
            currencyCode = "UGX",
            currencyScale = 0,
            totalAmountMinor = 30_000,
            recipientUserIds = roster,
        )!!.encode()
    }

    private fun outcome(action: KitGroupPaymentAction): String =
        KitGroupPaymentMessage.outcome(action, paymentId)!!.encode()

    private var clock = 0L

    /**
     * A thread entry. Group-payment wire is projected into its own kind exactly as the repository
     * does it, so these fixtures reach the policies in the shape the app really hands them.
     */
    private fun message(
        senderUserId: String,
        body: String,
        outgoing: Boolean = false,
        epochMillis: Long? = null,
    ): Message {
        clock += 1_000
        val descriptor = KitGroupPaymentMessage.parse(body)
        return Message(
            id = "message-$clock",
            text = if (descriptor == null) body else "",
            time = "09:00",
            fromMe = outgoing,
            kind = when (descriptor?.action) {
                null -> MessageKind.TEXT
                KitGroupPaymentAction.SENT -> MessageKind.GROUP_PAYMENT
                else -> MessageKind.GROUP_PAYMENT_EVENT
            },
            mediaDescriptor = descriptor?.encode(),
            groupPaymentId = descriptor?.groupPaymentId,
            senderUserId = senderUserId,
            sortEpochMillis = epochMillis ?: (DAY_ONE + clock),
        )
    }

    private companion object {
        /** Fixed so a day break in a test is a day break everywhere the test runs. */
        val UTC: ZoneId = ZoneId.of("UTC")
        const val DAY_ONE = 86_400_000L
        const val DAY_TWO = 172_800_000L
    }
}
