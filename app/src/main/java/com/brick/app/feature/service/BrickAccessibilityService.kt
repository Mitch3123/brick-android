package com.brick.app.feature.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.brick.app.core.database.BrickDatabase
import com.brick.app.core.policy.AppBlockPolicy
import com.brick.app.core.policy.SystemWhitelist
import com.brick.app.feature.home.SessionManager
import com.brick.app.feature.interception.BlockingOverlayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Real-time Accessibility Service that monitors foreground window changes (TYPE_WINDOW_STATE_CHANGED)
 * and immediately intercepts launch of blocked packages while bricked.
 * Also enforces Strict Mode anti-tamper if user attempts to navigate to Settings to disable/uninstall Brick.
 */
class BrickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BrickAccessibility"
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var sessionManager: SessionManager
    private lateinit var appBlockPolicy: AppBlockPolicy
    private val launcherPackages = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "BrickAccessibilityService connected and active")

        sessionManager = SessionManager.getInstance(applicationContext)

        // Resolve installed home launcher packages
        resolveHomeLaunchers()

        appBlockPolicy = AppBlockPolicy(launcherPackages)

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
        }
        serviceInfo = info
    }

    private fun resolveHomeLaunchers() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            for (info in resolveInfos) {
                launcherPackages.add(info.activityInfo.packageName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving home launchers: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank()) return

        // 1. Strict Mode Anti-Tamper Protection
        if (sessionManager.isBricked() && sessionManager.isStrictModeActive()) {
            if (packageName == SETTINGS_PACKAGE) {
                // If navigating to Settings while strict mode is active, kick user to Home screen
                Log.w(TAG, "Strict mode active: intercepting Settings navigation to prevent tamper")
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // 2. Block Policy Evaluation
        if (sessionManager.isBricked()) {
            val session = sessionManager.activeSession.value
            val activeMode = sessionManager.activeMode.value

            val shouldBlock = appBlockPolicy.shouldBlockPackage(
                packageName = packageName,
                session = session,
                activeMode = activeMode
            )

            if (shouldBlock) {
                Log.i(TAG, "Blocking package launch: $packageName")
                launchBlockingOverlay(packageName, activeMode?.name ?: "Focus Mode")
            }
        }
    }

    private fun launchBlockingOverlay(blockedPackage: String, modeName: String) {
        val intent = Intent(this, BlockingOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BlockingOverlayActivity.EXTRA_BLOCKED_PACKAGE, blockedPackage)
            putExtra(BlockingOverlayActivity.EXTRA_MODE_NAME, modeName)
        }
        startActivity(intent)

        // Increment blocked attempts in database asynchronously
        serviceScope.launch(Dispatchers.IO) {
            val session = sessionManager.activeSession.value
            if (session != null) {
                BrickDatabase.getInstance(applicationContext).sessionDao().incrementBlockedAttempts(session.id)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "BrickAccessibilityService interrupted")
    }
}
