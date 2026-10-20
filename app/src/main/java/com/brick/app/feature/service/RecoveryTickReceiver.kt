package com.brick.app.feature.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brick.app.core.security.RecoveryEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Delivers the periodic monotonic tick that advances the 24-hour recovery countdown and completes
 * it unattended. Armed by `AndroidRecoveryScheduler`; each tick arms the next one.
 *
 * Not exported: only the app's own `PendingIntent` can trigger it, so a tick cannot be forged by
 * another app to probe or advance the cooldown (and advancing it is impossible anyway — the
 * engine credits monotonic elapsed time, not the number of ticks it receives).
 */
class RecoveryTickReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RecoveryTickReceiver"
        const val ACTION_RECOVERY_TICK = "com.brick.action.RECOVERY_TICK"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RECOVERY_TICK) return

        val pending = goAsync()
        scope.launch {
            try {
                RecoveryEngine.getInstance(context).onTick()
            } catch (t: Throwable) {
                Log.e(TAG, "Recovery tick failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
