package com.brick.app.feature.stats

import com.brick.app.core.database.BrickSessionEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * One local Sunday-to-Saturday week of completed focus time.
 *
 * [days] always has seven entries in Sunday..Saturday order. Days that have not happened yet are
 * present with zero duration. [averageMs] is the mean over fully elapsed days only (today is still
 * in progress), and is null when there is nothing truthful to average yet.
 */
data class WeekActivity(
    val weekStartMs: Long,
    val days: List<FocusDay>,
    val offsetWeeks: Int,
    val elapsedDays: Int,
    val completedDays: Int,
    val totalMs: Long,
    val averageMs: Long?
) {
    val isCurrentWeek: Boolean get() = offsetWeeks == 0
    val maxDayMs: Long get() = days.maxOf { it.durationMs }
}

object WeeklyActivity {
    /**
     * @param offsetWeeks 0 for the week containing [nowMs], -1 for the previous week, and so on.
     *  Positive offsets are clamped to the current week (the future has no data).
     */
    fun week(
        sessions: List<BrickSessionEntity>,
        offsetWeeks: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): WeekActivity {
        val offset = offsetWeeks.coerceAtMost(0)
        val weekStart = Calendar.getInstance(zone).apply {
            firstDayOfWeek = Calendar.SUNDAY
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
            add(Calendar.DAY_OF_YEAR, 7 * offset)
        }
        val starts = ArrayList<Long>(8)
        val cursor = weekStart.clone() as Calendar
        repeat(8) { starts.add(cursor.timeInMillis); cursor.add(Calendar.DAY_OF_YEAR, 1) }
        val weekEnd = starts[7]
        // Days that have begun by "now" (all seven for any past week).
        val elapsed = (0 until 7).count { starts[it] <= nowMs }.coerceAtLeast(1)
        // For a finished week anchor on the next Sunday midnight so Saturday keeps its final
        // millisecond; recentDays' newest entry is then the (empty) following day and is dropped.
        val finished = nowMs >= weekEnd
        val recent = if (finished) {
            FocusTimeline.recentDays(sessions, count = 8, nowMs = weekEnd, zone = zone).drop(1)
        } else {
            FocusTimeline.recentDays(sessions, count = elapsed, nowMs = nowMs, zone = zone)
        }
        val real = recent.reversed()
        val days = (0 until 7).map { real.getOrNull(it) ?: FocusDay(starts[it], 0L, 0) }
        val completed = if (offset == 0) elapsed - 1 else 7
        val total = days.sumOf { it.durationMs }
        val completedTotal = days.take(completed).sumOf { it.durationMs }
        val average = if (completed > 0 && total > 0) completedTotal / completed else null
        return WeekActivity(starts[0], days, offset, elapsed, completed, total, average)
    }

    /** Chart ceiling: at least one hour, rounded up to a whole hour so gridlines stay readable. */
    fun axisMaxMs(maxDayMs: Long): Long {
        val hour = 3_600_000L
        return maxOf(hour, ((maxDayMs + hour - 1) / hour) * hour)
    }

    /** Compact label for axis ticks and averages: 30m, 1h, 1h 20m. */
    fun compactDuration(durationMs: Long): String {
        val minutes = durationMs.coerceAtLeast(0) / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
