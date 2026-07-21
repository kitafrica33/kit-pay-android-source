package com.kit.wallet.feature.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitPrimaryButton
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.model.formatKitTag
import com.kit.wallet.ui.theme.KitWalletTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onBack: () -> Unit,
    viewModel: ReceiveViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    ReceiveContent(profile, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveContent(profile: UserProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val tag = formatKitTag(profile.tag)

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
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.Share,
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
                "Share your Kit Pay tag or verified phone number with another Kit Pay user to receive money.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            KitPrimaryButton(
                text = "Share payment details",
                onClick = {
                    launchTextShare(
                        context = context,
                        chooserTitle = "Share Kit Pay details",
                        text = receiveDetailsShareText(profile),
                    )
                },
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
            onBack = {},
        )
    }
}
