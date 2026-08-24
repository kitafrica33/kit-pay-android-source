package com.kit.wallet.screenshots

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.feature.calls.CallsContent
import com.kit.wallet.feature.chat.ChatsContent
import com.kit.wallet.feature.chat.ConversationContent
import com.kit.wallet.feature.home.HomeDashboard
import com.kit.wallet.feature.wallet.SendMoneyContent
import com.kit.wallet.feature.wallet.TransactionsContent
import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.ui.theme.KitWalletTheme

/**
 * Renders one Kit Pay screen filled with the fictional [StoreScreenshotData] so the Play Store
 * listing can be captured with `adb screencap` on a clean emulator.
 *
 * This activity exists only in the debug source set and is never present in a distributable
 * build. It hosts the real production composables — nothing here reimplements or mocks up the
 * user interface, so a captured screenshot is the app as it actually draws.
 *
 * Usage:
 *   adb shell am start -n com.kit.wallet/com.kit.wallet.screenshots.StoreScreenshotActivity \
 *       --es screen chats
 */
class StoreScreenshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(com.kit.wallet.R.style.Theme_KitWallet)
        val screen = intent?.getStringExtra(EXTRA_SCREEN).orEmpty()
        setContent {
            KitWalletTheme(darkTheme = false) {
                when (screen) {
                    "chats" -> ChatsShot()
                    "paid" -> PaidShot()
                    "send" -> SendShot()
                    "transfer" -> TransferShot()
                    "reversal" -> ReversalShot()
                    "request" -> RequestShot()
                    "home" -> HomeShot()
                    "transactions" -> TransactionsShot()
                    "calls" -> CallsShot()
                    else -> error("unknown screenshot screen: '$screen'")
                }
            }
        }
    }

    @Composable
    private fun ChatsShot() {
        ChatsContent(
            allChats = StoreScreenshotData.chats,
            messagingAvailable = true,
            onChat = {},
            onNewChat = {},
        )
    }

    @Composable
    private fun PaidShot() {
        ConversationContent(
            chat = StoreScreenshotData.brianChat,
            messages = StoreScreenshotData.paidConversation,
            onBack = {},
            onVoiceCall = {},
            onVideoCall = {},
            sending = false,
            retryingMessageId = null,
            error = null,
            onClearError = {},
            onSend = { _, onSent -> onSent() },
            onRetry = { _, onRetried -> onRetried() },
        )
    }

    @Composable
    private fun SendShot() {
        SendMoneyContent(
            initialContactId = "c2",
            contacts = StoreScreenshotData.favorites,
            balanceMinor = StoreScreenshotData.balanceMinor,
            sending = false,
            lastSent = null,
            error = null,
            onBack = {},
            onDone = {},
            onSend = { _, _, _, _, done -> done() },
        )
    }

    @Composable
    private fun TransferShot() {
        ConversationContent(
            chat = StoreScreenshotData.graceChat,
            messages = StoreScreenshotData.incomingTransferConversation,
            onBack = {},
            onVoiceCall = {},
            onVideoCall = {},
            sending = false,
            retryingMessageId = null,
            error = null,
            onClearError = {},
            onSend = { _, onSent -> onSent() },
            onRetry = { _, onRetried -> onRetried() },
            claimableTransfersEnabled = true,
            currentAccountId = StoreScreenshotData.ME,
            transferClaims = mapOf(
                StoreScreenshotData.heldClaim.id to StoreScreenshotData.heldClaim,
            ),
        )
    }

    @Composable
    private fun ReversalShot() {
        ConversationContent(
            chat = StoreScreenshotData.brianChat,
            messages = StoreScreenshotData.reversedConversation,
            onBack = {},
            onVoiceCall = {},
            onVideoCall = {},
            sending = false,
            retryingMessageId = null,
            error = null,
            onClearError = {},
            onSend = { _, onSent -> onSent() },
            onRetry = { _, onRetried -> onRetried() },
            claimableTransfersEnabled = true,
            currentAccountId = StoreScreenshotData.ME,
            transferClaims = mapOf(
                StoreScreenshotData.reversedClaim.id to StoreScreenshotData.reversedClaim,
            ),
        )
    }

    @Composable
    private fun RequestShot() {
        ConversationContent(
            chat = StoreScreenshotData.chats[7],
            messages = StoreScreenshotData.requestConversation,
            onBack = {},
            onVoiceCall = {},
            onVideoCall = {},
            sending = false,
            retryingMessageId = null,
            error = null,
            onClearError = {},
            onSend = { _, onSent -> onSent() },
            onRetry = { _, onRetried -> onRetried() },
        )
    }

    @Composable
    private fun HomeShot() {
        val snackbar = remember { SnackbarHostState() }
        HomeDashboard(
            profile = StoreScreenshotData.profile,
            balanceMinor = StoreScreenshotData.balanceMinor,
            capabilities = activatedCapabilities,
            favorites = StoreScreenshotData.favorites,
            recent = StoreScreenshotData.transactions,
            snackbarHostState = snackbar,
            onSend = {},
            onReceive = {},
            onScan = {},
            onBills = {},
            onAirtime = {},
            onBank = {},
            onMobileMoney = {},
            onRequest = {},
            onKyc = {},
            onAllTransactions = {},
            onTransaction = {},
            onFavorite = {},
        )
    }

    @Composable
    private fun TransactionsShot() {
        TransactionsContent(
            transactions = StoreScreenshotData.transactions,
            onBack = {},
            onTransaction = {},
        )
    }

    @Composable
    private fun CallsShot() {
        CallsContent(
            allCalls = StoreScreenshotData.calls,
            onVoiceCall = {},
            onVideoCall = {},
            onNewCall = {},
        )
    }

    private val activatedCapabilities = AppCapabilities(
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
    )

    private companion object {
        const val EXTRA_SCREEN = "screen"
    }
}
