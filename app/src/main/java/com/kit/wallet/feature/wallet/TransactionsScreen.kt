package com.kit.wallet.feature.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.SectionHeader
import com.kit.wallet.ui.components.TransactionRow
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme

private val filters = listOf("All", "In", "Out", "Bills", "Bank")

internal data class TransactionActivitySummary(
    val moneyInMinor: Long,
    val moneyOutMinor: Long,
) {
    val hasActivity: Boolean
        get() = moneyInMinor > 0 || moneyOutMinor > 0

    private val total: Double
        get() = moneyInMinor.toDouble() + moneyOutMinor.toDouble()

    /** Null segments must not be passed to Compose's Modifier.weight, which requires > 0. */
    val moneyInWeight: Float?
        get() = moneyInMinor.takeIf { it > 0 }?.let { (it.toDouble() / total).toFloat() }

    val moneyOutWeight: Float?
        get() = moneyOutMinor.takeIf { it > 0 }?.let { (it.toDouble() / total).toFloat() }
}

internal fun summarizeTransactionActivity(
    transactions: List<Transaction>,
): TransactionActivitySummary {
    var moneyIn = 0L
    var moneyOut = 0L
    transactions.forEach { transaction ->
        if (transaction.amountMinor > 0) {
            moneyIn = saturatedAdd(moneyIn, transaction.amountMinor)
        } else if (transaction.amountMinor < 0) {
            val magnitude = if (transaction.amountMinor == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                -transaction.amountMinor
            }
            moneyOut = saturatedAdd(moneyOut, magnitude)
        }
    }
    return TransactionActivitySummary(moneyIn, moneyOut)
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onBack: () -> Unit,
    onTransaction: (String) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    TransactionsContent(transactions, onBack, onTransaction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionsContent(
    transactions: List<Transaction>,
    onBack: () -> Unit,
    onTransaction: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }

    val visible = transactions.filter { tx ->
        val matchesQuery = query.isBlank() ||
            tx.counterparty.contains(query, true) ||
            (tx.note ?: "").contains(query, true) ||
            tx.reference.contains(query, true)
        val matchesFilter = when (filter) {
            "In" -> tx.amountMinor > 0
            "Out" -> tx.amountMinor < 0
            "Bills" -> tx.type == TxType.BILL || tx.type == TxType.AIRTIME
            "Bank" -> tx.type == TxType.BANK_IN || tx.type == TxType.BANK_OUT
            else -> true
        }
        matchesQuery && matchesFilter
    }
    val grouped = visible.groupBy { it.dateGroup }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transactions") },
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
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    placeholder = { Text("Search name, note or reference") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    filters.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            item { MonthSummary(transactions) }

            grouped.forEach { (group, txs) ->
                item { SectionHeader(group) }
                items(txs.size) { i ->
                    TransactionRow(tx = txs[i], onClick = { onTransaction(txs[i].id) })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Simple in/out monthly summary with proportion bars. */
@Composable
private fun MonthSummary(transactions: List<Transaction>, modifier: Modifier = Modifier) {
    val summary = summarizeTransactionActivity(transactions)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("This month", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Money in",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Money.format(summary.moneyInMinor),
                        style = MaterialTheme.typography.titleMedium,
                        color = KitTheme.colors.moneyIn,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Money out",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Money.format(summary.moneyOutMinor),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (summary.hasActivity) {
                Row(Modifier.height(8.dp)) {
                    summary.moneyInWeight?.let { weight ->
                        Surface(
                            modifier = Modifier
                                .weight(weight)
                                .fillMaxSize(),
                            color = KitTheme.colors.success,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {}
                    }
                    if (summary.moneyInWeight != null && summary.moneyOutWeight != null) {
                        Spacer(Modifier.width(4.dp))
                    }
                    summary.moneyOutWeight?.let { weight ->
                        Surface(
                            modifier = Modifier
                                .weight(weight)
                                .fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {}
                    }
                }
            } else {
                Text(
                    "No money movement yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TransactionsPreview() {
    KitWalletTheme {
        TransactionsContent(DemoData.transactions, onBack = {}, onTransaction = {})
    }
}
