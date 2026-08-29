package com.kit.wallet.feature.referrals

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
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.referrals.ReferralEntry
import com.kit.wallet.data.referrals.ReferralOverview
import com.kit.wallet.data.referrals.ReferralProgramTerms
import com.kit.wallet.data.referrals.ReferralRewardStatus
import com.kit.wallet.data.referrals.ReferralShareCode
import com.kit.wallet.feature.wallet.launchTextShare
import com.kit.wallet.ui.components.EmptyState
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.StatusChip
import com.kit.wallet.ui.theme.KitTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Invite friends: the account's server-minted share link and the server's own
 * view of every referral. Policy terms, reward amounts, and statuses are all
 * rendered verbatim — this screen never computes qualification, progress, or
 * payouts (docs/support-client.md R1–R4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferralScreen(
    onBack: () -> Unit,
    viewModel: ReferralViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite friends") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && state.overview == null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            state.error != null && state.overview == null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
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
                TextButton(onClick = viewModel::refresh) { Text("Try again") }
            }

            else -> state.overview?.let { overview ->
                ReferralContent(
                    overview = overview,
                    mintingCode = state.mintingCode,
                    codeError = state.codeError,
                    onRequestCode = viewModel::requestCode,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }
}

@Composable
private fun ReferralContent(
    overview: ReferralOverview,
    mintingCode: Boolean,
    codeError: String?,
    onRequestCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val program = overview.program
            if (program != null) {
                ProgramCard(program)
            } else {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(
                        "There's no active referral program right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        item {
            val code = overview.code
            when {
                code != null -> ShareCodeCard(code)
                overview.program != null -> Column {
                    KitGreenButton(
                        text = if (mintingCode) "Getting your link…" else "Get my invite link",
                        enabled = !mintingCode,
                        loading = mintingCode,
                        onClick = onRequestCode,
                    )
                    codeError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (overview.totals.total > 0) {
            item {
                Text(
                    "You've invited ${overview.totals.total} " +
                        if (overview.totals.total == 1) "person" else "people",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (overview.referrals.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.CardGiftcard,
                    title = "No one has joined yet",
                    body = "Share your link — you'll see each friend here " +
                        "as soon as they join Kit Pay.",
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(overview.referrals.size, key = { overview.referrals[it].id }) { index ->
                ReferralRow(overview.referrals[index])
            }
        }
    }
}

/** The active policy's own numbers, shown as the server stated them. */
@Composable
private fun ProgramCard(program: ReferralProgramTerms) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Earn ${program.reward.currencyCode} ${program.reward.amount} per friend",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "A friend counts once they join with your link and keep at least " +
                    "${program.qualifyingBalance.currencyCode} " +
                    "${program.qualifyingBalance.amount} in their wallet for " +
                    "${program.qualifyingBusinessDays} business days within " +
                    "${program.windowDays} days of joining. Kit Pay confirms each " +
                    "reward — the status below is always the server's answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ShareCodeCard(code: ReferralShareCode) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                code.code,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                code.shareUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(code.shareUrl)) },
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Copy link")
                }
                OutlinedButton(
                    onClick = {
                        launchTextShare(
                            context,
                            "Invite friends to Kit Pay",
                            "Join me on Kit Pay: ${code.shareUrl}",
                        )
                    },
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun ReferralRow(entry: ReferralEntry) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.referredName ?: "Someone you invited",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ReferralStatusChip(entry)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("Joined ${formatReferralDate(entry.attributedAt)}")
                    append(" • Reward ${entry.reward.currencyCode} ${entry.reward.amount}")
                    entry.paidAt?.let { append(" • Paid ${formatReferralDate(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Exactly the server's reward state; an unknown status shows the server's own word. */
@Composable
private fun ReferralStatusChip(entry: ReferralEntry) {
    when (entry.status) {
        ReferralRewardStatus.PAID -> StatusChip(
            "Paid",
            KitTheme.colors.successContainer,
            KitTheme.colors.onSuccessContainer,
        )
        ReferralRewardStatus.QUALIFIED -> StatusChip(
            "Qualified",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        ReferralRewardStatus.PENDING -> StatusChip(
            "Pending",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReferralRewardStatus.EXPIRED -> StatusChip(
            "Expired",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReferralRewardStatus.NOT_ELIGIBLE -> StatusChip(
            "Not eligible",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReferralRewardStatus.REVERSED -> StatusChip(
            "Reversed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        ReferralRewardStatus.UNKNOWN -> StatusChip(
            entry.rawStatus,
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatReferralDate(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(value)
