package com.brick.app.core.common

import java.util.concurrent.atomic.AtomicLong

/**
 * Controllable [TimeProvider] for testing and debug instrumentation.
 * Allows independent manipulation of monotonic elapsed real time and wall-clock time,
 * verifying 24-hour recovery anti-tamper security and mid-cooldown reboot survival.
 *
 * @param initialCurrentTimeMillis Starting wall-clock time in ms (default: arbitrary epoch timestamp)
 * @param initialElapsedRealtime Starting monotonic elapsed time in ms (default: 10,000 ms)
 */
class TestTimeProvider(
    private val initialCurrentTimeMillis: Long = 1_700_000_000_000L,
    private val initialElapsedRealtime: Long = 10_000L
) : TimeProvider {

    private val currentWallClock = AtomicLong(initialCurrentTimeMillis)
    private val currentElapsed = AtomicLong(initialElapsedRealtime)

    override fun currentTimeMillis(): Long = currentWallClock.get()

    override fun elapsedRealtime(): Long = currentElapsed.get()

    /**
     * Advances both monotonic elapsed real time and wall-clock time by [deltaMs].
     * Simulates normal real-time progression while the device is running.
     *
     * @param deltaMs Milliseconds to advance (must be >= 0)
     */
    fun advanceTime(deltaMs: Long) {
        require(deltaMs >= 0) { "deltaMs must be non-negative: $deltaMs" }
        currentElapsed.addAndGet(deltaMs)
        currentWallClock.addAndGet(deltaMs)
    }

    /**
     * Advances ONLY monotonic elapsed real time by [deltaMs].
     *
     * @param deltaMs Milliseconds to advance (must be >= 0)
     */
    fun advanceElapsedRealtime(deltaMs: Long) {
        require(deltaMs >= 0) { "deltaMs must be non-negative: $deltaMs" }
        currentElapsed.addAndGet(deltaMs)
    }

    /**
     * Advances ONLY wall-clock time by [deltaMs].
     * Simulates a user manually advancing the device clock in Settings to attempt bypass.
     *
     * @param deltaMs Milliseconds to adjust (positive or negative)
     */
    fun advanceWallClock(deltaMs: Long) {
        currentWallClock.addAndGet(deltaMs)
    }

    /**
     * Explicitly sets wall-clock time to [newTimeMillis].
     */
    fun setWallClock(newTimeMillis: Long) {
        currentWallClock.set(newTimeMillis)
    }

    /**
     * Explicitly sets monotonic elapsed real time to [newElapsedMs].
     */
    fun setElapsedRealtime(newElapsedMs: Long) {
        require(newElapsedMs >= 0) { "elapsedMs must be non-negative: $newElapsedMs" }
        currentElapsed.set(newElapsedMs)
    }

    /**
     * Simulates a device reboot:
     * - Resets [elapsedRealtime] to [postBootElapsedMs] (e.g. 1000 ms post-boot).
     * - Optionally advances wall-clock time by [powerOffDurationMs] (time device was powered down).
     */
    fun simulateReboot(
        postBootElapsedMs: Long = 1_000L,
        powerOffDurationMs: Long = 30_000L
    ) {
        require(postBootElapsedMs >= 0) { "postBootElapsedMs must be non-negative: $postBootElapsedMs" }
        currentElapsed.set(postBootElapsedMs)
        currentWallClock.addAndGet(powerOffDurationMs)
    }

    /**
     * Resets the clock to its initial starting values.
     */
    fun reset() {
        currentWallClock.set(initialCurrentTimeMillis)
        currentElapsed.set(initialElapsedRealtime)
    }

    companion object {
        @Volatile
        private var INSTANCE: TestTimeProvider? = null

        fun getInstance(): TestTimeProvider {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TestTimeProvider().also { INSTANCE = it }
            }
        }

        fun setInstance(provider: TestTimeProvider?) {
            synchronized(this) {
                INSTANCE = provider
            }
        }
    }
}
