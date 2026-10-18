package com.brick.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    /**
     * Retrieves the current actively bricked session (where [endTimeMs] is null).
     */
    @Query("SELECT * FROM brick_sessions WHERE end_time_ms IS NULL ORDER BY start_time_ms DESC LIMIT 1")
    suspend fun getActiveSession(): BrickSessionEntity?

    /**
     * Continuously observes the current actively bricked session.
     */
    @Query("SELECT * FROM brick_sessions WHERE end_time_ms IS NULL ORDER BY start_time_ms DESC LIMIT 1")
    fun observeActiveSession(): Flow<BrickSessionEntity?>

    @Query("SELECT * FROM brick_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): BrickSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: BrickSessionEntity): Long

    @Update
    suspend fun update(session: BrickSessionEntity)

    /**
     * Closes an active session by recording end time, duration, and the trigger that unbricked it.
     */
    @Query("""
        UPDATE brick_sessions 
        SET end_time_ms = :endTimeMs, 
            duration_ms = CASE WHEN (:endTimeMs - start_time_ms) > 0 THEN (:endTimeMs - start_time_ms) ELSE 0 END, 
            unbrick_trigger = :trigger 
        WHERE id = :sessionId
    """)
    suspend fun closeSession(sessionId: Long, endTimeMs: Long, trigger: UnbrickTrigger)

    /**
     * Increments the count of blocked application launch attempts during this session.
     */
    @Query("UPDATE brick_sessions SET blocked_attempts_count = blocked_attempts_count + 1 WHERE id = :sessionId")
    suspend fun incrementBlockedAttempts(sessionId: Long)

    /**
     * Observes all completed sessions in reverse chronological order for stats and history.
     */
    @Query("SELECT * FROM brick_sessions WHERE end_time_ms IS NOT NULL ORDER BY start_time_ms DESC")
    fun observeCompletedSessions(): Flow<List<BrickSessionEntity>>

    @Query("SELECT * FROM brick_sessions WHERE end_time_ms IS NOT NULL ORDER BY start_time_ms DESC")
    suspend fun getAllCompletedSessions(): List<BrickSessionEntity>

    /**
     * Computes the total time spent bricked across all historical sessions in milliseconds.
     */
    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM brick_sessions WHERE end_time_ms IS NOT NULL")
    suspend fun getTotalDurationMs(): Long

    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM brick_sessions WHERE end_time_ms IS NOT NULL")
    fun observeTotalDurationMs(): Flow<Long>

    @Query("SELECT COUNT(*) FROM brick_sessions WHERE end_time_ms IS NOT NULL")
    suspend fun getCompletedSessionCount(): Int

    @Query("SELECT COUNT(*) FROM brick_sessions WHERE end_time_ms IS NOT NULL")
    fun observeCompletedSessionCount(): Flow<Int>

    @Delete
    suspend fun delete(session: BrickSessionEntity)

    @Query("DELETE FROM brick_sessions WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM brick_sessions")
    suspend fun deleteAll(): Int

    // Convenience delegates for alternative naming conventions
    suspend fun insertSession(session: BrickSessionEntity): Long = insert(session)
    suspend fun updateSession(session: BrickSessionEntity) = update(session)
    fun getActiveSessionFlow(): Flow<BrickSessionEntity?> = observeActiveSession()
    suspend fun getCompletedSessions(): List<BrickSessionEntity> = getAllCompletedSessions()
    suspend fun getTotalBrickedTimeMs(): Long = getTotalDurationMs()
}
