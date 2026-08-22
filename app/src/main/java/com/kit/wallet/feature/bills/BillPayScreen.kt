package com.kit.wallet.feature.bills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillPayScreen(
    providerId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: BillPayViewModel = hiltViewModel(),
) {
    val provider by viewModel.provider.collectAsStateWithLifecycle()
    val paying by viewModel.paying.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val quote by viewModel.quote.collectAsStateWithLifecycle()
    val selectedProvider = provider
    if (selectedProvider == null) {
        BillProviderUnavailable(onBack = onBack, error = error)
        return
    }
    BillPayContent(
        provider = selectedProvider,
        paying = paying,
        error = error,
        quote = quote,
        onBack = onBack,
        onQuoteInvalidated = viewModel::invalidateQuote,
        onReview = viewModel::review,
        onPay = { pin -> viewModel.pay(pin, onDone) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillProviderUnavailable(onBack: () -> Unit, error: String?) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pay bill") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Text(
            error ?: "Loading bill provider…",
            color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(padding).padding(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillPayContent(
    provider: BillProvider,
    paying: Boolean,
    error: String?,
    quote: FinancialOperationQuote?,
    onBack: () -> Unit,
    onQuoteInvalidated: () -> Unit,
    onReview: (String, Long) -> Unit,
    onPay: (String) -> Unit,
) {
    var account by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var paymentPin by rememberSaveable { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amount) ?: 0L

    // Only a quote for exactly these details may be approved; edits require a fresh review.
    val reviewedQuote = quote?.takeIf {
        it.operationType == "bill_payment" && it.productId == provider.id &&
            it.destinationId == account && it.amountMinor == amountMinor
    }
    LaunchedEffect(account, amountMinor) { onQuoteInvalidated() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider.name) },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text(
                provider.category,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(provider.accountHint) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                supportingText = { Text("We'll show the exact fee before you approve.") },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount (${Money.SYMBOL})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            if (reviewedQuote != null) {
                Spacer(Modifier.height(16.dp))
                ProviderQuoteSummary(reviewedQuote)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = paymentPin,
                    onValueChange = { paymentPin = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Wallet PIN (optional with biometrics)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = error != null,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            KitGreenButton(
                text = if (reviewedQuote == null) {
                    "Review amount and fees"
                } else {
                    "Pay ${Money.format(
                        reviewedQuote.customerDebitMinor,
                        reviewedQuote.currencyCode,
                        reviewedQuote.currencyScale,
                    )}"
                },
                loading = paying,
                onClick = {
                    if (reviewedQuote == null) onReview(account, amountMinor)
                    else onPay(paymentPin)
                },
                enabled = account.isNotBlank() && amountMinor > 0 &&
                    (reviewedQuote == null || paymentPin.isEmpty() || paymentPin.length == 4),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BillPayPreview() {
    KitWalletTheme {
        BillPayContent(
            DemoData.billProviders.first(),
            paying = false,
            error = null,
            quote = null,
            onBack = {},
            onQuoteInvalidated = {},
            onReview = { _, _ -> },
            onPay = {},
        )
    }
}
