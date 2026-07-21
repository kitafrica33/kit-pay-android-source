package com.kit.wallet.feature.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.kit.wallet.BuildConfig
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.repository.BlockedCommunicationUser
import com.kit.wallet.feature.legal.OpenSourceLicenceDialog
import com.kit.wallet.feature.legal.isTrustedKitReleaseSourceUrl
import com.kit.wallet.feature.legal.openSourceLicencePresentation
import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.ui.components.KitAvatar
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.model.formatKitTag
import com.kit.wallet.ui.theme.KitTheme
import com.kit.wallet.ui.theme.KitWalletTheme
import java.net.URI
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    capabilities: AppCapabilities,
    onEditProfile: () -> Unit,
    onSecurity: () -> Unit,
    onKyc: () -> Unit,
    onLogoutCurrentDevice: () -> Unit,
    logoutBusy: Boolean = false,
    logoutError: String? = null,
    onDismissLogoutError: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    communicationViewModel: CommunicationPrivacyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val emailState by viewModel.emailState.collectAsStateWithLifecycle()
    val deletionState by viewModel.deletionState.collectAsStateWithLifecycle()
    val communicationState by communicationViewModel.state.collectAsStateWithLifecycle()
    val communicationContacts by communicationViewModel.contacts.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var showEmailDialog by rememberSaveable { mutableStateOf(false) }
    var showKycUnavailable by rememberSaveable { mutableStateOf(false) }
    var showPrivacyUnavailable by rememberSaveable { mutableStateOf(false) }
    var showOpenSourceLicence by rememberSaveable { mutableStateOf(false) }
    var showSourceUnavailable by rememberSaveable { mutableStateOf(false) }
    var showAccountDeletionUnavailable by rememberSaveable { mutableStateOf(false) }
    var showLogoutError by rememberSaveable { mutableStateOf(false) }
    var showDeletionDialog by rememberSaveable { mutableStateOf(false) }
    var showBlockedAccounts by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(logoutError) {
        if (!logoutError.isNullOrBlank()) showLogoutError = true
    }

    if (showEmailDialog) {
        ProfileEmailDialog(
            state = emailState,
            onRequest = viewModel::requestEmailAttachment,
            onVerify = { code ->
                viewModel.verifyEmailAttachment(code) { showEmailDialog = false }
            },
            onDismiss = {
                viewModel.dismissEmailFlow()
                showEmailDialog = false
            },
        )
    }

    if (showDeletionDialog) {
        AccountDeletionDialog(
            state = deletionState,
            onRetry = viewModel::beginAccountDeletion,
            onConfirm = viewModel::requestAccountDeletion,
            onDismiss = {
                viewModel.dismissAccountDeletion()
                showDeletionDialog = false
            },
        )
    }

    if (showBlockedAccounts) {
        BlockedAccountsDialog(
            state = communicationState,
            contacts = communicationContacts,
            onBlock = communicationViewModel::block,
            onUnblock = communicationViewModel::unblock,
            onRefresh = communicationViewModel::refreshBlockManagement,
            onDismiss = { showBlockedAccounts = false },
        )
    }

    if (showKycUnavailable) {
        AlertDialog(
            onDismissRequest = { showKycUnavailable = false },
            title = { Text("Didit verification unavailable") },
            text = {
                Text("Identity verification is temporarily unavailable. Please try again later.")
            },
            confirmButton = {
                TextButton(onClick = { showKycUnavailable = false }) { Text("OK") }
            },
        )
    }

    if (showPrivacyUnavailable) {
        AlertDialog(
            onDismissRequest = { showPrivacyUnavailable = false },
            title = { Text("Privacy policy unavailable") },
            text = {
                Text("No browser is available to open Kit Pay's privacy policy.")
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyUnavailable = false }) { Text("OK") }
            },
        )
    }

    if (showOpenSourceLicence) {
        val presentation = openSourceLicencePresentation(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
        OpenSourceLicenceDialog(
            presentation = presentation,
            onOpenSource = {
                showOpenSourceLicence = false
                if (!isTrustedKitReleaseSourceUrl(presentation.sourceUrl)) {
                    showSourceUnavailable = true
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, presentation.sourceUrl.toUri()).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    if (runCatching { context.startActivity(intent) }.isFailure) {
                        showSourceUnavailable = true
                    }
                }
            },
            onDismiss = { showOpenSourceLicence = false },
        )
    }

    if (showSourceUnavailable) {
        AlertDialog(
            onDismissRequest = { showSourceUnavailable = false },
            title = { Text("Source page unavailable") },
            text = {
                Text(
                    "No browser is available. The exact corresponding-source URL remains " +
                        "visible in Kit Pay's open-source licence notice.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showSourceUnavailable = false }) { Text("OK") }
            },
        )
    }

    if (showAccountDeletionUnavailable) {
        AlertDialog(
            onDismissRequest = { showAccountDeletionUnavailable = false },
            title = { Text("Account deletion page unavailable") },
            text = {
                Text("No browser is available to open Kit Pay's account deletion request page.")
            },
            confirmButton = {
                TextButton(onClick = { showAccountDeletionUnavailable = false }) { Text("OK") }
            },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!logoutBusy) confirmLogout = false },
            title = { Text("Log out of this device?") },
            text = {
                Text(
                    "This ends only this phone's Kit Pay session. " +
                        "Your other signed-in devices stay connected.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !logoutBusy,
                    onClick = {
                        confirmLogout = false
                        onLogoutCurrentDevice()
                    },
                ) {
                    Text(if (logoutBusy) "Logging out…" else "Log out")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !logoutBusy,
                    onClick = { confirmLogout = false },
                ) { Text("Cancel") }
            },
        )
    }

    if (showLogoutError && !logoutError.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = {
                showLogoutError = false
                onDismissLogoutError()
            },
            title = { Text("Could not log out") },
            text = {
                Text(logoutFailureMessage(logoutError))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutError = false
                        onDismissLogoutError()
                    },
                ) { Text("OK") }
            },
        )
    }

    SettingsContent(
        profile = profile,
        capabilities = capabilities,
        onEditProfile = onEditProfile,
        onEmail = {
            if (!profile.emailVerified && capabilities.enabled(KitFeature.EMAIL_REGISTRATION)) {
                viewModel.beginEmailFlow(profile.email)
                showEmailDialog = true
            }
        },
        onSecurity = onSecurity,
        communicationState = communicationState,
        messagingUsable = capabilities.messagingUsable,
        onPhoneDiscoverabilityChanged = communicationViewModel::setPhoneDiscoverable,
        onIncomingCallsChanged = communicationViewModel::setIncomingCallsEnabled,
        onDirectMessageRequestsChanged = { enabled ->
            communicationViewModel.setDirectMessageRequestsEnabled(
                enabled = enabled,
                messagingUsable = capabilities.messagingUsable,
            )
        },
        onRefreshCommunicationPrivacy = communicationViewModel::refresh,
        onBlockedAccounts = {
            showBlockedAccounts = true
            communicationViewModel.refreshBlockManagement()
        },
        onKyc = {
            if (capabilities.enabled(KitFeature.KYC)) onKyc() else showKycUnavailable = true
        },
        onPrivacyPolicy = {
            val policy = privacyPolicyPresentation()
            if (!isTrustedKitPrivacyPolicyUrl(policy.url)) {
                showPrivacyUnavailable = true
            } else {
                val intent = Intent(Intent.ACTION_VIEW, policy.url.toUri()).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                if (runCatching { context.startActivity(intent) }.isFailure) {
                    showPrivacyUnavailable = true
                }
            }
        },
        onOpenSourceLicence = { showOpenSourceLicence = true },
        onDeleteAccount = {
            if (capabilities.enabled(KitFeature.ACCOUNT_DELETION)) {
                showDeletionDialog = true
                viewModel.beginAccountDeletion()
            } else if (isTrustedKitAccountDeletionUrl(KIT_ACCOUNT_DELETION_URL)) {
                val intent = Intent(Intent.ACTION_VIEW, KIT_ACCOUNT_DELETION_URL.toUri()).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                if (runCatching { context.startActivity(intent) }.isFailure) {
                    showAccountDeletionUnavailable = true
                }
            } else {
                showAccountDeletionUnavailable = true
            }
        },
        onLogout = { confirmLogout = true },
    )
}

