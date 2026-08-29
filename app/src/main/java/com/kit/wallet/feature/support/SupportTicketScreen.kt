package com.kit.wallet.feature.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Attachment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kit.wallet.data.support.SupportDraft
import com.kit.wallet.data.support.SupportMessage
import com.kit.wallet.data.support.SupportPaymentReceipt
import com.kit.wallet.data.support.SupportSenderType
import com.kit.wallet.data.support.SupportTicket
import com.kit.wallet.feature.auth.PaymentApproval
import com.kit.wallet.feature.auth.rememberBiometricApprovalAvailable
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

private const val POLL_INTERVAL_MILLIS = 5_000L
private const val REPLY_MAX = 4_000

/**
 * One support ticket: the server-readable thread, the composer, close and
 * escalate, and — when the capability handshake allows it — a payment to the
 * company beneficiary. Everything that writes is offered only while the ticket
 * is verifiably open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportTicketScreen(
    supportPaymentsUsable: Boolean,
    companyBeneficiaryName: String?,
    onBack: () -> Unit,
    viewModel: SupportTicketViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val payment by viewModel.payment.collectAsStateWithLifecycle()
    val agentPhoto by viewModel.agentPhoto.collectAsStateWithLifecycle()

    var showCloseDialog by rememberSaveable { mutableStateOf(false) }
    var showPaymentSheet by rememberSaveable { mutableStateOf(false) }

    // Poll only while the screen is actually visible; a backgrounded app or a
    // covered entry on the back stack must not keep hitting the network.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(POLL_INTERVAL_MILLIS)
                viewModel.poll()
            }
        }
    }

    val ticket = state.ticket
    val canPay = supportPaymentsUsable &&
        companyBeneficiaryName != null &&
        ticket?.acceptsWrites == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TicketHeader(ticket, agentPhoto) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (canPay) {
                        IconButton(onClick = { showPaymentSheet = true }) {
                            Icon(
                                Icons.Rounded.Payments,
                                contentDescription = "Pay Kit Pay",
                            )
                        }
                    }
                    if (ticket?.acceptsWrites == true) {
                        IconButton(onClick = { showCloseDialog = true }) {
                            Icon(
                                Icons.Rounded.RemoveCircleOutline,
                                contentDescription = "Close ticket",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            ServerReadableNotice(Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

            when {
                state.loading && ticket == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.error != null && ticket == null -> Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }

                ticket != null -> TicketThread(
                    ticket = ticket,
                    messages = state.messages,
                    drafts = drafts,
                    onRetryDrafts = viewModel::retryDrafts,
                    onDiscardDraft = viewModel::discardDraft,
                    modifier = Modifier.weight(1f),
                )
            }

            state.actionError?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        Modifier.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearActionError) { Text("Dismiss") }
                    }
                }
            }

            if (ticket != null) {
                if (ticket.assistantActive && ticket.acceptsWrites) {
                    TextButton(
                        onClick = viewModel::escalate,
                        enabled = !state.escalating,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            if (state.escalating) {
                                "Asking for a person…"
                            } else {
                                "Talk to a human instead"
                            },
                        )
                    }
                }
                if (ticket.acceptsWrites) {
                    ReplyComposer(onSend = { body, clear -> viewModel.send(body) { clear() } })
                } else {
                    ClosedFooter(ticket)
                }
            }
        }
    }

    if (showCloseDialog && ticket != null) {
        CloseTicketDialog(
            pendingDraftCount = drafts.count { !it.failed },
            closing = state.closing,
            onClose = { discardQueued ->
                viewModel.close(discardQueued) { showCloseDialog = false }
            },
            onDismiss = { showCloseDialog = false },
        )
    }

    if (showPaymentSheet && canPay && companyBeneficiaryName != null) {
        SupportPaymentSheet(
            beneficiaryName = companyBeneficiaryName,
            state = payment,
            onReview = viewModel::reviewPayment,
            onApprove = viewModel::confirmPayment,
            onDone = {
                viewModel.dismissPayment()
                showPaymentSheet = false
            },
            onDismiss = {
                if (!payment.busy) {
                    viewModel.dismissPayment()
                    showPaymentSheet = false
                }
            },
        )
    }
}

// --- Header ---------------------------------------------------------------------

@Composable
private fun TicketHeader(
    ticket: SupportTicket?,
    agentPhoto: ImageBitmap?,
) {
    if (ticket == null) {
        Text("Support ticket")
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SupportAvatar(photo = agentPhoto, size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ticket.identityDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                SupportVerifiedBadge(ticket.identityVerified)
            }
            Text(
                when {
                    ticket.agentAlias != null -> "Agent ${ticket.agentAlias}"
                    ticket.assistantActive -> "AI assistant"
                    else -> ticket.reference
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// --- Thread ---------------------------------------------------------------------

@Composable
private fun TicketThread(
    ticket: SupportTicket,
    messages: List<SupportMessage>,
    drafts: List<SupportDraft>,
    onRetryDrafts: () -> Unit,
    onDiscardDraft: (clientMessageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Follow the newest entry as messages and drafts arrive.
    LaunchedEffect(messages.size, drafts.size) {
        val total = messages.size + drafts.size + 1
        if (total > 0) listState.scrollToItem(total - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "ticket-subject") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    ticket.subject,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${ticket.categoryName} • ${ticket.reference}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(messages.size, key = { messages[it].id }) { index ->
            MessageBubble(messages[index])
        }
        items(drafts.size, key = { "draft-" + drafts[it].clientMessageId }) { index ->
            DraftBubble(
                draft = drafts[index],
                onRetry = onRetryDrafts,
                onDiscard = { onDiscardDraft(drafts[index].clientMessageId) },
            )
        }
    }
}

@Composable
private fun MessageBubble(message: SupportMessage) {
    val fromCustomer = message.sender.type == SupportSenderType.CUSTOMER

    if (message.sender.type == SupportSenderType.SYSTEM) {
        Text(
            message.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
        return
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromCustomer) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (fromCustomer) {
                KitTheme.colors.chatBubbleMe
            } else {
                KitTheme.colors.chatBubbleOther
            },
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (fromCustomer) 14.dp else 4.dp,
                bottomEnd = if (fromCustomer) 4.dp else 14.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!fromCustomer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message.sender.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (fromCustomer) {
                                KitTheme.colors.onChatBubbleMe
                            } else {
                                KitTheme.colors.onChatBubbleOther
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        SupportVerifiedBadge(
                            message.sender.verifiedOfficialSupport,
                            size = 12.dp,
                        )
                        if (message.sender.automated) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "AI",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
                if (message.body.isNotEmpty()) {
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fromCustomer) {
                            KitTheme.colors.onChatBubbleMe
                        } else {
                            KitTheme.colors.onChatBubbleOther
                        },
                    )
                }
                if (message.hasUndisplayableAttachment) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Attachment,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Attachment can't be shown in this version of Kit Pay",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    formatMessageTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** A reply that has not reached the server yet, or that the server refused. */
