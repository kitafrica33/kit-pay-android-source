package com.kit.wallet

import com.kit.wallet.data.messaging.KitPaymentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitPaymentMessageTest {
    private val request = KitPaymentMessage(
        action = KitPaymentMessage.ACTION_REQUEST,
        paymentRequestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        amountMinor = 2_500_000,
        currencyCode = "UGX",
        currencyScale = 2,
        note = "Lunch split 50/50 & drinks",
    )

    @Test
    fun roundTripsThroughDeterministicEncoding() {
        val encoded = request.encode()
        assertTrue(KitPaymentMessage.isPaymentText(encoded))
        assertEquals(request, KitPaymentMessage.parse(encoded))
        // Deterministic bytes keep retry text equality intact.
        assertEquals(encoded, KitPaymentMessage.parse(encoded)?.encode())
    }

    @Test
    fun paidConfirmationKeepsRequestIdentity() {
        val paid = request.copy(action = KitPaymentMessage.ACTION_PAID)
        val parsed = KitPaymentMessage.parse(paid.encode())
        assertEquals(paid, parsed)
        assertFalse(parsed!!.isRequest)
        assertEquals(request.paymentRequestId, parsed.paymentRequestId)
    }

    @Test
    fun rejectsNonCanonicalOrMalformedDescriptors() {
        val encoded = request.encode()
        assertNull(KitPaymentMessage.parse("$encoded&x=1"))
        assertNull(KitPaymentMessage.parse(encoded.replace("a=request", "a=steal")))
        assertNull(KitPaymentMessage.parse(encoded.replace("amt=2500000", "amt=-1")))
        assertNull(KitPaymentMessage.parse(encoded.replace("cur=UGX", "cur=ugx")))
        assertNull(KitPaymentMessage.parse(encoded.replace("id=", "id=%2e")))
        assertNull(KitPaymentMessage.parse("KITPAY1:v=2&a=request"))
        assertNull(KitPaymentMessage.parse("plain text mentioning KITPAY1: later"))
    }

    @Test
    fun rejectsOversizedAmountsAndNotes() {
        assertNull(
            KitPaymentMessage.parse(
                request.copy(amountMinor = 1_000_000_000_001L).encode(),
            ),
        )
        assertNull(KitPaymentMessage.parse(request.copy(note = "x".repeat(141)).encode()))
        // A blank note must be omitted, never encoded.
        assertEquals(
            request.copy(note = null).encode(),
            request.copy(note = "   ").encode(),
        )
    }
}
