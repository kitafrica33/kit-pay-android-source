package com.kit.wallet.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.BuildConfig
import com.kit.wallet.data.repository.KycStatus
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.StatusChip
import com.kit.wallet.ui.theme.KitTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KycScreen(
    onBack: () -> Unit,
    viewModel: KycViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val launchUrl by viewModel.launchUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var consented by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }
    LaunchedEffect(launchUrl) {
        val url = launchUrl ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            context.startActivity(intent)
        }.onSuccess {
            viewModel.consumeLaunchUrl()
        }.onFailure {
            viewModel.launchFailed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity verification") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh verification status")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VerificationSummary(status)
            Text(
                "Kit Pay uses Didit to securely verify your identity. Provider credentials and identity " +
                    "documents are never stored in this app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status?.caseReference?.let { reference ->
                Text(
                    "Case reference: $reference",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.documents?.forEach { document ->
                Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Badge, contentDescription = null)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(document.type.replace('_', ' ').replaceFirstChar(Char::uppercase))
                            Text(
                                listOfNotNull(document.issuingCountry, document.status)
                                    .joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            when {
                status?.status in setOf("approved", "verified") -> {
                    Text(
                        "Your identity is verified. Your wallet limits will update automatically.",
                        color = KitTheme.colors.success,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                status?.verificationUrl != null -> {
                    KitGreenButton(
                        text = "Continue with Didit",
                        loading = busy,
                        enabled = !busy,
                        onClick = viewModel::continueVerification,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                        Text(
                            "Opens Didit's secure verification page in your browser.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                status?.status in setOf("pending", "in_review", "submitted") -> {
                    Text(
                        "Your verification is being reviewed. Pull down or tap refresh after Didit completes.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(checked = consented, onCheckedChange = { consented = it })
                        Text(
                            "I consent to identity verification and the Kit Pay privacy notice " +
                                "(${BuildConfig.KIT_PRIVACY_NOTICE_VERSION}).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    KitGreenButton(
                        text = "Start secure verification",
                        loading = busy,
                        enabled = consented && !busy,
                        onClick = { viewModel.startVerification(consented) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun VerificationSummary(status: KycStatus?) {
    val value = status?.status ?: "loading"
    val approved = value in setOf("approved", "verified")
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (approved) KitTheme.colors.successContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (approved) Icons.Rounded.CheckCircle else Icons.Rounded.Badge,
                contentDescription = null,
                tint = if (approved) KitTheme.colors.success else MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("KYC status", style = MaterialTheme.typography.labelMedium)
                Text(
                    value.replace('_', ' ').replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (status?.provider == "didit") {
                StatusChip(
                    "Didit",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
