package com.brick.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.DatabaseProvider
import com.brick.app.core.database.ModeDao
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.ScheduleDao
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.core.database.SessionDao
import com.brick.app.core.database.TokenDao
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.database.UnbrickTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Empirical Adversarial Test Suite for Milestone 1 Room Database Layer.
 *
 * Stress-tests:
 * 1. Unique constraint violations & foreign key integrity.
 * 2. Multi-child foreign key cascade deletions and isolation between parents.
 * 3. Prepopulation callback behavior on production singleton and in-memory databases.
 * 4. High-concurrency rapid read/write stress harness across multiple threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseAdversarialTest {

    private lateinit var context: Context
    private lateinit var db: BrickDatabase
    private lateinit var tokenDao: TokenDao
    private lateinit var modeDao: ModeDao
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var sessionDao: SessionDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BrickDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        tokenDao = db.tokenDao()
        modeDao = db.modeDao()
        scheduleDao = db.scheduleDao()
        sessionDao = db.sessionDao()
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        db.close()
        // Clean up any disk database created during getInstance tests
        context.deleteDatabase(BrickDatabase.DATABASE_NAME)
    }

    // =========================================================================
    // 1. Unique Constraint & Foreign Key Adversarial Tests
    // =========================================================================

    @Test
    fun tokenDao_uniqueConstraint_rejectsIdenticalHashAcrossDifferentTokenTypes() = runBlocking {
        val sharedHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val nfcToken = TokenEntity(
            tokenHash = sharedHash,
            tokenType = TokenType.NFC,
            label = "NFC Keycard",
            createdAt = 1000L
        )
        val qrToken = TokenEntity(
            tokenHash = sharedHash,
            tokenType = TokenType.QR,
            label = "Backup QR",
            createdAt = 2000L
        )

        val id1 = tokenDao.insert(nfcToken)
        assertTrue(id1 > 0)

        // Attempting to insert QR token with same hash must abort with SQLiteConstraintException
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                tokenDao.insert(qrToken)
            }
        }

        // Database must remain consistent with only 1 token
        assertEquals(1, tokenDao.getTokenCount())
        val retrieved = tokenDao.findByHash(sharedHash)
        assertEquals(TokenType.NFC, retrieved?.tokenType)
    }

    @Test
    fun tokenDao_uniqueConstraint_rejectsDuplicateEmptyHash(): Unit = runBlocking {
        val emptyHash1 = TokenEntity(
            tokenHash = "",
            tokenType = TokenType.NFC,
            label = "Empty Tag 1",
            createdAt = 1000L
        )
        val emptyHash2 = TokenEntity(
            tokenHash = "",
            tokenType = TokenType.QR,
            label = "Empty Tag 2",
            createdAt = 2000L
        )

        tokenDao.insert(emptyHash1)
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                tokenDao.insert(emptyHash2)
            }
        }
    }

    @Test
    fun scheduleDao_foreignKeyConstraint_rejectsNonExistentModeId(): Unit = runBlocking {
        val orphanedSchedule = ScheduleEntity(
            name = "Orphan Rule",
            modeId = 999999L, // non-existent mode
            daysOfWeek = ScheduleEntity.ALL_DAYS,
            startHour = 12,
            startMinute = 0,
            isEnabled = true,
            createdAt = 1000L
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                scheduleDao.insert(orphanedSchedule)
            }
        }
    }

    // =========================================================================
    // 2. Cascade Deletion Stress & Isolation Tests
    // =========================================================================

    @Test
    fun scheduleDao_cascadeDelete_multipleChildrenDeletedWhilePreservingOtherModes() = runBlocking {
        // Create Mode A
        val modeAId = modeDao.insert(
            ModeEntity(name = "Work", modeType = ModeType.BLOCK_LIST, packageNames = emptySet())
        )
        // Create Mode B
        val modeBId = modeDao.insert(
            ModeEntity(name = "Sleep", modeType = ModeType.ALLOW_LIST, packageNames = emptySet())
        )

        // Insert 3 schedules for Mode A
        val schedA1 = scheduleDao.insert(
            ScheduleEntity(name = "Morning", modeId = modeAId, daysOfWeek = ScheduleEntity.WEEKDAYS, startHour = 9, startMinute = 0)
        )
        val schedA2 = scheduleDao.insert(
            ScheduleEntity(name = "Afternoon", modeId = modeAId, daysOfWeek = ScheduleEntity.WEEKDAYS, startHour = 14, startMinute = 0)
        )
        val schedA3 = scheduleDao.insert(
            ScheduleEntity(name = "Evening", modeId = modeAId, daysOfWeek = ScheduleEntity.WEEKDAYS, startHour = 18, startMinute = 0)
        )

        // Insert 2 schedules for Mode B
        val schedB1 = scheduleDao.insert(
            ScheduleEntity(name = "Bedtime", modeId = modeBId, daysOfWeek = ScheduleEntity.ALL_DAYS, startHour = 22, startMinute = 0)
        )
        val schedB2 = scheduleDao.insert(
            ScheduleEntity(name = "Deep Sleep", modeId = modeBId, daysOfWeek = ScheduleEntity.ALL_DAYS, startHour = 23, startMinute = 30)
        )

        assertEquals(5, scheduleDao.getScheduleCount())
        assertEquals(3, scheduleDao.getSchedulesByModeId(modeAId).size)
        assertEquals(2, scheduleDao.getSchedulesByModeId(modeBId).size)

        // Delete Mode A via deleteById
        val deletedCount = modeDao.deleteById(modeAId)
        assertEquals(1, deletedCount)

        // Mode A schedules must be completely purged by foreign key cascade
        assertEquals(0, scheduleDao.getSchedulesByModeId(modeAId).size)
        assertNull(scheduleDao.getScheduleById(schedA1))
        assertNull(scheduleDao.getScheduleById(schedA2))
        assertNull(scheduleDao.getScheduleById(schedA3))

        // Mode B and its schedules must be completely untouched
        assertNotNull(modeDao.getModeById(modeBId))
        assertEquals(2, scheduleDao.getSchedulesByModeId(modeBId).size)
        assertNotNull(scheduleDao.getScheduleById(schedB1))
        assertNotNull(scheduleDao.getScheduleById(schedB2))
        assertEquals(2, scheduleDao.getScheduleCount())
    }

    @Test
    fun scheduleDao_cascadeDelete_triggeredOnEntityDelete() = runBlocking {
        val mode = ModeEntity(name = "Study", modeType = ModeType.BLOCK_LIST, packageNames = emptySet())
        val modeId = modeDao.insert(mode)
        val modeWithId = mode.copy(id = modeId)

        val schedId = scheduleDao.insert(
            ScheduleEntity(name = "Library Time", modeId = modeId, daysOfWeek = ScheduleEntity.WEEKENDS, startHour = 10, startMinute = 0)
        )
        assertNotNull(scheduleDao.getScheduleById(schedId))

        // Delete using delete(entity)
        modeDao.delete(modeWithId)

        assertNull(modeDao.getModeById(modeId))
        assertNull("Schedule must be deleted by cascade when mode entity is deleted", scheduleDao.getScheduleById(schedId))
    }

    // =========================================================================
    // 3. Prepopulation Callback Empirical Tests
    // =========================================================================

    @Test
    fun brickDatabase_prepopulatesDeepWorkMode_onFirstCreation() = runBlocking {
        // Clean any pre-existing database file
        context.deleteDatabase(BrickDatabase.DATABASE_NAME)

        // Request instance from production singleton factory
        val prodDb = BrickDatabase.getInstance(context)

        // Query modeDao to trigger database creation and prepopulate callback
        val defaultMode = prodDb.modeDao().getDefaultMode()
        assertNotNull("PrepopulateCallback must create a default mode", defaultMode)
        assertEquals("Deep Work", defaultMode?.name)
        assertEquals(ModeType.BLOCK_LIST, defaultMode?.modeType)
        assertTrue("Prepopulated mode must be marked as default", defaultMode?.isDefault == true)
        assertEquals("Prepopulated mode package list should be empty set", emptySet<String>(), defaultMode?.packageNames)
        assertTrue(defaultMode!!.createdAt > 0L)
    }

    @Test
    fun brickDatabase_createInMemory_canSupportPrepopulationWhenProvided() = runBlocking {
        // Verify that custom callback can also prepopulate in-memory db if requested
        val customDb = Room.inMemoryDatabaseBuilder(context, BrickDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    db.execSQL(
                        """
                        INSERT INTO modes (name, mode_type, packages, is_default, created_at)
                        VALUES ('Deep Work', 'BLOCK_LIST', '[]', 1, 1000)
                        """.trimIndent()
                    )
                }
            })
            .allowMainThreadQueries()
            .build()

        val defaultMode = customDb.modeDao().getDefaultMode()
        assertNotNull(defaultMode)
        assertEquals("Deep Work", defaultMode?.name)
        customDb.close()
    }

    // =========================================================================
    // 4. High-Concurrency & Stress Tests
    // =========================================================================

    @Test
    fun database_highConcurrency_rapidInsertsAndQueriesUnderStress() = runBlocking {
        val coroutineCount = 40
        val operationsPerCoroutine = 10

        // Concurrently insert tokens across multiple threads on Dispatchers.IO
        val tokenJobs = (1..coroutineCount).map { workerId ->
            async(Dispatchers.IO) {
                for (op in 1..operationsPerCoroutine) {
                    val uniqueHash = "hash_${workerId}_${op}_${System.nanoTime()}"
                    val token = TokenEntity(
                        tokenHash = uniqueHash,
                        tokenType = if (op % 2 == 0) TokenType.NFC else TokenType.QR,
                        label = "Worker-$workerId Token-$op",
                        createdAt = System.currentTimeMillis()
                    )
                    val id = tokenDao.insert(token)
                    assertTrue(id > 0)

                    val retrieved = tokenDao.findByHash(uniqueHash)
                    assertNotNull(retrieved)
                    assertEquals("Worker-$workerId Token-$op", retrieved?.label)
                }
            }
        }

        // Concurrently create sessions, increment attempts, and query active sessions
        val sessionJobs = (1..coroutineCount).map { workerId ->
            async(Dispatchers.IO) {
                val session = BrickSessionEntity(
                    startTimeMs = System.currentTimeMillis(),
                    modeId = 1L,
                    modeName = "Worker-$workerId Mode",
                    modeType = ModeType.BLOCK_LIST
                )
                val sessionId = sessionDao.insert(session)
                assertTrue(sessionId > 0)

                sessionDao.incrementBlockedAttempts(sessionId)
                sessionDao.incrementBlockedAttempts(sessionId)

                val active = sessionDao.getActiveSession()
                assertNotNull(active)

                sessionDao.closeSession(
                    sessionId = sessionId,
                    endTimeMs = System.currentTimeMillis() + 1000L,
                    trigger = UnbrickTrigger.NFC_TAG
                )
            }
        }

        // Concurrently insert and delete modes and schedules
        val modeJobs = (1..20).map { workerId ->
            async(Dispatchers.IO) {
                val modeId = modeDao.insert(
                    ModeEntity(
                        name = "Stress Mode $workerId",
                        modeType = ModeType.BLOCK_LIST,
                        packageNames = setOf("com.pkg.$workerId")
                    )
                )
                val schedId = scheduleDao.insert(
                    ScheduleEntity(
                        name = "Stress Schedule $workerId",
                        modeId = modeId,
                        daysOfWeek = ScheduleEntity.ALL_DAYS,
                        startHour = workerId % 24,
                        startMinute = 0
                    )
                )
                assertNotNull(scheduleDao.getScheduleById(schedId))

                // Delete mode and verify cascade without crashing concurrent readers
                modeDao.deleteById(modeId)
                assertNull(scheduleDao.getScheduleById(schedId))
            }
        }

        // Await all concurrent tasks
        (tokenJobs + sessionJobs + modeJobs).awaitAll()

        // Verify final database state is consistent
        val totalTokens = tokenDao.getTokenCount()
        assertEquals(coroutineCount * operationsPerCoroutine, totalTokens)

        val completedSessions = sessionDao.getCompletedSessionCount()
        assertEquals(coroutineCount, completedSessions)
    }

    // =========================================================================
    // 5. SessionDao Close Session Duration Calculation Edge Cases
    // =========================================================================

    @Test
    fun sessionDao_closeSession_handlesClockBackwardsSafely() = runBlocking {
        // Simulate a scenario where wall-clock or end time is before start time
        val session = BrickSessionEntity(
            startTimeMs = 10_000L,
            modeId = 1L,
            modeName = "Test Mode",
            modeType = ModeType.BLOCK_LIST
        )
        val id = sessionDao.insert(session)

        // Close with endTime < startTime (e.g. wall clock manipulated backwards)
        sessionDao.closeSession(
            sessionId = id,
            endTimeMs = 5_000L,
            trigger = UnbrickTrigger.MANUAL_DEBUG
        )

        val closed = sessionDao.getSessionById(id)
        assertNotNull(closed)
        assertEquals(5_000L, closed?.endTimeMs)
        // CASE WHEN (:endTimeMs - start_time_ms) > 0 THEN ... ELSE 0 END guarantees duration >= 0
        assertEquals(0L, closed?.durationMs)
    }
}
