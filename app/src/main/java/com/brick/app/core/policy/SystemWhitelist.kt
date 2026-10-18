package com.brick.app.core.policy

/**
 * Whitelist of system-critical applications and services that must NEVER be blocked
 * under any circumstances to prevent device bricking, soft-locks, or dialing failure.
 */
object SystemWhitelist {

    val CRITICAL_PACKAGES = setOf(
        "com.brick.app",
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.apps.safetyhub"
    )

    fun isWhitelisted(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName == "com.brick.app") return true
        if (CRITICAL_PACKAGES.contains(packageName)) return true
        
        // System phone/dialer/emergency packages
        if (packageName.endsWith(".dialer") || 
            packageName.contains("telecom") || 
            packageName.contains("emergency")) {
            return true
        }

        return false
    }
}
