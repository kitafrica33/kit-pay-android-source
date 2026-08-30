package com.kit.wallet.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.data.auth.isPlaceholderProfileName
import com.kit.wallet.data.auth.isProvisionalProfileTag
import com.kit.wallet.data.auth.normalizeProfileName
import com.kit.wallet.ui.model.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    setup: Boolean,
    onDone: () -> Unit,
    onSkip: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    /**
     * Opens identity verification. Supplied during setup when the feature is available, which is
     * what makes the offered order verify first, then choose a name — the legal name is then read
     * from the document instead of typed.
     */
    onVerifyIdentity: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var tagEdited by rememberSaveable { mutableStateOf(false) }
    val editorBusy = editorState.saving || editorState.uploadingAvatar

    BackHandler(enabled = editorBusy) {
        // Keep the screen and its ViewModel alive until the in-flight profile mutation settles.
    }

    // Returning from identity verification is the moment a legal name can appear. The editor's
    // ViewModel outlives that trip, so nothing else would go and look.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshProfile()
        onPauseOrDispose {}
    }

    LaunchedEffect(profile.name, profile.tag, profile.legalName) {
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
                    Text(if (setup) "Set up your profile" else "Edit profile")
                },
                navigationIcon = {
                    if (!setup) {
                        IconButton(onClick = onBack, enabled = !editorBusy) {
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
                // The setup step is now photo, two fields, discoverability and two buttons, which
                // is taller than a small phone with the keyboard up.
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                profileEditorIntroduction(setup = setup, verified = profile.identityVerified),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProfileAvatarPicker(
                name = profile.displayIdentityName,
                avatarUrl = profile.avatarUrl,
                uploading = editorState.uploadingAvatar,
                onAvatarSelected = viewModel::attachAvatar,
                onSelectionError = viewModel::reportAvatarSelectionError,
                // During setup the photo leads: it is the first thing that proves the account
                // being set up is the one the person already has.
                prominent = setup,
            )
            // Shown above the two editable fields so the order on screen says what the rule is:
            // the verified name is settled, the rest is a choice.
            profile.legalName?.takeIf(String::isNotBlank)?.let { VerifiedLegalNameCard(it) }
            if (onVerifyIdentity != null && !profile.identityVerified) {
                VerifyIdentityInvitation(onVerify = onVerifyIdentity)
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameEdited = true
                    viewModel.clearProfileError()
                },
                label = {
                    Text(if (profile.identityVerified) "Display name (optional)" else "Display name")
                },
                supportingText = if (profile.identityVerified) {
                    { Text("A nickname to appear under. Leave it empty to use your legal name.") }
                } else {
                    null
                },
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
                label = { Text(if (profile.identityVerified) "Username (optional)" else "Username") },
                prefix = { Text("@") },
                supportingText = {
                    Text(
                        if (profile.identityVerified) {
                            "A handle people can pay you by. 3–32 lowercase letters, numbers, " +
                                "or underscores."
                        } else {
                            "3–32 lowercase letters, numbers, or underscores"
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (setup) {
                ProfileDiscoverabilitySection()
            }
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
                enabled = !editorBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    when {
                        editorState.saving -> "Saving…"
                        // Setup can now finish with nothing typed at all, so "Save" would be a
                        // strange thing to call it.
                        setup -> "Continue"
                        else -> "Save profile"
                    },
                )
            }
            if (setup && onSkip != null) {
                TextButton(
                    onClick = onSkip,
                    enabled = !editorBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Do this later")
                }
            }
        }
    }
}

/**
 * The account's verified name, stated plainly and not offered for editing.
 *
 * It has its own card rather than a disabled text field because it is not a field: nothing the user
 * types here or anywhere else can change it, and a greyed-out box invites the attempt.
 */
@Composable
private fun VerifiedLegalNameCard(legalName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Legal name",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(legalName, style = MaterialTheme.typography.titleMedium)
            Text(
                "Read from your verified ID. Kit Pay uses it whenever money moves, so it is not " +
                    "something a display name or username replaces.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The offer to verify before choosing anything, which is the order this flow is meant to run in.
 *
 * An invitation and not a wall. Someone who cannot verify right now — no document to hand, no
 * light to photograph it in — still finishes setup by typing a name, exactly as before.
 */
@Composable
private fun VerifyIdentityInvitation(onVerify: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Badge,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("Verify your identity first", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Kit Pay reads your legal name straight from your ID, so you never type it and " +
                    "nothing here can overwrite it. Once it is verified, both fields below become " +
                    "optional.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = onVerify,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text("Verify my identity")
            }
        }
    }
}

internal fun profileEditorIntroduction(setup: Boolean, verified: Boolean): String = when {
    setup && verified ->
        "Your name is already verified from your ID. All that is left is how you appear to other " +
            "people — both of these are optional."
    setup ->
        "Check your photo, then choose the display name and username people will see when they " +
            "pay or contact you."
    verified ->
        "Your verified legal name stays as it is. These two are what other Kit Pay users see."
    else -> "Update the display name and username shown to other Kit Pay users."
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
    val legalName = normalizeProfileName(profile.legalName.orEmpty())
    // The API presents the verified legal name as the display name when no display name was
    // chosen. Putting that into an editable "Display name" box would invite someone to edit their
    // verified name, and would quietly turn the fallback into a chosen name the moment they saved.
    val nameIsLegalNameFallback = legalName.isNotBlank() &&
        normalizeProfileName(profile.name) == legalName
    return ProfileEditorInitialValues(
        name = when {
            nameIsLegalNameFallback -> ""
            setup && isPlaceholderProfileName(profile.name) -> ""
            else -> profile.name
        },
        tag = when {
            isProvisionalProfileTag(normalizedTag) && (setup || profile.identityVerified) -> ""
            else -> normalizedTag
        },
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
