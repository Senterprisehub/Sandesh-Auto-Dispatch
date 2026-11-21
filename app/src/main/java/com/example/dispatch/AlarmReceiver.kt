package com.example.dispatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val scheduleId = intent?.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L) ?: -1L
        if (scheduleId <= 0L) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val cpuWakeLock = runCatching {
            powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Sandesh:AlarmBridgeLock"
            )?.apply { acquire(15_000L) }
        }.getOrNull()

        val serviceIntent = Intent(context, DispatchForegroundService::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } finally {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
        }
    }
}
