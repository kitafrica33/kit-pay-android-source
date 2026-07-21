package com.kit.wallet.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.data.auth.isTrustedTotpProvisioningUri
import com.kit.wallet.data.auth.normalizeMfaFactorCode
import com.kit.wallet.ui.security.SecureScreen
import com.kit.wallet.ui.security.copySensitiveText

private enum class MfaCodeAction { REGENERATE, DISABLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MfaScreen(
    onBack: () -> Unit,
    viewModel: MfaViewModel = hiltViewModel(),
) {
    SecureScreen()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<MfaCodeAction?>(null) }

    pendingAction?.let { action ->
        MfaCodeDialog(
            destructive = action == MfaCodeAction.DISABLE,
            onDismiss = { pendingAction = null },
            onConfirm = { currentCode ->
                pendingAction = null
                if (action == MfaCodeAction.DISABLE) viewModel.disable(currentCode)
                else viewModel.regenerateRecoveryCodes(currentCode)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Two-step verification") },
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
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = if (state.enabled) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (state.enabled) "Authenticator protection is on"
                                else "Protect your account with an authenticator",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (state.enabled) "Sign-ins require a current code or a recovery code."
                                else "This adds a second check after your password.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (state.loading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            state.enrollment?.let { enrollment ->
                val trustedProvisioningUri = isTrustedTotpProvisioningUri(
                    enrollment.provisioningUri,
                    enrollment.secret,
                )
                item {
                    Text("1. Add Kit Pay to your authenticator", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Secret: ${enrollment.secret}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                copySensitiveText(context, "Authenticator secret", enrollment.secret)
                            },
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy secret")
                        }
                        TextButton(
                            enabled = trustedProvisioningUri,
                            onClick = {
                                if (trustedProvisioningUri) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                android.net.Uri.parse(enrollment.provisioningUri),
                                            ),
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open app")
                        }
                    }
                    if (!trustedProvisioningUri) {
                        Text(
                            "The authenticator link was not valid. Use the manual secret instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    Text("2. Confirm the current code", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("6-digit code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    KitGreenButton(
                        text = "Turn on two-step verification",
                        loading = state.loading,
                        enabled = code.length == 6,
                        onClick = { viewModel.confirm(code) },
                    )
                }
            }

            if (!state.enabled && state.enrollment == null && !state.loading) {
                item {
                    KitGreenButton(text = "Set up authenticator", onClick = viewModel::enroll)
                }
            }

            if (state.recoveryCodes.isNotEmpty()) {
                item {
                    RecoveryCodes(
                        codes = state.recoveryCodes,
                        onCopy = {
                            copySensitiveText(
                                context,
                                "Kit Pay recovery codes",
                                state.recoveryCodes.joinToString("\n"),
                            )
                        },
                        onHidden = viewModel::clearRecoveryCodes,
                    )
                }
            }

            if (state.enabled && state.enrollment == null) {
                item {
                    KitOutlinedButton(
                        text = "Generate new recovery codes",
                        onClick = { pendingAction = MfaCodeAction.REGENERATE },
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { pendingAction = MfaCodeAction.DISABLE },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Turn off two-step verification", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            state.message?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.secondary) }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun RecoveryCodes(
    codes: List<String>,
    onCopy: () -> Unit,
    onHidden: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recovery codes", style = MaterialTheme.typography.titleMedium)
            Text(
                "Each code works once. Store them somewhere private; Kit Pay will not show them again.",
                style = MaterialTheme.typography.bodySmall,
            )
            codes.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            TextButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Copy all")
            }
            TextButton(onClick = onHidden) { Text("I saved these codes") }
        }
    }
}

@Composable
private fun MfaCodeDialog(
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (destructive) "Turn off protection?" else "Confirm it is you") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    code = value
                        .uppercase()
                        .filter { it.isLetterOrDigit() || it == '-' || it == ' ' }
                        .take(64)
                },
                label = { Text("Authenticator or recovery code") },
                supportingText = { Text("Recovery codes work once.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(code) },
                enabled = normalizeMfaFactorCode(code) != null,
            ) {
                Text(if (destructive) "Turn off" else "Continue")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
