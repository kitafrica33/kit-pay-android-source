package com.kit.wallet

import com.kit.wallet.feature.chat.paymentEventSummary
import com.kit.wallet.feature.chat.paymentOutcomes
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.PaymentEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPaymentTimelineTest {
    @Test
    fun `an open transfer has no outcome until one is recorded`() {
        val transfer = paymentMessage(
            id = "1",
            kind = MessageKind.PAYMENT_TRANSFER,
            event = PaymentEventKind.TRANSFER,
        )

        assertTrue(paymentOutcomes(listOf(transfer)).isEmpty())
    }

    @Test
    fun `a reversal records both the outcome and the reason against the transfer`() {
        val outcomes = paymentOutcomes(
            listOf(
                paymentMessage("1", MessageKind.PAYMENT_TRANSFER, PaymentEventKind.TRANSFER),
                paymentMessage(
                    id = "2",
                    kind = MessageKind.PAYMENT_EVENT,
                    event = PaymentEventKind.REVERSED,
                    reason = "Sent to the wrong person",
                ),
            ),
        )

        val outcome = checkNotNull(outcomes[REFERENCE])
        assertEquals(PaymentEventKind.REVERSED, outcome.event)
        assertEquals("Sent to the wrong person", outcome.reason)
    }

    @Test
    fun `the conversation's last word on a payment wins`() {
        val outcomes = paymentOutcomes(
            listOf(
                paymentMessage("1", MessageKind.PAYMENT_TRANSFER, PaymentEventKind.TRANSFER),
                paymentMessage("2", MessageKind.PAYMENT_EVENT, PaymentEventKind.ACCEPTED),
                paymentMessage(
                    id = "3",
                    kind = MessageKind.PAYMENT_EVENT,
                    event = PaymentEventKind.REVERSED,
                    reason = "Changed my mind",
                ),
            ),
        )

        assertEquals(PaymentEventKind.REVERSED, outcomes[REFERENCE]?.event)
        assertEquals("Changed my mind", outcomes[REFERENCE]?.reason)
    }

    @Test
    fun `a payment message with no reference is not an outcome for anything`() {
        val orphan = paymentMessage("1", MessageKind.PAYMENT_EVENT, PaymentEventKind.REJECTED)
            .copy(paymentReferenceId = null)

        assertTrue(paymentOutcomes(listOf(orphan)).isEmpty())
    }

    @Test
    fun `references are matched regardless of the case they were written in`() {
        val outcomes = paymentOutcomes(
            listOf(
                paymentMessage("1", MessageKind.PAYMENT_EVENT, PaymentEventKind.ACCEPTED)
                    .copy(paymentReferenceId = REFERENCE.uppercase()),
            ),
        )

        assertEquals(PaymentEventKind.ACCEPTED, outcomes[REFERENCE]?.event)
        assertNull(outcomes[REFERENCE.uppercase()])
    }

    @Test
    fun `a reversal line says who reversed it and why`() {
        val mine = paymentMessage(
            id = "1",
            kind = MessageKind.PAYMENT_EVENT,
            event = PaymentEventKind.REVERSED,
            reason = "Wrong person",
        ).copy(fromMe = true)
        val theirs = mine.copy(fromMe = false, senderName = null)

        assertEquals("You reversed UGX 500 · Wrong person", paymentEventSummary(mine, "Amara"))
        assertEquals("Amara reversed UGX 500 · Wrong person", paymentEventSummary(theirs, "Amara"))
    }

    @Test
    fun `a line with no reason still reads as a complete sentence`() {
        val accepted = paymentMessage("1", MessageKind.PAYMENT_EVENT, PaymentEventKind.ACCEPTED)
            .copy(fromMe = true)

        assertEquals("You accepted UGX 500", paymentEventSummary(accepted, "Amara"))
    }

    @Test
    fun `an expiry names no one, because no one acted`() {
        val expired = paymentMessage("1", MessageKind.PAYMENT_EVENT, PaymentEventKind.EXPIRED)

        assertEquals(
            "UGX 500 was returned — not accepted in time",
            paymentEventSummary(expired, "Amara"),
        )
    }

    @Test
    fun `a missing peer name never puts the wrong person's name on the line`() {
        val rejected = paymentMessage("1", MessageKind.PAYMENT_EVENT, PaymentEventKind.REJECTED)
            .copy(fromMe = false, senderName = null)

        assertEquals("They returned UGX 500", paymentEventSummary(rejected, null))
    }

    private fun paymentMessage(
        id: String,
        kind: MessageKind,
        event: PaymentEventKind,
        reason: String? = null,
    ) = Message(
        id = id,
        text = "",
        time = "10:00",
        fromMe = false,
        kind = kind,
        amountMinor = 50_000,
        paymentReferenceId = REFERENCE,
        paymentEvent = event,
        paymentReason = reason,
        paymentCurrencyCode = "UGX",
        paymentCurrencyScale = 2,
    )

    private companion object {
        const val REFERENCE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
