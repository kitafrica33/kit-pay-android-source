package com.kit.wallet.feature.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.ui.components.KitPinEntry

private const val PIN_LENGTH = 4

/**
 * The screen that stands between a locked login and the app.
 *
 * Two things about it are deliberate. It is a screen, not a dialog box: unlocking a wallet is the
 * whole task at that moment, and a half-height alert with text fields in it neither looks nor feels
 * like the front door of something holding money. And when this device can unlock with biometrics,
 * it does — the system prompt is raised on arrival rather than waiting behind a button, because
 * asking someone to type a PIN they did not need is the thing that makes an app tiring to open.
 *
 * The PIN is the fallback, always reachable, and is entered on the same branded keypad used
 * everywhere else in Kit Pay rather than in a text field.
 */
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
    onVerifyIdentity: () -> Unit = {},
) {
    // A brand-new account has no wallet PIN and no biometric key yet: creating the first PIN is
    // the unlock method the server accepts for it, so the gate offers it inline.
    val needsFirstPin = !state.checking && state.methods.isEmpty() && !state.deviceIdentityRequired
    // No PIN or biometric can unlock this login until the device identity check finishes, so the
    // gate never shows a PIN form it knows the server will refuse.
    val needsIdentity = !state.checking && state.deviceIdentityRequired
    val identityPending = state.deviceIdentityStatus.equals("pending", ignoreCase = true) ||
        state.deviceIdentityStatus.equals("review", ignoreCase = true)
    val biometricsOffered = !state.checking && !needsIdentity && state.biometricReady &&
        "biometric_signature" in state.methods

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

    // Raised once, on arrival. Cancelling the system prompt leaves this true, so someone who chose
    // "Use wallet PIN" gets the keypad instead of the prompt springing back at them.
    var biometricsAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricsOffered) {
        if (biometricsOffered && !biometricsAttempted) {
            biometricsAttempted = true
            onRequestBiometric()
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                when {
                    state.checking -> UnlockChecking()
                    needsIdentity -> UnlockNeedsIdentity(
                        state = state,
                        pending = identityPending,
                        onRetry = onRetry,
                        onVerifyIdentity = onVerifyIdentity,
                    )
                    needsFirstPin -> UnlockCreatePin(state = state, onCreatePin = onCreatePin)
                    "pin" in state.methods -> UnlockWithPin(
                        state = state,
                        biometricsOffered = biometricsOffered,
                        onUnlock = onUnlock,
                        onUseBiometrics = onRequestBiometric,
                    )
                    biometricsOffered -> UnlockBiometricsOnly(
                        state = state,
                        onUseBiometrics = onRequestBiometric,
                    )
                    else -> UnlockUnavailable(state = state, onRetry = onRetry)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSignOut, enabled = !state.unlocking) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun UnlockChecking() {
    UnlockBadge(busy = true)
    Text("Checking this session…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "One moment while Kit Pay confirms this device.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

@Composable
private fun UnlockNeedsIdentity(
    state: SessionAssuranceUiState,
    pending: Boolean,
    onRetry: () -> Unit,
    onVerifyIdentity: () -> Unit,
) {
    val failed = state.deviceIdentityStatus.equals("failed", ignoreCase = true)
    UnlockBadge(icon = Icons.Rounded.Badge)
    Text("Verify your identity", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        state.error ?: when {
            pending ->
                "Your identity check is being reviewed. This usually finishes in a few minutes " +
                    "— tap Check again once you're done."
            failed ->
                "Your last identity check did not go through. Verify your identity again to " +
                    "continue."
            else ->
                "To keep your money safe, verify your identity once on this phone. It only takes " +
                    "a minute."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.error != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
    Spacer(Modifier.height(28.dp))
    Column(Modifier.padding(horizontal = 32.dp)) {
        if (pending) {
            KitGreenButton(text = "Check again", onClick = onRetry)
        } else {
            KitGreenButton(text = "Verify identity", onClick = onVerifyIdentity)
            Spacer(Modifier.height(10.dp))
            KitOutlinedButton(text = "I've already verified", onClick = onRetry)
        }
    }
}

@Composable
private fun UnlockCreatePin(
    state: SessionAssuranceUiState,
    onCreatePin: (String, String) -> Unit,
) {
    // remember, not rememberSaveable: a wallet PIN must never reach saved instance state.
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pin) {
        if (pin.length != PIN_LENGTH || state.unlocking) return@LaunchedEffect
        val entered = pin
        pin = ""
        val first = firstPin
        if (first == null) {
            firstPin = entered
        } else {
            firstPin = null
            onCreatePin(first, entered)
        }
    }

    KitPinEntry(
        title = if (firstPin == null) "Create a wallet PIN" else "Confirm your new PIN",
        subtitle = if (firstPin == null) {
            "Four digits. This PIN secures your account and unlocks Kit Pay."
        } else {
            "Enter the same four digits once more."
        },
        pin = pin,
        onPin = { pin = it },
        busy = state.unlocking,
        error = state.error,
        header = { UnlockBadge(busy = state.unlocking) },
    )
}

@Composable
private fun UnlockWithPin(
    state: SessionAssuranceUiState,
    biometricsOffered: Boolean,
    onUnlock: (String) -> Unit,
    onUseBiometrics: () -> Unit,
) {
    // remember, not rememberSaveable: a wallet PIN must never reach saved instance state.
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH && !state.unlocking) {
            val entered = pin
            pin = ""
            onUnlock(entered)
        }
    }

    KitPinEntry(
        title = "Enter your wallet PIN",
        subtitle = "Unlock Kit Pay on this device.",
        pin = pin,
        onPin = { pin = it },
        busy = state.unlocking,
        error = state.error,
        header = { UnlockBadge(busy = state.unlocking) },
        footer = {
            if (biometricsOffered) {
                TextButton(onClick = onUseBiometrics, enabled = !state.unlocking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Use fingerprint or face instead")
                    }
                }
            }
        },
    )
}

/** The server accepts biometrics and nothing else on this login, so there is no PIN to fall back to. */
@Composable
private fun UnlockBiometricsOnly(
    state: SessionAssuranceUiState,
    onUseBiometrics: () -> Unit,
) {
    UnlockBadge(icon = Icons.Rounded.Fingerprint, busy = state.unlocking)
    Text("Unlock Kit Pay", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        state.error ?: "Confirm it's you with your fingerprint or face.",
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.error != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
    Spacer(Modifier.height(28.dp))
    Column(Modifier.padding(horizontal = 32.dp)) {
        KitGreenButton(
            text = "Unlock",
            icon = Icons.Rounded.Fingerprint,
            loading = state.unlocking,
            onClick = onUseBiometrics,
        )
    }
}

@Composable
private fun UnlockUnavailable(state: SessionAssuranceUiState, onRetry: () -> Unit) {
    UnlockBadge()
    Text("Unlock Kit Pay", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        state.error ?: "Kit Pay could not verify this session.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
    Spacer(Modifier.height(28.dp))
    Column(Modifier.padding(horizontal = 32.dp)) {
        KitGreenButton(text = "Try again", onClick = onRetry, loading = state.unlocking)
    }
}

@Composable
private fun UnlockBadge(
    icon: ImageVector = Icons.Rounded.Lock,
    busy: Boolean = false,
) {
    Box(
        Modifier
            .size(64.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
}
