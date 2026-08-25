package com.kit.wallet.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.backup.MessageBackupFrequency
import com.kit.wallet.ui.theme.KitGreen600
import com.kit.wallet.ui.theme.KitWalletTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBackupScreen(
    onBack: () -> Unit,
    viewModel: ChatBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbars = remember { SnackbarHostState() }
    var confirmingRestore by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var enteringRecoveryCode by remember { mutableStateOf(false) }

    // Google's consent screen is a PendingIntent, so it is launched through the activity-result
    // API rather than started directly: that is what delivers the grant back to this screen.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.consentReturned(result.data) }

    LaunchedEffect(state.consent) {
        val consent = state.consent ?: return@LaunchedEffect
        viewModel.consentLaunched()
        consentLauncher.launch(IntentSenderRequest.Builder(consent.intentSender).build())
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        // Coming back from Google's account settings can mean the grant was revoked there, so the
        // screen re-reads rather than trusting what it drew before the user left.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message, state.error) {
        val note = state.error ?: state.message ?: return@LaunchedEffect
        snackbars.showSnackbar(note)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats & backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = 32.dp,
            ),
        ) {
            item { ExplainerCard() }

            if (!state.configured) {
                item { UnavailableCard() }
                return@LazyColumn
            }

            if (!state.connected) {
                item {
                    ConnectCard(
                        connecting = state.task == ChatBackupTask.CONNECTING,
                        onConnect = viewModel::connect,
                    )
                }
                return@LazyColumn
            }

            item {
                BackupNowCard(
                    state = state,
                    onBackUpNow = viewModel::backUpNow,
                )
            }
            item {
                RecoveryKeyCard(
                    state = state,
                    onReveal = viewModel::revealRecoveryCode,
                )
            }
            item {
                ScheduleCard(
                    state = state,
                    onFrequency = viewModel::setFrequency,
                    onUnmetered = viewModel::setRequiresUnmeteredNetwork,
                )
            }
            item {
                RestoreCard(
                    state = state,
                    onRestore = { confirmingRestore = true },
                    onRestoreWithCode = { enteringRecoveryCode = true },
                )
            }
            item {
                DeleteCard(
                    state = state,
                    onDelete = { confirmingDelete = true },
                    onDisconnect = viewModel::disconnect,
                )
            }
        }
    }

    state.recoveryCode?.let { code ->
        RecoveryCodeDialog(
            code = code,
            confirmed = state.recoveryCodeConfirmed,
            onConfirm = viewModel::confirmRecoveryCodeSaved,
            onDismiss = viewModel::hideRecoveryCode,
        )
    }

    if (confirmingRestore) {
        AlertDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text("Restore messages?") },
            text = {
                Text(
                    "Messages already on this phone are kept. The backup only fills in what is " +
                        "missing, and nothing here is overwritten.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRestore = false
                        viewModel.restore()
                    },
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRestore = false }) { Text("Cancel") }
            },
        )
    }

    if (enteringRecoveryCode) {
        RecoveryCodeEntryDialog(
            onRestore = { code ->
                enteringRecoveryCode = false
                viewModel.restore(code)
            },
            onDismiss = { enteringRecoveryCode = false },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete backup and key?") },
            text = {
                Text(
                    "This deletes the encrypted file from Google Drive and the key on this phone. " +
                        "Chats already on this phone stay here, and automatic backups turn off. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.deleteBackup()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ExplainerCard() {
    BackupCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.EnhancedEncryption,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text("End-to-end encrypted", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Backups are encrypted on this phone before they reach Google Drive, and they go to " +
                "a private folder only Kit Pay can open. Neither Kit Pay nor Google can read your " +
                "messages. Your recovery code is the only way back in, so keep it somewhere safe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun UnavailableCard() {
    BackupCard {
        Text("Not available on this phone", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Drive backup needs Google Play services, which this phone does not have. Your " +
                "messages stay on this device and are not backed up anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectCard(connecting: Boolean, onConnect: () -> Unit) {
    BackupCard {
        Text("Connect Google Drive", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Google asks which account to use and whether to allow it. Kit Pay never sees your " +
                "Google password, is never told which account you picked, and can only reach the " +
                "private folder it creates — not your documents, photos or anything else in Drive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            label = if (connecting) "Asking Google…" else "Connect Google Drive",
            busy = connecting,
            enabled = !connecting,
            onClick = onConnect,
        )
    }
}

@Composable
private fun BackupNowCard(state: ChatBackupUiState, onBackUpNow: () -> Unit) {
    BackupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (state.everBackedUp) Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload,
                contentDescription = null,
                tint = KitGreen600,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Last backup", style = MaterialTheme.typography.titleSmall)
                Text(
                    state.lastBackupAt?.let(::formatBackupMoment) ?: "Never backed up",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val detail = listOfNotNull(
                    state.lastBackupMessageCount?.let { "$it messages" },
                    state.lastBackupBytes?.let(::formatByteSize),
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            label = if (state.task == ChatBackupTask.BACKING_UP) {
                "Encrypting and uploading…"
            } else {
                "Back up now"
            },
            busy = state.task == ChatBackupTask.BACKING_UP,
            enabled = !state.busy,
            onClick = onBackUpNow,
        )
    }
}

@Composable
private fun RecoveryKeyCard(state: ChatBackupUiState, onReveal: () -> Unit) {
    BackupCard(
        color = if (state.recoveryCodeConfirmed) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.recoveryCodeConfirmed) "Recovery code saved" else "Save your recovery code",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (state.recoveryCodeConfirmed) {
                        "You will need it to restore on a new phone."
                    } else {
                        "Without it, a backup cannot be opened on a new phone. Nobody can reissue it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onReveal, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.recoveryCodeConfirmed) "Show code again" else "Show recovery code")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(
    state: ChatBackupUiState,
    onFrequency: (MessageBackupFrequency) -> Unit,
    onUnmetered: (Boolean) -> Unit,
) {
    BackupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("Automatic backup", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(12.dp))
        val options = MessageBackupFrequency.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, frequency ->
                SegmentedButton(
                    selected = state.frequency == frequency,
                    onClick = { onFrequency(frequency) },
                    enabled = !state.busy,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(frequency.label, maxLines = 1)
                }
            }
        }
        AnimatedVisibility(
            visible = state.frequency != MessageBackupFrequency.OFF,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only on Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Keeps backups off your mobile data bundle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.requiresUnmeteredNetwork,
                        onCheckedChange = onUnmetered,
                        enabled = !state.busy,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Backups run ${state.frequency.label.lowercase(Locale.ROOT)} while Kit Pay " +
                        "is online and the battery is not low.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RestoreCard(
    state: ChatBackupUiState,
    onRestore: () -> Unit,
    onRestoreWithCode: () -> Unit,
) {
    BackupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Restore, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Restore from Google Drive", style = MaterialTheme.typography.titleSmall)
                Text(
                    state.available?.let { found ->
                        listOfNotNull(
                            formatBackupMoment(found.createdAt),
                            found.byteSize.takeIf { it > 0 }?.let(::formatByteSize),
                        ).joinToString(" · ")
                    } ?: "No backup found in this Google account",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRestore,
            enabled = !state.busy && state.available != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.task == ChatBackupTask.RESTORING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Restoring…")
            } else {
                Text("Restore messages")
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onRestoreWithCode,
            enabled = !state.busy && state.available != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restore with a recovery code")
        }
    }
}

@Composable
private fun DeleteCard(
    state: ChatBackupUiState,
    onDelete: () -> Unit,
    onDisconnect: () -> Unit,
) {
    BackupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Delete backup and key", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Removes the encrypted file from Drive and the key from this phone. Chats on " +
                        "this phone stay here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onDelete,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.task == ChatBackupTask.DELETING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Deleting…")
            } else {
                Text("Delete backup & key", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onDisconnect,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Disconnect Google account, keep the backup")
        }
    }
}

@Composable
private fun RecoveryCodeDialog(
    code: String,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your recovery code") },
        text = {
            Column {
                Text(
                    "Write this down and keep it somewhere only you can reach. It is the only " +
                        "way to open your backup on a new phone, and Kit Pay cannot reissue it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        code,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (confirmed) "Done" else "I have written it down")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RecoveryCodeEntryDialog(onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter your recovery code") },
        text = {
            Column {
                Text(
                    "Type the code exactly as you wrote it down. Spaces and dashes do not matter.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRestore(typed) },
                enabled = typed.isNotBlank(),
            ) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = KitGreen600,
            contentColor = Color.White,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun BackupCard(
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

private val BACKUP_MOMENT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.getDefault())

internal fun formatBackupMoment(instant: Instant): String =
    BACKUP_MOMENT.format(instant.atZone(ZoneId.systemDefault()))

/** Decimal units, because that is what a phone's storage screen shows the user. */
internal fun formatByteSize(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "%.0f KB".format(bytes / 1_000.0)
    bytes < 1_000_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.2f GB".format(bytes / 1_000_000_000.0)
}

@Preview(showBackground = true)
@Composable
private fun ChatBackupPreview() {
    KitWalletTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExplainerCard()
            BackupNowCard(
                state = ChatBackupUiState(
                    connected = true,
                    lastBackupAt = Instant.ofEpochMilli(1_756_000_000_000),
                    lastBackupBytes = 2_400_000,
                    lastBackupMessageCount = 4_812,
                ),
                onBackUpNow = {},
            )
        }
    }
}
