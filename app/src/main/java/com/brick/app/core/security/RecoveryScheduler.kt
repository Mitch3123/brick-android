package com.brick.app.core.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.brick.app.feature.service.RecoveryTickReceiver

/**
 * Wakes [RecoveryEngine] periodically so a 24-hour countdown completes on its own instead of only
 * when some screen happens to be opened.
 */
interface RecoveryScheduler {

    /** Requests a tick in [delayMs] of monotonic time. Replaces any previously requested tick. */
    fun scheduleNextTick(delayMs: Long)

    fun cancel()
}

/**
 * `ELAPSED_REALTIME_WAKEUP` implementation.
 *
 * Monotonic on purpose: an `RTC_WAKEUP` tick would move when the user changes the device clock.
 * The tick is only a nudge — all accounting is done from the persisted ledger when it fires — so
 * falling back to an inexact alarm when `SCHEDULE_EXACT_ALARM` is denied is safe: an inexact alarm
 * can only be delivered late, which can delay an unlock but never shorten the cooldown.
 *
 * Elapsed-realtime alarms do not survive a reboot, so `BootReceiver` must re-arm them
 * (see [RecoveryEngine.onBootDetected]).
 */
class AndroidRecoveryScheduler(private val context: Context) : RecoveryScheduler {

    companion object {
        private const val TAG = "RecoveryScheduler"
        private const val REQUEST_CODE = 0x2B10C
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleNextTick(delayMs: Long) {
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
        val operation = operation(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
                if (exactAllowed) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation
                    )
                }
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
            }
        } catch (t: Throwable) {
            // Worst case the countdown advances on the next app launch or boot instead.
            Log.w(TAG, "Could not arm recovery tick: ${t.message}")
        }
    }

    override fun cancel() {
        val operation = operation(PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    private fun operation(flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, RecoveryTickReceiver::class.java).setAction(RecoveryTickReceiver.ACTION_RECOVERY_TICK),
        flag or PendingIntent.FLAG_IMMUTABLE
    )
}
