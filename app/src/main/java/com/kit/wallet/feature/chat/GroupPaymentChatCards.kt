package com.kit.wallet.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kit.wallet.data.messaging.KitGroupPaymentMessage
import com.kit.wallet.ui.model.GroupPaymentShare
import com.kit.wallet.ui.model.GroupPaymentSummary
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitTheme

/**
 * A group payment as it appears in the thread: gold where the rest of the app is green, because it
 * is the one payment that belongs to everybody reading it rather than to two people.
 *
 * Two things are drawn from two different sources, deliberately. The announcement is the
 * conversation's own record, so it still reads with no network at all. Everything that can move —
 * a share's amount, its status, whether this member may still act — comes only from [payment], the
 * server's answer scoped to whoever is looking. When the two disagree the card stops offering
 * anything and says so, because the safe reading of a mismatch is that this device does not know
 * what happened.
 *
 * Mirrors iOS `GroupPaymentCardView`.
 */
@Composable
internal fun GroupPaymentChatCard(
    descriptor: KitGroupPaymentMessage,
    payment: GroupPaymentSummary?,
    contradictsServer: Boolean,
    isOutgoing: Boolean,
    senderName: String,
    /** Resolves a member's display name from their user id. */
    displayName: (String) -> String,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onReturnUnclaimed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KitTheme.colors
    val announcement = GroupPaymentCopy.announcement(
        descriptor = descriptor,
        senderName = senderName,
        isViewerSender = isOutgoing,
        recipientNames = descriptor.recipientUserIds.map(displayName),
        // Only ever the sender's own view: the server withholds the pot of a custom split from
        // everybody else, so there is nothing here to leak.
        totalOverride = if (isOutgoing) payment?.totalAmountMinor else null,
    )

    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = colors.goldContainer,
            contentColor = colors.onGoldContainer,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.4.dp, colors.goldSheenStart),
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Payments,
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "GROUP PAYMENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.gold,
                    )
                }
                Text(
                    announcement,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                GroupPaymentCopy.evenShareSubtitle(descriptor)?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                descriptor.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    contradictsServer -> Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = colors.warning,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Kit could not verify this payment. Open your wallet to check it.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.warning,
                        )
                    }

                    payment != null -> {
                        HorizontalDivider(color = colors.gold.copy(alpha = 0.3f))
                        payment.yourShare?.let { share ->
                            YourGroupPaymentShare(
                                share = share,
                                payment = payment,
                                isBusy = isBusy,
                                onAccept = onAccept,
                                onDecline = onDecline,
                            )
                        }
                        if (isOutgoing || payment.canReverseUnclaimed) {
                            SenderGroupPaymentSummary(
                                payment = payment,
                                isBusy = isBusy,
                                onReturnUnclaimed = onReturnUnclaimed,
                            )
                        }
                        GroupPaymentRecipientRoll(payment, displayName)
                    }

                    else -> CircularProgressIndicator(
                        color = colors.gold,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(22.dp),
                    )
                }
            }
        }
    }
}

/** What this member is owed, and the only two buttons that can move it. */
@Composable
private fun YourGroupPaymentShare(
    share: GroupPaymentShare,
    payment: GroupPaymentSummary,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = KitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Your share",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            Money.format(share.amountMinor, payment.currencyCode, payment.currencyScale),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            GroupPaymentCopy.shareStatus(share.status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (share.canAccept || share.canReject) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (share.canAccept) {
                    Button(
                        onClick = onAccept,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.gold),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Take my share")
                        }
                    }
                }
                if (share.canReject) {
                    OutlinedButton(
                        onClick = onDecline,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("Decline")
                    }
                }
            }
            Text(
                GroupPaymentCopy.GROUP_ONLY_CLAIM_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How far the payment has got, in counts, plus the sender's way of pulling back the rest. */
@Composable
private fun SenderGroupPaymentSummary(
    payment: GroupPaymentSummary,
    isBusy: Boolean,
    onReturnUnclaimed: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            GroupPaymentCopy.progress(payment),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (payment.canReverseUnclaimed && payment.pendingCount > 0) {
            OutlinedButton(
                onClick = onReturnUnclaimed,
                enabled = !isBusy,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Rounded.Undo,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Return what is unclaimed")
            }
        }
    }
}

/**
 * Who was paid and where each of them has got to. Amounts appear only on the lines the server
 * chose to fill in — for a custom split that is the sender's view and your own line.
 */
@Composable
private fun GroupPaymentRecipientRoll(
    payment: GroupPaymentSummary,
    displayName: (String) -> String,
) {
    if (payment.recipients.isEmpty() || payment.recipients.size > MAX_LISTED_RECIPIENTS) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        payment.recipients.forEach { recipient ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    recipient.name?.takeIf(String::isNotBlank)
                        ?: recipient.userId?.let(displayName)
                        ?: "Kit Pay user",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                recipient.amountMinor?.let { amount ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        Money.format(amount, payment.currencyCode, payment.currencyScale),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    GroupPaymentCopy.recipientStatus(recipient.status),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One member's answer, shown the way a date heading is: small, centred, unobtrusive. It states
 * only what its author did, which is the only thing the thread can vouch for offline.
 */
@Composable
internal fun GroupPaymentOutcomeChip(text: String) {
    val colors = KitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.gold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(colors.goldContainer, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Beyond this the roll is a wall of names; the counts above already say where the payment is. */
private const val MAX_LISTED_RECIPIENTS = 12
