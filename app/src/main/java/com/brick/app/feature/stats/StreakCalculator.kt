package com.brick.app.feature.stats

import com.brick.app.core.database.BrickSessionEntity
import java.util.TimeZone

data class StreakStats(
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val totalBrickedTimeMs: Long,
    val sessionCount: Int
)

/**
 * Calculates current focus streak, longest focus streak, and aggregates historical session metrics.
 *
 * Days are keyed by their local epoch day index, so ordering is numeric and consecutive days stay
 * consecutive across month and year boundaries.
 */
object StreakCalculator {

    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    fun calculateStats(
        sessions: List<BrickSessionEntity>,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): StreakStats {
        if (sessions.isEmpty()) {
            return StreakStats(
                currentStreakDays = 0,
                longestStreakDays = 0,
                totalBrickedTimeMs = 0L,
                sessionCount = 0
            )
        }

        var totalMs = 0L
        val activeDays = sortedSetOf<Long>()

        for (s in sessions) {
            totalMs += durationOf(s)
            activeDays.add(localDayIndex(s.startTimeMs, timeZone))
        }

        return StreakStats(
            currentStreakDays = currentStreak(activeDays, localDayIndex(nowMs, timeZone)),
            longestStreakDays = longestStreak(activeDays),
            totalBrickedTimeMs = totalMs,
            sessionCount = sessions.size
        )
    }

    private fun durationOf(session: BrickSessionEntity): Long =
        session.durationMs
            ?: session.endTimeMs?.let { (it - session.startTimeMs).coerceAtLeast(0L) }
            ?: 0L

    /**
     * Local epoch day of [ms]: the number of whole days since 1970-01-01 as seen in [timeZone].
     * Uses the UTC offset in effect at that instant so DST shifts do not move a day boundary.
     */
    private fun localDayIndex(ms: Long, timeZone: TimeZone): Long =
        Math.floorDiv(ms + timeZone.getOffset(ms), MS_PER_DAY)

    private fun longestStreak(activeDays: Set<Long>): Int {
        var longest = 0
        var run = 0
        var previous: Long? = null

        for (day in activeDays) {
            run = if (previous != null && day == previous + 1) run + 1 else 1
            if (run > longest) longest = run
            previous = day
        }
        return longest
    }

    /**
     * The run of consecutive active days ending today, or ending yesterday when today has no
     * session yet. Days in the future (clock changes) never anchor a current streak.
     */
    private fun currentStreak(activeDays: Set<Long>, today: Long): Int {
        val anchor = when {
            activeDays.contains(today) -> today
            activeDays.contains(today - 1) -> today - 1
            else -> return 0
        }

        var streak = 0
        var day = anchor
        while (activeDays.contains(day)) {
            streak++
            day--
        }
        return streak
    }
}
