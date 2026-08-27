package com.kit.wallet.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Keeps incoming shared content behind authentication and the complete secure-messaging gate.
 * No fallback transports exist here: if E2EE is unavailable, the only action is to dismiss.
 */
@Composable
internal fun IncomingTextShareCoordinator(
    request: IncomingTextShareRequest,
    ownerMatchesCurrentSession: Boolean,
    signedIn: Boolean,
    accountSetupRequired: Boolean,
    signInFlowActive: Boolean,
    capabilitiesLoaded: Boolean,
    capabilityLoadFailed: Boolean,
    secureMessagingServerCompatible: Boolean,
    groupMessagingEnabled: Boolean,
    localOutboxAvailable: Boolean,
    onSignIn: () -> Unit,
    onRetryCapabilities: () -> Unit,
    onConsumed: () -> Unit,
    onDeferred: () -> Unit,
    onSendingChanged: (Boolean) -> Unit,
) {
    when (val payload = request.payload) {
        is IncomingTextShare.Rejected -> ShareStatusDialog(
            title = "Can't share this item",
            message = payload.reason,
            confirmLabel = "Close",
            onConfirm = onConsumed,
            onDismiss = onConsumed,
        )

        is IncomingTextShare.Accepted -> AcceptedTextShareCoordinator(
            request = request,
            batch = payload.batch,
            ownerMatchesCurrentSession = ownerMatchesCurrentSession,
            signedIn = signedIn,
            accountSetupRequired = accountSetupRequired,
            signInFlowActive = signInFlowActive,
            capabilitiesLoaded = capabilitiesLoaded,
            capabilityLoadFailed = capabilityLoadFailed,
            secureMessagingServerCompatible = secureMessagingServerCompatible,
            groupMessagingEnabled = groupMessagingEnabled,
            localOutboxAvailable = localOutboxAvailable,
            onSignIn = onSignIn,
            onRetryCapabilities = onRetryCapabilities,
            onConsumed = onConsumed,
            onDeferred = onDeferred,
            onSendingChanged = onSendingChanged,
        )
    }
}

@Composable
private fun AcceptedTextShareCoordinator(
    request: IncomingTextShareRequest,
    batch: SharedInboxBatch,
    ownerMatchesCurrentSession: Boolean,
    signedIn: Boolean,
    accountSetupRequired: Boolean,
    signInFlowActive: Boolean,
    capabilitiesLoaded: Boolean,
    capabilityLoadFailed: Boolean,
    secureMessagingServerCompatible: Boolean,
    groupMessagingEnabled: Boolean,
    localOutboxAvailable: Boolean,
    onSignIn: () -> Unit,
    onRetryCapabilities: () -> Unit,
    onConsumed: () -> Unit,
    onDeferred: () -> Unit,
    onSendingChanged: (Boolean) -> Unit,
) {
    var continuingToSignIn by remember(request.token) { mutableStateOf(false) }
    var enteredSignInFlow by remember(request.token) { mutableStateOf(false) }

    LaunchedEffect(continuingToSignIn, signInFlowActive, signedIn) {
        if (continuingToSignIn && signInFlowActive) {
            enteredSignInFlow = true
        } else if (continuingToSignIn && enteredSignInFlow && !signedIn) {
            // Returning from the auth journey without a session is an explicit cancellation.
            onConsumed()
        }
    }

    val access = incomingShareAccess(
        capabilitiesLoaded = capabilitiesLoaded,
        capabilityLoadFailed = capabilityLoadFailed,
        secureMessagingServerCompatible = secureMessagingServerCompatible,
        localOutboxAvailable = localOutboxAvailable,
    )
    val closePendingShare = if (batch.pinnedConversationId == null) onConsumed else onDeferred
    when {
        !ownerMatchesCurrentSession -> ShareStatusDialog(
            title = "This share belongs to another session",
            message = "For your privacy, content prepared under a different Kit Pay login cannot be opened here.",
            confirmLabel = "Close",
            onConfirm = onConsumed,
            onDismiss = onConsumed,
        )

        !signedIn && !continuingToSignIn -> ShareStatusDialog(
            title = "Sign in to share securely",
            message = "Sign in to choose a recipient and review your message. Nothing will be sent automatically.",
            confirmLabel = "Sign in",
            dismissLabel = "Cancel",
            onConfirm = {
                continuingToSignIn = true
                onSignIn()
            },
            onDismiss = onConsumed,
        )

        !signedIn -> Unit

        // Let the existing mandatory profile flow remain fully interactive. The in-memory share
        // will resume automatically once the signed-in account is ready.
        accountSetupRequired -> Unit

        access == IncomingShareAccess.CHECKING ->
            ShareLoadingDialog(onDismiss = closePendingShare)

        access == IncomingShareAccess.RETRY_CAPABILITIES -> ShareStatusDialog(
            title = "Couldn't open secure sharing",
            message = "Kit Pay could not open your encrypted conversations on this device.",
            confirmLabel = "Try again",
            dismissLabel = "Cancel",
            onConfirm = onRetryCapabilities,
            onDismiss = closePendingShare,
        )

        access == IncomingShareAccess.UNAVAILABLE -> ShareStatusDialog(
            title = "Secure sharing isn't available",
            message = "Kit Pay will not send this without reviewed end-to-end encryption. Nothing has been shared.",
            confirmLabel = "Close",
            onConfirm = closePendingShare,
            onDismiss = closePendingShare,
        )

        else -> SharedTextShareDialog(
            request = request,
            batch = batch,
            groupMessagingEnabled = groupMessagingEnabled,
            onDismiss = onConsumed,
            onDeferred = onDeferred,
            onSent = onConsumed,
            onSendingChanged = onSendingChanged,
        )
    }
}

internal enum class IncomingShareAccess {
    CHECKING,
    RETRY_CAPABILITIES,
    UNAVAILABLE,
    RECIPIENT_PICKER,
}

/**
 * A network failure cannot close an already-open encrypted outbox: the repository can publish the
 * selected content locally and send it after connectivity returns. A positive server response is
 * still checked normally, and a device with no local activation cannot accept an offline share.
 */
internal fun incomingShareAccess(
    capabilitiesLoaded: Boolean,
    capabilityLoadFailed: Boolean,
    secureMessagingServerCompatible: Boolean,
    localOutboxAvailable: Boolean,
): IncomingShareAccess = when {
    capabilityLoadFailed && localOutboxAvailable -> IncomingShareAccess.RECIPIENT_PICKER
    capabilityLoadFailed -> IncomingShareAccess.RETRY_CAPABILITIES
    !capabilitiesLoaded -> IncomingShareAccess.CHECKING
    !secureMessagingServerCompatible -> IncomingShareAccess.UNAVAILABLE
    !localOutboxAvailable -> IncomingShareAccess.CHECKING
    else -> IncomingShareAccess.RECIPIENT_PICKER
}

@Composable
private fun ShareLoadingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("Checking secure messaging") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Verifying that this message can be protected end to end.")
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ShareStatusDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(message)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A share is held only until you send it or close this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            if (dismissLabel != null) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            }
        },
    )
}
