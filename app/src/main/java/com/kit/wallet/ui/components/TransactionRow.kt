package com.kit.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RequestPage
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.theme.KitTheme

@Composable
fun TransactionRow(
    tx: Transaction,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val icon = when (tx.type) {
        TxType.SEND -> Icons.AutoMirrored.Rounded.CallMade
        TxType.RECEIVE -> Icons.AutoMirrored.Rounded.CallReceived
        TxType.BILL -> Icons.Rounded.Receipt
        TxType.AIRTIME -> Icons.Rounded.SimCard
        TxType.BANK_IN, TxType.BANK_OUT -> Icons.Rounded.AccountBalance
        TxType.MERCHANT -> Icons.Rounded.Storefront
        TxType.REQUEST -> Icons.Rounded.RequestPage
    }
    val amountColor = when {
        tx.status == TxStatus.FAILED -> MaterialTheme.colorScheme.error
        tx.amountMinor > 0 -> KitTheme.colors.moneyIn
        else -> KitTheme.colors.moneyOut
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.counterparty,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tx.note ?: tx.time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Money.format(tx.amountMinor, signed = true),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
            )
            when (tx.status) {
                TxStatus.PENDING -> StatusChip(
                    "Pending",
                    KitTheme.colors.warningContainer,
                    KitTheme.colors.warning,
                )
                TxStatus.FAILED -> StatusChip(
                    "Failed",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
                TxStatus.COMPLETED -> Text(
                    tx.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
