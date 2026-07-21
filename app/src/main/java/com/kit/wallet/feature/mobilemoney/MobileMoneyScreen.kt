package com.kit.wallet.feature.mobilemoney

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.SectionHeader
import com.kit.wallet.ui.components.StatusChip
import com.kit.wallet.ui.model.MobileMoneyAccount
import com.kit.wallet.ui.model.MobileMoneyNetwork
import com.kit.wallet.ui.model.MobileMoneyOperation
import com.kit.wallet.ui.model.MobileMoneyVerificationState
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileMoneyScreen(
    onBack: () -> Unit,
    viewModel: MobileMoneyViewModel = hiltViewModel(),
) {
    val networks by viewModel.networks.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var addingAccount by remember { mutableStateOf(false) }
    var operationAction by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mobile money") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh mobile money")
                    }
                },
            )
        },
    ) { padding ->
        MobileMoneyContent(
            accounts = accounts,
            operations = operations,
            modifier = Modifier.padding(padding),
            onCollect = {
                viewModel.clearError()
                operationAction = "collection"
            },
            onPayout = {
                viewModel.clearError()
                operationAction = "payout"
            },
            onAddAccount = {
                viewModel.clearError()
                addingAccount = true
            },
        )
    }

    if (addingAccount) {
        AddMobileMoneyAccountSheet(
            networks = networks,
            verification = verification,
            busy = busy,
            error = error,
            onDismiss = { if (!busy) addingAccount = false },
            onSubmit = { network, phone, label, kind ->
                viewModel.addAccount(network, phone, label, kind) {
                    addingAccount = false
                }
            },
        )
    }
    operationAction?.let { action ->
        MobileMoneyOperationSheet(
            action = action,
            accounts = accounts.filter { action != "collection" || it.isOwnAccount },
            busy = busy,
            error = error,
            onDismiss = { if (!busy) operationAction = null },
            onSubmit = { accountId, amountMinor, pin ->
                viewModel.operate(action, accountId, amountMinor, pin) {
                    operationAction = null
                }
            },
        )
    }
}

