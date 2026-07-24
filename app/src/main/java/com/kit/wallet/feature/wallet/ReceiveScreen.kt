package com.kit.wallet.feature.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RequestPage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitPrimaryButton
import com.kit.wallet.ui.components.KitOutlinedButton
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.model.formatKitTag
import com.kit.wallet.ui.theme.KitWalletTheme
import kotlinx.coroutines.launch

internal const val RECEIVE_MY_QR_COMING_SOON = "Coming soon: My QR."
internal const val RECEIVE_SET_AMOUNT_COMING_SOON = "Coming soon: Set amount."
internal const val RECEIVE_REQUEST_AMOUNT_COMING_SOON = "Coming soon: Request amount."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    requestMoneyAvailable: Boolean,
    onRequestAmount: () -> Unit,
    onBack: () -> Unit,
    viewModel: ReceiveViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ReceiveContent(
        profile = profile,
        requestMoneyAvailable = requestMoneyAvailable,
        snackbarHostState = remember { SnackbarHostState() },
        onBack = onBack,
        onRequestAmount = onRequestAmount,
        onShare = {
            launchTextShare(
                context = context,
                chooserTitle = "Share Kit Pay details",
                text = receiveDetailsShareText(profile),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiveContent(
    profile: UserProfile,
    requestMoneyAvailable: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRequestAmount: () -> Unit,
    onShare: () -> Unit,
) {
    val tag = formatKitTag(profile.tag)
    val scope = rememberCoroutineScope()
    val showComingSoon: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive money") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(
                Icons.Rounded.QrCode2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                profile.name.ifBlank { "Kit Pay user" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                listOf(tag, profile.phone).filter(String::isNotBlank).joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Share your Kit Pay tag or verified phone number to receive money. " +
                    "A scannable Kit Pay QR will appear here when QR receiving is ready.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showComingSoon(RECEIVE_MY_QR_COMING_SOON) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("receive-action-my-qr"),
                ) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("My QR")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = {
                        if (requestMoneyAvailable) onRequestAmount()
                        else showComingSoon(RECEIVE_REQUEST_AMOUNT_COMING_SOON)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("receive-action-request-amount"),
                ) {
                    Icon(Icons.Rounded.RequestPage, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Request amount")
                }
            }
            Spacer(Modifier.height(12.dp))
            KitOutlinedButton(
                text = "Set amount",
                onClick = { showComingSoon(RECEIVE_SET_AMOUNT_COMING_SOON) },
                modifier = Modifier.testTag("receive-action-set-amount"),
            )
            Spacer(Modifier.height(12.dp))
            KitPrimaryButton(
                text = "Share",
                onClick = onShare,
                modifier = Modifier.testTag("receive-action-share"),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReceivePreview() {
    KitWalletTheme {
        ReceiveContent(
            profile = UserProfile(DemoData.USER_NAME, DemoData.USER_PHONE, "@amina", "KYC verified • Level 2"),
            requestMoneyAvailable = true,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onRequestAmount = {},
            onShare = {},
        )
    }
}
