package com.example.dispatch

import com.example.data.model.RecurrenceType
import java.util.Calendar

object RecurrenceCalculator {

    fun extractDayOfMonth(timeMillis: Long): Int {
        return Calendar.getInstance().apply { timeInMillis = timeMillis }.get(Calendar.DAY_OF_MONTH)
    }

    fun computeNextOccurrence(
        scheduledTimeMillis: Long,
        recurrence: RecurrenceType,
        nowMillis: Long = System.currentTimeMillis(),
        anchorDayOfMonth: Int = 0
    ): Long? {
        if (recurrence == RecurrenceType.ONCE) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = scheduledTimeMillis
        }
        val targetDay = if (anchorDayOfMonth in 1..31) {
            anchorDayOfMonth
        } else {
            cal.get(Calendar.DAY_OF_MONTH)
        }

        // Advance until strictly in the future
        var safetyCounter = 0
        while (cal.timeInMillis <= nowMillis && safetyCounter < 5000) {
            when (recurrence) {
                RecurrenceType.DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
                RecurrenceType.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                RecurrenceType.MONTHLY -> {
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.add(Calendar.MONTH, 1)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, targetDay.coerceAtMost(maxDay))
                }
                RecurrenceType.ONCE -> break
            }
            safetyCounter++
        }
        return cal.timeInMillis
    }

    fun formatCountdown(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = targetMillis - nowMillis
        if (diff <= 0L) return "Due now"
        val totalMinutes = diff / 60_000L
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val mins = totalMinutes % 60
        return when {
            days > 0 -> "in ${days}d ${hours}h"
            hours > 0 -> "in ${hours}h ${mins}m"
            mins > 0 -> "in ${mins}m"
            else -> "in <1m"
        }
    }
}
