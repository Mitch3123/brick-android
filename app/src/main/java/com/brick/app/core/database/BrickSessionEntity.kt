package com.brick.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records focus sessions for tracking active state, stats, streak calculation, and history.
 * An active session has [endTimeMs] == null.
 */
@Entity(tableName = "brick_sessions")
data class BrickSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "start_time_ms")
    val startTimeMs: Long,

    @ColumnInfo(name = "end_time_ms")
    val endTimeMs: Long? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    @ColumnInfo(name = "mode_id")
    val modeId: Long,

    @ColumnInfo(name = "mode_name")
    val modeName: String,

    @ColumnInfo(name = "mode_type")
    val modeType: ModeType,

    @ColumnInfo(name = "unbrick_trigger")
    val unbrickTrigger: UnbrickTrigger? = null,

    @ColumnInfo(name = "strict_mode_active")
    val strictModeActive: Boolean = false,

    @ColumnInfo(name = "blocked_attempts_count")
    val blockedAttemptsCount: Int = 0
) {
    val isActive: Boolean
        get() = endTimeMs == null
}

enum class UnbrickTrigger {
    NFC_TAG,
    QR_CODE,
    RECOVERY_24H,
    MANUAL_DEBUG
}
