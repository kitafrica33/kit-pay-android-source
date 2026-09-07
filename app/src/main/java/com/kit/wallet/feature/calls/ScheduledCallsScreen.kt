package com.kit.wallet.feature.calls

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.remote.ScheduledCallDto
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.Contact
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledCallsScreen(onBack: () -> Unit, viewModel: ScheduledCallsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var cancelling by remember { mutableStateOf<ScheduledCallDto?>(null) }
    val context = LocalContext.current
    LaunchedEffect(state.share?.id) {
        viewModel.takeShare()?.let { url ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }, "Share call invitation"))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Scheduled calls") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }, actions = {
                IconButton(onClick = viewModel::refresh, enabled = !state.busy) { Icon(Icons.Rounded.Refresh, "Refresh") }
            })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            if (state.features.scheduling) {
                item {
                    Button(onClick = { viewModel.openEditor() }, enabled = !state.busy && !state.pendingCreate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Schedule a call")
                    }
                }
                item { Text("Choose a time and invite Kit Pay contacts. Kit Pay rings everyone invited when the call starts, including you.", style = MaterialTheme.typography.bodyMedium) }
                if (state.pendingCreate) item {
                    Column {
                        Text("A scheduled call is waiting for confirmation. Retry safely or refresh to check if it was saved.")
                        TextButton(onClick = viewModel::retryPendingCreate, enabled = !state.busy) { Text("Retry pending call") }
                    }
                }
                if (state.loaded && state.calls.isEmpty()) item { Text("No scheduled calls yet.", style = MaterialTheme.typography.bodyLarge) }
                items(state.calls, key = ScheduledCallDto::id) { schedule ->
                    ScheduledCallCard(
                        schedule = schedule, organizer = viewModel.isOrganizer(schedule), busy = state.busy,
                        shareEnabled = state.features.inviteLinks,
                        onEdit = { viewModel.openEditor(schedule) }, onCancel = { cancelling = schedule },
                        onRespond = { viewModel.respond(schedule, it) }, onShare = { viewModel.share(schedule) },
                    )
                }
                if (state.nextCursor != null) item {
                    TextButton(onClick = viewModel::loadMore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
                }
            } else if (state.loaded && state.error == null) {
                item { Text("Scheduled calls are not available for this account yet.") }
            }
        }
    }
    if (state.editorOpen) ScheduledCallEditor(state, viewModel.currentServerTime(), viewModel::closeEditor, viewModel::save)
    cancelling?.let { call ->
        // Account switches clear the source list, invalidating a pending confirmation too.
        if (state.calls.any { it.id == call.id } && viewModel.isOrganizer(call)) {
            AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("Cancel scheduled call?") },
                text = { Text("This call will no longer ring the invited contacts.") },
                confirmButton = { TextButton(onClick = { cancelling = null; viewModel.cancel(call) }, enabled = !state.busy) { Text("Cancel call") } },
                dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep call") } })
        } else {
            LaunchedEffect(call.id) { cancelling = null }
        }
    }
}

