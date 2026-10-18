package com.brick.app.feature.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.home.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BootReceiver: Restores brick state, foreground service watchdog, and 24h recovery tick
 * upon device reboot (BOOT_COMPLETED).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BrickBootReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed event received. Checking brick state and recovery.")

        val pending = goAsync()
        scope.launch {
            try {
                val db = BrickDatabase.getInstance(context)
                val activeSession = db.sessionDao().getActiveSession()

                if (activeSession != null && activeSession.isActive) {
                    Log.i(TAG, "Active session found post-boot: ${activeSession.id}. Ensuring watchdog is active.")
                    BrickForegroundService.start(context, activeSession.modeName)
                }

                // Check and update recovery engine state across reboot
                val recoveryEngine = RecoveryEngine.getInstance(context)
                recoveryEngine.onTick()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling boot completed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
