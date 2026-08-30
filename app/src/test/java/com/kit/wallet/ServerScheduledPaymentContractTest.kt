package com.kit.wallet

import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPlanDto
import com.kit.wallet.data.remote.ScheduledGroupPlanRecipientDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpIntentDto
import com.kit.wallet.data.remote.ScheduledPaymentDto
import com.kit.wallet.data.remote.ScheduledPaymentStatus
import com.kit.wallet.data.remote.PreviewScheduledGroupPaymentRequest
import com.kit.wallet.data.remote.CreateGroupPaymentRecipient
import com.kit.wallet.data.repository.matchesReviewedDraft
import com.kit.wallet.data.repository.recoverNonTerminalSchedules
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerScheduledPaymentContractTest {
    private val scheduleId = "00000000-0000-4000-8000-000000000001"
    private val conversationId = "00000000-0000-4000-8000-000000000002"
    private val source = "00000000-0000-4000-8000-000000000003"
    private val destination = "00000000-0000-4000-8000-000000000004"

    @Test
    fun `direct schedule state machine rejects malformed money and incoherent terminals`() {
        val scheduled = ScheduledPaymentDto(
            id = scheduleId,
            type = "scheduled_payment",
            status = "scheduled",
            conversationId = conversationId,
            sourceWalletId = source,
            destinationWalletId = destination,
            amount = "250000.00",
            currency = CurrencyDto("UGX", "2"),
            scheduledFor = "2027-01-01T12:00:00Z",
            createdAt = "2026-08-29T12:00:00Z",
        )

        assertTrue(scheduled.isStructurallyValid())
        assertFalse(scheduled.copy(amount = "250000").isStructurallyValid())
        assertFalse(scheduled.copy(amount = "NaN").isStructurallyValid())
        assertFalse(scheduled.copy(status = "completed").isStructurallyValid())
    }

    @Test
    fun `scheduled group preview binds frozen sorted recipients and exact step-up intent`() {
        val recipient = ScheduledGroupPlanRecipientDto(
            userId = "00000000-0000-4000-8000-000000000005",
            destinationWalletId = destination,
            amount = "1000.00",
        )
        val hash = "a".repeat(64)
        val frozen = "${recipient.userId}:${recipient.destinationWalletId}:100000"
        val intent = ScheduledGroupStepUpIntentDto(
            action = "create",
            planId = scheduleId,
            planHash = hash,
            conversationId = conversationId,
            sourceWalletId = source,
            splitMode = "even",
            audience = "selected",
            totalAmount = "1000.00",
            currency = "UGX",
            note = "Equipment",
            scheduledFor = "2027-01-01T12:00:00Z",
            rosterFingerprint = hash,
            frozenRecipients = frozen,
        )
        val plan = ScheduledGroupPaymentPlanDto(
            planId = scheduleId,
            conversationId = conversationId,
            sourceWalletId = source,
            splitMode = "even",
            audience = "selected",
            totalAmount = "1000.00",
            currency = CurrencyDto("UGX", "2"),
            note = "Equipment",
            recipientCount = 1,
            recipients = listOf(recipient),
            rosterFingerprint = hash,
            frozenRecipients = frozen,
            planHash = hash,
            scheduledFor = "2027-01-01T12:00:00Z",
            expiresAt = "2026-12-31T12:00:00Z",
            stepUp = ScheduledGroupStepUpDto("scheduled_group_payment", intent),
        )

        assertTrue(plan.isStructurallyValid(Instant.parse("2026-08-29T12:00:00Z")))
        assertFalse(
            plan.copy(frozenRecipients = "$frozen,forged").isStructurallyValid(
                Instant.parse("2026-08-29T12:00:00Z"),
            ),
        )
        assertFalse(
            plan.copy(totalAmount = "1000").isStructurallyValid(
                Instant.parse("2026-08-29T12:00:00Z"),
            ),
        )
    }

    @Test
    fun `group preview fails closed when currency total or recipient differs`() {
        val plan = validPlan()
        val request = PreviewScheduledGroupPaymentRequest(
            sourceWalletId = source,
            splitMode = "even",
            audience = "selected",
            totalAmount = "1000.00",
            note = "Equipment",
            recipients = listOf(CreateGroupPaymentRecipient(plan.recipients.single().userId)),
            scheduledFor = plan.scheduledFor,
        )
        val allowed = setOf(plan.recipients.single().userId)

        assertTrue(plan.matchesReviewedDraft(request, CurrencyDto("UGX", "2"), allowed))
        assertFalse(plan.matchesReviewedDraft(request, CurrencyDto("USD", "2"), allowed))
        assertFalse(plan.matchesReviewedDraft(request.copy(totalAmount = "999.00"),
            CurrencyDto("UGX", "2"), allowed))
        assertFalse(plan.matchesReviewedDraft(request, CurrencyDto("UGX", "2"), setOf(destination)))
    }

    @Test
    fun `even preview binds quotient and remainder share multiset`() {
        val plan = multiRecipientEvenPlan(listOf("3.34", "3.33", "3.33"))
        val request = PreviewScheduledGroupPaymentRequest(
            sourceWalletId = source,
            splitMode = "even",
            audience = "selected",
            totalAmount = "10.00",
            note = "Equipment",
            // Picker and response order are irrelevant; only the canonical share multiset binds.
            recipients = plan.recipients.reversed().map { CreateGroupPaymentRecipient(it.userId) },
            scheduledFor = plan.scheduledFor,
        )
        val allowed = plan.recipients.mapTo(mutableSetOf()) { it.userId }

        assertTrue(plan.isStructurallyValid(Instant.parse("2026-08-29T12:00:00Z")))
        assertTrue(plan.matchesReviewedDraft(request, CurrencyDto("UGX", "2"), allowed))
    }

    @Test
    fun `even preview rejects a sum-preserving skew but accepts remainder on any recipient`() {
        val canonical = multiRecipientEvenPlan(listOf("3.34", "3.33", "3.33"))
        val request = PreviewScheduledGroupPaymentRequest(
            sourceWalletId = source,
            splitMode = "even",
            audience = "selected",
            totalAmount = "10.00",
            note = "Equipment",
            recipients = canonical.recipients.map { CreateGroupPaymentRecipient(it.userId) },
            scheduledFor = canonical.scheduledFor,
        )
        val allowed = canonical.recipients.mapTo(mutableSetOf()) { it.userId }
        val skewed = multiRecipientEvenPlan(listOf("3.35", "3.32", "3.33"))
        val misplacedRemainder = multiRecipientEvenPlan(listOf("3.33", "3.34", "3.33"))

        assertTrue(skewed.isStructurallyValid(Instant.parse("2026-08-29T12:00:00Z")))
        assertTrue(misplacedRemainder.isStructurallyValid(Instant.parse("2026-08-29T12:00:00Z")))
        assertFalse(skewed.matchesReviewedDraft(request, CurrencyDto("UGX", "2"), allowed))
        assertTrue(
            misplacedRemainder.matchesReviewedDraft(request, CurrencyDto("UGX", "2"), allowed),
        )
    }

    @Test
    fun `recovery exhausts every active status and exact reconciles omitted prior rows`() = runTest {
        data class Row(val id: String, val status: ScheduledPaymentStatus)
        data class Page(val rows: List<Row>, val more: Boolean = false, val cursor: String? = null)
        val calls = mutableListOf<Pair<ScheduledPaymentStatus, String?>>()
        val scheduled = Row("scheduled", ScheduledPaymentStatus.SCHEDULED)
        val queued = Row("queued", ScheduledPaymentStatus.QUEUED)
        val processing = Row("processing", ScheduledPaymentStatus.PROCESSING)
        val omitted = Row("omitted", ScheduledPaymentStatus.PROCESSING)
        val terminal = Row("terminal", ScheduledPaymentStatus.SCHEDULED)

        val recovered = recoverNonTerminalSchedules(
            previous = listOf(omitted, terminal),
            page = { status, cursor ->
                calls += status to cursor
                Page(listOf(when (status) {
                    ScheduledPaymentStatus.SCHEDULED -> scheduled
                    ScheduledPaymentStatus.QUEUED -> queued
                    ScheduledPaymentStatus.PROCESSING -> processing
                    else -> error("terminal status was paged")
                }))
            },
            pageItems = Page::rows,
            pageHasMore = Page::more,
            pageCursor = Page::cursor,
            exact = { id -> if (id == "omitted") omitted else Row(id, ScheduledPaymentStatus.COMPLETED) },
            id = Row::id,
            status = Row::status,
        )

        assertEquals(setOf("scheduled", "queued", "processing", "omitted"), recovered.map { it.id }.toSet())
        assertEquals(
            listOf(ScheduledPaymentStatus.SCHEDULED, ScheduledPaymentStatus.QUEUED,
                ScheduledPaymentStatus.PROCESSING),
            calls.map { it.first },
        )
    }

    @Test
    fun `recovery rejects a looping cursor without publishing partial rows`() = runTest {
        data class Row(val id: String, val status: ScheduledPaymentStatus)
        data class Page(val rows: List<Row>, val cursor: String)
        var returned = false
        try {
            recoverNonTerminalSchedules(
                previous = emptyList(),
                page = { status, _ -> Page(listOf(Row(status.wire, status)), "same") },
                pageItems = Page::rows,
                pageHasMore = { true },
                pageCursor = Page::cursor,
                exact = { null },
                id = Row::id,
                status = Row::status,
            )
            returned = true
        } catch (_: IllegalStateException) {
            // Expected: callers retain their previous immutable list because no value returned.
        }
        assertFalse(returned)
    }

    private fun validPlan(): ScheduledGroupPaymentPlanDto {
        val recipient = ScheduledGroupPlanRecipientDto(
            userId = "00000000-0000-4000-8000-000000000005",
            destinationWalletId = destination,
            amount = "1000.00",
        )
        val hash = "a".repeat(64)
        val frozen = "${recipient.userId}:${recipient.destinationWalletId}:100000"
        val scheduledFor = "2027-01-01T12:00:00Z"
        return ScheduledGroupPaymentPlanDto(
            planId = scheduleId, conversationId = conversationId, sourceWalletId = source,
            splitMode = "even", audience = "selected", totalAmount = "1000.00",
            currency = CurrencyDto("UGX", "2"), note = "Equipment", recipientCount = 1,
            recipients = listOf(recipient), rosterFingerprint = hash, frozenRecipients = frozen,
            planHash = hash, scheduledFor = scheduledFor, expiresAt = "2026-12-31T12:00:00Z",
            stepUp = ScheduledGroupStepUpDto(
                "scheduled_group_payment",
                ScheduledGroupStepUpIntentDto(
                    "create", scheduleId, hash, conversationId, source, "even", "selected",
                    "1000.00", "UGX", "Equipment", scheduledFor, hash, frozen,
                ),
            ),
        )
    }

    private fun multiRecipientEvenPlan(amounts: List<String>): ScheduledGroupPaymentPlanDto {
        require(amounts.size == 3)
        val recipients = amounts.mapIndexed { index, amount ->
            ScheduledGroupPlanRecipientDto(
                userId = "00000000-0000-4000-8000-00000000000${index + 5}",
                destinationWalletId = "10000000-0000-4000-8000-00000000000${index + 5}",
                amount = amount,
            )
        }
        val hash = "b".repeat(64)
        val frozen = recipients.joinToString(",") { recipient ->
            "${recipient.userId}:${recipient.destinationWalletId}:" +
                checkNotNull(recipient.amount.toBigDecimalOrNull())
                    .movePointRight(2).longValueExact()
        }
        val scheduledFor = "2027-01-01T12:00:00Z"
        return ScheduledGroupPaymentPlanDto(
            planId = scheduleId,
            conversationId = conversationId,
            sourceWalletId = source,
            splitMode = "even",
            audience = "selected",
            totalAmount = "10.00",
            currency = CurrencyDto("UGX", "2"),
            note = "Equipment",
            recipientCount = recipients.size,
            recipients = recipients,
            rosterFingerprint = hash,
            frozenRecipients = frozen,
            planHash = hash,
            scheduledFor = scheduledFor,
            expiresAt = "2026-12-31T12:00:00Z",
            stepUp = ScheduledGroupStepUpDto(
                "scheduled_group_payment",
                ScheduledGroupStepUpIntentDto(
                    "create", scheduleId, hash, conversationId, source, "even", "selected",
                    "10.00", "UGX", "Equipment", scheduledFor, hash, frozen,
                ),
            ),
        )
    }
}
