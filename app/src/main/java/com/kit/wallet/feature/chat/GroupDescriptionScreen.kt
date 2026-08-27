package com.kit.wallet.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.remote.MAX_GROUP_DESCRIPTION_LENGTH
import com.kit.wallet.data.remote.normalizeMessagingGroupDescription

/**
 * The group description on its own, breathable screen.
 *
 * A full destination rather than a sheet or dialog, per the app-wide rule for substantive
 * edits. Saving an emptied field clears the description; the screen closes only after the
 * server accepts, so what the group info screen shows next is always the server's answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDescriptionScreen(
    onBack: () -> Unit,
    viewModel: GroupProfileViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var text by rememberSaveable {
        mutableStateOf(viewModel.chat.value?.description.orEmpty())
    }
    val canonical = normalizeMessagingGroupDescription(text)
    val current = chat?.description.orEmpty()
    val changed = canonical != current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group description") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Say what this group is for. Every participant can read it; only owners and " +
                    "admins can change it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = boundedMessagingGroupDescriptionInput(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                placeholder = { Text("Add a description") },
                minLines = 4,
                enabled = !busy,
                supportingText = {
                    Text(
                        "${canonical.codePointCount(0, canonical.length)}" +
                            "/$MAX_GROUP_DESCRIPTION_LENGTH",
                    )
                },
            )
            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.saveDescription(text, onSaved = onBack) },
                enabled = changed && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Text(if (canonical.isEmpty() && current.isNotEmpty()) "Remove description" else "Save")
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
