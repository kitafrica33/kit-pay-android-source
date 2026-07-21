package com.kit.wallet.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.data.auth.normalizeProfileTag

@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onRegistered: () -> Unit,
    viewModel: AccountAccessViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must never be serialized into Android saved-instance-state bundles.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    AccountAccessScaffold(
        title = "Create your Kit Pay account",
        subtitle = "Choose your username / display name and unique @tag, then add an email address you can verify.",
        onBack = onBack,
        feedback = state,
    ) {
        AccountTextField(name, { name = it }, "Username / display name")
        AccountTextField(
            tag,
            { value -> tag = normalizeProfileTag(value) },
            "Unique Kit Pay tag (without @)",
        )
        AccountTextField(
            email,
            { email = it },
            "Email address",
            keyboardType = KeyboardType.Email,
        )
        AccountTextField(
            password,
            { password = it },
            "Password",
            keyboardType = KeyboardType.Password,
            password = true,
        )
        AccountTextField(
            confirmation,
            { confirmation = it },
            "Confirm password",
            keyboardType = KeyboardType.Password,
            password = true,
        )
        Text(
            "Use at least 12 characters with uppercase, lowercase and a number.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        KitGreenButton(
            text = "Create account",
            loading = state.loading,
            enabled = name.isNotBlank() && tag.isNotBlank() &&
                email.isNotBlank() && password.isNotBlank(),
            onClick = {
                viewModel.register(name, tag, email, password, confirmation, onRegistered)
            },
        )
    }
}

@Composable
fun VerifyEmailScreen(
    onBack: () -> Unit,
    onVerified: () -> Unit,
    viewModel: AccountAccessViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var token by remember { mutableStateOf(state.identityToken) }
    var email by rememberSaveable(state.email) { mutableStateOf(state.email) }

    AccountAccessScaffold(
        title = "Verify your email",
        subtitle = state.verificationDestination?.let {
            "Paste the secure verification token sent to $it."
        } ?: "Paste the secure verification token from your Kit Pay email.",
        onBack = onBack,
        feedback = state,
    ) {
        AccountTextField(
            value = token,
            onValueChange = {
                token = it.filterNot(Char::isWhitespace)
                viewModel.setIdentityToken(token)
            },
            label = "Verification token",
            trailing = {
                IconButton(
                    onClick = {
                        clipboard.getText()?.text?.let {
                            token = it.filterNot(Char::isWhitespace)
                            viewModel.setIdentityToken(token)
                        }
                    },
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste token")
                }
            },
        )
        KitGreenButton(
            text = "Verify email",
            loading = state.loading,
            enabled = token.length >= 64,
            onClick = { viewModel.verifyEmail(token, onVerified) },
        )
        Spacer(Modifier.height(16.dp))
        AccountTextField(
            email,
            {
                email = it
                viewModel.setEmail(it)
            },
            "Email address for resend",
            keyboardType = KeyboardType.Email,
        )
        KitOutlinedButton(
            text = "Send a new token",
            enabled = email.contains('@') && !state.loading,
            onClick = viewModel::resendVerification,
        )
    }
}

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onRequested: () -> Unit,
    viewModel: AccountAccessViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var email by rememberSaveable(state.email) { mutableStateOf(state.email) }

    AccountAccessScaffold(
        title = "Reset your password",
        subtitle = "We will email reset instructions if the address belongs to an eligible account.",
        onBack = onBack,
        feedback = state,
    ) {
        AccountTextField(
            email,
            {
                email = it
                viewModel.setEmail(it)
            },
            "Email address",
            keyboardType = KeyboardType.Email,
        )
        KitGreenButton(
            text = "Send reset instructions",
            loading = state.loading,
            enabled = email.contains('@'),
            onClick = { viewModel.forgotPassword(email, onRequested) },
        )
    }
}

@Composable
fun ResetPasswordScreen(
    onBack: () -> Unit,
    onReset: () -> Unit,
    viewModel: AccountAccessViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var token by remember { mutableStateOf(state.identityToken) }
    // Reset credentials are intentionally process-local and non-saveable.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    AccountAccessScaffold(
        title = "Choose a new password",
        subtitle = "Paste the single-use token from your password reset email.",
        onBack = onBack,
        feedback = state,
    ) {
        AccountTextField(
            value = token,
            onValueChange = {
                token = it.filterNot(Char::isWhitespace)
                viewModel.setIdentityToken(token)
            },
            label = "Reset token",
            trailing = {
                IconButton(
                    onClick = {
                        clipboard.getText()?.text?.let {
                            token = it.filterNot(Char::isWhitespace)
                            viewModel.setIdentityToken(token)
                        }
                    },
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste token")
                }
            },
        )
        AccountTextField(
            password,
            { password = it },
            "New password",
            keyboardType = KeyboardType.Password,
            password = true,
        )
        AccountTextField(
            confirmation,
            { confirmation = it },
            "Confirm new password",
            keyboardType = KeyboardType.Password,
            password = true,
        )
        KitGreenButton(
            text = "Update password",
            loading = state.loading,
            enabled = token.length >= 64 && password.isNotBlank(),
            onClick = { viewModel.resetPassword(token, password, confirmation, onReset) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountAccessScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    feedback: AccountAccessUiState,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
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
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
            feedback.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            feedback.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = trailing,
        singleLine = !label.contains("token", ignoreCase = true),
        minLines = if (label.contains("token", ignoreCase = true)) 2 else 1,
        shape = MaterialTheme.shapes.medium,
    )
}
