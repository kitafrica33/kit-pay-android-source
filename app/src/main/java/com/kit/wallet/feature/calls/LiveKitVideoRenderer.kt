package com.kit.wallet.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
) {
    if (LocalView.current.isInEditMode) {
        Box(modifier.background(Color.Black))
        return
    }

    var renderer by remember(room) { mutableStateOf<TextureViewRenderer?>(null) }
    var boundTrack by remember(room) { mutableStateOf<VideoTrack?>(null) }

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
    DisposableEffect(mirror, renderer) {
        renderer?.setMirror(mirror)
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

    AndroidView(
        factory = { context ->
            TextureViewRenderer(context).apply {
                room.initVideoRenderer(this)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                renderer = this
                bind(track, this)
            }
        },
        update = { bind(track, it) },
        modifier = modifier,
    )
}
