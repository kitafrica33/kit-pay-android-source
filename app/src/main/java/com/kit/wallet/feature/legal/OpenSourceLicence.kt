package com.kit.wallet.feature.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.net.URI

internal const val KIT_SOURCE_REPOSITORY_URL =
    "https://github.com/kitafrica33/kit-pay-android-source"

internal data class OpenSourceLicencePresentation(
    val notice: String,
    val sourceUrl: String,
)

internal fun openSourceLicencePresentation(
    versionName: String,
    versionCode: Int,
): OpenSourceLicencePresentation {
    require(versionName.matches(Regex("[0-9]+(?:[.][0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?"))) {
        "Invalid release version name"
    }
    require(versionCode > 0) { "Invalid release version code" }
    return OpenSourceLicencePresentation(
        notice = "Copyright (C) 2026 KIT POS UGANDA LIMITED.\n\n" +
            "Kit Pay for Android is free software: you may redistribute and/or modify it " +
            "under GNU AGPL version 3 only. It is provided without any warranty, including " +
            "the implied warranties of merchantability or fitness for a particular purpose.",
        sourceUrl = "$KIT_SOURCE_REPOSITORY_URL/releases/tag/v$versionName-code$versionCode",
    )
}

internal fun isTrustedKitReleaseSourceUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.path.matches(
            Regex(
                "/kitafrica33/kit-pay-android-source/releases/tag/" +
                    "v[0-9]+(?:[.][0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?-code[1-9][0-9]*",
            ),
        ) &&
        uri.userInfo == null &&
        uri.port == -1 &&
        uri.query == null &&
        uri.fragment == null
}.getOrDefault(false)

@Composable
internal fun OpenSourceLicenceDialog(
    presentation: OpenSourceLicencePresentation,
    onOpenSource: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showFullLicence by rememberSaveable { mutableStateOf(false) }
    val thirdPartyNotices = remember(context) {
        runCatching {
            context.assets.open("legal/THIRD_PARTY_NOTICES.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrDefault("The bundled third-party notices could not be read.")
    }
    val fullLicence = remember(context) {
        runCatching {
            context.assets.open("legal/AGPL-3.0-only.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrDefault("The bundled GNU AGPL text could not be read.")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licence") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(presentation.notice)
                Text("Corresponding source for this exact version", fontWeight = FontWeight.SemiBold)
                Text(
                    presentation.sourceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Notices", fontWeight = FontWeight.SemiBold)
                Text(thirdPartyNotices, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showFullLicence = !showFullLicence }) {
                    Text(if (showFullLicence) "Hide full GNU AGPL" else "Read full GNU AGPL")
                }
                if (showFullLicence) {
                    Text(fullLicence, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onOpenSource) { Text("Source code") }
        },
    )
}
