package com.gamemonitor.android.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/** 基于 UsageStatsManager.queryEvents 的前台应用检测（增量查询 + 事件回溯）。 */
class ForegroundDetector(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private var current: String? = null
    private var lastQueryWall: Long = 0L
    private val event = UsageEvents.Event()

    fun detect(nowWall: Long): String? {
        val manager = usageStatsManager ?: return current
        val begin = if (lastQueryWall > 0L) lastQueryWall - 1000L else nowWall - 10_000L
        lastQueryWall = nowWall
        val events = runCatching { manager.queryEvents(begin, nowWall) }
            .onFailure { Log.w(TAG, "queryEvents failed", it) }
            .getOrNull() ?: return current
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (current == event.packageName) current = null
            }
        }
        return current?.takeIf { it != context.packageName }
    }

    companion object {
        private const val TAG = "GameMonitor.Foreground"
    }
}
