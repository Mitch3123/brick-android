package com.brick.app.core.common

/**
 * Interface abstracting time sources to enable deterministic unit and integration testing.
 * Provides both wall-clock time and monotonic elapsed real time.
 */
interface TimeProvider {

    /**
     * Returns the current wall-clock time in milliseconds since Unix epoch (Jan 1, 1970 UTC).
     * Note: Subject to NTP adjustments and manual user date/time changes.
     */
    fun currentTimeMillis(): Long

    /**
     * Returns milliseconds since system boot, including deep sleep.
     * Note: Monotonic and immune to wall-clock manipulation, but resets to 0 upon device reboot.
     */
    fun elapsedRealtime(): Long
}
