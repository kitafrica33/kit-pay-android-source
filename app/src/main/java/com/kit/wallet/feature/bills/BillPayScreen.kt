package com.kit.wallet.feature.bills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
    val selectedProvider = provider
    if (selectedProvider == null) {
        BillProviderUnavailable(onBack = onBack, error = error)
        return
    }
    BillPayContent(
        provider = selectedProvider,
        paying = paying,
        error = error,
        onBack = onBack,
        onPay = { account, amountMinor, pin -> viewModel.pay(account, amountMinor, pin, onDone) },
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
    onBack: () -> Unit,
    onPay: (String, Long, String) -> Unit,
) {
    var account by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var paymentPin by rememberSaveable { mutableStateOf("") }

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
                supportingText = { Text("We'll verify the account name before you pay.") },
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
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = paymentPin,
                onValueChange = { paymentPin = it.filter(Char::isDigit).take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Wallet PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = error != null,
                shape = MaterialTheme.shapes.medium,
            )
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            KitGreenButton(
                text = "Pay bill",
                loading = paying,
                onClick = {
                    val amountMinor = Money.parseMinor(amount) ?: return@KitGreenButton
                    onPay(account, amountMinor, paymentPin)
                },
                enabled = account.isNotBlank() &&
                    (Money.parseMinor(amount) ?: 0L) > 0 && paymentPin.length == 4,
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
            onBack = {},
            onPay = { _, _, _ -> },
        )
    }
}
