package com.kit.wallet.feature.bills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.ui.model.Money

/**
 * The authoritative provider quote, shown before any PIN or biometric approval: the exact amount,
 * fee and wallet debit the server will charge, plus the provider-verified destination.
 */
@Composable
internal fun ProviderQuoteSummary(quote: FinancialOperationQuote, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Review before you approve",
                style = MaterialTheme.typography.titleSmall,
            )
            quote.accountDisplay?.takeIf(String::isNotBlank)?.let { verified ->
                Spacer(Modifier.height(4.dp))
                Text(
                    verified,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            QuoteRow("Amount", Money.format(quote.amountMinor, quote.currencyCode, quote.currencyScale))
            QuoteRow("Fee", Money.format(quote.feesMinor, quote.currencyCode, quote.currencyScale))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            QuoteRow(
                "Total to pay",
                Money.format(quote.customerDebitMinor, quote.currencyCode, quote.currencyScale),
                emphasized = true,
            )
        }
    }
}

@Composable
private fun QuoteRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
