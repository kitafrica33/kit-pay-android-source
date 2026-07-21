package com.kit.wallet.feature.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.AddIcCall
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.SectionHeader
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    onNewCall: () -> Unit,
    viewModel: CallsViewModel = hiltViewModel(),
) {
    val calls by viewModel.calls.collectAsStateWithLifecycle()
    CallsContent(calls, onVoiceCall, onVideoCall, onNewCall)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CallsContent(
    allCalls: List<CallEntry>,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    onNewCall: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val calls = allCalls.filter {
        filter == "All" || it.direction == CallDirection.MISSED
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewCall,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(Icons.Rounded.AddIcCall, contentDescription = "New call")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Calls",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    listOf("All", "Missed").forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            item { SectionHeader("Recent") }
            items(calls.size) { i ->
                val target = calls[i].participantUserIds.firstOrNull()
                CallRow(
                    call = calls[i],
                    onCall = target?.let { userId ->
                        {
                            if (calls[i].video) onVideoCall(userId) else onVoiceCall(userId)
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

@Composable
private fun CallRow(call: CallEntry, onCall: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onCall != null) Modifier.clickable(onClick = onCall) else Modifier)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitAvatar(call.name, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                call.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (call.direction == CallDirection.MISSED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (call.direction) {
                    CallDirection.INCOMING -> Icons.AutoMirrored.Rounded.CallReceived to KitTheme.colors.success
                    CallDirection.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade to KitTheme.colors.success
                    CallDirection.MISSED -> Icons.AutoMirrored.Rounded.CallMissed to MaterialTheme.colorScheme.error
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.width(4.dp))
                Text(
                    call.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onCall != null) {
            Icon(
                if (call.video) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = if (call.video) "Video call" else "Voice call",
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CallsPreview() {
    KitWalletTheme {
        CallsContent(
            DemoData.calls,
            onVoiceCall = {},
            onVideoCall = {},
            onNewCall = {},
        )
    }
}
