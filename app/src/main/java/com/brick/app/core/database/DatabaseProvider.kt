package com.brick.app.core.database

import android.content.Context

/**
 * Singleton database provider and service locator for the data access layer.
 * Decouples consumers from direct database initialization and supports mock/in-memory substitution in tests.
 */
object DatabaseProvider {

    @Volatile
    private var databaseInstance: BrickDatabase? = null

    fun getDatabase(context: Context): BrickDatabase {
        return databaseInstance ?: synchronized(this) {
            databaseInstance ?: BrickDatabase.getInstance(context.applicationContext).also {
                databaseInstance = it
            }
        }
    }

    fun getTokenDao(context: Context): TokenDao = getDatabase(context).tokenDao()
    fun getModeDao(context: Context): ModeDao = getDatabase(context).modeDao()
    fun getScheduleDao(context: Context): ScheduleDao = getDatabase(context).scheduleDao()
    fun getSessionDao(context: Context): SessionDao = getDatabase(context).sessionDao()

    /**
     * Test hook to override the database with an in-memory or mock instance.
     */
    fun setTestDatabase(testDatabase: BrickDatabase?) {
        synchronized(this) {
            databaseInstance = testDatabase
        }
    }
}
