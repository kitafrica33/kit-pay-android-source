package com.kit.wallet

import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.ScheduledGroupPaymentPlanDto
import com.kit.wallet.data.remote.ScheduledGroupPlanRecipientDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpDto
import com.kit.wallet.data.remote.ScheduledGroupStepUpIntentDto
import com.kit.wallet.data.remote.ScheduledPaymentDto
import java.time.Instant
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
}