@Composable
private fun DraftBubble(
    draft: SupportDraft,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = if (draft.failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    draft.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (draft.failed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (draft.failed) {
                        "Not accepted" + (draft.failureCode?.let { " ($it)" } ?: "")
                    } else {
                        "Waiting to send"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (draft.failed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Row {
                    if (!draft.failed) {
                        TextButton(onClick = onRetry) { Text("Send now") }
                    }
                    TextButton(onClick = onDiscard) { Text("Discard") }
                }
            }
        }
    }
}

// --- Composer / closed footer -----------------------------------------------------

@Composable
private fun ReplyComposer(onSend: (body: String, clear: () -> Unit) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(REPLY_MAX) },
            placeholder = { Text("Write to support…") },
            modifier = Modifier.weight(1f),
            maxLines = 5,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { onSend(text) { text = "" } },
            enabled = text.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}

@Composable
private fun ClosedFooter(ticket: SupportTicket) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "This ticket is closed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    ticket.closedAt?.let { append(formatMessageTime(it)) }
                    ticket.closedReasonCode?.let {
                        if (isNotEmpty()) append(" • ")
                        append(it)
                    }
                    if (isEmpty()) append("Open a new ticket if you still need help.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// --- Close dialog -------------------------------------------------------------------

/**
 * Closing is always an explicit choice, and queued replies never vanish
 * silently: when any are pending the person picks what happens to them.
 */
@Composable
private fun CloseTicketDialog(
    pendingDraftCount: Int,
    closing: Boolean,
    onClose: (discardQueued: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!closing) onDismiss() },
        title = { Text("Close this ticket?") },
        text = {
            Column {
                Text(
                    if (pendingDraftCount > 0) {
                        val plural = if (pendingDraftCount == 1) "reply" else "replies"
                        "You have $pendingDraftCount queued $plural that " +
                            "haven't reached support yet. A closed ticket can't " +
                            "receive them, so they will not be sent."
                    } else {
                        "You can read the conversation afterwards, but no more " +
                            "messages can be sent on a closed ticket."
                    },
                )
                if (closing) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Closing…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (pendingDraftCount > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = { onClose(true) },
                        enabled = !closing,
                    ) { Text("Discard queued & close") }
                    TextButton(
                        onClick = { onClose(false) },
                        enabled = !closing,
                    ) { Text("Close & keep them (they won't send)") }
                }
            } else {
                TextButton(onClick = { onClose(false) }, enabled = !closing) {
                    Text("Close ticket")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !closing) { Text("Cancel") }
        },
    )
}

