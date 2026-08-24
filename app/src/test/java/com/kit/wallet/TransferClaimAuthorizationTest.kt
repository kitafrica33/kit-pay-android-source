package com.kit.wallet

import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.repository.canonicalTransferClaimReason
import com.kit.wallet.data.repository.transferClaimReverseIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferClaimAuthorizationTest {
    @Test
    fun `reverse authorization intent is exact ordered and reason canonical`() {
        val intent = transferClaimReverseIntent(CLAIM_ID, "  Wrong person  ")

        assertEquals(listOf("action", "claim_id", "reason"), intent.keys.toList())
        assertEquals("reverse", intent["action"])
        assertEquals(CLAIM_ID, intent["claim_id"])
        assertEquals("Wrong person", intent["reason"])
    }

    @Test
    fun `blank reason is null and oversized reason is capped for intent and request parity`() {
        assertEquals(null, canonicalTransferClaimReason("   "))
        assertEquals(
            "x".repeat(KitPaymentMessage.MAX_REASON_LENGTH),
            canonicalTransferClaimReason("x".repeat(400)),
        )
    }

    private companion object {
        const val CLAIM_ID = "11111111-1111-4111-8111-111111111111"
    }
}
