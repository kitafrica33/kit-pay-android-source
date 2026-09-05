package com.kit.wallet.feature.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kit.wallet.MainActivity
import com.kit.wallet.R
import com.kit.wallet.data.notifications.ActiveCallReturnLink
import com.kit.wallet.data.notifications.CallActionReceiver

class CallForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ongoing Kit Pay calls", NotificationManager.IMPORTANCE_LOW),
        )
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Kit Pay contact" }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val camera = intent?.getBooleanExtra(EXTRA_CAMERA, video) == true
        // The tap must land back on this exact call, so the content intent names it by id.
        // The link is only ever matched against the call the app knows it is in — a stale
        // notification does nothing. Without a valid id the tap just brings the app forward.
        val returnLink = ActiveCallReturnLink.forCallId(intent?.getStringExtra(EXTRA_CALL_ID))
        val openCall = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    if (returnLink != null) {
                        action = Intent.ACTION_VIEW
                        data = Uri.parse(returnLink.deepLinkUri())
                    }
                },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kit_mark)
            .setContentTitle(if (video) "Kit Pay video call" else "Kit Pay voice call")
            .setContentText("Call with $name")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openCall)
        returnLink?.let { link ->
            val hangUp = PendingIntent.getBroadcast(
                this,
                0,
                CallActionReceiver.endIntent(this, link.callId, "completed")
                    .setData(Uri.parse("kitpay-internal://call/${Uri.encode(link.callId)}/end")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    Person.Builder().setName(name).setImportant(true).build(),
                    hangUp,
                ).setIsVideo(video),
            )
        }
        val notification = builder.build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && camera) {
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
        private const val EXTRA_CAMERA = "camera"
        private const val EXTRA_CALL_ID = "call_id"

        fun start(context: Context, name: String, video: Boolean, callId: String? = null, camera: Boolean = video) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallForegroundService::class.java)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_VIDEO, video)
                    .putExtra(EXTRA_CAMERA, camera)
                    .putExtra(EXTRA_CALL_ID, callId),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
