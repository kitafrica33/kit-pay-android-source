package com.kit.wallet

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kit.wallet.feature.wallet.RECEIVE_MY_QR_COMING_SOON
import com.kit.wallet.feature.wallet.RECEIVE_REQUEST_AMOUNT_COMING_SOON
import com.kit.wallet.feature.wallet.RECEIVE_SET_AMOUNT_COMING_SOON
import com.kit.wallet.feature.wallet.ReceiveContent
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.theme.KitWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReceiveScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun receive_actions_are_live_and_unintegrated_paths_explain_their_state() {
        val snackbar = SnackbarHostState()
        val callbacks = mutableListOf<String>()
        setReceiveContent(
            requestMoneyAvailable = true,
            snackbar = snackbar,
            callbacks = callbacks,
        )

        clickAndExpectSnackbar("receive-action-my-qr", RECEIVE_MY_QR_COMING_SOON, snackbar)
        clickAndExpectSnackbar(
            "receive-action-set-amount",
            RECEIVE_SET_AMOUNT_COMING_SOON,
            snackbar,
        )
        compose.onNodeWithTag("receive-action-request-amount")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag("receive-action-share")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle { assertEquals(listOf("request", "share"), callbacks) }
    }

    @Test
    fun request_amount_stays_fail_closed_when_the_capability_is_off() {
        val snackbar = SnackbarHostState()
        val callbacks = mutableListOf<String>()
        setReceiveContent(
            requestMoneyAvailable = false,
            snackbar = snackbar,
            callbacks = callbacks,
        )

        clickAndExpectSnackbar(
            "receive-action-request-amount",
            RECEIVE_REQUEST_AMOUNT_COMING_SOON,
            snackbar,
        )
        compose.runOnIdle { assertEquals(emptyList<String>(), callbacks) }
    }

    private fun clickAndExpectSnackbar(
        tag: String,
        message: String,
        snackbar: SnackbarHostState,
    ) {
        compose.onNodeWithTag(tag)
            .performScrollTo()
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

    private fun setReceiveContent(
        requestMoneyAvailable: Boolean,
        snackbar: SnackbarHostState,
        callbacks: MutableList<String>,
    ) {
        compose.setContent {
            KitWalletTheme {
                ReceiveContent(
                    profile = UserProfile(
                        name = "Amina Yusuf",
                        phone = "+256700000000",
                        tag = "@amina",
                        kycLabel = "Verified",
                    ),
                    requestMoneyAvailable = requestMoneyAvailable,
                    snackbarHostState = snackbar,
                    onBack = { callbacks += "back" },
                    onRequestAmount = { callbacks += "request" },
                    onShare = { callbacks += "share" },
                )
            }
        }
    }
}