internal fun logoutFailureMessage(error: String): String =
    "$error If this phone still shows you as signed in, check your connection and retry."

@Composable
private fun SettingsContent(
    profile: UserProfile,
    capabilities: AppCapabilities,
    onEditProfile: () -> Unit,
    onEmail: () -> Unit,
    onSecurity: () -> Unit,
    communicationState: CommunicationPrivacyUiState,
    messagingUsable: Boolean,
    onPhoneDiscoverabilityChanged: (Boolean) -> Unit,
    onIncomingCallsChanged: (Boolean) -> Unit,
    onDirectMessageRequestsChanged: (Boolean) -> Unit,
    onRefreshCommunicationPrivacy: () -> Unit,
    onBlockedAccounts: () -> Unit,
    onKyc: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onOpenSourceLicence: () -> Unit,
    onDeleteAccount: () -> Unit,
    onLogout: () -> Unit,
) {
    val identityVerification = identityVerificationPresentation(profile.kycLabel)
    val emailPresentation = profileEmailPresentation(
        profile,
        attachmentAvailable = capabilities.enabled(KitFeature.EMAIL_REGISTRATION),
    )
    val privacyPolicy = privacyPolicyPresentation()
    val accountDeletion = accountDeletionPresentation(
        inAppAvailable = capabilities.enabled(KitFeature.ACCOUNT_DELETION),
    )
    val kycAvailable = capabilities.enabled(KitFeature.KYC)

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        item {
            Text(
                "Profile",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KitAvatar(profile.name, size = 76.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name.ifBlank { "Kit Pay user" }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOf(formatKitTag(profile.tag), profile.phone)
                            .filter(String::isNotBlank)
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (kycAvailable) {
                        Text(
                            (if (identityVerification.verified) "✓ " else "Didit • ") +
                                identityVerification.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (identityVerification.verified) KitTheme.colors.success
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Communication privacy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
        }
        item {
            CommunicationPrivacySettings(
                state = communicationState,
                messagingUsable = messagingUsable,
                onPhoneDiscoverabilityChanged = onPhoneDiscoverabilityChanged,
                onIncomingCallsChanged = onIncomingCallsChanged,
                onDirectMessageRequestsChanged = onDirectMessageRequestsChanged,
                onRefresh = onRefreshCommunicationPrivacy,
                onBlockedAccounts = onBlockedAccounts,
            )
        }
        item {
            SettingsGroup {
                SettingsRow(
                    Icons.Rounded.Edit,
                    "Edit profile",
                    "Change your username / display name and unique @tag",
                    onClick = onEditProfile,
                )
                SettingsRow(
                    Icons.Rounded.AlternateEmail,
                    emailPresentation.title,
                    emailPresentation.subtitle,
                    onClick = if (emailPresentation.canAttach) onEmail else null,
                )
                SettingsRow(
                    Icons.Rounded.Badge,
                    if (kycAvailable) identityVerification.title
                    else "Verify your identity with Didit",
                    if (kycAvailable) identityVerification.subtitle
                    else "Didit verification is temporarily unavailable",
                    onClick = onKyc,
                )
                SettingsRow(
                    Icons.Rounded.Security,
                    "Security",
                    "Wallet PIN, authenticator and active sessions",
                    onClick = onSecurity,
                )
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    Icons.Rounded.PrivacyTip,
                    privacyPolicy.title,
                    privacyPolicy.subtitle,
                    onClick = onPrivacyPolicy,
                )
                SettingsRow(
                    Icons.Rounded.Description,
                    "Open-source licence",
                    "AGPL-3.0-only, warranty, notices and corresponding source",
                    onClick = onOpenSourceLicence,
                )
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    Icons.Rounded.DeleteForever,
                    accountDeletion.title,
                    accountDeletion.subtitle,
                    onClick = onDeleteAccount,
                )
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    Icons.AutoMirrored.Rounded.Logout,
                    "Log out of this device",
                    "End only this phone's Kit Pay session",
                    onClick = onLogout,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CommunicationPrivacySettings(
    state: CommunicationPrivacyUiState,
    messagingUsable: Boolean,
    onPhoneDiscoverabilityChanged: (Boolean) -> Unit,
    onIncomingCallsChanged: (Boolean) -> Unit,
    onDirectMessageRequestsChanged: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onBlockedAccounts: () -> Unit,
) {
    val controlsEnabled = state.loaded && !state.loading && state.savingField == null

    SettingsGroup {
        SettingsRow(
            icon = Icons.Rounded.PhoneAndroid,
            title = "Find me by phone number",
            subtitle = "Off by default. Allow verified Kit Pay contacts to match your phone number.",
            trailing = {
                Switch(
                    checked = state.preferences.phoneDiscoverable,
                    enabled = controlsEnabled,
                    onCheckedChange = if (controlsEnabled) onPhoneDiscoverabilityChanged else null,
                )
            },
            onClick = null,
        )
        SettingsRow(
            icon = Icons.Rounded.Call,
            title = "Incoming calls",
            subtitle = "Off by default. Allow eligible Kit Pay contacts to start voice or video calls.",
            trailing = {
                Switch(
                    checked = state.preferences.incomingCallsEnabled,
                    enabled = controlsEnabled,
                    onCheckedChange = if (controlsEnabled) onIncomingCallsChanged else null,
                )
            },
            onClick = null,
        )
        SettingsRow(
            icon = Icons.Rounded.ChatBubbleOutline,
            title = "Direct message requests",
            subtitle = if (messagingUsable) {
                "Allow future secure message requests from eligible Kit Pay contacts."
            } else {
                "Coming later. This stays off until reviewed secure messaging is available."
            },
            trailing = {
                val enabled = controlsEnabled && messagingUsable
                Switch(
                    checked = messagingUsable &&
                        state.preferences.directMessageRequestsEnabled,
                    enabled = enabled,
                    onCheckedChange = if (enabled) onDirectMessageRequestsChanged else null,
                )
            },
            onClick = null,
        )
        SettingsRow(
            icon = Icons.Rounded.Block,
            title = "Blocked accounts",
            subtitle = when {
                state.blocksLoading && !state.blocksLoaded -> "Loading blocked accounts…"
                !state.blocksLoaded -> "Unavailable until the list can be refreshed"
                state.blockedUsers.isEmpty() -> "No accounts blocked"
                state.blockedUsers.size == 1 -> "1 account blocked"
                else -> "${state.blockedUsers.size} accounts blocked"
            },
            onClick = onBlockedAccounts,
        )
        if (state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "Loading privacy choices…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { error ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
            }
        }
    }
}

@Composable
private fun BlockedAccountsDialog(
    state: CommunicationPrivacyUiState,
    contacts: List<Contact>,
    onBlock: (String) -> Unit,
    onUnblock: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val blockedIds = state.blockedUsers.mapTo(mutableSetOf(), BlockedCommunicationUser::userId)
    val contactsById = contacts.filter(Contact::isKitUser).associateBy(Contact::id)
    val blockCandidates = contactsById.values
        .filterNot { blockedIds.contains(it.id) }
        .sortedBy { it.name.lowercase() }
    val busyUserId = state.blockMutationUserId

    AlertDialog(
        onDismissRequest = { if (busyUserId == null) onDismiss() },
        title = { Text("Blocked accounts") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Blocking is private and directional. Either person's active block stops " +
                        "phone matching, new calls and direct encrypted messaging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.blocksLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Refreshing accounts…")
                    }
                }

                Text("Blocked", fontWeight = FontWeight.SemiBold)
                when {
                    state.blockedUsers.isEmpty() && state.blocksLoaded -> Text("No accounts blocked.")
                    state.blockedUsers.isEmpty() -> Text("The blocked-account list is unavailable.")
                    else -> state.blockedUsers.forEach { block ->
                        val contact = contactsById[block.userId]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(contact?.name ?: "Kit Pay account")
                                Text(
                                    compactUserId(block.userId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                enabled = busyUserId == null,
                                onClick = { onUnblock(block.userId) },
                            ) {
                                Text(if (busyUserId == block.userId) "Unblocking…" else "Unblock")
                            }
                        }
                    }
                }

                Text("Block a Kit Pay contact", fontWeight = FontWeight.SemiBold)
                if (blockCandidates.isEmpty()) {
                    Text(
                        "No eligible Kit Pay contacts are available. Sync contacts first, then return here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    blockCandidates.forEach { contact ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name)
                                Text(
                                    contact.phone,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                enabled = busyUserId == null,
                                onClick = { onBlock(contact.id) },
                            ) {
                                Text(if (busyUserId == contact.id) "Blocking…" else "Block")
                            }
                        }
                    }
                }
                state.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !state.blocksLoading && busyUserId == null) {
                Text("Refresh")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = busyUserId == null) { Text("Close") }
        },
    )
}

