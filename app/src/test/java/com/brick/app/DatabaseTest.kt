package com.brick.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.Converters
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
import java.io.IOException

/**
 * In-memory Room database tests verifying CRUD operations, Flow queries,
 * unique constraints, foreign-key cascades, and TypeConverter serialization
 * on [TokenDao], [ModeDao], [ScheduleDao], and [SessionDao].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseTest {

    private lateinit var db: BrickDatabase
    private lateinit var tokenDao: TokenDao
    private lateinit var modeDao: ModeDao
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var sessionDao: SessionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun closeDb() {
        db.close()
    }

    // =========================================================================
    // TokenDao CRUD Tests
    // =========================================================================

    @Test
    fun tokenDao_insertAndFindByHash() = runBlocking {
        val token = TokenEntity(
            tokenHash = "a1b2c3d4e5f600112233445566778899aabbccddeeff00112233445566778899",
            tokenType = TokenType.NFC,
            label = "Office Desk Tag",
            createdAt = 1000L,
            isPrimary = true
        )

        val id = tokenDao.insertToken(token)
        assertTrue("Inserted token ID must be > 0", id > 0)

        val retrieved = tokenDao.findByHash(token.tokenHash)
        assertNotNull("Token must be found by hash", retrieved)
        assertEquals("Office Desk Tag", retrieved?.label)
        assertEquals(TokenType.NFC, retrieved?.tokenType)
        assertTrue(retrieved?.isPrimary == true)
        assertNull("lastUsedAt should initially be null", retrieved?.lastUsedAt)

        val notFound = tokenDao.findByHash("non_existent_hash")
        assertNull("Unpaired hash must return null", notFound)
    }

    @Test
    fun tokenDao_getAllTokens_and_flow() = runBlocking {
        val token1 = TokenEntity(
            tokenHash = "hash_1111111111111111111111111111111111111111111111111111111111111111",
            tokenType = TokenType.NFC,
            label = "Primary Tag",
            createdAt = 1000L,
            isPrimary = true
        )
        val token2 = TokenEntity(
            tokenHash = "hash_2222222222222222222222222222222222222222222222222222222222222222",
            tokenType = TokenType.QR,
            label = "Desk QR Code",
            createdAt = 2000L,
            isPrimary = false
        )

        tokenDao.insertToken(token1)
        tokenDao.insertToken(token2)

        val list = tokenDao.getAllTokens()
        assertEquals(2, list.size)

        val flowList = tokenDao.getAllTokensFlow().first()
        assertEquals(2, flowList.size)
        assertEquals(2, tokenDao.getTokenCount())
    }

    @Test
    fun tokenDao_updateLastUsed() = runBlocking {
        val token = TokenEntity(
            tokenHash = "hash_3333333333333333333333333333333333333333333333333333333333333333",
            tokenType = TokenType.NFC,
            label = "Kitchen Tag",
            createdAt = 1000L
        )
        val id = tokenDao.insertToken(token)

        tokenDao.updateLastUsed(id, 9999L)

        val updated = tokenDao.findByHash(token.tokenHash)
        assertEquals(9999L, updated?.lastUsedAt)
    }

    @Test
    fun tokenDao_deleteToken_and_deleteById() = runBlocking {
        val token = TokenEntity(
            tokenHash = "hash_4444444444444444444444444444444444444444444444444444444444444444",
            tokenType = TokenType.QR,
            label = "Spare QR",
            createdAt = 1000L
        )
        val id = tokenDao.insertToken(token)
        assertEquals(1, tokenDao.getTokenCount())

        tokenDao.deleteTokenById(id)
        assertEquals(0, tokenDao.getTokenCount())
        assertNull(tokenDao.findByHash(token.tokenHash))
    }

    @Test
    fun tokenDao_uniqueHashConstraint_throwsExceptionOnDuplicate(): Unit = runBlocking {
        val token1 = TokenEntity(
            tokenHash = "duplicate_hash_val",
            tokenType = TokenType.NFC,
            label = "Tag 1",
            createdAt = 1000L
        )
        val token2 = TokenEntity(
            tokenHash = "duplicate_hash_val",
            tokenType = TokenType.QR,
            label = "Tag 2",
            createdAt = 2000L
        )

        tokenDao.insertToken(token1)
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                tokenDao.insertToken(token2)
            }
        }
    }

    // =========================================================================
    // ModeDao CRUD & TypeConverter Tests
    // =========================================================================

    @Test
    fun modeDao_insertAndGetById_withPackageSetConverter() = runBlocking {
        val packages = setOf("com.instagram.android", "com.twitter.android", "com.reddit.frontpage")
        val mode = ModeEntity(
            name = "Deep Work",
            modeType = ModeType.BLOCK_LIST,
            packageNames = packages,
            isDefault = true,
            createdAt = 5000L
        )

        val id = modeDao.insertMode(mode)
        assertTrue(id > 0)

        val retrieved = modeDao.getModeById(id)
        assertNotNull(retrieved)
        assertEquals("Deep Work", retrieved?.name)
        assertEquals(ModeType.BLOCK_LIST, retrieved?.modeType)
        assertTrue(retrieved?.isDefault == true)
        assertEquals(packages, retrieved?.packageNames)
        assertTrue(retrieved?.packageNames?.contains("com.instagram.android") == true)
    }

    @Test
    fun modeDao_getDefaultMode() = runBlocking {
        val defaultMode = ModeEntity(
            name = "Standard Block",
            modeType = ModeType.BLOCK_LIST,
            packageNames = setOf("com.game.app"),
            isDefault = true,
            createdAt = 1000L
        )
        val customMode = ModeEntity(
            name = "Study",
            modeType = ModeType.ALLOW_LIST,
            packageNames = setOf("com.book.reader"),
            isDefault = false,
            createdAt = 2000L
        )

        modeDao.insertMode(customMode)
        modeDao.insertMode(defaultMode)

        val retrievedDefault = modeDao.getDefaultMode()
        assertNotNull(retrievedDefault)
        assertEquals("Standard Block", retrievedDefault?.name)
        assertTrue(retrievedDefault?.isDefault == true)
    }

    @Test
    fun modeDao_updateMode() = runBlocking {
        val mode = ModeEntity(
            name = "Old Name",
            modeType = ModeType.BLOCK_LIST,
            packageNames = setOf("com.app.one"),
            createdAt = 1000L
        )
        val id = modeDao.insertMode(mode)

        val updatedMode = mode.copy(
            id = id,
            name = "Updated Focus",
            packageNames = setOf("com.app.one", "com.app.two")
        )
        modeDao.updateMode(updatedMode)

        val retrieved = modeDao.getModeById(id)
        assertEquals("Updated Focus", retrieved?.name)
        assertEquals(2, retrieved?.packageNames?.size)
        assertTrue(retrieved?.packageNames?.contains("com.app.two") == true)
    }

    @Test
    fun modeDao_deleteMode() = runBlocking {
        val mode = ModeEntity(
            name = "Temporary Mode",
            modeType = ModeType.BLOCK_LIST,
            packageNames = emptySet(),
            createdAt = 1000L
        )
        val id = modeDao.insertMode(mode)
        assertNotNull(modeDao.getModeById(id))

        modeDao.deleteModeById(id)
        assertNull(modeDao.getModeById(id))
    }

    // =========================================================================
    // ScheduleDao CRUD & Foreign Key Cascade Tests
    // =========================================================================

    @Test
    fun scheduleDao_insertAndQueryByMode() = runBlocking {
        val mode = ModeEntity(
            name = "Work Hours",
            modeType = ModeType.BLOCK_LIST,
            packageNames = setOf("com.distraction.app"),
            createdAt = 1000L
        )
        val modeId = modeDao.insertMode(mode)

        val schedule = ScheduleEntity(
            name = "Weekday Mornings",
            modeId = modeId,
            daysOfWeek = 0b0011111, // Mon-Fri
            startHour = 9,
            startMinute = 0,
            isEnabled = true,
            createdAt = 2000L
        )
        val scheduleId = scheduleDao.insertSchedule(schedule)
        assertTrue(scheduleId > 0)

        val retrieved = scheduleDao.getScheduleById(scheduleId)
        assertNotNull(retrieved)
        assertEquals("Weekday Mornings", retrieved?.name)
        assertEquals(modeId, retrieved?.modeId)
        assertEquals(9, retrieved?.startHour)
        assertEquals(0, retrieved?.startMinute)
        assertTrue(retrieved?.isEnabled == true)

        val modeSchedules = scheduleDao.getSchedulesForMode(modeId)
        assertEquals(1, modeSchedules.size)
    }

    @Test
    fun scheduleDao_foreignKeyCascade_deletingModeDeletesAssociatedSchedule() = runBlocking {
        val modeId = modeDao.insertMode(
            ModeEntity(
                name = "Parent Mode",
                modeType = ModeType.BLOCK_LIST,
                packageNames = emptySet(),
                createdAt = 1000L
            )
        )

        val scheduleId = scheduleDao.insertSchedule(
            ScheduleEntity(
                name = "Child Schedule",
                modeId = modeId,
                daysOfWeek = 0b0000001,
                startHour = 20,
                startMinute = 0,
                isEnabled = true,
                createdAt = 2000L
            )
        )
        assertNotNull(scheduleDao.getScheduleById(scheduleId))

        // Delete parent mode
        modeDao.deleteModeById(modeId)

        // Foreign Key Cascade should delete child schedule
        assertNull(
            "Cascade delete must automatically remove child schedule when parent mode is deleted",
            scheduleDao.getScheduleById(scheduleId)
        )
    }

    // =========================================================================
    // SessionDao CRUD & Aggregate Stats Tests
    // =========================================================================

    @Test
    fun sessionDao_insertActiveSession_and_queryActive() = runBlocking {
        val session = BrickSessionEntity(
            startTimeMs = 10_000L,
            modeId = 1L,
            modeName = "Deep Focus",
            modeType = ModeType.BLOCK_LIST,
            strictModeActive = true
        )
        val id = sessionDao.insertSession(session)
        assertTrue(id > 0)

        val active = sessionDao.getActiveSession()
        assertNotNull("Active session must be retrieved", active)
        assertEquals("Deep Focus", active?.modeName)
        assertNull("Active session endTimeMs must be null", active?.endTimeMs)
        assertTrue(active?.strictModeActive == true)

        val activeFromFlow = sessionDao.getActiveSessionFlow().first()
        assertNotNull(activeFromFlow)
        assertEquals(id, activeFromFlow?.id)
    }

    @Test
    fun sessionDao_completeSession_and_updateStats() = runBlocking {
        val session = BrickSessionEntity(
            startTimeMs = 10_000L,
            modeId = 1L,
            modeName = "Bedtime",
            modeType = ModeType.ALLOW_LIST
        )
        val id = sessionDao.insertSession(session)

        // Complete session
        val completed = session.copy(
            id = id,
            endTimeMs = 15_000L,
            durationMs = 5_000L,
            unbrickTrigger = UnbrickTrigger.NFC_TAG,
            blockedAttemptsCount = 3
        )
        sessionDao.updateSession(completed)

        assertNull("Once completed, getActiveSession must return null", sessionDao.getActiveSession())

        val completedList = sessionDao.getCompletedSessions()
        assertEquals(1, completedList.size)
        assertEquals(5_000L, completedList[0].durationMs)
        assertEquals(UnbrickTrigger.NFC_TAG, completedList[0].unbrickTrigger)
        assertEquals(3, completedList[0].blockedAttemptsCount)
    }

    @Test
    fun sessionDao_totalBrickedTimeMs_aggregatesCorrectly() = runBlocking {
        // Session 1: 15 minutes (900,000 ms)
        sessionDao.insertSession(
            BrickSessionEntity(
                startTimeMs = 1_000_000L,
                endTimeMs = 1_900_000L,
                durationMs = 900_000L,
                modeId = 1L,
                modeName = "Work",
                modeType = ModeType.BLOCK_LIST
            )
        )

        // Session 2: 30 minutes (1,800,000 ms)
        sessionDao.insertSession(
            BrickSessionEntity(
                startTimeMs = 2_000_000L,
                endTimeMs = 3_800_000L,
                durationMs = 1_800_000L,
                modeId = 1L,
                modeName = "Work",
                modeType = ModeType.BLOCK_LIST
            )
        )

        val totalTime = sessionDao.getTotalBrickedTimeMs()
        assertEquals("Total bricked time must equal sum of completed durations", 2_700_000L, totalTime)
    }

    @Test
    fun sessionDao_incrementBlockedAttempts() = runBlocking {
        val session = BrickSessionEntity(
            startTimeMs = 10_000L,
            modeId = 1L,
            modeName = "Deep Focus",
            modeType = ModeType.BLOCK_LIST,
            blockedAttemptsCount = 0
        )
        val id = sessionDao.insertSession(session)

        sessionDao.incrementBlockedAttempts(id)
        sessionDao.incrementBlockedAttempts(id)

        val updated = sessionDao.getSessionById(id)
        assertEquals(2, updated?.blockedAttemptsCount)
    }

    // =========================================================================
    // Converters & DatabaseProvider Tests
    // =========================================================================

    @Test
    fun converters_stringSetRoundTrip() {
        val converters = Converters()
        val original = setOf("pkg.a", "pkg.b", "pkg.c")
        val json = converters.fromStringSet(original)
        val deserialized = converters.toStringSet(json)
        assertEquals(original, deserialized)

        assertEquals("[]", converters.fromStringSet(emptySet()))
        assertEquals(emptySet<String>(), converters.toStringSet("[]"))
        assertEquals(emptySet<String>(), converters.toStringSet(null))
        assertEquals(emptySet<String>(), converters.toStringSet(""))
    }

    @Test
    fun databaseProvider_singletonAndTestHook() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaultDb = DatabaseProvider.getDatabase(context)
        assertNotNull(defaultDb)

        val inMemory = BrickDatabase.createInMemory(context)
        DatabaseProvider.setTestDatabase(inMemory)
        assertEquals(inMemory, DatabaseProvider.getDatabase(context))

        DatabaseProvider.setTestDatabase(null)
    }
}
