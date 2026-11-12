package com.gamemonitor.android.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 设备所有者载体：用于 DevicePolicyManager.setPackagesSuspended。 */
class MonitorDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
    }

    companion object {
        private const val TAG = "GameMonitor.Admin"
    }
}
