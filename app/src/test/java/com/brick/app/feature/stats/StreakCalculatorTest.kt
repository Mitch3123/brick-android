package com.brick.app.feature.stats

import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.ModeType
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [StreakCalculator]: numeric day ordering, month/year rollover,
 * and the today/yesterday anchor of the current streak.
 */
class StreakCalculatorTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val sydney: TimeZone = TimeZone.getTimeZone("Australia/Sydney")
    private val oneHourMs = 60L * 60L * 1000L

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        zone: TimeZone = utc
    ): Long {
        val cal = GregorianCalendar(zone)
        cal.clear()
        cal.set(year, month - 1, day, hour, 0, 0)
        return cal.timeInMillis
    }

    private fun session(
        startMs: Long,
        durationMs: Long? = oneHourMs,
        endMs: Long? = startMs + oneHourMs
    ) = BrickSessionEntity(
        startTimeMs = startMs,
        endTimeMs = endMs,
        durationMs = durationMs,
        modeId = 1L,
        modeName = "Deep Work",
        modeType = ModeType.BLOCK_LIST
    )

    @Test
    fun emptySessions_returnsZeroedStats() {
        val stats = StreakCalculator.calculateStats(emptyList(), nowMs = at(2026, 1, 10), timeZone = utc)

        assertEquals(0, stats.currentStreakDays)
        assertEquals(0, stats.longestStreakDays)
        assertEquals(0L, stats.totalBrickedTimeMs)
        assertEquals(0, stats.sessionCount)
    }

    @Test
    fun consecutiveSingleDigitToDoubleDigitDays_countAsOneRun() {
        // Day-of-year keys 8..11 sort lexicographically as 10, 11, 8, 9 — must not split the run.
        val sessions = listOf(
            session(at(2026, 1, 8)),
            session(at(2026, 1, 9)),
            session(at(2026, 1, 10)),
            session(at(2026, 1, 11))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 1, 11, 20), timeZone = utc)

        assertEquals(4, stats.longestStreakDays)
        assertEquals(4, stats.currentStreakDays)
    }

    @Test
    fun runCrossingMonthBoundary_countsAsConsecutive() {
        val sessions = listOf(
            session(at(2026, 1, 30)),
            session(at(2026, 1, 31)),
            session(at(2026, 2, 1)),
            session(at(2026, 2, 2))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 2, 2, 20), timeZone = utc)

        assertEquals(4, stats.longestStreakDays)
        assertEquals(4, stats.currentStreakDays)
    }

    @Test
    fun runCrossingYearBoundary_countsAsConsecutive() {
        val sessions = listOf(
            session(at(2025, 12, 30)),
            session(at(2025, 12, 31)),
            session(at(2026, 1, 1)),
            session(at(2026, 1, 2))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 1, 2, 20), timeZone = utc)

        assertEquals(4, stats.longestStreakDays)
        assertEquals(4, stats.currentStreakDays)
    }

    @Test
    fun unsortedInput_doesNotAffectStreaks() {
        val sessions = listOf(
            session(at(2026, 3, 3)),
            session(at(2026, 3, 1)),
            session(at(2026, 3, 2))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 3, 3, 23), timeZone = utc)

        assertEquals(3, stats.longestStreakDays)
        assertEquals(3, stats.currentStreakDays)
    }

    @Test
    fun multipleSessionsSameDay_countAsOneStreakDay() {
        val sessions = listOf(
            session(at(2026, 5, 10, 8)),
            session(at(2026, 5, 10, 13)),
            session(at(2026, 5, 10, 21))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 5, 10, 22), timeZone = utc)

        assertEquals(1, stats.longestStreakDays)
        assertEquals(1, stats.currentStreakDays)
        assertEquals(3, stats.sessionCount)
    }

    @Test
    fun streakEndingYesterday_isStillCurrent() {
        val sessions = listOf(
            session(at(2026, 5, 8)),
            session(at(2026, 5, 9))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 5, 10, 9), timeZone = utc)

        assertEquals(2, stats.currentStreakDays)
        assertEquals(2, stats.longestStreakDays)
    }

    @Test
    fun currentStreakAnchoredOnYesterday_stopsAtGap() {
        // Active: May 5, May 6, (gap May 7, May 8), May 9. Today is May 10.
        val sessions = listOf(
            session(at(2026, 5, 5)),
            session(at(2026, 5, 6)),
            session(at(2026, 5, 9))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 5, 10, 9), timeZone = utc)

        assertEquals(1, stats.currentStreakDays)
        assertEquals(2, stats.longestStreakDays)
    }

    @Test
    fun streakEndingTwoDaysAgo_isNotCurrent() {
        val sessions = listOf(
            session(at(2026, 5, 6)),
            session(at(2026, 5, 7)),
            session(at(2026, 5, 8))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 5, 10, 9), timeZone = utc)

        assertEquals(0, stats.currentStreakDays)
        assertEquals(3, stats.longestStreakDays)
    }

    @Test
    fun longestStreakInPast_doesNotInflateCurrentStreak() {
        val sessions = listOf(
            session(at(2026, 4, 1)),
            session(at(2026, 4, 2)),
            session(at(2026, 4, 3)),
            session(at(2026, 4, 4)),
            session(at(2026, 4, 20))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 4, 20, 18), timeZone = utc)

        assertEquals(4, stats.longestStreakDays)
        assertEquals(1, stats.currentStreakDays)
    }

    @Test
    fun futureDatedSession_doesNotAnchorCurrentStreak() {
        val sessions = listOf(
            session(at(2026, 5, 1)),
            session(at(2026, 5, 20))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 5, 10, 9), timeZone = utc)

        assertEquals(0, stats.currentStreakDays)
        assertEquals(1, stats.longestStreakDays)
    }

    @Test
    fun futureDatedSession_doesNotExtendTodayStreak() {
        val sessions = listOf(
            session(at(2026, 5, 9)),
            session(at(2026, 5, 10)),
            session(at(2026, 5, 25))
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 5, 10, 9), timeZone = utc)

        assertEquals(2, stats.currentStreakDays)
    }

    @Test
    fun runSpanningDstStart_countsAsConsecutive() {
        // Sydney moves to DST on 2026-10-04 (02:00 -> 03:00).
        val sessions = listOf(
            session(at(2026, 10, 2, 12, sydney)),
            session(at(2026, 10, 3, 12, sydney)),
            session(at(2026, 10, 4, 12, sydney)),
            session(at(2026, 10, 5, 12, sydney))
        )

        val stats = StreakCalculator.calculateStats(
            sessions,
            nowMs = at(2026, 10, 5, 20, sydney),
            timeZone = sydney
        )

        assertEquals(4, stats.longestStreakDays)
        assertEquals(4, stats.currentStreakDays)
    }

    @Test
    fun runSpanningDstEnd_countsAsConsecutive() {
        // Sydney leaves DST on 2026-04-05 (03:00 -> 02:00).
        val sessions = listOf(
            session(at(2026, 4, 3, 12, sydney)),
            session(at(2026, 4, 4, 12, sydney)),
            session(at(2026, 4, 5, 12, sydney)),
            session(at(2026, 4, 6, 12, sydney))
        )

        val stats = StreakCalculator.calculateStats(
            sessions,
            nowMs = at(2026, 4, 6, 20, sydney),
            timeZone = sydney
        )

        assertEquals(4, stats.longestStreakDays)
        assertEquals(4, stats.currentStreakDays)
    }

    @Test
    fun lateNightSession_belongsToItsLocalDay() {
        // 23:30 Sydney on May 9 is 13:30 UTC on May 9, but 00:30 UTC on May 10 at +11.
        val sessions = listOf(
            session(at(2026, 5, 9, 23, sydney)),
            session(at(2026, 5, 10, 1, sydney))
        )

        val stats = StreakCalculator.calculateStats(
            sessions,
            nowMs = at(2026, 5, 10, 9, sydney),
            timeZone = sydney
        )

        assertEquals(2, stats.currentStreakDays)
        assertEquals(2, stats.longestStreakDays)
    }

    @Test
    fun totalTime_prefersStoredDurationThenFallsBackToTimestamps() {
        val sessions = listOf(
            session(at(2026, 6, 1), durationMs = 90L * 60L * 1000L, endMs = at(2026, 6, 1) + oneHourMs),
            session(at(2026, 6, 2), durationMs = null, endMs = at(2026, 6, 2) + 2 * oneHourMs),
            session(at(2026, 6, 3), durationMs = null, endMs = null)
        )

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 6, 3, 18), timeZone = utc)

        assertEquals(90L * 60L * 1000L + 2 * oneHourMs, stats.totalBrickedTimeMs)
        assertEquals(3, stats.sessionCount)
        assertEquals(3, stats.currentStreakDays)
    }

    @Test
    fun negativeTimestampDelta_doesNotReduceTotalTime() {
        val start = at(2026, 6, 1)
        val sessions = listOf(session(start, durationMs = null, endMs = start - oneHourMs))

        val stats = StreakCalculator.calculateStats(sessions, nowMs = at(2026, 6, 1, 18), timeZone = utc)

        assertEquals(0L, stats.totalBrickedTimeMs)
    }
}
