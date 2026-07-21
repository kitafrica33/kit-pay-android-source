package com.kit.wallet.feature.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.EmptyState
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.StatusChip
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    txId: String,
    onBack: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val current = state) {
        TransactionDetailUiState.Loading -> TransactionDetailPlaceholder(
            loading = true,
            onBack = onBack,
        )
        TransactionDetailUiState.NotFound -> TransactionDetailPlaceholder(
            loading = false,
            onBack = onBack,
        )
        is TransactionDetailUiState.Ready -> TransactionDetailContent(
            tx = current.transaction,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailPlaceholder(loading: Boolean, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    title = "Transaction unavailable",
                    body = "This transaction could not be found. It may no longer be available.",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailContent(tx: Transaction, onBack: () -> Unit) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            KitAvatar(tx.counterparty, size = 64.dp)
            Spacer(Modifier.height(12.dp))
            Text(tx.counterparty, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                Money.format(tx.amountMinor, signed = true),
                style = MaterialTheme.typography.displaySmall,
                color = if (tx.amountMinor > 0) KitTheme.colors.moneyIn
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            when (tx.status) {
                TxStatus.COMPLETED -> StatusChip(
                    "Completed",
                    KitTheme.colors.successContainer,
                    KitTheme.colors.onSuccessContainer,
                )
                TxStatus.PENDING -> StatusChip(
                    "Pending",
                    KitTheme.colors.warningContainer,
                    KitTheme.colors.warning,
                )
                TxStatus.FAILED -> StatusChip(
                    "Failed",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Spacer(Modifier.height(24.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    DetailRow("Date", "${tx.dateGroup}, ${tx.time}")
                    DetailRow("Reference", tx.reference)
                    DetailRow("Type", tx.type.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (tx.note != null) DetailRow("Note", tx.note)
                    DetailRow("Fee", "Free")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 7.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun TxDetailPreview() {
    KitWalletTheme { TransactionDetailContent(DemoData.transactions.first(), onBack = {}) }
}
