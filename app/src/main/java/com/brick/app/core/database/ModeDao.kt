package com.brick.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ModeDao {

    @Query("SELECT * FROM modes ORDER BY is_default DESC, name ASC")
    fun observeAllModes(): Flow<List<ModeEntity>>

    @Query("SELECT * FROM modes ORDER BY is_default DESC, name ASC")
    suspend fun getAllModes(): List<ModeEntity>

    @Query("SELECT * FROM modes WHERE id = :id LIMIT 1")
    fun observeModeById(id: Long): Flow<ModeEntity?>

    @Query("SELECT * FROM modes WHERE id = :id LIMIT 1")
    suspend fun getModeById(id: Long): ModeEntity?

    @Query("SELECT * FROM modes WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultMode(): ModeEntity?

    @Query("SELECT * FROM modes WHERE is_default = 1 LIMIT 1")
    fun observeDefaultMode(): Flow<ModeEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mode: ModeEntity): Long

    @Update
    suspend fun update(mode: ModeEntity)

    @Query("UPDATE modes SET is_default = CASE WHEN id = :modeId THEN 1 ELSE 0 END")
    suspend fun setDefaultMode(modeId: Long)

    @Delete
    suspend fun delete(mode: ModeEntity)

    @Query("DELETE FROM modes WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM modes")
    suspend fun getModeCount(): Int

    @Query("DELETE FROM modes")
    suspend fun deleteAll(): Int

    // Convenience delegates for alternative naming conventions
    suspend fun insertMode(mode: ModeEntity): Long = insert(mode)
    suspend fun updateMode(mode: ModeEntity) = update(mode)
    suspend fun deleteModeById(id: Long): Int = deleteById(id)

    suspend fun insertOrUpdateActive(mode: ModeEntity): Long {
        val id = insert(mode)
        if (mode.isDefault) {
            setDefaultMode(id)
        }
        return id
    }
}
