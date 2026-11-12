package com.gamemonitor.android.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** 通过 activity-alias 控制桌面图标显示 / 隐藏。 */
class LauncherIconController(private val context: Context) {

    private val alias = ComponentName(context.packageName, ALIAS_CLASS)

    fun isHidden(): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(alias) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)

    fun hide(): Boolean = setEnabled(false)

    fun restore(): Boolean = setEnabled(true)

    private fun setEnabled(enabled: Boolean): Boolean {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        return runCatching {
            context.packageManager.setComponentEnabledSetting(
                alias, state, PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, if (enabled) "launcher alias ENABLED" else "launcher alias DISABLED")
            true
        }.onFailure { Log.e(TAG, "setComponentEnabledSetting failed", it) }
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "GameMonitor.Launcher"
        const val ALIAS_CLASS = "com.gamemonitor.android.LauncherAlias"
        const val ADB_RESTORE_COMMAND =
            "adb shell pm enable com.gamemonitor.android/.LauncherAlias"
    }
}
