package com.kit.wallet.feature.wallet

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitKeypad
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.ui.components.SectionHeader
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.theme.KitWalletTheme

private enum class SendStep { PICK_RECIPIENT, ENTER_AMOUNT, SUCCESS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendMoneyScreen(
    initialContactId: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: SendMoneyViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val balanceMinor by viewModel.balanceMinor.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val lastSent by viewModel.lastSent.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    SendMoneyContent(
        initialContactId = initialContactId,
        contacts = contacts,
        balanceMinor = balanceMinor,
        sending = sending,
        lastSent = lastSent,
        error = error,
        onBack = onBack,
        onDone = onDone,
        onSend = viewModel::send,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendMoneyContent(
    initialContactId: String?,
    contacts: List<Contact>,
    balanceMinor: Long,
    sending: Boolean,
    lastSent: Transaction?,
    error: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onSend: (Contact, Long, String?, String, () -> Unit) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(SendStep.PICK_RECIPIENT) }
    var recipientId by rememberSaveable { mutableStateOf<String?>(null) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var confirmSheet by rememberSaveable { mutableStateOf(false) }
    var initialContactHandled by rememberSaveable(initialContactId) { mutableStateOf(false) }

    LaunchedEffect(initialContactId, contacts) {
        if (!initialContactHandled && recipientId == null && !initialContactId.isNullOrBlank()) {
            val initialRecipient = contacts.firstOrNull { contact ->
                contact.id == initialContactId && contact.canReceiveKitTransfer()
            }
            if (initialRecipient != null) {
                recipientId = initialRecipient.id
                step = SendStep.ENTER_AMOUNT
                initialContactHandled = true
            } else if (contacts.isNotEmpty()) {
                // A stale or forged route argument must not select an ineligible recipient.
                initialContactHandled = true
            }
        }
    }

    val recipient = contacts.find { it.id == recipientId }
    val amountMinor = Money.parseMinor(amountText) ?: 0L

    Scaffold(
        topBar = {
            if (step != SendStep.SUCCESS) {
                TopAppBar(
                    title = {
                        Text(
                            when (step) {
                                SendStep.PICK_RECIPIENT -> "Send money"
                                else -> recipient?.name ?: "Amount"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (step == SendStep.ENTER_AMOUNT) step = SendStep.PICK_RECIPIENT
                            else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        AnimatedContent(step, label = "sendStep", modifier = Modifier.padding(padding)) { s ->
            when (s) {
                SendStep.PICK_RECIPIENT -> RecipientPicker(
                    contacts = contacts,
                    onPick = {
                        recipientId = it.id
                        step = SendStep.ENTER_AMOUNT
                    },
                )
                SendStep.ENTER_AMOUNT -> AmountEntry(
                    recipient = recipient,
                    amountText = amountText,
                    balanceMinor = balanceMinor,
                    note = note,
                    onNote = { note = it },
                    onKey = { c ->
                        amountText = when {
                            c == '.' && amountText.contains('.') -> amountText
                            amountText.length >= 9 -> amountText
                            else -> amountText + c
                        }
                    },
                    onBackspace = { amountText = amountText.dropLast(1) },
                    onContinue = { confirmSheet = true },
                    canContinue = amountMinor > 0,
                )
                SendStep.SUCCESS -> SendSuccess(
                    recipient = recipient,
                    transaction = lastSent,
                    amountMinor = amountMinor,
                    onDone = onDone,
                )
            }
        }
    }

    if (confirmSheet && recipient != null) {
        var paymentPin by rememberSaveable { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { if (!sending) confirmSheet = false }) {
            ConfirmPayment(
                recipient = recipient,
                amountMinor = amountMinor,
                note = note,
                sending = sending,
                paymentPin = paymentPin,
                error = error,
                onPaymentPin = { paymentPin = it.filter(Char::isDigit).take(4) },
                onConfirm = {
                    onSend(recipient, amountMinor, note, paymentPin) {
                        confirmSheet = false
                        step = SendStep.SUCCESS
                    }
                },
            )
        }
    }
}

internal fun Contact.canReceiveKitTransfer(): Boolean =
    id.isNotBlank() && isKitUser && !receivingWalletId.isNullOrBlank()

@Composable
private fun RecipientPicker(contacts: List<Contact>, onPick: (Contact) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = contacts.filter {
        it.name.contains(query, ignoreCase = true) || it.phone.contains(query)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = { Text("Name, phone or @kittag") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
        }
        item { SectionHeader("Favorites") }
        item {
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                contacts.filter { it.favorite }.forEach { c ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(
                                enabled = c.canReceiveKitTransfer(),
                                onClick = { onPick(c) },
                            )
                            .padding(4.dp),
                    ) {
                        KitAvatar(c.name, size = 52.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(c.name.substringBefore(" "), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        item { SectionHeader("All contacts", Modifier.padding(top = 8.dp)) }
        items(results.size) { i ->
            val c = results[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = c.canReceiveKitTransfer(),
                        onClick = { onPick(c) },
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KitAvatar(c.name, size = 46.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        c.phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!c.isKitUser) {
                    Text(
                        "Invite",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AmountEntry(
    recipient: Contact?,
    amountText: String,
    balanceMinor: Long,
    note: String,
    onNote: (String) -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onContinue: () -> Unit,
    canContinue: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        if (recipient != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitAvatar(recipient.name, size = 32.dp)
                Spacer(Modifier.width(8.dp))
                Text(recipient.phone, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (amountText.isEmpty()) "${Money.SYMBOL} 0"
            else "${Money.SYMBOL} $amountText",
            style = MaterialTheme.typography.displayMedium,
            color = if (amountText.isEmpty()) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Balance: ${Money.format(balanceMinor)} • No fees on Kit Pay",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = note,
            onValueChange = onNote,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            placeholder = { Text("Add a note (optional)") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
        )
        Spacer(Modifier.height(16.dp))
        KitKeypad(onKey = onKey, onBackspace = onBackspace, accessory = '.')
        Spacer(Modifier.height(16.dp))
        KitGreenButton(
            text = "Review & send",
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ConfirmPayment(
    recipient: Contact,
    amountMinor: Long,
    note: String,
    sending: Boolean,
    paymentPin: String,
    error: String?,
    onPaymentPin: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Confirm payment", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        KitAvatar(recipient.name, size = 64.dp)
        Spacer(Modifier.height(10.dp))
        Text(recipient.name, style = MaterialTheme.typography.titleMedium)
        Text(
            recipient.phone,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(Money.format(amountMinor), style = MaterialTheme.typography.displaySmall)
        if (note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "“$note”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Row(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Fee",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("Free", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = paymentPin,
            onValueChange = onPaymentPin,
            label = { Text("Wallet PIN") },
            supportingText = { Text("Required once to authorize this exact payment") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            )
        }
        KitGreenButton(
            text = "Send ${Money.format(amountMinor)}",
            icon = Icons.Rounded.Lock,
            loading = sending,
            onClick = onConfirm,
            enabled = paymentPin.length == 4,
        )
    }
}

@Composable
private fun SendSuccess(
    recipient: Contact?,
    transaction: Transaction?,
    amountMinor: Long,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Sent!", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "${Money.format(amountMinor)} is on its way to ${recipient?.name ?: "your contact"}.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (transaction != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Ref ${transaction.reference} • ${transaction.dateGroup}, ${transaction.time}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.weight(1f))
        if (transaction != null) {
            KitOutlinedButton(
                text = "Share receipt",
                onClick = {
                    launchTextShare(
                        context = context,
                        chooserTitle = "Share Kit Pay receipt",
                        text = receiptShareText(recipient?.name, amountMinor, transaction),
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
        }
        KitGreenButton(text = "Done", onClick = onDone)
        Spacer(Modifier.height(28.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipientPickerPreview() {
    KitWalletTheme {
        RecipientPicker(contacts = DemoData.contacts, onPick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SendSuccessPreview() {
    KitWalletTheme {
        SendSuccess(
            recipient = DemoData.contacts[1],
            transaction = DemoData.transactions.first(),
            amountMinor = 2_500_000,
            onDone = {},
        )
    }
}
