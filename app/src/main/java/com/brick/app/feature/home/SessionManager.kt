package com.brick.app.feature.home

import android.content.Context
import android.content.Intent
import android.util.Log
import com.brick.app.core.common.SystemTimeProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.TokenEntity
import com.brick.app.core.database.UnbrickTrigger
import com.brick.app.feature.service.BrickForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
                val provider = TestTimeProvider.getInstance() ?: SystemTimeProvider()
                INSTANCE ?: SessionManager(context.applicationContext, provider).also { INSTANCE = it }
            }
        }

        fun createWithDatabase(context: Context, timeProvider: TimeProvider, database: BrickDatabase): SessionManager {
            return SessionManager(context.applicationContext, timeProvider, database)
        }
    }

    private val db = database ?: BrickDatabase.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    suspend fun brick(modeId: Long? = null, strictMode: Boolean = false) {
        val mode = if (modeId != null) {
            db.modeDao().getModeById(modeId)
        } else {
            db.modeDao().getDefaultMode()
        } ?: run {
            // Create fallback default mode if none exists
            val fallback = ModeEntity(
                name = "Deep Work",
                modeType = com.brick.app.core.database.ModeType.BLOCK_LIST,
                packageNames = emptySet(),
                isDefault = true
            )
            val newId = db.modeDao().insert(fallback)
            fallback.copy(id = newId)
        }

        // Close any dangling sessions first
        val existing = db.sessionDao().getActiveSession()
        if (existing != null) {
            db.sessionDao().closeSession(
                sessionId = existing.id,
                endTimeMs = timeProvider.currentTimeMillis(),
                trigger = UnbrickTrigger.MANUAL_DEBUG
            )
        }

        val newSession = BrickSessionEntity(
            startTimeMs = timeProvider.currentTimeMillis(),
            modeId = mode.id,
            modeName = mode.name,
            modeType = mode.modeType,
            strictModeActive = strictMode
        )

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

    suspend fun unbrick(trigger: UnbrickTrigger = UnbrickTrigger.NFC_TAG, triggerToken: TokenEntity? = null) {
        val session = db.sessionDao().getActiveSession()
        if (session != null) {
            val endTime = timeProvider.currentTimeMillis()
            db.sessionDao().closeSession(
                sessionId = session.id,
                endTimeMs = endTime,
                trigger = trigger
            )
        }

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
        unbrick(UnbrickTrigger.MANUAL_DEBUG)
    }
}
