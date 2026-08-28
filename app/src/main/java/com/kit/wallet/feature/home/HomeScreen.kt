package com.kit.wallet.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RequestPage
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.repository.KycVerificationState
import com.kit.wallet.data.repository.kycVerificationStateOf
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.SectionHeader
import com.kit.wallet.ui.components.TransactionRow
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    capabilities: AppCapabilities,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
    onBills: () -> Unit,
    onAirtime: () -> Unit,
    onBank: () -> Unit,
    onMobileMoney: () -> Unit,
    onRequest: () -> Unit,
    onKyc: () -> Unit,
    onStartChat: () -> Unit,
    onAllTransactions: () -> Unit,
    onTransaction: (String) -> Unit,
    onFavorite: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val balanceMinor by viewModel.balanceMinor.collectAsStateWithLifecycle()
    val recent by viewModel.recentTransactions.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val checklist by viewModel.starterChecklist.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        viewModel.setHomeVisible(true)
        onDispose { viewModel.setHomeVisible(false) }
    }

    HomeDashboard(
        profile = profile,
        balanceMinor = balanceMinor,
        capabilities = capabilities,
        favorites = favorites,
        recent = recent,
        checklist = checklist,
        refreshing = refreshing,
        onRefresh = viewModel::refresh,
        snackbarHostState = snackbarHostState,
        onSend = onSend,
        onReceive = onReceive,
        onScan = onScan,
        onBills = onBills,
        onAirtime = onAirtime,
        onBank = onBank,
        onMobileMoney = onMobileMoney,
        onRequest = onRequest,
        onKyc = onKyc,
        onStartChat = onStartChat,
        onAllTransactions = onAllTransactions,
        onTransaction = onTransaction,
        onFavorite = onFavorite,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDashboard(
    profile: UserProfile,
    balanceMinor: Long,
    capabilities: AppCapabilities,
    favorites: List<Contact>,
    recent: List<Transaction>,
    snackbarHostState: SnackbarHostState,
    checklist: StarterChecklist = StarterChecklistPolicy.checklist(
        liveKyc = null,
        profileKycLabel = null,
        hasSentMessage = false,
        firstTransactionMade = false,
    ),
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
    onBills: () -> Unit,
    onAirtime: () -> Unit,
    onBank: () -> Unit,
    onMobileMoney: () -> Unit,
    onRequest: () -> Unit,
    onKyc: () -> Unit,
    onStartChat: () -> Unit = {},
    onAllTransactions: () -> Unit,
    onTransaction: (String) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dispatch: (HomeAction, () -> Unit) -> Unit = { action, onAvailable ->
        val access = capabilities.homeActionAccess(action)
        if (access.available) {
            onAvailable()
        } else {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(access.unavailableMessage)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        HomeContent(
            profile = profile,
            balanceMinor = balanceMinor,
            capabilities = capabilities,
            favorites = favorites,
            recent = recent,
            checklist = checklist,
            onStartChat = { dispatch(HomeAction.START_FIRST_CHAT, onStartChat) },
            onSend = { dispatch(HomeAction.SEND_MONEY, onSend) },
            onReceive = { dispatch(HomeAction.RECEIVE_MONEY, onReceive) },
            onScan = { dispatch(HomeAction.SCAN_QR, onScan) },
            onBills = { dispatch(HomeAction.PAY_BILLS, onBills) },
            onAirtime = { dispatch(HomeAction.BUY_AIRTIME, onAirtime) },
            onBank = { dispatch(HomeAction.BANK, onBank) },
            onMobileMoney = { dispatch(HomeAction.MOBILE_MONEY, onMobileMoney) },
            onRequest = { dispatch(HomeAction.REQUEST_MONEY, onRequest) },
            onKyc = { dispatch(HomeAction.VERIFY_IDENTITY, onKyc) },
            onNotifications = { dispatch(HomeAction.NOTIFICATIONS) {} },
            onFavorite = { contact ->
                dispatch(HomeAction.FAVORITE_SEND) { onFavorite(contact.id) }
            },
            onAllTransactions = {
                dispatch(HomeAction.ALL_TRANSACTIONS, onAllTransactions)
            },
            onTransaction = { id ->
                dispatch(HomeAction.TRANSACTION_DETAIL) { onTransaction(id) }
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun HomeContent(
    profile: UserProfile,
    balanceMinor: Long,
    capabilities: AppCapabilities,
    favorites: List<Contact>,
    recent: List<Transaction>,
    checklist: StarterChecklist,
    onStartChat: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
    onBills: () -> Unit,
    onAirtime: () -> Unit,
    onBank: () -> Unit,
    onMobileMoney: () -> Unit,
    onRequest: () -> Unit,
    onKyc: () -> Unit,
    onNotifications: () -> Unit,
    onFavorite: (Contact) -> Unit,
    onAllTransactions: () -> Unit,
    onTransaction: (String) -> Unit,
) {
    val walletEnabled = capabilities.enabled(KitFeature.WALLETS)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KitAvatar(profile.name, size = 40.dp, avatarUrl = profile.avatarUrl)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Good afternoon,",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            profile.name.substringBefore(" "),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    IconButton(
                        onClick = onNotifications,
                        modifier = Modifier.testTag(HomeAction.NOTIFICATIONS.testTag),
                    ) {
                        Icon(Icons.Rounded.Notifications, contentDescription = "Notifications")
                    }
                    IconButton(
                        onClick = onScan,
                        modifier = Modifier.testTag(HomeAction.SCAN_QR.testTag),
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan QR")
                    }
                }
            }

            item {
                BalanceCard(
                    balanceMinor = balanceMinor,
                    balanceAvailable = walletEnabled,
                    onSend = onSend,
                    onReceive = onReceive,
                    onRequest = onRequest,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            // Identity has exactly one home surface: the checklist row below. The old
            // standalone card is gone — the checklist derives from live state, so an
            // identity that later regresses simply brings the checklist itself back.

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickAction(
                        Icons.Rounded.Receipt,
                        "Pay bills",
                        onBills,
                        Modifier.weight(1f).testTag(HomeAction.PAY_BILLS.testTag),
                    )
                    QuickAction(
                        Icons.Rounded.SimCard,
                        "Airtime",
                        onAirtime,
                        Modifier.weight(1f).testTag(HomeAction.BUY_AIRTIME.testTag),
                    )
                    QuickAction(
                        Icons.Rounded.AccountBalance,
                        "Bank",
                        onBank,
                        Modifier.weight(1f).testTag(HomeAction.BANK.testTag),
                    )
                    QuickAction(
                        Icons.Rounded.PhoneAndroid,
                        "Mobile money",
                        onMobileMoney,
                        Modifier.weight(1f).testTag(HomeAction.MOBILE_MONEY.testTag),
                    )
                }
            }

            if (favorites.isNotEmpty()) {
                item {
                    SectionHeader("Favorites")
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        favorites.forEach { contact ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .testTag("${HomeAction.FAVORITE_SEND.testTag}-${contact.id}")
                                    .clickable { onFavorite(contact) }
                                    .padding(6.dp),
                            ) {
                                KitAvatar(contact.name, size = 52.dp, avatarUrl = contact.avatarUrl)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    contact.name.substringBefore(" "),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            // The new-account starter checklist sits immediately before Recent activity
            // and retires itself the moment all three steps are done. Every step is read
            // from real account state and fails closed, so an account still loading shows
            // steps as not-yet-done rather than blocking home from rendering.
            if (!checklist.allComplete) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Get started",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            StarterChecklistPolicy.progressLabel(checklist),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(StarterStep.entries.size) { index ->
                    val step = StarterStep.entries[index]
                    StarterChecklistRow(
                        step = step,
                        completed = checklist.completed(step),
                        detail = when (step) {
                            // The same anti-resubmission phrasing the identity card used:
                            // a check already with a reviewer reads as "in review", never
                            // as an invitation to submit again.
                            StarterStep.VERIFY_IDENTITY ->
                                identityPromptFor(checklist.identityState)?.detail
                            StarterStep.SEND_FIRST_MESSAGE ->
                                "Say hello over encrypted chat."
                            StarterStep.MAKE_FIRST_TRANSACTION ->
                                "Send money, or pay a bill or airtime."
                        },
                        // The identity row keeps the tag the standalone card carried, so
                        // it is unmistakably the one identity surface home has.
                        testTag = when (step) {
                            StarterStep.VERIFY_IDENTITY -> HomeAction.VERIFY_IDENTITY.testTag
                            StarterStep.SEND_FIRST_MESSAGE ->
                                HomeAction.START_FIRST_CHAT.testTag
                            StarterStep.MAKE_FIRST_TRANSACTION ->
                                "home-starter-first-transaction"
                        },
                        onClick = when (step) {
                            StarterStep.VERIFY_IDENTITY -> onKyc
                            StarterStep.SEND_FIRST_MESSAGE -> onStartChat
                            StarterStep.MAKE_FIRST_TRANSACTION -> onSend
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    "Recent activity",
                    actionLabel = "See all",
                    onAction = onAllTransactions,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            items(recent.size) { i ->
                TransactionRow(
                    tx = recent[i],
                    onClick = { onTransaction(recent[i].id) },
                    modifier = Modifier.testTag(
                        "${HomeAction.TRANSACTION_DETAIL.testTag}-${recent[i].id}",
                    ),
                )
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

/**
 * One row of the starter checklist. A completed step shows its check and stops being a
 * button; an incomplete one navigates to the screen where it is actually done.
 */
@Composable
private fun StarterChecklistRow(
    step: StarterStep,
    completed: Boolean,
    detail: String?,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !completed, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (completed) "Done" else "Not done yet",
            tint = if (completed) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyLarge)
            if (!completed && detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the home card should say about identity, or null when it should not be there at all. */
internal data class IdentityPrompt(val title: String, val detail: String)

/**
 * The identity card, phrased for the state the account is actually in.
 *
 * A check that is already with a reviewer is reported as such rather than as an invitation to
 * start another one: being told to "verify your identity" while a submitted check sits in review
 * is what led people to submit again, and again. An unreadable status shows nothing, because the
 * only honest thing to say about it is nothing.
 */
internal fun identityPromptFor(status: String): IdentityPrompt? =
    identityPromptFor(kycVerificationStateOf(status))

internal fun identityPromptFor(state: KycVerificationState): IdentityPrompt? =
    when (state) {
        KycVerificationState.VERIFIED, KycVerificationState.UNKNOWN -> null
        KycVerificationState.IN_REVIEW -> IdentityPrompt(
            title = "Verification in review",
            detail = "We're checking what you sent. Tap for the latest.",
        )
        KycVerificationState.ACTION_NEEDED -> IdentityPrompt(
            title = "Finish verifying your identity",
            detail = "The last check couldn't be completed. Tap to try again.",
        )
        KycVerificationState.NOT_STARTED -> IdentityPrompt(
            title = "Verify your identity",
            detail = "Continue securely with Didit to access regulated Kit Pay services.",
        )
    }

internal fun shouldPromptForIdentityVerification(status: String): Boolean =
    identityPromptFor(status) != null

@Composable
private fun BalanceCard(
    balanceMinor: Long,
    balanceAvailable: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hidden by rememberSaveable { mutableStateOf(false) }
    val colors = KitTheme.colors

    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    listOf(colors.balanceCardStart, colors.balanceCardEnd)
                )
            )
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Wallet balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !balanceAvailable -> "—"
                        hidden -> "••••••••"
                        else -> Money.format(balanceMinor)
                    },
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                )
            }
            IconButton(onClick = { hidden = !hidden }) {
                Icon(
                    if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = if (hidden) "Show balance" else "Hide balance",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BalanceAction(
                Icons.AutoMirrored.Rounded.CallMade,
                "Send",
                onSend,
                Modifier.weight(1f).testTag(HomeAction.SEND_MONEY.testTag),
                prominent = true,
            )
            BalanceAction(
                Icons.AutoMirrored.Rounded.CallReceived,
                "Receive",
                onReceive,
                Modifier.weight(1f).testTag(HomeAction.RECEIVE_MONEY.testTag),
            )
            BalanceAction(
                Icons.Rounded.RequestPage,
                "Request",
                onRequest,
                Modifier.weight(1f).testTag(HomeAction.REQUEST_MONEY.testTag),
            )
        }
    }
}

@Composable
private fun BalanceAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    val bg = if (prominent) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.12f)
    val fg = if (prominent) MaterialTheme.colorScheme.onSecondary else Color.White
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    KitWalletTheme {
        HomeDashboard(
            profile = UserProfile(DemoData.USER_NAME, DemoData.USER_PHONE, "@amina", "KYC verified • Level 2"),
            balanceMinor = DemoData.WALLET_BALANCE_MINOR,
            capabilities = AppCapabilities(
                features = mapOf(
                    "wallets" to true,
                    "internal_transfers" to true,
                    "payment_requests" to true,
                    "merchant_payments" to true,
                    "qr_payments" to true,
                    "bills" to true,
                    "airtime" to true,
                    "bank_transfers" to true,
                    "mobile_money" to true,
                    "notifications" to true,
                ),
                loaded = true,
                qrScannerClientReady = true,
            ),
            favorites = DemoData.contacts.filter { it.favorite },
            recent = DemoData.transactions.take(5),
            snackbarHostState = remember { SnackbarHostState() },
            onSend = {}, onReceive = {}, onScan = {}, onBills = {}, onAirtime = {},
            onBank = {}, onRequest = {}, onAllTransactions = {}, onTransaction = {},
            onMobileMoney = {}, onKyc = {}, onFavorite = {},
        )
    }
}
