package com.brick.app.feature.schedules

import com.brick.app.core.database.ScheduleEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure weekday-bitmask / next-occurrence arithmetic for schedules.
 *
 * Deliberately free of Android types so the occurrence maths is unit-testable on a bare JVM.
 * Uses [Calendar] rather than `java.time` because the module sets `minSdk = 24` without core
 * library desugaring enabled, so `java.time` is unavailable at runtime below API 26.
 */
object ScheduleClock {

    const val DAYS_IN_WEEK = 7

    /**
     * Converts [Calendar.DAY_OF_WEEK] (SUNDAY = 1 .. SATURDAY = 7) to the ISO-8601 numbering
     * used by [ScheduleEntity.daysOfWeek] (MONDAY = 1 .. SUNDAY = 7).
     */
    fun isoDayOfWeek(calendar: Calendar): Int = ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    /** Strips bits outside the 7 known weekday bits so junk persisted data cannot widen a schedule. */
    fun normaliseMask(daysOfWeek: Int): Int = daysOfWeek and ScheduleEntity.ALL_DAYS

    fun isDayActive(daysOfWeek: Int, isoDayOfWeek: Int): Boolean {
        if (isoDayOfWeek !in 1..DAYS_IN_WEEK) return false
        return (normaliseMask(daysOfWeek) and (1 shl (isoDayOfWeek - 1))) != 0
    }

    fun isValidTimeOfDay(hour: Int, minute: Int): Boolean = hour in 0..23 && minute in 0..59

    /**
     * First instant strictly after [afterMillis] whose local time is [hour]:[minute] on a day
     * enabled in [daysOfWeek].
     *
     * @return epoch milliseconds, or `null` when the schedule can never fire (no active day, or
     *   an out-of-range time of day).
     */
    fun nextOccurrenceMillis(
        daysOfWeek: Int,
        hour: Int,
        minute: Int,
        afterMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (!isValidTimeOfDay(hour, minute)) return null
        val mask = normaliseMask(daysOfWeek)
        if (mask == 0) return null

        // Scan today plus a full week: a schedule active only on today's weekday whose time has
        // already passed lands on offset 7.
        for (dayOffset in 0..DAYS_IN_WEEK) {
            val candidate = localTimeOnDay(afterMillis, dayOffset, hour, minute, timeZone)
            if (candidate.timeInMillis <= afterMillis) continue
            if (isDayActive(mask, isoDayOfWeek(candidate))) return candidate.timeInMillis
        }
        return null
    }

    /**
     * Most recent instant at or before [atMillis] matching the schedule, used to detect an
     * occurrence missed while the device was powered off or the app was being replaced.
     */
    fun previousOccurrenceMillis(
        daysOfWeek: Int,
        hour: Int,
        minute: Int,
        atMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (!isValidTimeOfDay(hour, minute)) return null
        val mask = normaliseMask(daysOfWeek)
        if (mask == 0) return null

        for (dayOffset in 0..DAYS_IN_WEEK) {
            val candidate = localTimeOnDay(atMillis, -dayOffset, hour, minute, timeZone)
            if (candidate.timeInMillis > atMillis) continue
            if (isDayActive(mask, isoDayOfWeek(candidate))) return candidate.timeInMillis
        }
        return null
    }

    /**
     * Whether [instantMillis] is a legitimate occurrence of [schedule] as it is stored *now*.
     *
     * An alarm queued before the user edited the schedule still carries the old trigger instant,
     * so the instant must be re-validated against the current row before it is allowed to brick:
     * the local time of day and the weekday must both still match.
     */
    fun matchesOccurrence(
        schedule: ScheduleEntity,
        instantMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        if (!isValidTimeOfDay(schedule.startHour, schedule.startMinute)) return false
        val local = Calendar.getInstance(timeZone).apply { timeInMillis = instantMillis }
        return local.get(Calendar.HOUR_OF_DAY) == schedule.startHour &&
            local.get(Calendar.MINUTE) == schedule.startMinute &&
            isDayActive(schedule.daysOfWeek, isoDayOfWeek(local))
    }

    fun nextOccurrenceMillis(
        schedule: ScheduleEntity,
        afterMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? = nextOccurrenceMillis(
        daysOfWeek = schedule.daysOfWeek,
        hour = schedule.startHour,
        minute = schedule.startMinute,
        afterMillis = afterMillis,
        timeZone = timeZone
    )

    fun previousOccurrenceMillis(
        schedule: ScheduleEntity,
        atMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? = previousOccurrenceMillis(
        daysOfWeek = schedule.daysOfWeek,
        hour = schedule.startHour,
        minute = schedule.startMinute,
        atMillis = atMillis,
        timeZone = timeZone
    )

    /**
     * Rebuilds the calendar from [baseMillis] for every candidate day instead of repeatedly
     * adding 24h, so a day that is 23h or 25h long across a DST transition still resolves to the
     * intended local wall time. A local time that does not exist on a spring-forward day is
     * normalised forward by [Calendar]'s lenient arithmetic.
     */
    private fun localTimeOnDay(
        baseMillis: Long,
        dayOffset: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone
    ): Calendar = Calendar.getInstance(timeZone).apply {
        timeInMillis = baseMillis
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
