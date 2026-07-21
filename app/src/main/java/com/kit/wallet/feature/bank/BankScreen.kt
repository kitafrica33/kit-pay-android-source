package com.kit.wallet.feature.bank

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.SectionHeader
import com.kit.wallet.ui.components.StatusChip
import com.kit.wallet.ui.components.TransactionRow
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BankCapability
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.BankOperationKind
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.eligibleBankBeneficiaries
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankScreen(
    onBack: () -> Unit,
    viewModel: BankViewModel = hiltViewModel(),
) {
    val beneficiaries by viewModel.beneficiaries.collectAsStateWithLifecycle()
    val transfers by viewModel.bankTransfers.collectAsStateWithLifecycle()
    val banks by viewModel.banks.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var operation by remember { mutableStateOf<BankOperationKind?>(null) }
    var addingAccount by remember { mutableStateOf(false) }
    val linkableBanks = banks.filter { it.supports(BankCapability.ACCOUNT_VERIFICATION) }
    val depositBeneficiaries = eligibleBankBeneficiaries(
        BankOperationKind.DEPOSIT,
        banks,
        beneficiaries,
    )
    val withdrawalBeneficiaries = eligibleBankBeneficiaries(
        BankOperationKind.WITHDRAWAL,
        banks,
        beneficiaries,
    )
    val transferBeneficiaries = eligibleBankBeneficiaries(
        BankOperationKind.TRANSFER,
        banks,
        beneficiaries,
    )
    val selectedBeneficiaries = operation?.let { selected ->
        eligibleBankBeneficiaries(selected, banks, beneficiaries)
    }.orEmpty()

    LaunchedEffect(addingAccount, linkableBanks) {
        if (addingAccount && linkableBanks.isEmpty()) addingAccount = false
    }
    LaunchedEffect(operation, selectedBeneficiaries) {
        if (operation != null && selectedBeneficiaries.isEmpty()) operation = null
    }
    BankContent(
        beneficiaries,
        transfers,
        onBack,
        depositAvailable = depositBeneficiaries.isNotEmpty(),
        withdrawalAvailable = withdrawalBeneficiaries.isNotEmpty(),
        transferAvailable = transferBeneficiaries.isNotEmpty(),
        accountLinkingAvailable = linkableBanks.isNotEmpty(),
        onDeposit = { viewModel.clearError(); operation = BankOperationKind.DEPOSIT },
        onWithdraw = { viewModel.clearError(); operation = BankOperationKind.WITHDRAWAL },
        onTransfer = { viewModel.clearError(); operation = BankOperationKind.TRANSFER },
        onAdd = { viewModel.clearError(); addingAccount = true },
    )

    if (addingAccount && linkableBanks.isNotEmpty()) {
        AddBankAccountSheet(
            banks = linkableBanks,
            busy = busy,
            error = error,
            onDismiss = { if (!busy) addingAccount = false },
            onAdd = { bankId, account, label, kind ->
                viewModel.addAccount(bankId, account, label, kind) { addingAccount = false }
            },
        )
    }
    operation?.let { selected ->
        if (selectedBeneficiaries.isEmpty()) return@let
        BankOperationSheet(
            operation = selected,
            beneficiaries = selectedBeneficiaries,
            busy = busy,
            error = error,
            onDismiss = { if (!busy) operation = null },
            onSubmit = { beneficiaryId, amountMinor, pin ->
                viewModel.operate(selected, beneficiaryId, amountMinor, pin) { operation = null }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankContent(
    beneficiaries: List<Beneficiary>,
    transfers: List<Transaction>,
    onBack: () -> Unit,
    depositAvailable: Boolean,
    withdrawalAvailable: Boolean,
    transferAvailable: Boolean,
    accountLinkingAvailable: Boolean,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onTransfer: () -> Unit,
    onAdd: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bank") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (depositAvailable || withdrawalAvailable) {
                item {
                    Row(Modifier.padding(horizontal = 20.dp)) {
                        if (depositAvailable) {
                            BankAction(
                                Icons.Rounded.SouthWest,
                                "Deposit",
                                "From bank to Kit Pay",
                                Modifier.weight(1f),
                                onDeposit,
                            )
                        }
                        if (depositAvailable && withdrawalAvailable) {
                            Spacer(Modifier.width(12.dp))
                        }
                        if (withdrawalAvailable) {
                            BankAction(
                                Icons.Rounded.NorthEast,
                                "Withdraw",
                                "From Kit Pay to bank",
                                Modifier.weight(1f),
                                onWithdraw,
                            )
                        }
                    }
                }
            }
            if (transferAvailable) {
                item {
                    BankAction(
                        Icons.Rounded.SwapHoriz,
                        "Bank transfer",
                        "Send to a linked beneficiary",
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        onTransfer,
                    )
                }
            }
            if (!depositAvailable && !withdrawalAvailable && !transferAvailable) {
                item {
                    Text(
                        "No bank money-movement actions are available for your linked accounts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            item {
                SectionHeader(
                    "Linked accounts",
                    actionLabel = if (accountLinkingAvailable) "+ Add" else null,
                    onAction = if (accountLinkingAvailable) onAdd else null,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(beneficiaries.size) { i ->
                val b = beneficiaries[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.AccountBalance,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.bank, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${b.name} • ${b.accountMasked}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (b.verified) {
                        StatusChip(
                            "Verified",
                            KitTheme.colors.successContainer,
                            KitTheme.colors.onSuccessContainer,
                        )
                    } else {
                        StatusChip(
                            "Pending",
                            KitTheme.colors.warningContainer,
                            KitTheme.colors.warning,
                        )
                    }
                }
            }
            item { SectionHeader("Recent transfers", Modifier.padding(top = 6.dp)) }
            items(transfers.size) { i ->
                TransactionRow(tx = transfers[i])
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BankAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBankAccountSheet(
    banks: List<BankInstitution>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String) -> Unit,
) {
    var bankId by remember(banks) { mutableStateOf(banks.firstOrNull()?.id.orEmpty()) }
    var account by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("own") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Link a bank account", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            banks.forEach { bank ->
                FilterChip(
                    selected = bank.id == bankId,
                    onClick = { bankId = bank.id },
                    label = { Text("${bank.name} • ${bank.currency}") },
                )
            }
            Row {
                FilterChip(
                    selected = kind == "own",
                    onClick = { kind = "own" },
                    label = { Text("My account") },
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = kind == "third_party",
                    onClick = { kind = "third_party" },
                    label = { Text("Beneficiary") },
                )
            }
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("Account number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Account label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(error)
            com.kit.wallet.ui.components.KitGreenButton(
                text = "Verify & link account",
                loading = busy,
                enabled = bankId.isNotBlank() && account.length >= 4 && label.isNotBlank(),
                onClick = { onAdd(bankId, account, label, kind) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankOperationSheet(
    operation: BankOperationKind,
    beneficiaries: List<Beneficiary>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, Long, String) -> Unit,
) {
    var beneficiaryId by remember(beneficiaries) {
        mutableStateOf(beneficiaries.firstOrNull()?.id.orEmpty())
    }
    var amount by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amount) ?: 0L
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(
                when (operation) {
                    BankOperationKind.DEPOSIT -> "Deposit from bank"
                    BankOperationKind.WITHDRAWAL -> "Withdraw to bank"
                    BankOperationKind.TRANSFER -> "Send bank transfer"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            if (beneficiaries.isEmpty()) {
                Text(
                    if (operation.requiresOwnAccount) {
                        "Link and verify an account that belongs to you first."
                    } else {
                        "Link and verify a beneficiary first."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            beneficiaries.forEach { beneficiary ->
                FilterChip(
                    selected = beneficiary.id == beneficiaryId,
                    onClick = { beneficiaryId = beneficiary.id },
                    label = { Text("${beneficiary.bank} • ${beneficiary.accountMasked}") },
                )
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { character -> character.isDigit() || character == '.' } },
                label = { Text("Amount (${Money.SYMBOL})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
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
            com.kit.wallet.ui.components.KitGreenButton(
                text = when (operation) {
                    BankOperationKind.DEPOSIT -> "Request deposit"
                    BankOperationKind.WITHDRAWAL -> "Request withdrawal"
                    BankOperationKind.TRANSFER -> "Send bank transfer"
                },
                loading = busy,
                enabled = beneficiaryId.isNotBlank() && amountMinor > 0 && pin.length == 4,
                onClick = { onSubmit(beneficiaryId, amountMinor, pin) },
            )
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Text(
            error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        Spacer(Modifier.height(8.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun BankPreview() {
    KitWalletTheme {
        BankContent(
            beneficiaries = DemoData.beneficiaries,
            transfers = DemoData.transactions.filter {
                it.type == TxType.BANK_IN || it.type == TxType.BANK_OUT
            },
            onBack = {},
            depositAvailable = true,
            withdrawalAvailable = true,
            transferAvailable = true,
            accountLinkingAvailable = true,
            onDeposit = {},
            onWithdraw = {},
            onTransfer = {},
            onAdd = {},
        )
    }
}
