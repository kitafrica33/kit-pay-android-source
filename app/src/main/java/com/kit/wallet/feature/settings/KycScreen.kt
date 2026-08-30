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
import com.kit.wallet.data.repository.KycVerificationState
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.StatusChip

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

            // An account that has already proven its identity is never asked to prove it again.
            // Whatever is outstanding after that belongs to this device, and is described as
            // such, so the two can never be mistaken for one another.
            val current = status
            val accountVerified = current?.accountState == KycVerificationState.VERIFIED
            if (accountVerified) {
                Text(
                    "Your identity is verified. Your wallet limits will update automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            val outstanding = current?.deviceState
            if (accountVerified && current?.deviceCheckRequired == true) {
                Text(
                    "This device still needs to confirm it's you before it can move money. " +
                        "Your verified identity stays as it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                current == null || (accountVerified && !current.deviceCheckRequired) -> Unit
                // A live provider session outranks a status word: the server only publishes the
                // link while it is genuinely resumable, so finishing it is always better than
                // starting a second one.
                current.resumable -> {
                    KitGreenButton(
                        text = if (accountVerified) "Confirm this device" else "Continue with Didit",
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
                outstanding == KycVerificationState.IN_REVIEW -> {
                    Text(
                        "Your check is with our reviewers. There is nothing more to send — this " +
                            "page updates on its own, and you can tap refresh any time.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    reviewReason(current.decisionCode)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // A word this build does not know is a gap in the app, not a verdict on the user.
                // Starting a verification off the back of one is precisely the mistake that had
                // verified people queueing up for a check they had already passed.
                outstanding == KycVerificationState.UNKNOWN -> {
                    Text(
                        "We're checking your verification status. Tap refresh if this doesn't " +
                            "settle in a moment.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    if (outstanding == KycVerificationState.ACTION_NEEDED) {
                        Text(
                            "The last check couldn't be completed. You can try again below.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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
                        text = when {
                            accountVerified -> "Confirm this device"
                            outstanding == KycVerificationState.ACTION_NEEDED -> "Try verification again"
                            else -> "Start secure verification"
                        },
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

/**
 * Plain-English reasons for the decision codes a reviewer's queue can produce.
 *
 * Anything unrecognised returns null rather than being shown raw: an internal code tells the user
 * nothing and reads as a fault they caused.
 */
internal fun reviewReason(decisionCode: String?): String? = when (decisionCode?.uppercase()) {
    "DIDIT_IDENTITY_NAME_REVIEW_REQUIRED" ->
        "The name on your document needs a manual check against your Kit Pay profile."
    // Deliberately unspecific, and it must stay that way. This one code covers a document image a
    // reviewer has to look at *and* a document already registered to another Kit Pay account, and
    // the server keeps it generic for that second case: telling somebody holding a document that
    // is not theirs that it is "already registered" confirms precisely what they were testing for.
    "DIDIT_IDENTITY_DOCUMENT_REVIEW_REQUIRED" ->
        "Your document needs a manual check by our team. Nothing more is needed from you."
    "DIDIT_SCREENING_IDENTITY_REVIEW_REQUIRED" ->
        "Your details need a manual check against our compliance records."
    else -> null
}

/**
 * The one-line verdict at the top of the screen, phrased from the *account's* standing.
 *
 * A device check that is still outstanding is described below this card, not here, so the card
 * never contradicts a verification the user has already passed.
 */
internal fun verificationSummaryLabel(status: KycStatus?): String = when {
    status == null -> "Checking…"
    status.accountState == KycVerificationState.VERIFIED && status.deviceCheckRequired ->
        "Verified • confirming this device"
    else -> when (status.accountState) {
        KycVerificationState.VERIFIED -> "Verified"
        KycVerificationState.IN_REVIEW -> "In review"
        KycVerificationState.ACTION_NEEDED -> "Needs another try"
        KycVerificationState.NOT_STARTED -> "Not started"
        KycVerificationState.UNKNOWN -> "Checking…"
    }
}

@Composable
private fun VerificationSummary(status: KycStatus?) {
    val value = verificationSummaryLabel(status)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Badge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Identity", style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
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
