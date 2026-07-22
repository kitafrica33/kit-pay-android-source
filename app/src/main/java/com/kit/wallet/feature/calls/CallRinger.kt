package com.kit.wallet.feature.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays the device ringtone and a repeating vibration while an incoming Kit Pay call is ringing,
 * and stops both as soon as the call is answered, declined or otherwise leaves the ringing phase.
 *
 * The ringer is deliberately confined to the UI layer: it never touches call signalling or media,
 * so it cannot affect the secure call session or the encrypted call-control contract. It also
 * honours the device ringer mode, so silent/vibrate profiles are respected.
 */
internal class CallRinger(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var ringtone: Ringtone? = null
    private var vibrating = false

    fun start() {
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) startRingtone()
        if (ringerMode != AudioManager.RINGER_MODE_SILENT) startVibration()
    }

    private fun startRingtone() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        ringtone = runCatching {
            RingtoneManager.getRingtone(appContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.getOrNull()
    }

    private fun startVibration() {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(VIBRATION_TIMINGS, VIBRATION_AMPLITUDES, /* repeat = */ 0),
                    attributes,
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATION_TIMINGS, /* repeat = */ 0)
            }
            vibrating = true
        }
    }

    fun stop() {
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        if (vibrating) {
            vibrator?.let { runCatching { it.cancel() } }
            vibrating = false
        }
    }

    private companion object {
        // One long ring pulse followed by a gap, repeated for the whole ringing window.
        val VIBRATION_TIMINGS = longArrayOf(0L, 700L, 900L)
        val VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0)
    }
}
