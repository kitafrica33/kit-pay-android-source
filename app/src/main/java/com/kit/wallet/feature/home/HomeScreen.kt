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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RequestPage
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onAllTransactions: () -> Unit,
    onTransaction: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val balanceMinor by viewModel.balanceMinor.collectAsStateWithLifecycle()
    val recent by viewModel.recentTransactions.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    HomeDashboard(
        profile = profile,
        balanceMinor = balanceMinor,
        capabilities = capabilities,
        favorites = favorites,
        recent = recent,
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
        onAllTransactions = onAllTransactions,
        onTransaction = onTransaction,
    )
}

@Composable
internal fun HomeDashboard(
    profile: UserProfile,
    balanceMinor: Long,
    capabilities: AppCapabilities,
    favorites: List<Contact>,
    recent: List<Transaction>,
    snackbarHostState: SnackbarHostState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
    onBills: () -> Unit,
    onAirtime: () -> Unit,
    onBank: () -> Unit,
    onMobileMoney: () -> Unit,
    onRequest: () -> Unit,
    onKyc: () -> Unit,
    onAllTransactions: () -> Unit,
    onTransaction: (String) -> Unit,
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

    Box(Modifier.fillMaxSize()) {
        HomeContent(
            profile = profile,
            balanceMinor = balanceMinor,
            capabilities = capabilities,
            favorites = favorites,
            recent = recent,
            onSend = { dispatch(HomeAction.SEND_MONEY, onSend) },
            onReceive = { dispatch(HomeAction.RECEIVE_MONEY, onReceive) },
            onScan = { dispatch(HomeAction.SCAN_QR, onScan) },
            onBills = { dispatch(HomeAction.PAY_BILLS, onBills) },
            onAirtime = { dispatch(HomeAction.BUY_AIRTIME, onAirtime) },
            onBank = { dispatch(HomeAction.BANK, onBank) },
            onMobileMoney = { dispatch(HomeAction.MOBILE_MONEY, onMobileMoney) },
            onRequest = { dispatch(HomeAction.REQUEST_MONEY, onRequest) },
            onKyc = { dispatch(HomeAction.VERIFY_IDENTITY, onKyc) },
            onFavorite = { dispatch(HomeAction.FAVORITE_SEND, onSend) },
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
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
    onBills: () -> Unit,
    onAirtime: () -> Unit,
    onBank: () -> Unit,
    onMobileMoney: () -> Unit,
    onRequest: () -> Unit,
    onKyc: () -> Unit,
    onFavorite: () -> Unit,
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
                    KitAvatar(profile.name, size = 40.dp)
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

            if (shouldPromptForIdentityVerification(profile.kycLabel)) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .fillMaxWidth()
                            .testTag(HomeAction.VERIFY_IDENTITY.testTag)
                            .clickable(onClick = onKyc),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    "Verify your identity",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    "Continue securely with Didit to access regulated Kit Pay services.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }

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
                                    .clickable(onClick = onFavorite)
                                    .padding(6.dp),
                            ) {
                                KitAvatar(contact.name, size = 52.dp)
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

internal fun shouldPromptForIdentityVerification(status: String): Boolean =
    status.trim().lowercase() !in setOf("verified", "approved", "kyc verified")

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
            onMobileMoney = {}, onKyc = {},
        )
    }
}
