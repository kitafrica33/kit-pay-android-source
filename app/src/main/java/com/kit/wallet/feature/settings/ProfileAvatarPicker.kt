package com.kit.wallet.feature.settings

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kit.wallet.ui.components.KitAvatar
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The profile photo, and the two ways to change it.
 *
 * Shared by account setup and Edit profile so that setting up an account shows the photo the
 * account already has rather than pretending it has none. Someone signing in on a new phone should
 * see their own face and be able to keep it with one glance — being asked to "add a photo" you
 * already added, on an account you already own, reads as the app not knowing who you are.
 */
@Composable
fun ProfileAvatarPicker(
    name: String,
    avatarUrl: String?,
    uploading: Boolean,
    onAvatarSelected: (ByteArray) -> Unit,
    onSelectionError: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Centred and large, for a setup step where the photo is the point of the screen. */
    prominent: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var captureTarget by remember { mutableStateOf<Uri?>(null) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var showSources by remember { mutableStateOf(false) }

    fun prepareAvatar(uri: Uri, onFinished: () -> Unit = {}) {
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) {
                runCatching { transcodeProfileAvatar(context.contentResolver, uri) }.getOrNull()
            }
            onFinished()
            if (jpeg == null) {
                onSelectionError(
                    "That image could not be read on this device. Try another photo or take a " +
                        "new one.",
                )
            } else {
                onAvatarSelected(jpeg)
            }
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) prepareAvatar(uri) }
    val takeAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = captureTarget
        val file = captureFile
        captureTarget = null
        captureFile = null
        if (saved && target != null) prepareAvatar(target) { file?.delete() } else file?.delete()
    }

    fun launchAvatarCapture() {
        val directory = File(context.cacheDir, "chat-capture").apply { mkdirs() }
        val file = File(directory, "avatar-${UUID.randomUUID()}.jpg")
        captureFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.chatmedia", file)
        captureTarget = uri
        takeAvatar.launch(uri)
    }

    // The manifest declares CAMERA, so ACTION_IMAGE_CAPTURE throws SecurityException unless the
    // permission has actually been granted first.
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchAvatarCapture()
        } else {
            onSelectionError("Camera access is needed to take a profile photo.")
        }
    }

    val actionLabel = when {
        uploading -> "Uploading photo…"
        avatarUrl != null && prominent -> "Use a different photo"
        avatarUrl != null -> "Change photo"
        else -> "Add photo"
    }

    @Composable
    fun ChangeButton() {
        Box {
            TextButton(
                onClick = { if (!uploading) showSources = true },
                enabled = !uploading,
            ) {
                Text(actionLabel)
            }
            DropdownMenu(expanded = showSources, onDismissRequest = { showSources = false }) {
                DropdownMenuItem(
                    text = { Text("Choose from gallery") },
                    leadingIcon = { Icon(Icons.Rounded.Photo, contentDescription = null) },
                    onClick = {
                        showSources = false
                        pickAvatar.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Take a photo") },
                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                    onClick = {
                        showSources = false
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            launchAvatarCapture()
                        } else {
                            cameraPermission.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                )
            }
        }
    }

    if (prominent) {
        Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KitAvatar(
                name = name,
                modifier = Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape,
                ),
                size = 104.dp,
                avatarUrl = avatarUrl,
            )
            Spacer(Modifier.height(6.dp))
            ChangeButton()
            Text(
                if (avatarUrl != null) {
                    "This is the photo on your account. Keep it, or pick a new one."
                } else {
                    "Add a photo so people know it's really you."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            KitAvatar(name, size = 64.dp, avatarUrl = avatarUrl)
            Spacer(Modifier.width(14.dp))
            ChangeButton()
        }
    }
}
