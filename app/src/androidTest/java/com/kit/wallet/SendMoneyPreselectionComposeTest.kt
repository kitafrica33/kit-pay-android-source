package com.kit.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.kit.wallet.feature.wallet.SendMoneyContent
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.theme.KitWalletTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMoneyPreselectionComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun valid_home_favorite_opens_send_with_that_contact_preselected() {
        val favorite = Contact(
            id = "favorite-user",
            name = "Grace Nakato",
            phone = "+256700000001",
            favorite = true,
            receivingWalletId = "wallet-grace",
        )
        setSendContent(initialContactId = favorite.id, contacts = listOf(favorite))

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Grace Nakato"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Grace Nakato").assertIsDisplayed()
        compose.onNodeWithText(favorite.phone).assertIsDisplayed()
        compose.onNodeWithText("Review & send").assertIsDisplayed()
    }

    @Test
    fun stale_or_ineligible_favorite_id_cannot_preselect_a_transfer_recipient() {
        val ineligible = Contact(
            id = "favorite-user",
            name = "Grace Nakato",
            phone = "+256700000001",
            favorite = true,
            receivingWalletId = null,
        )
        setSendContent(initialContactId = ineligible.id, contacts = listOf(ineligible))

        compose.onNodeWithText("Send money").assertIsDisplayed()
        assertTrue(
            compose.onAllNodes(androidx.compose.ui.test.hasText("Review & send"))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun setSendContent(initialContactId: String?, contacts: List<Contact>) {
        compose.setContent {
            KitWalletTheme {
                SendMoneyContent(
                    initialContactId = initialContactId,
                    contacts = contacts,
                    balanceMinor = 100_000,
                    sending = false,
                    lastSent = null,
                    error = null,
                    onBack = {},
                    onDone = {},
                    onSend = { _, _, _, _, _ -> },
                )
            }
        }
    }
}
