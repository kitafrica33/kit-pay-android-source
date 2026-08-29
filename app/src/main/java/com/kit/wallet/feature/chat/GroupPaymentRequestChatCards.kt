package com.kit.wallet.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kit.wallet.data.remote.GroupPaymentRequestContributionDto
import com.kit.wallet.data.remote.GroupPaymentRequestDto
import com.kit.wallet.data.remote.GroupPaymentRequestPresentation
import com.kit.wallet.data.remote.GroupPaymentRequestStatus
import com.kit.wallet.data.remote.KitGroupPaymentRequestAction
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.theme.KitTheme
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A collaborative request card whose mutable facts and actions come only from the API object. */
@Composable
internal fun GroupPaymentRequestChatCard(
    request: GroupPaymentRequestDto?,
    fallbackAmountMinor: Long?,
    fallbackCurrencyCode: String,
    fallbackCurrencyScale: Int,
    fallbackNote: String?,
    displayName: (String) -> String,
    busy: Boolean,
    onPayRemaining: (GroupPaymentRequestDto) -> Unit,
    onPayPartial: (GroupPaymentRequestDto) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = KitTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 16.dp),
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Payments,
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "GROUP PAYMENT REQUEST",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.gold,
                    )
                }
                if (request == null) {
                    fallbackAmountMinor?.let {
                        Text(
                            Money.format(it, fallbackCurrencyCode, fallbackCurrencyScale),
                            fontSize = 25.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    fallbackNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                    }
                    CircularProgressIndicator(
                        color = colors.gold,
                        strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(22.dp),
                    )
                    Text(
                        "Checking the latest request status…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    return@Column
                }

                val scale = request.currencyScale ?: return@Column
                val target = request.targetMinor ?: return@Column
                Text(
                    Money.format(target, request.currency.code, scale),
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                request.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
                LinearProgressIndicator(
                    progress = { request.progressBasisPoints.coerceIn(0, 10_000) / 10_000f },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.gold,
                )
                Text(
                    GroupPaymentRequestPresentation.progress(request),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (request.contributionCount > 0) {
                    HorizontalDivider(color = colors.gold.copy(alpha = 0.3f))
                    request.bubbleContributions().forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (row.isYours) "You" else displayName(row.contributorUserId),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                Money.format(row.amountMinor.toLong(), request.currency.code, scale),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (request.contributionCount > 5) {
                        Text(
                            "${request.contributionCount - 5} earlier contributions",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                when (request.knownStatus) {
                    GroupPaymentRequestStatus.OPEN -> {
                        if (request.canContribute) {
                            Button(
                                onClick = { onPayRemaining(request) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Pay remaining · ${Money.format(
                                    checkNotNull(request.remainingMinor), request.currency.code, scale,
                                )}")
                            }
                            OutlinedButton(
                                onClick = { onPayPartial(request) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Contribute another amount") }
                        }
                        if (request.canCancel) {
                            OutlinedButton(
                                onClick = onCancel,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Cancel request") }
                        }
                    }
                    GroupPaymentRequestStatus.COMPLETED -> Text(
                        "Complete · 100% collected",
                        fontWeight = FontWeight.Bold,
                        color = colors.success,
                    )
                    GroupPaymentRequestStatus.CANCELLED -> Text("Cancelled", fontWeight = FontWeight.Bold)
                    GroupPaymentRequestStatus.EXPIRED -> Text("Expired", fontWeight = FontWeight.Bold)
                    null -> Unit
                }
            }
        }
    }
}

/** Actor-aware copy only when an event's exact contribution row proves the actor and amount. */
internal fun groupPaymentRequestEventText(
    message: Message,
    request: GroupPaymentRequestDto?,
    contribution: GroupPaymentRequestContributionDto?,
    displayName: (String) -> String,
): String? {
    val action = message.groupPaymentRequestAction?.let(KitGroupPaymentRequestAction::fromWire)
        ?: return null
    val authority = request ?: return null
    return when (action) {
        KitGroupPaymentRequestAction.CONTRIBUTED,
        KitGroupPaymentRequestAction.COMPLETED,
        -> {
            val exactId = message.groupPaymentRequestContributionId ?: return if (
                action == KitGroupPaymentRequestAction.COMPLETED
            ) GroupPaymentRequestPresentation.progress(authority) else null
            val exact = contribution?.takeIf {
                it.id == exactId && message.senderUserId?.equals(it.contributorUserId, true) == true
            } ?: return null
            if (action == KitGroupPaymentRequestAction.COMPLETED) {
                GroupPaymentRequestPresentation.completed(
                    actorName = displayName(exact.contributorUserId),
                    contributionAmount = exact.amount,
                    collectedAmount = authority.targetAmount,
                    currencyCode = authority.currency.code,
                    isViewer = message.fromMe || exact.isYours,
                )
            } else {
                GroupPaymentRequestPresentation.contributed(
                    actorName = displayName(exact.contributorUserId),
                    amount = exact.amount,
                    currencyCode = authority.currency.code,
                    isViewer = message.fromMe || exact.isYours,
                )
            }
        }
        KitGroupPaymentRequestAction.CANCELLED -> "This payment request was cancelled."
        KitGroupPaymentRequestAction.EXPIRED -> "This payment request expired."
        KitGroupPaymentRequestAction.REQUESTED -> null
    }
}

/** A backend-owned money schedule. It is deliberately not represented by the local text queue. */
@Composable
internal fun ServerScheduledPaymentCard(
    amount: String?,
    currencyCode: String,
    scheduledFor: String,
    note: String?,
    group: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
) {
    val formattedAmount = amount?.let { raw ->
        runCatching {
            val plain = BigDecimal(raw).stripTrailingZeros().toPlainString()
            val units = plain.substringBefore('.')
            val fraction = plain.substringAfter('.', "")
            "$currencyCode ${Money.groupUnits(units)}${if (fraction.isEmpty()) "" else ".$fraction"}"
        }.getOrNull()
    }
    val due = runCatching {
        DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(scheduledFor))
    }.getOrElse { scheduledFor }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (group) "SCHEDULED GROUP PAYMENT" else "SCHEDULED PAYMENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                formattedAmount?.let {
                    Text(it, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("Scheduled for $due", style = MaterialTheme.typography.bodySmall)
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel scheduled payment") }
            }
        }
    }
}
