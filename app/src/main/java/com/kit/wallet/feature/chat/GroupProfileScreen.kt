package com.kit.wallet.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.remote.MAX_GROUP_MEMBERS
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.theme.KitTheme

/**
 * A group's own screen: who is in it, what each of them may do, and the way out.
 *
 * Every row's menu is built from [groupMemberActions], which mirrors the server's membership
 * rules, so an action that would be refused is never offered. The server is still what decides;
 * a refusal surfaces as an error rather than as a list that silently disagrees with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupProfileScreen(
    onBack: () -> Unit,
    onAddParticipants: () -> Unit,
    onLeft: () -> Unit,
    viewModel: GroupProfileViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var leaving by remember { mutableStateOf(false) }
    var confirmRemoval by remember { mutableStateOf<ChatMember?>(null) }
    val canManage = viewer?.role?.canManageMembers == true
    val canLeave = canLeaveGroup(members)

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave ${chat?.name ?: "this group"}?") },
            text = {
                Text(
                    "You will stop receiving its messages on every device. This conversation and " +
                        "its history are removed from this device.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        leaving = false
                        viewModel.leave(onLeft)
                    },
                ) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
        )
    }

    confirmRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            title = { Text("Remove ${target.name}?") },
            text = {
                Text(
                    "They stop receiving new messages in this group. What they already have " +
                        "stays on their device.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoval = null
                        viewModel.removeMember(target)
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
            if (busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    KitAvatar(chat?.name.orEmpty(), size = 88.dp, avatarUrl = chat?.avatarUrl)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        chat?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Text(
                        groupMemberCountLabel(members.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    Text(
                        "Messages, photos and payments in this group are end-to-end encrypted. " +
                            "Only its participants can read them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            if (error != null) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
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
            if (canManage) {
                item {
                    GroupActionRow(
                        label = "Add participants",
                        onClick = onAddParticipants,
                        enabled = !busy,
                    ) {
                        Icon(
                            Icons.Rounded.PersonAdd,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            item {
                Text(
                    "Participants",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(members.size) { index ->
                val member = members[index]
                GroupMemberRow(
                    member = member,
                    actions = groupMemberActions(viewer, member),
                    enabled = !busy,
                    onPromote = { viewModel.setRole(member, ChatMemberRole.ADMIN) },
                    onDemote = { viewModel.setRole(member, ChatMemberRole.MEMBER) },
                    onMakeOwner = { viewModel.setRole(member, ChatMemberRole.OWNER) },
                    onRemove = { confirmRemoval = member },
                )
            }
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    GroupActionRow(
                        label = "Exit group",
                        onClick = { leaving = true },
                        enabled = canLeave && !busy,
                        tint = MaterialTheme.colorScheme.error,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!canLeave && viewer != null) {
                        Text(
                            // The server refuses to leave a group with nobody able to manage it,
                            // so the way out is named instead of the attempt simply failing.
                            "You are its only owner. Make somebody else an owner first, then " +
                                "you can leave.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 78.dp, end = 20.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

/** The add-participants half of the group screen, sharing its view model and its refusals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAddParticipantsScreen(
    onBack: () -> Unit,
    viewModel: GroupProfileViewModel = hiltViewModel(),
) {
    val contacts by viewModel.addableResults.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val full = members.size >= MAX_GROUP_MEMBERS

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add participants") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
            if (busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
            if (full) {
                item {
                    Text(
                        "This group is full. Remove somebody before adding anybody else.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            } else if (contacts.isEmpty()) {
                item {
                    Text(
                        "Everybody you know on Kit Pay is already in this group.",
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
                    selected = false,
                    enabled = !busy && !full,
                    // Adding is one call each, applied the moment it is tapped: the group's own
                    // screen is where the result shows, so going back is the confirmation.
                    onClick = { viewModel.addMember(contact, onAdded = onBack) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GroupActionRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) tint else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun GroupMemberRow(
    member: ChatMember,
    actions: GroupMemberActions,
    enabled: Boolean,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onMakeOwner: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KitAvatar(
            member.name,
            size = 46.dp,
            online = member.online,
            avatarUrl = member.avatarUrl,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (member.isSelf) "You" else member.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (member.online) {
                Text(
                    "online",
                    style = MaterialTheme.typography.labelSmall,
                    color = KitTheme.colors.success,
                )
            }
        }
        // A plain member wears no badge: the absence of one is what "member" means, and thirty
        // identical chips would only make the two that matter harder to see.
        if (member.role != ChatMemberRole.MEMBER) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    member.role.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (actions.any) {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Manage ${member.name}",
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (actions.canPromote) {
                        DropdownMenuItem(
                            text = { Text("Make admin") },
                            onClick = {
                                menuOpen = false
                                onPromote()
                            },
                        )
                    }
                    if (actions.canDemote) {
                        DropdownMenuItem(
                            text = { Text("Dismiss as admin") },
                            onClick = {
                                menuOpen = false
                                onDemote()
                            },
                        )
                    }
                    if (actions.canMakeOwner) {
                        DropdownMenuItem(
                            text = { Text("Make owner") },
                            onClick = {
                                menuOpen = false
                                onMakeOwner()
                            },
                        )
                    }
                    if (actions.canRemove) {
                        DropdownMenuItem(
                            text = { Text("Remove from group") },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}
