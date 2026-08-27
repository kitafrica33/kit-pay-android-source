package com.kit.wallet.feature.chat

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.remote.MAX_GROUP_MEMBERS
import com.kit.wallet.feature.settings.transcodeProfileAvatar
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.theme.KitTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onEditDescription: () -> Unit,
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
                    KitAvatar(
                        chat?.name.orEmpty(),
                        size = 88.dp,
                        avatarUrl = chat?.avatarUrl,
                        isGroup = true,
                    )
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
                    if (canManage) {
                        GroupPhotoControls(
                            hasPhoto = chat?.avatarUrl != null,
                            enabled = !busy,
                            onPhotoPicked = viewModel::changePhoto,
                            onRemovePhoto = viewModel::removePhoto,
                            onError = viewModel::reportError,
                        )
                    }
                }
            }
            item {
                GroupDescriptionSection(
                    description = chat?.description,
                    canManage = canManage,
                    enabled = !busy,
                    onEdit = onEditDescription,
                )
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

/**
 * The two ways in for a group photo and the one way out, offered only to managers.
 *
 * The picked image rides the exact profile-avatar preparation — square crop, downscale,
 * bounded JPEG — so the server-side sanitizer sees the same shape of upload either way.
 */
@Composable
private fun GroupPhotoControls(
    hasPhoto: Boolean,
    enabled: Boolean,
    onPhotoPicked: (ByteArray) -> Unit,
    onRemovePhoto: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSources by remember { mutableStateOf(false) }
    var captureTarget by remember { mutableStateOf<Uri?>(null) }
    var captureFile by remember { mutableStateOf<File?>(null) }

    fun preparePhoto(uri: Uri, onFinished: () -> Unit = {}) {
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) {
                runCatching { transcodeProfileAvatar(context.contentResolver, uri) }.getOrNull()
            }
            onFinished()
            if (jpeg == null) {
                onError(
                    "That image could not be read on this device. Try another photo or take " +
                        "a new one.",
                )
            } else {
                onPhotoPicked(jpeg)
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) preparePhoto(uri) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = captureTarget
        val file = captureFile
        captureTarget = null
        captureFile = null
        if (saved && target != null) preparePhoto(target) { file?.delete() } else file?.delete()
    }

    fun launchCapture() {
        val directory = File(context.cacheDir, "chat-capture").apply { mkdirs() }
        val file = File(directory, "group-photo-${UUID.randomUUID()}.jpg")
        captureFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.chatmedia", file)
        captureTarget = uri
        takePhoto.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCapture()
        } else {
            onError("Camera access is needed to take a group photo.")
        }
    }

    Box {
        TextButton(onClick = { if (enabled) showSources = true }, enabled = enabled) {
            Text(if (hasPhoto) "Change group photo" else "Add group photo")
        }
        DropdownMenu(expanded = showSources, onDismissRequest = { showSources = false }) {
            DropdownMenuItem(
                text = { Text("Choose from gallery") },
                leadingIcon = { Icon(Icons.Rounded.Photo, contentDescription = null) },
                onClick = {
                    showSources = false
                    pickPhoto.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Take a photo") },
                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                onClick = {
                    showSources = false
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        launchCapture()
                    } else {
                        cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }
                },
            )
            if (hasPhoto) {
                DropdownMenuItem(
                    text = {
                        Text("Remove photo", color = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        showSources = false
                        onRemovePhoto()
                    },
                )
            }
        }
    }
}

/**
 * What this group is for, in its own words.
 *
 * Everybody reads it here; managers can also edit it, and a group without one shows the
 * affordance only to the people who could actually write it.
 */
@Composable
private fun GroupDescriptionSection(
    description: String?,
    canManage: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    if (description == null && !canManage) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
    ) {
        if (description != null) {
            Text(
                "Description",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = if (canManage) {
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, onClick = onEdit)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
            if (canManage) {
                TextButton(onClick = onEdit, enabled = enabled) { Text("Edit description") }
            }
        } else {
            TextButton(onClick = onEdit, enabled = enabled) { Text("Add group description") }
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
