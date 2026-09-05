package com.kit.wallet.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

@Composable
fun LiveKitVideoRenderer(
    room: Room,
    track: VideoTrack?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fit: Boolean = false,
) {
    if (LocalView.current.isInEditMode) {
        Box(modifier.background(Color.Black))
        return
    }

    var renderer by remember(room) { mutableStateOf<TextureViewRenderer?>(null) }
    var boundTrack by remember(room) { mutableStateOf<VideoTrack?>(null) }
    val scalingType = if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT
        else RendererCommon.ScalingType.SCALE_ASPECT_FILL

    fun bind(nextTrack: VideoTrack?, view: TextureViewRenderer) {
        if (boundTrack == nextTrack) return
        boundTrack?.removeRenderer(view)
        boundTrack = nextTrack
        nextTrack?.addRenderer(view)
    }

    DisposableEffect(track, renderer) {
        renderer?.let { bind(track, it) }
        onDispose { }
    }
    DisposableEffect(room) {
        onDispose {
            renderer?.let { view -> boundTrack?.removeRenderer(view) }
            boundTrack = null
            renderer?.release()
            renderer = null
        }
    }

    // FIT is a native measurement policy. Passing exact width AND height from fill/weight
    // defeats it, so let a fitted TextureView measure inside the centered available stage.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                TextureViewRenderer(context).apply {
                    room.initVideoRenderer(this)
                    setScalingType(scalingType)
                    setMirror(mirror)
                    renderer = this
                    bind(track, this)
                }
            },
            update = {
                bind(track, it)
                it.setMirror(mirror)
                it.setScalingType(scalingType)
            },
            modifier = if (fit) Modifier else Modifier.matchParentSize(),
        )
    }
}
