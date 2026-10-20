package com.brick.app.core.security

import android.util.Log
import java.io.File

/**
 * Supplies a boot-unique identity so the recovery ledger can tell "the device rebooted" from
 * "the monotonic clock went backwards for some other reason".
 *
 * The kernel's random boot id is the only identity available to an unprivileged app that is
 * (a) genuinely regenerated on every boot and (b) not derived from the wall clock. Anything
 * wall-clock derived (e.g. `currentTimeMillis - elapsedRealtime`) would let a user forge a fake
 * reboot by changing the device date, which is precisely the bypass the cooldown must resist.
 *
 * When the file is unreadable the provider returns [NO_IDENTITY] and the caller must fall back to
 * evidence it can still trust: a monotonic clock regression, or a real `BOOT_COMPLETED` broadcast.
 */
object BootIdentityProvider {

    const val NO_IDENTITY = ""

    private const val TAG = "BootIdentity"
    private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

    @Volatile
    private var cached: String? = null

    /**
     * Kernel boot id, or [NO_IDENTITY] when unavailable.
     *
     * Cached for the lifetime of the process: the value cannot change without a reboot, and a
     * reboot necessarily ends the process.
     */
    fun currentBootToken(): String {
        cached?.let { return it }
        val token = synchronized(this) {
            cached ?: readBootId().also { cached = it }
        }
        return token
    }

    private fun readBootId(): String = try {
        val file = File(BOOT_ID_PATH)
        if (file.canRead()) file.readText().trim() else NO_IDENTITY
    } catch (t: Throwable) {
        Log.w(TAG, "Kernel boot id unavailable: ${t.message}")
        NO_IDENTITY
    }
}
