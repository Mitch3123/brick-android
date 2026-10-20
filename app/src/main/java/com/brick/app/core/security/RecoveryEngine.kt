package com.brick.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.brick.app.core.common.SystemTimeProvider
import com.brick.app.core.common.TestTimeProvider
import com.brick.app.core.common.TimeProvider
import com.brick.app.core.database.UnbrickTrigger
import com.brick.app.feature.home.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

data class RecoveryState(
    val isActive: Boolean,
    val remainingMs: Long,
    val cumulativeElapsedMs: Long,
    val bootId: String,
    /** The 24h of real elapsed time has been served; the unlock is authorised. */
    val isComplete: Boolean = false
)

/** Persisted monotonic countdown progress. */
data class RecoveryLedger(
    val cumulativeElapsedMs: Long,
    val anchorElapsedMs: Long,
    val bootToken: String
)

/**
 * The whole monotonic-accounting rule, as a pure function so every reboot and tamper case is
 * unit-testable without Android.
 *
 * Invariants:
 * - Only time measured *inside one boot* is ever credited. Wall-clock changes contribute nothing
 *   because the wall clock is never read.
 * - Across a boot the credit is `elapsedRealtime` (uptime of the new boot). The interval the
 *   device spent powered off is deliberately not credited: it is unmeasurable without trusting
 *   the wall clock, and trusting it would make "set the clock forward, reboot" a bypass.
 * - Credit is never negative, so no input can wind the countdown backwards.
 */
object RecoveryAccounting {

    /**
     * A new boot epoch is recognised only from evidence that cannot be forged: a change in the
     * kernel boot id, or the monotonic clock having gone backwards.
     *
     * [bootEvidence] is an out-of-band *verified* boot signal and production always passes
     * `false`. A broadcast must never set it: `BOOT_COMPLETED` is a protected system broadcast,
     * but the OEM `QUICKBOOT_POWERON` variants are not, so any app could send one to an exported
     * receiver and have the device's entire uptime credited at once. Failing closed costs at most
     * a slightly longer cooldown on a device whose kernel boot id is unreadable; failing open
     * would hand out an instant unlock.
     */
    fun isNewBootEpoch(
        ledger: RecoveryLedger,
        currentElapsedMs: Long,
        currentBootToken: String,
        bootEvidence: Boolean
    ): Boolean {
        if (bootEvidence) return true
        if (currentElapsedMs < ledger.anchorElapsedMs) return true
        val known = currentBootToken != BootIdentityProvider.NO_IDENTITY &&
            ledger.bootToken != BootIdentityProvider.NO_IDENTITY
        return known && currentBootToken != ledger.bootToken
    }

    fun advance(
        ledger: RecoveryLedger,
        currentElapsedMs: Long,
        currentBootToken: String,
        bootEvidence: Boolean
    ): RecoveryLedger {
        val newEpoch = isNewBootEpoch(ledger, currentElapsedMs, currentBootToken, bootEvidence)
        val credit = if (newEpoch) currentElapsedMs else currentElapsedMs - ledger.anchorElapsedMs
        return RecoveryLedger(
            cumulativeElapsedMs = ledger.cumulativeElapsedMs + credit.coerceAtLeast(0L),
            anchorElapsedMs = currentElapsedMs.coerceAtLeast(0L),
            bootToken = if (currentBootToken != BootIdentityProvider.NO_IDENTITY) currentBootToken else ledger.bootToken
        )
    }
}

/**
 * 24-Hour Recovery Engine: the only way to unbrick without a paired token.
 *
 * 1. Requires 24 hours (86,400,000 ms) of real elapsed time.
 * 2. Survives reboots mid-cooldown without losing progress and without being shortened.
 * 3. Never reads the wall clock, so changing the system date/time has exactly zero effect.
 * 4. Ticks itself via [RecoveryScheduler] so the countdown completes unattended.
 *
 * Threading: all state transitions happen inside a short critical section that performs no
 * blocking or coroutine work. The unbrick attempt is made with no lock held, because
 * `SessionManager.brick`/`unbrick` call back into [cancelRecovery] from a coroutine dispatcher —
 * holding the monitor across that call would deadlock. Ticks should still be delivered off the
 * main thread (the alarm receiver and boot receiver both use `goAsync` + `Dispatchers.IO`).
 */
