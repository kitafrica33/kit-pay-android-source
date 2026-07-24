package com.kit.wallet.feature.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestMoneyScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: RequestMoneyViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    RequestMoneyContent(
        contacts = contacts,
        sending = sending,
        error = error,
        onBack = onBack,
        onRequest = { from, amountMinor, note -> viewModel.request(from, amountMinor, note, onDone) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestMoneyContent(
    contacts: List<Contact>,
    sending: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRequest: (Contact, Long, String?) -> Unit,
) {
    val eligibleContacts = contacts.filter(Contact::canReceiveKitPaymentRequest)
    var selectedId by rememberSaveable {
        mutableStateOf(eligibleContacts.firstOrNull()?.id.orEmpty())
    }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val amountMinor = Money.parseMinor(amount) ?: 0L
    val selectedContact = eligibleContacts.firstOrNull { it.id == selectedId }

    LaunchedEffect(eligibleContacts) {
        if (eligibleContacts.none { it.id == selectedId }) {
            selectedId = eligibleContacts.firstOrNull()?.id.orEmpty()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Request money") },
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
                .navigationBarsPadding(),
        ) {
            Text(
                "From",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)) {
                items(eligibleContacts.size) { i ->
                    val c = eligibleContacts[i]
                    val selected = c.id == selectedId
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .testTag("request-contact-${c.id}")
                            .clickable { selectedId = c.id }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        KitAvatar(c.name, size = 56.dp, online = selected)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            c.name.substringBefore(" "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (selectedContact == null) {
                Text(
                    "No Kit Pay contact is available. Add or sync a Kit Pay contact first.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("request-contact-feedback"),
                )
            } else {
                Text(
                    "Requesting from ${selectedContact.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("request-selected-contact"),
                )
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .testTag("request-amount"),
                label = { Text("Amount (${Money.SYMBOL})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                label = { Text("What's it for?") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )

            Spacer(Modifier.weight(1f))
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "The request appears in their Kit Pay account for approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
            }
            KitGreenButton(
                text = "Send request",
                loading = sending,
                onClick = {
                    // The button cannot be enabled without both values. Re-resolve the contact
                    // from the visible eligible set so an asynchronous refresh cannot submit a
                    // hidden, removed, or non-Kit identity.
                    eligibleContacts.firstOrNull { it.id == selectedId }?.let { from ->
                        if (amountMinor > 0) {
                            onRequest(from, amountMinor, note.ifBlank { null })
                        }
                    }
                },
                enabled = selectedContact != null && amountMinor > 0,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .testTag("request-submit"),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

internal fun Contact.canReceiveKitPaymentRequest(): Boolean = isKitUser && id.isNotBlank()

@Preview(showBackground = true)
@Composable
private fun RequestMoneyPreview() {
    KitWalletTheme {
        RequestMoneyContent(
            DemoData.contacts,
            sending = false,
            error = null,
            onBack = {},
            onRequest = { _, _, _ -> },
        )
    }
}
