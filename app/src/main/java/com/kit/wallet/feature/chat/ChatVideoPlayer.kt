package com.kit.wallet.feature.chat

import android.content.Context
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/** One lifecycle/error policy for the connected gallery and album video viewer. */
@Composable
internal fun ChatVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    onCompleted: () -> Unit,
    onError: () -> Unit,
) {
    val complete = rememberUpdatedState(onCompleted)
    val fail = rememberUpdatedState(onError)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // PiP remains STARTED while visible. ON_STOP also covers closing the floating window,
            // denied PiP and normal backgrounding on a device without PiP support.
            if (event == Lifecycle.Event.ON_STOP) complete.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { context ->
            ChatVideoView(context).apply {
                val view = this
                // Register before opening: a malformed file can fail while VideoView opens it.
                setOnErrorListener { _, _, _ ->
                    if (!released) fail.value()
                    true // The Compose retry UI owns the error; never open a platform dialog.
                }
                setOnCompletionListener { if (!released) complete.value() }
                setOnPreparedListener {
                    if (!released) {
                        if (context.isCallInProgress() ||
                            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        ) {
                            fail.value()
                        } else {
                            VoiceNotePlayer.stop()
                            runCatching { view.start() }.onFailure { fail.value() }
                        }
                    }
                }
                setMediaController(MediaController(context).also { it.setAnchorView(view) })
                runCatching { setVideoPath(file.absolutePath) }.onFailure { fail.value() }
            }
        },
        modifier = modifier,
        onRelease = ChatVideoView::release,
    )
}

private class ChatVideoView(context: Context) : VideoView(context) {
    var released = false
        private set

    fun release() {
        released = true
        setOnPreparedListener(null)
        setOnCompletionListener(null)
        setOnErrorListener { _, _, _ -> true }
        setMediaController(null)
        runCatching { stopPlayback() }
    }
}
