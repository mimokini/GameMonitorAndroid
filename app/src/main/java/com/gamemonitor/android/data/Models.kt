package com.gamemonitor.android.data

import java.util.UUID

enum class ScheduleType { ALLOW, BLOCK }

/** 被监控的一个应用条目。 */
data class MonitoredApp(
    val packageName: String,
    val label: String,
    /** 单次时长（分钟），0 = 不限 */
    val singleLimitMinutes: Int = 0,
    /** 每日配额（分钟），0 = 不限 */
    val dailyQuotaMinutes: Int = 0,
    /** 单次用尽后的休息间隔（分钟），0 = 无休息期 */
    val intervalMinutes: Int = 0,
    val enabled: Boolean = true,
    /** 越小越优先 */
    val order: Int = 0,
)

/** 时段规则：开放时段 / 禁止时段。跨零点由 start > end 表示。 */
data class ScheduleRule(
    val id: String = UUID.randomUUID().toString(),
    val type: ScheduleType = ScheduleType.BLOCK,
    /** 一天内的分钟偏移 0..1439 */
    val startMinute: Int = 8 * 60,
    val endMinute: Int = 22 * 60,
    /** ISO 周几：1=周一 .. 7=周日 */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val enabled: Boolean = true,
) {
    fun startText(): String = "%02d:%02d".format(startMinute / 60, startMinute % 60)
    fun endText(): String = "%02d:%02d".format(endMinute / 60, endMinute % 60)
}

/** 某应用某一天的用量。date = yyyy-MM-dd。 */
data class UsageRecord(
    val packageName: String,
    val date: String,
    val usedSeconds: Long = 0L,
    val sessions: Int = 0,
    val firstOpenMillis: Long? = null,
    val lastCloseMillis: Long? = null,
)

/** 全局设置 + 三类文案样式。 */
data class AppSettings(
    /** 所有条目合计的每日总限额（分钟），0 = 不限 */
    val totalDailyLimitMinutes: Int = 0,
    /** 单次时长用尽后直接进入倒计时（秒） */
    val countdownSeconds: Int = 10,
    val countdownText: String = "「{app}」将在 {seconds} 秒后关闭",
    val cooldownText: String = "注意休息，{minutes} 分钟后可再次开启「{app}」",
    val blockedText: String = "当前时段（{clock}）不允许使用「{app}」",
    val fontSizeSp: Int = 20,
    /** ARGB */
    val textColor: Long = 0xFFFFFFFF,
    /** 背景不透明度 0..100（%） */
    val bgAlphaPercent: Int = 90,
    /** 是否允许无障碍自动点击“强行停止”（默认关闭，风险较高） */
    val autoForceStopEnabled: Boolean = false,
)

data class Snapshot(
    val apps: List<MonitoredApp> = emptyList(),
    val rules: List<ScheduleRule> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val usage: List<UsageRecord> = emptyList(),
) {
    fun usageOf(packageName: String): UsageRecord? = usage.firstOrNull { it.packageName == packageName }

    fun appOf(packageName: String?): MonitoredApp? =
        packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }

    fun totalUsedSeconds(): Long = usage.sumOf { it.usedSeconds }
}
