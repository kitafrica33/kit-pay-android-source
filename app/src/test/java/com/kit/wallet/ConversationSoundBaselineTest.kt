package com.kit.wallet

import com.kit.wallet.feature.chat.ConversationSoundBaseline
import com.kit.wallet.feature.chat.ConversationSoundDecision
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSoundBaselineTest {
    @Test
    fun `restored messages and payments stay silent across readiness recovery`() {
        val baseline = ConversationSoundBaseline()
        val retained = listOf(
            message("old-text"),
            message("old-payment", kind = MessageKind.PAYMENT),
        )

        assertEquals(ConversationSoundDecision(), baseline.observe(true, retained))
        assertEquals(ConversationSoundDecision(), baseline.observe(false, emptyList()))
        assertEquals(ConversationSoundDecision(), baseline.observe(false, retained))
        assertEquals(ConversationSoundDecision(), baseline.observe(true, retained))

        val conflatedRecovery = ConversationSoundBaseline()
        conflatedRecovery.observe(true, retained)
        conflatedRecovery.observe(false, emptyList())
        assertEquals(
            ConversationSoundDecision(),
            conflatedRecovery.observe(true, retained),
        )
    }

    @Test
    fun `only post baseline arrivals and first delivery ticks request sounds`() {
        val baseline = ConversationSoundBaseline()
        val initial = listOf(
            message("old-text"),
            message("outgoing", fromMe = true, state = DeliveryState.SENDING),
        )
        baseline.observe(messagingReady = false, initial)
        baseline.observe(messagingReady = true, initial)

        assertEquals(
            ConversationSoundDecision(playReceived = true, playSent = true),
            baseline.observe(
                messagingReady = true,
                projected = initial.map {
                    if (it.id == "outgoing") it.copy(state = DeliveryState.SENT) else it
                } + message("new-text"),
            ),
        )
    }

    @Test
    fun `new completed payment takes precedence over ordinary receive tone`() {
        val baseline = ConversationSoundBaseline()
        baseline.observe(messagingReady = true, projected = emptyList())

        assertEquals(
            ConversationSoundDecision(playPaymentReceived = true),
            baseline.observe(
                messagingReady = true,
                projected = listOf(
                    message("new-text"),
                    message("new-payment", kind = MessageKind.PAYMENT),
                ),
            ),
        )
    }

    private fun message(
        id: String,
        fromMe: Boolean = false,
        state: DeliveryState = DeliveryState.DELIVERED,
        kind: MessageKind = MessageKind.TEXT,
    ) = Message(
        id = id,
        text = id,
        time = "12:00",
        fromMe = fromMe,
        state = state,
        kind = kind,
    )
}
