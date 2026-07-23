package com.kit.wallet

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.feature.home.HomeAction
import com.kit.wallet.feature.home.HomeDashboard
import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.theme.KitWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HomeDashboardComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unavailable_dashboard_buttons_stay_enabled_and_show_clear_feedback() {
        val snackbar = SnackbarHostState()
        val callbacks = mutableListOf<String>()
        setDashboard(
            capabilities = AppCapabilities(loaded = true),
            snackbar = snackbar,
            callbacks = callbacks,
            favorites = emptyList(),
            recent = emptyList(),
        )

        val unavailableActions = listOf(
            HomeAction.SCAN_QR,
            HomeAction.SEND_MONEY,
            HomeAction.RECEIVE_MONEY,
            HomeAction.REQUEST_MONEY,
            HomeAction.VERIFY_IDENTITY,
            HomeAction.PAY_BILLS,
            HomeAction.BUY_AIRTIME,
            HomeAction.BANK,
            HomeAction.MOBILE_MONEY,
        )
        unavailableActions.forEach { action ->
            compose.onNodeWithTag(action.testTag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHasClickAction()
                .performClick()

            val expectedMessage = "Coming soon: ${action.displayName}."
            compose.waitUntil(timeoutMillis = 5_000) {
                snackbar.currentSnackbarData?.visuals?.message == expectedMessage
            }
            compose.onNodeWithText(expectedMessage).assertIsDisplayed()
            compose.runOnIdle { snackbar.currentSnackbarData?.dismiss() }
            compose.waitUntil(timeoutMillis = 5_000) {
                snackbar.currentSnackbarData == null
            }
        }

        compose.runOnIdle { assertEquals(emptyList<String>(), callbacks) }
    }

    @Test
    fun activated_dashboard_invokes_every_existing_flow() {
        val snackbar = SnackbarHostState()
        val callbacks = mutableListOf<String>()
        val favorite = Contact(
            id = "favorite-1",
            name = "Grace Nakato",
            phone = "+256700000001",
            favorite = true,
        )
        val transaction = Transaction(
            id = "transaction-1",
            counterparty = "Kit Pay Power",
            note = "Electricity",
            amountMinor = -10_000,
            time = "10:00 AM",
            dateGroup = "Today",
            type = TxType.BILL,
            status = TxStatus.COMPLETED,
            reference = "KIT-TEST",
        )
        setDashboard(
            capabilities = fullyActivatedCapabilities(),
            snackbar = snackbar,
            callbacks = callbacks,
            favorites = listOf(favorite),
            recent = listOf(transaction),
        )

        click(HomeAction.SCAN_QR)
        compose.onNodeWithContentDescription("Hide balance")
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithContentDescription("Show balance").assertIsDisplayed()
        click(HomeAction.SEND_MONEY)
        click(HomeAction.RECEIVE_MONEY)
        click(HomeAction.REQUEST_MONEY)
        click(HomeAction.VERIFY_IDENTITY)
        click(HomeAction.PAY_BILLS)
        click(HomeAction.BUY_AIRTIME)
        click(HomeAction.BANK)
        click(HomeAction.MOBILE_MONEY)
        compose.onNodeWithTag("${HomeAction.FAVORITE_SEND.testTag}-${favorite.id}")
            .performScrollTo()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithText("See all")
            .performScrollTo()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        val transactionTag = "${HomeAction.TRANSACTION_DETAIL.testTag}-${transaction.id}"
        // LazyColumn does not materialize every off-screen row. Ask the scroll container to bring
        // the matching item into composition before querying the row itself.
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(transactionTag))
        compose.onNodeWithTag(transactionTag)
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    "scan",
                    "send",
                    "receive",
                    "request",
                    "kyc",
                    "bills",
                    "airtime",
                    "bank",
                    "mobile-money",
                    "send",
                    "transactions",
                    "transaction:${transaction.id}",
                ),
                callbacks,
            )
            assertNull(snackbar.currentSnackbarData)
        }
    }

    private fun click(action: HomeAction) {
        compose.onNodeWithTag(action.testTag)
            .performScrollTo()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
    }

    private fun setDashboard(
        capabilities: AppCapabilities,
        snackbar: SnackbarHostState,
        callbacks: MutableList<String>,
        favorites: List<Contact>,
        recent: List<Transaction>,
    ) {
        compose.setContent {
            KitWalletTheme {
                HomeDashboard(
                    profile = UserProfile(
                        name = "Amina Yusuf",
                        phone = "+256700000000",
                        tag = "@amina",
                        kycLabel = "Pending",
                    ),
                    balanceMinor = 100_000,
                    capabilities = capabilities,
                    favorites = favorites,
                    recent = recent,
                    snackbarHostState = snackbar,
                    onSend = { callbacks += "send" },
                    onReceive = { callbacks += "receive" },
                    onScan = { callbacks += "scan" },
                    onBills = { callbacks += "bills" },
                    onAirtime = { callbacks += "airtime" },
                    onBank = { callbacks += "bank" },
                    onMobileMoney = { callbacks += "mobile-money" },
                    onRequest = { callbacks += "request" },
                    onKyc = { callbacks += "kyc" },
                    onAllTransactions = { callbacks += "transactions" },
                    onTransaction = { callbacks += "transaction:$it" },
                )
            }
        }
    }

    private fun fullyActivatedCapabilities() = AppCapabilities(
        features = mapOf(
            KitFeature.WALLETS to true,
            KitFeature.INTERNAL_TRANSFERS to true,
            KitFeature.PAYMENT_REQUESTS to true,
            KitFeature.MERCHANT_PAYMENTS to true,
            KitFeature.QR_PAYMENTS to true,
            KitFeature.BILLS to true,
            KitFeature.AIRTIME to true,
            KitFeature.BANK_TRANSFERS to true,
            KitFeature.MOBILE_MONEY to true,
            KitFeature.KYC to true,
        ),
        loaded = true,
        qrScannerClientReady = true,
        receiveQrClientReady = true,
    )
}