internal fun compactUserId(userId: String): String = when {
    userId.length <= 13 -> userId
    else -> "${userId.take(8)}…${userId.takeLast(4)}"
}

internal const val KIT_PRIVACY_POLICY_URL = "https://pay.kit.africa/privacy"
internal const val KIT_ACCOUNT_DELETION_URL = "https://pay.kit.africa/account-deletion"

@Composable
private fun AccountDeletionDialog(
    state: AccountDeletionUiState,
    onRetry: () -> Unit,
    onConfirm: (confirmation: String, paymentPin: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmation by remember { mutableStateOf("") }
    // Never save the wallet PIN across activity/process recreation.
    var paymentPin by remember { mutableStateOf("") }
    val preflight = state.preflight

    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        title = { Text("Delete your Kit Pay account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Kit Pay will lock this account immediately, sign out every device and " +
                        "begin deletion or de-identification of eligible data.",
                )
                if (state.loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Loading the deletion and retention notice…")
                    }
                }
                preflight?.let { details ->
                    if (details.closureRequirements.isNotEmpty()) {
                        Text(
                            "Support must resolve before final erasure:",
                            fontWeight = FontWeight.SemiBold,
                        )
                        details.closureRequirements.forEach { Text("• $it") }
                    }
                    Text(
                        "Financial, KYC, sanctions, fraud, dispute and audit records may be " +
                            "retained where legally required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it.take(16) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Type ${details.confirmationText}") },
                        singleLine = true,
                        enabled = !state.submitting,
                    )
                    OutlinedTextField(
                        value = paymentPin,
                        onValueChange = { paymentPin = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Wallet PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        enabled = !state.submitting,
                    )
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                preflight == null && !state.loading -> TextButton(onClick = onRetry) {
                    Text("Retry")
                }
                preflight != null -> TextButton(
                    enabled = !state.submitting &&
                        confirmation == preflight.confirmationText &&
                        paymentPin.length == 4,
                    onClick = { onConfirm(confirmation, paymentPin) },
                ) {
                    Text(if (state.submitting) "Submitting…" else "Delete account")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !state.submitting, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

internal data class PrivacyPolicyPresentation(
    val title: String,
    val subtitle: String,
    val url: String,
)

internal fun privacyPolicyPresentation() = PrivacyPolicyPresentation(
    title = "Privacy policy",
    subtitle = "Learn how Kit Pay handles your data",
    url = KIT_PRIVACY_POLICY_URL,
)

internal fun isTrustedKitPrivacyPolicyUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("pay.kit.africa", ignoreCase = true) &&
        uri.path == "/privacy" &&
        uri.userInfo == null &&
        uri.port == -1 &&
        uri.query == null &&
        uri.fragment == null
}.getOrDefault(false)

internal data class AccountDeletionPresentation(
    val title: String,
    val subtitle: String,
    val usesProtectedInAppFlow: Boolean,
    val publicUrl: String,
)

internal fun accountDeletionPresentation(inAppAvailable: Boolean) = AccountDeletionPresentation(
    title = "Delete account",
    subtitle = if (inAppAvailable) {
        "Request protected in-app deletion of your Kit Pay account and eligible data"
    } else {
        "Open Kit Pay's support-assisted account deletion request page"
    },
    usesProtectedInAppFlow = inAppAvailable,
    publicUrl = KIT_ACCOUNT_DELETION_URL,
)

internal fun isTrustedKitAccountDeletionUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("pay.kit.africa", ignoreCase = true) &&
        uri.path == "/account-deletion" &&
        uri.userInfo == null &&
        uri.port == -1 &&
        uri.query == null &&
        uri.fragment == null
}.getOrDefault(false)

internal data class IdentityVerificationPresentation(
    val title: String,
    val subtitle: String,
    val status: String,
    val verified: Boolean,
)

internal fun identityVerificationPresentation(rawStatus: String): IdentityVerificationPresentation {
    val status = rawStatus.trim().ifBlank { "KYC not started" }
    val normalized = status.lowercase()
    val verified = normalized.contains("verified") &&
        !normalized.contains("not verified") &&
        !normalized.contains("unverified")
    val underReview = normalized.contains("pending") || normalized.contains("review")
    return when {
        verified -> IdentityVerificationPresentation(
            title = "Identity verified with Didit",
            subtitle = status,
            status = status,
            verified = true,
        )
        underReview -> IdentityVerificationPresentation(
            title = "Didit verification in review",
            subtitle = "$status • Tap to refresh your status",
            status = status,
            verified = false,
        )
        else -> IdentityVerificationPresentation(
            title = "Verify your identity with Didit",
            subtitle = "$status • Tap to start the secure identity check",
            status = status,
            verified = false,
        )
    }
}

internal data class ProfileEmailPresentation(
    val title: String,
    val subtitle: String,
    val canAttach: Boolean,
)

internal fun profileEmailPresentation(
    profile: UserProfile,
    attachmentAvailable: Boolean = true,
): ProfileEmailPresentation = when {
    profile.emailVerified && !profile.email.isNullOrBlank() -> ProfileEmailPresentation(
        title = "Email address",
        subtitle = "${profile.email} • Verified • Email changes are not yet supported",
        canAttach = false,
    )
    !attachmentAvailable -> ProfileEmailPresentation(
        title = if (profile.email.isNullOrBlank()) "Add email address" else "Verify email address",
        subtitle = "Email verification is temporarily unavailable",
        canAttach = false,
    )
    profile.email.isNullOrBlank() -> ProfileEmailPresentation(
        title = "Add email address",
        subtitle = "Add a verified contact and recovery address",
        canAttach = true,
    )
    else -> ProfileEmailPresentation(
        title = "Verify email address",
        subtitle = "${profile.email} • Not verified",
        canAttach = true,
    )
}

@Composable
private fun ProfileEmailDialog(
    state: ProfileEmailUiState,
    onRequest: (String) -> Unit,
    onVerify: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by rememberSaveable(state.challenge?.id) { mutableStateOf(state.email) }
    // Email proofs are short-lived credentials and must not enter saved-instance-state bundles.
    var code by remember(state.challenge?.id) { mutableStateOf("") }
    val challenge = state.challenge
    val busy = state.requesting || state.verifying
    var resendSeconds by remember(challenge?.id, state.resendNotBeforeEpochMillis) {
        mutableLongStateOf(
            profileEmailResendSecondsRemaining(
                state.resendNotBeforeEpochMillis,
                System.currentTimeMillis(),
            ),
        )
    }

    LaunchedEffect(challenge?.id, state.resendNotBeforeEpochMillis) {
        while (resendSeconds > 0L) {
            delay(250L)
            resendSeconds = profileEmailResendSecondsRemaining(
                state.resendNotBeforeEpochMillis,
                System.currentTimeMillis(),
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when {
                    challenge != null -> "Verify your email"
                    else -> "Add email address"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (challenge == null) {
                    Text("We will send a short-lived verification code before attaching this address.")
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Enter the 6-digit code sent to ${challenge.destination}.")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                        label = { Text("Verification code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { onRequest(state.email) },
                        enabled = !busy && state.resendNotBeforeEpochMillis != null &&
                            resendSeconds == 0L,
                    ) {
                        Text(
                            when {
                                state.resendNotBeforeEpochMillis == null ->
                                    "Another code is temporarily unavailable"
                                resendSeconds > 0L ->
                                    "Send another code in ${formatProfileEmailCountdown(resendSeconds)}"
                                else -> "Send another code"
                            },
                        )
                    }
                }
                state.error?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && if (challenge == null) email.isNotBlank() else code.length == 6,
                onClick = { if (challenge == null) onRequest(email) else onVerify(code) },
            ) {
                Text(
                    when {
                        state.requesting -> "Sending…"
                        state.verifying -> "Verifying…"
                        challenge == null -> "Send code"
                        else -> "Verify"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

internal fun formatProfileEmailCountdown(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    return "%d:%02d".format(safeSeconds / 60L, safeSeconds % 60L)
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    KitWalletTheme {
        SettingsContent(
            profile = UserProfile(
                DemoData.USER_NAME,
                DemoData.USER_PHONE,
                "@amina",
                "KYC verified • Level 2",
                email = "amina@example.test",
                emailVerified = true,
            ),
            capabilities = AppCapabilities(
                features = mapOf("wallets" to true, "kyc" to true),
                loaded = true,
            ),
            onEditProfile = {},
            onEmail = {},
            onSecurity = {},
            communicationState = CommunicationPrivacyUiState(
                loaded = true,
                loading = false,
                blocksLoaded = true,
                blocksLoading = false,
            ),
            messagingUsable = false,
            onPhoneDiscoverabilityChanged = {},
            onIncomingCallsChanged = {},
            onDirectMessageRequestsChanged = {},
            onRefreshCommunicationPrivacy = {},
            onBlockedAccounts = {},
            onKyc = {},
            onPrivacyPolicy = {},
            onOpenSourceLicence = {},
            onDeleteAccount = {},
            onLogout = {},
        )
    }
}
