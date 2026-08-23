package com.kit.wallet.feature.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

@Composable
fun SessionUnlockGate(
    state: SessionAssuranceUiState,
    onUnlock: (String) -> Unit,
    onCreatePin: (String, String) -> Unit = { _, _ -> },
    onRetry: () -> Unit,
    onRequestBiometric: () -> Unit,
    onBiometricSuccess: (BiometricUnlockRequest, java.security.Signature) -> Unit,
    onBiometricCancelled: (String?) -> Unit,
    onSignOut: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    // A brand-new account has no wallet PIN and no biometric key yet: creating the first PIN is
    // the unlock method the server accepts for it, so the gate offers it inline.
    val needsFirstPin = !state.checking && state.methods.isEmpty()
    val activity = LocalContext.current as? FragmentActivity
    LaunchedEffect(state.biometricRequest) {
        val request = state.biometricRequest ?: return@LaunchedEffect
        val host = activity ?: run {
            onBiometricCancelled("Biometric authentication is unavailable")
            return@LaunchedEffect
        }
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val signature = result.cryptoObject?.signature
                    if (signature == null) onBiometricCancelled("Biometric signature was unavailable")
                    else onBiometricSuccess(request, signature)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onBiometricCancelled(errString.toString())
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Kit Pay")
                .setSubtitle("Confirm your identity on this device")
                .setNegativeButtonText("Use wallet PIN")
                .build(),
            BiometricPrompt.CryptoObject(request.signature),
        )
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Unlock Kit Pay") },
        text = {
            Column {
                if (state.checking) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Checking this session…")
                } else {
                    Text(
                        state.error ?: when {
                            needsFirstPin ->
                                "Create a four-digit wallet PIN to secure this account and " +
                                    "unlock this login."
                            else -> "Enter your wallet PIN to continue on this device."
                        },
                    )
                    if ("pin" in state.methods) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                            label = { Text("Wallet PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (needsFirstPin) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                            label = { Text("New wallet PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmPin,
                            onValueChange = { confirmPin = it.filter(Char::isDigit).take(4) },
                            label = { Text("Confirm wallet PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.checking -> Unit
                "pin" in state.methods -> Button(
                    onClick = { onUnlock(pin) },
                    enabled = pin.length == 4 && !state.unlocking,
                ) { Text(if (state.unlocking) "Unlocking…" else "Unlock") }
                needsFirstPin -> Button(
                    onClick = { onCreatePin(pin, confirmPin) },
                    enabled = pin.length == 4 && confirmPin.length == 4 && !state.unlocking,
                ) { Text(if (state.unlocking) "Saving…" else "Create PIN and unlock") }
                state.error != null -> Button(onClick = onRetry) { Text("Retry") }
            }
        },
        dismissButton = {
            Column {
                if (state.biometricReady && "biometric_signature" in state.methods &&
                    state.biometricRequest == null
                ) {
                    OutlinedButton(onClick = onRequestBiometric) { Text("Use biometrics") }
                }
                OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            }
        },
    )
}
