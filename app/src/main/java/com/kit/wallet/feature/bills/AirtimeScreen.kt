package com.kit.wallet.feature.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitWalletTheme

private val quickAmounts = listOf(1_000, 2_000, 5_000, 10_000, 20_000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirtimeScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AirtimeViewModel = hiltViewModel(),
) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val buying by viewModel.buying.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    AirtimeContent(
        products = products,
        ownPhone = viewModel.ownPhone,
        buying = buying,
        error = error,
        onBack = onBack,
        onBuy = { productId, phone, amountMinor, pin ->
            viewModel.buy(productId, phone, amountMinor, pin, onDone)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirtimeContent(
    products: List<BillProvider>,
    ownPhone: String,
    buying: Boolean,
    error: String?,
    onBack: () -> Unit,
    onBuy: (String, String, Long, String) -> Unit,
) {
    var productId by rememberSaveable(products) {
        mutableStateOf(products.firstOrNull()?.id.orEmpty())
    }
    var phone by rememberSaveable { mutableStateOf(ownPhone) }
    var amount by rememberSaveable { mutableStateOf("") }
    var paymentPin by rememberSaveable { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amount) ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Buy airtime") },
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
            Text("Network", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            if (products.isEmpty()) {
                Text(
                    "No RukaPay airtime network is currently available.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                products.forEach { product ->
                    FilterChip(
                        selected = product.id == productId,
                        onClick = { productId = product.id },
                        label = { Text(product.name) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { character -> character.isDigit() || character == '+' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Phone number") },
                trailingIcon = {
                    Icon(
                        Icons.Rounded.Contacts,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickAmounts.forEach { quickAmount ->
                    FilterChip(
                        selected = amount == quickAmount.toString(),
                        onClick = { amount = quickAmount.toString() },
                        label = { Text(quickAmount.toString()) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { value -> amount = value.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount (${Money.SYMBOL})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                text = if (amountMinor > 0) "Buy airtime • ${Money.format(amountMinor)}" else "Buy airtime",
                loading = buying,
                onClick = { onBuy(productId, phone, amountMinor, paymentPin) },
                enabled = productId.isNotBlank() && phone.length >= 9 &&
                    amountMinor > 0 && paymentPin.length == 4,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AirtimePreview() {
    KitWalletTheme {
        AirtimeContent(
            products = listOf(BillProvider("airtime-mtn", "MTN Airtime", "Airtime", "Phone number")),
            ownPhone = DemoData.USER_PHONE,
            buying = false,
            error = null,
            onBack = {},
            onBuy = { _, _, _, _ -> },
        )
    }
}
