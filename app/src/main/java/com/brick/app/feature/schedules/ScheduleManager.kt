package com.brick.app.feature.schedules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.brick.app.core.common.SystemTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.feature.home.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Whether the last arm attempt got a real exact alarm, degraded, or failed outright. */
enum class ExactAlarmStatus {
    /** No arm attempted yet in this process and nothing persisted. */
    UNKNOWN,

    /** Exact-and-allow-while-idle alarm accepted: schedule fires on the minute. */
    EXACT,

    /** Exact alarms unavailable (permission not granted); armed inexactly, so it may fire late. */
    INEXACT_FALLBACK,

    /** Neither exact nor inexact arming succeeded: this schedule will NOT fire. */
    FAILED
}

enum class SkipReason {
    /** `isEnabled == false`; any previously armed alarm was cancelled. */
    DISABLED,

    /** The weekday bitmask selects no day, so there is no future occurrence. */
    NO_ACTIVE_DAYS,

    /** `startHour`/`startMinute` outside 0..23 / 0..59. */
    INVALID_TIME
}

/** Outcome of a single arm attempt. Returned so UI can surface a degraded or failed schedule. */
sealed interface ArmResult {
    val scheduleId: Long

    data class Armed(
        override val scheduleId: Long,
        val triggerAtMillis: Long,
        val exact: Boolean
    ) : ArmResult

    data class Skipped(
        override val scheduleId: Long,
        val reason: SkipReason
    ) : ArmResult

    data class Failed(
        override val scheduleId: Long,
        val reason: String
    ) : ArmResult
}

/**
 * Indirection over [AlarmManager] so exact-alarm denial, inexact fallback and hard failure are
 * all reachable in a JVM test without depending on shadow internals.
 */
interface AlarmGateway {
    fun canScheduleExact(): Boolean

    /** @return false when the platform refused the exact alarm (permission revoked). */
    fun setExactWakeup(triggerAtMillis: Long, operation: PendingIntent): Boolean

    fun setInexactWakeup(triggerAtMillis: Long, operation: PendingIntent)

    fun cancel(operation: PendingIntent)
}

internal class AndroidAlarmGateway(context: Context) : AlarmGateway {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    override fun setExactWakeup(triggerAtMillis: Long, operation: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
        true
    } catch (e: SecurityException) {
        Log.w(ScheduleManager.TAG, "Exact alarm refused: ${e.message}")
        false
    }

