package com.gamemonitor.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后自动拉起监控服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> MonitorService.start(context)
        }
    }
}
