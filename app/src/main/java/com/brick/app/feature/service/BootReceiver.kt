package com.brick.app.feature.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.schedules.ScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Repairs everything the platform throws away when the device restarts, the app is replaced, or
 * the clock/timezone is changed:
 *
 * - the brick session's foreground watchdog,
 * - the 24-hour recovery ledger (credited for the new boot) and its periodic tick alarm,
 * - every enabled schedule's `RTC_WAKEUP` alarm.
 *
 * All three alarm-bearing cases matter, not just boot: the OS clears pending alarms on reboot
 * *and* on package replacement, and an `RTC_WAKEUP` instant computed before a timezone change no
 * longer corresponds to the schedule's local wall-clock time.
 *
 * Only a genuine boot is reported to [RecoveryEngine.onBootDetected] as boot evidence; a package
 * replacement or clock change must never be mistaken for a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BrickBootReceiver"
        /**
         * Protected system broadcasts only. The unprotected OEM `QUICKBOOT_POWERON` variants are
         * excluded on purpose: this receiver must be exported to hear `BOOT_COMPLETED`, and an
         * action any app may send would be a forgeable boot signal.
         */
        private val BOOT_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED)
        private val REARM_ACTIONS = setOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot = action in BOOT_ACTIONS
        if (!isBoot && action !in REARM_ACTIONS) return
        Log.i(TAG, "Restoring brick state, recovery and schedules after $action")

        val pending = goAsync()
        scope.launch {
            try {
                // Independent paths: a failure in one must not skip the others, or a bad session
                // row would leave every schedule unarmed until the next reboot.
                if (isBoot) step("session restore") { restoreActiveSession(context) }
                step("recovery restore") { restoreRecovery(context, isBoot) }
                step("schedule rearm") { rearmSchedules(context, catchUp = isBoot) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Boot $name failed", t)
        }
    }

    private suspend fun restoreActiveSession(context: Context) {
        val activeSession = BrickDatabase.getInstance(context).sessionDao().getActiveSession()
        if (activeSession != null && activeSession.isActive) {
            Log.i(TAG, "Active session found post-boot: ${activeSession.id}; ensuring watchdog is running")
            BrickForegroundService.start(context, activeSession.modeName)
        }
    }

    private fun restoreRecovery(context: Context, isBoot: Boolean) {
        val engine = RecoveryEngine.getInstance(context)
        // Both paths only tick and re-arm. The tick itself recognises a reboot from the kernel
        // boot id or a monotonic regression, so no credit is derived from this broadcast.
        if (isBoot) engine.onBootDetected() else { engine.onTick(); engine.ensureTickScheduled() }
    }

    private suspend fun rearmSchedules(context: Context, catchUp: Boolean) {
        val schedules = BrickDatabase.getInstance(context).scheduleDao().getAllSchedules()
        if (schedules.isEmpty()) return

        val manager = ScheduleManager(context)
        val results = manager.rescheduleAll(schedules)
        Log.i(TAG, "Rearmed ${results.size} schedules; ${results.count { it is com.brick.app.feature.schedules.ArmResult.Armed }} armed")

        if (!catchUp) return
        val missed = manager.missedOccurrences(schedules)
        if (missed.isEmpty()) return
        brickForMissed(context, missed)
    }

    /**
     * A schedule whose moment passed while the device was off is honoured only inside
     * [ScheduleManager.CATCH_UP_WINDOW_MS]; anything older is logged and dropped rather than
     * bricking the user for a window that has long closed.
     */
    private suspend fun brickForMissed(context: Context, missed: List<ScheduleEntity>) {
        val sessionManager = SessionManager.getInstance(context)
        if (sessionManager.isBricked()) return
        val schedule = missed.first()
        // One session can be active at a time; show which other due rules were skipped rather
        // than silently discarding them when several schedules landed inside the catch-up window.
        if (missed.size > 1) {
            Log.i(TAG, "Skipping simultaneous missed schedules ${missed.drop(1).map { it.id }}; catching up ${schedule.id}")
        }
        Log.i(TAG, "Catching up schedule ${schedule.id} missed during downtime")
        // The central brick guard rejects an unlockable session (no paired token, empty block
        // list). Intentional: log and move on, the rearmed alarm stands.
        runCatching { sessionManager.brick(modeId = schedule.modeId.takeIf { it > 0 }) }
            .onFailure { Log.w(TAG, "Catch-up brick for schedule ${schedule.id} rejected: ${it.message}") }
    }
}
