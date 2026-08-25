package com.kit.wallet.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The "who can find you" step of account setup.
 *
 * Discoverability is decided here rather than left for someone to discover in Settings later,
 * because it is a question about the account being set up and the answer is otherwise inherited
 * silently. Both switches are the same ones Settings shows, backed by the same state, so a choice
 * made here is not a separate setting that later disagrees with the real one.
 */
@Composable
internal fun ProfileDiscoverabilitySection(
    modifier: Modifier = Modifier,
    viewModel: CommunicationPrivacyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shareDeviceContacts by viewModel.shareDeviceContacts.collectAsStateWithLifecycle()
    val contactDiscoveryAvailable by viewModel.contactDiscoveryAvailable.collectAsStateWithLifecycle()
    val contactDiscoveryToggle = rememberContactDiscoveryToggle(
        consentGranted = shareDeviceContacts,
        consentAvailable = contactDiscoveryAvailable,
        onConsentChanged = viewModel::setShareDeviceContacts,
    )

    ProfileDiscoverabilityContent(
        state = state,
        shareDeviceContacts = contactDiscoveryToggle.checked,
        shareDeviceContactsEnabled = contactDiscoveryToggle.enabled,
        onPhoneDiscoverabilityChanged = viewModel::setPhoneDiscoverable,
        onShareDeviceContactsChanged = contactDiscoveryToggle.onCheckedChange,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )
}

@Composable
private fun ProfileDiscoverabilityContent(
    state: CommunicationPrivacyUiState,
    shareDeviceContacts: Boolean,
    shareDeviceContactsEnabled: Boolean,
    onPhoneDiscoverabilityChanged: (Boolean) -> Unit,
    onShareDeviceContactsChanged: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The phone-number switch is the account's answer and lives on the server; it cannot be moved
    // before the current answer is known, or the move would be made against a guess.
    val phoneControlEnabled = state.loaded && !state.loading && state.savingField == null

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Who can find you", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your @tag always works. These two decide whether your number and your " +
                        "contacts do as well — change them any time in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsRow(
                icon = Icons.Rounded.PhoneAndroid,
                title = "Find me by phone number",
                subtitle = if (state.preferences.phoneDiscoverable) {
                    "On. People who already have your number can find your account."
                } else {
                    "Off. Your number will not match you to anyone searching for it."
                },
                trailing = {
                    Switch(
                        checked = state.preferences.phoneDiscoverable,
                        enabled = phoneControlEnabled,
                        onCheckedChange = if (phoneControlEnabled) {
                            onPhoneDiscoverabilityChanged
                        } else {
                            null
                        },
                    )
                },
                onClick = null,
            )
            SettingsRow(
                icon = Icons.Rounded.Contacts,
                title = "Find me from my contacts",
                subtitle = if (shareDeviceContacts) {
                    "On. Kit Pay may send your address book to show which of your contacts " +
                        "are here."
                } else {
                    "Off. Your address book stays on this phone."
                },
                trailing = {
                    Switch(
                        checked = shareDeviceContacts,
                        enabled = shareDeviceContactsEnabled,
                        onCheckedChange = if (shareDeviceContactsEnabled) {
                            onShareDeviceContactsChanged
                        } else {
                            null
                        },
                    )
                },
                onClick = null,
            )
            if (!state.loaded && !state.loading) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "Your phone-number setting could not be loaded, so it stays off for now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                        Text("Try again")
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
