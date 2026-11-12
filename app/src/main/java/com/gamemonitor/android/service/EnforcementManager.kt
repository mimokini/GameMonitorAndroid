package com.gamemonitor.android.service

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/** 权限探测 + 分级拦截能力的统一入口。 */
class EnforcementManager(private val context: Context) {

    private val dpm: DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private val admin: ComponentName = ComponentName(context, MonitorDeviceAdminReceiver::class.java)

    val packageName: String = context.packageName

    /** 是否已是设备所有者（可静默挂起应用）。 */
    fun isDeviceOwner(): Boolean = runCatching {
        dpm?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    /** admin receiver 是否处于激活状态。 */
    fun hasActiveAdmin(): Boolean = runCatching {
        dpm?.isAdminActive(admin) == true
    }.getOrDefault(false)

    /** 设备所有者下挂起 / 解除挂起目标应用；失败返回 false。 */
    fun setSuspended(targetPackage: String, suspended: Boolean): Boolean {
        val manager = dpm ?: return false
        if (!isDeviceOwner()) return false
        return runCatching {
            manager.setPackagesSuspended(admin, arrayOf(targetPackage), suspended)
            true
        }.onFailure { Log.w(TAG, "setPackagesSuspended($targetPackage,$suspended) failed", it) }
            .getOrDefault(false)
    }

    fun isSuspended(targetPackage: String): Boolean = runCatching {
        dpm?.isPackageSuspended(admin, targetPackage) == true
    }.getOrDefault(false)

    /** 悬浮窗权限。 */
    fun canOverlay(): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** 无障碍服务是否已启用。 */
    fun isAccessibilityEnabled(): Boolean = runCatching {
        val component = ComponentName(context, GameAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }.getOrDefault(false)

    /** 使用情况访问权限（UsageStatsManager 必需）。 */
    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.PACKAGE_USAGE_STATS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun canPostNotifications(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }.getOrDefault(true)

    /** 返回桌面（需要无障碍服务）。 */
    fun goHome(): Boolean {
        val service = GameAccessibilityService.instance ?: return false
        return runCatching { service.goHome() }.getOrDefault(false)
    }

    /** 兜底：打开应用详情页并尝试点击“强行停止”（仅 autoForceStopEnabled 开启时生效）。 */
    fun requestForceStop(targetPackage: String, enabled: Boolean): Boolean {
        val service = GameAccessibilityService.instance ?: return false
        return runCatching {
            service.requestForceStop(targetPackage, context, enabled)
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 跳转

    fun openUsageAccessSettings() = safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun openAccessibilitySettings() = safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openOverlaySettings() = safeStart(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
    )

    fun openBatteryOptimizationSettings() = safeStart(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    )

    fun openAppNotificationSettings() = safeStart(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    )

    private fun safeStart(intent: Intent) {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "startActivity failed: $intent", it) }
    }

    companion object {
        private const val TAG = "GameMonitor.Enforce"
        const val DEVICE_OWNER_COMMAND =
            "adb shell dpm set-device-owner com.gamemonitor.android/.service.MonitorDeviceAdminReceiver"
    }
}
