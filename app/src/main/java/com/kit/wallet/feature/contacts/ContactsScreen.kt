package com.kit.wallet.feature.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.components.SectionHeader
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.theme.KitWalletTheme

enum class ContactPickerPurpose { CHAT, CALL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    purpose: ContactPickerPurpose = ContactPickerPurpose.CHAT,
    onContact: (String) -> Unit = {},
    onVoiceCall: (String) -> Unit = {},
    onVideoCall: (String) -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val openingContactId by viewModel.openingContactId.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val tagResults by viewModel.tagResults.collectAsStateWithLifecycle()
    val tagSearching by viewModel.tagSearching.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var contactSyncStage by rememberSaveable { mutableStateOf(ContactSyncStage.IDLE) }
    val contactPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val decision = decideContactSync(
            stage = contactSyncStage,
            event = ContactSyncEvent.PERMISSION_RESULT,
            permissionGranted = granted,
        )
        contactSyncStage = decision.nextStage
        if (decision.effect == ContactSyncEffect.SYNC) viewModel.syncDeviceContacts()
        else if (!granted) viewModel.clearError()
    }

    if (contactSyncStage == ContactSyncStage.DISCLOSURE) {
        val disclosure = contactSyncDisclosurePresentation()
        val cancel = {
            contactSyncStage = decideContactSync(
                stage = contactSyncStage,
                event = ContactSyncEvent.CANCEL,
            ).nextStage
        }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(disclosure.title) },
            text = { Text(disclosure.body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val permissionGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CONTACTS,
                        ) == PackageManager.PERMISSION_GRANTED
                        val decision = decideContactSync(
                            stage = contactSyncStage,
                            event = ContactSyncEvent.AGREE,
                            permissionGranted = permissionGranted,
                        )
                        contactSyncStage = decision.nextStage
                        when (decision.effect) {
                            ContactSyncEffect.REQUEST_PERMISSION ->
                                contactPermission.launch(Manifest.permission.READ_CONTACTS)
                            ContactSyncEffect.SYNC -> viewModel.syncDeviceContacts()
                            ContactSyncEffect.NONE -> Unit
                        }
                    },
                ) { Text(disclosure.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = cancel) { Text(disclosure.cancelLabel) }
            },
        )
    }

    val requestSync = {
        if (!syncing) {
            contactSyncStage = decideContactSync(
                stage = contactSyncStage,
                event = ContactSyncEvent.START,
            ).nextStage
        }
    }
    // Opens the system contact editor: edits the existing entry, or pre-fills a new one with the
    // number (and the registered name) when the person is not yet in the device address book.
    val onManageContact: (Contact) -> Unit = { contact ->
        val intent = Intent(Intent.ACTION_INSERT_OR_EDIT)
            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, contact.phone)
        if (!contact.savedInDevice) {
            contact.registeredName?.takeIf(String::isNotBlank)?.let { registered ->
                intent.putExtra(ContactsContract.Intents.Insert.NAME, registered)
            }
        }
        runCatching { context.startActivity(intent) }
    }
    // WhatsApp-style invite: prefills the person's SMS thread with a Kit Pay download link.
    val onInvite: (Contact) -> Unit = { contact ->
        val smsIntent = Intent(Intent.ACTION_SENDTO)
            .setData(android.net.Uri.parse("smsto:${contact.phone}"))
            .putExtra("sms_body", INVITE_MESSAGE)
        runCatching { context.startActivity(smsIntent) }.recoverCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, INVITE_MESSAGE),
                    "Invite to Kit Pay",
                ),
            )
        }
    }
    ContactsContent(
        allContacts = contacts,
        syncing = syncing,
        error = error,
        openingContactId = openingContactId,
        purpose = purpose,
        query = query,
        tagResults = tagResults,
        tagSearching = tagSearching,
        onQueryChange = viewModel::setQuery,
        onBack = onBack,
        onContact = { contact -> viewModel.openDirectConversation(contact, onContact) },
        onVoiceCall = onVoiceCall,
        onVideoCall = onVideoCall,
        onSync = requestSync,
        onManageContact = onManageContact,
        onInvite = onInvite,
    )
}

/** The public direct-download link served from the Kit Pay production host. */
private const val INVITE_MESSAGE =
    "Let's chat and send money securely on Kit Pay! Download it here: https://pay.kit.africa/kit.apk"

internal enum class ContactSyncStage { IDLE, DISCLOSURE, AWAITING_PERMISSION }

internal enum class ContactSyncEvent { START, CANCEL, AGREE, PERMISSION_RESULT }

internal enum class ContactSyncEffect { NONE, REQUEST_PERMISSION, SYNC }

internal data class ContactSyncDecision(
    val nextStage: ContactSyncStage,
    val effect: ContactSyncEffect = ContactSyncEffect.NONE,
)

