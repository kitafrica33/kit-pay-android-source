package com.kit.wallet

import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.TransferClaimDto
import com.kit.wallet.data.remote.TransferClaimPartyDto
import com.kit.wallet.ui.model.TransferClaimActor
import com.kit.wallet.ui.model.TransferClaimStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferClaimMappingTest {
    @Test
    fun `a pending claim carries its parties, reason window and offered actions`() {
        val claim = checkNotNull(
            claimDto(
                note = "Rent",
                sender = TransferClaimPartyDto(id = "u1", name = "Amara"),
                recipient = TransferClaimPartyDto(id = "u2", name = "Bwire"),
                canAccept = true,
                canReject = true,
            ).toUiModel(),
        )

        assertEquals(TransferClaimStatus.PENDING, claim.status)
        assertEquals(250_000L, claim.amountMinor)
        assertEquals("UGX", claim.currencyCode)
        assertEquals(2, claim.currencyScale)
        assertEquals("Rent", claim.note)
        assertEquals("u1", claim.senderUserId)
        assertEquals("u2", claim.recipientUserId)
        assertEquals("Amara", claim.senderName)
        assertEquals("Bwire", claim.recipientName)
        assertTrue(claim.canAccept)
        assertTrue(claim.canReject)
        assertFalse(claim.canReverse)
    }

    @Test
    fun `a settled claim never offers an action, whatever the service says`() {
        for (status in listOf("accepted", "rejected", "reversed", "expired")) {
            val claim = checkNotNull(
                claimDto(status = status, canAccept = true, canReject = true, canReverse = true)
                    .toUiModel(),
            )
            assertFalse("canAccept leaked on $status", claim.canAccept)
            assertFalse("canReject leaked on $status", claim.canReject)
            assertFalse("canReverse leaked on $status", claim.canReverse)
        }
    }

    @Test
    fun `a returned claim keeps who returned it and why`() {
        val claim = checkNotNull(
            claimDto(
                status = "REVERSED",
                reason = "Sent to the wrong person",
                resolvedBy = "Sender",
            ).toUiModel(),
        )

        assertEquals(TransferClaimStatus.REVERSED, claim.status)
        assertEquals(TransferClaimActor.SENDER, claim.resolvedBy)
        assertEquals("Sent to the wrong person", claim.reason)
    }

    @Test
    fun `an expiry with nobody acting is attributed to the system`() {
        val claim = checkNotNull(claimDto(status = "expired", resolvedBy = "system").toUiModel())

        assertEquals(TransferClaimStatus.EXPIRED, claim.status)
        assertEquals(TransferClaimActor.SYSTEM, claim.resolvedBy)
    }

    @Test
    fun `a status this build does not understand is dropped rather than guessed at`() {
        assertNull(claimDto(status = "disputed").toUiModel())
        assertNull(claimDto(status = "").toUiModel())
    }

    @Test
    fun `an unreadable amount or scale is dropped rather than shown as zero`() {
        assertNull(claimDto(amount = "not money").toUiModel())
        assertNull(claimDto(scale = "two").toUiModel())
    }

    @Test
    fun `a held amount is always shown as a positive sum`() {
        assertEquals(250_000L, claimDto(amount = "-2500.00").toUiModel()?.amountMinor)
    }

    @Test
    fun `blank strings from the service are absent, not empty`() {
        val claim = checkNotNull(
            claimDto(
                note = "  ",
                reason = "",
                resolvedBy = "nobody",
                sender = TransferClaimPartyDto(id = "u1", name = " "),
            ).toUiModel(),
        )

        assertNull(claim.note)
        assertNull(claim.reason)
        assertNull(claim.resolvedBy)
        assertNull(claim.senderName)
        assertNull(claim.recipientName)
    }

    private fun claimDto(
        status: String = "pending",
        amount: String = "2500.00",
        scale: String = "2",
        note: String? = null,
        reason: String? = null,
        resolvedBy: String? = null,
        sender: TransferClaimPartyDto? = null,
        recipient: TransferClaimPartyDto? = null,
        canAccept: Boolean = false,
        canReject: Boolean = false,
        canReverse: Boolean = false,
    ) = TransferClaimDto(
        id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        transactionId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        status = status,
        amount = amount,
        currency = CurrencyDto(code = "UGX", scale = scale),
        note = note,
        sender = sender,
        recipient = recipient,
        reason = reason,
        resolvedBy = resolvedBy,
        canAccept = canAccept,
        canReject = canReject,
        canReverse = canReverse,
    )
}
