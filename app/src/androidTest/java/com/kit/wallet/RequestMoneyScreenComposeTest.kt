package com.kit.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kit.wallet.feature.wallet.RequestMoneyContent
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.theme.KitWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RequestMoneyScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun only_a_visible_valid_kit_contact_can_enable_and_submit_the_request() {
        val submitted = mutableListOf<Contact>()
        val nonKit = Contact("device:1", "Local Person", "0700000000", isKitUser = false)
        val valid = Contact("kit-user-1", "Flora Namisi", "0761146015", isKitUser = true)

        compose.setContent {
            KitWalletTheme {
                RequestMoneyContent(
                    contacts = listOf(nonKit, valid),
                    sending = false,
                    error = null,
                    onBack = {},
                    onRequest = { contact, _, _ -> submitted += contact },
                )
            }
        }

        compose.onNodeWithText("Requesting from Flora Namisi").assertIsDisplayed()
        compose.onNodeWithTag("request-contact-kit-user-1").assertIsDisplayed()
        compose.onNodeWithTag("request-submit").assertIsNotEnabled()
        compose.onNodeWithTag("request-amount").performTextInput("2500")
        compose.onNodeWithTag("request-submit").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf(valid), submitted) }
    }

    @Test
    fun no_kit_contact_shows_clear_feedback_and_never_enables_submit() {
        val submitted = mutableListOf<Contact>()
        compose.setContent {
            KitWalletTheme {
                RequestMoneyContent(
                    contacts = listOf(
                        Contact("device:1", "Local Person", "0700000000", isKitUser = false),
                        Contact("", "Broken Kit identity", "0711111111", isKitUser = true),
                    ),
                    sending = false,
                    error = null,
                    onBack = {},
                    onRequest = { contact, _, _ -> submitted += contact },
                )
            }
        }

        compose.onNodeWithTag("request-contact-feedback").assertIsDisplayed()
        compose.onNodeWithText(
            "No Kit Pay contact is available. Add or sync a Kit Pay contact first.",
        ).assertIsDisplayed()
        compose.onNodeWithTag("request-amount").performTextInput("2500")
        compose.onNodeWithTag("request-submit").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<Contact>(), submitted) }
    }
}