@Composable
private fun ScheduledCallCard(
    schedule: ScheduledCallDto,
    organizer: Boolean,
    busy: Boolean,
    shareEnabled: Boolean,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onRespond: (String) -> Unit,
    onShare: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(schedule.title?.takeIf(String::isNotBlank) ?: if (schedule.type == "video") "Video call" else "Voice call", style = MaterialTheme.typography.titleMedium)
            Text(formatScheduledTime(schedule.startsAt), style = MaterialTheme.typography.bodyLarge)
            val names = schedule.participants.mapNotNull { it.name?.filterNot(Char::isISOControl)?.takeIf(String::isNotBlank) }
            Text(names.joinToString(", ").ifEmpty { "Kit Pay contacts" }, style = MaterialTheme.typography.bodyMedium)
            Text(if (organizer) "Organized by you · ${scheduleStatus(schedule.status)}" else "${scheduleStatus(schedule.status)} · ${responseLabel(schedule.myResponse)}", style = MaterialTheme.typography.labelLarge)
            if (schedule.status == "scheduled") {
                if (organizer) {
                    Row {
                        TextButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
                        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                        if (shareEnabled) TextButton(onClick = onShare, enabled = !busy) { Text("Share link") }
                    }
                } else {
                    Row {
                        TextButton(onClick = { onRespond("accepted") }, enabled = !busy && schedule.myResponse != "accepted") { Text("Accept") }
                        TextButton(onClick = { onRespond("declined") }, enabled = !busy && schedule.myResponse != "declined") { Text("Decline") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledCallEditor(
    state: ScheduledCallsState,
    now: Instant,
    onClose: () -> Unit,
    onSave: (String, List<String>, Boolean, LocalDateTime, ZoneId) -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val editing = state.editing
    var title by remember(editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
    var video by remember(editing?.id) { mutableStateOf(editing?.type == "video") }
    var selected by remember(editing?.id) { mutableStateOf(emptySet<String>()) }
    var time by remember(editing?.id) {
        mutableStateOf((editing?.startsAt?.let(Instant::parse) ?: now.plusSeconds(3600)).atZone(zone).toLocalDateTime().withSecond(0).withNano(0))
    }
    Dialog(onDismissRequest = { if (!state.busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = { TopAppBar(title = { Text(if (editing == null) "Schedule a call" else "Edit scheduled call") }, navigationIcon = {
                    IconButton(onClick = onClose, enabled = !state.busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }, actions = {
                    TextButton(onClick = { onSave(title, selected.toList(), video, time, zone) },
                        enabled = !state.busy && (editing != null || selected.isNotEmpty())) { Text("Save") }
                }) },
            ) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    item { OutlinedTextField(value = title, onValueChange = { if (it.length <= ScheduledCallPolicy.MAX_TITLE_LENGTH) title = it }, label = { Text("Title (optional)") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Video call", Modifier.weight(1f))
                            Switch(checked = video, onCheckedChange = { video = it }, enabled = !state.busy && editing == null)
                        }
                    }
                    item {
                        Row {
                            OutlinedButton(onClick = {
                                DatePickerDialog(context, { _, year, month, day -> time = time.withYear(year).withMonth(month + 1).withDayOfMonth(day) }, time.year, time.monthValue - 1, time.dayOfMonth).show()
                            }, enabled = !state.busy) { Text(time.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                TimePickerDialog(context, { _, hour, minute -> time = time.withHour(hour).withMinute(minute).withSecond(0).withNano(0) }, time.hour, time.minute, DateFormat.is24HourFormat(context)).show()
                            }, enabled = !state.busy) { Text(time.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
                        }
                        Text("Time zone: ${zone.id}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (editing == null) {
                        item { Text("Invite contacts (${selected.size}/20)", style = MaterialTheme.typography.titleMedium) }
                        if (state.contacts.isEmpty()) item { Text("No Kit Pay contacts available. Add a contact, then return to schedule a call.") }
                        items(state.contacts, key = Contact::id) { contact ->
                            CallRecipientRow(contact, contact.id in selected, !state.busy && (contact.id in selected || selected.size < 20)) {
                                selected = if (contact.id in selected) selected - contact.id else selected + contact.id
                            }
                        }
                    } else item { Text("The invited contacts and call type stay the same. Changing the schedule invalidates earlier invitation links.") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallInvitePreviewScreen(onBack: () -> Unit, onJoin: (String, Boolean) -> Unit, viewModel: ScheduledCallsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCallId.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Call invitation") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }, actions = {
        IconButton(onClick = viewModel::refresh, enabled = !state.busy) { Icon(Icons.Rounded.Refresh, "Refresh") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            val preview = state.preview
            val schedule = preview?.scheduledCall
            if (preview != null) {
                Text(schedule?.title ?: preview.call?.name?.takeIf(String::isNotBlank) ?: "Kit Pay call", style = MaterialTheme.typography.headlineSmall)
                Text(if ((schedule?.type ?: preview.call?.type) == "video") "Video call" else "Voice call")
                if (schedule != null) {
                    Text(formatScheduledTime(schedule.startsAt))
                    Text(scheduleStatus(schedule.status))
                    if (schedule.status == "scheduled" && !viewModel.isOrganizer(schedule)) {
                        Row {
                            Button(onClick = { viewModel.respond(schedule, "accepted") }, enabled = !state.busy && schedule.myResponse != "accepted") { Text("Accept invitation") }
                            TextButton(onClick = { viewModel.respond(schedule, "declined") }, enabled = !state.busy && schedule.myResponse != "declined") { Text("Decline") }
                        }
                        Text(responseLabel(schedule.myResponse))
                    }
                    if (schedule.status in setOf("scheduled", "queued") && schedule.myResponse != "declined") {
                        Text("Kit Pay will ring you when this call starts. You can close this screen.")
                    }
                }
                val live = preview.kind == "call" || (schedule?.status == "started" && schedule.callId != null)
                if (live) {
                    if (activeCall != null) Text("Finish your current call before joining this one.")
                    Button(onClick = { viewModel.prepareJoin()?.let { onJoin(it.token, it.video) } }, enabled = !state.busy && activeCall == null && state.features.inviteLinks) { Text("Join call") }
                }
                Text("Only contacts invited by the organizer can use this link.", style = MaterialTheme.typography.bodySmall)
            } else if (state.loaded && state.error == null) {
                Text("This call invitation is not available for this account.")
            }
        }
    }
}

@Composable
internal fun CallRecipientPicker(
    state: CallActionsState,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onVoice: () -> Unit,
    onVideo: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Call contacts (${state.selected.size}/20)") },
        text = {
            Column {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.contacts.isEmpty()) Text("No Kit Pay contacts available.")
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(state.contacts, key = Contact::id) { contact ->
                        CallRecipientRow(contact, contact.id in state.selected, true) { onToggle(contact.id) }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onVoice, enabled = state.selected.isNotEmpty()) { Text("Voice call") }
                TextButton(onClick = onVideo, enabled = state.selected.isNotEmpty()) { Text("Video call") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CallRecipientRow(contact: Contact, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        KitAvatar(contact.name, size = 36.dp, avatarUrl = contact.avatarUrl)
        Text(contact.name, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
    }
}

private fun formatScheduledTime(raw: String): String = runCatching {
    Instant.parse(raw).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault("Time unavailable")

private fun scheduleStatus(status: String): String = when (status) {
    "scheduled" -> "Scheduled"
    "queued" -> "Starting soon"
    "started" -> "Started"
    "cancelled" -> "Cancelled"
    "skipped" -> "Could not start"
    else -> "Unavailable"
}

private fun responseLabel(response: String?): String = when (response) {
    "accepted" -> "Accepted"
    "declined" -> "Declined"
    else -> "Awaiting your response"
}
