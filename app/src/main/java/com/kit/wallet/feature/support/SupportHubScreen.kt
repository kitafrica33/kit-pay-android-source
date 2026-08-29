package com.kit.wallet.feature.support

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ScheduleSend
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.support.SupportDraft
import com.kit.wallet.data.support.SupportTicket
import com.kit.wallet.data.support.SupportTicketStatus
import com.kit.wallet.ui.components.EmptyState
import com.kit.wallet.ui.components.StatusChip
import com.kit.wallet.ui.theme.KitTheme

/**
 * The Help & support hub: the account's tickets newest-first, queued drafts
 * that have not reached the server yet, and the way into a new ticket. Reached
 * only from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportHubScreen(
    onBack: () -> Unit,
    onNewTicket: () -> Unit,
    onTicket: (ticketId: String) -> Unit,
    viewModel: SupportHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queuedDrafts by viewModel.queuedDrafts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & support") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTicket,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New ticket") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ServerReadableNotice() }

            if (queuedDrafts.isNotEmpty()) {
                item {
                    Text(
                        "Waiting to send",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(queuedDrafts.size, key = { "draft-" + queuedDrafts[it].clientMessageId }) {
                    QueuedTicketDraftCard(
                        draft = queuedDrafts[it],
                        onDiscard = { viewModel.discardDraft(queuedDrafts[it].clientMessageId) },
                        onRetry = { viewModel.refresh() },
                    )
                }
            }

            when {
                state.loading && state.tickets.isEmpty() -> item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                state.error != null && state.tickets.isEmpty() -> item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            state.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                }
                state.tickets.isEmpty() && queuedDrafts.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Rounded.SupportAgent,
                        title = "How can we help?",
                        body = "Open a ticket and the official Kit Pay support " +
                            "team will get back to you here.",
                        modifier = Modifier.padding(vertical = 40.dp),
                    )
                }
                else -> {
                    items(state.tickets.size, key = { state.tickets[it].id }) { index ->
                        SupportTicketCard(
                            ticket = state.tickets[index],
                            onClick = { onTicket(state.tickets[index].id) },
                        )
                    }
                    if (state.hasMore) {
                        item {
                            TextButton(
                                onClick = viewModel::loadMore,
                                enabled = !state.loadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.loadingMore) "Loading…" else "Load older tickets")
                            }
                        }
                    }
                    if (state.error != null) {
                        item {
                            Text(
                                state.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportTicketCard(ticket: SupportTicket, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ticket.subject,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                when (ticket.status) {
                    SupportTicketStatus.OPEN -> StatusChip(
                        "Open",
                        KitTheme.colors.successContainer,
                        KitTheme.colors.onSuccessContainer,
                    )
                    else -> StatusChip(
                        "Closed",
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${ticket.categoryName} • ${ticket.reference}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ticket.identityDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                SupportVerifiedBadge(ticket.identityVerified, size = 14.dp)
                if (ticket.assistantActive) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "AI assistant",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** An open-ticket draft that has not reached the server yet. */
@Composable
private fun QueuedTicketDraftCard(
    draft: SupportDraft,
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (draft.failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ScheduleSend,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    draft.subject ?: "New ticket",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (draft.failed) {
                    "Support couldn't accept this ticket" +
                        (draft.failureCode?.let { " ($it)" } ?: "")
                } else {
                    "Queued — it will be sent when you're back online"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (draft.failed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
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
