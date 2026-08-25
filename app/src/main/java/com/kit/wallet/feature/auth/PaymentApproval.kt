package com.kit.wallet.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.auth.PaymentApprovalMethods
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitPinEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PIN_LENGTH = 4

@HiltViewModel
class PaymentApprovalViewModel @Inject constructor(
    private val methods: PaymentApprovalMethods,
) : ViewModel() {
    private val mutableBiometricsAvailable = MutableStateFlow(false)
    val biometricsAvailable = mutableBiometricsAvailable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableBiometricsAvailable.value =
                withContext(Dispatchers.IO) { methods.biometricsAvailable() }
        }
    }
}

/**
 * Whether biometric approval should be offered, re-checked every time the app comes forward.
 *
 * Biometric enrollment is changed in system settings, which means leaving Kit Pay and coming back:
 * re-reading on resume is what stops the app offering a prompt whose key Android invalidated while
 * it was in the background.
 */
@Composable
fun rememberBiometricApprovalAvailable(
    viewModel: PaymentApprovalViewModel = hiltViewModel(),
): Boolean {
    val available by viewModel.biometricsAvailable.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return available
}

/**
 * The approval step of a payment: one button that does the right thing.
 *
 * When this device can approve with biometrics, that is the whole interaction — the button raises
 * the system prompt and no PIN is ever asked for, because being asked for a PIN you did not need
 * is the part people find tiring. The wallet PIN stays one tap away as a fallback, and takes over
 * entirely when biometrics are not available.
 *
 * The PIN itself is entered on a full screen of its own rather than in a field inside the sheet:
 * a payment credential deserves the whole screen, and it keeps the soft keyboard — with its
 * suggestions, its clipboard and its third-party input methods — away from it.
 *
 * [onApprove] is called with an empty string for the biometric path, which is what
 * `PaymentAuthorizer` reads as "sign this challenge instead of verifying a PIN".
 */
@Composable
fun PaymentApproval(
    actionLabel: String,
    biometricsAvailable: Boolean,
    busy: Boolean,
    error: String?,
    onApprove: (paymentPin: String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pinTitle: String = "Enter your wallet PIN",
    pinSubtitle: String = "This PIN authorizes this exact payment.",
) {
    var enteringPin by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (error != null && !enteringPin) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            )
        }
        KitGreenButton(
            text = actionLabel,
            icon = if (biometricsAvailable) Icons.Rounded.Fingerprint else Icons.Rounded.Lock,
            loading = busy && !enteringPin,
            enabled = enabled && !busy,
            onClick = { if (biometricsAvailable) onApprove("") else enteringPin = true },
        )
        Spacer(Modifier.height(6.dp))
        if (biometricsAvailable) {
            TextButton(onClick = { enteringPin = true }, enabled = !busy) {
                Text("Use wallet PIN instead")
            }
        } else {
            Text(
                "Kit Pay will ask for your four-digit wallet PIN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (enteringPin) {
        WalletPinScreen(
            title = pinTitle,
            subtitle = pinSubtitle,
            actionLabel = actionLabel,
            busy = busy,
            error = error,
            biometricsAvailable = biometricsAvailable,
            onSubmit = onApprove,
            onUseBiometrics = {
                enteringPin = false
                onApprove("")
            },
            onDismiss = { enteringPin = false },
        )
    }
}

/**
 * The full-screen wallet PIN pad, shown over whatever asked for it.
 *
 * A dialog rather than a destination so that every sheet and screen that needs a PIN gets the same
 * screen without any of them having to own a route, and so the payment underneath is still there,
 * unchanged, when the PIN is dismissed.
 */
@Composable
private fun WalletPinScreen(
    title: String,
    subtitle: String,
    actionLabel: String,
    busy: Boolean,
    error: String?,
    biometricsAvailable: Boolean,
    onSubmit: (String) -> Unit,
    onUseBiometrics: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The digits deliberately use remember, not rememberSaveable: a payment credential must never
    // be written into Android's saved instance state.
    var pin by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH && !busy) {
            val entered = pin
            pin = ""
            submitted = true
            onSubmit(entered)
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                    }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.weight(1f))
                KitPinEntry(
                    title = title,
                    subtitle = subtitle,
                    pin = pin,
                    onPin = { pin = it },
                    busy = busy,
                    // Only this attempt's failure belongs on this screen. An error the payment was
                    // already showing before the PIN was opened is not something the PIN caused.
                    error = if (submitted) error else null,
                    header = {
                        Box(
                            Modifier
                                .size(64.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    CircleShape,
                                ),
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
                                    Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    },
                    footer = {
                        if (biometricsAvailable) {
                            TextButton(onClick = onUseBiometrics, enabled = !busy) {
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
                Spacer(Modifier.weight(1f))
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
