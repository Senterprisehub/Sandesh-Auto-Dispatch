package com.example.dispatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.SandeshApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val supported = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
        if (action !in supported) return

        val pendingResult = goAsync()
        val app = context.applicationContext as? SandeshApplication
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app != null) {
                    AlarmScheduler.rescheduleAllPending(context, app.container.repository)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