    override fun setInexactWakeup(triggerAtMillis: Long, operation: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    override fun cancel(operation: PendingIntent) = alarmManager.cancel(operation)
}

/**
 * Arms auto-brick alarms for user-defined schedules.
 *
 * Dependability contract:
 * - The weekday bitmask is honoured; a schedule with no active day is never armed.
 * - Exactly one alarm exists per schedule id at a time (request code is the schedule id).
 * - Every fire rearms the following occurrence ([rearmAfterFire]) — the OS only ever holds the
 *   next one, so a dropped alarm cannot cascade into a permanently dead schedule.
 * - Boot, app replacement, time set and timezone change all rearm via
 *   `BootReceiver` -> [rescheduleAll].
 * - If exact alarms are denied the schedule is armed inexactly rather than silently dropped, and
 *   [exactAlarmStatus] reports the degradation so the UI can prompt for the permission.
 *
 * Time source: the trigger instant is wall-clock by nature (a schedule means "18:00 local"), and
 * `RTC_WAKEUP` alarms are delivered against the real RTC, so [timeProvider] defaults to
 * [SystemTimeProvider] even in debug builds. The debug [com.brick.app.core.common.TestTimeProvider]
 * deliberately does NOT drive schedule arming: it would compute occurrences against a fake epoch
 * the alarm service knows nothing about. Tamper resistance that must not be bypassable lives in
 * `RecoveryEngine`, which counts monotonic elapsed time only.
 */
class ScheduleManager(
    private val context: Context,
    private val alarms: AlarmGateway = AndroidAlarmGateway(context),
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val timeZoneProvider: () -> TimeZone = { TimeZone.getDefault() }
) {

    companion object {
        internal const val TAG = "ScheduleManager"

        const val ACTION_SCHEDULE_ALARM = "com.brick.action.SCHEDULE_ALARM"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_MODE_ID = "extra_mode_id"
        const val EXTRA_TRIGGER_AT_MILLIS = "extra_trigger_at_millis"

        /**
         * Gap inserted after a fired occurrence before searching for the next one, so a clock that
         * is a few milliseconds behind the delivered alarm cannot rearm the same instant and loop.
         */
        const val REARM_GUARD_MS = 60_000L

        /**
         * An occurrence missed no longer than this ago (device powered off over it, or the app was
         * being replaced) still bricks on boot. Older occurrences are skipped: retroactively
         * bricking for yesterday's schedule would be surprising rather than dependable.
         */
        const val CATCH_UP_WINDOW_MS = 15 * 60 * 1000L

        private const val PREFS_NAME = "brick_schedule_prefs"
        private const val KEY_EXACT_ALARM_STATUS = "exact_alarm_status"

        private val _exactAlarmStatus = MutableStateFlow(ExactAlarmStatus.UNKNOWN)

        /**
         * Process-wide status of the most recent arm attempt. Collect this in the UI to show
         * "schedules may fire late — grant exact alarms" or a hard failure banner.
         */
        val exactAlarmStatus: StateFlow<ExactAlarmStatus> = _exactAlarmStatus.asStateFlow()

        /** Last known status, surviving process death. Safe to call before any arm attempt. */
        fun lastExactAlarmStatus(context: Context): ExactAlarmStatus {
            val persisted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_EXACT_ALARM_STATUS, null) ?: return ExactAlarmStatus.UNKNOWN
            return runCatching { ExactAlarmStatus.valueOf(persisted) }.getOrDefault(ExactAlarmStatus.UNKNOWN)
        }
    }

    /**
     * Arms the next occurrence of [schedule], replacing any alarm already armed for its id.
     *
     * Signature is unchanged for existing callers (`ScheduleManager(context).scheduleNextAlarm(s)`);
     * the [ArmResult] return value is additive and may be ignored.
     */
    fun scheduleNextAlarm(schedule: ScheduleEntity): ArmResult = armFrom(schedule, nowMillis())

    /**
     * Rearms the occurrence following the one that just fired.
     *
     * @param firedTriggerAtMillis the trigger instant carried by the delivered alarm, used in
     *   preference to "now" when the alarm arrives early by a few milliseconds.
     */
    fun rearmAfterFire(schedule: ScheduleEntity, firedTriggerAtMillis: Long): ArmResult {
        val from = maxOf(nowMillis(), firedTriggerAtMillis) + REARM_GUARD_MS
        return armFrom(schedule, from)
    }

    /**
     * Rearms every supplied schedule. Disabled ones are cancelled, so this is also the repair
     * path after boot, app replacement, time set or timezone change — all of which either clear
     * the alarm table or invalidate previously computed trigger instants.
     */
    fun rescheduleAll(schedules: List<ScheduleEntity>): List<ArmResult> =
        schedules.map { scheduleNextAlarm(it) }

    /**
     * Enabled schedules whose occurrence fell inside the last [windowMs] and was therefore never
     * delivered. Caller decides what to do (see `BootReceiver`), keeping this side-effect free.
     */
    fun missedOccurrences(
        schedules: List<ScheduleEntity>,
        nowMillis: Long = nowMillis(),
        windowMs: Long = CATCH_UP_WINDOW_MS
    ): List<ScheduleEntity> = schedules.filter { schedule ->
        if (!schedule.isEnabled) return@filter false
        val previous = ScheduleClock.previousOccurrenceMillis(schedule, nowMillis, timeZoneProvider())
            ?: return@filter false
        nowMillis - previous in 0..windowMs
    }

    /** Next trigger instant for UI labels such as "Next: Mon 18:00", or null if it can never fire. */
    fun nextTriggerMillis(schedule: ScheduleEntity, fromMillis: Long = nowMillis()): Long? =
        if (!schedule.isEnabled) null
        else ScheduleClock.nextOccurrenceMillis(schedule, fromMillis, timeZoneProvider())

