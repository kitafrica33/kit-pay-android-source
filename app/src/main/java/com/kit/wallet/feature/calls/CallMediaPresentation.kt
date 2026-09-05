package com.kit.wallet.feature.calls

import com.twilio.audioswitch.AudioDevice

internal enum class CallVideoSource { CAMERA, SCREEN_SHARE, OTHER }

internal data class CallVideoPublication<T>(
    val track: T?,
    val source: CallVideoSource,
    val muted: Boolean,
)

/** A shared screen owns the stage until it stops; a muted or unsubscribed track cannot hide it. */
internal fun <T> selectRemoteCallVideo(
    publications: List<CallVideoPublication<T>>,
): CallVideoPublication<T>? = publications
    .filter { it.track != null && !it.muted }
    .minByOrNull {
        when (it.source) {
            CallVideoSource.SCREEN_SHARE -> 0
            CallVideoSource.CAMERA -> 1
            CallVideoSource.OTHER -> 2
        }
    }

/** Change the built-in fallback for video without taking a call away from connected headphones. */
internal fun callAudioDevicePreference(video: Boolean): List<Class<out AudioDevice>> = buildList {
    add(AudioDevice.BluetoothHeadset::class.java)
    add(AudioDevice.WiredHeadset::class.java)
    if (video) {
        add(AudioDevice.Speakerphone::class.java)
        add(AudioDevice.Earpiece::class.java)
    } else {
        add(AudioDevice.Earpiece::class.java)
        add(AudioDevice.Speakerphone::class.java)
    }
}

internal fun callAudioDeviceLabel(device: AudioDevice?): String = when (device) {
    is AudioDevice.BluetoothHeadset -> "Bluetooth"
    is AudioDevice.WiredHeadset -> "Headphones"
    is AudioDevice.Speakerphone -> "Speaker"
    is AudioDevice.Earpiece -> "Phone"
    else -> "Audio output"
}
