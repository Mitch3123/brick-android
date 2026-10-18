package com.brick.app.feature.stats

import com.brick.app.core.database.BrickSessionEntity
import java.util.Calendar

data class StreakStats(
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val totalBrickedTimeMs: Long,
    val sessionCount: Int
)

/**
 * Calculates current focus streak, longest focus streak, and aggregates historical session metrics.
 */
object StreakCalculator {

    fun calculateStats(sessions: List<BrickSessionEntity>): StreakStats {
        if (sessions.isEmpty()) {
            return StreakStats(
                currentStreakDays = 0,
                longestStreakDays = 0,
                totalBrickedTimeMs = 0L,
                sessionCount = 0
            )
        }

        var totalMs = 0L
        val uniqueDays = mutableSetOf<String>()

        for (s in sessions) {
            val duration = s.durationMs ?: (if (s.endTimeMs != null) (s.endTimeMs - s.startTimeMs).coerceAtLeast(0L) else 0L)
            totalMs += duration

            val cal = Calendar.getInstance().apply { timeInMillis = s.startTimeMs }
            val dayKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
            uniqueDays.add(dayKey)
        }

        val sortedDays = uniqueDays.sorted()
        var currentStreak = 0
        var longestStreak = 0
        var tempStreak = 0

        // Calculate longest streak from unique consecutive day keys
        var prevYear = -1
        var prevDay = -1

        for (key in sortedDays) {
            val parts = key.split("-")
            val year = parts[0].toInt()
            val day = parts[1].toInt()

            if (prevYear == -1) {
                tempStreak = 1
            } else if (year == prevYear && day == prevDay + 1) {
                tempStreak++
            } else {
                tempStreak = 1
            }

            if (tempStreak > longestStreak) {
                longestStreak = tempStreak
            }

            prevYear = year
            prevDay = day
        }

        // Current streak check based on today / yesterday
        val todayCal = Calendar.getInstance()
        val todayKey = "${todayCal.get(Calendar.YEAR)}-${todayCal.get(Calendar.DAY_OF_YEAR)}"
        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayKey = "${yesterdayCal.get(Calendar.YEAR)}-${yesterdayCal.get(Calendar.DAY_OF_YEAR)}"

        currentStreak = if (uniqueDays.contains(todayKey) || uniqueDays.contains(yesterdayKey)) {
            tempStreak
        } else {
            0
        }

        return StreakStats(
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            totalBrickedTimeMs = totalMs,
            sessionCount = sessions.size
        )
    }
}
