package com.gamemonitor.android.domain

import android.util.Log
import com.gamemonitor.android.data.AppSettings
import com.gamemonitor.android.data.GameRepository
import com.gamemonitor.android.data.MonitoredApp
import com.gamemonitor.android.data.Snapshot
import com.gamemonitor.android.data.TimeUtils
import com.gamemonitor.android.data.UsageRecord
import com.gamemonitor.android.service.EnforcementManager
import com.gamemonitor.android.service.Notifier
import com.gamemonitor.android.service.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 单点状态机：每秒一次 tick，按“前台应用 + 已用时长 + 时段规则”推进状态，
 * 时间推进全部基于 SystemClock.elapsedRealtime() 差值，不用定时器累加。
 */
class MonitorEngine(
    private val repo: GameRepository,
    private val enforcement: EnforcementManager,
    private val overlay: OverlayController,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private class SessionState {
        var state: MonitorState = MonitorState.IDLE
        var sessionSeconds: Long = 0
        var pendingMs: Long = 0

        /** 当前轮次的管理端加时（秒），进入新一轮或跨天时清零。 */
        var bonusSeconds: Long = 0
        var cooldownUntilRealtime: Long = 0
        var countdownEndRealtime: Long = 0
        var homeAtRealtime: Long = 0
        var dismissAtRealtime: Long = 0
        var blockApplied: Boolean = false
    }

    private val sessions = mutableMapOf<String, SessionState>()
    private val usage = mutableMapOf<String, UsageRecord>()
    private var initialized = false
    private var lastTickRealtime = 0L
    private var currentPackage: String? = null
    private var overlayKey: String? = null
    private var today: String = TimeUtils.todayKey()
    private val suspendedByUs = mutableSetOf<String>()
    private var lastFlushWall = 0L

    fun updateSnapshot(newSnapshot: Snapshot) {
        snapshot = newSnapshot
        if (!initialized) {
            usage.clear()
            newSnapshot.usage.forEach { usage[it.packageName] = it }
            today = TimeUtils.todayKey()
            initialized = true
        }
    }

    fun tick(foregroundPackage: String?, nowRealtime: Long, nowWall: Long) {
        try {
            if (!initialized) return
            if (lastTickRealtime == 0L) lastTickRealtime = nowRealtime
            val deltaMs = (nowRealtime - lastTickRealtime).coerceIn(0L, MAX_TICK_DELTA_MS)
            lastTickRealtime = nowRealtime

            rotateDayIfNeeded(nowWall)

            val foreground = foregroundPackage?.takeIf { pkg ->
                snapshot.apps.any { it.packageName == pkg && it.enabled }
            }

            if (foreground != currentPackage) {
                currentPackage?.let { onLeave(it, nowWall) }
                if (foreground != null) onEnter(foreground, nowWall, nowRealtime)
                currentPackage = foreground
            }

            if (foreground != null) {
                val st = sessionOf(foreground)
                if (st.state == MonitorState.RUNNING ||
                    st.state == MonitorState.COUNTING_DOWN
                ) {
                    accumulate(foreground, st, deltaMs, nowWall)
                }
                evaluate(foreground, nowWall, nowRealtime)
            } else {
                dismissOverlay()
            }

            reconcileSuspensions(nowWall, nowRealtime)
            flushIfDue(nowWall)
        } catch (t: Throwable) {
            Log.e(TAG, "tick failed", t)
        }
    }

    /**
     * 管理端加时：只作用于当前轮次，抬高本轮的单次上限与当日配额阈值，
     * 不新增第二套计时来源；若正处于倒计时，立即恢复计时并撤掉倒计时浮层。
     */
    fun extendTime(packageName: String, minutes: Int) {
        if (!initialized) return
        val seconds = minutes.coerceIn(1, MAX_EXTENSION_MINUTES).toLong() * 60L
        val st = sessionOf(packageName)
        st.bonusSeconds += seconds

        if (st.state == MonitorState.COUNTING_DOWN) {
            st.state = MonitorState.RUNNING
            st.countdownEndRealtime = 0L
            st.blockApplied = false
            st.homeAtRealtime = 0
            st.dismissAtRealtime = 0
            dismissOverlay()
        }
        flushNow()
    }

    /** 服务停止时调用：落盘并清理浮层。 */
    fun onEngineStop() {
        try {
            flushNow()
            dismissOverlay()
        } catch (t: Throwable) {
            Log.e(TAG, "onEngineStop failed", t)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    private fun onEnter(packageName: String, nowWall: Long, nowRealtime: Long) {
        val rec = usageOf(packageName, nowWall)
        usage[packageName] = rec.copy(
            sessions = rec.sessions + 1,
            firstOpenMillis = rec.firstOpenMillis ?: nowWall
        )
        sessionOf(packageName).also {
            it.blockApplied = false
            it.homeAtRealtime = 0
        }
    }

    private fun onLeave(packageName: String, nowWall: Long) {
        val rec = usage[packageName]
        if (rec != null) usage[packageName] = rec.copy(lastCloseMillis = nowWall)

        val st = sessions[packageName]
        if (st != null) {
            // 倒计时不随切出暂停或取消：保留 COUNTING_DOWN 与绝对结束时间，切回时按真实剩余继续。
            if (st.state == MonitorState.BLOCKED_BY_SCHEDULE || st.state == MonitorState.QUOTA_EXHAUSTED) {
                st.state = MonitorState.IDLE
            }
            st.homeAtRealtime = 0
            st.blockApplied = false
        }
        dismissOverlay()
        flushNow()
    }

    private fun accumulate(packageName: String, st: SessionState, deltaMs: Long, nowWall: Long) {
        st.pendingMs += deltaMs
        val wholeSeconds = st.pendingMs / 1000
        if (wholeSeconds <= 0) return
        st.pendingMs -= wholeSeconds * 1000
        st.sessionSeconds += wholeSeconds
        val rec = usageOf(packageName, nowWall)
        usage[packageName] = rec.copy(usedSeconds = rec.usedSeconds + wholeSeconds)
    }

    // ---------------------------------------------------------------- 核心状态推进

    private fun evaluate(packageName: String, nowWall: Long, nowRealtime: Long) {
        val app = snapshot.appOf(packageName) ?: return
        val st = sessionOf(packageName)
        val settings = snapshot.settings
        val rec = usageOf(packageName, nowWall)

        // 无返回桌面能力时，浮层只提示若干秒，避免用户被永久遮罩
        if (st.dismissAtRealtime > 0 && nowRealtime >= st.dismissAtRealtime) {
            st.dismissAtRealtime = 0
            dismissOverlay()
        }

        // 休息期结束
        if (st.cooldownUntilRealtime > 0 && nowRealtime >= st.cooldownUntilRealtime) {
            st.cooldownUntilRealtime = 0
            st.sessionSeconds = 0
            st.pendingMs = 0
            if (st.state == MonitorState.COOLDOWN) {
                st.state = MonitorState.IDLE
                dismissOverlay()
            }
        }

        val scheduleBlocked = ScheduleMatcher.isBlocked(snapshot.rules, nowWall)
        val quotaUsedUp = app.dailyQuotaMinutes > 0 &&
            rec.usedSeconds >= app.dailyQuotaMinutes * 60L + st.bonusSeconds
        val totalLimit = settings.totalDailyLimitMinutes
        val totalUsedUp = totalLimit > 0 &&
            totalUsedSeconds(nowWall) >= totalLimit * 60L + st.bonusSeconds
        val inCooldown = st.cooldownUntilRealtime > nowRealtime

        when {
            scheduleBlocked -> {
                setState(st, MonitorState.BLOCKED_BY_SCHEDULE)
                val text = render(
                    settings.blockedText, app, null, null, TimeUtils.clockText(nowWall)
                )
                enforceBlocked(packageName, st, "schedule", text, settings, nowRealtime)
            }

            quotaUsedUp || totalUsedUp -> {
                setState(st, MonitorState.QUOTA_EXHAUSTED)
                val text = "「${app.label}」今日可用时长已用尽，明天再来"
                enforceBlocked(packageName, st, "quota", text, settings, nowRealtime)
            }

            inCooldown -> {
                setState(st, MonitorState.COOLDOWN)
                val remainMinutes = ((st.cooldownUntilRealtime - nowRealtime) + 59_999) / 60_000
                val text = render(
                    settings.cooldownText, app, remainMinutes.toInt(), null, TimeUtils.clockText(nowWall)
                )
                enforceBlocked(packageName, st, "cooldown", text, settings, nowRealtime)
            }

            else -> {
                if (st.state == MonitorState.IDLE ||
                    st.state == MonitorState.COOLDOWN ||
                    st.state == MonitorState.QUOTA_EXHAUSTED ||
                    st.state == MonitorState.BLOCKED_BY_SCHEDULE
                ) {
                    st.state = MonitorState.RUNNING
                    st.blockApplied = false
                    dismissOverlay()
                }
                advanceSession(packageName, app, st, settings, nowWall, nowRealtime)
            }
        }
    }

    private fun advanceSession(
        packageName: String,
        app: MonitoredApp,
        st: SessionState,
        settings: AppSettings,
        nowWall: Long,
        nowRealtime: Long,
    ) {
        val hasSingleLimit = app.singleLimitMinutes > 0
        val limitSeconds = app.singleLimitMinutes * 60L + st.bonusSeconds
        when (st.state) {
            MonitorState.RUNNING -> {
                // 取消宽限：单次到点直接进入倒计时；倒计时设为 0 时立即执行拦截。
                if (hasSingleLimit && st.sessionSeconds >= limitSeconds) {
                    if (settings.countdownSeconds <= 0) {
                        blockSession(packageName, app, st, settings, nowWall, nowRealtime)
                    } else {
                        st.state = MonitorState.COUNTING_DOWN
                        st.countdownEndRealtime = nowRealtime + settings.countdownSeconds * 1000L
                        st.blockApplied = false
                        val text = render(settings.countdownText, app, null, settings.countdownSeconds, null)
                        showOverlay("countdown", text, settings)
                    }
                }
            }

            MonitorState.COUNTING_DOWN -> {
                val remain = ((st.countdownEndRealtime - nowRealtime) / 1000L).coerceAtLeast(0L)
                val text = render(settings.countdownText, app, null, remain.toInt(), null)
                showOverlay("countdown", text, settings)
                if (nowRealtime >= st.countdownEndRealtime) {
                    blockSession(packageName, app, st, settings, nowWall, nowRealtime)
                }
            }

            else -> Unit
        }
    }

    /** 单次时长用尽：重置单次计时、进入休息期并执行拦截动作。 */
    private fun blockSession(
        packageName: String,
        app: MonitoredApp,
        st: SessionState,
        settings: AppSettings,
        nowWall: Long,
        nowRealtime: Long,
    ) {
        st.sessionSeconds = 0
        st.pendingMs = 0
        st.bonusSeconds = 0
        st.blockApplied = false
        st.homeAtRealtime = 0
        usage[packageName]?.let { usage[packageName] = it.copy(lastCloseMillis = nowWall) }

        val interval = app.intervalMinutes
        if (interval > 0) {
            st.cooldownUntilRealtime = nowRealtime + interval * 60_000L
            st.state = MonitorState.COOLDOWN
        } else {
            st.cooldownUntilRealtime = 0
            st.state = MonitorState.IDLE
        }

        val text = if (interval > 0) {
            render(settings.cooldownText, app, interval, null, TimeUtils.clockText(nowWall))
        } else {
            "「${app.label}」本次可用时长已用尽"
        }

        st.blockApplied = true
        if (enforcement.setSuspended(packageName, true)) {
            suspendedByUs.add(packageName)
            dismissOverlay()
            notifier.alert(text)
        } else {
            if (enforcement.canOverlay()) {
                showOverlay(if (interval > 0) "cooldown" else "blocked", text, settings)
                if (!enforcement.isAccessibilityEnabled()) {
                    st.dismissAtRealtime = nowRealtime + 4_000L
                }
            } else {
                notifier.alert(text)
            }
            if (enforcement.isAccessibilityEnabled()) {
                st.homeAtRealtime = nowRealtime + HOME_DELAY_MS
            }
        }
        flushNow()
    }

    /** 禁止状态：设备所有者静默挂起优先，否则浮层提示 + 返回桌面。 */
    private fun enforceBlocked(
        packageName: String,
        st: SessionState,
        key: String,
        text: String,
        settings: AppSettings,
        nowRealtime: Long,
    ) {
        if (!st.blockApplied) {
            st.blockApplied = true
            st.homeAtRealtime = 0
            if (enforcement.setSuspended(packageName, true)) {
                suspendedByUs.add(packageName)
                dismissOverlay()
                notifier.alert(text)
                return
            }
            if (enforcement.canOverlay()) {
                showOverlay(key, text, settings)
                if (!enforcement.isAccessibilityEnabled()) {
                    st.dismissAtRealtime = nowRealtime + 4_000L
                }
            } else {
                notifier.alert(text)
            }
            if (enforcement.isAccessibilityEnabled()) {
                st.homeAtRealtime = nowRealtime + HOME_DELAY_MS
            }
        } else if (overlayKey == key) {
            overlay.update(key, text, settings)
        }

        if (st.homeAtRealtime > 0 && nowRealtime >= st.homeAtRealtime) {
            st.homeAtRealtime = 0
            dismissOverlay()
            if (!enforcement.goHome()) {
                if (settings.autoForceStopEnabled && enforcement.requestForceStop(packageName, true)) {
                    Log.i(TAG, "auto force-stop session requested for $packageName")
                } else {
                    notifier.alert("无法返回桌面（无障碍服务未就绪）。$text")
                }
            }
        }
    }

    private fun setState(st: SessionState, target: MonitorState) {
        if (st.state != target) {
            st.state = target
            st.blockApplied = false
            st.homeAtRealtime = 0
        }
    }

    // ---------------------------------------------------------------- 休息期到点解除挂起

    private fun reconcileSuspensions(nowWall: Long, nowRealtime: Long) {
        if (suspendedByUs.isEmpty()) return
        val scheduleBlocked = ScheduleMatcher.isBlocked(snapshot.rules, nowWall)
        val iterator = suspendedByUs.iterator()
        while (iterator.hasNext()) {
            val pkg = iterator.next()
            val app = snapshot.appOf(pkg)
            if (app == null || !app.enabled) {
                enforcement.setSuspended(pkg, false)
                iterator.remove()
                continue
            }
            val rec = usage[pkg]
            val bonus = sessions[pkg]?.bonusSeconds ?: 0L
            val quotaUsedUp = app.dailyQuotaMinutes > 0 &&
                (rec?.usedSeconds ?: 0L) >= app.dailyQuotaMinutes * 60L + bonus
            val totalLimit = snapshot.settings.totalDailyLimitMinutes
            val totalUsedUp = totalLimit > 0 &&
                totalUsedSeconds(nowWall) >= totalLimit * 60L + bonus
            val cooldownActive = sessions[pkg]?.cooldownUntilRealtime?.let { it > nowRealtime } == true
            val restricted = scheduleBlocked || quotaUsedUp || totalUsedUp || cooldownActive
            if (!restricted) {
                enforcement.setSuspended(pkg, false)
                iterator.remove()
            }
        }
    }

    // ---------------------------------------------------------------- 日期切换

    private fun rotateDayIfNeeded(nowWall: Long) {
        val key = TimeUtils.todayKey(nowWall)
        if (key == today) return
        today = key
        usage.clear()
        sessions.values.forEach {
            it.sessionSeconds = 0
            it.pendingMs = 0
            it.bonusSeconds = 0
            it.state = MonitorState.IDLE
            it.cooldownUntilRealtime = 0
            it.blockApplied = false
            it.homeAtRealtime = 0
        }
        suspendedByUs.forEach { enforcement.setSuspended(it, false) }
        suspendedByUs.clear()
        dismissOverlay()
        flushNow()
    }

    // ---------------------------------------------------------------- 输出 & 工具

    private fun showOverlay(
        key: String,
        text: String,
        settings: AppSettings,
    ) {
        if (!enforcement.canOverlay()) return
        if (overlayKey == key) {
            overlay.update(key, text, settings)
        } else {
            overlay.show(key, text, settings)
            overlayKey = key
        }
    }

    private fun dismissOverlay() {
        if (overlayKey == null) return
        overlay.dismiss()
        overlayKey = null
    }

    private fun render(
        template: String,
        app: MonitoredApp,
        minutes: Int?,
        seconds: Int?,
        clock: String?,
    ): String = template
        .replace("{app}", app.label)
        .replace("{minutes}", (minutes ?: app.singleLimitMinutes).toString())
        .replace("{seconds}", (seconds ?: 0).toString())
        .replace("{clock}", clock ?: TimeUtils.clockText())

    private fun usageOf(packageName: String, nowWall: Long): UsageRecord =
        usage.getOrPut(packageName) { UsageRecord(packageName, TimeUtils.todayKey(nowWall)) }

    private fun sessionOf(packageName: String): SessionState =
        sessions.getOrPut(packageName) { SessionState() }

    private fun totalUsedSeconds(nowWall: Long): Long =
        usage.values.sumOf { it.usedSeconds }

    private fun flushIfDue(nowWall: Long) {
        if (nowWall - lastFlushWall >= FLUSH_INTERVAL_MS) flushNow()
    }

    private fun flushNow() {
        lastFlushWall = System.currentTimeMillis()
        val records = usage.values.toList()
        scope.launch {
            runCatching { repo.saveUsage(records) }
                .onFailure { Log.w(TAG, "saveUsage failed", it) }
        }
    }

    companion object {
        private const val TAG = "GameMonitor.Engine"
        private const val MAX_TICK_DELTA_MS = 3_000L
        private const val HOME_DELAY_MS = 2_000L
        private const val FLUSH_INTERVAL_MS = 10_000L
        private const val MAX_EXTENSION_MINUTES = 720
    }
}
