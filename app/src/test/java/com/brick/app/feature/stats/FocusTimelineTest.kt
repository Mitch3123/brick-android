package com.brick.app.feature.stats

import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.ModeType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FocusTimelineTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun instant(y: Int, mon: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(utc).apply {
            clear(); set(y, mon - 1, day, hour, 0, 0)
        }.timeInMillis
    private fun session(from: Long, to: Long?) = BrickSessionEntity(
        id = from, startTimeMs = from, endTimeMs = to,
        modeId = 1L, modeName = "Work", modeType = ModeType.BLOCK_LIST
    )

    @Test fun headlineTotalsAgreeWithRowsAndDailyTilesForCompletedSessions() {
        val now = instant(2026, 10, 22, 12)
        val first = session(instant(2026, 10, 21, 23), instant(2026, 10, 22, 1))
            .copy(durationMs = 99 * 3_600_000L)
        val second = session(instant(2026, 10, 22, 10), now + 3_600_000L)
        val sessions = listOf(first, second)
        val tiles = FocusTimeline.recentDays(sessions, count = 2, nowMs = now, zone = utc)
        val rowTotal = sessions.sumOf { FocusTimeline.sessionDurationMs(it, now) }
        assertEquals(4 * 3_600_000L, rowTotal)
        assertEquals(rowTotal, tiles.sumOf { it.durationMs })
        assertEquals(3 * 3_600_000L, tiles[0].durationMs)
        assertEquals(3_600_000L, tiles[1].durationMs)
    }

    @Test fun overnightFocusIsSplitByLocalDay() {
        val start = instant(2026, 10, 21, 23)
        val days = FocusTimeline.recentDays(listOf(session(start, instant(2026, 10, 22, 1))),
            count = 3, nowMs = instant(2026, 10, 22, 12), zone = utc)
        assertEquals(3, days.size)
        assertEquals(3_600_000L, days[0].durationMs)
        assertEquals(3_600_000L, days[1].durationMs)
        assertEquals(1, days[0].sessionCount)
        assertEquals(1, days[1].sessionCount)
        assertEquals(0L, days[2].durationMs)
    }

    @Test fun unfinishedAndFutureTimeNeverAppearInDailyTotals() {
        val now = instant(2026, 10, 22, 12)
        val days = FocusTimeline.recentDays(listOf(
            session(now - 2 * 3_600_000L, null),
            session(now - 3_600_000L, now + 3_600_000L)
        ), nowMs = now, zone = utc)
        assertEquals(3_600_000L, days[0].durationMs)
        assertEquals(1, days[0].sessionCount)
    }

    @Test fun dailyAverageIncludesZeroDays() {
        val days = FocusTimeline.recentDays(
            listOf(session(instant(2026, 10, 22, 9), instant(2026, 10, 22, 16))),
            nowMs = instant(2026, 10, 22, 18), zone = utc)
        assertEquals(3_600_000L, FocusTimeline.averageLastSevenDays(days))
    }

    @Test fun daylightSavingBoundaryUsesCalendarDayRatherThan24Hours() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(ny).apply {
            clear(); set(2026, Calendar.MARCH, 9, 12, 0, 0)
        }.timeInMillis
        val days = FocusTimeline.recentDays(emptyList(), count = 3, nowMs = now, zone = ny)
        assertEquals(23 * 3_600_000L, days[0].startMs - days[1].startMs)
    }
}
