package com.example.dispatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {

    const val CHANNEL_DISPATCH = "sandesh_dispatch_channel"
    const val CHANNEL_RESULTS = "sandesh_results_channel"
    const val NOTIF_ID_FOREGROUND = 4101

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val dispatchChannel = NotificationChannel(
            CHANNEL_DISPATCH,
            context.getString(R.string.notif_channel_dispatch_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_dispatch_desc)
            setShowBadge(false)
        }

        val resultsChannel = NotificationChannel(
            CHANNEL_RESULTS,
            context.getString(R.string.notif_channel_results_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_results_desc)
        }

        nm.createNotificationChannel(dispatchChannel)
        nm.createNotificationChannel(resultsChannel)
    }

    fun buildDispatchForegroundNotification(
        context: Context,
        recipientLabel: String,
        wakeIntent: Intent
    ): Notification {
        ensureChannels(context)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            9001,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_DISPATCH)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Dispatching WhatsApp message")
            .setContentText("Sending scheduled message to $recipientLabel")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    fun showResultNotification(
        context: Context,
        scheduleId: Long,
        recipientLabel: String,
        success: Boolean,
        detail: String
    ) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            scheduleId.toInt(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (success) {
            "Sent to $recipientLabel"
        } else {
            "Dispatch issue for $recipientLabel"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            nm.notify((5000 + (scheduleId % 1000)).toInt(), notification)
        }
    }
}
