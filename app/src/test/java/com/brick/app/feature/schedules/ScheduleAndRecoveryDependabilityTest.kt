package com.brick.app.feature.schedules

import android.app.PendingIntent
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.security.BootIdentityProvider
import com.brick.app.core.security.RecoveryAccounting
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.core.security.RecoveryLedger
import com.brick.app.core.security.RecoveryScheduler
import com.brick.app.feature.home.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * Dependability suite for schedules and the 24-hour recovery countdown.
 *
 * Deliberately narrow: it covers the arming/rearming contract and the monotonic ledger, which is
 * where silent failures hide. The occurrence maths and the ledger are exercised as pure
 * functions; the Android surface is reached through the injected [AlarmGateway] and
 * [RecoveryScheduler] seams rather than shadow internals, so every exact / inexact / failed
 * branch is reachable and deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScheduleAndRecoveryDependabilityTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private lateinit var context: Context
    private lateinit var db: BrickDatabase
    private lateinit var timeProvider: TestTimeProvider
    private lateinit var sessionManager: SessionManager

    /** 2026-10-20 is a Tuesday; 10-24 Saturday, 10-26 Monday, 10-27 Tuesday. */
    private val tuesday0900 = utcMillis(2026, 10, 20, 9, 0)
    private val saturday0900 = utcMillis(2026, 10, 24, 9, 0)

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
    }

    @After
    fun tearDown() {
        db.close()
        context.getSharedPreferences("brick_recovery_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("brick_schedule_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        TestTimeProvider.setInstance(null)
    }

    // =========================================================================
    // Weekday bitmask and next-occurrence arithmetic
    // =========================================================================

    @Test
    fun nextOccurrence_skipsDaysOutsideTheBitmask() {
        val next = ScheduleClock.nextOccurrenceMillis(
            daysOfWeek = ScheduleEntity.WEEKDAYS,
            hour = 18,
            minute = 30,
            afterMillis = saturday0900,
            timeZone = utc
        )
        assertEquals("Weekday-only schedule must skip the weekend", utcMillis(2026, 10, 26, 18, 30), next)
    }

    @Test
    fun nextOccurrence_usesTodayWhileTheTimeIsStillAhead() {
        val next = ScheduleClock.nextOccurrenceMillis(
            daysOfWeek = ScheduleEntity.ALL_DAYS,
            hour = 18, minute = 30, afterMillis = tuesday0900, timeZone = utc
        )
        assertEquals(utcMillis(2026, 10, 20, 18, 30), next)
    }

    @Test
    fun nextOccurrence_rollsAFullWeekForASingleDaySchedule() {
        val atTheExactTriggerInstant = utcMillis(2026, 10, 20, 18, 30)
        val next = ScheduleClock.nextOccurrenceMillis(
            daysOfWeek = ScheduleEntity.TUESDAY,
            hour = 18, minute = 30, afterMillis = atTheExactTriggerInstant, timeZone = utc
        )
        assertEquals(
            "An occurrence must be strictly in the future, so the same instant rolls a week",
            utcMillis(2026, 10, 27, 18, 30),
            next
        )
    }

    @Test
    fun nextOccurrence_isNullWhenTheScheduleCanNeverFire() {
        assertNull(ScheduleClock.nextOccurrenceMillis(0, 18, 30, tuesday0900, utc))
        assertNull(ScheduleClock.nextOccurrenceMillis(ScheduleEntity.ALL_DAYS, 24, 0, tuesday0900, utc))
        assertNull(ScheduleClock.nextOccurrenceMillis(ScheduleEntity.ALL_DAYS, 18, 60, tuesday0900, utc))
        assertNull(ScheduleClock.nextOccurrenceMillis(ScheduleEntity.ALL_DAYS, -1, 0, tuesday0900, utc))
    }

    @Test
    fun maskBitsOutsideTheSevenWeekdaysAreIgnored() {
        val junk = ScheduleEntity.MONDAY or (1 shl 20)
        assertEquals(ScheduleEntity.MONDAY, ScheduleClock.normaliseMask(junk))
        assertFalse(ScheduleClock.isDayActive(junk, 2))
        assertTrue(ScheduleClock.isDayActive(junk, 1))
    }

    @Test
    fun previousOccurrence_findsTheOccurrenceMissedWhileDown() {
        val previous = ScheduleClock.previousOccurrenceMillis(
            daysOfWeek = ScheduleEntity.TUESDAY,
            hour = 18, minute = 30,
            atMillis = utcMillis(2026, 10, 20, 18, 35), timeZone = utc
        )
        assertEquals(utcMillis(2026, 10, 20, 18, 30), previous)
    }

    @Test
    fun isoDayOfWeek_agreesWithTheEntityBitmask() {
        val expected = listOf(1, 2, 3, 4, 5, 6, 7) // Monday 2026-10-26 .. Sunday 2026-11-01
        expected.forEachIndexed { index, isoDay ->
            val calendar = Calendar.getInstance(utc).apply { timeInMillis = utcMillis(2026, 10, 26 + index, 12, 0) }
            assertEquals("day offset $index", isoDay, ScheduleClock.isoDayOfWeek(calendar))

            val onlyThatDay = ScheduleEntity(
                name = "single", modeId = 1L, daysOfWeek = 1 shl (isoDay - 1), startHour = 1, startMinute = 0
            )
            assertTrue(onlyThatDay.isDayActive(isoDay))
            assertTrue(ScheduleClock.isDayActive(onlyThatDay.daysOfWeek, isoDay))
        }
    }

    @Test
    fun nextOccurrence_keepsLocalWallTimeAcrossADstTransition() {
        // Sydney moves to DST on 2026-10-04, making that day 23 hours long. Adding 24h of
        // milliseconds to the previous occurrence would drift the schedule to 19:30 local.
        val sydney = TimeZone.getTimeZone("Australia/Sydney")
        val saturday1900Local = Calendar.getInstance(sydney).apply {
            clear(); set(2026, Calendar.OCTOBER, 3, 19, 0, 0)
        }.timeInMillis

        val next = ScheduleClock.nextOccurrenceMillis(
            daysOfWeek = ScheduleEntity.ALL_DAYS,
            hour = 18, minute = 30, afterMillis = saturday1900Local, timeZone = sydney
        )

        val local = Calendar.getInstance(sydney).apply { timeInMillis = next!! }
        assertEquals(2026, local.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, local.get(Calendar.MONTH))
        assertEquals(4, local.get(Calendar.DAY_OF_MONTH))
        assertEquals("Local wall time must survive the DST transition", 18, local.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, local.get(Calendar.MINUTE))
    }

    // =========================================================================
    // Arming, rearming and visible exact-alarm failure
    // =========================================================================

    @Test
    fun scheduleNextAlarm_armsAnExactAlarmAtTheNextBitmaskOccurrence() {
        val gateway = FakeAlarmGateway()
        val manager = managerAt(saturday0900, gateway)

        val result = manager.scheduleNextAlarm(weekdaySchedule())

        assertEquals(listOf(utcMillis(2026, 10, 26, 18, 30)), gateway.exact)
        assertTrue(gateway.inexact.isEmpty())
        val armed = result as ArmResult.Armed
        assertTrue(armed.exact)
        assertEquals(utcMillis(2026, 10, 26, 18, 30), armed.triggerAtMillis)
        assertEquals(ExactAlarmStatus.EXACT, ScheduleManager.exactAlarmStatus.value)
    }

    @Test
    fun scheduleNextAlarm_fallsBackToInexactAndReportsTheDegradation() {
        val gateway = FakeAlarmGateway(exactPermitted = false)
        val manager = managerAt(saturday0900, gateway)

        val result = manager.scheduleNextAlarm(weekdaySchedule())

        assertTrue("Schedule must still be armed, never silently dropped", gateway.exact.isEmpty())
        assertEquals(listOf(utcMillis(2026, 10, 26, 18, 30)), gateway.inexact)
        assertFalse((result as ArmResult.Armed).exact)
        assertEquals(ExactAlarmStatus.INEXACT_FALLBACK, ScheduleManager.exactAlarmStatus.value)
        assertEquals(
            "Degradation must survive process death for the UI banner",
            ExactAlarmStatus.INEXACT_FALLBACK,
            ScheduleManager.lastExactAlarmStatus(context)
        )
        assertFalse(manager.canScheduleExactAlarms())
    }

    @Test
    fun scheduleNextAlarm_surfacesAHardFailureInsteadOfSwallowingIt() {
        val gateway = FakeAlarmGateway(exactPermitted = true, exactAccepted = false, inexactThrows = true)
        val manager = managerAt(saturday0900, gateway)

        val result = manager.scheduleNextAlarm(weekdaySchedule())

        assertTrue(result is ArmResult.Failed)
        assertEquals(ExactAlarmStatus.FAILED, ScheduleManager.exactAlarmStatus.value)
        assertEquals(ExactAlarmStatus.FAILED, ScheduleManager.lastExactAlarmStatus(context))
    }

    @Test
    fun scheduleNextAlarm_cancelsAndSkipsADisabledSchedule() {
        val gateway = FakeAlarmGateway()
        val manager = managerAt(saturday0900, gateway)
        val schedule = weekdaySchedule()

        manager.scheduleNextAlarm(schedule)
        val result = manager.scheduleNextAlarm(schedule.copy(isEnabled = false))

        assertEquals(ArmResult.Skipped(schedule.id, SkipReason.DISABLED), result)
        assertEquals("Disabling must cancel the armed alarm", 1, gateway.cancelled.size)
        assertEquals("No further alarm may be armed for a disabled schedule", 1, gateway.exact.size)
        assertNull(manager.nextTriggerMillis(schedule.copy(isEnabled = false)))
    }

    @Test
    fun scheduleNextAlarm_neverArmsAScheduleWithNoActiveDay() {
        val gateway = FakeAlarmGateway()
        val manager = managerAt(saturday0900, gateway)

        val result = manager.scheduleNextAlarm(weekdaySchedule().copy(daysOfWeek = 0))

        assertEquals(SkipReason.NO_ACTIVE_DAYS, (result as ArmResult.Skipped).reason)
        assertTrue(gateway.exact.isEmpty())
        assertTrue(gateway.inexact.isEmpty())
    }

    @Test
    fun rearmAfterFire_armsTheFollowingOccurrenceAndCannotRefireTheSameInstant() {
        val gateway = FakeAlarmGateway()
        val firedAt = utcMillis(2026, 10, 26, 18, 30)
        // A delivered alarm can arrive a touch early; the rearm must not land back on firedAt.
        val manager = managerAt(firedAt - 5L, gateway)

        val result = manager.rearmAfterFire(weekdaySchedule(), firedAt)

        assertEquals(utcMillis(2026, 10, 27, 18, 30), (result as ArmResult.Armed).triggerAtMillis)
        assertEquals(listOf(utcMillis(2026, 10, 27, 18, 30)), gateway.exact)
    }

    @Test
    fun rearmAfterFire_skipsTheWeekendForAWeekdaySchedule() {
        val gateway = FakeAlarmGateway()
        val firedFriday = utcMillis(2026, 10, 30, 18, 30) // Friday
        val manager = managerAt(firedFriday, gateway)

        val result = manager.rearmAfterFire(weekdaySchedule(), firedFriday)

        assertEquals(utcMillis(2026, 11, 2, 18, 30), (result as ArmResult.Armed).triggerAtMillis)
    }

    @Test
    fun rescheduleAll_armsEnabledSchedulesOnly() {
        val gateway = FakeAlarmGateway()
        val manager = managerAt(saturday0900, gateway)
        val enabled = weekdaySchedule()
        val disabled = weekdaySchedule().copy(id = 2L, isEnabled = false)
        val impossible = weekdaySchedule().copy(id = 3L, daysOfWeek = 0)

        val results = manager.rescheduleAll(listOf(enabled, disabled, impossible))

        assertEquals(1, results.count { it is ArmResult.Armed })
        assertEquals(2, results.count { it is ArmResult.Skipped })
        assertEquals(1, gateway.exact.size)
    }

    @Test
    fun missedOccurrences_onlyReportsOccurrencesInsideTheCatchUpWindow() {
        val gateway = FakeAlarmGateway()
        val justMissedAt = utcMillis(2026, 10, 26, 18, 30) + 60_000L
        val longPastAt = utcMillis(2026, 10, 26, 18, 30) + ScheduleManager.CATCH_UP_WINDOW_MS + 60_000L
        val schedule = weekdaySchedule()

        assertEquals(
            listOf(schedule),
            managerAt(justMissedAt, gateway).missedOccurrences(listOf(schedule))
        )
        assertTrue(managerAt(longPastAt, gateway).missedOccurrences(listOf(schedule)).isEmpty())
        assertTrue(
            "A disabled schedule is never caught up",
            managerAt(justMissedAt, gateway).missedOccurrences(listOf(schedule.copy(isEnabled = false))).isEmpty()
        )
    }

    // =========================================================================
    // Monotonic recovery accounting (pure)
    // =========================================================================

    @Test
    fun accounting_creditsOnlyTheElapsedDeltaWithinOneBoot() {
        val ledger = RecoveryLedger(cumulativeElapsedMs = 1_000L, anchorElapsedMs = 5_000L, bootToken = "boot-a")

        val advanced = RecoveryAccounting.advance(
            ledger = ledger, currentElapsedMs = 9_000L, currentBootToken = "boot-a", bootEvidence = false
        )

        assertEquals(5_000L, advanced.cumulativeElapsedMs)
        assertEquals(9_000L, advanced.anchorElapsedMs)
    }

    @Test
    fun accounting_rebootCreditsUptimeOnlyAndNeverThePowerOffInterval() {
        val servedEightHours = RecoveryLedger(8 * HOUR_MS, 8 * HOUR_MS + 10_000L, "boot-a")

        // Device rebooted: monotonic clock restarted, 1s of uptime so far. Whatever happened to
        // the wall clock while it was off is unobservable here by construction.
        val advanced = RecoveryAccounting.advance(
            ledger = servedEightHours, currentElapsedMs = 1_000L, currentBootToken = "boot-b", bootEvidence = false
        )

        assertEquals(8 * HOUR_MS + 1_000L, advanced.cumulativeElapsedMs)
        assertTrue("Progress must not be lost", advanced.cumulativeElapsedMs > 8 * HOUR_MS)
        assertEquals("boot-b", advanced.bootToken)
    }

    @Test
    fun accounting_newKernelBootTokenIsANewEpochEvenWithoutAClockRegression() {
        // Long uptime on a *new* boot: without boot identity the delta would be credited, which
        // is only correct inside one boot.
        val ledger = RecoveryLedger(HOUR_MS, 10_000L, "boot-a")

        val advanced = RecoveryAccounting.advance(
            ledger = ledger, currentElapsedMs = 20 * HOUR_MS, currentBootToken = "boot-b", bootEvidence = false
        )

        assertTrue(RecoveryAccounting.isNewBootEpoch(ledger, 20 * HOUR_MS, "boot-b", false))
        assertEquals(HOUR_MS + 20 * HOUR_MS, advanced.cumulativeElapsedMs)
    }

    @Test
    fun accounting_verifiedBootEvidenceIsHonouredWhenNoKernelTokenIsReadable() {
        // Contract of the pure rule. Production always passes false: no broadcast is trusted
        // enough to supply this (see recovery_aForgedBootSignalCannotCreditUnservedUptime).
        val ledger = RecoveryLedger(HOUR_MS, 10_000L, BootIdentityProvider.NO_IDENTITY)

        assertTrue(
            RecoveryAccounting.isNewBootEpoch(ledger, 60_000L, BootIdentityProvider.NO_IDENTITY, bootEvidence = true)
        )
        val advanced = RecoveryAccounting.advance(
            ledger = ledger, currentElapsedMs = 60_000L,
            currentBootToken = BootIdentityProvider.NO_IDENTITY, bootEvidence = true
        )
        assertEquals(HOUR_MS + 60_000L, advanced.cumulativeElapsedMs)
    }

    @Test
    fun accounting_anUnknownTokenAloneIsNeverTreatedAsAReboot() {
        val ledger = RecoveryLedger(HOUR_MS, 10_000L, "boot-a")

        // Token unreadable this time round: must fall back to the delta, keep the known identity,
        // and not invent a boot (which would credit the whole uptime).
        val advanced = RecoveryAccounting.advance(
            ledger = ledger, currentElapsedMs = 20 * HOUR_MS,
            currentBootToken = BootIdentityProvider.NO_IDENTITY, bootEvidence = false
        )

        assertEquals(HOUR_MS + (20 * HOUR_MS - 10_000L), advanced.cumulativeElapsedMs)
        assertEquals("boot-a", advanced.bootToken)
    }

    @Test
    fun accounting_neverCreditsNegativeTime() {
        val ledger = RecoveryLedger(HOUR_MS, 10_000L, "boot-a")

        val advanced = RecoveryAccounting.advance(
            ledger = ledger, currentElapsedMs = 0L, currentBootToken = "boot-a", bootEvidence = false
        )

        assertEquals(HOUR_MS, advanced.cumulativeElapsedMs)
        assertEquals(0L, advanced.anchorElapsedMs)
    }

    // =========================================================================
    // Recovery engine: wall-clock immunity, reboot survival, self-ticking
    // =========================================================================

    @Test
    fun recovery_ignoresWallClockJumpsAndCompletesOnRealElapsedTime() = runBlocking {
        val scheduler = FakeRecoveryScheduler()
        val engine = engineWith(scheduler)
        brickSession()

        engine.startRecovery()
        assertEquals(RecoveryEngine.COOLDOWN_DURATION_MS, engine.remainingMs())
        assertFalse("Proof must not exist before the time is served", engine.hasCompletedRecovery())

        timeProvider.advanceWallClock(72 * HOUR_MS)
        engine.onTick()
        assertEquals("Wall-clock jumps must credit nothing", RecoveryEngine.COOLDOWN_DURATION_MS, engine.remainingMs())
        assertTrue(sessionManager.isBricked())

        timeProvider.advanceElapsedRealtime(RecoveryEngine.COOLDOWN_DURATION_MS)
        engine.onTick()

        assertFalse(engine.isRecoveryActive())
        assertEquals(0L, engine.recoveryStateFlow.value.remainingMs)
        assertFalse("24h of real elapsed time must unlock the session", sessionManager.isBricked())
    }

    @Test
    fun recovery_survivesARebootMidCooldownWithoutLosingOrGainingTime() = runBlocking {
        val engine = engineWith(FakeRecoveryScheduler())
        brickSession()
        engine.startRecovery()

        timeProvider.advanceTime(8 * HOUR_MS)
        engine.onTick()
        assertEquals(16 * HOUR_MS, engine.remainingMs())

        // Reboot mid-cooldown, powered off for two days with the clock wound forward.
        timeProvider.simulateReboot(postBootElapsedMs = 1_000L, powerOffDurationMs = 48 * HOUR_MS)
        val rebuilt = engineWith(FakeRecoveryScheduler())
        rebuilt.onBootDetected()

        assertTrue(rebuilt.isRecoveryActive())
        assertEquals(
            "Only the new boot's uptime is credited; the power-off interval is not",
            16 * HOUR_MS - 1_000L,
            rebuilt.remainingMs()
        )
        assertTrue(sessionManager.isBricked())

        timeProvider.advanceElapsedRealtime(16 * HOUR_MS)
        rebuilt.onTick()
        assertFalse(rebuilt.isRecoveryActive())
        assertFalse(sessionManager.isBricked())
    }

    @Test
    fun startRecovery_isIdempotentAndPreservesProgress() = runBlocking {
        val engine = engineWith(FakeRecoveryScheduler())
        brickSession()

        engine.startRecovery()
        timeProvider.advanceElapsedRealtime(8 * HOUR_MS)
        engine.onTick()
        assertEquals(16 * HOUR_MS, engine.remainingMs())

        // Home and the blocking overlay both expose the entry point.
        engine.startRecovery()
        engine.startRecovery()

        assertEquals("A second request must not restart the countdown", 16 * HOUR_MS, engine.remainingMs())
    }

    @Test
    fun recovery_armsPeriodicTicksCappedByTheRemainingTime() = runBlocking {
        val scheduler = FakeRecoveryScheduler()
        val engine = engineWith(scheduler)
        brickSession()

        engine.startRecovery()
        assertEquals(
            "A long countdown ticks at the fixed interval",
            RecoveryEngine.TICK_INTERVAL_MS,
            scheduler.delays.last()
        )

        timeProvider.advanceElapsedRealtime(RecoveryEngine.COOLDOWN_DURATION_MS - 60_000L)
        engine.onTick()
        assertEquals("The last tick must not overshoot completion", 60_000L, scheduler.delays.last())

        timeProvider.advanceElapsedRealtime(60_000L)
        engine.onTick()
        assertFalse(sessionManager.isBricked())
        assertTrue("Completion must stop the tick chain", scheduler.cancelCount >= 1)
    }

    @Test
    fun cancelRecovery_clearsTheCompletionProofAndStopsTicking() = runBlocking {
        val scheduler = FakeRecoveryScheduler()
        val engine = engineWith(scheduler)
        brickSession()

        engine.startRecovery()
        engine.cancelRecovery()

        assertFalse(engine.isRecoveryActive())
        assertFalse(engine.hasCompletedRecovery())
        assertFalse(engine.hasCompletedRecoveryFor(sessionManager.activeSession.value!!.id))
        assertEquals(-1L, engine.guardedSessionId())
        assertEquals(0L, engine.remainingMs())
        assertTrue(scheduler.cancelCount >= 1)
    }

    @Test
    fun completionProof_isBoundToTheSessionItWasStartedAgainst() = runBlocking {
        val engine = engineWith(FakeRecoveryScheduler())
        brickSession()
        val sessionId = sessionManager.activeSession.value!!.id

        engine.startRecovery()

        assertEquals(sessionId, engine.guardedSessionId())
        assertFalse(engine.hasCompletedRecoveryFor(sessionId))
        assertFalse("An unrelated session id must never be authorised", engine.hasCompletedRecoveryFor(sessionId + 1))
    }

    @Test
    fun matchesOccurrence_rejectsAnAlarmQueuedBeforeTheScheduleWasEdited() {
        val schedule = weekdaySchedule()
        val queued = utcMillis(2026, 10, 26, 18, 30)

        assertTrue(ScheduleClock.matchesOccurrence(schedule, queued, utc))
        assertFalse(
            "Time of day was edited after the alarm was queued",
            ScheduleClock.matchesOccurrence(schedule.copy(startHour = 20), queued, utc)
        )
        assertFalse(
            "Minute was edited after the alarm was queued",
            ScheduleClock.matchesOccurrence(schedule.copy(startMinute = 45), queued, utc)
        )
        assertFalse(
            "Monday was removed from the mask after the alarm was queued",
            ScheduleClock.matchesOccurrence(schedule.copy(daysOfWeek = ScheduleEntity.WEEKENDS), queued, utc)
        )
    }

    @Test
    fun recovery_repeatedBootBroadcastsCreditTheRebootUptimeExactlyOnce() = runBlocking {
        val engine = engineWith(FakeRecoveryScheduler())
        brickSession()
        engine.startRecovery()
        timeProvider.advanceElapsedRealtime(8 * HOUR_MS)
        engine.onTick()

        timeProvider.simulateReboot(postBootElapsedMs = 60_000L, powerOffDurationMs = 48 * HOUR_MS)
        engine.onBootDetected()
        val afterFirstBroadcast = engine.remainingMs()
        assertEquals(16 * HOUR_MS - 60_000L, afterFirstBroadcast)

        // BOOT_COMPLETED and the OEM QUICKBOOT_POWERON variant can both arrive for one boot.
        engine.onBootDetected()
        engine.onBootDetected()

        assertEquals("Duplicate boot broadcasts must not re-credit uptime", afterFirstBroadcast, engine.remainingMs())
    }

    @Test
    fun recovery_tickBeforeTheBootBroadcastStillCreditsTheUptimeOnce() = runBlocking {
        val engine = engineWith(FakeRecoveryScheduler())
        brickSession()
        engine.startRecovery()
        timeProvider.advanceElapsedRealtime(8 * HOUR_MS)
        engine.onTick()

        // App startup commonly ticks before BOOT_COMPLETED is delivered; the tick already sees
        // the reboot from the monotonic regression.
        timeProvider.simulateReboot(postBootElapsedMs = 60_000L, powerOffDurationMs = 48 * HOUR_MS)
        engine.onTick()
        val afterTick = engine.remainingMs()
        assertEquals(16 * HOUR_MS - 60_000L, afterTick)

        engine.onBootDetected()

        assertEquals("A late boot broadcast must not re-credit the same uptime", afterTick, engine.remainingMs())
    }

    @Test
    fun recovery_keepsCountingAndRetriesWhenTheUnlockIsRefused() = runBlocking {
        val scheduler = FakeRecoveryScheduler()
        val engine = engineWith(scheduler)
        brickSession()
        engine.startRecovery()
        timeProvider.advanceElapsedRealtime(RecoveryEngine.COOLDOWN_DURATION_MS)

        // Make the unbrick fail for a reason outside the engine's control.
        db.close()
        engine.onTick()

        assertTrue("Proof must be persisted before the unbrick is attempted", engine.hasCompletedRecovery())
        assertTrue("A refused unlock must not retire the countdown", engine.isRecoveryActive())
        assertEquals(
            "A refused unlock must leave a paced retry armed",
            RecoveryEngine.UNLOCK_RETRY_INTERVAL_MS,
            scheduler.delays.last()
        )
    }

    @Test
    fun recovery_aForgedBootSignalCannotCreditUnservedUptime() = runBlocking {
        // BootReceiver must be exported to hear BOOT_COMPLETED, so treat any "the device just
        // booted" signal as untrusted: crediting elapsedRealtime on demand would hand an
        // attacker (or the user) the entire device uptime, which can exceed the whole cooldown.
        timeProvider.setElapsedRealtime(30 * HOUR_MS)
        brickSession()
        val engine = engineWith(FakeRecoveryScheduler())
        engine.startRecovery()

        timeProvider.advanceElapsedRealtime(60_000L)
        engine.onBootDetected()
        engine.onBootDetected()

        assertEquals(
            "Only the minute actually served may be credited, never the 30h of prior uptime",
            RecoveryEngine.COOLDOWN_DURATION_MS - 60_000L,
            engine.remainingMs()
        )
        assertTrue(engine.isRecoveryActive())
        assertFalse(engine.hasCompletedRecovery())
        assertTrue("Device must stay bricked", sessionManager.isBricked())
    }

    @Test
    fun startRecovery_isRejectedWhileNoSessionIsActive() {
        val engine = engineWith(FakeRecoveryScheduler())

        assertFalse(engine.canStartRecovery())
        engine.startRecovery()

        assertFalse("An idle countdown must never pre-authorise a future unlock", engine.isRecoveryActive())
        assertEquals(0L, engine.remainingMs())
        assertFalse(engine.hasCompletedRecovery())
        assertEquals(-1L, engine.guardedSessionId())
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private fun weekdaySchedule() = ScheduleEntity(
        id = 1L,
        name = "Deep work",
        modeId = 7L,
        daysOfWeek = ScheduleEntity.WEEKDAYS,
        startHour = 18,
        startMinute = 30
    )

    private fun managerAt(nowMillis: Long, gateway: FakeAlarmGateway) = ScheduleManager(
        context = context,
        alarms = gateway,
        timeProvider = FixedClock(nowMillis),
        timeZoneProvider = { utc }
    )

    private fun engineWith(scheduler: RecoveryScheduler) = RecoveryEngine.createWithTimeProvider(
        context = context,
        timeProvider = timeProvider,
        sessionManager = sessionManager,
        scheduler = scheduler
    )

    /** Brings the device into a bricked session with a paired token present. */
    private suspend fun brickSession() {
        db.tokenDao().insert(
            TokenEntity(tokenHash = "hash-primary", tokenType = TokenType.NFC, label = "Primary", isPrimary = true)
        )
        val modeId = db.modeDao().insert(
            ModeEntity(
                name = "Focus", modeType = ModeType.BLOCK_LIST,
                packageNames = setOf("com.android.chrome"), isDefault = true
            )
        )
        sessionManager.brick(modeId = modeId)
        sessionManager.refreshSession()
        assertTrue("Fixture must start from a bricked session", sessionManager.isBricked())
    }

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }

    private class FixedClock(private val nowMillis: Long) : TimeProvider {
        override fun currentTimeMillis(): Long = nowMillis
        override fun elapsedRealtime(): Long = 0L
    }

    private class FakeAlarmGateway(
        private val exactPermitted: Boolean = true,
        private val exactAccepted: Boolean = true,
        private val inexactThrows: Boolean = false
    ) : AlarmGateway {
        val exact = mutableListOf<Long>()
        val inexact = mutableListOf<Long>()
        val cancelled = mutableListOf<PendingIntent>()

        override fun canScheduleExact(): Boolean = exactPermitted

        override fun setExactWakeup(triggerAtMillis: Long, operation: PendingIntent): Boolean {
            if (!exactAccepted) return false
            exact += triggerAtMillis
            return true
        }

        override fun setInexactWakeup(triggerAtMillis: Long, operation: PendingIntent) {
            if (inexactThrows) throw SecurityException("alarms unavailable")
            inexact += triggerAtMillis
        }

        override fun cancel(operation: PendingIntent) {
            cancelled += operation
        }
    }

    private class FakeRecoveryScheduler : RecoveryScheduler {
        val delays = mutableListOf<Long>()
        var cancelCount = 0

        override fun scheduleNextTick(delayMs: Long) {
            delays += delayMs
        }

        override fun cancel() {
            cancelCount++
        }
    }
}