class RecoveryEngine private constructor(
    private val context: Context,
    private val timeProvider: TimeProvider,
    private val sessionManager: SessionManager? = null,
    scheduler: RecoveryScheduler? = null
) {

    companion object {
        private const val TAG = "RecoveryEngine"

        const val COOLDOWN_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

        /** Upper bound on how long the engine goes un-ticked while a countdown is running. */
        const val TICK_INTERVAL_MS = 15 * 60 * 1000L

        /**
         * Retry cadence once the time is served but the unbrick was refused or threw. Paced, not
         * a busy loop: the countdown stays active until the session is confirmed closed.
         */
        const val UNLOCK_RETRY_INTERVAL_MS = 60 * 1000L

        private const val PREFS_NAME = "brick_recovery_prefs"
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_CUMULATIVE_ELAPSED_MS = "cumulative_elapsed_ms"
        private const val KEY_LAST_ELAPSED_MS = "last_elapsed_ms"
        private const val KEY_BOOT_ID = "boot_id"
        private const val KEY_COMPLETED = "recovery_completed"
        private const val KEY_COMPLETED_SESSION_ID = "recovery_completed_session_id"

        @Volatile
        private var INSTANCE: RecoveryEngine? = null

        fun getInstance(context: Context): RecoveryEngine {
            val application = context.applicationContext
            INSTANCE?.takeIf { it.context === application }?.let { return it }
            return synchronized(this) {
                INSTANCE?.takeIf { it.context === application } ?: run {
                    val provider = if (com.brick.app.BuildConfig.E2E_HOOKS) TestTimeProvider.getInstance() else SystemTimeProvider()
                    RecoveryEngine(application, provider).also { INSTANCE = it }
                }
            }
        }

        fun createWithTimeProvider(
            context: Context,
            timeProvider: TimeProvider,
            sessionManager: SessionManager? = null,
            scheduler: RecoveryScheduler? = null
        ): RecoveryEngine {
            return RecoveryEngine(context.applicationContext, timeProvider, sessionManager, scheduler)
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scheduler: RecoveryScheduler = scheduler ?: AndroidRecoveryScheduler(context)

    /** Guards ledger transitions only. Never held across blocking or coroutine work. */
    private val lock = Any()

    private val _recoveryState = MutableStateFlow(loadState())
    val recoveryStateFlow: StateFlow<RecoveryState> = _recoveryState.asStateFlow()

    /** A cooldown can only be requested against the session it would unlock. */
    fun canStartRecovery(): Boolean = activeSessionId() > 0L

    /**
     * Starts the cooldown, or leaves an already running one untouched.
     *
     * Idempotent by design: Home and the blocking overlay both expose the entry point, and a
     * second press must not discard progress already served.
     *
     * Rejected when no session is active: an idle countdown would have nothing to bind its
     * completion proof to and would otherwise pre-authorise the unlock of a future session.
     * Check [canStartRecovery] before offering the control.
     */
    fun startRecovery(): RecoveryState {
        val outcome = synchronized(lock) {
            when {
                prefs.getBoolean(KEY_IS_ACTIVE, false) -> StartOutcome.ALREADY_RUNNING
                activeSessionId() <= 0L -> StartOutcome.REJECTED_IDLE
                else -> {
                    writeFreshLedger()
                    StartOutcome.STARTED
                }
            }
        }
        if (outcome == StartOutcome.REJECTED_IDLE) {
            Log.w(TAG, "Recovery request rejected: no active session to unlock")
            return _recoveryState.value
        }
        if (outcome == StartOutcome.STARTED) {
            Log.i(TAG, "Recovery cooldown started: ${COOLDOWN_DURATION_MS}ms of real elapsed time required")
        } else {
            Log.i(TAG, "Recovery already running; start request ignored (progress preserved)")
        }
        // Re-ticks so the caller sees a current remaining value and the periodic tick is armed.
        onTick()
        return _recoveryState.value
    }

    /**
     * Credits elapsed time, completes the cooldown when due and rearms the next tick.
     * Safe to call from anywhere and at any frequency; extra calls credit zero.
     */
    fun onTick() {
        val outcome = synchronized(lock) { advanceLocked() }
        outcome.nextTickDelayMs?.let { scheduler.scheduleNextTick(it) }
        if (!outcome.attemptUnlock) return

        val unlocked = attemptUnlock()
        val retire = synchronized(lock) {
            // SessionManager cancels its singleton engine on a successful unlock, which shares
            // this persisted ledger but may use a different scheduler (e.g. an injected engine).
            // Always retire this engine's alarm as well, even if that cancellation cleared the
            // active flag before we reacquired the ledger lock.
            if (unlocked && prefs.getBoolean(KEY_IS_ACTIVE, false)) {
                prefs.edit().putBoolean(KEY_IS_ACTIVE, false).commit()
            }
            _recoveryState.value = loadState()
            unlocked
        }
        if (retire) {
            scheduler.cancel()
        } else if (!unlocked) {
            // Without this the completed-but-refused state would never be revisited.
            scheduler.scheduleNextTick(UNLOCK_RETRY_INTERVAL_MS)
        }
    }

    /**
     * Re-arms the tick alarm the reboot discarded and ticks once.
     *
     * Deliberately grants no credit of its own: the tick recognises the reboot from the kernel
     * boot id or the monotonic regression, both unforgeable. A duplicate or spoofed boot
     * broadcast therefore cannot advance the countdown at all, which is why no de-duplication
     * state is needed. Called from `BootReceiver`.
     */
    fun onBootDetected() {
        onTick()
        ensureTickScheduled()
    }

    /** Name used by the architecture doc; delegates to [onBootDetected]. */
    fun onRebootDetected() = onBootDetected()

    /** Arms the periodic tick if a countdown is running. Cheap and idempotent. */
    fun ensureTickScheduled() {
        val delay = synchronized(lock) {
            if (!prefs.getBoolean(KEY_IS_ACTIVE, false)) null
            else tickDelayFor(remainingMsLocked())
        }
        delay?.let { scheduler.scheduleNextTick(it) }
    }

    /**
     * Persistent proof that a full 24h cooldown was served. Set before the unbrick is attempted
     * and left set until [cancelRecovery] is called, so `SessionManager` can require it before
     * honouring an `UnbrickTrigger.RECOVERY_24H`.
     */
    fun hasCompletedRecovery(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    /**
     * Same proof, additionally bound to the session the cooldown was started against, so a stale
     * completion cannot authorise an unlock of a later session.
     */
    fun hasCompletedRecoveryFor(sessionId: Long): Boolean =
        hasCompletedRecovery() && prefs.getLong(KEY_COMPLETED_SESSION_ID, -1L) == sessionId

    /** Session id the running or completed cooldown belongs to, or -1 when unknown. */
    fun guardedSessionId(): Long = prefs.getLong(KEY_COMPLETED_SESSION_ID, -1L)

    fun isRecoveryActive(): Boolean = prefs.getBoolean(KEY_IS_ACTIVE, false)

    fun remainingMs(): Long = synchronized(lock) { remainingMsLocked() }

    /** Ticks, then exposes the state flow. Kept for existing callers. */
    fun getRecoveryState(): StateFlow<RecoveryState> {
        onTick()
        return recoveryStateFlow
    }

    /**
     * Clears the countdown and the completion proof, and cancels the tick alarm.
     * Called by `SessionManager` when a new session starts and after a successful unlock.
     */
    fun cancelRecovery() {
        synchronized(lock) {
            prefs.edit()
                .putBoolean(KEY_IS_ACTIVE, false)
                .putLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)
                .putLong(KEY_LAST_ELAPSED_MS, 0L)
                .putBoolean(KEY_COMPLETED, false)
                .putLong(KEY_COMPLETED_SESSION_ID, -1L)
                .commit()

            _recoveryState.value = RecoveryState(
                isActive = false,
                remainingMs = 0L,
                cumulativeElapsedMs = 0L,
                bootId = prefs.getString(KEY_BOOT_ID, "") ?: "",
                isComplete = false
            )
        }
        scheduler.cancel()
    }

    private enum class StartOutcome { STARTED, ALREADY_RUNNING, REJECTED_IDLE }

    private class TickOutcome(val attemptUnlock: Boolean, val nextTickDelayMs: Long?)

    private fun advanceLocked(): TickOutcome {
        val isActive = prefs.getBoolean(KEY_IS_ACTIVE, false)
        if (!isActive) {
            _recoveryState.value = loadState()
            return TickOutcome(attemptUnlock = false, nextTickDelayMs = null)
        }

        val ledger = loadLedgerLocked()
        val currentElapsed = timeProvider.elapsedRealtime()
        val currentToken = BootIdentityProvider.currentBootToken()
        // bootEvidence = false, always: see RecoveryAccounting.isNewBootEpoch. Only the kernel
        // boot id and a monotonic regression are trusted, so no broadcast can grant credit and
        // repeated broadcasts are structurally incapable of double-crediting.
        val advanced = RecoveryAccounting.advance(
            ledger = ledger,
            currentElapsedMs = currentElapsed,
            currentBootToken = currentToken,
            bootEvidence = false
        )
        val isComplete = advanced.cumulativeElapsedMs >= COOLDOWN_DURATION_MS

        // commit(), not apply(): losing this write to a crash would either lose served time or
        // re-credit it. The payload is a handful of longs.
        val editor = prefs.edit()
            .putLong(KEY_CUMULATIVE_ELAPSED_MS, advanced.cumulativeElapsedMs)
            .putLong(KEY_LAST_ELAPSED_MS, advanced.anchorElapsedMs)
            .putString(KEY_BOOT_ID, advanced.bootToken)
            .putBoolean(KEY_COMPLETED, prefs.getBoolean(KEY_COMPLETED, false) || isComplete)
        editor.commit()

        _recoveryState.value = loadState()
        return TickOutcome(
            attemptUnlock = isComplete,
            nextTickDelayMs = if (isComplete) null else tickDelayFor(remainingMsLocked())
        )
    }

    private fun attemptUnlock(): Boolean {
        val manager = sessionManager ?: SessionManager.getInstance(context)
        return try {
            runBlocking { manager.unbrick(UnbrickTrigger.RECOVERY_24H) }
            val unlocked = !manager.isBricked()
            if (unlocked) {
                Log.i(TAG, "24h recovery served; session unlocked")
            } else {
                Log.w(TAG, "24h recovery served but unbrick was refused; will retry on next tick")
            }
            unlocked
        } catch (t: Throwable) {
            Log.e(TAG, "Recovery unbrick failed; will retry on next tick", t)
            false
        }
    }

    /** Non-blocking StateFlow read; safe inside the ledger critical section. */
    private fun activeSessionId(): Long =
        (sessionManager ?: runCatching { SessionManager.getInstance(context) }.getOrNull())
            ?.activeSession?.value?.id ?: -1L

    private fun writeFreshLedger() {
        val activeSessionId = activeSessionId()
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, true)
            .putLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)
            .putLong(KEY_LAST_ELAPSED_MS, timeProvider.elapsedRealtime())
            .putString(KEY_BOOT_ID, BootIdentityProvider.currentBootToken())
            .putBoolean(KEY_COMPLETED, false)
            .putLong(KEY_COMPLETED_SESSION_ID, activeSessionId)
            .commit()

        _recoveryState.value = loadState()
    }

    private fun loadLedgerLocked(): RecoveryLedger = RecoveryLedger(
        cumulativeElapsedMs = prefs.getLong(KEY_CUMULATIVE_ELAPSED_MS, 0L),
        anchorElapsedMs = prefs.getLong(KEY_LAST_ELAPSED_MS, timeProvider.elapsedRealtime()),
        bootToken = prefs.getString(KEY_BOOT_ID, BootIdentityProvider.NO_IDENTITY) ?: BootIdentityProvider.NO_IDENTITY
    )

    private fun remainingMsLocked(): Long =
        if (!prefs.getBoolean(KEY_IS_ACTIVE, false)) 0L
        else (COOLDOWN_DURATION_MS - prefs.getLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)).coerceAtLeast(0L)

    private fun tickDelayFor(remainingMs: Long): Long =
        minOf(TICK_INTERVAL_MS, remainingMs.coerceAtLeast(1_000L))

    private fun loadState(): RecoveryState {
        val isActive = prefs.getBoolean(KEY_IS_ACTIVE, false)
        val cumulative = prefs.getLong(KEY_CUMULATIVE_ELAPSED_MS, 0L)
        return RecoveryState(
            isActive = isActive,
            remainingMs = if (isActive) (COOLDOWN_DURATION_MS - cumulative).coerceAtLeast(0L) else 0L,
            cumulativeElapsedMs = cumulative,
            bootId = prefs.getString(KEY_BOOT_ID, "") ?: "",
            isComplete = prefs.getBoolean(KEY_COMPLETED, false)
        )
    }
}
