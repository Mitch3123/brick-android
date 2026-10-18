package com.brick.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules ORDER BY start_hour ASC, start_minute ASC")
    fun observeAllSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY start_hour ASC, start_minute ASC")
    suspend fun getAllSchedules(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE is_enabled = 1 ORDER BY start_hour ASC, start_minute ASC")
    suspend fun getEnabledSchedules(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE is_enabled = 1 ORDER BY start_hour ASC, start_minute ASC")
    fun observeEnabledSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE mode_id = :modeId")
    suspend fun getSchedulesByModeId(modeId: Long): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: ScheduleEntity): Long

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Query("UPDATE schedules SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun delete(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM schedules WHERE mode_id = :modeId")
    suspend fun deleteByModeId(modeId: Long): Int

    @Query("SELECT COUNT(*) FROM schedules")
    suspend fun getScheduleCount(): Int

    @Query("DELETE FROM schedules")
    suspend fun deleteAll(): Int

    // Convenience delegates for alternative naming conventions
    suspend fun insertSchedule(schedule: ScheduleEntity): Long = insert(schedule)
    suspend fun getSchedulesForMode(modeId: Long): List<ScheduleEntity> = getSchedulesByModeId(modeId)
}
