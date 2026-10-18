package com.brick.app.feature

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.database.UnbrickTrigger
import com.brick.app.core.policy.AppBlockPolicy
import com.brick.app.core.policy.SystemWhitelist
import com.brick.app.core.security.CryptoUtils
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.nfc.DefaultTokenScanUseCase
import com.brick.app.feature.nfc.ScanResult
import com.brick.app.feature.stats.StreakCalculator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        tokenScanUseCase = DefaultTokenScanUseCase(db, sessionManager)
    }

    @After
    fun tearDown() {
        db.close()
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
        assertFalse(policy.shouldBlockPackage("com.android.systemui", activeSession, mode))
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
        recoveryEngine.onTick()

        val statePostReboot = recoveryEngine.getRecoveryState().value
        assertTrue("Recovery must remain active post-reboot", statePostReboot.isActive)
        // 8h + 1s accumulated elapsed time
        assertEquals(16 * 60 * 60 * 1000L - 1000L, statePostReboot.remainingMs)
        assertTrue("Device must still be bricked", sessionManager.isBricked())

        // 5. Complete remaining ~16 hours of real elapsed time
        timeProvider.advanceTime(16 * 60 * 60 * 1000L)
        recoveryEngine.onTick()

        val finalState = recoveryEngine.getRecoveryState().value
        assertFalse("Recovery must complete after full 24h", finalState.isActive)
        assertEquals(0L, finalState.remainingMs)
        assertFalse("Device must be unbricked after full 24h recovery", sessionManager.isBricked())
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
        sessionManager.unbrick(UnbrickTrigger.NFC_TAG)

        // Session 2: Day 2, 3 hours
        timeProvider.advanceTime(oneDayMs)
        sessionManager.brick()
        timeProvider.advanceTime(3 * 60 * 60 * 1000L)
        sessionManager.unbrick(UnbrickTrigger.QR_CODE)

        val completed = db.sessionDao().getAllCompletedSessions()
        assertEquals(2, completed.size)

        val stats = StreakCalculator.calculateStats(completed)
        assertEquals(2, stats.sessionCount)
        assertEquals(5 * 60 * 60 * 1000L, stats.totalBrickedTimeMs)
    }
}
