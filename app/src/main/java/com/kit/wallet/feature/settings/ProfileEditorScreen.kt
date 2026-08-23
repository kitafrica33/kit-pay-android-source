package com.kit.wallet.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.auth.isPlaceholderProfileName
import com.kit.wallet.data.auth.isProvisionalProfileTag
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    setup: Boolean,
    onDone: () -> Unit,
    onSkip: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var tagEdited by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(profile.name, profile.tag) {
        if (!initialized && (profile.name.isNotBlank() || profile.tag.isNotBlank())) {
            val initial = mergeProfileEditorInitialValues(
                current = ProfileEditorInitialValues(name, tag),
                profile = profile,
                setup = setup,
                nameEdited = nameEdited,
                tagEdited = tagEdited,
            )
            name = initial.name
            tag = initial.tag
            initialized = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (setup) "Choose your username and Kit Pay tag" else "Edit profile")
                },
                navigationIcon = {
                    if (!setup) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (setup) {
                    "Choose the username / display name and unique @tag people will see when they pay or contact you."
                } else {
                    "Update the username / display name and unique @tag shown to other Kit Pay users."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!setup) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val pickAvatar = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val jpeg = withContext(Dispatchers.Default) {
                            runCatching {
                                transcodeProfileAvatar(context.contentResolver, uri)
                            }.getOrNull()
                        }
                        if (jpeg == null) {
                            viewModel.reportAvatarSelectionError(
                                "That photo could not be prepared. Choose a different photo.",
                            )
                        } else {
                            viewModel.attachAvatar(jpeg)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KitAvatar(profile.name, size = 64.dp, avatarUrl = profile.avatarUrl)
                    Spacer(Modifier.width(14.dp))
                    TextButton(
                        onClick = {
                            if (!editorState.uploadingAvatar) {
                                pickAvatar.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            }
                        },
                        enabled = !editorState.uploadingAvatar,
                    ) {
                        Text(
                            if (editorState.uploadingAvatar) "Uploading photo…"
                            else if (profile.avatarUrl != null) "Change photo"
                            else "Add photo",
                        )
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameEdited = true
                    viewModel.clearProfileError()
                },
                label = { Text("Username / display name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tag,
                onValueChange = {
                    tag = normalizeProfileTag(it)
                    tagEdited = true
                    viewModel.clearProfileError()
                },
                label = { Text("Unique Kit Pay tag") },
                prefix = { Text("@") },
                supportingText = { Text("3–32 lowercase letters, numbers, or underscores") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            editorState.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = { viewModel.saveProfile(name, tag, onDone) },
                enabled = !editorState.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (editorState.saving) "Saving…" else "Save profile")
            }
            if (setup && onSkip != null) {
                TextButton(
                    onClick = onSkip,
                    enabled = !editorState.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Do this later")
                }
            }
        }
    }
}

internal data class ProfileEditorInitialValues(
    val name: String,
    val tag: String,
)

internal fun profileEditorInitialValues(
    profile: UserProfile,
    setup: Boolean,
): ProfileEditorInitialValues {
    val normalizedTag = normalizeProfileTag(profile.tag)
    return ProfileEditorInitialValues(
        name = if (setup && isPlaceholderProfileName(profile.name)) "" else profile.name,
        tag = if (setup && isProvisionalProfileTag(normalizedTag)) "" else normalizedTag,
    )
}

internal fun mergeProfileEditorInitialValues(
    current: ProfileEditorInitialValues,
    profile: UserProfile,
    setup: Boolean,
    nameEdited: Boolean,
    tagEdited: Boolean,
): ProfileEditorInitialValues {
    val cached = profileEditorInitialValues(profile, setup)
    return ProfileEditorInitialValues(
        name = if (nameEdited) current.name else cached.name,
        tag = if (tagEdited) current.tag else cached.tag,
    )
}
