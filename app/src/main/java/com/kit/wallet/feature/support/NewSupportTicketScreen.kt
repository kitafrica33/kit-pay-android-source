package com.kit.wallet.feature.support

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import com.kit.wallet.ui.components.KitGreenButton

private const val SUBJECT_MAX = 140
private const val MESSAGE_MAX = 4000

/**
 * Composing a new ticket. A category chosen from the server's list is required
 * before anything can be sent — there is no free-typed category and no default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSupportTicketScreen(
    aiAdvertised: Boolean,
    onBack: () -> Unit,
    onOpened: (ticketId: String) -> Unit,
    onQueued: () -> Unit,
    viewModel: NewSupportTicketViewModel = hiltViewModel(),
) {
    val categoriesState by viewModel.categories.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var subject by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var categoryExpanded by rememberSaveable { mutableStateOf(false) }

    val trimmedSubject = subject.trim()
    val canSubmit = !state.submitting &&
        categoryKey != null &&
        trimmedSubject.length in 3..SUBJECT_MAX &&
        message.isNotEmpty() && message.length <= MESSAGE_MAX

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New support ticket") },
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
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            ServerReadableNotice(Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))

            when (val categories = categoriesState) {
                SupportCategoriesState.Loading -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) { CircularProgressIndicator() }

                is SupportCategoriesState.Failed -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        categories.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::loadCategories) { Text("Try again") }
                }

                is SupportCategoriesState.Loaded -> {
                    val selected = categories.categories.firstOrNull { it.key == categoryKey }
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selected?.name.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("What is this about?") },
                            placeholder = { Text("Choose a category") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false },
                        ) {
                            categories.categories.forEach { category ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(category.name)
                                            category.description?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme
                                                        .colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        categoryKey = category.key
                                        categoryExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it.take(SUBJECT_MAX) },
                        label = { Text("Subject") },
                        supportingText = {
                            Text("${trimmedSubject.length}/$SUBJECT_MAX (at least 3 characters)")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it.take(MESSAGE_MAX) },
                        label = { Text("Describe the problem") },
                        supportingText = { Text("${message.length}/$MESSAGE_MAX") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (aiAdvertised) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Kit Pay's AI assistant may reply first. You can ask for " +
                                "a human on the ticket at any time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    KitGreenButton(
                        text = if (state.submitting) "Sending…" else "Open ticket",
                        enabled = canSubmit,
                        loading = state.submitting,
                        onClick = {
                            val key = categoryKey ?: return@KitGreenButton
                            viewModel.submit(key, trimmedSubject, message) { result ->
                                when (result) {
                                    is NewTicketResult.Opened -> onOpened(result.ticket.id)
                                    NewTicketResult.Queued -> onQueued()
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
