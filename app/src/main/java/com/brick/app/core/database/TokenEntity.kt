package com.brick.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a physical hardware NFC tag or printed QR token paired with the device.
 * Token hashes are computed using SHA-256 with an application salt.
 */
@Entity(
    tableName = "tokens",
    indices = [
        Index(value = ["token_hash"], unique = true)
    ]
)
data class TokenEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "token_hash")
    val tokenHash: String,

    @ColumnInfo(name = "token_type")
    val tokenType: TokenType,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,

    @ColumnInfo(name = "is_primary")
    val isPrimary: Boolean = false
)

enum class TokenType {
    NFC,
    QR
}
