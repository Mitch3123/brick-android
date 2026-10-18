package com.brick.app.feature.schedules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.feature.home.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * ScheduleManager manages auto-bricking at user-defined schedules using AlarmManager.
 */
class ScheduleManager(private val context: Context) {

    companion object {
        const val ACTION_SCHEDULE_ALARM = "com.brick.action.SCHEDULE_ALARM"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_MODE_ID = "extra_mode_id"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleNextAlarm(schedule: ScheduleEntity) {
        if (!schedule.isEnabled) return

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, schedule.startHour)
            set(Calendar.MINUTE, schedule.startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If time already passed today, advance by 1 day
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_SCHEDULE_ALARM
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(EXTRA_MODE_ID, schedule.modeId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    target.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    target.timeInMillis,
                    pendingIntent
                )
            }
            Log.i("ScheduleManager", "Scheduled alarm for mode ${schedule.modeId} at ${target.time}")
        } catch (e: SecurityException) {
            Log.w("ScheduleManager", "Cannot set exact alarm: ${e.message}")
        }
    }

    fun cancelAlarm(scheduleId: Long) {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_SCHEDULE_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}

class ScheduleAlarmReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val modeId = intent?.getLongExtra(ScheduleManager.EXTRA_MODE_ID, -1L) ?: -1L
        Log.i("ScheduleAlarmReceiver", "Schedule alarm triggered! Auto-bricking device with mode: $modeId")

        val pending = goAsync()
        scope.launch {
            try {
                val sessionManager = SessionManager.getInstance(context)
                if (!sessionManager.isBricked()) {
                    sessionManager.brick(modeId = if (modeId != -1L) modeId else null)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
