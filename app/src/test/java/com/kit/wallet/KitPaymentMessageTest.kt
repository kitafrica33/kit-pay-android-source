package com.kit.wallet

import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitPaymentMessageTest {
    private val request = KitPaymentMessage(
        action = KitPaymentAction.REQUEST,
        referenceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
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
        val paid = request.copy(action = KitPaymentAction.PAID)
        val parsed = KitPaymentMessage.parse(paid.encode())
        assertEquals(paid, parsed)
        assertFalse(parsed!!.isRequest)
        assertEquals(request.referenceId, parsed.referenceId)
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
    fun userTextCannotBeginWithTheReservedPaymentPrefix() {
        assertFalse(KitPaymentMessage.allowsUserAuthoredText(request.encode()))
        assertFalse(KitPaymentMessage.allowsUserAuthoredText(" \n\tKITPAY1:not-valid-either"))
        assertTrue(KitPaymentMessage.allowsUserAuthoredText("Please review KITPAY1: later"))
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

    @Test
    fun transferActionsRoundTripAndCarryTheirReason() {
        val reversed = KitPaymentMessage(
            action = KitPaymentAction.REVERSED,
            referenceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            amountMinor = 50_000,
            currencyCode = "UGX",
            currencyScale = 0,
            note = null,
            reason = "Sent to the wrong person & in a hurry",
        )
        val parsed = KitPaymentMessage.parse(reversed.encode())
        assertEquals(reversed, parsed)
        assertEquals("Sent to the wrong person & in a hurry", parsed?.reason)
        assertTrue(checkNotNull(parsed).action.returnedFunds)
        assertTrue(parsed.action.isTransferEvent)
        assertFalse(parsed.isRequest)
    }

    @Test
    fun everyActionSurvivesItsOwnRoundTrip() {
        for (action in KitPaymentAction.entries) {
            val descriptor = request.copy(action = action, reason = "why")
            assertEquals(
                "Round trip failed for ${action.wire}",
                descriptor,
                KitPaymentMessage.parse(descriptor.encode()),
            )
        }
    }

    @Test
    fun rejectsBlankReorderedAndOversizedReasons() {
        val reversed = request.copy(action = KitPaymentAction.REVERSED, reason = "wrong person")
        val encoded = reversed.encode()
        // Fixed field order: the reason follows the note, never precedes it.
        assertTrue(encoded.indexOf("&note=") < encoded.indexOf("&rsn="))
        assertNull(KitPaymentMessage.parse(reversed.copy(reason = "x".repeat(141)).encode()))
        assertNull(KitPaymentMessage.parse("$encoded&rsn=second"))
        // A blank reason must be omitted, never encoded.
        assertEquals(
            reversed.copy(reason = null).encode(),
            reversed.copy(reason = "   ").encode(),
        )
    }

    @Test
    fun onlyRequestActionsLeaveMoneyWhereItIs() {
        val stationary = setOf(
            KitPaymentAction.REQUEST,
            KitPaymentAction.DECLINED,
            KitPaymentAction.CANCELLED,
        )
        for (action in KitPaymentAction.entries) {
            assertEquals(
                "movesMoney is wrong for ${action.wire}",
                action !in stationary,
                action.movesMoney,
            )
        }
    }
}
