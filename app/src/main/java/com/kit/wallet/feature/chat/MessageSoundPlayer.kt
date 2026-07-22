package com.kit.wallet.feature.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.kit.wallet.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short in-app message sounds, played while a conversation is open: a "water drop" when an
 * outgoing message reaches its first delivery tick, the "knock" tone whenever a message arrives,
 * and a "coin" tone when a payment is received — the same feedback pattern common messengers use.
 */
interface MessageSoundPlayer {
    fun playSent()

    fun playReceived()

    fun playPaymentReceived()
}

/**
 * Plays the bundled message/payment sounds with a low-latency [SoundPool]. Playback is
 * best-effort and never throws into the calling flow.
 */
@Singleton
class AndroidMessageSoundPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MessageSoundPlayer {
    private val appContext = context.applicationContext

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    // Sample ids resolve asynchronously; a sound that has not finished loading simply no-ops once.
    private val sentSoundId = runCatching {
        soundPool.load(appContext, R.raw.msg_sent, 1)
    }.getOrDefault(0)
    private val receivedSoundId = runCatching {
        soundPool.load(appContext, R.raw.msg_received, 1)
    }.getOrDefault(0)
    private val paymentSoundId = runCatching {
        soundPool.load(appContext, R.raw.payment_received, 1)
    }.getOrDefault(0)

    override fun playSent() = play(sentSoundId)

    override fun playReceived() = play(receivedSoundId)

    override fun playPaymentReceived() = play(paymentSoundId)

    private fun play(soundId: Int) {
        if (soundId == 0) return
        runCatching { soundPool.play(soundId, 1f, 1f, 1, 0, 1f) }
    }
}
