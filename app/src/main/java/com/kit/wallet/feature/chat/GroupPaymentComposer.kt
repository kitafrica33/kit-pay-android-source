package com.kit.wallet.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kit.wallet.data.messaging.GroupPaymentAudience
import com.kit.wallet.data.messaging.GroupPaymentSplitMode
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.data.messaging.KitPaymentMessage
import com.kit.wallet.data.repository.GroupPaymentDraftPolicy
import com.kit.wallet.feature.auth.PaymentApproval
import com.kit.wallet.ui.model.ChatMember

/**
 * What the conversation hands back when a group payment has been filled in and approved.
 *
 * Deliberately the raw answers rather than a built request: the same policy the ViewModel checks
 * against the wallet is the one that turns them into a request, so a screen can never talk the app
 * into a payment the policy would have refused.
 */
internal typealias GroupPaymentSendHandler = (
    splitMode: GroupPaymentSplitMode,
    audience: GroupPaymentAudience,
    selected: List<GroupPaymentDraftPolicy.Member>,
    totalInput: String,
    customAmounts: Map<String, String>,
    note: String?,
    paymentPin: String,
    onSent: () -> Unit,
) -> Unit

/**
 * Filling in one payment to the group: who is being paid, and how the money is divided between
 * them.
 *
 * The two questions are kept apart because they answer different things. *Everyone* or *some
 * people* decides who the announcement names; *split evenly* or *decide each amount* decides what
 * the group is allowed to be told. A custom split has no total anybody but the sender may see, so
 * choosing it also forces the members to be picked one by one — there is no per-member figure to
 * enter for a roster this device has not enumerated.
 */
@Composable
internal fun GroupPaymentComposerDialog(
    members: List<ChatMember>,
    currencyCode: String,
    sending: Boolean,
    error: String?,
    biometricsAvailable: Boolean,
    onDismiss: () -> Unit,
    onSend: GroupPaymentSendHandler,
) {
    // Only other people can be paid: a payment to yourself is not one, and the server refuses it.
    val payable = remember(members) { members.filterNot { it.isSelf } }
    var audience by remember { mutableStateOf(GroupPaymentAudience.ALL) }
    var splitMode by remember { mutableStateOf(GroupPaymentSplitMode.EVEN) }
    var total by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    val customAmounts: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }

    val selected = remember(audience, payable, picked.toList()) {
        val chosen = if (audience == GroupPaymentAudience.ALL) {
            payable
        } else {
            payable.filter { it.userId in picked }
        }
        chosen.map { GroupPaymentDraftPolicy.Member(userId = it.userId, name = it.name) }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Pay the group") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Each member claims their own share here, in this chat. Anything nobody " +
                        "claims comes back to you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = audience == GroupPaymentAudience.ALL,
                        enabled = !sending && splitMode == GroupPaymentSplitMode.EVEN,
                        onClick = { audience = GroupPaymentAudience.ALL },
                        label = { Text("Everyone") },
                    )
                    FilterChip(
                        selected = audience == GroupPaymentAudience.SELECTED,
                        enabled = !sending,
                        onClick = { audience = GroupPaymentAudience.SELECTED },
                        label = { Text("Choose members") },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = splitMode == GroupPaymentSplitMode.EVEN,
                        enabled = !sending,
                        onClick = { splitMode = GroupPaymentSplitMode.EVEN },
                        label = { Text("Split evenly") },
                    )
                    FilterChip(
                        selected = splitMode == GroupPaymentSplitMode.CUSTOM,
                        enabled = !sending,
                        onClick = {
                            splitMode = GroupPaymentSplitMode.CUSTOM
                            // Nothing to type an amount into until the members are named.
                            audience = GroupPaymentAudience.SELECTED
                        },
                        label = { Text("Decide each amount") },
                    )
                }

                if (audience == GroupPaymentAudience.SELECTED) {
                    Text(
                        "Who are you paying?",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    payable.forEach { member ->
                        val checked = member.userId in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    enabled = !sending,
                                    onValueChange = { on ->
                                        if (on) {
                                            if (picked.size < GroupPaymentDraftPolicy.MAX_RECIPIENTS) {
                                                picked += member.userId
                                            }
                                        } else {
                                            picked -= member.userId
                                            customAmounts -= member.userId
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                member.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (checked && splitMode == GroupPaymentSplitMode.CUSTOM) {
                            OutlinedTextField(
                                value = customAmounts[member.userId].orEmpty(),
                                onValueChange = { customAmounts[member.userId] = it },
                                enabled = !sending,
                                singleLine = true,
                                label = { Text("${member.name}'s share ($currencyCode)") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, bottom = 4.dp),
                            )
                        }
                    }
                }

                if (splitMode == GroupPaymentSplitMode.EVEN) {
                    OutlinedTextField(
                        value = total,
                        onValueChange = { total = it },
                        enabled = !sending,
                        singleLine = true,
                        label = { Text("Total to divide ($currencyCode)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(KitGroupPaymentMessage.MAX_NOTE_LENGTH) },
                    enabled = !sending,
                    singleLine = true,
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.width(0.dp))
                PaymentApproval(
                    actionLabel = "Send to ${recipientCountLabel(selected.size)}",
                    biometricsAvailable = biometricsAvailable,
                    busy = sending,
                    error = error,
                    // The policy has the wallet's balance and the currency's scale, and this
                    // screen has neither. All that is checked here is that there is something to
                    // approve at all.
                    enabled = selected.isNotEmpty(),
                    onApprove = { pin ->
                        onSend(
                            splitMode,
                            audience,
                            selected,
                            total,
                            customAmounts.toMap(),
                            note.trim().ifBlank { null },
                            pin,
                            onDismiss,
                        )
                    },
                    pinSubtitle = "Authorizes this group payment from your wallet.",
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Collects the optional reason before a share goes back to the sender.
 *
 * Declining sends back only what this member never took, and needs no approval. Returning the
 * unclaimed shares moves other people's money, so it is approved like any other payment.
 */
@Composable
internal fun GroupPaymentAnswerDialog(
    returningUnclaimed: Boolean,
    sending: Boolean,
    error: String?,
    biometricsAvailable: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String?, paymentPin: String) -> Unit,
) {
    var reason by remember(returningUnclaimed) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = {
            Text(if (returningUnclaimed) "Return what is unclaimed" else "Decline your share")
        },
        text = {
            Column {
                Text(
                    if (returningUnclaimed) {
                        "Every share nobody has taken comes back to your wallet. Shares already " +
                            "taken stay where they are."
                    } else {
                        "Your share goes back to the sender straight away. Nobody else's share " +
                            "is affected."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(0.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(KitPaymentMessage.MAX_REASON_LENGTH) },
                    enabled = !sending,
                    label = { Text("Reason (optional)") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (returningUnclaimed) {
                    PaymentApproval(
                        actionLabel = "Return the unclaimed shares",
                        biometricsAvailable = biometricsAvailable,
                        busy = sending,
                        error = error,
                        onApprove = { pin -> onConfirm(reason.trim().ifBlank { null }, pin) },
                        pinSubtitle = "Authorizes returning the unclaimed shares to your wallet.",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!returningUnclaimed) {
                TextButton(
                    enabled = !sending,
                    onClick = { onConfirm(reason.trim().ifBlank { null }, "") },
                ) {
                    Text(if (sending) "Sending…" else "Decline")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun recipientCountLabel(count: Int): String = when (count) {
    0 -> "the group"
    1 -> "1 member"
    else -> "$count members"
}
