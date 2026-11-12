package com.gamemonitor.android.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务只做两件事：
 * 1) `GLOBAL_ACTION_HOME` 返回桌面；
 * 2) 在用户显式开启「自动强行停止」后，于本应用自己打开的系统设置“应用详情页”内点击一次「强行停止」。
 *
 * 严格作用域：
 * - 仅在 [requestForceStop] 记录成功后的 15 秒窗口内工作；
 * - 前台包必须是系统设置白名单（见 [settingsPackages]），否则立即跳过、不做任何动作；
 * - 不做手势注入、不做滚动、不做无条件的全局动作；事件类型只处理窗口状态变化。
 */
class GameAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GameMonitor.A11y"
        private const val FORCE_STOP_WINDOW_MS = 15_000L
        private const val CLICK_INTERVAL_MS = 2_000L

        /** 各 ROM 的系统设置 / 应用管理包名白名单。 */
        val settingsPackages: Set<String> = setOf(
            "com.android.settings",
            "com.google.android.settings",
            "com.miui.securitycenter",
            "com.coloros.settings",
            "com.oplus.settings",
            "com.vivo.settings",
            "com.huawei.systemmanager",
            "com.samsung.android.settings",
        )

        @Volatile
        var instance: GameAccessibilityService? = null
            private set
    }

    private var pendingPackage: String? = null
    private var pendingAt: Long = 0L
    private var lastClickAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility connected, scope=settings-only on explicit request")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val target = pendingPackage ?: return
            val elapsed = SystemClock.elapsedRealtime() - pendingAt
            if (elapsed > FORCE_STOP_WINDOW_MS) {
                pendingPackage = null
                Log.i(TAG, "force-stop window expired after ${elapsed}ms, abort. target=$target")
                return
            }
            val pkg = event.packageName?.toString() ?: return
            if (pkg !in settingsPackages) {
                Log.i(TAG, "foreground=$pkg not a settings package, skip auto action. target=$target")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (lastClickAt > 0 && now - lastClickAt < CLICK_INTERVAL_MS) return
            clickForceStop(pkg, target)
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent failed", t)
        }
    }

    fun goHome(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)

    /**
     * 开始一次“自动强行停止”会话。
     * @param enabled 来自设置项 [com.gamemonitor.android.data.AppSettings.autoForceStopEnabled]，默认 false。
     *                关闭时直接返回 false，不打开任何页面、不做任何点击。
     */
    fun requestForceStop(targetPackage: String, context: Context, enabled: Boolean): Boolean {
        if (!enabled) {
            Log.i(TAG, "auto force-stop disabled, skip. target=$targetPackage")
            return false
        }
        return runCatching {
            pendingPackage = targetPackage
            pendingAt = SystemClock.elapsedRealtime()
            lastClickAt = 0L
            Log.i(TAG, "force-stop session started, target=$targetPackage, window=${FORCE_STOP_WINDOW_MS}ms")
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$targetPackage")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure { Log.w(TAG, "requestForceStop failed", it) }.getOrDefault(false)
    }

    private fun clickForceStop(settingsPackage: String, targetPackage: String) {
        val root = rootInActiveWindow ?: return
        if (!isTargetDetailsPage(root, targetPackage)) {
            Log.i(TAG, "not target details page yet. foreground=$settingsPackage target=$targetPackage")
            return
        }
        val stopNode = findFirst(root, "强行停止") ?: findFirst(root, "Force stop")
        if (stopNode == null) {
            Log.i(TAG, "force-stop node not found. foreground=$settingsPackage target=$targetPackage")
            return
        }
        val clickable = clickableAncestor(stopNode) ?: stopNode
        if (!clickable.isEnabled) return

        val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        lastClickAt = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "ACTION_CLICK force-stop, foreground=$settingsPackage target=$targetPackage " +
                "node=${stopNode.text}, clicked=$clicked"
        )
        // 只处理一次，避免反复点击
        pendingPackage = null
        if (!clicked) return

        val dialogRoot = rootInActiveWindow ?: root
        val confirm = findFirst(dialogRoot, "确定") ?: findFirst(dialogRoot, "OK") ?: return
        val confirmClickable = clickableAncestor(confirm) ?: confirm
        if (confirmClickable.isEnabled) {
            val ok = confirmClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(
                TAG,
                "ACTION_CLICK force-stop confirm, foreground=$settingsPackage " +
                    "node=${confirm.text}, clicked=$ok"
            )
        }
    }

    private fun isTargetDetailsPage(root: AccessibilityNodeInfo, targetPackage: String): Boolean =
        runCatching {
            val rootPkg = root.packageName?.toString()
            val inSettings = rootPkg == null || rootPkg in settingsPackages
            inSettings && root.findAccessibilityNodeInfosByText(targetPackage).isNotEmpty()
        }.getOrDefault(false)

    private fun findFirst(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        runCatching { root.findAccessibilityNodeInfosByText(text).firstOrNull() }.getOrNull()

    private fun clickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }
}
