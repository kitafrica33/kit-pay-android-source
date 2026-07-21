package com.kit.wallet.feature.auth

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kit.wallet.ui.components.KitKeypad
import com.kit.wallet.ui.theme.KitWalletTheme
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val PIN_LENGTH = 4

@Composable
fun PinSetupScreen(
    onDone: () -> Unit,
    requireCurrentPin: Boolean = false,
    viewModel: PaymentPinViewModel = hiltViewModel(),
) {
    // PIN digits deliberately use remember, not rememberSaveable: Android must not
    // serialize a payment credential into saved instance state.
    var pin by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf<String?>(null) }
    var firstPin by remember { mutableStateOf<String?>(null) }
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH && !loading) {
            delay(180)
            if (requireCurrentPin && currentPin == null) {
                currentPin = pin
                pin = ""
            } else if (firstPin == null) {
                firstPin = pin
                pin = ""
            } else if (pin != firstPin) {
                firstPin = null
                pin = ""
                viewModel.showError("The PINs did not match. Start again.")
            } else {
                val confirmedPin = pin
                pin = ""
                viewModel.set(confirmedPin, currentPin, onDone)
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                when {
                    requireCurrentPin && currentPin == null -> "Enter your current PIN"
                    firstPin != null -> "Confirm your new PIN"
                    requireCurrentPin -> "Create a new wallet PIN"
                    else -> "Create a wallet PIN"
                },
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your PIN approves payments and unlocks Kit Pay.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(
                                if (i < pin.length) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                CircleShape,
                            ),
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 40.dp),
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "This PIN authorizes the exact amount and recipient for each payment.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }

            Spacer(Modifier.weight(1f))
            KitKeypad(
                onKey = { if (!loading && pin.length < PIN_LENGTH) pin += it },
                onBackspace = { if (!loading) pin = pin.dropLast(1) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinSetupPreview() {
    KitWalletTheme { PinSetupScreen(onDone = {}) }
}
