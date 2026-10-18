package com.brick.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TokenEntity::class,
        ModeEntity::class,
        ScheduleEntity::class,
        BrickSessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BrickDatabase : RoomDatabase() {

    abstract fun tokenDao(): TokenDao
    abstract fun modeDao(): ModeDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val DATABASE_NAME = "brick_database.db"

        @Volatile
        private var INSTANCE: BrickDatabase? = null

        fun getInstance(context: Context): BrickDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * Creates an isolated in-memory database for JVM unit tests and integration tests.
         */
        fun createInMemory(context: Context): BrickDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                BrickDatabase::class.java
            )
            .allowMainThreadQueries()
            .build()
        }

        private fun buildDatabase(context: Context): BrickDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                BrickDatabase::class.java,
                DATABASE_NAME
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(PrepopulateCallback())
            .build()
        }
    }

    private class PrepopulateCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            val now = System.currentTimeMillis()
            // Prepopulate a default "Deep Work" block-list mode so the app is immediately ready
            db.execSQL(
                """
                INSERT INTO modes (name, mode_type, packages, is_default, created_at)
                VALUES ('Deep Work', 'BLOCK_LIST', '[]', 1, $now)
                """.trimIndent()
            )
        }
    }
}
