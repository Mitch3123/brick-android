package com.brick.app.feature

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.database.UnbrickTrigger
import com.brick.app.core.policy.AppBlockPolicy
import com.brick.app.core.security.CryptoUtils
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.nfc.DefaultTokenScanUseCase
import com.brick.app.feature.nfc.ScanResult
import com.brick.app.feature.schedules.ScheduleAlarmReceiver
import com.brick.app.feature.schedules.ScheduleManager
import com.brick.app.feature.stats.StreakCalculator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * End-to-end unit test suite running on Robolectric JVM verifying all acceptance criteria:
 * - Token pairing, NFC & QR hashing, unpaired rejection, state toggling
 * - Block-list & allow-list policy interception and system whitelisting
 * - Process survival and recovery cooldown with reboot resilience & anti-wallclock tampering
 * - Streak calculation and history aggregation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FullBrickFeatureSuiteTest {

    private lateinit var context: Context
    private lateinit var db: BrickDatabase
    private lateinit var timeProvider: TestTimeProvider
    private lateinit var sessionManager: SessionManager
    private lateinit var tokenScanUseCase: DefaultTokenScanUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BrickDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeProvider = TestTimeProvider(
            initialCurrentTimeMillis = 1_700_000_000_000L,
            initialElapsedRealtime = 10_000L
        )
        TestTimeProvider.setInstance(timeProvider)

        sessionManager = SessionManager.createWithDatabase(context, timeProvider, db)
        // A fresh device must pair a recovery token before it can start a session.
        runBlocking { db.tokenDao().insert(TokenEntity(
            tokenHash = CryptoUtils.sha256("FIXTURE_RECOVERY_TOKEN"),
            tokenType = TokenType.NFC, label = "Test recovery token"
        )) }
        tokenScanUseCase = DefaultTokenScanUseCase(db, sessionManager)
    }

    @After
    fun tearDown() {
        db.close()
        context.getSharedPreferences("brick_recovery_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TestTimeProvider.setInstance(null)
    }

    // =========================================================================
    // 1. Token Pairing & Brick/Unbrick State Machine (R2 / AC-3 / AC-6)
    // =========================================================================

    @Test
    fun tokenScan_unpairedToken_isStrictlyRejectedAndDoesNotChangeState() = runBlocking {
        assertFalse(sessionManager.isBricked())

        val rogueNfcPayload = "ROGUE_UNPAIRED_UID_999"
        val result = tokenScanUseCase.handleTokenScanned(rogueNfcPayload, TokenType.NFC)

        assertEquals(ScanResult.UnpairedTokenRejected, result)
        assertFalse("Device must not be bricked by an unpaired token", sessionManager.isBricked())
    }

    @Test
    fun tokenScan_wrongMediumForPairedPayload_isRejected() = runBlocking {
        val payload = "paired-qr-payload"
        db.tokenDao().insert(TokenEntity(
            tokenHash = CryptoUtils.sha256(payload), tokenType = TokenType.QR, label = "Printed QR"
        ))
        assertEquals(ScanResult.UnpairedTokenRejected, tokenScanUseCase.handleTokenScanned(payload, TokenType.NFC))
        assertFalse(sessionManager.isBricked())
        assertTrue(tokenScanUseCase.handleTokenScanned(payload, TokenType.QR) is ScanResult.BrickedSuccessfully)
        assertEquals(ScanResult.UnpairedTokenRejected, tokenScanUseCase.handleTokenScanned(payload, TokenType.NFC))
        assertTrue(sessionManager.isBricked())
    }

    @Test
    fun tokenScan_pairedNfcAndQrTokens_toggleBrickAndUnbrick() = runBlocking {
        // 1. Pair primary NFC token
        val primaryNfcPayload = "NFC_PRIMARY_UID_4A8B"
        val primaryHash = CryptoUtils.sha256(primaryNfcPayload)
        db.tokenDao().insert(
            TokenEntity(
                tokenHash = primaryHash,
                tokenType = TokenType.NFC,
                label = "Primary Card",
                isPrimary = true
            )
        )

        // 2. Pair spare NFC token
        val spareNfcPayload = "NFC_SPARE_UID_7C2D"
        val spareHash = CryptoUtils.sha256(spareNfcPayload)
        db.tokenDao().insert(
            TokenEntity(
                tokenHash = spareHash,
                tokenType = TokenType.NFC,
                label = "Spare Keychain",
                isPrimary = false
            )
        )

        // 3. Pair printed QR code token
        val qrPayload = "BRICK-QR-V1:PRINTED_DESK_CARD_123"
        val qrHash = CryptoUtils.sha256(qrPayload)
        db.tokenDao().insert(
            TokenEntity(
                tokenHash = qrHash,
                tokenType = TokenType.QR,
                label = "Desk QR Card",
                isPrimary = false
            )
        )

        // Step A: Tap primary NFC token -> Bricks
        val brickResult = tokenScanUseCase.handleTokenScanned(primaryNfcPayload, TokenType.NFC)
        assertTrue("Expected BrickedSuccessfully", brickResult is ScanResult.BrickedSuccessfully)
        assertTrue(sessionManager.isBricked())

        // Step B: Unpaired rogue token scanned while bricked -> Rejected, remains bricked
        val rogueResult = tokenScanUseCase.handleTokenScanned("ROGUE_ATTACKER_TAG", TokenType.NFC)
        assertEquals(ScanResult.UnpairedTokenRejected, rogueResult)
        assertTrue("Device must remain bricked after rogue scan", sessionManager.isBricked())

        // Step C: Tap spare NFC token -> Unbricks
        val unbrickResult = tokenScanUseCase.handleTokenScanned(spareNfcPayload, TokenType.NFC)
        assertTrue("Expected UnbrickedSuccessfully", unbrickResult is ScanResult.UnbrickedSuccessfully)
        assertFalse(sessionManager.isBricked())

        // Step D: Scan printed QR code token -> Bricks again
        val qrBrickResult = tokenScanUseCase.handleTokenScanned(qrPayload, TokenType.QR)
        assertTrue("Expected BrickedSuccessfully via QR", qrBrickResult is ScanResult.BrickedSuccessfully)
        assertTrue(sessionManager.isBricked())

        // Step E: Scan printed QR code token again -> Unbricks
        val qrUnbrickResult = tokenScanUseCase.handleTokenScanned(qrPayload, TokenType.QR)
        assertTrue("Expected UnbrickedSuccessfully via QR", qrUnbrickResult is ScanResult.UnbrickedSuccessfully)
        assertFalse(sessionManager.isBricked())
    }

    @Test
    fun sessionManager_refusesToBrickWithoutPairedTokenOrWithEmptyBlockList() = runBlocking {
        db.tokenDao().deleteAll()
        try {
            sessionManager.brick()
            org.junit.Assert.fail("Session without a paired recovery token could trap the user")
        } catch (_: IllegalStateException) { }
        assertFalse(sessionManager.isBricked())

        db.tokenDao().insert(TokenEntity(tokenHash = CryptoUtils.sha256("recovery"),
            tokenType = TokenType.NFC, label = "Recovery"))
        val emptyModeId = db.modeDao().insert(ModeEntity(name = "Empty", modeType = ModeType.BLOCK_LIST,
            packageNames = emptySet(), isDefault = true))
        try {
            sessionManager.brick(modeId = emptyModeId)
            org.junit.Assert.fail("An empty block list must not create a no-op focus session")
        } catch (_: IllegalStateException) { }
        assertFalse(sessionManager.isBricked())
    }

    @Test
    fun sessionManager_rejectsUnpairedOrForgedTokenAndRepeatBrick() = runBlocking {
        val payload = "physical-token"
        val id = db.tokenDao().insert(TokenEntity(
            tokenHash = CryptoUtils.sha256(payload), tokenType = TokenType.NFC, label = "Physical"
        ))
        sessionManager.brick()
        val originalSession = sessionManager.activeSession.value?.id
        try {
            sessionManager.brick()
            org.junit.Assert.fail("Repeated brick must not close the active session")
        } catch (_: IllegalStateException) { }
        assertEquals(originalSession, sessionManager.activeSession.value?.id)
        for (token in listOf(
            null,
            TokenEntity(id = id, tokenHash = "wrong", tokenType = TokenType.NFC, label = "Forged"),
            TokenEntity(id = id + 1, tokenHash = CryptoUtils.sha256(payload), tokenType = TokenType.NFC, label = "Forged"),
            TokenEntity(id = id, tokenHash = CryptoUtils.sha256(payload), tokenType = TokenType.QR, label = "Wrong medium")
        )) {
            try {
                sessionManager.unbrick(UnbrickTrigger.NFC_TAG, token)
                org.junit.Assert.fail("Unpaired or forged token unlocked device")
            } catch (_: IllegalArgumentException) { }
            assertTrue(sessionManager.isBricked())
        }
        try {
            sessionManager.unbrick(UnbrickTrigger.RECOVERY_24H)
            org.junit.Assert.fail("Recovery unlocked before 24 hours")
        } catch (_: IllegalStateException) { }
        assertTrue(sessionManager.isBricked())
        sessionManager.unbrick(UnbrickTrigger.NFC_TAG,
            TokenEntity(id = id, tokenHash = CryptoUtils.sha256(payload), tokenType = TokenType.NFC, label = "Physical"))
        assertFalse(sessionManager.isBricked())
    }

    // =========================================================================
    // 2. Block Policy: Block-List & Allow-List Interception (R3 / AC-4 / AC-5)
    // =========================================================================

    @Test
    fun blockPolicy_blockListMode_correctlyIntersectsTargetPackages() = runBlocking {
        val policy = AppBlockPolicy(launcherPackages = setOf("com.android.launcher3"))

        val mode = ModeEntity(
            name = "Work Focus",
            modeType = ModeType.BLOCK_LIST,
            packageNames = setOf("com.android.calculator2", "com.instagram.android")
        )

        // Case 1: Unbricked -> never block
        assertFalse(policy.shouldBlockPackage("com.android.calculator2", null, mode))

        // Case 2: Bricked
        sessionManager.brick()
        val activeSession = sessionManager.activeSession.value

        // Blocked app -> should block
        assertTrue(policy.shouldBlockPackage("com.android.calculator2", activeSession, mode))
        assertTrue(policy.shouldBlockPackage("com.instagram.android", activeSession, mode))

        // Non-blocked app -> allowed
        assertFalse(policy.shouldBlockPackage("com.android.dialer", activeSession, mode))

        // System critical (whitelist) -> never block even if configured
        assertFalse(policy.shouldBlockPackage("com.brick.app", activeSession, mode))
        assertFalse(policy.shouldBlockPackage("com.android.phone", activeSession, mode))

        // Launcher -> never block
        assertFalse(policy.shouldBlockPackage("com.android.launcher3", activeSession, mode))
    }

    @Test
    fun blockPolicy_allowListMode_blocksEverythingExceptAllowed() = runBlocking {
        val policy = AppBlockPolicy(launcherPackages = setOf("com.android.launcher3"))

        val mode = ModeEntity(
            name = "Strict Study",
            modeType = ModeType.ALLOW_LIST,
            packageNames = setOf("com.google.android.apps.docs", "com.android.dialer")
        )

        sessionManager.brick()
        val activeSession = sessionManager.activeSession.value

        // Whitelisted in mode -> allowed
        assertFalse(policy.shouldBlockPackage("com.google.android.apps.docs", activeSession, mode))
        assertFalse(policy.shouldBlockPackage("com.android.dialer", activeSession, mode))

        // Non-whitelisted app (e.g. calculator or calendar) -> BLOCKED
        assertTrue(policy.shouldBlockPackage("com.android.calculator2", activeSession, mode))
        assertTrue(policy.shouldBlockPackage("com.android.calendar", activeSession, mode))

        // System whitelist essentials -> ALWAYS allowed
        assertFalse(policy.shouldBlockPackage("com.brick.app", activeSession, mode))
        assertFalse(policy.shouldBlockPackage(com.brick.app.BuildConfig.APPLICATION_ID, activeSession, mode))
        assertFalse(policy.shouldBlockPackage("com.android.systemui", activeSession, mode))
    }

    @Test
    fun sessionManager_refreshesIdleModeButPinsModeAndStrictFlagDuringSession() = runBlocking {
        val firstId = db.modeDao().insert(ModeEntity(
            name = "First", modeType = ModeType.BLOCK_LIST,
            packageNames = setOf("com.android.calculator2"), isDefault = true
        ))
        sessionManager.refreshSession()
        assertEquals(firstId, sessionManager.activeMode.value?.id)

        val secondId = db.modeDao().insert(ModeEntity(
            name = "Second", modeType = ModeType.ALLOW_LIST,
            packageNames = setOf("com.android.calendar")
        ))
        db.modeDao().setDefaultMode(secondId)
        sessionManager.refreshSession()
        assertEquals("Idle mode must follow selected default", secondId, sessionManager.activeMode.value?.id)

        sessionManager.brick(strictMode = true)
        assertEquals(secondId, sessionManager.activeSession.value?.modeId)
        assertTrue(sessionManager.isStrictModeActive())
        db.modeDao().setDefaultMode(firstId)
        sessionManager.refreshSession()
        assertEquals("Active mode must remain pinned until unbrick", secondId, sessionManager.activeMode.value?.id)
        val restarted = SessionManager.createWithDatabase(context, timeProvider, db)
        assertTrue(restarted.isBricked())
        assertTrue("Strict flag must survive process recreation", restarted.isStrictModeActive())
        assertEquals(secondId, restarted.activeMode.value?.id)
        assertTrue(tokenScanUseCase.handleTokenScanned("FIXTURE_RECOVERY_TOKEN", TokenType.NFC) is ScanResult.UnbrickedSuccessfully)
        sessionManager.refreshSession()
        assertEquals(firstId, sessionManager.activeMode.value?.id)
    }

    @Test
    fun injectedDatabaseCannotAuthoriseManualUnbrickInNormalDebug() = runBlocking {
        assumeFalse("Normal debug/release builds must reject manual unlock; E2E intentionally permits fixture reset", com.brick.app.BuildConfig.E2E_HOOKS)
        sessionManager.brick()
        try {
            sessionManager.unbrick(UnbrickTrigger.MANUAL_DEBUG)
            throw AssertionError("Manual trigger bypassed paired-token requirement")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("isolated E2E build"))
            assertTrue(sessionManager.isBricked())
            assertNotNull(db.sessionDao().getActiveSession())
        }
        try {
            sessionManager.forceResetUnbrick()
            throw AssertionError("Reset bypassed paired-token requirement")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("isolated E2E build"))
            assertTrue(sessionManager.isBricked())
            assertNotNull(db.sessionDao().getActiveSession())
        }
        assertTrue(tokenScanUseCase.handleTokenScanned("FIXTURE_RECOVERY_TOKEN", TokenType.NFC) is ScanResult.UnbrickedSuccessfully)
    }

    // =========================================================================
    // 3. 24-Hour Recovery: Monotonic Countdown & Mid-Cooldown Reboot (R3 / AC-10)
    // =========================================================================

    @Test
    fun recoveryEngine_24hCountdown_immuneToWallClockAndSurvivesReboot() = runBlocking {
        val recoveryEngine = RecoveryEngine.createWithTimeProvider(context, timeProvider, sessionManager)
        sessionManager.brick()
        assertTrue(sessionManager.isBricked())

        // 1. Start recovery
        recoveryEngine.startRecovery()
        val initialState = recoveryEngine.getRecoveryState().value
        assertTrue(initialState.isActive)
        assertEquals(24 * 60 * 60 * 1000L, initialState.remainingMs)

        // 2. User attempts to cheat: manually changes wall clock forward by 48 hours in Settings
        timeProvider.advanceWallClock(48 * 60 * 60 * 1000L)
        recoveryEngine.onTick()
        val stateAfterClockTamper = recoveryEngine.getRecoveryState().value
        assertTrue("Recovery must NOT be bypassed by wall-clock jump", stateAfterClockTamper.isActive)
        assertEquals("Remaining time must be unchanged by wall-clock jump", 24 * 60 * 60 * 1000L, stateAfterClockTamper.remainingMs)
        assertTrue("Device must remain bricked", sessionManager.isBricked())

        // 3. Advance real monotonic time by 8 hours
        timeProvider.advanceTime(8 * 60 * 60 * 1000L)
        recoveryEngine.onTick()
        assertEquals(16 * 60 * 60 * 1000L, recoveryEngine.getRecoveryState().value.remainingMs)

        // 4. User reboots device mid-cooldown AND jumps wall-clock by another 24 hours
        timeProvider.simulateReboot(postBootElapsedMs = 1_000L, powerOffDurationMs = 24 * 60 * 60 * 1000L)
        // Reconstruct both components from persisted Room/SharedPreferences state to
        // emulate process recreation; the clock itself emulates the separate reboot.
        val restartedSessionManager = SessionManager.createWithDatabase(context, timeProvider, db)
        assertTrue("Active session must hydrate from Room after process recreation", restartedSessionManager.isBricked())
        val restartedEngine = RecoveryEngine.createWithTimeProvider(context, timeProvider, restartedSessionManager)
        restartedEngine.onTick()

        val statePostRestart = restartedEngine.getRecoveryState().value
        assertTrue("Recovery must remain active after restart", statePostRestart.isActive)
        assertEquals(16 * 60 * 60 * 1000L - 1000L, statePostRestart.remainingMs)
        assertTrue("Recreated session manager must still be bricked", restartedSessionManager.isBricked())

        timeProvider.advanceTime(16 * 60 * 60 * 1000L)
        restartedEngine.onTick()

        val finalState = restartedEngine.getRecoveryState().value
        assertFalse("Recovery must complete after full 24h", finalState.isActive)
        assertEquals(0L, finalState.remainingMs)
        assertFalse("Device must be unbricked after full 24h recovery", restartedSessionManager.isBricked())
    }

    @Test
    fun scheduleManager_setsExactAlarmForStoredSchedule() = runBlocking {
        val modeId = db.modeDao().insert(ModeEntity(
            name = "Focus", modeType = ModeType.BLOCK_LIST,
            packageNames = setOf("com.android.calculator2"), isDefault = true
        ))
        val schedule = ScheduleEntity(
            name = "Daily focus", modeId = modeId,
            daysOfWeek = ScheduleEntity.ALL_DAYS, startHour = 12, startMinute = 30
        )
        val id = db.scheduleDao().insert(schedule)
        ScheduleManager(context).scheduleNextAlarm(schedule.copy(id = id))

        val shadow = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val alarm = shadow.nextScheduledAlarm
        assertNotNull("Schedule must register a real AlarmManager alarm", alarm)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm!!.type)
        val expected = android.content.Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleManager.ACTION_SCHEDULE_ALARM
        }
        val pending = android.app.PendingIntent.getBroadcast(
            context, id.toInt(), expected,
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("Exact alarm must target ScheduleAlarmReceiver", pending)
        assertEquals(pending, alarm.operation)
    }

    // =========================================================================
    // 4. Stats & Streak Calculation (R4 / AC-11)
    // =========================================================================

    @Test
    fun streakCalculator_computesTotalTimeAndStreaksCorrectly() = runBlocking {
        val now = 1_700_000_000_000L
        val oneDayMs = 24 * 60 * 60 * 1000L

        // Session 1: Day 1, 2 hours
        sessionManager.brick()
        timeProvider.advanceTime(2 * 60 * 60 * 1000L)
        assertTrue(tokenScanUseCase.handleTokenScanned("FIXTURE_RECOVERY_TOKEN", TokenType.NFC) is ScanResult.UnbrickedSuccessfully)

        // Session 2: Day 2, 3 hours
        timeProvider.advanceTime(oneDayMs)
        sessionManager.brick()
        timeProvider.advanceTime(3 * 60 * 60 * 1000L)
        assertTrue(tokenScanUseCase.handleTokenScanned("FIXTURE_RECOVERY_TOKEN", TokenType.NFC) is ScanResult.UnbrickedSuccessfully)

        val completed = db.sessionDao().getAllCompletedSessions()
        assertEquals(2, completed.size)

        val stats = StreakCalculator.calculateStats(completed)
        assertEquals(2, stats.sessionCount)
        assertEquals(5 * 60 * 60 * 1000L, stats.totalBrickedTimeMs)
        assertEquals(UnbrickTrigger.NFC_TAG, completed[0].unbrickTrigger)
        assertEquals(UnbrickTrigger.NFC_TAG, completed[1].unbrickTrigger)
    }
}
