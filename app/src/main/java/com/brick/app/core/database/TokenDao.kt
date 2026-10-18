package com.brick.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TokenDao {

    @Query("SELECT * FROM tokens ORDER BY is_primary DESC, created_at ASC")
    fun observeAllTokens(): Flow<List<TokenEntity>>

    @Query("SELECT * FROM tokens ORDER BY is_primary DESC, created_at ASC")
    suspend fun getAllTokens(): List<TokenEntity>

    @Query("SELECT * FROM tokens WHERE token_hash = :tokenHash LIMIT 1")
    suspend fun findByHash(tokenHash: String): TokenEntity?

    @Query("SELECT * FROM tokens WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TokenEntity?

    @Query("SELECT * FROM tokens WHERE is_primary = 1 LIMIT 1")
    suspend fun getPrimaryToken(): TokenEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(token: TokenEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tokens: List<TokenEntity>): List<Long>

    @Update
    suspend fun update(token: TokenEntity)

    @Query("UPDATE tokens SET last_used_at = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: Long, timestamp: Long)

    @Query("UPDATE tokens SET is_primary = CASE WHEN id = :primaryId THEN 1 ELSE 0 END")
    suspend fun setPrimaryToken(primaryId: Long)

    @Delete
    suspend fun delete(token: TokenEntity)

    @Query("DELETE FROM tokens WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM tokens")
    suspend fun getTokenCount(): Int

    @Query("SELECT COUNT(*) FROM tokens")
    fun observeTokenCount(): Flow<Int>

    @Query("DELETE FROM tokens")
    suspend fun deleteAll(): Int

    // Convenience delegates for alternative naming conventions
    suspend fun insertToken(token: TokenEntity): Long = insert(token)
    fun getAllTokensFlow(): Flow<List<TokenEntity>> = observeAllTokens()
    suspend fun deleteTokenById(id: Long): Int = deleteById(id)
}
