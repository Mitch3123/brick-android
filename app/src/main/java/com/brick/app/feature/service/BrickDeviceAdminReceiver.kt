package com.brick.app.feature.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DeviceAdminReceiver to enforce Strict Mode anti-uninstall while bricked.
 */
class BrickDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "BrickDeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled for Strict Mode")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling device admin while bricked is prevented by Strict Mode."
    }
}
