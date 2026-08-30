package com.kit.wallet.feature.funding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.repository.FinancialOperationQuote
import com.kit.wallet.feature.auth.PaymentApproval
import com.kit.wallet.feature.auth.rememberBiometricApprovalAvailable
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.ui.components.VerifiedAccountName
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.MobileMoneyNetwork
import com.kit.wallet.ui.model.MobileMoneyVerificationState
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.TopUpRequirement
import com.kit.wallet.ui.theme.KitTheme

/**
 * The sheet a payment falls back to when the wallet cannot cover it.
 *
 * Hosted by every screen that spends money. It says how far short the wallet is, moves that much in
 * from an account the person owns, and calls [onFunded] only once the balance itself has caught up
 * — at which point the host reopens its own approval step and the payment goes through as normal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopUpSheet(
    viewModel: TopUpViewModel,
    onDismiss: () -> Unit,
    onFunded: () -> Unit,
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    ModalBottomSheet(
        // Swiping the sheet away mid-submission would leave money moving with nothing watching it,
        // so while a top-up is in flight the way out is the button that says so.
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        TopUpSheetContent(viewModel = viewModel, onDismiss = onDismiss, onFunded = onFunded)
    }
}

/**
 * The same thing without a sheet of its own, for screens that are already showing one.
 *
 * Two stacked bottom sheets is a worse answer than one sheet that changes what it is about, and it
 * keeps the payment behind it composed, so the amount and account someone typed are still there
 * when they come back to approve it.
 */
@Composable
fun TopUpSheetContent(
    viewModel: TopUpViewModel,
    onDismiss: () -> Unit,
    onFunded: () -> Unit,
) {
    val requirement by viewModel.requirement.collectAsStateWithLifecycle()
    val need = requirement ?: return
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val quote by viewModel.quote.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val adding by viewModel.addingSource.collectAsStateWithLifecycle()
    val networks by viewModel.networks.collectAsStateWithLifecycle()
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val banks by viewModel.depositBanks.collectAsStateWithLifecycle()
    val biometricsAvailable = rememberBiometricApprovalAvailable()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp)
            .navigationBarsPadding(),
    ) {
        when (adding) {
            null -> TopUpBody(
                need = need,
                stage = stage,
                sources = sources,
                selectedId = selectedId,
                quote = quote,
                busy = busy,
                error = error,
                biometricsAvailable = biometricsAvailable,
                onSelect = viewModel::select,
                onAdd = viewModel::addSource,
                onReview = viewModel::review,
                onBack = viewModel::back,
                onConfirm = viewModel::confirm,
                onKeepWaiting = viewModel::keepWaiting,
                onFunded = onFunded,
                onDismiss = onDismiss,
            )
            TopUpChannel.MOBILE_MONEY -> AddMobileMoneySourceForm(
                networks = networks,
                verification = verification,
                busy = busy,
                error = error,
                onBack = { viewModel.addSource(null) },
                onSubmit = viewModel::addMobileMoneySource,
            )
            TopUpChannel.BANK -> AddBankSourceForm(
                banks = banks,
                busy = busy,
                error = error,
                onBack = { viewModel.addSource(null) },
                onSubmit = viewModel::addBankSource,
            )
        }
    }
}

@Composable
private fun TopUpBody(
    need: TopUpRequirement,
    stage: TopUpStage,
    sources: List<TopUpSource>,
    selectedId: String?,
    quote: FinancialOperationQuote?,
    busy: Boolean,
    error: String?,
    biometricsAvailable: Boolean,
    onSelect: (String) -> Unit,
    onAdd: (TopUpChannel) -> Unit,
    onReview: () -> Unit,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit,
    onKeepWaiting: () -> Unit,
    onFunded: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (stage) {
        TopUpStage.Funded -> TopUpFunded(need, onFunded)
        TopUpStage.Waiting -> TopUpWaiting(need)
        TopUpStage.StillMoving -> TopUpStillMoving(need, busy, onKeepWaiting, onDismiss)
        else -> {
            Shortfall(need)
            Spacer(Modifier.height(20.dp))
            if (stage == TopUpStage.Review && quote != null) {
                TopUpReview(
                    need = need,
                    quote = quote,
                    source = sources.firstOrNull { it.id == selectedId },
                    busy = busy,
                    error = error,
                    biometricsAvailable = biometricsAvailable,
                    onBack = onBack,
                    onConfirm = onConfirm,
                )
            } else {
                ChooseSource(
                    need = need,
                    sources = sources,
                    selectedId = selectedId,
                    busy = busy,
                    error = error,
                    onSelect = onSelect,
                    onAdd = onAdd,
                    onReview = onReview,
                )
            }
        }
    }
}