    fun canScheduleExactAlarms(): Boolean = alarms.canScheduleExact()

    /**
     * Settings screen where the user grants `SCHEDULE_EXACT_ALARM`, or null below API 31 where the
     * permission is granted at install time. Launch from the UI when [exactAlarmStatus] degrades.
     */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
        } else {
            null
        }

    /**
     * Cancels any alarm armed for [scheduleId]. Unconditional: the `PendingIntent` is resolved
     * with `FLAG_UPDATE_CURRENT` rather than `FLAG_NO_CREATE` so the cancel does not depend on
     * whether the platform still holds a matching one, then the token itself is cancelled.
     */
    fun cancelAlarm(scheduleId: Long) {
        val operation = PendingIntent.getBroadcast(
            context,
            scheduleId.toInt(),
            alarmIntent(scheduleId, modeId = -1L, triggerAtMillis = 0L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarms.cancel(operation)
        operation.cancel()
        Log.i(TAG, "Cancelled alarm for schedule $scheduleId")
    }

    private fun armFrom(schedule: ScheduleEntity, fromMillis: Long): ArmResult {
        if (!schedule.isEnabled) {
            cancelAlarm(schedule.id)
            return ArmResult.Skipped(schedule.id, SkipReason.DISABLED)
        }
        if (!ScheduleClock.isValidTimeOfDay(schedule.startHour, schedule.startMinute)) {
            cancelAlarm(schedule.id)
            Log.w(TAG, "Schedule ${schedule.id} has invalid time ${schedule.startHour}:${schedule.startMinute}")
            return ArmResult.Skipped(schedule.id, SkipReason.INVALID_TIME)
        }
        val triggerAt = ScheduleClock.nextOccurrenceMillis(schedule, fromMillis, timeZoneProvider())
        if (triggerAt == null) {
            cancelAlarm(schedule.id)
            Log.w(TAG, "Schedule ${schedule.id} selects no active weekday; not armed")
            return ArmResult.Skipped(schedule.id, SkipReason.NO_ACTIVE_DAYS)
        }

        val operation = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt(),
            alarmIntent(schedule.id, schedule.modeId, triggerAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (alarms.canScheduleExact() && alarms.setExactWakeup(triggerAt, operation)) {
            publishStatus(ExactAlarmStatus.EXACT)
            Log.i(TAG, "Armed exact alarm: schedule=${schedule.id} mode=${schedule.modeId} at $triggerAt")
            return ArmResult.Armed(schedule.id, triggerAt, exact = true)
        }

        return try {
            alarms.setInexactWakeup(triggerAt, operation)
            publishStatus(ExactAlarmStatus.INEXACT_FALLBACK)
            Log.w(
                TAG,
                "Exact alarms unavailable; schedule=${schedule.id} armed INEXACTLY at $triggerAt and may fire late"
            )
            ArmResult.Armed(schedule.id, triggerAt, exact = false)
        } catch (t: Throwable) {
            publishStatus(ExactAlarmStatus.FAILED)
            Log.e(TAG, "Schedule ${schedule.id} could not be armed at all", t)
            ArmResult.Failed(schedule.id, t.message ?: t::class.java.simpleName)
        }
    }

    private fun alarmIntent(scheduleId: Long, modeId: Long, triggerAtMillis: Long): Intent =
        Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_SCHEDULE_ALARM
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_MODE_ID, modeId)
            putExtra(EXTRA_TRIGGER_AT_MILLIS, triggerAtMillis)
        }

    private fun publishStatus(status: ExactAlarmStatus) {
        _exactAlarmStatus.value = status
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EXACT_ALARM_STATUS, status.name)
            .apply()
    }

    private fun nowMillis(): Long = timeProvider.currentTimeMillis()
}

