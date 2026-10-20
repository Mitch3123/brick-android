package com.brick.app.feature.home

import android.content.Context
import android.util.Log
import com.brick.app.core.common.SystemTimeProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.TokenType
import com.brick.app.core.database.UnbrickTrigger
import com.brick.app.core.security.RecoveryEngine
import com.brick.app.feature.service.BrickForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

/**
 * Core session state machine.
 * Coordinates Brick/Unbrick status, active session in Room DB,
 * foreground watchdog service, and strict mode.
 */
class SessionManager private constructor(
    private val context: Context,
    private val timeProvider: TimeProvider,
    private val database: BrickDatabase? = null
) {

    companion object {
        private const val TAG = "SessionManager"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                val provider = if (com.brick.app.BuildConfig.E2E_HOOKS) TestTimeProvider.getInstance() else SystemTimeProvider()
                INSTANCE ?: SessionManager(context.applicationContext, provider).also { INSTANCE = it }
            }
        }

        fun createWithDatabase(context: Context, timeProvider: TimeProvider, database: BrickDatabase): SessionManager {
            return SessionManager(context.applicationContext, timeProvider, database)
        }
    }

    private val db = database ?: BrickDatabase.getInstance(context)
    private val transitionMutex = Mutex()

    /** Holds the same lock as brick/unbrick through the actual token write. */
    suspend fun <T> whileUnbricked(block: suspend () -> T): T? = transitionMutex.withLock {
        if (db.sessionDao().getActiveSession() != null) null else block()
    }

    private val _activeSession = MutableStateFlow<BrickSessionEntity?>(null)
    val activeSession: StateFlow<BrickSessionEntity?> = _activeSession.asStateFlow()

    private val _isBricked = MutableStateFlow(false)
    val isBrickedFlow: StateFlow<Boolean> = _isBricked.asStateFlow()

    private val _activeMode = MutableStateFlow<ModeEntity?>(null)
    val activeMode: StateFlow<ModeEntity?> = _activeMode.asStateFlow()

    init {
        // Hydrate active session synchronously on startup so state is immediate
        runBlocking(Dispatchers.IO) {
            refreshSession()
        }
    }

    suspend fun refreshSession() {
        val session = db.sessionDao().getActiveSession()
        _activeSession.value = session
        _isBricked.value = (session != null && session.isActive)

        if (session != null) {
            _activeMode.value = db.modeDao().getModeById(session.modeId) ?: db.modeDao().getDefaultMode()
        } else {
            _activeMode.value = db.modeDao().getDefaultMode()
        }
    }

    fun isBricked(): Boolean {
        // Fast path from StateFlow, fallback to DB if needed
        return _isBricked.value
    }

    fun getActiveSessionFlow(): StateFlow<BrickSessionEntity?> = activeSession
    fun getActiveModeFlow(): StateFlow<ModeEntity?> = activeMode

    fun isStrictModeActive(): Boolean {
        return _activeSession.value?.strictModeActive == true
    }

    suspend fun brick(modeId: Long? = null, strictMode: Boolean = false) = transitionMutex.withLock {
        check(db.sessionDao().getActiveSession() == null) { "Already bricked" }
        // Guard the state-machine boundary: alarms and token scans can start sessions too.
        check(db.tokenDao().getTokenCount() > 0) { "Pair a physical token before bricking" }
        val requestedMode = if (modeId != null) db.modeDao().getModeById(modeId) else null
        if (modeId != null) check(requestedMode != null) { "Scheduled mode no longer exists" }
        val mode = requestedMode ?: db.modeDao().getDefaultMode() ?: run {
            // Create fallback default mode if none exists
            val fallback = ModeEntity(
                name = "Deep Work",
                modeType = com.brick.app.core.database.ModeType.BLOCK_LIST,
                packageNames = setOf("com.google.android.youtube", "com.instagram.android", "com.android.chrome"),
                isDefault = true
            )
            val newId = db.modeDao().insert(fallback)
            fallback.copy(id = newId)
        }

        check(mode.modeType != com.brick.app.core.database.ModeType.BLOCK_LIST || mode.packageNames.isNotEmpty()) {
            "Select apps to block before bricking"
        }

        val newSession = BrickSessionEntity(
            startTimeMs = timeProvider.currentTimeMillis(),
            modeId = mode.id,
            modeName = mode.name,
            modeType = mode.modeType,
            strictModeActive = strictMode
        )

        RecoveryEngine.getInstance(context).cancelRecovery()
        val sessionId = db.sessionDao().insert(newSession)
        val createdSession = newSession.copy(id = sessionId)

        _activeSession.value = createdSession
        _activeMode.value = mode
        _isBricked.value = true

        Log.i(TAG, "Device BRICKED: session=$sessionId, mode=${mode.name}")

        // Start Foreground Watchdog Service
        try {
            BrickForegroundService.start(context, mode.name)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    suspend fun unbrick(trigger: UnbrickTrigger, triggerToken: TokenEntity? = null) = transitionMutex.withLock {
        val session = db.sessionDao().getActiveSession() ?: return@withLock
        val expectedType = when (trigger) {
            UnbrickTrigger.NFC_TAG -> TokenType.NFC
            UnbrickTrigger.QR_CODE -> TokenType.QR
            else -> null
        }
        when (trigger) {
            UnbrickTrigger.NFC_TAG, UnbrickTrigger.QR_CODE -> {
                require(triggerToken != null && triggerToken.id > 0 &&
                    triggerToken.tokenType == expectedType &&
                    db.tokenDao().getById(triggerToken.id)?.let { paired ->
                        paired.tokenType == expectedType && paired.tokenHash == triggerToken.tokenHash
                    } == true) { "A paired physical token is required for NFC/QR unlock" }
            }
            UnbrickTrigger.RECOVERY_24H -> {
                check(RecoveryEngine.getInstance(context).hasCompletedRecoveryFor(session.id)) {
                    "24-hour recovery is not complete"
                }
            }
            UnbrickTrigger.MANUAL_DEBUG -> {
                check(com.brick.app.BuildConfig.E2E_HOOKS) { "Manual unbrick is only available in the isolated E2E build" }
            }
        }
        if (session.isActive) {
            val endTime = timeProvider.currentTimeMillis()
            db.sessionDao().closeSession(
                sessionId = session.id,
                endTimeMs = endTime,
                trigger = trigger
            )
        }

        RecoveryEngine.getInstance(context).cancelRecovery()
        _activeSession.value = null
        _isBricked.value = false

        Log.i(TAG, "Device UNBRICKED by $trigger")

        // Stop Foreground Service
        try {
            BrickForegroundService.stop(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop foreground service: ${e.message}")
        }
    }

    suspend fun forceResetUnbrick() {
        check(com.brick.app.BuildConfig.E2E_HOOKS) { "Reset disabled outside the isolated E2E build" }
        unbrick(UnbrickTrigger.MANUAL_DEBUG)
    }
}