/** The arithmetic, spelled out: what the payment needs, what is there, and the difference. */
@Composable
private fun Shortfall(need: TopUpRequirement) {
    Text("Not enough in your wallet", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        "This payment needs ${need.money(need.requiredMinor)} and your wallet holds " +
            "${need.money(need.balanceMinor)}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.large,
            )
            .padding(16.dp),
    ) {
        Text(
            "You are short by",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(need.money(need.shortfallMinor), style = MaterialTheme.typography.headlineMedium)
        if (need.topUpMinor != need.shortfallMinor) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Topping up ${need.money(need.topUpMinor)} — rounded up to a whole " +
                    "${need.currencyCode}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChooseSource(
    need: TopUpRequirement,
    sources: List<TopUpSource>,
    selectedId: String?,
    busy: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onAdd: (TopUpChannel) -> Unit,
    onReview: () -> Unit,
) {
    Text("Top up from", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Only accounts of your own can be used — money is being pulled in, not sent out.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    if (sources.isEmpty()) {
        Text(
            "You have no ${need.currencyCode} account linked yet. Add one to top up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    sources.forEach { source ->
        SourceRow(
            source = source,
            selected = source.id == selectedId,
            onClick = { onSelect(source.id) },
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TopUpChannel.entries.forEach { channel ->
            FilterChip(
                selected = false,
                onClick = { onAdd(channel) },
                enabled = !busy,
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                label = { Text(channel.label) },
            )
        }
    }
    ErrorText(error)
    Spacer(Modifier.height(12.dp))
    KitGreenButton(
        text = "Continue",
        loading = busy,
        enabled = selectedId != null,
        onClick = onReview,
    )
}

@Composable
private fun SourceRow(source: TopUpSource, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitAvatar(
            source.title,
            size = 40.dp,
            avatarUrl = source.avatarUrl,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            VerifiedAccountName(
                name = source.title,
                verification = source.accountVerification,
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (source.channel) {
                        TopUpChannel.MOBILE_MONEY -> Icons.Rounded.PhoneAndroid
                        TopUpChannel.BANK -> Icons.Rounded.AccountBalance
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    source.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun TopUpReview(
    need: TopUpRequirement,
    quote: FinancialOperationQuote,
    source: TopUpSource?,
    busy: Boolean,
    error: String?,
    biometricsAvailable: Boolean,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = !busy) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Choose another account")
        }
        Text("Confirm top-up", style = MaterialTheme.typography.titleMedium)
    }
    if (source != null) {
        Text(
            "From ${source.title} • ${source.detail}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    QuoteRow("Into your wallet", quote.money(quote.recipientAmountMinor))
    QuoteRow(
        "Fee",
        if (!quote.feesKnown) "Confirmed by your provider" else quote.money(quote.feesMinor),
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    QuoteRow("Total charged", quote.money(quote.customerDebitMinor), emphasised = true)
    Spacer(Modifier.height(4.dp))
    Text(
        "Your wallet will then hold ${need.money(need.balanceMinor + quote.recipientAmountMinor)}, " +
            "enough for this payment.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    PaymentApproval(
        actionLabel = "Approve & top up ${quote.money(quote.customerDebitMinor)}",
        biometricsAvailable = biometricsAvailable,
        busy = busy,
        error = error,
        onApprove = onConfirm,
        pinSubtitle = "Authorizes moving ${quote.money(quote.customerDebitMinor)} into your wallet.",
    )
}

@Composable
private fun QuoteRow(label: String, value: String, emphasised: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (emphasised) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TopUpWaiting(need: TopUpRequirement) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Waiting for your top-up", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Approve the request on your phone if your provider asks. We will take you back to " +
                "your payment as soon as ${need.money(need.topUpMinor)} reaches your wallet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TopUpFunded(need: TopUpRequirement, onFunded: () -> Unit) {
    // Nothing here is a guess: this is drawn only after the wallet balance was re-read and covers
    // the payment, so "topped up" is a statement about the balance, not about the request.
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).background(KitTheme.colors.successContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = KitTheme.colors.onSuccessContainer,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Wallet topped up", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your wallet now covers ${need.money(need.requiredMinor)}. Approve the payment to " +
                "finish sending it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        KitGreenButton(text = "Continue to payment", onClick = onFunded)
    }
}

@Composable
private fun TopUpStillMoving(
    need: TopUpRequirement,
    busy: Boolean,
    onKeepWaiting: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Still on its way", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "${need.money(need.topUpMinor)} has not reached your wallet yet. Nothing has gone " +
                "wrong — providers sometimes take a few minutes. Do not send it again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        KitGreenButton(text = "Keep waiting", loading = busy, onClick = onKeepWaiting)
        Spacer(Modifier.height(10.dp))
        KitOutlinedButton(text = "Close", enabled = !busy, onClick = onDismiss)
    }
}

/**
 * Adding a mobile money account to top up from.
 *
 * Deliberately not the mobile money screen's own form: there is no beneficiary option here, because
 * a top-up can only pull from a number that belongs to you.
 */
@Composable
private fun AddMobileMoneySourceForm(
    networks: List<MobileMoneyNetwork>,
    verification: MobileMoneyVerificationState?,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSubmit: (String, String, String) -> Unit,
) {
    var networkCode by remember(networks) { mutableStateOf(networks.firstOrNull()?.code.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = !busy) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Text("Add your mobile money", style = MaterialTheme.typography.titleMedium)
    }
    Text(
        "We verify the number with your provider before saving it.",
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
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } },
        label = { Text("Mobile money number") },
        supportingText = { Text("Include the country code, for example 256…") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = label,
        onValueChange = { label = it.take(100) },
        label = { Text("Account label") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (busy && verification != null) {
        Text(
            when (verification.status.lowercase()) {
                "verified" -> "Verified as ${verification.accountName.orEmpty()}"
                "failed", "rejected" -> verification.failureMessage ?: "Verification failed"
                else -> "Checking ${verification.phoneNumberMasked}…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    ErrorText(error)
    Spacer(Modifier.height(12.dp))
    KitGreenButton(
        text = "Verify & save",
        loading = busy,
        enabled = networkCode.isNotBlank() && phone.length >= 9 && label.isNotBlank(),
        onClick = { onSubmit(networkCode, phone, label) },
    )
}

/** Adding a bank account to top up from; own accounts only, for the same reason. */
@Composable
private fun AddBankSourceForm(
    banks: List<BankInstitution>,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSubmit: (String, String, String) -> Unit,
) {
    var bankId by remember(banks) { mutableStateOf(banks.firstOrNull()?.id.orEmpty()) }
    var account by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = !busy) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Text("Add your bank account", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
    if (banks.isEmpty()) {
        Text(
            "No bank here supports topping up in this currency yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    banks.forEach { bank ->
        FilterChip(
            selected = bank.id == bankId,
            onClick = { bankId = bank.id },
            label = { Text(bank.name) },
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = account,
        onValueChange = { account = it.filter(Char::isDigit) },
        label = { Text("Account number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = label,
        onValueChange = { label = it.take(100) },
        label = { Text("Account label") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(12.dp))
    KitGreenButton(
        text = "Verify & link",
        loading = busy,
        enabled = bankId.isNotBlank() && account.length >= 4 && label.isNotBlank(),
        onClick = { onSubmit(bankId, account, label) },
    )
}

@Composable
private fun ErrorText(error: String?) {
    if (error.isNullOrBlank()) return
    Text(
        error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private fun TopUpRequirement.money(amountMinor: Long): String =
    Money.format(amountMinor, currencyCode, currencyScale)

private fun FinancialOperationQuote.money(amountMinor: Long): String =
    Money.format(amountMinor, currencyCode, currencyScale)
