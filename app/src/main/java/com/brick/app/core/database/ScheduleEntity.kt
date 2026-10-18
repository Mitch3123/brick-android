package com.brick.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Defines an automated schedule rule that auto-bricks the device at a specified time.
 * Cascade deletion ensures that if a parent mode is deleted, associated schedules are removed.
 */
@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = ModeEntity::class,
            parentColumns = ["id"],
            childColumns = ["mode_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mode_id"])
    ]
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "mode_id")
    val modeId: Long,

    /**
     * Bitmask for active days of the week:
     * Bit 0 (1)  = Monday
     * Bit 1 (2)  = Tuesday
     * Bit 2 (4)  = Wednesday
     * Bit 3 (8)  = Thursday
     * Bit 4 (16) = Friday
     * Bit 5 (32) = Saturday
     * Bit 6 (64) = Sunday
     * Value 127 = Every day
     */
    @ColumnInfo(name = "days_of_week")
    val daysOfWeek: Int,

    @ColumnInfo(name = "start_hour")
    val startHour: Int, // 0..23

    @ColumnInfo(name = "start_minute")
    val startMinute: Int, // 0..59

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Checks if this schedule is active on a given day of the week.
     * @param dayOfWeek 1 = Monday, ..., 7 = Sunday (matching java.time.DayOfWeek.value)
     */
    fun isDayActive(dayOfWeek: Int): Boolean {
        if (dayOfWeek !in 1..7) return false
        val mask = 1 shl (dayOfWeek - 1)
        return (daysOfWeek and mask) != 0
    }

    companion object {
        const val MONDAY = 1
        const val TUESDAY = 2
        const val WEDNESDAY = 4
        const val THURSDAY = 8
        const val FRIDAY = 16
        const val SATURDAY = 32
        const val SUNDAY = 64

        const val ALL_DAYS = 127
        const val WEEKDAYS = 31
        const val WEEKENDS = 96
    }
}
