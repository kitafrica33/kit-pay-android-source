package com.kit.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.kit.wallet.feature.chat.MessageBubble
import com.kit.wallet.ui.model.DeliveryState
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.theme.KitWalletTheme
import org.junit.Rule
import org.junit.Test

class ConversationReceiptComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun outgoing_bubbles_distinguish_sent_delivered_and_read() {
        compose.setContent {
            KitWalletTheme {
                Column {
                    listOf(
                        DeliveryState.SENT to "sent body",
                        DeliveryState.DELIVERED to "delivered body",
                        DeliveryState.READ to "read body",
                    ).forEach { (state, body) ->
                        MessageBubble(
                            msg = Message(
                                id = state.name,
                                text = body,
                                time = "12:00",
                                fromMe = true,
                                state = state,
                            ),
                            operationInFlight = false,
                            retrying = false,
                            retryEnabled = false,
                            onRetry = {},
                        )
                    }
                }
            }
        }

        listOf("Sent", "Delivered", "Read").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
        }
    }
}
