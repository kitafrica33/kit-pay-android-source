package com.kit.wallet.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.remote.MAX_GROUP_MEMBERS
import com.kit.wallet.data.remote.isValidMessagingGroupTitle
import com.kit.wallet.data.remote.normalizeMessagingGroupTitle
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.Contact

internal fun isMessagingGroupTitleInputError(value: String): Boolean {
    val normalizedTitle = normalizeMessagingGroupTitle(value)
    return normalizedTitle.isNotEmpty() && !isValidMessagingGroupTitle(normalizedTitle)
}

/**
 * The group builder: pick the people, name the group, create it.
 *
 * Both halves are on one screen deliberately. A group is the only conversation whose name is
 * visible to the server, so the field that discloses it is put in front of whoever is typing it,
 * next to the people it will be disclosed to, rather than on a second step.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewGroupScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: NewGroupViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val canCreate by viewModel.canCreate.collectAsStateWithLifecycle()
    val messagingAvailable by viewModel.messagingAvailable.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selectedIds = selected.mapTo(mutableSetOf(), Contact::id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(
                            onClick = { viewModel.create(onCreated) },
                            enabled = canCreate && messagingAvailable,
                        ) { Text("Create") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = viewModel::setTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    label = { Text("Group name") },
                    supportingText = {
                        Text(
                            // The one thing a group tells the server about itself, so it is said
                            // plainly here rather than buried in a settings screen afterwards.
                            "Everyone in the group sees this name. Messages, photos and payments " +
                                "stay end-to-end encrypted.",
                        )
                    },
                    singleLine = true,
                    isError = isMessagingGroupTitleInputError(title),
                )
            }
            if (!messagingAvailable) {
                item {
                    Text(
                        "Keep Kit Pay online while this device finishes secure setup. A group " +
                            "cannot be created until then.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            if (selected.isNotEmpty()) {
                item {
                    FlowRow(
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selected.forEach { contact ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.toggle(contact) },
                                label = {
                                    Text(
                                        contact.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    // Counting this account keeps the number the same one the group's own screen
                    // shows the moment it exists.
                    "${selected.size + 1} of $MAX_GROUP_MEMBERS people",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    placeholder = { Text("Search contacts") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            if (error != null) {
                item {
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
            }
            if (contacts.isEmpty()) {
                item {
                    Text(
                        "Only contacts who are on Kit Pay can join a group. Sync your phone " +
                            "contacts from the New chat screen to find them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(contacts.size) { index ->
                val contact = contacts[index]
                SelectableContactRow(
                    contact = contact,
                    selected = contact.id in selectedIds,
                    onClick = { viewModel.toggle(contact) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun SelectableContactRow(
    contact: Contact,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitAvatar(contact.name, size = 46.dp, avatarUrl = contact.avatarUrl)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                contact.phone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (selected) "In this group" else "Not in this group",
            tint = if (selected) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}
