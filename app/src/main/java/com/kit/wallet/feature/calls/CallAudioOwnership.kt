package com.kit.wallet.feature.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Owns the focus lease independently of RTC generations. Disconnecting a held Room must not
 * abandon the delayed focus callback that tells us a cellular/other-app call has finished.
 * AudioSwitch still owns Bluetooth/routing but has manageAudioFocus=false.
 */
internal class CallAudioOwnership(
    context: Context,
    private val interruptImmediately: () -> Unit,
    private val onAvailable: () -> Unit,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var wanted = false
    private var closed = false
    private var telecomPaused = false
    private var externalCallObserved = false
    private var rtcPaused = true
    @Volatile var available = false
        private set
    private var hasRequest = false
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!closed && wanted) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    available = !cellularCallActive() && (!telecomPaused || externalCallObserved)
                    if (available) {
                        telecomPaused = false
                        main.post { if (wanted && available && !closed) onAvailable() }
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> loseAudio(external = true)
            }
        }
    }
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(listener, main)
        .build()
    // API 26–30 do not expose mode callbacks. Observe only during an interruption, never ask
    // for READ_PHONE_STATE, and never contend with another MODE_IN_COMMUNICATION owner.
    private val recovery = object : Runnable {
        override fun run() {
            if (closed || !wanted || available) return
            if (cellularCallActive() || (rtcPaused && manager.mode == AudioManager.MODE_IN_COMMUNICATION)) {
                externalCallObserved = true
            }
            if (manager.mode == AudioManager.MODE_NORMAL && (!telecomPaused || externalCallObserved)) {
                telecomPaused = false
                if (acquire()) onAvailable()
            }
            if (!available) main.postDelayed(this, 1_000)
        }
    }

    private val modeListener: AudioManager.OnModeChangedListener? = if (Build.VERSION.SDK_INT >= 31) {
        AudioManager.OnModeChangedListener { mode ->
            if (wanted && !closed && (mode == AudioManager.MODE_IN_CALL ||
                    (rtcPaused && mode == AudioManager.MODE_IN_COMMUNICATION))) loseAudio(external = true)
        }.also { manager.addOnModeChangedListener(context.mainExecutor, it) }
    } else null

    fun mediaActive(active: Boolean) { rtcPaused = !active }

    fun acquire(): Boolean {
        if (closed) return false
        wanted = true
        if (cellularCallActive() || telecomPaused ||
            (!available && rtcPaused && manager.mode == AudioManager.MODE_IN_COMMUNICATION)) {
            loseAudio()
            return false
        }
        if (available) return true
        hasRequest = true
        available = runCatching { manager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!available) {
            main.removeCallbacks(recovery)
            main.postDelayed(recovery, 1_000)
        }
        return available
    }

    fun systemHeld() {
        telecomPaused = true
        externalCallObserved = false
        loseAudio()
    }

    fun systemResumed() {
        telecomPaused = false
        if (wanted && acquire()) onAvailable()
    }

    private fun cellularCallActive(): Boolean = manager.mode == AudioManager.MODE_IN_CALL ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && manager.mode == AudioManager.MODE_CALL_SCREENING)

    private fun loseAudio(external: Boolean = false) {
        externalCallObserved = externalCallObserved || external
        available = false
        interruptImmediately()
        main.removeCallbacks(recovery)
        if (wanted) main.postDelayed(recovery, 1_000)
    }

    fun abandon() {
        wanted = false
        available = false
        telecomPaused = false
        externalCallObserved = false
        rtcPaused = true
        main.removeCallbacks(recovery)
        if (hasRequest) runCatching { manager.abandonAudioFocusRequest(request) }
        hasRequest = false
    }

    fun close() {
        abandon()
        closed = true
        if (Build.VERSION.SDK_INT >= 31) modeListener?.let(manager::removeOnModeChangedListener)
    }
}
