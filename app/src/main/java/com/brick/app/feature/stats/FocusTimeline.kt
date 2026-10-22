package com.brick.app.feature.stats

import com.brick.app.core.database.BrickSessionEntity
import java.util.Calendar
import java.util.TimeZone

/** Completed focus time apportioned to each LOCAL calendar day, including overnight sessions. */
data class FocusDay(val startMs: Long, val durationMs: Long, val sessionCount: Int)

object FocusTimeline {
    /** Wall-clock span for a completed session, capped at now; same source as daily tiles. */
    fun sessionDurationMs(session: BrickSessionEntity, nowMs: Long): Long =
        session.endTimeMs?.let { (it.coerceAtMost(nowMs) - session.startTimeMs).coerceAtLeast(0L) } ?: 0L

    fun recentDays(
        sessions: List<BrickSessionEntity>,
        count: Int = 15,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): List<FocusDay> {
        require(count > 0)
        val day = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        val boundaries = ArrayList<Long>(count + 1)
        repeat(count + 1) {
            boundaries.add(day.timeInMillis)
            day.add(Calendar.DAY_OF_YEAR, -1)
        }
        boundaries.reverse()
        return (0 until count).map { index ->
            val start = boundaries[index]
            val end = boundaries[index + 1]
            var duration = 0L
            var sessionCount = 0
            for (session in sessions) {
                // Never count active sessions or time that has not yet occurred.
                val finish = session.endTimeMs?.coerceAtMost(nowMs) ?: continue
                val overlap = (minOf(finish, end) - maxOf(session.startTimeMs, start)).coerceAtLeast(0)
                if (overlap > 0) {
                    duration += overlap
                    sessionCount++
                }
            }
            FocusDay(start, duration, sessionCount)
        }.reversed()
    }

    fun averageLastSevenDays(days: List<FocusDay>): Long =
        days.take(7).sumOf { it.durationMs } / 7L
}
