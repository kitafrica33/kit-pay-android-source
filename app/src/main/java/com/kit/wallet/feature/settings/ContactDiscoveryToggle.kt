package com.kit.wallet.feature.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kit.wallet.feature.contacts.ContactSyncEffect
import com.kit.wallet.feature.contacts.ContactSyncEvent
import com.kit.wallet.feature.contacts.ContactSyncStage
import com.kit.wallet.feature.contacts.contactSyncDisclosurePresentation
import com.kit.wallet.feature.contacts.decideContactSync

/**
 * The whole "let people find me from my contacts" decision, behind one `(Boolean) -> Unit`.
 *
 * Turning it on is three things and not one: the prominent disclosure, the Android permission, and
 * this device's recorded consent to upload. Keeping them together means account setup and Settings
 * cannot drift into asking differently for the same thing, and that the switch only ever reads
 * "on" when the upload can actually happen. Turning it off is one thing, and takes effect at once.
 *
 * The disclosure copy is the same [contactSyncDisclosurePresentation] the contacts screen shows.
 */
@Composable
internal fun rememberContactDiscoveryToggle(
    consentGranted: Boolean,
    consentAvailable: Boolean,
    onConsentChanged: (Boolean) -> Unit,
): ContactDiscoveryToggleBinding {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var stage by rememberSaveable { mutableStateOf(ContactSyncStage.IDLE) }
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    var permissionGranted by rememberSaveable {
        mutableStateOf(context.hasContactPermission())
    }

    fun reconcilePermission() {
        val granted = context.hasContactPermission()
        permissionGranted = granted
        // Android can revoke an unused permission or the person can revoke it in Settings while
        // this screen is stopped. A visible "on" switch must never outlive that grant.
        if (consentGranted && !granted) onConsentChanged(false)
    }

    LaunchedEffect(consentGranted, consentAvailable) { reconcilePermission() }
    DisposableEffect(lifecycleOwner, consentGranted, consentAvailable) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reconcilePermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        val decision = decideContactSync(
            stage = stage,
            event = ContactSyncEvent.PERMISSION_RESULT,
            permissionGranted = granted,
        )
        stage = decision.nextStage
        // Only when Android will no longer show the prompt at all is there anything left to say.
        // Someone who just tapped "Don't allow" has been heard; repeating the ask is nagging.
        permissionBlocked = !granted && !context.canPromptForContacts()
        if (decision.effect == ContactSyncEffect.SYNC) onConsentChanged(true)
    }

    if (stage == ContactSyncStage.DISCLOSURE) {
        val disclosure = contactSyncDisclosurePresentation()
        val cancel = {
            stage = decideContactSync(stage = stage, event = ContactSyncEvent.CANCEL).nextStage
        }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(disclosure.title) },
            text = { Text(disclosure.body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val granted = context.hasContactPermission()
                        permissionGranted = granted
                        val decision = decideContactSync(
                            stage = stage,
                            event = ContactSyncEvent.AGREE,
                            permissionGranted = granted,
                        )
                        stage = decision.nextStage
                        when (decision.effect) {
                            ContactSyncEffect.REQUEST_PERMISSION ->
                                permission.launch(Manifest.permission.READ_CONTACTS)
                            ContactSyncEffect.SYNC -> onConsentChanged(true)
                            ContactSyncEffect.NONE -> Unit
                        }
                    },
                ) { Text(disclosure.confirmLabel) }
            },
            dismissButton = { TextButton(onClick = cancel) { Text(disclosure.cancelLabel) } },
        )
    }

    if (permissionBlocked) {
        AlertDialog(
            onDismissRequest = { permissionBlocked = false },
            title = { Text("Contacts access is off") },
            text = {
                Text(
                    "Android is not letting Kit Pay read the contacts on this phone, so there is " +
                        "nothing to match. Turn on Contacts for Kit Pay in Android settings, " +
                        "then try again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionBlocked = false
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    },
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { permissionBlocked = false }) { Text("Not now") }
            },
        )
    }

    return ContactDiscoveryToggleBinding(
        checked = contactDiscoveryToggleChecked(
            consentGranted,
            consentAvailable,
            permissionGranted,
        ),
        enabled = consentAvailable,
        onCheckedChange = { wanted ->
            if (consentAvailable) {
                if (wanted) {
                    stage = decideContactSync(stage = stage, event = ContactSyncEvent.START).nextStage
                } else {
                    onConsentChanged(false)
                }
            }
        },
    )
}

internal data class ContactDiscoveryToggleBinding(
    val checked: Boolean,
    val enabled: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

internal fun contactDiscoveryToggleChecked(
    consentGranted: Boolean,
    consentAvailable: Boolean,
    permissionGranted: Boolean,
): Boolean = consentGranted && consentAvailable && permissionGranted

private fun Context.hasContactPermission(): Boolean = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.READ_CONTACTS,
) == PackageManager.PERMISSION_GRANTED

/** Whether the system would still put the Contacts permission prompt in front of the user. */
private fun Context.canPromptForContacts(): Boolean {
    val activity = findActivity() ?: return true
    return ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.READ_CONTACTS,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
