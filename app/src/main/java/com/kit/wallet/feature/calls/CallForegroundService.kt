package com.kit.wallet.feature.calls

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

class CallForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ongoing Kit Pay calls", NotificationManager.IMPORTANCE_LOW),
        )
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Kit Pay contact" }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val openCall = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kit_mark)
            .setContentTitle(if (video) "Kit Pay video call" else "Kit Pay voice call")
            .setContentText("Call with $name")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openCall)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && video) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "kit_ongoing_calls"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, name: String, video: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallForegroundService::class.java)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_VIDEO, video),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
