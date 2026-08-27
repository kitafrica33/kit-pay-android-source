package com.kit.wallet.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.MessageDeliveryInfo
import com.kit.wallet.ui.model.MessageDeliveryPerson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What happened to a message after it left the composer.
 *
 * A screen of its own rather than a sheet peeled up from the bottom. In a group this is a list of
 * everybody the message was addressed to, each of whom opens into three moments; that is a page of
 * reading, and a half-height sheet would spend the whole time being dragged. A direct chat gets
 * three lines, because there is only one person to report on. Either way the question is *who* and
 * *when* — "delivered to thirty people" is an average nobody asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInfoScreen(
    state: MessageInfoState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Message info") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Close message info",
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 32.dp),
                ) {
                    when (state) {
                        MessageInfoState.Loading -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                        is MessageInfoState.Failed -> Column {
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = onRetry) { Text("Try again") }
                        }
                        is MessageInfoState.Loaded -> LoadedMessageInfo(state.info)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadedMessageInfo(info: MessageDeliveryInfo) {
    val single = info.recipients.singleOrNull()
    Column {
        MomentRow(Icons.Rounded.Done, "Sent", info.sentAtEpochMillis)
        if (single != null) {
            MomentRow(Icons.Rounded.DoneAll, "Delivered", single.deliveredAtEpochMillis)
            MomentRow(Icons.Rounded.Visibility, "Read", single.readAtEpochMillis)
        } else {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(
                "Read by ${info.readCount} of ${info.recipients.size}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Tap somebody to see when the message reached them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            info.recipients.forEach { recipient ->
                RecipientRow(recipient, sentAtEpochMillis = info.sentAtEpochMillis)
            }
        }
    }
}

@Composable
private fun RecipientRow(recipient: MessageDeliveryPerson, sentAtEpochMillis: Long) {
    var expanded by remember(recipient.userId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KitAvatar(recipient.name, size = 44.dp, avatarUrl = recipient.avatarUrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    recipient.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    furthestReached(recipient),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 58.dp, bottom = 6.dp)) {
                MomentRow(Icons.Rounded.Done, "Sent", sentAtEpochMillis)
                MomentRow(Icons.Rounded.DoneAll, "Delivered", recipient.deliveredAtEpochMillis)
                MomentRow(Icons.Rounded.Visibility, "Read", recipient.readAtEpochMillis)
            }
        }
    }
}

/** One witnessed moment, or the fact that it has not been witnessed. */
@Composable
private fun MomentRow(icon: ImageVector, label: String, epochMillis: Long) {
    val witnessed = epochMillis > 0
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (witnessed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            if (witnessed) messageDeliveryMomentLabel(epochMillis) else "Not yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The furthest a message got with one person, said in one line. */
internal fun furthestReached(recipient: MessageDeliveryPerson): String = when {
    recipient.readAtEpochMillis > 0 ->
        "Read · ${messageDeliveryMomentLabel(recipient.readAtEpochMillis)}"
    recipient.deliveredAtEpochMillis > 0 ->
        "Delivered · ${messageDeliveryMomentLabel(recipient.deliveredAtEpochMillis)}"
    else -> "Not delivered yet"
}

/**
 * How one witnessed moment is written.
 *
 * Times only, never "3 minutes ago": somebody opens this screen precisely because they want to
 * know when, and a relative phrase makes them do the arithmetic the phone already did.
 */
internal fun messageDeliveryMomentLabel(
    epochMillis: Long,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    val today = now.atZone(zoneId).toLocalDate()
    val time = moment.format(MOMENT_TIME)
    return when (moment.toLocalDate()) {
        today -> "Today at $time"
        today.minusDays(1) -> "Yesterday at $time"
        else -> "${moment.format(MOMENT_DATE)} at $time"
    }
}

private val MOMENT_TIME = DateTimeFormatter.ofPattern("h:mm a")
private val MOMENT_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
