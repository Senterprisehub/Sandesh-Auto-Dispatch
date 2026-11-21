package com.example.dispatch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.MainActivity
import com.example.data.local.ScheduledMessageEntity
import com.example.data.model.RecurrenceType
import com.example.data.model.ScheduleStatus
import com.example.data.repository.SandeshRepository

object AlarmScheduler {

    const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    const val MAX_ACTIVE_ALARMS_WINDOW = 250

    fun schedule(context: Context, item: ScheduledMessageEntity): Boolean {
        if (item.statusEnum != ScheduleStatus.PENDING) return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false

        val triggerAt = item.effectiveTriggerMillis.coerceAtLeast(System.currentTimeMillis() + 1500L)
        val pendingIntent = buildPendingIntent(context, item.id)

        val showIntent = PendingIntent.getActivity(
            context,
            item.id.toInt() + 100_000,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return runCatching {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                // setAlarmClock bypasses aggressive Indian OEM Doze & sleep guards
                val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
                alarmManager.setAlarmClock(info, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            true
        }.getOrElse {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                true
            }.getOrDefault(false)
        }
    }

    fun cancel(context: Context, scheduleId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, scheduleId)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /**
     * Reconciles interrupted DISPATCHING rows, processes overdue triggers cleanly, and registers
     * only the next MAX_ACTIVE_ALARMS_WINDOW (250) upcoming alarms so Android's 500-alarm UID cap
     * is never exceeded even with 36,500+ future schedules queued.
     */
    suspend fun hydrateAlarmWindow(context: Context, repository: SandeshRepository): Int {
        val now = System.currentTimeMillis()
        repository.recoverStuckDispatching(now)

        val overdue = repository.getOverduePending(now)
        for (item in overdue) {
            if (item.retryAtMillis in 1..now) {
                // Overdue retry fires promptly once device wakes
                val promptRetry = item.copy(retryAtMillis = now + 5000L)
                repository.saveSchedule(promptRetry)
            } else if (item.recurrenceEnum != RecurrenceType.ONCE) {
                val next = RecurrenceCalculator.computeNextOccurrence(
                    scheduledTimeMillis = item.scheduledTimeMillis,
                    recurrence = item.recurrenceEnum,
                    nowMillis = now,
                    anchorDayOfMonth = item.anchorDayOfMonth
                )
                if (next != null) {
                    repository.saveSchedule(
                        item.copy(
                            scheduledTimeMillis = next,
                            retryCount = 0,
                            retryAtMillis = 0L
                        )
                    )
                }
            } else {
                // Missed while phone was powered off beyond trigger time -> fire promptly if within 15 mins, else mark FAILED
                if (now - item.scheduledTimeMillis <= 15 * 60_000L) {
                    repository.saveSchedule(item.copy(scheduledTimeMillis = now + 5000L))
                } else {
                    repository.recordDispatchOutcome(
                        scheduleId = item.id,
                        success = false,
                        durationMillis = 0L,
                        detailMessage = "Device was powered off during scheduled window",
                        allowRetry = false
                    )
                }
            }
        }

        val window = repository.getUpcomingPendingWindow(MAX_ACTIVE_ALARMS_WINDOW)
        var registeredCount = 0
        for (item in window) {
            if (schedule(context, item)) registeredCount++
        }
        return registeredCount
    }

    suspend fun rescheduleAllPending(context: Context, repository: SandeshRepository): Int =
        hydrateAlarmWindow(context, repository)

    private fun buildPendingIntent(context: Context, scheduleId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.sandesh.ACTION_DISPATCH_$scheduleId"
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context,
            scheduleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
