package com.kit.wallet.feature.chat

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Whether a chat video should keep playing in the system's floating window when the user's
 * attention moves on.
 *
 * Kept as a decision of its own so the rule can be read and tested without an Activity. The rule is
 * the iOS one: a video the user started is theirs until it ends, so leaving Kit Pay hands playback
 * to Picture in Picture rather than cutting it off — unless a call is up, because a call owns the
 * audio route and a chat video must never take it.
 */
internal fun shouldEnterChatVideoPictureInPicture(
    isPlaying: Boolean,
    callInProgress: Boolean,
    supported: Boolean = true,
): Boolean = isPlaying && !callInProgress && supported

/**
 * Hands a playing chat video to Picture in Picture when Kit Pay goes away, and takes the floating
 * window down the moment the video ends.
 *
 * Android has no per-layer Picture in Picture — the window is the activity — so this is only armed
 * while a full-screen video is actually playing, and disarmed the instant it is not. That also
 * keeps it clear of the call screen, which arms the same activity for its own window.
 *
 * The end of the video is the end of the window: nothing is left to watch, so it closes itself
 * rather than sitting on the user's screen showing a frozen last frame.
 */
@Composable
internal fun ChatVideoPictureInPictureEffect(isPlaying: Boolean) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val eligible = shouldEnterChatVideoPictureInPicture(
        isPlaying = isPlaying,
        callInProgress = context.isCallInProgress(),
        supported = activity?.packageManager
            ?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true,
    )

    DisposableEffect(activity, eligible) {
        if (activity == null) return@DisposableEffect onDispose { }
        if (!eligible) {
            // Playback stopped — either the video ended or the user closed it. A window that was
            // holding it up has nothing left to show, so it goes with it.
            if (activity.isInPictureInPictureMode) activity.moveTaskToBack(true)
            activity.disarmPictureInPicture()
            return@DisposableEffect onDispose { }
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(true)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
        activity.setPictureInPictureParams(params)
        // Below 12 there is no automatic hand-off, so the last moment before the app goes away is
        // the only moment the window can be asked for.
        val leaveHint = Runnable {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                !activity.isInPictureInPictureMode
            ) {
                runCatching { activity.enterPictureInPictureMode(params) }
            }
        }
        activity.addOnUserLeaveHintListener(leaveHint)
        onDispose {
            activity.removeOnUserLeaveHintListener(leaveHint)
            activity.disarmPictureInPicture()
        }
    }
}

private fun ComponentActivity.disarmPictureInPicture() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        setPictureInPictureParams(
            PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
        )
    }
}

/**
 * A call owns the audio route. Android has no single active-call object to ask, so the route itself
 * is asked — the same substitute the voice-note player makes.
 */
internal fun Context.isCallInProgress(): Boolean {
    val mode = (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode ?: return false
    return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