/**
 * Receives a fired schedule alarm: bricks with the schedule's mode and immediately rearms the
 * next occurrence. The stored row is re-read and re-validated rather than trusted from the intent,
 * so an alarm armed before the schedule was edited, disabled or deleted cannot brick the device.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduleManager.ACTION_SCHEDULE_ALARM) return

        val scheduleId = intent.getLongExtra(ScheduleManager.EXTRA_SCHEDULE_ID, -1L)
        val modeIdExtra = intent.getLongExtra(ScheduleManager.EXTRA_MODE_ID, -1L)
        val triggerAt = intent.getLongExtra(ScheduleManager.EXTRA_TRIGGER_AT_MILLIS, 0L)
        Log.i(TAG, "Schedule alarm fired: schedule=$scheduleId mode=$modeIdExtra triggerAt=$triggerAt")

        val pending = goAsync()
        scope.launch {
            try {
                handle(context, scheduleId, modeIdExtra, triggerAt)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle schedule alarm for $scheduleId", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, scheduleId: Long, modeIdExtra: Long, triggerAt: Long) {
        val manager = ScheduleManager(context)
        val stored = runCatching {
            if (scheduleId > 0) BrickDatabase.getInstance(context).scheduleDao().getScheduleById(scheduleId) else null
        }.onFailure { Log.w(TAG, "Could not load schedule $scheduleId: ${it.message}") }.getOrNull()

        if (stored == null) {
            // Row unreadable or deleted. Bricking from an unverifiable intent would be worse than
            // skipping; the alarm is not rearmed because there is nothing authoritative to rearm.
            Log.w(TAG, "No stored schedule $scheduleId; ignoring stale alarm")
            return
        }
        if (!stored.isEnabled) {
            Log.i(TAG, "Schedule $scheduleId is disabled; ignoring stale alarm")
            manager.cancelAlarm(scheduleId)
            return
        }

        // Rearm from the stored row first, so a rejected or failed brick can never break the
        // repeating series. This is the only reason the device keeps auto-bricking day after day.
        manager.rearmAfterFire(stored, triggerAt)

        if (!isQueuedOccurrenceStillValid(stored, modeIdExtra, triggerAt)) return

        val sessionManager = SessionManager.getInstance(context)
        if (sessionManager.isBricked()) {
            Log.i(TAG, "Already bricked; schedule $scheduleId is a no-op")
            return
        }
        // The central brick guard rejects a session that could not be unlocked again (no paired
        // token, empty block list). That is intentional: log it and keep the next alarm armed.
        runCatching { sessionManager.brick(modeId = stored.modeId.takeIf { it > 0 }) }
            .onSuccess { Log.i(TAG, "Auto-bricked by schedule $scheduleId with mode ${stored.modeId}") }
            .onFailure { Log.w(TAG, "Schedule $scheduleId could not brick: ${it.message}") }
    }

    /**
     * An alarm already queued with the platform keeps the trigger instant and mode it was armed
     * with. If the user has since edited the time, the weekday mask or the mode, that queued
     * instant must not brick: it would fire at a time the schedule no longer specifies.
     */
    private fun isQueuedOccurrenceStillValid(stored: ScheduleEntity, modeIdExtra: Long, triggerAt: Long): Boolean {
        if (modeIdExtra > 0 && modeIdExtra != stored.modeId) {
            Log.w(
                TAG,
                "Schedule ${stored.id} mode changed after this alarm was queued " +
                    "(queued $modeIdExtra, stored ${stored.modeId}); not bricking, rearmed alarm carries the new mode"
            )
            return false
        }
        if (triggerAt <= 0L) {
            // Every alarm this app arms carries its occurrence. An intent without one cannot be
            // validated against the stored row, so it is rejected rather than trusted: otherwise a
            // hand-sent broadcast could brick at an hour the schedule never specified.
            Log.w(TAG, "Schedule ${stored.id} alarm carries no occurrence instant; not bricking")
            return false
        }
        if (!ScheduleClock.matchesOccurrence(stored, triggerAt)) {
            Log.w(
                TAG,
                "Schedule ${stored.id} was edited after this alarm was queued " +
                    "(queued $triggerAt is no longer ${stored.startHour}:${stored.startMinute} on an active day); not bricking"
            )
            return false
        }
        val lateness = System.currentTimeMillis() - triggerAt
        if (lateness > ScheduleManager.CATCH_UP_WINDOW_MS) {
            Log.w(TAG, "Schedule ${stored.id} alarm arrived ${lateness}ms late; window closed, not bricking")
            return false
        }
        return true
    }
}
