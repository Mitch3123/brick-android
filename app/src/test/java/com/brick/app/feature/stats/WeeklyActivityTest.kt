package com.brick.app.feature.stats

import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.ModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WeeklyActivityTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private fun at(y: Int, mon: Int, day: Int, h: Int, zone: TimeZone = utc): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, mon - 1, day, h, 0, 0) }.timeInMillis
    private fun session(from: Long, to: Long?) = BrickSessionEntity(
        id = from, startTimeMs = from, endTimeMs = to,
        modeId = 1L, modeName = "Work", modeType = ModeType.BLOCK_LIST
    )

    // Thursday 22 Oct 2026; its week is Sun 18 .. Sat 24.
    private val thursday = at(2026, 10, 22, 12)

    @Test fun weekIsSundayFirstAndAlwaysSevenDays() {
        val week = WeeklyActivity.week(emptyList(), 0, thursday, utc)
        assertEquals(7, week.days.size)
        assertEquals(at(2026, 10, 18, 0), week.days.first().startMs)
        assertEquals(at(2026, 10, 24, 0), week.days.last().startMs)
        assertEquals(at(2026, 10, 18, 0), week.weekStartMs)
        assertTrue(week.isCurrentWeek)
        assertEquals(5, week.elapsedDays)
    }

    @Test fun sundayNowStillStartsTheWeekToday() {
        val sunday = at(2026, 10, 18, 9)
        val week = WeeklyActivity.week(emptyList(), 0, sunday, utc)
        assertEquals(at(2026, 10, 18, 0), week.weekStartMs)
        assertEquals(1, week.elapsedDays)
        assertEquals(0, week.completedDays)
    }

    @Test fun realSessionsLandOnTheirOwnDayAndFutureDaysStayZero() {
        val sessions = listOf(
            session(at(2026, 10, 19, 9), at(2026, 10, 19, 10)),
            session(at(2026, 10, 22, 8), at(2026, 10, 22, 10)),
            session(at(2026, 10, 22, 11), null)
        )
        val week = WeeklyActivity.week(sessions, 0, thursday, utc)
        assertEquals(0L, week.days[0].durationMs)
        assertEquals(hour, week.days[1].durationMs)
        assertEquals(2 * hour, week.days[4].durationMs)
        assertEquals(0L, week.days[5].durationMs)
        assertEquals(0L, week.days[6].durationMs)
        assertEquals(3 * hour, week.totalMs)
        assertEquals(2 * hour, week.maxDayMs)
    }

    @Test fun overnightSessionIsSplitAcrossWeekBoundaryOnlyWithinTheWeek() {
        val sessions = listOf(session(at(2026, 10, 17, 23), at(2026, 10, 18, 2)))
        val current = WeeklyActivity.week(sessions, 0, thursday, utc)
        val previous = WeeklyActivity.week(sessions, -1, thursday, utc)
        assertEquals(2 * hour, current.days[0].durationMs)
        assertEquals(hour, previous.days[6].durationMs)
        assertEquals(0L, previous.days[0].durationMs)
    }

    @Test fun lastWeekIsFullySevenElapsedDaysAndPositiveOffsetsClamp() {
        val previous = WeeklyActivity.week(emptyList(), -1, thursday, utc)
        assertEquals(at(2026, 10, 11, 0), previous.weekStartMs)
        assertEquals(7, previous.elapsedDays)
        assertEquals(7, previous.completedDays)
        assertFalse(previous.isCurrentWeek)
        assertEquals(at(2026, 10, 18, 0), WeeklyActivity.week(emptyList(), 3, thursday, utc).weekStartMs)
    }

    @Test fun averageExcludesTheDayInProgressAndIsNullWithoutTruthfulData() {
        assertNull(WeeklyActivity.week(emptyList(), 0, thursday, utc).averageMs)
        val onlyToday = listOf(session(at(2026, 10, 22, 8), at(2026, 10, 22, 10)))
        val withToday = WeeklyActivity.week(onlyToday, 0, thursday, utc)
        assertEquals(0L, withToday.averageMs)
        val sunday = at(2026, 10, 18, 9)
        assertNull(WeeklyActivity.week(listOf(session(sunday - 1, sunday + hour)), 0, sunday + 2 * hour, utc).averageMs)
        val sessions = listOf(
            session(at(2026, 10, 18, 9), at(2026, 10, 18, 11)),
            session(at(2026, 10, 21, 9), at(2026, 10, 21, 11)),
            session(at(2026, 10, 22, 8), at(2026, 10, 22, 11))
        )
        val week = WeeklyActivity.week(sessions, 0, thursday, utc)
        assertEquals(4, week.completedDays)
        assertEquals(hour, week.averageMs)
    }

    @Test fun lastWeekAverageDividesBySevenDays() {
        val sessions = listOf(session(at(2026, 10, 12, 9), at(2026, 10, 12, 16)))
        assertEquals(hour, WeeklyActivity.week(sessions, -1, thursday, utc).averageMs)
    }

    @Test fun localTimeZoneBoundariesAreRespected() {
        val sydney = TimeZone.getTimeZone("Australia/Sydney")
        val now = at(2026, 10, 22, 12, sydney)
        val week = WeeklyActivity.week(
            listOf(session(at(2026, 10, 18, 0, sydney) + 30 * 60_000L, at(2026, 10, 18, 1, sydney))),
            0, now, sydney)
        assertEquals(at(2026, 10, 18, 0, sydney), week.weekStartMs)
        assertEquals(30 * 60_000L, week.days[0].durationMs)
    }

    @Test fun axisScalesToWholeHoursWithOneHourMinimum() {
        assertEquals(hour, WeeklyActivity.axisMaxMs(0))
        assertEquals(hour, WeeklyActivity.axisMaxMs(hour))
        assertEquals(2 * hour, WeeklyActivity.axisMaxMs(hour + 1))
        assertEquals(3 * hour, WeeklyActivity.axisMaxMs(3 * hour - 1))
    }

    @Test fun compactDurationFormatsAxisAndAverageLabels() {
        assertEquals("0m", WeeklyActivity.compactDuration(-5))
        assertEquals("30m", WeeklyActivity.compactDuration(30 * 60_000L))
        assertEquals("1h", WeeklyActivity.compactDuration(hour))
        assertEquals("1h 20m", WeeklyActivity.compactDuration(80 * 60_000L))
    }
    @Test fun yearBoundaryStartsInPreviousYear() {
        val week = WeeklyActivity.week(emptyList(), 0, at(2027, 1, 1, 12), utc)
        assertEquals(at(2026, 12, 27, 0), week.weekStartMs)
        assertEquals(at(2027, 1, 2, 0), week.days.last().startMs)
    }

    @Test fun sydneyDaylightSavingWeekUsesLocalMidnights() {
        val zone = TimeZone.getTimeZone("Australia/Sydney")
        val week = WeeklyActivity.week(emptyList(), -1, at(2026, 10, 12, 12, zone), zone)
        assertEquals(at(2026, 10, 4, 0, zone), week.weekStartMs)
        assertEquals(23 * hour, week.days[1].startMs - week.days[0].startMs)
    }

}
