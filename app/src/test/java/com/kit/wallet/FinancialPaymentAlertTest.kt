package com.kit.wallet

import com.kit.wallet.data.notifications.FinancialPaymentAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialPaymentAlertTest {
    @Test
    fun `payment alert ignores supplied deep link and reconstructs canonical route`() {
        val alert = FinancialPaymentAlert.fromData(
            mapOf(
                "type" to "group_payment_request.contributed",
                "action" to "open_group_payment_request",
                "conversation_id" to "00000000-0000-4000-8000-000000000004",
                "group_payment_request_id" to "00000000-0000-4000-8000-000000000003",
                "notification_tag" to "group-payment-request:00000000-0000-4000-8000-000000000003",
                "deep_link" to "https://attacker.example",
            ),
        )!!

        assertEquals(
            "kitwallet://conversation/00000000-0000-4000-8000-000000000004?" +
                "group_payment_request_id=00000000-0000-4000-8000-000000000003",
            alert.deepLink(),
        )
    }

    @Test
    fun `payment alert rejects wrong action tag or identifiers`() {
        val base = mapOf(
            "type" to "scheduled_group_payment.completed",
            "action" to "open_scheduled_group_payment",
            "conversation_id" to "00000000-0000-4000-8000-000000000004",
            "scheduled_group_payment_id" to "00000000-0000-4000-8000-000000000003",
        )
        assertNull(FinancialPaymentAlert.fromData(base + ("action" to "open_scheduled_payment")))
        assertNull(FinancialPaymentAlert.fromData(base + ("conversation_id" to "not-a-uuid")))
        assertNull(FinancialPaymentAlert.fromData(base + ("notification_tag" to "wrong")))
    }
}
