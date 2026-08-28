package com.kit.wallet

import com.kit.wallet.data.notifications.PaymentClaimLink
import com.kit.wallet.navigation.PaymentClaimNavigationTarget
import com.kit.wallet.navigation.paymentClaimDestination
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentClaimDestinationTest {

    private val claimId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val groupId = "0e5a9c3d-7a4b-4a4e-8a2f-6d3b1c9e7f21"
    private val groupPaymentId = "9c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f"
    private val sender = "5b0e7d7c-1f2a-4b3c-8d4e-9f0a1b2c3d4e"
    private val recipient = "7a1b2c3d-4e5f-4671-8293-a4b5c6d7e8f9"
    private val bystander = "1c2d3e4f-5a6b-4c8d-9e0f-a1b2c3d4e5f6"

    /** The floor for everything: wallet activity named by the tapped claim, exactly. */
    private val walletActivity = PaymentClaimNavigationTarget.WalletHistory(claimId)

    private fun claim(
        id: String = claimId,
        senderUserId: String? = sender,
        recipientUserId: String? = recipient,
        transactionId: String = "tx-77",
    ) = TransferClaim(
        id = id,
        transactionId = transactionId,
        status = TransferClaimStatus.PENDING,
        amountMinor = 5_000,
        senderUserId = senderUserId,
        recipientUserId = recipientUserId,
    )

    private fun chat(
        id: String,
        peerUserId: String? = null,
        isGroup: Boolean = false,
    ) = ChatPreview(
        id = id,
        name = id,
        lastMessage = "",
        time = "10:00",
        peerUserId = peerUserId,
        isGroup = isGroup,
    )

    private fun transferCard(id: String, referenceId: String?) = Message(
        id = id,
        text = "",
        time = "10:00",
        fromMe = false,
        kind = MessageKind.PAYMENT_TRANSFER,
        paymentReferenceId = referenceId,
    )

    private fun announcementCard(id: String, groupPaymentId: String?) = Message(
        id = id,
        text = "",
        time = "10:00",
        fromMe = false,
        kind = MessageKind.GROUP_PAYMENT,
        groupPaymentId = groupPaymentId,
    )

    private fun destination(
        link: PaymentClaimLink = PaymentClaimLink(claimId),
        claim: TransferClaim = claim(),
        currentUserId: String? = recipient,
        chats: List<ChatPreview> = emptyList(),
        rosters: Map<String, List<ChatMember>> = emptyMap(),
        messages: Map<String, List<Message>> = emptyMap(),
    ) = paymentClaimDestination(
        link = link,
        claim = claim,
        currentUserId = currentUserId,
        chats = chats,
        rosterFor = { rosters[it].orEmpty() },
        messagesFor = { messages[it].orEmpty() },
    )

    private fun groupLink(groupPaymentId: String? = null) =
        PaymentClaimLink(claimId, conversationId = groupId, groupPaymentId = groupPaymentId)

    private val fullRoster = listOf(member(recipient), member(sender), member(bystander))

    @Test
    fun `a claim echoing anything but the exact canonical id lands on its wallet activity`() {
        assertEquals(walletActivity, destination(claim = claim(id = groupPaymentId)))
        // The fallback names the tapped claim, never the claim's transaction or echoed id.
        assertEquals(
            walletActivity,
            destination(claim = claim(id = groupPaymentId, transactionId = "tx-going-nowhere")),
        )
        // A case-variant echo is the same canonical claim, not a contradiction.
        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = null),
            destination(
                link = groupLink(),
                claim = claim(id = claimId.uppercase()),
                chats = listOf(chat(groupId, isGroup = true)),
                rosters = mapOf(groupId to fullRoster),
            ),
        )
    }

    @Test
    fun `parties must be canonical uuids, distinct, and include this account`() {
        assertEquals(walletActivity, destination(claim = claim(senderUserId = null)))
        assertEquals(walletActivity, destination(claim = claim(recipientUserId = "  ")))
        assertEquals(
            walletActivity,
            destination(claim = claim(recipientUserId = "user-recipient")),
        )
        assertEquals(walletActivity, destination(claim = claim(recipientUserId = " $recipient ")))
        assertEquals(
            walletActivity,
            destination(claim = claim(recipientUserId = sender.uppercase())),
        )
        assertEquals(walletActivity, destination(currentUserId = bystander))
        assertEquals(walletActivity, destination(currentUserId = null))
        assertEquals(walletActivity, destination(currentUserId = "user-recipient"))
    }

    @Test
    fun `party ids compare canonically, so case variants still authorize`() {
        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = null),
            destination(
                link = groupLink(),
                claim = claim(senderUserId = sender.uppercase()),
                currentUserId = recipient.uppercase(),
                chats = listOf(chat(groupId, isGroup = true)),
                rosters = mapOf(groupId to fullRoster),
            ),
        )
    }

    @Test
    fun `without a group hint a valid claim opens its wallet activity, never a guessed chat`() {
        assertEquals(walletActivity, destination(chats = emptyList()))
        // Even one plausible direct chat with the counterparty: there is no direct routing.
        assertEquals(
            walletActivity,
            destination(
                chats = listOf(chat(groupId, peerUserId = sender)),
                messages = mapOf(
                    groupId to listOf(transferCard("msg-1", referenceId = claimId)),
                ),
            ),
        )
    }

    @Test
    fun `a group hint opens the one canonical group whose roster holds all three parties`() {
        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = null),
            destination(
                link = groupLink(),
                chats = listOf(chat(groupId, isGroup = true)),
                rosters = mapOf(
                    // Case-variant member ids canonicalize before the membership check.
                    groupId to listOf(member(recipient.uppercase()), member(sender)),
                ),
            ),
        )
        // The chat id itself may be a case variant of the canonical hint.
        val variantId = groupId.uppercase()
        assertEquals(
            PaymentClaimNavigationTarget.Conversation(variantId, focusMessageId = null),
            destination(
                link = groupLink(),
                chats = listOf(chat(variantId, isGroup = true)),
                rosters = mapOf(variantId to fullRoster),
            ),
        )
    }

    @Test
    fun `a roster that is not exactly canonical authorizes nothing`() {
        val group = chat(groupId, isGroup = true)

        for (roster in listOf(
            // Missing one party.
            listOf(member(recipient), member(bystander)),
            listOf(member(sender), member(bystander)),
            emptyList(),
            // One non-canonical member invalidates the whole roster, parties present or not.
            listOf(member(recipient), member(sender), member("user-other")),
            // A duplicated member — even as a case variant — invalidates it too.
            listOf(member(recipient), member(sender), member(sender.uppercase())),
        )) {
            assertEquals(
                walletActivity,
                destination(
                    link = groupLink(),
                    chats = listOf(group),
                    rosters = mapOf(groupId to roster),
                ),
            )
        }
    }

    @Test
    fun `a group hint never opens a direct chat and never picks between two matches`() {
        assertEquals(
            walletActivity,
            destination(
                link = groupLink(),
                chats = listOf(chat(groupId, peerUserId = sender)),
                rosters = mapOf(groupId to fullRoster),
            ),
        )
        assertEquals(
            walletActivity,
            destination(
                link = groupLink(),
                chats = listOf(
                    chat(groupId, isGroup = true),
                    chat(groupId.uppercase(), isGroup = true),
                ),
                rosters = mapOf(groupId to fullRoster),
            ),
        )
    }

    @Test
    fun `an unknown hinted group falls back to the claim's wallet activity`() {
        assertEquals(
            walletActivity,
            destination(
                link = PaymentClaimLink(claimId, conversationId = groupPaymentId),
                chats = listOf(chat(groupId, isGroup = true)),
            ),
        )
    }

    @Test
    fun `the focus is the claim's one card, matched canonically`() {
        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = "msg-2"),
            destination(
                link = groupLink(),
                chats = listOf(chat(groupId, isGroup = true)),
                rosters = mapOf(groupId to fullRoster),
                messages = mapOf(
                    groupId to listOf(
                        transferCard("msg-1", referenceId = groupPaymentId),
                        transferCard("msg-2", referenceId = claimId.uppercase()),
                        transferCard("msg-3", referenceId = "not-a-uuid"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a group payment hint focuses only its one announcement card`() {
        val announcement = announcementCard("gp-1", groupPaymentId.uppercase())

        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = "gp-1"),
            destination(
                link = groupLink(groupPaymentId),
                chats = listOf(chat(groupId, isGroup = true)),
                rosters = mapOf(groupId to fullRoster),
                messages = mapOf(
                    groupId to listOf(
                        announcement,
                        announcement.copy(id = "gp-2", kind = MessageKind.GROUP_PAYMENT_EVENT),
                        announcement.copy(id = "gp-3", groupPaymentId = claimId),
                    ),
                ),
            ),
        )
        // Without the hint, an announcement card never matches anything.
        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = null),
            destination(
                link = groupLink(),
                chats = listOf(chat(groupId, isGroup = true)),
                rosters = mapOf(groupId to fullRoster),
                messages = mapOf(groupId to listOf(announcement)),
            ),
        )
    }

    @Test
    fun `an ambiguous card means no focus rather than a guessed scroll`() {
        val group = chat(groupId, isGroup = true)
        val twin = transferCard("msg-1", referenceId = claimId)

        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = null),
            destination(
                link = groupLink(),
                chats = listOf(group),
                rosters = mapOf(groupId to fullRoster),
                messages = mapOf(groupId to listOf(twin, twin.copy(id = "msg-2"))),
            ),
        )
        // One transfer card and one announcement card both naming this claim's payment is
        // two candidates, not a winner: exactly one match across both shapes, or none.
        assertEquals(
            PaymentClaimNavigationTarget.Conversation(groupId, focusMessageId = null),
            destination(
                link = groupLink(groupPaymentId),
                chats = listOf(group),
                rosters = mapOf(groupId to fullRoster),
                messages = mapOf(
                    groupId to listOf(twin, announcementCard("gp-1", groupPaymentId)),
                ),
            ),
        )
    }

    private fun member(userId: String) = ChatMember(userId = userId, name = userId)
}
