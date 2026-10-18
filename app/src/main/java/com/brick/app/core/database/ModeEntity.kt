package com.brick.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * Defines a focus mode profile.
 * - BLOCK_LIST: apps in [packageNames] are blocked; all other apps are allowed.
 * - ALLOW_LIST: apps in [packageNames] (plus system whitelist) are allowed; all other apps are blocked.
 */
@Entity(tableName = "modes")
data class ModeEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "mode_type")
    val modeType: ModeType,

    @ColumnInfo(name = "packages")
    val packageNames: Set<String>,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    @Ignore
    constructor(
        name: String,
        type: ModeType,
        packages: List<String>,
        isActive: Boolean = false
    ) : this(
        id = 0,
        name = name,
        modeType = type,
        packageNames = packages.toSet(),
        isDefault = isActive,
        createdAt = System.currentTimeMillis()
    )

    val packages: List<String>
        get() = packageNames.toList()

    val type: ModeType
        get() = modeType

    val isActive: Boolean
        get() = isDefault
}

enum class ModeType {
    BLOCK_LIST,
    ALLOW_LIST
}
