package com.brick.app.core.security

import android.content.Context
import android.content.SharedPreferences
import com.brick.app.core.common.SystemTimeProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.UnbrickTrigger
import com.brick.app.feature.home.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.util.UUID

data class RecoveryState(
    val isActive: Boolean,
    val remainingMs: Long,
    val cumulativeElapsedMs: Long,
    val bootId: String
)

/**
 * 24-Hour Recovery Engine.
 * Monotonic cumulative elapsed-realtime countdown that:
 * 1. Requires 24 hours (86,400,000 ms) of real elapsed time.
 * 2. Survives device reboots mid-cooldown without losing progress or resetting.
 * 3. Completely ignores wall-clock manipulation (changing system date/time in Settings has 0 effect).
 */
class RecoveryEngine private constructor(
    private val context: Context,
    private val timeProvider: TimeProvider,
    private val sessionManager: SessionManager? = null
) {

    companion object {
        const val COOLDOWN_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val PREFS_NAME = "brick_recovery_prefs"
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_CUMULATIVE_ELAPSED_MS = "cumulative_elapsed_ms"
        private const val KEY_LAST_ELAPSED_MS = "last_elapsed_ms"
        private const val KEY_BOOT_ID = "boot_id"

        @Volatile
        private var INSTANCE: RecoveryEngine? = null

        fun getInstance(context: Context): RecoveryEngine {
            return INSTANCE ?: synchronized(this) {
                val provider = TestTimeProvider.getInstance() ?: SystemTimeProvider()
                INSTANCE ?: RecoveryEngine(context.applicationContext, provider).also { INSTANCE = it }
            }
        }

        fun createWithTimeProvider(
            context: Context,
            timeProvider: TimeProvider,
            sessionManager: SessionManager? = null
        ): RecoveryEngine {
            return RecoveryEngine(context.applicationContext, timeProvider, sessionManager)
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _recoveryState = MutableStateFlow(loadState())
    val recoveryStateFlow: StateFlow<RecoveryState> = _recoveryState.asStateFlow()

    private fun loadState(): RecoveryState {
        val isActive = prefs.getBoolean(KEY_IS_ACTIVE, false)
        val cumulative = prefs.getLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)
        val bootId = prefs.getString(KEY_BOOT_ID, "") ?: ""
        val remaining = if (isActive) (COOLDOWN_DURATION_MS - cumulative).coerceAtLeast(0L) else 0L

        return RecoveryState(
            isActive = isActive,
            remainingMs = remaining,
            cumulativeElapsedMs = cumulative,
            bootId = bootId
        )
    }

    @Synchronized
    fun startRecovery() {
        val currentElapsed = timeProvider.elapsedRealtime()
        val currentBootId = UUID.randomUUID().toString()

        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, true)
            .putLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)
            .putLong(KEY_LAST_ELAPSED_MS, currentElapsed)
            .putString(KEY_BOOT_ID, currentBootId)
            .apply()

        _recoveryState.value = RecoveryState(
            isActive = true,
            remainingMs = COOLDOWN_DURATION_MS,
            cumulativeElapsedMs = 0L,
            bootId = currentBootId
        )
    }

    @Synchronized
    fun onTick() {
        if (!prefs.getBoolean(KEY_IS_ACTIVE, false)) return

        val currentElapsed = timeProvider.elapsedRealtime()
        val lastElapsed = prefs.getLong(KEY_LAST_ELAPSED_MS, currentElapsed)
        var cumulative = prefs.getLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)

        if (currentElapsed >= lastElapsed) {
            // Normal progression within same boot
            val delta = currentElapsed - lastElapsed
            cumulative += delta
        } else {
            // Monotonic elapsed time reset -> device rebooted
            cumulative += currentElapsed
        }

        val remaining = (COOLDOWN_DURATION_MS - cumulative).coerceAtLeast(0L)
        val isFinished = cumulative >= COOLDOWN_DURATION_MS

        prefs.edit()
            .putLong(KEY_CUMULATIVE_ELAPSED_MS, cumulative)
            .putLong(KEY_LAST_ELAPSED_MS, currentElapsed)
            .putBoolean(KEY_IS_ACTIVE, !isFinished)
            .apply()

        _recoveryState.value = RecoveryState(
            isActive = !isFinished,
            remainingMs = remaining,
            cumulativeElapsedMs = cumulative,
            bootId = prefs.getString(KEY_BOOT_ID, "") ?: ""
        )

        if (isFinished) {
            // Auto unbrick via 24h recovery
            runBlocking {
                val sm = sessionManager ?: SessionManager.getInstance(context)
                sm.unbrick(UnbrickTrigger.RECOVERY_24H)
            }
        }
    }

    fun getRecoveryState(): StateFlow<RecoveryState> {
        onTick()
        return recoveryStateFlow
    }

    @Synchronized
    fun cancelRecovery() {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, false)
            .putLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)
            .apply()

        _recoveryState.value = RecoveryState(
            isActive = false,
            remainingMs = 0L,
            cumulativeElapsedMs = 0L,
            bootId = ""
        )
    }
}
