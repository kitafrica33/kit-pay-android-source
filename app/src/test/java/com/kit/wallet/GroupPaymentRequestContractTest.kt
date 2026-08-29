package com.kit.wallet

import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.GroupPaymentRequestContributionDto
import com.kit.wallet.data.remote.GroupPaymentRequestDto
import com.kit.wallet.data.remote.GroupPaymentRequestPresentation
import com.kit.wallet.data.remote.KitGroupPaymentRequestAction
import com.kit.wallet.data.remote.KitGroupPaymentRequestMessage
import com.kit.wallet.feature.chat.groupPaymentRequestEventText
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPaymentRequestContractTest {
    private val requestId = "00000000-0000-4000-8000-000000000003"
    private val conversationId = "00000000-0000-4000-8000-000000000004"
    private val requesterId = "00000000-0000-4000-8000-000000000005"

    @Test
    fun `partial progress is authoritative and contribution count counts rows`() {
        val first = contribution("00000000-0000-4000-8000-000000000006", "25000000")
        val second = contribution("00000000-0000-4000-8000-000000000008", "25000000")
        val request = request(
            contributionCount = 2,
            contributorCount = 1,
            contributed = "50000000",
            remaining = "50000000",
            progress = 5_000,
            contributions = listOf(first, second),
        )

        assertTrue(request.isStructurallyValid())
        assertEquals(2, request.contributionCount)
        assertEquals(1, request.contributorCount)
    }

    @Test
    fun `newest fifty page advertises exact stable older cursor and bubble stays at five`() {
        val rows = (1..50).map { index ->
            contribution("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}", "1")
        }
        val request = request(
            contributionCount = 51,
            contributorCount = 1,
            contributed = "51",
            remaining = "99999949",
            progress = 0,
            contributions = rows,
            hasMore = true,
            nextBefore = rows.first().id,
        )

        assertTrue(request.isStructurallyValid())
        assertEquals(5, request.bubbleContributions().size)
        assertFalse(request.bubbleContributions().contains(rows.first()))
    }

    @Test
    fun `KITGREQ1 is canonical and contribution hints carry exact row id`() {
        val contributionId = "00000000-0000-4000-8000-000000000006"
        val descriptor = KitGroupPaymentRequestMessage.create(
            KitGroupPaymentRequestAction.CONTRIBUTED,
            requestId,
            contributionId = contributionId,
            amountMinor = 25_000_000,
        )!!

        assertEquals(descriptor, KitGroupPaymentRequestMessage.parse(descriptor.encode()))
        assertNull(KitGroupPaymentRequestMessage.parse(descriptor.encode() + "&amt=25000000"))
        assertNull(
            KitGroupPaymentRequestMessage.parse(
                "KITGREQ1:a=contributed&v=1&id=$requestId&cid=$contributionId&amt=25000000",
            ),
        )
    }

    @Test
    fun `partial and completing contributions use professional actor-aware copy`() {
        assertEquals(
            "Ama contributed UGX 250,000 to this request.",
            GroupPaymentRequestPresentation.contributed("Ama", "250,000", "UGX", false),
        )
        assertEquals(
            "You contributed UGX 250,000 to this request.",
            GroupPaymentRequestPresentation.contributed("Ama", "250,000", "UGX", true),
        )
        assertEquals(
            "Ama completed this request with UGX 250,000 — UGX 1,000,000 collected.",
            GroupPaymentRequestPresentation.completed(
                "Ama", "250,000", "1,000,000", "UGX", false,
            ),
        )
        assertEquals(
            "You completed this request with UGX 250,000 — UGX 1,000,000 collected.",
            GroupPaymentRequestPresentation.completed(
                "Ama", "250,000", "1,000,000", "UGX", true,
            ),
        )
    }

    @Test
    fun `completion attribution requires the exact contribution and matching actor`() {
        val exact = contribution(
            "00000000-0000-4000-8000-000000000006", "25000000",
        ).copy(isYours = false)
        val completed = request(
            contributionCount = 1,
            contributorCount = 1,
            contributed = "100000000",
            remaining = "0",
            progress = 10_000,
            contributions = listOf(exact),
            status = "completed",
        )
        val message = Message(
            id = "financial:1",
            text = "",
            time = "12:00",
            fromMe = false,
            senderUserId = exact.contributorUserId,
            kind = MessageKind.GROUP_PAYMENT_REQUEST_EVENT,
            groupPaymentRequestId = requestId,
            groupPaymentRequestAction = "completed",
            groupPaymentRequestContributionId = exact.id,
        )

        assertEquals(
            "Ama completed this request with UGX 25000000 — UGX 100000000 collected.",
            groupPaymentRequestEventText(message, completed, exact) { "Ama" },
        )
        assertNull(
            groupPaymentRequestEventText(
                message.copy(senderUserId = "00000000-0000-4000-8000-000000000009"),
                completed,
                exact,
            ) { "Wrong actor" },
        )
        assertNull(groupPaymentRequestEventText(message, completed, null) { "Ama" })
    }

    private fun contribution(id: String, amountMinor: String) = GroupPaymentRequestContributionDto(
        id = id,
        contributorUserId = "00000000-0000-4000-8000-000000000007",
        amount = amountMinor,
        amountMinor = amountMinor,
        isYours = true,
    )

    private fun request(
        contributionCount: Int,
        contributorCount: Int,
        contributed: String,
        remaining: String,
        progress: Int,
        contributions: List<GroupPaymentRequestContributionDto>,
        hasMore: Boolean = false,
        nextBefore: String? = null,
        status: String = "open",
    ) = GroupPaymentRequestDto(
        id = requestId,
        type = "group_payment_request",
        conversationId = conversationId,
        requesterUserId = requesterId,
        status = status,
        targetAmount = "100000000",
        targetAmountMinor = "100000000",
        contributedAmount = contributed,
        contributedAmountMinor = contributed,
        remainingAmount = remaining,
        remainingAmountMinor = remaining,
        progressBasisPoints = progress,
        contributionCount = contributionCount,
        contributorCount = contributorCount,
        yourContributedAmount = contributed,
        yourContributedAmountMinor = contributed,
        currency = CurrencyDto("UGX", "0"),
        canContribute = status == "open",
        canCancel = false,
        contributionsHasMore = hasMore,
        contributionsNextBefore = nextBefore,
        contributions = contributions,
    )
}