// --- Payment sheet --------------------------------------------------------------------

/**
 * Paying Kit Pay from inside a ticket. The beneficiary is the company account
 * from the negotiated protocol — there is no payee field anywhere, and support
 * cannot request money: this sheet only ever opens from the customer's side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupportPaymentSheet(
    beneficiaryName: String,
    state: SupportPaymentUiState,
    onReview: (amountText: String, noteText: String) -> Unit,
    onApprove: (paymentPin: String) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            val receipt = state.receipt
            val review = state.review
            when {
                receipt != null -> PaymentReceiptStage(receipt, onDone)
                review != null -> PaymentConfirmStage(
                    beneficiaryName = beneficiaryName,
                    review = review,
                    busy = state.busy,
                    error = state.error,
                    onApprove = onApprove,
                    onCancel = onDismiss,
                )
                else -> PaymentEntryStage(
                    beneficiaryName = beneficiaryName,
                    error = state.error,
                    onReview = onReview,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PaymentEntryStage(
    beneficiaryName: String,
    error: String?,
    onReview: (String, String) -> Unit,
) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var noteText by rememberSaveable { mutableStateOf("") }

    Text("Pay Kit Pay", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        "This payment goes to $beneficiaryName. Support cannot ask you to " +
            "pay anyone else.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it },
        label = { Text("Amount") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = noteText,
        onValueChange = { noteText = it.take(280) },
        label = { Text("Note (optional)") },
        supportingText = { Text("${noteText.length}/280") },
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(16.dp))
    KitGreenButton(
        text = "Review payment",
        enabled = amountText.isNotBlank(),
        onClick = { onReview(amountText, noteText) },
    )
}

@Composable
private fun PaymentConfirmStage(
    beneficiaryName: String,
    review: SupportPaymentReview,
    busy: Boolean,
    error: String?,
    onApprove: (paymentPin: String) -> Unit,
    onCancel: () -> Unit,
) {
    val amount = Money.format(review.amountMinor, review.currencyCode, review.currencyScale)
    val biometricsAvailable = rememberBiometricApprovalAvailable()

    Text("Confirm payment", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(14.dp))
    Text(
        amount,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    PaymentDetailRow("To", beneficiaryName)
    review.note?.let { PaymentDetailRow("Note", it) }
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    PaymentApproval(
        actionLabel = "Pay $amount",
        biometricsAvailable = biometricsAvailable,
        busy = busy,
        error = error,
        onApprove = onApprove,
    )
    TextButton(
        onClick = onCancel,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Cancel") }
}

@Composable
private fun PaymentReceiptStage(receipt: SupportPaymentReceipt, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .background(KitTheme.colors.successContainer, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = KitTheme.colors.onSuccessContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Payment sent", style = MaterialTheme.typography.titleLarge)
        if (receipt.idempotentReplay) {
            Spacer(Modifier.height(4.dp))
            Text(
                "This payment had already gone through — you were not charged twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    PaymentDetailRow("Amount", "${receipt.currencyCode} ${receipt.amount}")
    PaymentDetailRow("To", receipt.beneficiaryName)
    PaymentDetailRow("Reference", receipt.reference)
    PaymentDetailRow("Status", receipt.status)
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    KitGreenButton(text = "Done", onClick = onDone)
}

@Composable
private fun PaymentDetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatMessageTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT))
}.getOrDefault(value)
