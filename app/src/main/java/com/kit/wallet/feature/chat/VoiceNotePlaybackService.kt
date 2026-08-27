package com.kit.wallet.feature.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kit.wallet.MainActivity
import com.kit.wallet.R

/**
 * Keeps a playing voice note alive while Kit Pay is in the background, the way a call is kept alive.
 *
 * The notification is the only control the user has once the app is off screen, so it carries the
 * same transport the floating bar does — play/pause and a skip either way — and names the speaker
 * and the chat, never the note's contents. It goes away the moment playback does.
 */
class VoiceNotePlaybackService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> VoiceNotePlayer.toggleCurrent()
            ACTION_SKIP_FORWARD -> VoiceNotePlayer.seekBy(SKIP_MILLIS)
            ACTION_SKIP_BACKWARD -> VoiceNotePlayer.seekBy(-SKIP_MILLIS)
            ACTION_STOP -> VoiceNotePlayer.stop()
        }
        val state = VoiceNotePlayer.state.value
        val playing = state.playing
        if (playing == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(playing.context, state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** A note cannot survive its own process being torn down; leave nothing playing behind it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        VoiceNotePlayer.stop()
        super.onTaskRemoved(rootIntent)
    }

    private fun notification(
        context: VoiceNotePlaybackContext,
        state: VoiceNotePlaybackState,
    ): android.app.Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Voice note playback",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kit_mark)
            .setContentTitle(context.title)
            .setContentText(context.subtitle)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(!state.isPaused)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setDeleteIntent(command(ACTION_STOP))
            .addAction(
                R.drawable.ic_kit_mark,
                "Back 15s",
                command(ACTION_SKIP_BACKWARD),
            )
            .addAction(
                R.drawable.ic_kit_mark,
                if (state.isPaused) "Play" else "Pause",
                command(ACTION_TOGGLE),
            )
            .addAction(
                R.drawable.ic_kit_mark,
                "Forward 15s",
                command(ACTION_SKIP_FORWARD),
            )
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, VoiceNotePlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "kit_voice_note_playback"
        private const val NOTIFICATION_ID = 4103
        private const val ACTION_TOGGLE = "com.kit.wallet.voicenote.TOGGLE"
        private const val ACTION_SKIP_FORWARD = "com.kit.wallet.voicenote.SKIP_FORWARD"
        private const val ACTION_SKIP_BACKWARD = "com.kit.wallet.voicenote.SKIP_BACKWARD"
        private const val ACTION_STOP = "com.kit.wallet.voicenote.STOP"
        private const val SKIP_MILLIS = 15_000L

        /**
         * Brings the service into line with what the player is doing: started and re-notified while
         * a note is in hand, gone as soon as one is not.
         *
         * Called from the player itself, so it must never throw — a background start that the system
         * refuses is a lost notification, not a lost note.
         */
        internal fun refresh(context: Context?, state: VoiceNotePlaybackState) {
            val target = context ?: return
            val intent = Intent(target, VoiceNotePlaybackService::class.java)
            runCatching {
                if (state.playing == null) {
                    target.stopService(intent)
                } else {
                    ContextCompat.startForegroundService(target, intent)
                }
            }
        }
    }
}
