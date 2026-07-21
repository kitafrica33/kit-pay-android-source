package com.kit.wallet.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLoginScreen(
    onBack: () -> Unit,
    loading: Boolean,
    error: String?,
    onPhoneContinue: (String) -> Unit,
    onEmailContinue: (String, String) -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: (String) -> Unit,
    onVerifyEmail: (String) -> Unit,
    emailRegistrationAvailable: Boolean,
    emailRecoveryAvailable: Boolean,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var useEmail by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must not be written to saved-instance-state bundles.
    var password by remember { mutableStateOf("") }

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
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (useEmail) "Sign in with email" else "What's your number?",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (useEmail) "Use the password for your existing Kit Pay account."
                else "We'll text you a verification code. Standard rates may apply.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            if (useEmail) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !loading,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !loading,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                if (emailRegistrationAvailable || emailRecoveryAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (emailRegistrationAvailable) {
                            TextButton(onClick = { onVerifyEmail(email) }, enabled = !loading) {
                                Text("Verify email")
                            }
                        }
                        if (emailRecoveryAvailable) {
                            TextButton(onClick = { onForgotPassword(email) }, enabled = !loading) {
                                Text("Forgot password?")
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = "+256",
                        onValueChange = {},
                        modifier = Modifier.width(96.dp),
                        readOnly = true,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("Code") },
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { v -> phone = v.filter { it.isDigit() }.take(10) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Phone number") },
                        placeholder = { Text("772 345 678") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        enabled = !loading,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(),
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(28.dp))
            KitGreenButton(
                text = "Continue",
                onClick = {
                    if (useEmail) onEmailContinue(email, password)
                    else onPhoneContinue(phone)
                },
                enabled = if (useEmail) email.contains("@") && password.isNotBlank()
                else phone.length in 9..10,
                loading = loading,
            )
            Spacer(Modifier.height(12.dp))
            KitOutlinedButton(
                text = if (useEmail) "Use phone number instead" else "Use email instead",
                onClick = {
                    if (useEmail) password = ""
                    useEmail = !useEmail
                },
                enabled = !loading,
            )
            Spacer(Modifier.height(8.dp))
            if (emailRegistrationAvailable) {
                TextButton(
                    onClick = onCreateAccount,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("New to Kit Pay? Create an account")
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Protected by Kit Pay security",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PhoneLoginPreview() {
    KitWalletTheme {
        PhoneLoginScreen(
            onBack = {},
            loading = false,
            error = null,
            onPhoneContinue = {},
            onEmailContinue = { _, _ -> },
            onCreateAccount = {},
            onForgotPassword = {},
            onVerifyEmail = {},
            emailRegistrationAvailable = true,
            emailRecoveryAvailable = true,
        )
    }
}
