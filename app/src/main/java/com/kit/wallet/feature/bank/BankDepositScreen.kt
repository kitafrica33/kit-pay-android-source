package com.kit.wallet.feature.bank

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kit.wallet.data.remote.BankDepositProofUploader
import com.kit.wallet.data.repository.BankDeposit
import com.kit.wallet.data.repository.BankFundingAccount
import com.kit.wallet.data.repository.WalletCurrency
import com.kit.wallet.ui.components.GroupedAmountTransformation
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.StatusChip
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitTheme
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PreparedBankDepositProof(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
)

internal object BankDepositProofReader {
    suspend fun read(resolver: ContentResolver, uri: Uri): PreparedBankDepositProof =
        withContext(Dispatchers.IO) {
            val mimeType = normalizeMimeType(resolver.getType(uri), uri)
            require(mimeType in BankDepositProofUploader.ACCEPTED_MIME_TYPES) {
                "Choose a JPEG, PNG, WebP, or PDF receipt"
            }
            val filename = queryFilename(resolver, uri).take(255).ifBlank {
                if (mimeType == "application/pdf") "bank-transfer-receipt.pdf"
                else "bank-transfer-receipt.jpg"
            }
            require(filename.none(Char::isISOControl)) { "Choose a receipt with a valid filename" }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= BankDepositProofUploader.MAX_PROOF_BYTES) {
                        "Choose a receipt no larger than 10 MB"
                    }
                }
                output.toByteArray()
            } ?: error("The selected receipt could not be opened")
            require(bytes.isNotEmpty()) { "The selected receipt is empty" }
            if (mimeType == "application/pdf") {
                require(bytes.take(5).toByteArray().contentEquals("%PDF-".encodeToByteArray())) {
                    "The selected PDF is invalid"
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth > 0 && options.outHeight > 0) {
                    "The selected receipt image is invalid"
                }
            }
            PreparedBankDepositProof(bytes, filename, mimeType)
        }

    private fun normalizeMimeType(value: String?, uri: Uri): String {
        val reported = value?.lowercase()
        if (reported == "image/jpg") return "image/jpeg"
        if (reported in BankDepositProofUploader.ACCEPTED_MIME_TYPES) return requireNotNull(reported)
        return when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> reported.orEmpty()
        }
    }

    private fun queryFilename(resolver: ContentResolver, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0).orEmpty()
            else uri.lastPathSegment.orEmpty().substringAfterLast('/')
        } finally {
            cursor?.close()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BankDepositScreen(
    fundingAccounts: List<BankFundingAccount>,
    deposits: List<BankDeposit>,
    initialDepositId: String?,
    currency: WalletCurrency,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, Long, String?, (BankDeposit) -> Unit) -> Unit,
    onUpload: (String, ByteArray, String, String, (BankDeposit) -> Unit) -> Unit,
    onRefresh: (String, (BankDeposit) -> Unit) -> Unit,
    onObserve: (String?) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var depositId by remember(initialDepositId) { mutableStateOf(initialDepositId) }
    var localDeposit by remember(initialDepositId) {
        mutableStateOf(deposits.firstOrNull { it.id == initialDepositId })
    }
    val deposit = depositId?.let { id ->
        deposits.firstOrNull { it.id == id } ?: localDeposit
    }
    var accountId by remember(fundingAccounts) {
        mutableStateOf(fundingAccounts.firstOrNull()?.id.orEmpty())
    }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var proofReadError by remember { mutableStateOf<String?>(null) }
    var readingProof by remember { mutableStateOf(false) }
    var exportingPdf by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (!busy && !readingProof && !exportingPdf) onDismiss()
    }

    LaunchedEffect(fundingAccounts) {
        if (fundingAccounts.none { it.id == accountId }) {
            accountId = fundingAccounts.firstOrNull()?.id.orEmpty()
        }
    }
    LaunchedEffect(depositId) { onObserve(depositId) }
    DisposableEffect(Unit) { onDispose { onObserve(null) } }

    val proofPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && deposit != null) {
            readingProof = true
            proofReadError = null
            scope.launch {
                runCatching { BankDepositProofReader.read(context.contentResolver, uri) }
                    .onSuccess { proof ->
                        onUpload(
                            deposit.id,
                            proof.bytes,
                            proof.filename,
                            proof.mimeType,
                        ) { updated ->
                            localDeposit = updated
                            depositId = updated.id
                        }
                    }
                    .onFailure { proofReadError = it.message ?: "The receipt could not be opened" }
                readingProof = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (deposit == null) "Bank deposit" else "Deposit instructions")
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        enabled = !busy && !readingProof && !exportingPdf,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 40.dp),
        ) {
            if (deposit == null) {
                DepositCreateContent(
                    fundingAccounts = fundingAccounts,
                    accountId = accountId,
                    onAccount = { accountId = it },
                    amount = amount,
                    onAmount = { amount = sanitizeAmountInput(it, currency.scale) },
                    note = note,
                    onNote = { note = it.take(280) },
                    currency = currency,
                    busy = busy,
                    error = error,
                    onCreate = {
                        val minor = Money.parseMinor(amount, currency.scale) ?: return@DepositCreateContent
                        onCreate(accountId, minor, note.trim().ifEmpty { null }) { created ->
                            localDeposit = created
                            depositId = created.id
                        }
                    },
                )
            } else {
                DepositDetailsContent(
                    deposit = deposit,
                    busy = busy || readingProof || exportingPdf,
                    error = proofReadError ?: exportError ?: error,
                    onCopy = { value ->
                        clipboard.setText(AnnotatedString(value))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onRefresh = {
                        onRefresh(deposit.id) { updated -> localDeposit = updated }
                    },
                    onExportPdf = {
                        exportingPdf = true
                        exportError = null
                        scope.launch {
                            runCatching { BankDepositPdfExporter.exportAndShare(context, deposit) }
                                .onFailure {
                                    exportError = it.message
                                        ?: "The deposit instructions could not be exported"
                                }
                            exportingPdf = false
                        }
                    },
                    onChooseProof = {
                        proofPicker.launch(
                            arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf"),
                        )
                    },
                    onStartAnother = {
                        depositId = null
                        localDeposit = null
                        amount = ""
                        note = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun DepositCreateContent(
    fundingAccounts: List<BankFundingAccount>,
    accountId: String,
    onAccount: (String) -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    note: String,
    onNote: (String) -> Unit,
    currency: WalletCurrency,
    busy: Boolean,
    error: String?,
    onCreate: () -> Unit,
) {
    Text("Deposit by bank transfer", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        "Choose a Kit Pay receiving account and enter the exact amount. No beneficiary is needed — your unique reference links the bank payment to your wallet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(18.dp))
    Text("Receiving account", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    if (fundingAccounts.isEmpty()) {
        Text(
            "No receiving account is available for ${currency.code} yet.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    fundingAccounts.forEach { account ->
        ReceivingAccountOption(
            account = account,
            selected = account.id == accountId,
            enabled = !busy,
            onClick = { onAccount(account.id) },
        )
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = amount,
        onValueChange = onAmount,
        label = { Text("Deposit amount (${currency.code})") },
        visualTransformation = GroupedAmountTransformation,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (currency.scale == 0) KeyboardType.Number else KeyboardType.Decimal,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = note,
        onValueChange = onNote,
        label = { Text("Optional note") },
        supportingText = { Text("${note.length}/280") },
        minLines = 1,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    DepositErrorText(error)
    val amountMinor = Money.parseMinor(amount, currency.scale)
    KitGreenButton(
        text = "Get deposit instructions",
        loading = busy,
        enabled = accountId.isNotBlank() && amountMinor != null && amountMinor > 0,
        onClick = onCreate,
    )
}

@Composable
private fun DepositDetailsContent(
    deposit: BankDeposit,
    busy: Boolean,
    error: String?,
    onCopy: (String) -> Unit,
    onRefresh: () -> Unit,
    onExportPdf: () -> Unit,
    onChooseProof: () -> Unit,
    onStartAnother: () -> Unit,
) {
    val status = depositStatus(deposit.status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .background(status.container, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Icon(status.icon, contentDescription = null, tint = status.content)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(status.title, style = MaterialTheme.typography.titleLarge)
            Text(
                Money.format(
                    deposit.amountMinor,
                    deposit.currencyCode,
                    deposit.currencyScale,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = onRefresh, enabled = !busy) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh deposit status")
        }
    }
    Text(
        status.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    if (!deposit.terminal) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Your payment reference", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        deposit.reference,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onCopy(deposit.reference) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy reference")
                    }
                }
                Text(
                    "Use this exact reference so Kit Pay can match the payment to your wallet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    AccountInstructions(deposit, onCopy)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = { onCopy(bankDepositInstructionsText(deposit)) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Copy all deposit details")
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = onExportPdf,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Share, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(if (busy) "Preparing instructions…" else "Export PDF")
    }

    Spacer(Modifier.height(14.dp))
    DepositFacts(deposit, onCopy)
    Spacer(Modifier.height(14.dp))

    if (deposit.acceptsProof()) {
        Text(
            if (deposit.proof == null) "Upload payment proof" else "Replace payment proof",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Add a clear receipt image or PDF after the transfer. Staff verify it before your wallet is credited.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onChooseProof,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (busy) "Preparing receipt…" else "Choose receipt or PDF")
        }
    } else if (deposit.proof != null) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = KitTheme.colors.successContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = KitTheme.colors.onSuccessContainer)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text("Payment proof submitted", fontWeight = FontWeight.SemiBold)
                    Text(deposit.proof.filename, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    DepositErrorText(error)
    if (deposit.terminal) {
        KitGreenButton(
            text = "Start another deposit",
            loading = false,
            enabled = !busy,
            onClick = onStartAnother,
        )
    }
}

@Composable
private fun AccountInstructions(deposit: BankDeposit, onCopy: (String) -> Unit) {
    val account = deposit.fundingAccount
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AccountBalance, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Transfer to", style = MaterialTheme.typography.titleMedium)
            }
            CopyableFactRow("Bank", account.bankName, onCopy = onCopy)
            CopyableFactRow("Account name", account.accountName, onCopy = onCopy)
            CopyableFactRow(
                "Account number",
                account.accountNumber,
                monospaced = true,
                onCopy = onCopy,
            )
            account.branchName?.let { CopyableFactRow("Branch", it, onCopy = onCopy) }
            account.branchCode?.let {
                CopyableFactRow("Branch code", it, monospaced = true, onCopy = onCopy)
            }
            account.swiftCode?.let {
                CopyableFactRow("SWIFT", it, monospaced = true, onCopy = onCopy)
            }
            account.instructions?.takeIf(String::isNotBlank)?.let {
                Row(verticalAlignment = Alignment.Top) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onCopy(it) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy instructions")
                    }
                }
            }
        }
    }
}

@Composable
private fun DepositFacts(deposit: BankDeposit, onCopy: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CopyableFactRow(
                "Deposit reference",
                deposit.reference,
                monospaced = true,
                onCopy = onCopy,
            )
            FactRow("Status", depositStatus(deposit.status).title)
            deposit.bankTransactionReference?.let {
                CopyableFactRow("Bank transaction", it, true, onCopy)
            }
            deposit.completedAt?.let { FactRow("Completed", formatDepositTime(it)) }
            deposit.rejectionReason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReceivingAccountOption(
    account: BankFundingAccount,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, borderColor, MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountBalance, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.bankName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${account.label} • ${account.accountNumberMasked}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun CopyableFactRow(
    label: String,
    value: String,
    monospaced: Boolean = false,
    onCopy: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
            )
        }
        IconButton(onClick = { onCopy(value) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $label")
        }
    }
}

@Composable
private fun DepositErrorText(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(10.dp))
    Text(
        error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun FactRow(label: String, value: String, monospaced: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

internal data class BankDepositStatus(
    val title: String,
    val message: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val container: Color,
    val content: Color,
)

@Composable
internal fun depositStatus(value: String): BankDepositStatus = when (value.lowercase()) {
    "awaiting_proof" -> BankDepositStatus(
        "Awaiting receipt",
        "Complete the transfer using the exact reference, then upload your receipt.",
        Icons.Rounded.Description,
        KitTheme.colors.warningContainer,
        KitTheme.colors.warning,
    )
    "proof_submitted" -> BankDepositStatus(
        "Receipt submitted",
        "Your receipt is securely uploaded and waiting to be checked.",
        Icons.Rounded.CheckCircle,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    "awaiting_approval" -> BankDepositStatus(
        "Under review",
        "The evidence was verified and is awaiting independent approval.",
        Icons.Rounded.Refresh,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    "approved", "completed" -> BankDepositStatus(
        "Wallet credited",
        "The bank transfer was approved and the money is now in your Kit Pay wallet.",
        Icons.Rounded.CheckCircle,
        KitTheme.colors.successContainer,
        KitTheme.colors.onSuccessContainer,
    )
    "rejected" -> BankDepositStatus(
        "Deposit not approved",
        "Review the reason below before creating another deposit.",
        Icons.Rounded.Description,
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    "expired" -> BankDepositStatus(
        "Deposit expired",
        "Create a new deposit to receive a fresh reference.",
        Icons.Rounded.Refresh,
        KitTheme.colors.warningContainer,
        KitTheme.colors.warning,
    )
    else -> BankDepositStatus(
        value.replace('_', ' ').replaceFirstChar(Char::uppercase),
        "Kit Pay is checking the latest status of this bank deposit.",
        Icons.Rounded.Refresh,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

internal fun sanitizeAmountInput(value: String, scale: Int): String {
    val safeScale = scale.coerceIn(0, 9)
    val filtered = buildString {
        var usedPoint = false
        for (character in value) {
            when {
                character.isDigit() && (!usedPoint || safeScale > 0) -> append(character)
                character == '.' && !usedPoint -> {
                    usedPoint = true
                    if (safeScale > 0) append(character)
                }
            }
        }
    }
    val point = filtered.indexOf('.')
    return if (point < 0) filtered.take(30)
    else filtered.take(point + 1 + safeScale).take(31)
}

private fun formatDepositTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(value)

internal fun bankDepositInstructionsText(deposit: BankDeposit): String = buildString {
    val account = deposit.fundingAccount
    appendLine("Kit Pay bank deposit")
    appendLine("Amount: ${Money.format(deposit.amountMinor, deposit.currencyCode, deposit.currencyScale)}")
    appendLine("Reference: ${deposit.reference}")
    appendLine("Bank: ${account.bankName}")
    appendLine("Account name: ${account.accountName}")
    appendLine("Account number: ${account.accountNumber}")
    account.branchName?.takeIf(String::isNotBlank)?.let { appendLine("Branch: $it") }
    account.branchCode?.takeIf(String::isNotBlank)?.let { appendLine("Branch code: $it") }
    account.swiftCode?.takeIf(String::isNotBlank)?.let { appendLine("SWIFT / BIC: $it") }
    account.instructions?.takeIf(String::isNotBlank)?.let { appendLine("Instructions: $it") }
    append("Use the exact reference above, then upload your bank receipt in Kit Pay.")
}
