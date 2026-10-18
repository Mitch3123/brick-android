package com.brick.app.core.policy

import com.brick.app.core.database.BrickSessionEntity
import com.brick.app.core.database.ModeEntity
import com.brick.app.core.database.ModeType

/**
 * Evaluates whether a given package should be blocked based on active mode policy.
 */
class AppBlockPolicy(
    private val launcherPackages: Set<String> = emptySet()
) {

    fun shouldBlockPackage(
        packageName: String,
        session: BrickSessionEntity?,
        activeMode: ModeEntity?
    ): Boolean {
        // Not bricked -> never block
        if (session == null || !session.isActive) return false

        // System whitelist check (phone, emergency, Brick app)
        if (SystemWhitelist.isWhitelisted(packageName)) return false

        // Home launcher check -> never block launcher
        if (launcherPackages.contains(packageName)) return false

        // If no active mode configured, default to not blocking
        if (activeMode == null) return false

        return when (activeMode.modeType) {
            ModeType.BLOCK_LIST -> {
                // In block-list: block if package is explicitly in the blocked set
                activeMode.packageNames.contains(packageName)
            }
            ModeType.ALLOW_LIST -> {
                // In allow-list: block if package is NOT in the allowed set
                !activeMode.packageNames.contains(packageName)
            }
        }
    }
}