@Composable
private fun MobileMoneyContent(
    accounts: List<MobileMoneyAccount>,
    operations: List<MobileMoneyOperation>,
    modifier: Modifier = Modifier,
    onCollect: () -> Unit,
    onPayout: () -> Unit,
    onAddAccount: () -> Unit,
) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(
                "Move money securely between Kit Pay and your verified MTN or Airtel account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MobileMoneyAction(
                    icon = Icons.Rounded.SouthWest,
                    title = "Cash in",
                    subtitle = "Mobile money to Kit Pay",
                    onClick = onCollect,
                    modifier = Modifier.weight(1f),
                )
                MobileMoneyAction(
                    icon = Icons.Rounded.NorthEast,
                    title = "Cash out",
                    subtitle = "Kit Pay to mobile money",
                    onClick = onPayout,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            SectionHeader(
                "Saved mobile accounts",
                actionLabel = "+ Add",
                onAction = onAddAccount,
            )
        }
        if (accounts.isEmpty()) {
            item {
                EmptyPanel(
                    icon = Icons.Rounded.Add,
                    title = "No mobile money account yet",
                    detail = "Verify and save your number before cashing in or out.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else {
            items(accounts.size, key = { accounts[it].id }) { index ->
                MobileMoneyAccountRow(accounts[index])
            }
        }
        item { SectionHeader("Recent mobile money") }
        if (operations.isEmpty()) {
            item {
                Text(
                    "Your RukaPay collection and payout status will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        } else {
            items(operations.size, key = { operations[it].id }) { index ->
                MobileMoneyOperationRow(operations[index])
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun MobileMoneyAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MobileMoneyAccountRow(account: MobileMoneyAccount) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(account.label, style = MaterialTheme.typography.titleSmall)
            Text(
                "${account.networkName} • ${account.phoneNumberMasked}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                account.accountName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(
            if (account.isOwnAccount) "Mine" else "Beneficiary",
            KitTheme.colors.successContainer,
            KitTheme.colors.onSuccessContainer,
        )
    }
}

@Composable
private fun MobileMoneyOperationRow(operation: MobileMoneyOperation) {
    val successful = operation.status in setOf("completed", "succeeded")
    val failed = operation.status in setOf("failed", "reversed", "cancelled", "canceled")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (operation.action == "collection") Icons.Rounded.SouthWest else Icons.Rounded.NorthEast,
                contentDescription = null,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (operation.action == "collection") "Cash in • ${operation.networkName}"
                else "Cash out • ${operation.networkName}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                operation.failureMessage ?: operation.reference,
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${operation.currencyCode} ${decimalAmount(operation.amountMinor, operation.currencyScale)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            StatusChip(
                when {
                    successful -> "Completed"
                    failed -> "Failed"
                    else -> "Processing"
                },
                when {
                    successful -> KitTheme.colors.successContainer
                    failed -> MaterialTheme.colorScheme.errorContainer
                    else -> KitTheme.colors.warningContainer
                },
                when {
                    successful -> KitTheme.colors.onSuccessContainer
                    failed -> MaterialTheme.colorScheme.onErrorContainer
                    else -> KitTheme.colors.warning
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMobileMoneyAccountSheet(
    networks: List<MobileMoneyNetwork>,
    verification: MobileMoneyVerificationState?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit,
) {
    var networkCode by remember(networks) { mutableStateOf(networks.firstOrNull()?.code.orEmpty()) }
    var phone by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("own") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Add mobile money account", style = MaterialTheme.typography.titleLarge)
            Text(
                "RukaPay verifies the number before Kit Pay saves it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                networks.forEach { network ->
                    FilterChip(
                        selected = network.code == networkCode,
                        onClick = { networkCode = network.code },
                        label = { Text(network.name) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == "own",
                    onClick = { kind = "own" },
                    label = { Text("My account") },
                )
                FilterChip(
                    selected = kind == "third_party",
                    onClick = { kind = "third_party" },
                    label = { Text("Beneficiary") },
                )
            }
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { character -> character.isDigit() || character == '+' } },
                label = { Text("Mobile money number") },
                supportingText = { Text("Include country code, for example 256…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(100) },
                label = { Text("Account label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy && verification != null) {
                Text(
                    when (verification.status.lowercase()) {
                        "verified" -> "Verified as ${verification.accountName.orEmpty()}"
                        "failed", "rejected" -> verification.failureMessage ?: "Verification failed"
                        else -> "Checking ${verification.phoneNumberMasked} with RukaPay…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            ErrorText(error)
            KitGreenButton(
                text = "Verify & save account",
                loading = busy,
                enabled = networks.isNotEmpty() && networkCode.isNotBlank() &&
                    phone.length >= 9 && label.isNotBlank(),
                onClick = { onSubmit(networkCode, phone, label, kind) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileMoneyOperationSheet(
    action: String,
    accounts: List<MobileMoneyAccount>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, Long, String) -> Unit,
) {
    var accountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var amount by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amount) ?: 0L
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(
                if (action == "collection") "Cash in from mobile money" else "Cash out to mobile money",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            if (accounts.isEmpty()) {
                Text(
                    if (action == "collection") {
                        "Add and verify a mobile money account that belongs to you first."
                    } else {
                        "Add and verify a mobile money account first."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            accounts.forEach { account ->
                FilterChip(
                    selected = account.id == accountId,
                    onClick = { accountId = account.id },
                    label = { Text("${account.networkName} • ${account.phoneNumberMasked}") },
                )
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter(Char::isDigit) },
                label = { Text("Amount (UGX)") },
                supportingText = { Text("RukaPay supports whole-shilling amounts") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                label = { Text("Wallet PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(error)
            KitGreenButton(
                text = if (action == "collection") "Request cash in" else "Confirm cash out",
                loading = busy,
                enabled = accountId.isNotBlank() && amountMinor > 0 && pin.length == 4,
                onClick = { onSubmit(accountId, amountMinor, pin) },
            )
        }
    }
}

@Composable
private fun EmptyPanel(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error == null) {
        Spacer(Modifier.height(8.dp))
    } else {
        Text(
            error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

private fun decimalAmount(amountMinor: Long, scale: Int): String =
    java.math.BigDecimal.valueOf(amountMinor, scale).stripTrailingZeros().toPlainString()
