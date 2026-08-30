package com.kit.wallet

import com.kit.wallet.data.messaging.KitScheduledGroupPaymentOutcomeAction
import com.kit.wallet.data.messaging.KitScheduledGroupPaymentOutcomeMessage
import com.kit.wallet.data.messaging.KitScheduledPaymentAction
import com.kit.wallet.data.messaging.KitScheduledPaymentMessage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledPaymentProjectionTest {
    private val scheduleId = "00000000-0000-4000-8000-000000000001"
    private val transactionId = "00000000-0000-4000-8000-000000000002"
    private val scheduledAt = Instant.parse("2027-01-01T12:00:00Z")

    @Test
    fun `direct terminal descriptor is canonical round trippable and deterministic`() {
        val completed = requireNotNull(
            KitScheduledPaymentMessage.create(
                KitScheduledPaymentAction.COMPLETED, scheduleId, 250_000L, "UGX", 2,
                scheduledAt, transactionId, "School fees", null,
            ),
        )

        assertEquals(completed, KitScheduledPaymentMessage.parse(completed.encode()))
        assertEquals(completed.deterministicMessageId(), completed.deterministicMessageId())
        assertNotEquals(
            completed.deterministicMessageId(),
            completed.copy(
                action = KitScheduledPaymentAction.CANCELLED,
                walletTransactionId = null,
                reason = "The scheduled payment was cancelled.",
            ).deterministicMessageId(),
        )
        assertNull(KitScheduledPaymentMessage.parse(completed.encode() + "&amt=1"))
        assertNull(
            KitScheduledPaymentMessage.create(
                completed.action, completed.scheduledPaymentId, completed.amountMinor,
                completed.currencyCode, completed.currencyScale, scheduledAt, null,
                completed.note, completed.reason,
            ),
        )
    }

    @Test
    fun `scheduled group outcome contains no money and has stable identity`() {
        val failed = requireNotNull(
            KitScheduledGroupPaymentOutcomeMessage.create(
                KitScheduledGroupPaymentOutcomeAction.FAILED, scheduleId, scheduledAt,
            ),
        )
        assertEquals(failed, KitScheduledGroupPaymentOutcomeMessage.parse(failed.encode()))
        assertTrue(failed.encode().startsWith("KITSGRP1:"))
        assertEquals(failed.deterministicMessageId(), failed.deterministicMessageId())
        assertNull(KitScheduledGroupPaymentOutcomeMessage.parse(failed.encode() + "&amt=250000"))
    }
}
