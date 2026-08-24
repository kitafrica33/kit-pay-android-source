package com.kit.wallet

import com.kit.wallet.data.messaging.KitPaymentAction
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.feature.chat.TransferClaimPartyBinding
import com.kit.wallet.feature.chat.TransferClaimResolutionAction
import com.kit.wallet.feature.chat.TransferClaimResolutionPolicy
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import com.kit.wallet.ui.model.PaymentEventKind
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TransferClaimStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferClaimResolutionPolicyTest {
    @Test
    fun `recipient can accept or reject only an incoming exact-party transfer`() {
        val message = transferMessage(fromMe = false)
        val claim = claim(sender = PEER, recipient = CURRENT, canAccept = true, canReject = true)

        assertTrue(policyAllows(TransferClaimResolutionAction.ACCEPT, message, claim))
        assertTrue(policyAllows(TransferClaimResolutionAction.REJECT, message, claim))
        assertFalse(policyAllows(TransferClaimResolutionAction.REVERSE, message, claim))
    }

    @Test
    fun `sender can reverse only an outgoing exact-party transfer`() {
        val message = transferMessage(fromMe = true)
        val claim = claim(sender = CURRENT, recipient = PEER, canReverse = true)

        assertTrue(policyAllows(TransferClaimResolutionAction.REVERSE, message, claim))
        assertFalse(policyAllows(TransferClaimResolutionAction.ACCEPT, message, claim))
        assertFalse(policyAllows(TransferClaimResolutionAction.REJECT, message, claim))
    }

    @Test
    fun `a relayed descriptor never gains live buttons in another conversation`() {
        val message = transferMessage(fromMe = true)
        val claim = claim(sender = CURRENT, recipient = STRANGER, canReverse = true)

        assertNull(
            TransferClaimResolutionPolicy.forPresentation(
                message,
                claim,
                binding(),
                capabilityEnabled = true,
            ),
        )
        assertFalse(policyAllows(TransferClaimResolutionAction.REVERSE, message, claim))
    }

    @Test
    fun `missing parties and mismatched money fail closed`() {
        val message = transferMessage(fromMe = true)
        val exact = claim(sender = CURRENT, recipient = PEER, canReverse = true)

        assertNull(TransferClaimResolutionPolicy.resolve(message.copy(mediaDescriptor = null), exact, binding()))
        assertNull(TransferClaimResolutionPolicy.resolve(message, exact.copy(senderUserId = null), binding()))
        assertNull(TransferClaimResolutionPolicy.resolve(message, exact.copy(amountMinor = 1), binding()))
        assertNull(TransferClaimResolutionPolicy.resolve(message, exact.copy(currencyCode = "USD"), binding()))
        assertNull(TransferClaimResolutionPolicy.resolve(message, exact.copy(currencyScale = 0), binding()))
    }

    @Test
    fun `settled state server denial and missing capability strip every action`() {
        val message = transferMessage(fromMe = true)
        val claim = claim(sender = CURRENT, recipient = PEER, canReverse = true)

        assertFalse(
            policyAllows(
                TransferClaimResolutionAction.REVERSE,
                message,
                claim.copy(status = TransferClaimStatus.ACCEPTED),
            ),
        )
        assertFalse(
            policyAllows(
                TransferClaimResolutionAction.REVERSE,
                message,
                claim.copy(canReverse = false),
            ),
        )
        assertNull(
            TransferClaimResolutionPolicy.forPresentation(
                message,
                claim,
                binding(),
                capabilityEnabled = false,
            ),
        )
    }

    private fun policyAllows(
        action: TransferClaimResolutionAction,
        message: Message,
        claim: TransferClaim,
    ) = TransferClaimResolutionPolicy.allows(action, message, claim, binding())

    private fun binding() = checkNotNull(TransferClaimPartyBinding.create(CURRENT, PEER))

    private fun transferMessage(fromMe: Boolean): Message {
        val descriptor = KitPaymentMessage(
            action = KitPaymentAction.TRANSFER,
            referenceId = CLAIM_ID,
            amountMinor = 250_000,
            currencyCode = "UGX",
            currencyScale = 2,
            note = "Rent",
        )
        return Message(
            id = "message",
            text = "",
            time = "12:00",
            fromMe = fromMe,
            kind = MessageKind.PAYMENT_TRANSFER,
            mediaDescriptor = descriptor.encode(),
            paymentReferenceId = CLAIM_ID,
            paymentEvent = PaymentEventKind.TRANSFER,
            paymentCurrencyCode = "UGX",
            paymentCurrencyScale = 2,
        )
    }

    private fun claim(
        sender: String,
        recipient: String,
        canAccept: Boolean = false,
        canReject: Boolean = false,
        canReverse: Boolean = false,
    ) = TransferClaim(
        id = CLAIM_ID,
        transactionId = TRANSACTION_ID,
        status = TransferClaimStatus.PENDING,
        amountMinor = 250_000,
        currencyCode = "UGX",
        currencyScale = 2,
        senderUserId = sender,
        recipientUserId = recipient,
        canAccept = canAccept,
        canReject = canReject,
        canReverse = canReverse,
    )

    private companion object {
        const val CLAIM_ID = "11111111-1111-4111-8111-111111111111"
        const val TRANSACTION_ID = "22222222-2222-4222-8222-222222222222"
        const val CURRENT = "33333333-3333-4333-8333-333333333333"
        const val PEER = "44444444-4444-4444-8444-444444444444"
        const val STRANGER = "55555555-5555-4555-8555-555555555555"
    }
}
