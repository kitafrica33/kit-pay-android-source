package com.kit.wallet.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen explanation for a financial access state that cannot be resolved inline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinancialAccessScreen(
    blockReason: FinancialBlockReason,
    verificationAvailable: Boolean,
    onBack: () -> Unit,
    onVerifyIdentity: () -> Unit,
) {
    val canVerify = blockReason == FinancialBlockReason.VERIFY_IDENTITY && verificationAvailable
    val title = when (blockReason) {
        FinancialBlockReason.VERIFY_IDENTITY -> "Verify your identity"
        FinancialBlockReason.READ_ONLY -> "Payments are read-only"
        FinancialBlockReason.SESSION_ASSURANCE -> "Secure this session"
    }
    val detail = when (blockReason) {
        FinancialBlockReason.READ_ONLY ->
            "This review account can inspect wallet history, but cannot move money."
        FinancialBlockReason.SESSION_ASSURANCE ->
            "Confirm this device or unlock the session before using payment services."
        FinancialBlockReason.VERIFY_IDENTITY -> if (verificationAvailable) {
            "Complete identity verification before using wallet and payment services. " +
                "Messages and calls remain available."
        } else {
            "Wallet and payment services require identity verification. Verification is " +
                "temporarily unavailable; please try again later."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Financial access") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.AccountBalanceWallet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(
                onClick = if (canVerify) onVerifyIdentity else onBack,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            ) {
                Text(if (canVerify) "Continue to verification" else "Go back")
            }
        }
    }
}
