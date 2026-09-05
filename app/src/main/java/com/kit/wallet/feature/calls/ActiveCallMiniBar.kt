package com.kit.wallet.feature.calls

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kit.wallet.data.notifications.ActiveCallPresence
import com.kit.wallet.ui.theme.KitTheme
import kotlinx.coroutines.delay

/** The app's one ongoing-call bar. Its parent owns status/cutout insets once for the whole stack. */
@Composable
internal fun ActiveCallMiniBar(call: ActiveCallPresence, onReturn: () -> Unit) {
    var seconds by remember(call.callId, call.anchor) {
        mutableLongStateOf(CallDurationAnchorPolicy.seconds(call.anchor, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(call.callId, call.anchor) {
        while (true) {
            seconds = CallDurationAnchorPolicy.seconds(call.anchor, SystemClock.elapsedRealtime())
            delay(1_000)
        }
    }
    Surface(color = KitTheme.colors.successContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Return to call", onClick = onReturn)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (call.video) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = null,
                tint = KitTheme.colors.success,
                modifier = Modifier.size(18.dp),
            )
            Text(
                call.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = KitTheme.colors.onSuccessContainer,
            )
            Text(
                if (call.anchor != null) formatCallDuration(seconds) else "In call",
                style = MaterialTheme.typography.labelMedium,
                color = KitTheme.colors.onSuccessContainer,
            )
        }
    }
}
