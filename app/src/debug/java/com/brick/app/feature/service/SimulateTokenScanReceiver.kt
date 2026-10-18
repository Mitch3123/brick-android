package com.brick.app.feature.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brick.app.BuildConfig
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.security.CryptoUtils
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.nfc.TokenScanUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Test hook BroadcastReceiver for headless automated E2E verification.
 * Strictly present only in debug builds; absent from release manifest.
 * Provides programmatic injection of NFC UID scans, QR payloads,
 * monotonic test clock advancement, recovery controls, and mode switching.
 */
class SimulateTokenScanReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimulateTokenScan"

        const val ACTION_SIMULATE_TOKEN_SCAN = "com.brick.action.SIMULATE_TOKEN_SCAN"
        const val ACTION_ADVANCE_TEST_CLOCK = "com.brick.action.ADVANCE_TEST_CLOCK"
        const val ACTION_START_RECOVERY = "com.brick.action.START_RECOVERY"
        const val ACTION_CHECK_RECOVERY = "com.brick.action.CHECK_RECOVERY"
        const val ACTION_TRIGGER_SCHEDULE = "com.brick.action.TRIGGER_SCHEDULE"
        const val ACTION_SET_MODE = "com.brick.action.SET_MODE"
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
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "SimulateTokenScanReceiver invoked in non-debug build; ignoring.")
            return
        }

        val action = intent.action ?: return
        Log.i(TAG, "Handling test action: $action")

        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                when (action) {
                    ACTION_SIMULATE_TOKEN_SCAN -> {
                        handleSimulateTokenScan(context, intent)
                    }
                    ACTION_ADVANCE_TEST_CLOCK -> {
                        handleAdvanceTestClock(context, intent)
                    }
                    ACTION_START_RECOVERY -> {
                        handleStartRecovery(context)
                    }
                    ACTION_CHECK_RECOVERY -> {
                        handleCheckRecovery(context)
                    }
                    ACTION_TRIGGER_SCHEDULE -> {
                        handleTriggerSchedule(context, intent)
                    }
                    ACTION_SET_MODE -> {
                        handleSetMode(context, intent)
                    }
                    ACTION_GET_STATUS -> {
                        handleGetStatus(context)
                    }
                    ACTION_RESET_STATE -> {
                        handleResetState(context)
                    }
                    else -> {
                        Log.w(TAG, "Unhandled action: $action")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error executing test action $action", t)
                setResultCode(-1)
                setResultData("ERROR: ${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSimulateTokenScan(context: Context, intent: Intent) {
        val tokenTypeStr = intent.getStringExtra(EXTRA_TOKEN_TYPE) ?: "NFC"
        val rawPayload = intent.getStringExtra(EXTRA_TOKEN_PAYLOAD) ?: ""
        val isPairing = intent.getBooleanExtra(EXTRA_IS_PAIRING, false)
        val label = intent.getStringExtra(EXTRA_TOKEN_LABEL) ?: "Test Token"

        val tokenType = if (tokenTypeStr.equals("QR", ignoreCase = true)) {
            TokenType.QR
        } else {
            TokenType.NFC
        }

        val tokenHash = CryptoUtils.sha256(rawPayload)
        val db = BrickDatabase.getInstance(context)

        if (isPairing) {
            val existingCount = db.tokenDao().getTokenCount()
            val entity = TokenEntity(
                tokenHash = tokenHash,
                tokenType = tokenType,
                label = label,
                createdAt = System.currentTimeMillis(),
                isPrimary = (existingCount == 0)
            )
            db.tokenDao().insertToken(entity)
            Log.i(TAG, "Paired token: type=$tokenType, label=$label, hash=$tokenHash")
            setResultCode(0)
            setResultData("PAIRED: $tokenHash")
        } else {
            val token = db.tokenDao().findByHash(tokenHash)
            val sessionManager = SessionManager.getInstance(context)

            if (token == null) {
                Log.w(TAG, "Unpaired token rejected: hash=$tokenHash")
                setResultCode(1)
                setResultData("REJECTED: UNPAIRED")
            } else {
                if (sessionManager.isBricked()) {
                    sessionManager.unbrick(triggerToken = token)
                    Log.i(TAG, "Unbricked with token: label=${token.label}")
                    setResultCode(0)
                    setResultData("UNBRICKED: ${token.label}")
                } else {
                    sessionManager.brick()
                    Log.i(TAG, "Bricked with token: label=${token.label}")
                    setResultCode(0)
                    setResultData("BRICKED: ${token.label}")
                }
            }
        }
    }

    private suspend fun handleAdvanceTestClock(context: Context, intent: Intent) {
        val deltaMs = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
        if (deltaMs <= 0) {
            Log.w(TAG, "Invalid delta_ms: $deltaMs")
            setResultCode(1)
            setResultData("ERROR: delta_ms must be > 0")
            return
        }

        TestTimeProvider.getInstance().advanceTime(deltaMs)
        Log.i(TAG, "Advanced monotonic test clock by $deltaMs ms")

        val recoveryEngine = RecoveryEngine.getInstance(context)
        recoveryEngine.onTick()

        val state = recoveryEngine.getRecoveryState().value
        setResultCode(0)
        setResultData("CLOCK_ADVANCED: delta_ms=$deltaMs, remaining_ms=${state.remainingMs}")
    }

    private suspend fun handleStartRecovery(context: Context) {
        val recoveryEngine = RecoveryEngine.getInstance(context)
        recoveryEngine.startRecovery()
        val state = recoveryEngine.getRecoveryState().value
        Log.i(TAG, "Recovery started: remainingMs=${state.remainingMs}")
        setResultCode(0)
        setResultData("RECOVERY_STARTED: remaining_ms=${state.remainingMs}")
    }

    private suspend fun handleCheckRecovery(context: Context) {
        val recoveryEngine = RecoveryEngine.getInstance(context)
        recoveryEngine.onTick()
        val state = recoveryEngine.getRecoveryState().value
        val sessionManager = SessionManager.getInstance(context)
        val isBricked = sessionManager.isBricked()
        Log.i(TAG, "Recovery checked: active=${state.isActive}, remainingMs=${state.remainingMs}, isBricked=$isBricked")
        setResultCode(0)
        setResultData("RECOVERY_CHECK: active=${state.isActive}, remaining_ms=${state.remainingMs}, is_bricked=$isBricked")
    }

    private suspend fun handleTriggerSchedule(context: Context, intent: Intent) {
        val sessionManager = SessionManager.getInstance(context)
        sessionManager.brick()
        Log.i(TAG, "Schedule triggered auto-brick")
        setResultCode(0)
        setResultData("SCHEDULE_TRIGGERED: BRICKED")
    }

    private suspend fun handleSetMode(context: Context, intent: Intent) {
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
        Log.i(TAG, "Configured active mode: id=$modeId, name=$modeName, type=$modeType, pkgs=$packageList")
        setResultCode(0)
        setResultData("MODE_SET: id=$modeId, type=$modeType")
    }

    private suspend fun handleGetStatus(context: Context) {
        val sessionManager = SessionManager.getInstance(context)
        val recoveryEngine = RecoveryEngine.getInstance(context)
        val db = BrickDatabase.getInstance(context)

        val isBricked = sessionManager.isBricked()
        val tokenCount = db.tokenDao().getTokenCount()
        val recoveryState = recoveryEngine.getRecoveryState().value

        val status = "isBricked=$isBricked, tokens=$tokenCount, recoveryActive=${recoveryState.isActive}, remainingMs=${recoveryState.remainingMs}"
        Log.i(TAG, "Status query: $status")
        setResultCode(0)
        setResultData(status)
    }

    private suspend fun handleResetState(context: Context) {
        val db = BrickDatabase.getInstance(context)
        val sessionManager = SessionManager.getInstance(context)

        if (sessionManager.isBricked()) {
            sessionManager.forceResetUnbrick()
        }
        db.clearAllTables()
        TestTimeProvider.getInstance().reset()
        Log.i(TAG, "Test state reset completed")
        setResultCode(0)
        setResultData("STATE_RESET")
    }
}
