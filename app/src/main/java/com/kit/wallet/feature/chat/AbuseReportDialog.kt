package com.kit.wallet.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kit.wallet.data.repository.AbuseReportContext
import com.kit.wallet.data.repository.AbuseReportContract
import com.kit.wallet.data.repository.AbuseReportReason
import com.kit.wallet.data.repository.AbuseReportRequest
import com.kit.wallet.data.repository.AbuseReportSelectionPolicy
import com.kit.wallet.data.repository.AbuseReportTarget
import com.kit.wallet.ui.model.Message

/**
 * Full-screen because the consented plaintext must be readable before it leaves the device.
 * Local form fields intentionally use remember rather than rememberSaveable: report text never
 * enters Android's Activity state bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AbuseReportDialog(
    reportedName: String,
    context: AbuseReportContext,
    target: AbuseReportTarget,
    messages: List<Message>,
    reportingAvailable: Boolean,
    state: AbuseReportUiState,
    onSubmit: (AbuseReportRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember(target) { mutableStateOf<AbuseReportReason?>(null) }
    var details by remember(target) { mutableStateOf("") }
    var selectedIds by remember(target) { mutableStateOf(emptySet<String>()) }
    var plaintextConsent by remember(target) { mutableStateOf(false) }
    var pendingRequest by remember(target) { mutableStateOf<AbuseReportRequest?>(null) }
    var confirmSend by remember(target) { mutableStateOf(false) }
    val candidates = remember(messages, context, target) {
        AbuseReportSelectionPolicy.candidates(messages, context, target)
    }
    LaunchedEffect(selectedIds) {
        // Consent describes an exact selection; any edit requires a fresh opt-in.
        plaintextConsent = false
    }

    fun prepareRequest(): AbuseReportRequest? = runCatching {
        AbuseReportRequest.create(
            context = context,
            target = target,
            reason = checkNotNull(reason),
            reporterNote = details,
            selectedMessages = AbuseReportSelectionPolicy.selectedPayloads(
                selectedIds = selectedIds,
                candidates = candidates,
            ),
            shareSelectedMessagePlaintext = plaintextConsent,
        )
    }.getOrNull()

    Dialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            if (state.receipt != null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Report received",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Text(
                        "Thank you. Kit Pay's moderation team received your report. " +
                            "Reporting does not block this account.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "Reference ${state.receipt.id}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Button(onClick = onDismiss, modifier = Modifier.padding(top = 24.dp)) {
                        Text("Done")
                    }
                }
            } else {
                Scaffold(
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    if (target is AbuseReportTarget.MessageTarget) {
                                        "Report message"
                                    } else {
                                        "Report account"
                                    },
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    enabled = !state.submitting,
                                    onClick = onDismiss,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Cancel report",
                                    )
                                }
                            },
                        )
                    },
                    bottomBar = {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            state.error?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            Button(
                                enabled = reason != null && reportingAvailable &&
                                    !state.submitting &&
                                    (selectedIds.isEmpty() || plaintextConsent),
                                onClick = {
                                    prepareRequest()?.let {
                                        pendingRequest = it
                                        confirmSend = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.submitting) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                }
                                Text(if (state.submitting) "Submitting…" else "Review and send")
                            }
                        }
                    },
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text("Report $reportedName", fontWeight = FontWeight.Bold)
                            Text(
                                "Your report goes to authorized Kit Pay moderators. Reporting " +
                                    "does not block this account; use Blocked accounts separately " +
                                    "if you want to stop messages and calls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                            )
                        }
                        item {
                            Text("Reason", style = MaterialTheme.typography.titleSmall)
                        }
                        items(AbuseReportReason.entries, key = AbuseReportReason::wireValue) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { reason = item },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = reason == item, onClick = { reason = item })
                                Text(item.title)
                            }
                        }
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            OutlinedTextField(
                                value = details,
                                onValueChange = { details = AbuseReportContract.limitedNote(it) },
                                label = { Text("Details (optional)") },
                                supportingText = {
                                    Text(
                                        "${details.codePointCount(0, details.length)}/" +
                                            AbuseReportContract.MAXIMUM_NOTE_CHARACTERS,
                                    )
                                },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Write only information you want Kit Pay moderators to read.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (target is AbuseReportTarget.MessageTarget) {
                            item {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text("Reported message", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (target.messageId in selectedIds) {
                                        "The message ID and exact text shown below will be sent " +
                                            "only after you confirm plaintext sharing."
                                    } else {
                                        "The message ID will be sent without its text unless you " +
                                            "select that message below and explicitly consent."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("Message context (optional)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Kit Pay cannot decrypt this chat. Nothing is selected " +
                                    "automatically. You may choose up to five delivered text " +
                                    "messages; attachments, payment events and all other history " +
                                    "stay private.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (candidates.isEmpty()) {
                            item { Text("No delivered text messages are available to share.") }
                        } else {
                            items(candidates, key = { it.messageId }) { candidate ->
                                val selected = candidate.messageId in selectedIds
                                val selectable = AbuseReportSelectionPolicy.canSelect(
                                    candidate,
                                    selectedIds,
                                    candidates,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = selectable) {
                                        plaintextConsent = false
                                        selectedIds = if (selected) {
                                            selectedIds - candidate.messageId
                                        } else {
                                            selectedIds + candidate.messageId
                                        }
                                    }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        enabled = selectable,
                                        onCheckedChange = {
                                            plaintextConsent = false
                                            selectedIds = if (selected) {
                                                selectedIds - candidate.messageId
                                            } else {
                                                selectedIds + candidate.messageId
                                            }
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(Modifier.fillMaxWidth()) {
                                            Text(
                                                if (candidate.fromMe) {
                                                    "You"
                                                } else {
                                                    candidate.senderName?.takeIf(String::isNotBlank)
                                                        ?: reportedName
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Spacer(Modifier.weight(1f))
                                            Text(candidate.time, style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (candidate.isReportTarget) {
                                            Text(
                                                "Reported message",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        Text(
                                            candidate.plaintext,
                                            maxLines = if (selected || candidate.isReportTarget) 20 else 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedIds.isNotEmpty()) {
                            item {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        plaintextConsent = !plaintextConsent
                                    },
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Checkbox(
                                        checked = plaintextConsent,
                                        onCheckedChange = { plaintextConsent = it },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "I agree to share the ${selectedIds.size} selected " +
                                            "message${if (selectedIds.size == 1) "" else "s"} as " +
                                            "readable text with authorized moderators.",
                                    )
                                }
                            }
                        }
                        if (!reportingAvailable) {
                            item {
                                Text(
                                    "Reporting is temporarily unavailable.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false; pendingRequest = null },
            title = { Text("Send this report to Kit Pay?") },
            text = { Text(reportConfirmationCopy(target, selectedIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val request = pendingRequest ?: return@TextButton
                        confirmSend = false
                        pendingRequest = null
                        onSubmit(request)
                    },
                ) { Text("Send report", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSend = false; pendingRequest = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun reportConfirmationCopy(target: AbuseReportTarget, selectedCount: Int): String {
    if (selectedCount == 0) {
        return if (target is AbuseReportTarget.MessageTarget) {
            "Moderators will receive the reason, any details you wrote, and the reported " +
                "message ID. Message text and attachments will not be sent."
        } else {
            "Moderators will receive the reason and any details you wrote. No message text or " +
                "attachments will be sent."
        }
    }
    return "Moderators will receive the reason, any details you wrote, and $selectedCount " +
        "explicitly selected message${if (selectedCount == 1) "" else "s"} as readable text. " +
        "No other chat history or attachments will be sent."
}
