package com.kit.wallet.feature.calls

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Plays the standard telephony progress tones for outgoing Kit Pay calls: a repeating ringback
 * while the callee's device is ringing and a short busy-style disconnect burst when a call ends.
 *
 * Tones are generated locally on the voice-call stream and are never mixed into the encrypted
 * media session, so they cannot affect call signalling or the secure call contract. Creation and
 * playback are best-effort: a device without tone support simply stays silent.
 */
internal class CallTonePlayer {
    private var ringback: ToneGenerator? = null

    /** Starts the repeating ringback tone; safe to call repeatedly while already ringing. */
    fun startRingback() {
        if (ringback != null) return
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME) }
            .getOrNull()
            ?.also { runCatching { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) } }
    }

    fun stopRingback() {
        ringback?.let { generator ->
            runCatching { generator.stopTone() }
            runCatching { generator.release() }
        }
        ringback = null
    }

    /**
     * Plays the standard telephony call-waiting tone once. Callers repeat it on a cadence while a
     * second call is waiting, matching the familiar in-call "someone else is calling" beep.
     */
    fun playCallWaiting() {
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, CALL_WAITING_VOLUME)
        }.getOrNull() ?: return
        runCatching { generator.startTone(ToneGenerator.TONE_SUP_CALL_WAITING, CALL_WAITING_TONE_MILLIS) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { runCatching { generator.release() } },
            CALL_WAITING_TONE_MILLIS + RELEASE_GRACE_MILLIS,
        )
    }

    /**
     * Plays the brief end-of-call tone. The generator releases itself after the burst window,
     * so a screen teardown immediately after hangup cannot leak the audio resource.
     */
    fun playDisconnect() {
        stopRingback()
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, DISCONNECT_VOLUME)
        }.getOrNull() ?: return
        runCatching {
            generator.startTone(ToneGenerator.TONE_CDMA_CALLDROP_LITE, DISCONNECT_TONE_MILLIS)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { runCatching { generator.release() } },
            DISCONNECT_RELEASE_MILLIS,
        )
    }

    fun release() {
        stopRingback()
    }

    private companion object {
        const val RINGBACK_VOLUME = 70
        const val DISCONNECT_VOLUME = 80
        const val DISCONNECT_TONE_MILLIS = 900
        const val DISCONNECT_RELEASE_MILLIS = 1_100L
        const val CALL_WAITING_VOLUME = 85
        const val CALL_WAITING_TONE_MILLIS = 800
        const val RELEASE_GRACE_MILLIS = 300L
    }
}
