package com.kit.wallet.feature.chat

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kit.wallet.feature.settings.transcodeProfileAvatar
import com.kit.wallet.ui.components.KitAvatar
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A group's photo owns a complete navigation surface rather than a menu floating over its profile.
 * Local image preparation and the server mutation are both visible, and the route closes only
 * after the server has accepted the new photo (or its removal).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPhotoScreen(
    onBack: () -> Unit,
    viewModel: GroupProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var preparing by remember { mutableStateOf(false) }
    var captureTarget by remember { mutableStateOf<Uri?>(null) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val canEdit = canEditGroupPhoto(viewer)
    val working = busy || preparing

    DisposableEffect(Unit) {
        onDispose { captureFile?.delete() }
    }

    fun preparePhoto(uri: Uri, temporaryFile: File? = null) {
        if (working || !canEdit) {
            temporaryFile?.delete()
            return
        }
        preparing = true
        viewModel.clearError()
        scope.launch {
            val jpeg = try {
                withContext(Dispatchers.Default) {
                    runCatching {
                        transcodeProfileAvatar(context.contentResolver, uri)
                    }.getOrNull()
                }
            } finally {
                temporaryFile?.delete()
                preparing = false
            }
            if (jpeg == null) {
                viewModel.reportError(
                    "That image could not be read on this device. Try another photo or take " +
                        "a new one.",
                )
            } else {
                viewModel.changePhoto(jpeg, onSaved = onBack)
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) preparePhoto(uri) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = captureTarget
        val file = captureFile
        captureTarget = null
        captureFile = null
        if (saved && target != null) {
            preparePhoto(target, file)
        } else {
            file?.delete()
        }
    }

    fun launchCapture() {
        if (working || !canEdit) return
        val directory = File(context.cacheDir, "chat-capture")
        if (!directory.exists() && !directory.mkdirs()) {
            viewModel.reportError("A temporary camera file could not be created on this device.")
            return
        }
        val file = File(directory, "group-photo-${UUID.randomUUID()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.chatmedia", file)
        }.getOrElse {
            file.delete()
            viewModel.reportError("The camera could not be opened on this device.")
            return
        }
        captureFile = file
        captureTarget = uri
        takePhoto.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCapture()
        } else {
            viewModel.reportError("Camera access is needed to take a group photo.")
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { if (!working) confirmRemove = false },
            title = { Text("Remove group photo?") },
            text = { Text("The generated group image will be shown to every participant.") },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        confirmRemove = false
                        viewModel.removePhoto(onRemoved = onBack)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { confirmRemove = false }) {
                    Text("Keep photo")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group photo") },
                navigationIcon = {
                    IconButton(enabled = !working, onClick = onBack) {
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(36.dp))
            KitAvatar(
                name = chat?.name.orEmpty(),
                size = 168.dp,
                avatarUrl = chat?.avatarUrl,
                isGroup = true,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = chat?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (canEdit) {
                    "Choose a clear photo that helps everyone recognize this group."
                } else {
                    "Only a group owner or admin can change this photo."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            error?.let { message ->
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            if (canEdit) {
                Spacer(Modifier.height(28.dp))
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        enabled = !working,
                        onClick = {
                            pickPhoto.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Photo, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Choose from gallery")
                    }
                    OutlinedButton(
                        enabled = !working,
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                launchCapture()
                            } else {
                                cameraPermission.launch(android.Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Take a photo")
                    }
                    if (chat?.avatarUrl != null) {
                        TextButton(
                            enabled = !working,
                            onClick = { confirmRemove = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Remove photo")
                        }
                    }
                }
            }
        }
    }
}
