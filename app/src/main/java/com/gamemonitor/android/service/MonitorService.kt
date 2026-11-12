package com.gamemonitor.android.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gamemonitor.android.GameMonitorApp
import com.gamemonitor.android.domain.InstalledAppsProvider
import com.gamemonitor.android.domain.MonitorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 常驻前台服务：每秒 tick 一次，驱动 MonitorEngine。 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "GameMonitor.Service"
        private const val TICK_INTERVAL_MS = 1000L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var instance: MonitorService? = null

        /** 界面请求给某条目加时；服务未运行时返回 false。 */
        fun requestExtension(packageName: String, minutes: Int): Boolean {
            val service = instance ?: return false
            service.handler.post {
                runCatching { service.engine.extendTime(packageName, minutes) }
                    .onFailure { Log.w(TAG, "extendTime failed", it) }
            }
            return true
        }

        @Volatile
        var lastForegroundLabel: String = "-"
            private set

        /** 是否已设置管理密码（决定常驻通知是否挂「管理」action）。 */
        @Volatile
        private var pinConfigured: Boolean = false

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, MonitorService::class.java)
                )
            }.onFailure { Log.w("GameMonitor", "start service failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
        }

        /** 隐藏/恢复图标后立即刷新常驻通知；服务未运行时只撤掉遗留通知。 */
        fun refreshNotification(context: Context) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (!running) {
                    nm.cancel(Notifier.MONITOR_NOTIFICATION_ID)
                    return
                }
                val hidden = LauncherIconController(context).isHidden()
                nm.notify(
                    Notifier.MONITOR_NOTIFICATION_ID,
                    Notifier(context).ongoing(lastForegroundLabel, hidden && pinConfigured)
                )
            }.onFailure { Log.w(TAG, "refreshNotification failed", it) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var engine: MonitorEngine
    private lateinit var detector: ForegroundDetector
    private lateinit var notifier: Notifier
    private lateinit var powerManager: PowerManager
    private lateinit var launcherIcon: LauncherIconController
    private var notificationKey: String = ""

    private val tickRunnable = Runnable { doTick() }

    override fun onCreate() {
        super.onCreate()
        running = true
        try {
            notifier = Notifier(this).also { it.ensureChannels() }
            launcherIcon = LauncherIconController(this)
            powerManager = getSystemService(PowerManager::class.java)
            detector = ForegroundDetector(this)
            val app = application as GameMonitorApp
            engine = MonitorEngine(
                repo = app.repository,
                enforcement = EnforcementManager(this),
                overlay = OverlayController(this),
                notifier = notifier,
                scope = scope,
            )
            instance = this
            startForegroundSafely()
            scope.launch {
                app.repository.snapshotFlow.collectLatest { snap -> engine.updateSnapshot(snap) }
            }
            scope.launch {
                val configured = app.pinManager.isPinSet()
                pinConfigured = configured
                Log.i(TAG, "admin PIN configured=$configured")
                refreshNotification(this@MonitorService)
            }
            handler.postDelayed(tickRunnable, 500L)
        } catch (t: Throwable) {
            Log.e(TAG, "service onCreate failed", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Android 14 起前台服务必须带类型；用 ServiceCompat 兼容旧版本。 */
    private fun startForegroundSafely() {
        val notification = notifier.ongoing("启动中…", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Notifier.MONITOR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifier.MONITOR_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        running = false
        instance = null
        handler.removeCallbacks(tickRunnable)
        runCatching { engine.onEngineStop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun doTick() {
        try {
            val nowWall = System.currentTimeMillis()
            val nowRealtime = SystemClock.elapsedRealtime()
            // 只依赖 UsageStatsManager 判定前台，不再用无障碍事件兜底（避免全系统窗口监听）
            val foreground = if (powerManager.isInteractive) detector.detect(nowWall) else null
            engine.tick(foreground, nowRealtime, nowWall)
            val label = foreground
                ?.let { pkg -> runCatching { InstalledAppsProvider.labelOf(this, pkg) }.getOrNull() }
                ?: "桌面 / 无关应用"
            lastForegroundLabel = label
            updateNotification(label)
        } catch (t: Throwable) {
            Log.e(TAG, "tick failed", t)
        } finally {
            if (running) handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        }
    }

    private fun updateNotification(label: String) {
        val hidden = launcherIcon.isHidden()
        val key = "$label|$hidden|$pinConfigured"
        if (key == notificationKey) return
        notificationKey = key
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(
                Notifier.MONITOR_NOTIFICATION_ID,
                notifier.ongoing(label, hidden && pinConfigured)
            )
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }
}
