package com.kit.wallet

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kit.wallet.feature.bills.AIRTIME_CONTACT_COMING_SOON
import com.kit.wallet.feature.bills.AirtimeContent
import com.kit.wallet.feature.bills.DATA_BUNDLES_COMING_SOON
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.theme.KitWalletTheme
import org.junit.Rule
import org.junit.Test

class AirtimeScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun contact_glyph_and_data_bundles_are_actionable_with_explicit_feedback() {
        val snackbar = SnackbarHostState()
        compose.setContent {
            KitWalletTheme {
                AirtimeContent(
                    products = listOf(
                        BillProvider("airtime-mtn", "MTN Airtime", "Airtime", "Phone number"),
                    ),
                    ownPhone = "+256700000000",
                    buying = false,
                    error = null,
                    snackbarHostState = snackbar,
                    onBack = {},
                    onBuy = { _, _, _, _ -> },
                )
            }
        }

        clickAndExpectSnackbar("airtime-choose-contact", AIRTIME_CONTACT_COMING_SOON, snackbar)
        clickAndExpectSnackbar("airtime-data-bundles", DATA_BUNDLES_COMING_SOON, snackbar)
    }

    private fun clickAndExpectSnackbar(
        tag: String,
        message: String,
        snackbar: SnackbarHostState,
    ) {
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            snackbar.currentSnackbarData?.visuals?.message == message
        }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.runOnIdle { snackbar.currentSnackbarData?.dismiss() }
        compose.waitUntil(timeoutMillis = 5_000) { snackbar.currentSnackbarData == null }
    }
}
