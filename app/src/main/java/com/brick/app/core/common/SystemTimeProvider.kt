package com.brick.app.core.common

import android.os.SystemClock

/**
 * Production implementation of [TimeProvider] backed by the Android OS system clocks.
 */
class SystemTimeProvider : TimeProvider {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
