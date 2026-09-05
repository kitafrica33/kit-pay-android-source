package com.kit.wallet.feature.calls

import com.twilio.audioswitch.AudioDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallMediaPresentationTest {
    @Test
    fun `a screen share takes the stage even when the camera was published first`() {
        val camera = CallVideoPublication("camera", CallVideoSource.CAMERA, muted = false)
        val screen = CallVideoPublication("document", CallVideoSource.SCREEN_SHARE, muted = false)
        assertEquals(screen, selectRemoteCallVideo(listOf(camera, screen)))
        assertEquals(screen, selectRemoteCallVideo(listOf(screen, camera)))
    }

    @Test
    fun `a stopped or unsubscribed share restores the live camera`() {
        val camera = CallVideoPublication("camera", CallVideoSource.CAMERA, muted = false)
        val stopped = CallVideoPublication("document", CallVideoSource.SCREEN_SHARE, muted = true)
        val pending = CallVideoPublication<String>(null, CallVideoSource.SCREEN_SHARE, muted = false)
        assertEquals(camera, selectRemoteCallVideo(listOf(stopped, camera)))
        assertEquals(camera, selectRemoteCallVideo(listOf(pending, camera)))
    }

    @Test
    fun `muted cameras never leave a frozen frame in place of the avatar`() {
        assertNull(selectRemoteCallVideo(listOf(
            CallVideoPublication("camera", CallVideoSource.CAMERA, muted = true),
            CallVideoPublication<String>(null, CallVideoSource.SCREEN_SHARE, muted = false),
        )))
    }

    @Test
    fun `voice and video prefer connected headsets and only change the built in fallback`() {
        val headsetPriority = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
        )
        assertEquals(headsetPriority, callAudioDevicePreference(false).take(2))
        assertEquals(headsetPriority, callAudioDevicePreference(true).take(2))
        assertEquals(AudioDevice.Earpiece::class.java, callAudioDevicePreference(false)[2])
        assertEquals(AudioDevice.Speakerphone::class.java, callAudioDevicePreference(true)[2])
        assertEquals(4, callAudioDevicePreference(false).distinct().size)
        assertEquals(4, callAudioDevicePreference(true).distinct().size)
    }
}