internal fun decideContactSync(
    stage: ContactSyncStage,
    event: ContactSyncEvent,
    permissionGranted: Boolean = false,
): ContactSyncDecision = when (event) {
    ContactSyncEvent.START -> if (stage == ContactSyncStage.IDLE) {
        ContactSyncDecision(ContactSyncStage.DISCLOSURE)
    } else {
        ContactSyncDecision(stage)
    }
    ContactSyncEvent.CANCEL -> ContactSyncDecision(ContactSyncStage.IDLE)
    ContactSyncEvent.AGREE -> when {
        stage != ContactSyncStage.DISCLOSURE -> ContactSyncDecision(stage)
        permissionGranted -> ContactSyncDecision(
            ContactSyncStage.IDLE,
            ContactSyncEffect.SYNC,
        )
        else -> ContactSyncDecision(
            ContactSyncStage.AWAITING_PERMISSION,
            ContactSyncEffect.REQUEST_PERMISSION,
        )
    }
    ContactSyncEvent.PERMISSION_RESULT -> when {
        stage != ContactSyncStage.AWAITING_PERMISSION -> ContactSyncDecision(stage)
        permissionGranted -> ContactSyncDecision(
            ContactSyncStage.IDLE,
            ContactSyncEffect.SYNC,
        )
        else -> ContactSyncDecision(ContactSyncStage.IDLE)
    }
}

internal data class ContactSyncDisclosurePresentation(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

internal fun contactSyncDisclosurePresentation() = ContactSyncDisclosurePresentation(
    title = "Upload phone contacts to Kit Pay?",
    body = "Kit Pay will upload names, phone numbers and favorite status from your phone " +
        "contacts to Kit Pay to find people who use Kit Pay. Nothing is uploaded unless " +
        "you agree and allow Contacts access.",
    confirmLabel = "Agree and continue",
    cancelLabel = "Not now",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsContent(
    allContacts: List<Contact>,
    syncing: Boolean,
    error: String?,
    openingContactId: String?,
    purpose: ContactPickerPurpose,
    query: String = "",
    tagResults: List<Contact> = emptyList(),
    tagSearching: Boolean = false,
    onQueryChange: (String) -> Unit = {},
    onBack: () -> Unit,
    onContact: (Contact) -> Unit,
    onVoiceCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    onSync: () -> Unit,
    onManageContact: (Contact) -> Unit,
    onInvite: (Contact) -> Unit = {},
) {
    val tagMode = query.trim().startsWith("@")
    val contacts = if (tagMode) {
        tagResults
    } else {
        allContacts
            .filter { purpose != ContactPickerPurpose.CALL || it.isKitUser }
            .filter { it.name.contains(query, true) || it.phone.contains(query) }
            .sortedBy { it.name }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (purpose == ContactPickerPurpose.CALL) "New call" else "New chat")
                },
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
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    placeholder = { Text("Search contacts or @kittag") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            item {
                ActionRow(
                    Icons.Rounded.Sync,
                    if (syncing) "Syncing contacts…" else "Sync phone contacts",
                    onSync,
                )
            }
            if (error != null) {
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            item {
                SectionHeader(
                    when {
                        tagMode -> "On Kit Pay"
                        purpose == ContactPickerPurpose.CALL -> "On Kit Pay"
                        else -> "People"
                    },
                )
            }
            if (tagMode && tagSearching) {
                item {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Searching Kit Pay members…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (tagMode && !tagSearching && contacts.isEmpty()) {
                item {
                    Text(
                        "No Kit Pay member matches that kit tag yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            if (!tagMode && purpose == ContactPickerPurpose.CALL && contacts.isEmpty()) {
                item {
                    Text(
                        "No callable Kit Pay contacts yet. Sync your phone contacts to find people on Kit Pay.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(contacts.size) { i ->
                val c = contacts[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = openingContactId == null &&
                                (purpose == ContactPickerPurpose.CALL || c.isKitUser),
                        ) {
                            if (purpose == ContactPickerPurpose.CALL) onVoiceCall(c.id)
                            else onContact(c)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KitAvatar(c.name, size = 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            contactSubtitle(c),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (openingContactId == c.id) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        if (!c.isKitUser) {
                            TextButton(onClick = { onInvite(c) }) {
                                Text(
                                    "Invite",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        } else if (purpose == ContactPickerPurpose.CALL) {
                            IconButton(onClick = { onVoiceCall(c.id) }) {
                                Icon(
                                    Icons.Rounded.Call,
                                    contentDescription = "Voice call ${c.name}",
                                )
                            }
                            IconButton(onClick = { onVideoCall(c.id) }) {
                                Icon(
                                    Icons.Rounded.Videocam,
                                    contentDescription = "Video call ${c.name}",
                                )
                            }
                        }
                        if (purpose == ContactPickerPurpose.CHAT) {
                            IconButton(onClick = { onManageContact(c) }) {
                                Icon(
                                    if (c.savedInDevice) Icons.Rounded.Edit else Icons.Rounded.PersonAdd,
                                    contentDescription = if (c.savedInDevice) {
                                        "Edit ${c.name} in contacts"
                                    } else {
                                        "Save ${c.name} to contacts"
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/** WhatsApp-style secondary line: the registered name (as "~ name") when the row shows a saved
 *  device name that differs, otherwise the Kit Pay status for members or the raw number. */
private fun contactSubtitle(contact: Contact): String {
    val registered = contact.registeredName
    return when {
        contact.savedInDevice && !registered.isNullOrBlank() &&
            !registered.equals(contact.name, ignoreCase = true) -> "~ $registered"
        contact.isKitUser -> contact.status
        else -> contact.phone
    }
}

@Preview(showBackground = true)
@Composable
private fun ContactsPreview() {
    KitWalletTheme {
        ContactsContent(
            DemoData.contacts,
            syncing = false,
            error = null,
            openingContactId = null,
            purpose = ContactPickerPurpose.CHAT,
            onBack = {},
            onContact = {},
            onVoiceCall = {},
            onVideoCall = {},
            onSync = {},
            onManageContact = {},
        )
    }
}
