package com.brick.app.feature.service

import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brick.app.BuildConfig
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.ScheduleEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.nfc.DefaultTokenPairingUseCase
import com.brick.app.feature.nfc.PairingResult
import com.brick.app.feature.nfc.DefaultTokenScanUseCase
import com.brick.app.feature.nfc.ScanResult
import com.brick.app.feature.schedules.ScheduleManager
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Test hook BroadcastReceiver for headless automated E2E verification.
 * Present only in the explicit e2e variant; excluded from ordinary debug and release.
 * Provides programmatic injection of NFC UID scans, QR payloads,
 * monotonic test clock advancement, recovery controls, and mode switching.
 */
class SimulateTokenScanReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimulateTokenScan"

        const val ACTION_SIMULATE_TOKEN_SCAN = "com.brick.action.SIMULATE_TOKEN_SCAN"
        const val ACTION_ADVANCE_TEST_CLOCK = "com.brick.action.ADVANCE_TEST_CLOCK"
        const val ACTION_ADVANCE_WALL_CLOCK = "com.brick.action.ADVANCE_WALL_CLOCK"
        const val ACTION_START_RECOVERY = "com.brick.action.START_RECOVERY"
        const val ACTION_CHECK_RECOVERY = "com.brick.action.CHECK_RECOVERY"
        const val ACTION_TRIGGER_SCHEDULE = "com.brick.action.TRIGGER_SCHEDULE"
        const val ACTION_SET_MODE = "com.brick.action.SET_MODE"
        const val ACTION_BRICK_STRICT = "com.brick.action.BRICK_STRICT"
        const val ACTION_GET_STATUS = "com.brick.action.GET_STATUS"
        const val ACTION_RESET_STATE = "com.brick.action.RESET_STATE"

        const val EXTRA_TOKEN_TYPE = "token_type"
        const val EXTRA_TOKEN_PAYLOAD = "token_payload"
        const val EXTRA_IS_PAIRING = "is_pairing"
        const val EXTRA_TOKEN_LABEL = "token_label"
        const val EXTRA_DELTA_MS = "delta_ms"
        const val EXTRA_MODE_TYPE = "mode_type"
        const val EXTRA_MODE_NAME = "mode_name"
        const val EXTRA_PACKAGES = "packages"
    }

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // Runtime defense-in-depth gate
        if (!BuildConfig.E2E_HOOKS) {
            Log.w(TAG, "SimulateTokenScanReceiver invoked outside E2E build; ignoring.")
            return
        }

        val action = intent.action ?: return
        Log.i(TAG, "Handling test action: $action")

        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                when (action) {
                    ACTION_SIMULATE_TOKEN_SCAN -> {
                        handleSimulateTokenScan(context, intent, pendingResult)
                    }
                    ACTION_ADVANCE_TEST_CLOCK -> {
                        handleAdvanceTestClock(context, intent, pendingResult)
                    }
                    ACTION_ADVANCE_WALL_CLOCK -> {
                        handleAdvanceWallClock(context, intent, pendingResult)
                    }
                    ACTION_START_RECOVERY -> {
                        handleStartRecovery(context, pendingResult)
                    }
                    ACTION_CHECK_RECOVERY -> {
                        handleCheckRecovery(context, pendingResult)
                    }
                    ACTION_TRIGGER_SCHEDULE -> {
                        handleTriggerSchedule(context, pendingResult)
                    }
                    ACTION_SET_MODE -> {
                        handleSetMode(context, intent, pendingResult)
                    }
                    ACTION_BRICK_STRICT -> {
                        handleBrickStrict(context, pendingResult)
                    }
                    ACTION_GET_STATUS -> {
                        handleGetStatus(context, pendingResult)
                    }
                    ACTION_RESET_STATE -> {
                        handleResetState(context, pendingResult)
                    }
                    else -> {
                        Log.w(TAG, "Unhandled action: $action")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error executing test action $action", t)
                pendingResult.setResultCode(-1)
                pendingResult.setResultData("ERROR: ${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSimulateTokenScan(context: Context, intent: Intent, pendingResult: PendingResult) {
        val tokenTypeStr = intent.getStringExtra(EXTRA_TOKEN_TYPE) ?: "NFC"
        val rawPayload = intent.getStringExtra(EXTRA_TOKEN_PAYLOAD) ?: ""
        val isPairing = intent.getBooleanExtra(EXTRA_IS_PAIRING, false)
        val label = intent.getStringExtra(EXTRA_TOKEN_LABEL) ?: "Test Token"

        val tokenType = if (tokenTypeStr.equals("QR", ignoreCase = true)) {
            TokenType.QR
        } else {
            TokenType.NFC
        }

        if (rawPayload.isBlank()) {
            pendingResult.setResultCode(1)
            pendingResult.setResultData("ERROR: Empty token payload")
            return
        }
        val db = BrickDatabase.getInstance(context)

        if (isPairing) {
            // Exercise the same session-bound pairing guard as a real NFC/QR scan.
            when (val result = DefaultTokenPairingUseCase(db, SessionManager.getInstance(context))
                .pairToken(rawPayload, tokenType, label)) {
                is PairingResult.Paired -> {
                    pendingResult.setResultCode(0)
                    pendingResult.setResultData("PAIRED: ${result.token.tokenHash}")
                }
                is PairingResult.AlreadyPaired -> {
                    pendingResult.setResultCode(0)
                    pendingResult.setResultData("PAIRED: ${result.token.tokenHash}")
                }
                PairingResult.RefusedWhileBricked -> {
                    pendingResult.setResultCode(1)
                    pendingResult.setResultData("REJECTED: BRICKED")
                }
                is PairingResult.Error -> {
                    pendingResult.setResultCode(1)
                    pendingResult.setResultData("ERROR: ${result.message}")
                }
            }
        } else {
            val sessionManager = SessionManager.getInstance(context)
            when (val result = DefaultTokenScanUseCase(db, sessionManager)
                .handleTokenScanned(rawPayload, tokenType)) {
                is ScanResult.BrickedSuccessfully -> {
                    pendingResult.setResultCode(0)
                    pendingResult.setResultData("BRICKED: ${result.tokenLabel}")
                }
                is ScanResult.UnbrickedSuccessfully -> {
                    pendingResult.setResultCode(0)
                    pendingResult.setResultData("UNBRICKED: ${result.tokenLabel}")
                }
                ScanResult.UnpairedTokenRejected -> {
                    pendingResult.setResultCode(1)
                    pendingResult.setResultData("REJECTED: UNPAIRED")
                }
                is ScanResult.Error -> {
                    pendingResult.setResultCode(1)
                    pendingResult.setResultData("ERROR: ${result.message}")
                }
            }
        }
    }

    private suspend fun handleAdvanceTestClock(context: Context, intent: Intent, pendingResult: PendingResult) {
        val deltaMs = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
        if (deltaMs <= 0) {
            Log.w(TAG, "Invalid delta_ms: $deltaMs")
            pendingResult.setResultCode(1)
            pendingResult.setResultData("ERROR: delta_ms must be > 0")
            return
        }

        TestTimeProvider.getInstance().advanceTime(deltaMs)
        Log.i(TAG, "Advanced monotonic test clock by $deltaMs ms")

        val recoveryEngine = RecoveryEngine.getInstance(context)
        recoveryEngine.onTick()

        val state = recoveryEngine.getRecoveryState().value
        pendingResult.setResultCode(0)
        pendingResult.setResultData("CLOCK_ADVANCED: delta_ms=$deltaMs, remaining_ms=${state.remainingMs}")
    }

    /** Change the time provider wall clock only, without touching monotonic elapsed time. */
    private fun handleAdvanceWallClock(context: Context, intent: Intent, pendingResult: PendingResult) {
        val deltaMs = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
        if (deltaMs == 0L) {
            pendingResult.setResultCode(1)
            pendingResult.setResultData("ERROR: delta_ms must be non-zero")
            return
        }
        val clock = TestTimeProvider.getInstance()
        clock.advanceWallClock(deltaMs)
        val state = RecoveryEngine.getInstance(context).getRecoveryState().value
        pendingResult.setResultCode(0)
        pendingResult.setResultData("WALL_ADVANCED: delta_ms=$deltaMs, remaining_ms=${state.remainingMs}")
    }

    private suspend fun handleStartRecovery(context: Context, pendingResult: PendingResult) {
        val recoveryEngine = RecoveryEngine.getInstance(context)
        recoveryEngine.startRecovery()
        val state = recoveryEngine.getRecoveryState().value
        Log.i(TAG, "Recovery started: remainingMs=${state.remainingMs}")
        pendingResult.setResultCode(0)
        pendingResult.setResultData("RECOVERY_STARTED: remaining_ms=${state.remainingMs}")
    }

    private suspend fun handleCheckRecovery(context: Context, pendingResult: PendingResult) {
        val recoveryEngine = RecoveryEngine.getInstance(context)
        recoveryEngine.onTick()
        val state = recoveryEngine.getRecoveryState().value
        val sessionManager = SessionManager.getInstance(context)
        val isBricked = sessionManager.isBricked()
        Log.i(TAG, "Recovery checked: active=${state.isActive}, remainingMs=${state.remainingMs}, isBricked=$isBricked")
        pendingResult.setResultCode(0)
        pendingResult.setResultData("RECOVERY_CHECK: active=${state.isActive}, remaining_ms=${state.remainingMs}, is_bricked=$isBricked")
    }

    private suspend fun handleTriggerSchedule(context: Context, pendingResult: PendingResult) {
        val db = BrickDatabase.getInstance(context)
        val mode = db.modeDao().getDefaultMode() ?: error("No default mode configured")
        val nextMinute = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
        val schedule = ScheduleEntity(
            name = "E2E scheduled focus",
            modeId = mode.id,
            daysOfWeek = ScheduleEntity.ALL_DAYS,
            startHour = nextMinute.get(Calendar.HOUR_OF_DAY),
            startMinute = nextMinute.get(Calendar.MINUTE)
        )
        val id = db.scheduleDao().insert(schedule)
        ScheduleManager(context).scheduleNextAlarm(schedule.copy(id = id))
        pendingResult.setResultCode(0)
        pendingResult.setResultData("SCHEDULE_SET: id=$id, hour=${schedule.startHour}, minute=${schedule.startMinute}")
    }

    private suspend fun handleSetMode(context: Context, intent: Intent, pendingResult: PendingResult) {
        val modeTypeStr = intent.getStringExtra(EXTRA_MODE_TYPE) ?: "BLOCK_LIST"
        val modeName = intent.getStringExtra(EXTRA_MODE_NAME) ?: "Test Mode"
        val packagesStr = intent.getStringExtra(EXTRA_PACKAGES) ?: ""
        val packageList = packagesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val modeType = if (modeTypeStr.equals("ALLOW_LIST", ignoreCase = true)) {
            ModeType.ALLOW_LIST
        } else {
            ModeType.BLOCK_LIST
        }

        val db = BrickDatabase.getInstance(context)
        val modeEntity = ModeEntity(
            name = modeName,
            type = modeType,
            packages = packageList,
            isActive = true
        )
        val modeId = db.modeDao().insertOrUpdateActive(modeEntity)
        // An existing session keeps its mode; an idle session must see the new default.
        SessionManager.getInstance(context).refreshSession()
        Log.i(TAG, "Configured active mode: id=$modeId, name=$modeName, type=$modeType, pkgs=$packageList")
        pendingResult.setResultCode(0)
        pendingResult.setResultData("MODE_SET: id=$modeId, type=$modeType")
    }

    private suspend fun handleBrickStrict(context: Context, pendingResult: PendingResult) {
        val sessionManager = SessionManager.getInstance(context)
        if (sessionManager.isBricked()) {
            pendingResult.setResultCode(1)
            pendingResult.setResultData("ERROR: Unbrick before starting strict session")
            return
        }
        sessionManager.brick(strictMode = true)
        pendingResult.setResultCode(0)
        pendingResult.setResultData("STRICT_BRICKED")
    }

    private suspend fun handleGetStatus(context: Context, pendingResult: PendingResult) {
        val sessionManager = SessionManager.getInstance(context)
        val recoveryEngine = RecoveryEngine.getInstance(context)
        val db = BrickDatabase.getInstance(context)

        val isBricked = sessionManager.isBricked()
        val tokenCount = db.tokenDao().getTokenCount()
        val recoveryState = recoveryEngine.getRecoveryState().value

        val status = "isBricked=$isBricked, strictMode=${sessionManager.isStrictModeActive()}, tokens=$tokenCount, recoveryActive=${recoveryState.isActive}, remainingMs=${recoveryState.remainingMs}"
        Log.i(TAG, "Status query: $status")
        pendingResult.setResultCode(0)
        pendingResult.setResultData(status)
    }

    private suspend fun handleResetState(context: Context, pendingResult: PendingResult) {
        val db = BrickDatabase.getInstance(context)
        val sessionManager = SessionManager.getInstance(context)

        if (sessionManager.isBricked()) {
            sessionManager.forceResetUnbrick()
        }
        // Cancel persisted alarms before deleting their rows. This receiver exists only in the
        // isolated E2E variant; a rerun must not inherit the previous run's timed actions.
        val scheduleManager = ScheduleManager(context)
        db.scheduleDao().getAllSchedules().forEach { scheduleManager.cancelAlarm(it.id) }
        db.clearAllTables()
        // Room's onCreate seed is not replayed by clearAllTables: restore the same usable
        // default mode a fresh install gets before any fixture assumes one exists.
        db.modeDao().insert(ModeEntity(
            name = "Deep Work",
            modeType = ModeType.BLOCK_LIST,
            packageNames = setOf(
                "com.google.android.youtube", "com.instagram.android",
                "com.zhiliaoapp.musically", "com.android.chrome"
            ),
            isDefault = true
        ))
        context.getSharedPreferences("brick_schedule_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Clear first-run setup before the next launch so reruns actually show onboarding.
        context.getSharedPreferences("brick_setup", Context.MODE_PRIVATE)
            .edit().remove("onboarding_complete").commit()
        RecoveryEngine.getInstance(context).cancelRecovery()
        TestTimeProvider.getInstance().reset()
        sessionManager.refreshSession()
        Log.i(TAG, "Test state reset completed")
        pendingResult.setResultCode(0)
        pendingResult.setResultData("STATE_RESET")
    }
}
